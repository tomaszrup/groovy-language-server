////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Tomasz Rup
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Tomasz Rup
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

import java.net.URI;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.MemoryProfiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages TTL-based and memory-pressure-based eviction of heavy state
 * from idle {@link ProjectScope}s.  Extracted from {@link ProjectScopeManager}
 * to keep each class under 1000 lines.
 */
class ScopeEvictionManager {

	private static final Logger logger = LoggerFactory.getLogger(ScopeEvictionManager.class);

	private final Supplier<List<ProjectScope>> projectScopesSupplier;
	private final FileContentsTracker fileContentsTracker;

	/**
	 * TTL in seconds for scope eviction. When a scope hasn't been accessed
	 * for longer than this duration, its heavy state is evicted. 0 disables.
	 */
	private volatile long scopeEvictionTTLSeconds = 300;

	/**
	 * Heap usage ratio (0.0–1.0) above which emergency scope eviction is
	 * triggered. Default: 0.75 (75%). Can be lowered to evict sooner under
	 * memory pressure, or raised to allow more aggressive memory use.
	 */
	private volatile double memoryPressureThreshold = 0.75;

	/** Handle for the periodic eviction sweep, cancelled on shutdown. */
	private final AtomicReference<ScheduledFuture<?>> evictionFuture = new AtomicReference<>();

	ScopeEvictionManager(Supplier<List<ProjectScope>> projectScopesSupplier,
						 FileContentsTracker fileContentsTracker) {
		this.projectScopesSupplier = projectScopesSupplier;
		this.fileContentsTracker = fileContentsTracker;
	}

	// --- TTL / threshold accessors ---

	/**
	 * Set the scope eviction TTL. 0 disables eviction.
	 */
	void setScopeEvictionTTLSeconds(long ttlSeconds) {
		this.scopeEvictionTTLSeconds = ttlSeconds;
		logger.info("Scope eviction TTL set to {} seconds", ttlSeconds);
	}

	long getScopeEvictionTTLSeconds() {
		return scopeEvictionTTLSeconds;
	}

	/**
	 * Set the heap usage ratio above which emergency scope eviction triggers.
	 * Valid range: 0.3–0.95. Values outside this range are clamped.
	 */
	void setMemoryPressureThreshold(double threshold) {
		this.memoryPressureThreshold = Math.max(0.3, Math.min(0.95, threshold));
		logger.info("Memory pressure threshold set to {} %", (int) (this.memoryPressureThreshold * 100));
	}

	double getMemoryPressureThreshold() {
		return memoryPressureThreshold;
	}

	// --- Scheduler lifecycle ---

	/**
	 * Start the periodic eviction scheduler. Should be called once after
	 * the server is fully initialized.
	 *
	 * @param schedulingPool the shared scheduling pool from ExecutorPools
	 */
	void startEvictionScheduler(ScheduledExecutorService schedulingPool) {
		if (scopeEvictionTTLSeconds <= 0) {
			logger.info("Scope eviction disabled (TTL=0)");
			return;
		}
		// Run sweep every 60 seconds
		long sweepIntervalSeconds = Math.max(30, scopeEvictionTTLSeconds / 5);
		evictionFuture.set(schedulingPool.scheduleAtFixedRate(
				this::performEvictionSweep,
				sweepIntervalSeconds,
				sweepIntervalSeconds,
				TimeUnit.SECONDS));
		logger.info("Scope eviction scheduler started (TTL={}s, sweep interval={}s)",
				scopeEvictionTTLSeconds, sweepIntervalSeconds);
	}

	/**
	 * Stop the eviction scheduler. Called on server shutdown.
	 */
	void stopEvictionScheduler() {
		ScheduledFuture<?> f = evictionFuture.getAndSet(null);
		if (f != null) {
			f.cancel(false);
		}
	}

	// --- Eviction sweep ---

	/**
	 * Periodic sweep that evicts heavy state from inactive scopes and
	 * cleans up expired entries from the closed-file cache.
	 *
	 * <p>In addition to TTL-based eviction, this sweep also performs
	 * <b>memory-pressure eviction</b>: when heap usage exceeds the
	 * configured {@link #memoryPressureThreshold}, the least-recently-accessed
	 * compiled scope (without open files) is evicted immediately regardless
	 * of TTL.  This acts as a safety valve to prevent OOM in large
	 * multi-project workspaces.</p>
	 *
	 * <p><b>Dynamic TTL scaling</b>: when heap usage is between 60% and the
	 * pressure threshold, the effective TTL is linearly scaled down to half
	 * its configured value. This causes idle scopes to be evicted sooner as
	 * memory fills up, before reaching the emergency eviction trigger.</p>
	 */
	private void performEvictionSweep() {
		// --- Periodic memory profiling (opt-in) ---
		List<ProjectScope> scopes = projectScopesSupplier.get();
		MemoryProfiler.logProfile(scopes);

		logExpiredClosedFileEntries();

		long ttlMs = scopeEvictionTTLSeconds * 1000;
		if (ttlMs <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		Set<URI> openURIs = fileContentsTracker.getOpenURIs();

		// --- Memory-pressure eviction ---
		double heapUsageRatio = getHeapUsageRatio();
		long usedBytes = getUsedHeapBytes();
		long maxBytes = Runtime.getRuntime().maxMemory();
		double threshold = memoryPressureThreshold;

		if (heapUsageRatio > threshold) {
			logger.warn("High memory pressure: heap at {}/{} MB ({} %, threshold {} %). "
					+ "Attempting emergency scope eviction.",
					usedBytes / (1024 * 1024), maxBytes / (1024 * 1024),
					(int) (heapUsageRatio * 100), (int) (threshold * 100));
			evictLeastRecentScope(openURIs, now);
		}

		// --- Dynamic TTL scaling ---
		// When heap usage is between 60% and the pressure threshold, linearly
		// scale TTL down to 50% of its configured value. This evicts idle
		// scopes sooner as memory fills up, reducing peak usage.
		long effectiveTtlMs = applyDynamicTtlScaling(ttlMs, heapUsageRatio, threshold, scopes);

		// --- Standard TTL-based eviction ---
		for (ProjectScope scope : scopes) {
			long idleMs = now - scope.getLastAccessedAt();
			if (isTtlEvictionCandidate(scope, openURIs, idleMs, effectiveTtlMs)) {
				// Evict under write lock
				scope.getLock().writeLock().lock();
				try {
					// Double-check under lock
					if (!scope.isEvicted() && scope.isCompiled()
							&& (now - scope.getLastAccessedAt()) >= ttlMs
							&& !hasOpenFilesInScope(scope, openURIs)) {
						logger.info("Evicting scope {} (idle for {}s)",
								scope.getProjectRoot(), idleMs / 1000);
						scope.evictHeavyState();
					}
				} finally {
					scope.getLock().writeLock().unlock();
				}
			}
		}
	}

	private void logExpiredClosedFileEntries() {
		int expired = fileContentsTracker.sweepExpiredClosedFileCache();
		if (expired > 0) {
			logger.debug("Swept {} expired closed-file cache entries", expired);
		}
	}

	private long getUsedHeapBytes() {
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}

	private double getHeapUsageRatio() {
		long maxBytes = Runtime.getRuntime().maxMemory();
		if (maxBytes <= 0) {
			return 0.0;
		}
		return (double) getUsedHeapBytes() / maxBytes;
	}

	private long applyDynamicTtlScaling(long ttlMs,
									 double heapUsageRatio,
									 double threshold,
									 List<ProjectScope> scopes) {
		long effectiveTtlMs = ttlMs;
		double scalingFloor = 0.60;
		if (heapUsageRatio > scalingFloor && heapUsageRatio <= threshold) {
			double scaleFactor = 1.0 - 0.5 * ((heapUsageRatio - scalingFloor) / (threshold - scalingFloor));
			effectiveTtlMs = (long) (ttlMs * scaleFactor);
			logger.debug("Dynamic TTL scaling: heap at {} %, effective TTL reduced to {}s (base {}s)",
					(int) (heapUsageRatio * 100), effectiveTtlMs / 1000, scopeEvictionTTLSeconds);
			clearAstReferenceIndexes(scopes);
		}
		return effectiveTtlMs;
	}

	private void clearAstReferenceIndexes(List<ProjectScope> scopes) {
		for (ProjectScope scope : scopes) {
			if (scope.getAstVisitor() != null) {
				scope.getAstVisitor().clearReferenceIndex();
			}
		}
	}

	private boolean isTtlEvictionCandidate(ProjectScope scope,
									 Set<URI> openURIs,
									 long idleMs,
									 long effectiveTtlMs) {
		return !scope.isEvicted()
				&& scope.isCompiled()
				&& idleMs >= effectiveTtlMs
				&& !hasOpenFilesInScope(scope, openURIs);
	}

	/**
	 * Evicts the least-recently-accessed compiled scope that has no open
	 * files.  Used as an emergency memory-pressure relief.
	 */
	private void evictLeastRecentScope(Set<URI> openURIs, long now) {
		ProjectScope lruScope = null;
		long oldestAccess = Long.MAX_VALUE;

		for (ProjectScope scope : projectScopesSupplier.get()) {
			if (isEmergencyEvictionCandidate(scope, openURIs)
					&& scope.getLastAccessedAt() < oldestAccess) {
				oldestAccess = scope.getLastAccessedAt();
				lruScope = scope;
			}
		}

		if (lruScope != null) {
			lruScope.getLock().writeLock().lock();
			try {
				if (!lruScope.isEvicted() && lruScope.isCompiled()
						&& !hasOpenFilesInScope(lruScope, openURIs)) {
					long idleMs = now - lruScope.getLastAccessedAt();
					logger.info("Memory-pressure eviction: scope {} (idle for {}s)",
							lruScope.getProjectRoot(), idleMs / 1000);
					lruScope.evictHeavyState();
				}
			} finally {
				lruScope.getLock().writeLock().unlock();
			}
		} else {
			logger.warn("Memory-pressure eviction: no eligible scopes to evict");
		}
	}

	private boolean isEmergencyEvictionCandidate(ProjectScope scope, Set<URI> openURIs) {
		return !scope.isEvicted() && scope.isCompiled() && !hasOpenFilesInScope(scope, openURIs);
	}

	/**
	 * Returns true if any of the given open URIs belong to the given scope.
	 */
	private boolean hasOpenFilesInScope(ProjectScope scope, Set<URI> openURIs) {
		if (openURIs.isEmpty() || scope.getProjectRoot() == null) {
			return false;
		}
		for (URI uri : openURIs) {
			try {
				if (Paths.get(uri).startsWith(scope.getProjectRoot())) {
					return true;
				}
			} catch (Exception e) {
				// ignore URIs that can't be converted to Path
			}
		}
		return false;
	}
}
