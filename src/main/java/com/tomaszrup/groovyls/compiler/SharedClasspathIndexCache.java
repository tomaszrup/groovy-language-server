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
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;

/**
 * Process-wide cache of compact classpath indexes.
 *
 * <p>This cache is optimized for low RAM usage: it stores only lightweight
 * symbol metadata. It uses {@link SharedClassGraphCache} as a short-lived
 * scanner source, immediately releasing heavy {@link ScanResult} instances
 * after projection.</p>
 */
public class SharedClasspathIndexCache {
	private static final Logger logger = LoggerFactory.getLogger(SharedClasspathIndexCache.class);

	private static final int MAX_HELD_INDEXES = 8;
	private static final double SUPERSET_OVERLAP_THRESHOLD = 0.75;

	private static final SharedClasspathIndexCache INSTANCE = new SharedClasspathIndexCache();

	public static SharedClasspathIndexCache getInstance() {
		return INSTANCE;
	}

	public static final class AcquireResult {
		private final ClasspathSymbolIndex index;
		private final Set<String> ownClasspathElementPaths;

		AcquireResult(ClasspathSymbolIndex index, Set<String> ownClasspathElementPaths) {
			this.index = index;
			this.ownClasspathElementPaths = ownClasspathElementPaths;
		}

		public ClasspathSymbolIndex getIndex() {
			return index;
		}

		public Set<String> getOwnClasspathElementPaths() {
			return ownClasspathElementPaths;
		}
	}

	private static final class CacheEntry {
		final Set<String> classpathUrls;
		final ClasspathSymbolIndex index;
		long lastAccessNanos;

		CacheEntry(Set<String> classpathUrls, ClasspathSymbolIndex index) {
			this.classpathUrls = classpathUrls;
			this.index = index;
			this.lastAccessNanos = System.nanoTime();
		}
	}

	private final SharedClassGraphCache scanCache;
	private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);

	SharedClasspathIndexCache() {
		this(SharedClassGraphCache.getInstance());
	}

	SharedClasspathIndexCache(SharedClassGraphCache scanCache) {
		this.scanCache = scanCache;
	}

	public synchronized AcquireResult acquireWithResult(GroovyClassLoader classLoader) {
		if (classLoader == null) {
			return null;
		}

		String key = scanCache.computeClasspathKey(classLoader);
		Set<String> requestedUrls = extractUrlSet(classLoader);

		CacheEntry exact = cache.get(key);
		if (exact != null) {
			exact.lastAccessNanos = System.nanoTime();
			return new AcquireResult(exact.index, null);
		}

		AcquireResult overlap = findOverlapHit(requestedUrls);
		if (overlap != null) {
			return overlap;
		}

		SharedClassGraphCache.AcquireResult scanAcquire = scanCache.acquireWithResult(classLoader);
		if (scanAcquire == null || scanAcquire.getScanResult() == null) {
			return null;
		}

		ScanResult scanResult = scanAcquire.getScanResult();
		try {
			ClasspathSymbolIndex index = ClasspathSymbolIndex.fromScanResult(scanResult);
			CacheEntry newEntry = new CacheEntry(requestedUrls, index);
			cache.put(key, newEntry);
			evictLruIfNeeded();

			Set<String> ownPaths = toCanonicalPathSet(scanAcquire.getOwnClasspathFiles());
			return new AcquireResult(index, ownPaths);
		} finally {
			scanCache.release(scanResult);
		}
	}

	private AcquireResult findOverlapHit(Set<String> requestedUrls) {
		if (requestedUrls.isEmpty() || cache.isEmpty()) {
			return null;
		}

		CacheEntry bestCandidate = null;
		double bestOverlap = 0.0;

		for (CacheEntry candidate : cache.values()) {
			Set<String> cachedUrls = candidate.classpathUrls;
			if (cachedUrls == null || cachedUrls.isEmpty()) {
				continue;
			}
			int matchCount = 0;
			for (String url : requestedUrls) {
				if (cachedUrls.contains(url)) {
					matchCount++;
				}
			}
			double overlap = (double) matchCount / requestedUrls.size();
			if (overlap >= SUPERSET_OVERLAP_THRESHOLD && overlap > bestOverlap) {
				bestCandidate = candidate;
				bestOverlap = overlap;
			}
		}

		if (bestCandidate == null) {
			return null;
		}

		bestCandidate.lastAccessNanos = System.nanoTime();
		logger.info("SharedClasspathIndexCache overlap hit: {}% overlap", (int) (bestOverlap * 100));
		return new AcquireResult(bestCandidate.index, urlStringsToCanonicalPathSet(requestedUrls));
	}

	private void evictLruIfNeeded() {
		while (cache.size() > MAX_HELD_INDEXES) {
			String oldestKey = null;
			long oldestAccess = Long.MAX_VALUE;
			for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
				if (e.getValue().lastAccessNanos < oldestAccess) {
					oldestAccess = e.getValue().lastAccessNanos;
					oldestKey = e.getKey();
				}
			}
			if (oldestKey == null) {
				break;
			}
			cache.remove(oldestKey);
		}
	}

	public synchronized void clear() {
		cache.clear();
	}

	/**
	 * Evict cached classpath indexes that include classpath elements under the
	 * given project root. Used after Java source moves/renames so stale symbols
	 * from old package locations are not reused.
	 *
	 * @param projectRoot project root path
	 * @return number of removed cache entries
	 */
	public synchronized int invalidateEntriesUnderProject(Path projectRoot) {
		if (projectRoot == null || cache.isEmpty()) {
			return 0;
		}
		Path normalizedRoot = normalizePath(projectRoot);
		int removed = 0;
		java.util.Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, CacheEntry> entry = iterator.next();
			if (containsPathUnderRoot(entry.getValue().classpathUrls, normalizedRoot)) {
				iterator.remove();
				removed++;
			}
		}
		if (removed > 0) {
			logger.info("SharedClasspathIndexCache invalidated {} entries for project {}", removed, normalizedRoot);
		}
		return removed;
	}

	private static boolean containsPathUnderRoot(Set<String> urlStrings, Path projectRoot) {
		if (urlStrings == null || urlStrings.isEmpty()) {
			return false;
		}
		for (String url : urlStrings) {
			Path candidate = toPath(url);
			if (candidate != null && candidate.startsWith(projectRoot)) {
				return true;
			}
		}
		return false;
	}

	private static Path toPath(String url) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		try {
			java.net.URI uri = new java.net.URI(url);
			if ("file".equalsIgnoreCase(uri.getScheme())) {
				return normalizePath(Paths.get(uri));
			}
		} catch (Exception ignored) {
			// Ignore malformed/non-file URI strings and fall back to plain-path parsing.
		}
		try {
			return normalizePath(Paths.get(url));
		} catch (Exception ignored) {
			// Ignore invalid path strings.
			return null;
		}
	}

	private static Path normalizePath(Path path) {
		if (path == null) {
			return null;
		}
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	private static Set<String> extractUrlSet(GroovyClassLoader classLoader) {
		URL[] urls = classLoader.getURLs();
		Set<String> urlSet = new HashSet<>(urls.length * 2);
		for (URL url : urls) {
			urlSet.add(url.toExternalForm());
		}
		return Collections.unmodifiableSet(urlSet);
	}

	private static Set<String> toCanonicalPathSet(Set<File> files) {
		if (files == null || files.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> result = new HashSet<>(files.size() * 2);
		for (File file : files) {
			if (file == null) {
				continue;
			}
			try {
				result.add(file.getCanonicalFile().getPath());
			} catch (IOException e) {
				result.add(file.getAbsolutePath());
			}
		}
		return Collections.unmodifiableSet(result);
	}

	private static Set<String> urlStringsToCanonicalPathSet(Set<String> urls) {
		if (urls == null || urls.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> paths = new HashSet<>(urls.size() * 2);
		for (String url : urls) {
			try {
				java.net.URI uri = new java.net.URI(url);
				File file = new File(uri);
				paths.add(file.getCanonicalFile().getPath());
			} catch (Exception e) {
				try {
					paths.add(new File(url).getCanonicalFile().getPath());
				} catch (Exception ignored) {
					// Ignore entries that cannot be represented as local filesystem paths.
				}
			}
		}
		return Collections.unmodifiableSet(paths);
	}
}
