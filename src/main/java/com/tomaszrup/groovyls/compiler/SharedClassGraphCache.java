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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
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

	/** Singleton instance. */
	private static final SharedClassGraphCache INSTANCE = new SharedClassGraphCache();

	public static SharedClassGraphCache getInstance() {
		return INSTANCE;
	}

	/**
	 * A cached entry: the scan result and its current reference count.
	 */
	static final class CacheEntry {
		final ScanResult scanResult;
		final String classpathKey;
		int refCount;

		CacheEntry(ScanResult scanResult, String classpathKey) {
			this.scanResult = scanResult;
			this.classpathKey = classpathKey;
			this.refCount = 1;
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
	 * incremented and the existing result is returned. Otherwise a new
	 * ClassGraph scan is performed.
	 *
	 * @param classLoader the classloader whose classpath should be scanned
	 * @return the scan result, or {@code null} if scanning failed
	 */
	public synchronized ScanResult acquire(GroovyClassLoader classLoader) {
		String key = computeClasspathKey(classLoader);
		CacheEntry entry = cache.get(key);
		if (entry != null) {
			entry.refCount++;
			logger.info("SharedClassGraphCache HIT for key {}… (refCount={}), cache size={}",
					key.substring(0, Math.min(12, key.length())), entry.refCount, cache.size());
			return entry.scanResult;
		}

		// Cache miss — perform scan
		logger.info("SharedClassGraphCache MISS for key {}… — scanning classpath ({} URLs)",
				key.substring(0, Math.min(12, key.length())), classLoader.getURLs().length);
		try {
			ScanResult scanResult = new ClassGraph()
					.overrideClassLoaders(classLoader)
					.enableClassInfo()
					.enableSystemJarsAndModules()
					.scan();
			entry = new CacheEntry(scanResult, key);
			cache.put(key, entry);
			reverseIndex.put(scanResult, key);
			logger.info("SharedClassGraphCache stored new entry, cache size={}", cache.size());
			return scanResult;
		} catch (ClassGraphException e) {
			logger.warn("ClassGraph scan failed: {}", e.getMessage());
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
			scanResult.close();
			logger.info("SharedClassGraphCache evicted entry for key {}…, cache size={}",
					key.substring(0, Math.min(12, key.length())), cache.size());
		}
	}

	/**
	 * Compute a content-based cache key from the classloader's classpath.
	 * The URLs are sorted lexicographically and then SHA-256 hashed so that
	 * two classloaders with the same JARs (in any order) produce the same key.
	 */
	static String computeClasspathKey(GroovyClassLoader classLoader) {
		URL[] urls = classLoader.getURLs();
		String[] urlStrings = new String[urls.length];
		for (int i = 0; i < urls.length; i++) {
			urlStrings[i] = urls[i].toExternalForm();
		}
		Arrays.sort(urlStrings);
		StringBuilder sb = new StringBuilder();
		for (String url : urlStrings) {
			sb.append(url).append('\n');
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
	 * Close all cached entries and clear the cache. Call on server shutdown.
	 */
	public synchronized void clear() {
		for (CacheEntry entry : cache.values()) {
			try {
				entry.scanResult.close();
			} catch (Exception e) {
				logger.debug("Error closing ScanResult during cache clear", e);
			}
		}
		cache.clear();
		reverseIndex.clear();
		logger.info("SharedClassGraphCache cleared");
	}
}
