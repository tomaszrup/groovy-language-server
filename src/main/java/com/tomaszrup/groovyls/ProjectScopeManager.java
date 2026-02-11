////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.JavadocResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the creation, lookup, and lifecycle of {@link ProjectScope}s.
 * Handles multi-project registration, classpath updates, configuration
 * changes, and feature toggle settings.
 */
public class ProjectScopeManager {
	private static final Logger logger = LoggerFactory.getLogger(ProjectScopeManager.class);

	private volatile Path workspaceRoot;

	// Default scope (used when no build-tool projects are registered, e.g. in tests)
	private volatile ProjectScope defaultScope;
	// Per-project scopes, sorted by path length desc for longest-prefix match.
	// Volatile reference to an effectively-immutable list; swapped atomically
	// in addProjects(). Reads are lock-free.
	private volatile List<ProjectScope> projectScopes = Collections.emptyList();
	// True while a background project import (Gradle/Maven) is in progress.
	// When true, didOpen/didChange/didClose skip compilation on the defaultScope
	// to avoid wrong diagnostics (no classpath, entire workspace scanned).
	private volatile boolean importInProgress;

	private volatile boolean semanticHighlightingEnabled = true;
	private volatile boolean formattingEnabled = true;

	/**
	 * Tracks project roots whose classpath resolution is currently in-flight
	 * to prevent duplicate concurrent resolution requests for the same scope.
	 */
	private final Set<Path> resolutionInFlight = ConcurrentHashMap.newKeySet();

	/**
	 * Guards mutations to the {@link #projectScopes} list and
	 * {@link #defaultScope} reference. Held very briefly—only while
	 * swapping the list reference, not during compilation.
	 */
	private final Object scopesMutationLock = new Object();

	/**
	 * Cache for {@link #findProjectScope(URI)} results.  The URI → scope
	 * mapping is stable while the scope list is unchanged, so this avoids
	 * repeated linear scans on every LSP request (hover, completion,
	 * semantic tokens, inlay hints, etc.).
	 * <p>Invalidated whenever the scope list is mutated.</p>
	 */
	private final ConcurrentHashMap<URI, ProjectScope> scopeCache = new ConcurrentHashMap<>();

	private final FileContentsTracker fileContentsTracker;

	/** Supplier of language client — set after server connects. */
	private volatile LanguageClient languageClient;

	/**
	 * TTL in seconds for scope eviction. When a scope hasn't been accessed
	 * for longer than this duration, its heavy state is evicted. 0 disables.
	 */
	private volatile long scopeEvictionTTLSeconds = 300;

	/** Handle for the periodic eviction sweep, cancelled on shutdown. */
	private volatile ScheduledFuture<?> evictionFuture;

	public ProjectScopeManager(ICompilationUnitFactory defaultFactory, FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
		this.defaultScope = new ProjectScope(null, defaultFactory);
		// The default scope is not managed by a build tool, so its classpath
		// is always "resolved" (user-configured or empty).
		this.defaultScope.setClasspathResolved(true);
	}

	// --- Accessors ---

	public Path getWorkspaceRoot() {
		return workspaceRoot;
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		synchronized (scopesMutationLock) {
			this.workspaceRoot = workspaceRoot;
			ProjectScope ds = new ProjectScope(workspaceRoot, defaultScope.getCompilationUnitFactory());
			ds.setCompiled(false);
			ds.setClasspathResolved(true);
			this.defaultScope = ds;
			scopeCache.clear();
		}
	}

	public boolean isSemanticHighlightingEnabled() {
		return semanticHighlightingEnabled;
	}

	public boolean isFormattingEnabled() {
		return formattingEnabled;
	}

	public boolean isImportInProgress() {
		return importInProgress;
	}

	public void setImportInProgress(boolean inProgress) {
		this.importInProgress = inProgress;
	}

	public void setLanguageClient(LanguageClient client) {
		this.languageClient = client;
	}

	public ProjectScope getDefaultScope() {
		return defaultScope;
	}

	public List<ProjectScope> getProjectScopes() {
		return projectScopes;
	}

	// --- Scope lookup ---

	/**
	 * Find the project scope that owns the given URI.
	 * Returns the default scope when no project scopes are registered (backward
	 * compat for tests). Returns null if projects are registered but the file
	 * doesn't belong to any of them.
	 */
	public ProjectScope findProjectScope(URI uri) {
		if (!projectScopes.isEmpty() && uri != null) {
			// Fast path: check the cache first to avoid repeated linear scans
			ProjectScope cached = scopeCache.get(uri);
			if (cached != null) {
				cached.touchAccess();
				return cached;
			}
			Path filePath = Paths.get(uri);
			for (ProjectScope scope : projectScopes) {
				if (scope.getProjectRoot() != null && filePath.startsWith(scope.getProjectRoot())) {
					scopeCache.put(uri, scope);
					scope.touchAccess();
					logger.debug("findProjectScope({}) -> {}", uri, scope.getProjectRoot());
					return scope;
				}
			}
			logger.warn("findProjectScope({}) -> no matching project scope found", uri);
			return null;
		}
		return defaultScope;
	}

	/**
	 * Invalidate the {@link #findProjectScope(URI)} cache.  Must be called
	 * whenever the scope list is replaced or the workspace root changes.
	 */
	public void clearScopeCache() {
		scopeCache.clear();
	}

	/**
	 * Finds a project scope by its exact root path.
	 */
	public ProjectScope findProjectScopeByRoot(Path root) {
		for (ProjectScope scope : getAllScopes()) {
			if (root.equals(scope.getProjectRoot())) {
				return scope;
			}
		}
		return null;
	}

	/**
	 * Returns all active scopes: project scopes if any are registered,
	 * otherwise just the default scope.
	 */
	public List<ProjectScope> getAllScopes() {
		if (!projectScopes.isEmpty()) {
			return projectScopes;
		}
		return Collections.singletonList(defaultScope);
	}

	/**
	 * Returns true if the given scope should not yet be compiled because
	 * project discovery itself is still running.
	 * <p>
	 * This only gates during the <em>pre-discovery</em> phase (the default
	 * scope before any project scopes are registered and the background
	 * import thread is still running).  After discovery completes, scopes
	 * whose classpath has not been resolved yet are handled by the lazy
	 * resolution path in {@code didOpen()}/{@code didChange()} — they must
	 * <b>not</b> be blocked here, otherwise the coordinator's
	 * {@code requestResolution()} call is never reached.
	 */
	public boolean isImportPendingFor(ProjectScope scope) {
		// Pre-discovery phase: only the default scope exists and import is running
		if (importInProgress && scope == defaultScope && projectScopes.isEmpty()) {
			return true;
		}
		return false;
	}

	/**
	 * Mark a project root as having classpath resolution in-flight.
	 * Returns {@code true} if the root was not already in-flight (i.e.
	 * the caller should proceed with resolution). Returns {@code false}
	 * if resolution is already in progress for this root.
	 */
	public boolean markResolutionStarted(Path projectRoot) {
		return resolutionInFlight.add(projectRoot);
	}

	/**
	 * Mark a project root's classpath resolution as complete.
	 */
	public void markResolutionComplete(Path projectRoot) {
		resolutionInFlight.remove(projectRoot);
	}

	/**
	 * Returns true if classpath resolution is currently in-flight for the
	 * given project root.
	 */
	public boolean isResolutionInFlight(Path projectRoot) {
		return resolutionInFlight.contains(projectRoot);
	}

	// --- Project registration ---

	/**
	 * Register discovered project roots immediately, <b>before</b> classpath
	 * resolution.  Each scope is created with an empty classpath and
	 * {@code classpathResolved = false}.
	 */
	public void registerDiscoveredProjects(List<Path> projectRoots) {
		synchronized (scopesMutationLock) {
			logger.info("registerDiscoveredProjects called with {} projects", projectRoots.size());

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();

				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				factory.setExcludedSubRoots(excludedRoots);

				newScopes.add(new ProjectScope(projectRoot, factory));
			}

			newScopes.sort((a, b) -> b.getProjectRoot().toString().length() - a.getProjectRoot().toString().length());
			projectScopes = Collections.unmodifiableList(newScopes);
			scopeCache.clear();
		}

		clearDefaultScopeDiagnostics();
	}

	/**
	 * Update existing project scopes with their resolved classpaths.
	 * Called after background classpath resolution completes.
	 * Compilation is NOT triggered here &mdash; scopes compile lazily on
	 * first user interaction.
	 */
	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths) {
		logger.info("updateProjectClasspaths called with {} projects", projectClasspaths.size());
		List<ProjectScope> scopes = projectScopes;

		for (ProjectScope scope : scopes) {
			List<String> classpath = projectClasspaths.get(scope.getProjectRoot());
			if (classpath != null) {
				scope.getLock().writeLock().lock();
				try {
					scope.getCompilationUnitFactory()
							.setAdditionalClasspathList(classpath);
					scope.setClasspathResolved(true);
					// Index dependency source JARs for Go to Definition
					scope.updateSourceLocatorClasspath(classpath);
					// Classpath changed — evict stale Javadoc cache entries
					JavadocResolver.clearCache();

					if (scope.isCompiled()) {
						logger.info("Forcing recompilation of {} with resolved classpath",
								scope.getProjectRoot());
						scope.setCompiled(false);
						scope.setCompilationUnit(null);
						scope.setAstVisitor(null);
					}
				} finally {
					scope.getLock().writeLock().unlock();
				}
			}
		}
	}

	/**
	 * Update a single project scope with its resolved classpath.
	 * Called by the lazy on-demand resolution path when a user opens a file
	 * in a project whose classpath wasn't yet resolved.
	 *
	 * @param projectRoot the project root
	 * @param classpath   the resolved classpath entries
	 * @return the updated scope, or {@code null} if no matching scope was found
	 */
	public ProjectScope updateProjectClasspath(Path projectRoot, List<String> classpath) {
		ProjectScope scope = findProjectScopeByRoot(projectRoot);
		if (scope == null) {
			logger.warn("updateProjectClasspath: no scope found for {}", projectRoot);
			return null;
		}
		scope.getLock().writeLock().lock();
		try {
			scope.getCompilationUnitFactory().setAdditionalClasspathList(classpath);
			scope.setClasspathResolved(true);
			// Reset any previous OOM failure — the classpath change may
			// have reduced memory requirements (fewer deps) or the user
			// may have increased -Xmx.
			scope.resetCompilationFailed();
			// Index dependency source JARs for Go to Definition
			scope.updateSourceLocatorClasspath(classpath);
			JavadocResolver.clearCache();

			if (scope.isCompiled()) {
				logger.info("Forcing recompilation of {} with newly resolved classpath", projectRoot);
				scope.setCompiled(false);
				scope.setCompilationUnit(null);
				scope.setAstVisitor(null);
			}
		} finally {
			scope.getLock().writeLock().unlock();
		}
		logger.info("Updated classpath for project {} ({} entries)", projectRoot, classpath.size());
		return scope;
	}

	/**
	 * Register all build-tool projects at once with their resolved classpaths.
	 *
	 * @return set of open URIs that need compilation
	 */
	public Set<URI> addProjects(Map<Path, List<String>> projectClasspaths) {
		synchronized (scopesMutationLock) {
			logger.info("addProjects called with {} projects", projectClasspaths.size());
			List<Path> projectRoots = new ArrayList<>(projectClasspaths.keySet());
			for (Path p : projectRoots) {
				logger.info("  project root: {}, classpath entries: {}", p, projectClasspaths.get(p).size());
			}

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();
				List<String> classpath = projectClasspaths.get(projectRoot);
				factory.setAdditionalClasspathList(classpath);

				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				logger.info("  Project {}: excluding {} subproject root(s): {}", projectRoot, excludedRoots.size(),
						excludedRoots);
				factory.setExcludedSubRoots(excludedRoots);

				ProjectScope scope = new ProjectScope(projectRoot, factory);
				scope.setClasspathResolved(true);
				// Index dependency source JARs for Go to Definition
				scope.updateSourceLocatorClasspath(classpath);
				newScopes.add(scope);
			}

			newScopes.sort((a, b) -> b.getProjectRoot().toString().length() - a.getProjectRoot().toString().length());
			projectScopes = Collections.unmodifiableList(newScopes);
			scopeCache.clear();
		}

		clearDefaultScopeDiagnostics();
		return fileContentsTracker.getOpenURIs();
	}

	// --- Configuration ---

	public void updateFeatureToggles(JsonObject settings) {
		if (!settings.has("groovy") || !settings.get("groovy").isJsonObject()) {
			return;
		}
		JsonObject groovy = settings.get("groovy").getAsJsonObject();
		if (groovy.has("semanticHighlighting") && groovy.get("semanticHighlighting").isJsonObject()) {
			JsonObject sh = groovy.get("semanticHighlighting").getAsJsonObject();
			if (sh.has("enabled")) {
				this.semanticHighlightingEnabled = sh.get("enabled").getAsBoolean();
			}
		}
		if (groovy.has("formatting") && groovy.get("formatting").isJsonObject()) {
			JsonObject fmt = groovy.get("formatting").getAsJsonObject();
			if (fmt.has("enabled")) {
				this.formattingEnabled = fmt.get("enabled").getAsBoolean();
			}
		}
	}

	/**
	 * Parse classpath entries from the JSON settings object and delegate
	 * to {@link #updateClasspath(List)}.
	 */
	public void updateClasspathFromSettings(JsonObject settings) {
		List<String> classpathList = new ArrayList<>();

		if (settings.has("groovy") && settings.get("groovy").isJsonObject()) {
			JsonObject groovy = settings.get("groovy").getAsJsonObject();
			if (groovy.has("classpath") && groovy.get("classpath").isJsonArray()) {
				JsonArray classpath = groovy.get("classpath").getAsJsonArray();
				classpath.forEach(element -> {
					classpathList.add(element.getAsString());
				});
			}
		}

		updateClasspath(classpathList);
	}

	/**
	 * Update the default scope's classpath (used when no build-tool projects
	 * are registered).
	 */
	public void updateClasspath(List<String> classpathList) {
		if (!projectScopes.isEmpty()) {
			logger.debug("updateClasspath() ignored — {} project scope(s) are active", projectScopes.size());
			return;
		}
		if (importInProgress) {
			logger.info("updateClasspath() deferred — project import in progress");
			defaultScope.getCompilationUnitFactory().setAdditionalClasspathList(classpathList);
			return;
		}
		// Store the classpath but don't compile — the caller (CompilationService)
		// handles compilation when needed.
		ProjectScope ds = defaultScope;
		if (!classpathList.equals(ds.getCompilationUnitFactory().getAdditionalClasspathList())) {
			ds.getCompilationUnitFactory().setAdditionalClasspathList(classpathList);
			// Mark as needing recompilation
			ds.setCompiled(false);
		}
	}

	/**
	 * Returns true if the default scope classpath actually changed.
	 */
	public boolean hasClasspathChanged(List<String> classpathList) {
		return !classpathList.equals(defaultScope.getCompilationUnitFactory().getAdditionalClasspathList());
	}

	// --- Diagnostics helpers ---

	// --- Eviction ---

	/**
	 * Set the scope eviction TTL. 0 disables eviction.
	 */
	public void setScopeEvictionTTLSeconds(long ttlSeconds) {
		this.scopeEvictionTTLSeconds = ttlSeconds;
		logger.info("Scope eviction TTL set to {} seconds", ttlSeconds);
	}

	public long getScopeEvictionTTLSeconds() {
		return scopeEvictionTTLSeconds;
	}

	/**
	 * Start the periodic eviction scheduler. Should be called once after
	 * the server is fully initialized.
	 *
	 * @param schedulingPool the shared scheduling pool from ExecutorPools
	 */
	public void startEvictionScheduler(ScheduledExecutorService schedulingPool) {
		if (scopeEvictionTTLSeconds <= 0) {
			logger.info("Scope eviction disabled (TTL=0)");
			return;
		}
		// Run sweep every 60 seconds
		long sweepIntervalSeconds = Math.max(30, scopeEvictionTTLSeconds / 5);
		evictionFuture = schedulingPool.scheduleAtFixedRate(
				this::performEvictionSweep,
				sweepIntervalSeconds,
				sweepIntervalSeconds,
				TimeUnit.SECONDS);
		logger.info("Scope eviction scheduler started (TTL={}s, sweep interval={}s)",
				scopeEvictionTTLSeconds, sweepIntervalSeconds);
	}

	/**
	 * Periodic sweep that evicts heavy state from inactive scopes and
	 * cleans up expired entries from the closed-file cache.
	 *
	 * <p>In addition to TTL-based eviction, this sweep also performs
	 * <b>memory-pressure eviction</b>: when heap usage exceeds 75% of max
	 * heap, the least-recently-accessed compiled scope (without open files)
	 * is evicted immediately regardless of TTL.  This acts as a safety
	 * valve to prevent OOM in large multi-project workspaces.</p>
	 */
	private void performEvictionSweep() {
		// Sweep expired closed-file cache entries
		int expired = fileContentsTracker.sweepExpiredClosedFileCache();
		if (expired > 0) {
			logger.debug("Swept {} expired closed-file cache entries", expired);
		}

		long ttlMs = scopeEvictionTTLSeconds * 1000;
		if (ttlMs <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		Set<URI> openURIs = fileContentsTracker.getOpenURIs();

		// --- Memory-pressure eviction ---
		// When heap usage exceeds 75%, aggressively evict the least-recently
		// accessed compiled scope to reclaim memory before OOM.
		Runtime rt = Runtime.getRuntime();
		long usedBytes = rt.totalMemory() - rt.freeMemory();
		long maxBytes = rt.maxMemory();
		double heapUsageRatio = (double) usedBytes / maxBytes;
		if (heapUsageRatio > 0.75) {
			logger.warn("High memory pressure: heap at {}/{} MB ({} %). "
					+ "Attempting emergency scope eviction.",
					usedBytes / (1024 * 1024), maxBytes / (1024 * 1024),
					(int) (heapUsageRatio * 100));
			evictLeastRecentScope(openURIs, now);
		}

		// --- Standard TTL-based eviction ---
		for (ProjectScope scope : projectScopes) {
			if (scope.isEvicted() || !scope.isCompiled()) {
				continue;
			}

			long idleMs = now - scope.getLastAccessedAt();
			if (idleMs < ttlMs) {
				continue;
			}

			// Don't evict scopes that have open files
			if (hasOpenFilesInScope(scope, openURIs)) {
				continue;
			}

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

	/**
	 * Evicts the least-recently-accessed compiled scope that has no open
	 * files.  Used as an emergency memory-pressure relief.
	 */
	private void evictLeastRecentScope(Set<URI> openURIs, long now) {
		ProjectScope lruScope = null;
		long oldestAccess = Long.MAX_VALUE;

		for (ProjectScope scope : projectScopes) {
			if (scope.isEvicted() || !scope.isCompiled()) {
				continue;
			}
			if (hasOpenFilesInScope(scope, openURIs)) {
				continue;
			}
			if (scope.getLastAccessedAt() < oldestAccess) {
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

	/**
	 * Stop the eviction scheduler. Called on server shutdown.
	 */
	public void stopEvictionScheduler() {
		ScheduledFuture<?> f = evictionFuture;
		if (f != null) {
			f.cancel(false);
			evictionFuture = null;
		}
	}

	// --- Diagnostics helpers ---

	/**
	 * Publishes empty diagnostics for every file that defaultScope previously
	 * reported errors on, so the client clears them.
	 */
	public void clearDefaultScopeDiagnostics() {
		if (defaultScope.getPrevDiagnosticsByFile() != null && languageClient != null) {
			for (URI uri : defaultScope.getPrevDiagnosticsByFile().keySet()) {
				languageClient.publishDiagnostics(
						new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
			}
			defaultScope.setPrevDiagnosticsByFile(null);
		}
	}
}
