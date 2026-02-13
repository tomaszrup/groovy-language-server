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
package com.tomaszrup.groovyls.compiler;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraphException;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.util.MemoryProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A process-wide cache that shares {@link ScanResult} instances across
 * {@link com.tomaszrup.groovyls.GroovyServices.ProjectScope ProjectScope}s
 * that have identical classpaths.
 *
 * <p>In a typical Gradle multi-project workspace the majority of subprojects
 * share the same (or very similar) set of dependency JARs. Without sharing,
 * each scope would independently run a full ClassGraph scan — each scan
 * consuming 50–200 MB of heap. With 40 projects this would mean 2–8 GB of
 * duplicated data.
 *
 * <p>The cache is keyed by a SHA-256 hash of the <b>sorted</b> classpath
 * URLs of the {@link GroovyClassLoader}. This means two classloader instances
 * that happen to reference the same set of JARs (in any order) will share a
 * single {@code ScanResult}.
 *
 * <h3>Reference counting</h3>
 * Each cached entry maintains a reference count. Callers must:
 * <ol>
 *   <li>Call {@link #acquire(GroovyClassLoader)} to obtain a {@code ScanResult}
 *       and increment the ref count.</li>
 *   <li>Call {@link #release(ScanResult)} when the scope no longer needs it
 *       (e.g. the classpath changed or the server is shutting down).</li>
 * </ol>
 * When the ref count drops to zero the entry is evicted and the
 * {@code ScanResult} is closed.
 *
 * <p>This class is thread-safe.
 */
public class SharedClassGraphCache {

	private static final Logger logger = LoggerFactory.getLogger(SharedClassGraphCache.class);

	/**
	 * Maximum number of ClassGraph scan results to keep in memory at once.
	 * Each scan result consumes 50–200 MB of heap. When this limit is
	 * reached, zero-refcount entries are evicted before acquiring a new
	 * one.  This caps total ClassGraph memory at roughly
	 * {@code MAX_HELD_SCANS × 200 MB}.
	 */
	private static final int MAX_HELD_SCANS = 6;

	/**
	 * Max fraction of JVM heap that this cache is allowed to consume
	 * (estimated) before refusing to create a new in-memory scan.
	 */
	private static final double DEFAULT_HEAP_BUDGET_FRACTION = 0.35;

	/**
	 * Minimum estimated in-memory budget for scan cache, even on small heaps.
	 */
	private static final long MIN_BUDGET_BYTES = 256L * 1024L * 1024L;

	/**
	 * Version prefix for the disk cache key. Bump this whenever the ClassGraph
	 * scan configuration changes (e.g. adding/removing rejectPackages filters)
	 * so that stale cached results are naturally orphaned.
	 */
	private static final String CACHE_KEY_VERSION = "v4";

	/**
	 * Internal JDK implementation packages that are <b>always</b> rejected from
	 * ClassGraph scans. These are non-public APIs that should never appear in
	 * code completion. Not configurable.
	 */
	private static final String[] BASE_REJECTED_PACKAGES = {
		// --- JDK internal implementation packages (never useful) ---
		"sun.",
		"jdk.",
		"com.sun.proxy",
		"com.sun.crypto",
		"com.sun.org.apache",
		"com.sun.xml.internal",
		"com.sun.java.swing",
		"com.sun.imageio",
		"com.sun.media",
		"com.sun.naming",
		"com.sun.rowset",
		"com.sun.beans",
		"com.sun.corba",
		"com.sun.jmx",
		"com.sun.awt",
		"com.sun.swing",
		// Additional com.sun.* internal packages (not useful in Spring Boot / Java projects)
		"com.sun.jndi",      // JNDI SPI internals
		"com.sun.security",   // security SPI internals
		"com.sun.nio",        // NIO SPI internals
		"com.sun.net",        // internal net (httpserver is in com.sun.net.httpserver — rare)
		"com.sun.tools",      // JDK tools
		// --- Groovy's shaded internal dependencies (never useful in user code) ---
		"groovyjarjarantlr4",
		"groovyjarjarantlr",
		"groovyjarjarasm",
		"groovyjarjarpicocli",
		"groovyjarjarcommonscli"
	};

	/**
	 * Default list of <em>additional</em> public JDK packages to reject from
	 * ClassGraph scans. These are GUI, RMI, and other rarely-used packages
	 * that save ~15–20 MB per scan result. Configurable via the
	 * {@code groovy.memory.rejectedPackages} VS Code setting.
	 *
	 * <p>Users who need completions for any of these packages (e.g.
	 * {@code java.awt.Color} for PDF/Excel libraries) can remove entries
	 * from the setting.
	 */
	public static final List<String> DEFAULT_ADDITIONAL_REJECTED_PACKAGES = List.of(
		"javax.swing",
		"javax.sound",
		"javax.print",
		"javax.accessibility",
		"java.applet",
		"java.awt",
		"java.rmi",
		"javax.rmi",
		"javax.smartcardio",
		"org.ietf.jgss",
		"javax.security.sasl"
	);

	/** Singleton instance. */
	private static final SharedClassGraphCache INSTANCE = new SharedClassGraphCache();

	public static SharedClassGraphCache getInstance() {
		return INSTANCE;
	}

	/**
	 * User-configured additional packages to reject, set via
	 * {@code groovy.memory.rejectedPackages}. Defaults to
	 * {@link #DEFAULT_ADDITIONAL_REJECTED_PACKAGES}.
	 */
	private volatile List<String> additionalRejectedPackages =
			DEFAULT_ADDITIONAL_REJECTED_PACKAGES;

	/**
	 * Set the list of additional public JDK packages to reject from
	 * ClassGraph scans. These are merged with the hardcoded
	 * {@link #BASE_REJECTED_PACKAGES} at scan time.
	 *
	 * <p>Pass an empty list to disable additional filtering (only the
	 * base internal-JDK packages will be rejected).
	 *
	 * <p><b>Note:</b> changing this does <em>not</em> invalidate existing
	 * cached scan results. The cache key includes the rejected packages,
	 * so a new scan will be performed with the updated filter the next
	 * time {@link #acquire} is called with a classloader whose key does
	 * not match.</p>
	 *
	 * @param packages list of package prefixes to reject (e.g.
	 *                 {@code "javax.swing"}, {@code "java.awt"})
	 */
	public void setAdditionalRejectedPackages(List<String> packages) {
		this.additionalRejectedPackages = packages != null
				? Collections.unmodifiableList(new ArrayList<>(packages))
				: DEFAULT_ADDITIONAL_REJECTED_PACKAGES;
		logger.info("ClassGraph additional rejected packages: {}", this.additionalRejectedPackages);
	}

	/**
	 * Returns the merged array of all rejected packages (base + additional).
	 */
	private String[] getMergedRejectedPackages() {
		List<String> additional = this.additionalRejectedPackages;
		String[] merged = new String[BASE_REJECTED_PACKAGES.length + additional.size()];
		System.arraycopy(BASE_REJECTED_PACKAGES, 0, merged, 0, BASE_REJECTED_PACKAGES.length);
		for (int i = 0; i < additional.size(); i++) {
			merged[BASE_REJECTED_PACKAGES.length + i] = additional.get(i);
		}
		return merged;
	}

	/**
	 * A cached entry: the scan result (wrapped in a {@link SoftReference} so
	 * the GC can reclaim it under memory pressure) and its current reference
	 * count.  When the soft reference is cleared, the entry is transparently
	 * reloaded from the on-disk cache or re-scanned.
	 */
	/**
	 * Result of {@link #acquire}: the shared scan result plus the set of
	 * classpath files that belong to the acquiring scope.  When the scan
	 * result is a superset (shared across scopes with overlapping
	 * classpaths), consumers can use {@link #ownClasspathFiles} to filter
	 * {@code ScanResult.getAllClasses()} down to only the classes that
	 * are actually on the scope's classpath.
	 */
	public static final class AcquireResult {
		private final ScanResult scanResult;
		private final Set<File> ownClasspathFiles;
		private final boolean shared;

		AcquireResult(ScanResult scanResult, Set<File> ownClasspathFiles, boolean shared) {
			this.scanResult = scanResult;
			this.ownClasspathFiles = ownClasspathFiles;
			this.shared = shared;
		}

		/** The ClassGraph scan result (may be a superset of this scope's classpath). */
		public ScanResult getScanResult() { return scanResult; }

		/**
		 * The set of classpath files (JARs / directories) that belong to the
		 * acquiring scope.  {@code null} when the scan result is an exact match
		 * (no filtering needed).
		 */
		public Set<File> getOwnClasspathFiles() { return ownClasspathFiles; }

		/**
		 * Whether this scan result is shared as a superset across multiple
		 * scopes with overlapping (but not identical) classpaths.
		 */
		public boolean isShared() { return shared; }
	}

	static final class CacheEntry {
		volatile SoftReference<ScanResult> scanResultRef;
		final String classpathKey;
		/** The sorted set of classpath URL strings for this entry. */
		final Set<String> classpathUrls;
		int refCount;

		CacheEntry(ScanResult scanResult, String classpathKey, Set<String> classpathUrls) {
			this.scanResultRef = new SoftReference<>(scanResult);
			this.classpathKey = classpathKey;
			this.classpathUrls = classpathUrls;
			this.refCount = 1;
		}

		/**
		 * Returns the scan result, or {@code null} if the GC has cleared
		 * the soft reference.
		 */
		ScanResult get() {
			return scanResultRef.get();
		}

		/**
		 * Replace the scan result (e.g. after re-loading from disk).
		 */
		void set(ScanResult scanResult) {
			this.scanResultRef = new SoftReference<>(scanResult);
		}
	}

	/** Key = classpath hash → entry. */
	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	private long acquireRequests;
	private long exactHits;
	private long overlapHits;
	private long diskHits;
	private long scanBuilds;
	private long budgetRejects;

	/**
	 * Reverse lookup: ScanResult identity → cache key.  Needed so that
	 * {@link #release(ScanResult)} can find the entry without the caller
	 * having to remember the key.
	 */
	private final ConcurrentHashMap<ScanResult, String> reverseIndex = new ConcurrentHashMap<>();

	// Package-private for testing
	SharedClassGraphCache() {
	}

	/**
	 * Minimum overlap ratio between the requested classpath URLs and a cached
	 * entry's URLs for the cached entry to be reused.  A value of 0.75 means
	 * at least 75% of the requested URLs must appear in the cached entry.
	 *
	 * <p>In typical multi-module builds, parent and sub-module classpaths
	 * overlap by ~80–85% (differing only in the module's own output directory
	 * and 1–2 unique dependencies).  The per-scope filter ensures only classes
	 * from the scope's actual classpath appear in completions.</p>
	 */
	private static final double SUPERSET_OVERLAP_THRESHOLD = 0.75;

	/**
	 * Acquire a {@link ScanResult} for the given classloader.  Delegates to
	 * {@link #acquireWithResult(GroovyClassLoader)} and returns just the
	 * {@code ScanResult} for backward compatibility.
	 *
	 * @param classLoader the classloader whose classpath should be scanned
	 * @return the scan result, or {@code null} if scanning failed
	 */
	public synchronized ScanResult acquire(GroovyClassLoader classLoader) {
		AcquireResult ar = acquireWithResult(classLoader);
		return ar != null ? ar.getScanResult() : null;
	}

	/**
	 * Acquire a {@link ScanResult} for the given classloader, returning an
	 * {@link AcquireResult} that also carries the scope's own classpath
	 * files — needed for per-scope filtering when the scan result is a
	 * shared superset.
	 *
	 * <p><b>Similarity-based sharing:</b> if no exact cache hit is found,
	 * this method checks whether any existing cached entry's classpath is
	 * a <em>superset</em> of (or overlaps by &ge;{@value #SUPERSET_OVERLAP_THRESHOLD})
	 * the requested classpath.  If so, the existing entry is reused and
	 * marked as shared.  Consumers should then filter
	 * {@code ScanResult.getAllClasses()} using
	 * {@link AcquireResult#getOwnClasspathFiles}.</p>
	 *
	 * @param classLoader the classloader whose classpath should be scanned
	 * @return the acquire result, or {@code null} if scanning failed
	 */
	public synchronized AcquireResult acquireWithResult(GroovyClassLoader classLoader) {
		acquireRequests++;
		String key = computeClasspathKey(classLoader);
		Set<String> requestedUrls = extractUrlSet(classLoader);

		logger.info("SharedClassGraphCache acquireWithResult: {} URLs from classloader, "
				+ "cache has {} entries, key={}…",
				requestedUrls.size(), cache.size(),
				key.substring(0, Math.min(12, key.length())));

		// --- Exact cache hit ---
		CacheEntry entry = cache.get(key);
		if (entry != null) {
			ScanResult existing = entry.get();
			if (existing != null) {
				exactHits++;
				entry.refCount++;
				logger.debug("SharedClassGraphCache HIT for key {}… (refCount={}), cache size={}",
						key.substring(0, Math.min(12, key.length())), entry.refCount, cache.size());
				return new AcquireResult(existing, null, false);
			}
			// SoftReference was cleared by GC — reload from disk or rescan
			logger.info("SharedClassGraphCache: SoftReference cleared for key {}… — reloading",
					key.substring(0, Math.min(12, key.length())));
			reverseIndex.values().remove(key);
			ScanResult reloaded = loadFromDisk(key);
			if (reloaded != null) {
				diskHits++;
				entry.set(reloaded);
				entry.refCount++;
				reverseIndex.put(reloaded, key);
				logger.info("SharedClassGraphCache: reloaded from disk for key {}…",
						key.substring(0, Math.min(12, key.length())));
				return new AcquireResult(reloaded, null, false);
			}
			// Disk cache also missing — need full rescan
			cache.remove(key);
			logger.info("SharedClassGraphCache: disk cache miss for key {}… — full rescan needed",
					key.substring(0, Math.min(12, key.length())));
		}

		// --- Similarity-based sharing: check existing entries ---
		AcquireResult supersetHit = findSupersetEntry(requestedUrls, classLoader);
		if (supersetHit != null) {
			overlapHits++;
			return supersetHit;
		}

		// --- Try loading from disk cache ---
		long loadStart = System.currentTimeMillis();
		ScanResult scanResult = loadFromDisk(key);
		if (scanResult != null) {
			diskHits++;
			long loadElapsed = System.currentTimeMillis() - loadStart;
			logger.info("SharedClassGraphCache DISK HIT for key {}… ({}ms)",
					key.substring(0, Math.min(12, key.length())), loadElapsed);
			entry = new CacheEntry(scanResult, key, requestedUrls);
			cache.put(key, entry);
			reverseIndex.put(scanResult, key);
			return new AcquireResult(scanResult, null, false);
		}

		// --- Cache miss — perform full scan ---
		logger.debug("SharedClassGraphCache MISS for key {}… — scanning classpath ({} URLs)",
				key.substring(0, Math.min(12, key.length())), classLoader.getURLs().length);

		// Before scanning, enforce the held-entries limit by evicting
		// zero-refcount entries.  This caps total ClassGraph memory.
		evictUnusedEntries();
		if (isOverAdmissionBudget(classLoader, key)) {
			budgetRejects++;
			logger.warn("ClassGraph scan budget exceeded for key {}… but continuing scan to preserve usability",
					key.substring(0, Math.min(12, key.length())));
		}

		try {
			String[] mergedRejected = getMergedRejectedPackages();
			long scanStart = System.currentTimeMillis();
			scanResult = new ClassGraph()
					.overrideClassLoaders(classLoader)
					.enableClassInfo()
					.enableSystemJarsAndModules()
					.rejectPackages(mergedRejected)
					.scan();
			long scanElapsed = System.currentTimeMillis() - scanStart;
			logger.info("ClassGraph scan completed in {}ms ({} URLs)",
					scanElapsed, classLoader.getURLs().length);
			scanBuilds++;
			entry = new CacheEntry(scanResult, key, requestedUrls);
			cache.put(key, entry);
			reverseIndex.put(scanResult, key);
			logger.debug("SharedClassGraphCache stored new entry, cache size={}", cache.size());

			// Persist to disk for future server starts
			saveToDisk(key, scanResult);

			return new AcquireResult(scanResult, null, false);
		} catch (ClassGraphException e) {
			logger.warn("ClassGraph scan failed: {}", e.getMessage());
			return null;
		} catch (VirtualMachineError e) {
			logger.error("VirtualMachineError during ClassGraph scan ({} URLs): {}",
					classLoader.getURLs().length, e.toString());
			// Attempt to free memory before propagating
			try { System.gc(); } catch (Throwable ignored) { }
			return null;
		}
	}

	private boolean isOverAdmissionBudget(GroovyClassLoader classLoader, String key) {
		if (cache.size() >= MAX_HELD_SCANS) {
			logger.warn("ClassGraph scan budget pressure for key {}…: entry count {} at/above configured limit {}",
					key.substring(0, Math.min(12, key.length())), cache.size(), MAX_HELD_SCANS);
			return true;
		}

		long estimatedBytes = estimateMemoryBytes();
		long budgetBytes = computeBudgetBytes();
		if (estimatedBytes >= budgetBytes) {
			logger.warn("ClassGraph scan budget pressure for key {}…: estimated usage {} MB exceeds budget {} MB "
					+ "(maxHeap={} MB, urls={})",
					key.substring(0, Math.min(12, key.length())),
					estimatedBytes / (1024 * 1024),
					budgetBytes / (1024 * 1024),
					Runtime.getRuntime().maxMemory() / (1024 * 1024),
					classLoader.getURLs().length);
			return true;
		}

		return false;
	}

	private long computeBudgetBytes() {
		long heapMax = Runtime.getRuntime().maxMemory();
		long dynamic = (long) (heapMax * DEFAULT_HEAP_BUDGET_FRACTION);
		return Math.max(MIN_BUDGET_BYTES, dynamic);
	}

	/**
	 * Check cached entries for one whose classpath sufficiently overlaps with
	 * the requested URLs.  Returns an {@link AcquireResult} with the shared
	 * entry if found, or {@code null}.
	 *
	 * <p>When the best overlap is at or above the threshold, the cached entry
	 * is reused directly — even if it is not a strict superset.  Classes from
	 * the requesting scope's unique JARs that are <em>not</em> in the cached
	 * scan will simply be absent from completions; in practice these are
	 * almost always the module's own compiled output (already available
	 * through the AST) or minor test-only dependencies.</p>
	 *
	 * <p><b>Must be called while holding the synchronized lock.</b></p>
	 */
	private AcquireResult findSupersetEntry(Set<String> requestedUrls, GroovyClassLoader classLoader) {
		if (requestedUrls.isEmpty()) {
			return null;
		}

		CacheEntry bestCandidate = null;
		double bestOverlap = 0;
		int bestMatchCount = 0;

		for (CacheEntry candidate : cache.values()) {
			ScanResult sr = candidate.get();
			if (sr == null) continue;
			Set<String> cachedUrls = candidate.classpathUrls;
			if (cachedUrls == null || cachedUrls.isEmpty()) continue;

			int matchCount = 0;
			for (String url : requestedUrls) {
				if (cachedUrls.contains(url)) matchCount++;
			}
			double overlap = (double) matchCount / requestedUrls.size();

			logger.info("SharedClassGraphCache overlap check: {}/{} = {}% "
					+ "(cached={} URLs [key={}…], requested={} URLs)",
					matchCount, requestedUrls.size(), (int) (overlap * 100),
					cachedUrls.size(),
					candidate.classpathKey.substring(0, Math.min(8, candidate.classpathKey.length())),
					requestedUrls.size());

			if (overlap >= SUPERSET_OVERLAP_THRESHOLD && overlap > bestOverlap) {
				bestCandidate = candidate;
				bestOverlap = overlap;
				bestMatchCount = matchCount;
			}
		}

		if (bestCandidate == null) {
			if (!cache.isEmpty()) {
				logger.info("SharedClassGraphCache: no overlap candidate above {}% threshold",
						(int) (SUPERSET_OVERLAP_THRESHOLD * 100));
			}
			return null;
		}

		// Reuse the best candidate directly (strict superset or high overlap)
		bestCandidate.refCount++;
		Set<File> ownFiles = urlStringsToFiles(requestedUrls);
		int missingUrls = requestedUrls.size() - bestMatchCount;
		logger.info("SharedClassGraphCache OVERLAP HIT: reusing cached entry "
				+ "(overlap={}/{} = {}%, cached={} URLs, requested={} URLs, "
				+ "missing={} URLs, refCount={})",
				bestMatchCount, requestedUrls.size(), (int) (bestOverlap * 100),
				bestCandidate.classpathUrls.size(), requestedUrls.size(),
				missingUrls, bestCandidate.refCount);
		return new AcquireResult(bestCandidate.get(), ownFiles, true);
	}

	/**
	 * Extract a sorted set of URL external-form strings from classloader.
	 */
	static Set<String> extractUrlSet(GroovyClassLoader classLoader) {
		URL[] urls = classLoader.getURLs();
		Set<String> urlSet = new HashSet<>(urls.length * 2);
		for (URL url : urls) {
			urlSet.add(url.toExternalForm());
		}
		return Collections.unmodifiableSet(urlSet);
	}

	/**
	 * Convert URL strings to canonical {@link File} objects for comparison
	 * with {@link ClassInfo#getClasspathElementFile()}.
	 */
	private static Set<File> urlStringsToFiles(Set<String> urlStrings) {
		Set<File> files = new HashSet<>(urlStrings.size() * 2);
		for (String url : urlStrings) {
			try {
				java.net.URI uri = new java.net.URI(url);
				File f = new File(uri);
				files.add(f.getCanonicalFile());
			} catch (Exception e) {
				// Skip URLs that can't be converted to files (e.g. jrt:/)
				try {
					// Try as a plain path
					files.add(new File(url).getCanonicalFile());
				} catch (Exception ignored) {
					// Skip entirely
				}
			}
		}
		return Collections.unmodifiableSet(files);
	}

	/**
	 * Release a previously acquired {@link ScanResult}. Decrements the ref
	 * count and evicts + closes the result when it reaches zero.
	 *
	 * <p>It is safe to pass {@code null} — the call is a no-op.
	 *
	 * @param scanResult the result previously returned by {@link #acquire}
	 */
	public synchronized void release(ScanResult scanResult) {
		if (scanResult == null) {
			return;
		}
		String key = reverseIndex.get(scanResult);
		if (key == null) {
			// Not managed by this cache (e.g. legacy non-shared result) —
			// close it directly to avoid leaks.
			logger.debug("SharedClassGraphCache release() for untracked ScanResult — closing directly");
			scanResult.close();
			return;
		}
		CacheEntry entry = cache.get(key);
		if (entry == null) {
			// Already evicted (should not normally happen)
			reverseIndex.remove(scanResult);
			scanResult.close();
			return;
		}
		entry.refCount--;
		logger.debug("SharedClassGraphCache release() for key {}… (refCount={})",
				key.substring(0, Math.min(12, key.length())), entry.refCount);
		if (entry.refCount <= 0) {
			cache.remove(key);
			reverseIndex.remove(scanResult);
			// Clear the soft reference and close the scan result
			entry.scanResultRef.clear();
			scanResult.close();
			logger.debug("SharedClassGraphCache evicted entry for key {}…, cache size={}",
					key.substring(0, Math.min(12, key.length())), cache.size());
		}
	}

	/**
	 * Evict zero-refcount entries when the cache exceeds {@link #MAX_HELD_SCANS}.
	 * Called before acquiring a new scan to cap total ClassGraph memory.
	 * <p><b>Must be called while holding the synchronized lock.</b></p>
	 */
	private void evictUnusedEntries() {
		if (cache.size() < MAX_HELD_SCANS) {
			return;
		}
		var iterator = cache.entrySet().iterator();
		while (iterator.hasNext() && cache.size() >= MAX_HELD_SCANS) {
			var entry = iterator.next();
			CacheEntry ce = entry.getValue();
			if (ce.refCount <= 0) {
				logger.info("ClassGraph cache limit reached ({}/{}). "
						+ "Evicting unused entry for key {}…",
						cache.size(), MAX_HELD_SCANS,
						ce.classpathKey.substring(0, Math.min(12, ce.classpathKey.length())));
				ScanResult sr = ce.get();
				if (sr != null) {
					reverseIndex.remove(sr);
					ce.scanResultRef.clear();
					sr.close();
				}
				iterator.remove();
			}
		}
		if (cache.size() >= MAX_HELD_SCANS) {
			logger.warn("ClassGraph cache has {} active entries (all in use, limit {}). "
					+ "Memory pressure may increase.", cache.size(), MAX_HELD_SCANS);
		}
	}

	/**
	 * Compute a content-based cache key from the classloader's classpath.
	 * The URLs are sorted lexicographically and then SHA-256 hashed so that
	 * two classloaders with the same JARs (in any order) produce the same key.
	 */
	/**
	 * Compute a content-based cache key from the classloader's classpath
	 * <em>and</em> the current rejected-packages configuration.  Including
	 * the rejected packages in the hash ensures that changing the filter
	 * list naturally produces a different cache key, invalidating stale
	 * entries without requiring a manual version bump.
	 */
	String computeClasspathKey(GroovyClassLoader classLoader) {
		URL[] urls = classLoader.getURLs();
		String[] urlStrings = new String[urls.length];
		for (int i = 0; i < urls.length; i++) {
			urlStrings[i] = urls[i].toExternalForm();
		}
		Arrays.sort(urlStrings);
		StringBuilder sb = new StringBuilder();
		sb.append(CACHE_KEY_VERSION).append('\n');
		for (String url : urlStrings) {
			sb.append(url).append('\n');
		}
		// Include rejected packages so changing the filter invalidates the cache
		List<String> additional = this.additionalRejectedPackages;
		if (!additional.isEmpty()) {
			sb.append("rejected:");
			List<String> sorted = new ArrayList<>(additional);
			Collections.sort(sorted);
			for (String pkg : sorted) {
				sb.append(pkg).append(',');
			}
			sb.append('\n');
		}
		return sha256(sb.toString());
	}

	private static String sha256(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed by the JVM spec — this cannot happen
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns the current number of cached entries. Primarily for testing
	 * and monitoring.
	 */
	public synchronized int size() {
		return cache.size();
	}

	/**
	 * Returns the ref count for the entry associated with the given scan
	 * result, or {@code -1} if the result is not tracked by this cache.
	 * Primarily for testing.
	 */
	public synchronized int getRefCount(ScanResult scanResult) {
		if (scanResult == null) {
			return -1;
		}
		String key = reverseIndex.get(scanResult);
		if (key == null) {
			return -1;
		}
		CacheEntry entry = cache.get(key);
		return entry != null ? entry.refCount : -1;
	}

	/**
	 * Estimates the total heap memory consumed by all cached ScanResult
	 * instances in this cache.  Each ClassInfo object in a ScanResult
	 * carries method/field/annotation metadata, interned strings, and
	 * internal ClassGraph indexes — empirically ~6 KB per ClassInfo.
	 *
	 * <p>Because scopes share ScanResults via reference counting, this
	 * reports the <em>actual unique</em> memory footprint (no double-counting).
	 *
	 * @return estimated bytes consumed by all cached ScanResults
	 */
	public synchronized long estimateMemoryBytes() {
		long total = 0;
		for (CacheEntry entry : cache.values()) {
			ScanResult sr = entry.get();
			if (sr != null) {
				try {
					int classCount = sr.getAllClasses().size();
					// ~6 KB per ClassInfo (type refs, method/field lists,
					// annotations, generics, interned strings, index entries)
					// + 2 MB base overhead for ClassGraph internal structures
					total += 2L * 1024 * 1024 + (long) classCount * 6144;
				} catch (Exception e) {
					total += 2L * 1024 * 1024; // base only if ScanResult is closed
				}
			}
		}
		return total;
	}

	/**
	 * Returns the number of currently cached entries.
	 */
	public synchronized int getEntryCount() {
		return cache.size();
	}

	/**
	 * Returns the top N package prefixes (2-segment depth) by estimated memory
	 * across all cached ScanResult instances. Aggregates classes from all
	 * cache entries (unique ScanResults), groups by truncated package, and
	 * sorts descending by estimated MB.
	 *
	 * @param limit maximum number of package entries to return
	 * @return sorted list of per-package memory entries
	 */
	public synchronized List<MemoryProfiler.PackageMemoryEntry> getTopPackagesByMemory(int limit) {
		Map<String, Integer> countsMap = new LinkedHashMap<>();
		for (CacheEntry entry : cache.values()) {
			ScanResult sr = entry.get();
			if (sr != null) {
				try {
					for (ClassInfo ci : sr.getAllClasses()) {
						String prefix = MemoryProfiler.truncatePackage(ci.getPackageName(), 2);
						countsMap.merge(prefix, 1, Integer::sum);
					}
				} catch (Exception e) {
					// ScanResult may have been closed
				}
			}
		}

		if (countsMap.isEmpty()) {
			return Collections.emptyList();
		}

		List<MemoryProfiler.PackageMemoryEntry> entries = new ArrayList<>(countsMap.size());
		for (Map.Entry<String, Integer> e : countsMap.entrySet()) {
			int count = e.getValue();
			double mb = (count * 6144L) / (1024.0 * 1024.0);
			entries.add(new MemoryProfiler.PackageMemoryEntry(e.getKey(), mb, count));
		}

		entries.sort((a, b) -> Double.compare(b.estimatedMB, a.estimatedMB));
		return entries.subList(0, Math.min(limit, entries.size()));
	}

	/**
	 * Returns the total reference count across all cached entries.
	 */
	public synchronized int getTotalRefCount() {
		int total = 0;
		for (CacheEntry entry : cache.values()) {
			total += entry.refCount;
		}
		return total;
	}

	public static final class StatsSnapshot {
		public final long acquireRequests;
		public final long exactHits;
		public final long overlapHits;
		public final long diskHits;
		public final long scanBuilds;
		public final long budgetRejects;

		StatsSnapshot(long acquireRequests, long exactHits, long overlapHits,
				long diskHits, long scanBuilds, long budgetRejects) {
			this.acquireRequests = acquireRequests;
			this.exactHits = exactHits;
			this.overlapHits = overlapHits;
			this.diskHits = diskHits;
			this.scanBuilds = scanBuilds;
			this.budgetRejects = budgetRejects;
		}
	}

	public synchronized StatsSnapshot getStatsSnapshot() {
		return new StatsSnapshot(acquireRequests, exactHits, overlapHits,
				diskHits, scanBuilds, budgetRejects);
	}

	/**
	 * Close all cached entries and clear the cache. Call on server shutdown.
	 */
	public synchronized void clear() {
		for (CacheEntry entry : cache.values()) {
			try {
				ScanResult sr = entry.get();
				if (sr != null) {
					entry.scanResultRef.clear();
					sr.close();
				}
			} catch (Exception e) {
				logger.debug("Error closing ScanResult during cache clear", e);
			}
		}
		cache.clear();
		reverseIndex.clear();
		acquireRequests = 0;
		exactHits = 0;
		overlapHits = 0;
		diskHits = 0;
		scanBuilds = 0;
		budgetRejects = 0;
		logger.info("SharedClassGraphCache cleared");
	}

	// --- On-disk persistence ---

	/**
	 * Directory for persisted ClassGraph scan results.
	 * Stored under {@code ~/.groovyls/cache/classgraph/}.
	 */
	private static Path getDiskCacheDir() {
		String home = System.getProperty("user.home");
		return Paths.get(home, ".groovyls", "cache", "classgraph");
	}

	/**
	 * Load a previously cached scan result from disk.
	 *
	 * @param classpathKey the SHA-256 hash of the classpath
	 * @return the deserialized {@link ScanResult}, or {@code null} if not found
	 *         or corrupt
	 */
	private ScanResult loadFromDisk(String classpathKey) {
		Path cacheFile = getDiskCacheDir().resolve(classpathKey + ".json");
		if (!Files.isRegularFile(cacheFile)) {
			return null;
		}
		try {
			String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
			ScanResult result = ScanResult.fromJSON(json);
			return result;
		} catch (Exception e) {
			logger.debug("Failed to load ClassGraph cache from disk for key {}…: {}",
					classpathKey.substring(0, Math.min(12, classpathKey.length())),
					e.getMessage());
			// Delete corrupt cache file
			try {
				Files.deleteIfExists(cacheFile);
			} catch (IOException ignored) {
			}
			return null;
		}
	}

	/**
	 * Persist a scan result to disk atomically (write-to-temp then rename).
	 *
	 * @param classpathKey the SHA-256 hash of the classpath
	 * @param scanResult   the scan result to persist
	 */
	private void saveToDisk(String classpathKey, ScanResult scanResult) {
		try {
			Path cacheDir = getDiskCacheDir();
			Files.createDirectories(cacheDir);
			Path cacheFile = cacheDir.resolve(classpathKey + ".json");
			Path tempFile = cacheDir.resolve(classpathKey + ".tmp");

			String json = scanResult.toJSON();
			Files.writeString(tempFile, json, StandardCharsets.UTF_8);
			Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			logger.debug("Persisted ClassGraph scan to disk for key {}…",
					classpathKey.substring(0, Math.min(12, classpathKey.length())));
		} catch (Exception e) {
			logger.debug("Failed to persist ClassGraph scan to disk for key {}…: {}",
					classpathKey.substring(0, Math.min(12, classpathKey.length())),
					e.getMessage());
		}
	}
}
