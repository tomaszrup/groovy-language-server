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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.classgraph.ScanResult;

/**
 * On-disk persistence and path utilities for {@link SharedClassGraphCache}.
 *
 * <p>This is a package-private helper extracted from
 * {@link SharedClassGraphCache} to keep it under 1000 lines.</p>
 */
class ClassGraphDiskCache {

	private static final Logger logger = LoggerFactory.getLogger(ClassGraphDiskCache.class);
	private static final String CACHE_FILE_EXTENSION = ".json";

	ClassGraphDiskCache() {
	}

	// ----------------------------------------------------------------
	// Disk persistence
	// ----------------------------------------------------------------

	/**
	 * Directory for persisted ClassGraph scan results.
	 * Stored under {@code ~/.groovyls/cache/classgraph/}.
	 */
	static Path getDiskCacheDir() {
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
	ScanResult loadFromDisk(String classpathKey) {
		Path cacheFile = getDiskCacheDir().resolve(classpathKey + CACHE_FILE_EXTENSION);
		if (!Files.isRegularFile(cacheFile)) {
			return null;
		}
		try {
			String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
			return ScanResult.fromJSON(json);
		} catch (IOException | RuntimeException e) {
			String shortKey = abbreviateKey(classpathKey);
			logger.debug("Failed to load ClassGraph cache from disk for key {}…: {}",
					shortKey,
					e.getMessage());
			try {
				Files.deleteIfExists(cacheFile);
			} catch (IOException ignored) {
				logger.trace("Failed to delete corrupted cache file {}", cacheFile, ignored);
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
	void saveToDisk(String classpathKey, ScanResult scanResult) {
		String shortKey = abbreviateKey(classpathKey);
		try {
			Path cacheDir = getDiskCacheDir();
			Files.createDirectories(cacheDir);
			Path cacheFile = cacheDir.resolve(classpathKey + CACHE_FILE_EXTENSION);
			Path tempFile = cacheDir.resolve(classpathKey + ".tmp");

			String json = scanResult.toJSON();
			Files.writeString(tempFile, json, StandardCharsets.UTF_8);
			Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			logger.debug("Persisted ClassGraph scan to disk for key {}…",
					shortKey);
		} catch (IOException | RuntimeException e) {
			logger.debug("Failed to persist ClassGraph scan to disk for key {}…: {}",
					shortKey,
					e.getMessage());
		}
	}

	void deleteDiskCacheEntry(String classpathKey) {
		if (classpathKey == null || classpathKey.isEmpty()) {
			return;
		}
		Path cacheFile = getDiskCacheDir().resolve(classpathKey + CACHE_FILE_EXTENSION);
		try {
			Files.deleteIfExists(cacheFile);
		} catch (IOException e) {
			String shortKey = abbreviateKey(classpathKey);
			logger.debug("Failed to delete ClassGraph disk cache for key {}…: {}",
					shortKey,
					e.getMessage());
		}
	}

	// ----------------------------------------------------------------
	// Path utilities
	// ----------------------------------------------------------------

	static boolean containsPathUnderRoot(Set<String> urlStrings, Path projectRoot) {
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

	static Path toPath(String url) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		try {
			java.net.URI uri = new java.net.URI(url);
			if ("file".equalsIgnoreCase(uri.getScheme())) {
				return normalizePath(Paths.get(uri));
			}
		} catch (URISyntaxException | InvalidPathException ignored) {
			// Ignore malformed URIs and try best-effort path parsing below.
		}
		try {
			return normalizePath(Paths.get(url));
		} catch (InvalidPathException ignored) {
			return null;
		}
	}

	static Path normalizePath(Path path) {
		if (path == null) {
			return null;
		}
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	// ----------------------------------------------------------------
	// Utility methods
	// ----------------------------------------------------------------

	static String abbreviateKey(String key) {
		if (key == null || key.isEmpty()) {
			return "n/a";
		}
		return key.substring(0, Math.min(12, key.length()));
	}

	static int countMatches(Set<String> requestedUrls, Set<String> cachedUrls) {
		int matchCount = 0;
		for (String url : requestedUrls) {
			if (cachedUrls.contains(url)) {
				matchCount++;
			}
		}
		return matchCount;
	}

	void logOverlapCheck(SharedClassGraphCache.CacheEntry candidate, Set<String> cachedUrls,
			Set<String> requestedUrls, int matchCount, double overlap) {
		if (logger.isInfoEnabled()) {
			String shortKey = abbreviateKey(candidate.classpathKey);
			logger.info("SharedClassGraphCache overlap check: {}/{} = {}% "
					+ "(cached={} URLs [key={}…], requested={} URLs)",
					matchCount, requestedUrls.size(), (int) (overlap * 100),
					cachedUrls.size(), shortKey,
					requestedUrls.size());
		}
	}

	void closeScanResultQuietly(ScanResult scanResult, String context) {
		try {
			scanResult.close();
		} catch (RuntimeException e) {
			logger.debug("Error closing ScanResult during {}", context, e);
		}
	}
}
