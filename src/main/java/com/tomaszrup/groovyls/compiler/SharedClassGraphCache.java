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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraphException;
import io.github.classgraph.ScanResult;
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
	private static final int MAX_HELD_SCANS = 3;

	/**
	 * Version prefix for the disk cache key. Bump this whenever the ClassGraph
	 * scan configuration changes (e.g. adding/removing rejectPackages filters)
	 * so that stale cached results are naturally orphaned.
	 */
	private static final String CACHE_KEY_VERSION = "v3";

	/**
	 * Internal JDK implementation packages that are <b>always</b> rejected from
	 * ClassGraph scans. These are non-public APIs that should never appear in
	 * code completion. Not configurable.
	 */
	private static final String[] BASE_REJECTED_PACKAGES = {
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
		"com.sun.swing"
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
	static final class CacheEntry {
		volatile SoftReference<ScanResult> scanResultRef;
		final String classpathKey;
		int refCount;

		CacheEntry(ScanResult scanResult, String classpathKey) {
			this.scanResultRef = new SoftReference<>(scanResult);
			this.classpathKey = classpathKey;
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
	 * Acquire a {@link ScanResult} for the given classloader. If a cached
	 * result already exists for the same classpath, the ref count is
	 * incremented and the existing result is returned. Otherwise, checks
	 * the on-disk cache and falls back to a new ClassGraph scan.
	 *
	 * @param classLoader the classloader whose classpath should be scanned
	 * @return the scan result, or {@code null} if scanning failed
	 */
	public synchronized ScanResult acquire(GroovyClassLoader classLoader) {
		String key = computeClasspathKey(classLoader);
		CacheEntry entry = cache.get(key);
		if (entry != null) {
			ScanResult existing = entry.get();
			if (existing != null) {
				entry.refCount++;
				logger.debug("SharedClassGraphCache HIT for key {}… (refCount={}), cache size={}",
						key.substring(0, Math.min(12, key.length())), entry.refCount, cache.size());
				return existing;
			}
			// SoftReference was cleared by GC — reload from disk or rescan
			logger.info("SharedClassGraphCache: SoftReference cleared for key {}… — reloading",
					key.substring(0, Math.min(12, key.length())));
			reverseIndex.values().remove(key);
			ScanResult reloaded = loadFromDisk(key);
			if (reloaded != null) {
				entry.set(reloaded);
				entry.refCount++;
				reverseIndex.put(reloaded, key);
				logger.info("SharedClassGraphCache: reloaded from disk for key {}…",
						key.substring(0, Math.min(12, key.length())));
				return reloaded;
			}
			// Disk cache also missing — need full rescan
			cache.remove(key);
			logger.info("SharedClassGraphCache: disk cache miss for key {}… — full rescan needed",
					key.substring(0, Math.min(12, key.length())));
		}

		// Try loading from disk cache
		long loadStart = System.currentTimeMillis();
		ScanResult scanResult = loadFromDisk(key);
		if (scanResult != null) {
			long loadElapsed = System.currentTimeMillis() - loadStart;
			logger.info("SharedClassGraphCache DISK HIT for key {}… ({}ms)",
					key.substring(0, Math.min(12, key.length())), loadElapsed);
			entry = new CacheEntry(scanResult, key);
			cache.put(key, entry);
			reverseIndex.put(scanResult, key);
			return scanResult;
		}

		// Cache miss — perform scan
		logger.debug("SharedClassGraphCache MISS for key {}… — scanning classpath ({} URLs)",
				key.substring(0, Math.min(12, key.length())), classLoader.getURLs().length);

		// Before scanning, enforce the held-entries limit by evicting
		// zero-refcount entries.  This caps total ClassGraph memory.
		evictUnusedEntries();

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
			entry = new CacheEntry(scanResult, key);
			cache.put(key, entry);
			reverseIndex.put(scanResult, key);
			logger.debug("SharedClassGraphCache stored new entry, cache size={}", cache.size());

			// Persist to disk for future server starts
			saveToDisk(key, scanResult);

			return scanResult;
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
	 * Returns the total reference count across all cached entries.
	 */
	public synchronized int getTotalRefCount() {
		int total = 0;
		for (CacheEntry entry : cache.values()) {
			total += entry.refCount;
		}
		return total;
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
