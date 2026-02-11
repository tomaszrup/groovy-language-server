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
package com.tomaszrup.groovyls.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A process-wide cache that shares source-JAR indexes across
 * {@link com.tomaszrup.groovyls.ProjectScope ProjectScope}s with identical
 * classpaths.
 *
 * <p>In a Gradle multi-project workspace, most subprojects share the same
 * (or very similar) set of dependency JARs. Without sharing, each scope
 * independently indexes the same {@code *-sources.jar} files, duplicating
 * the {@code classNameToSourceJar} map 50× in a 50-project workspace.
 * This cache deduplicates that work using a SHA-256 classpath key and
 * reference counting (same pattern as {@code SharedClassGraphCache}).</p>
 *
 * <h3>Reference counting</h3>
 * <ol>
 *   <li>Call {@link #acquire(List)} to get the shared index entry and
 *       increment the ref count.</li>
 *   <li>Call {@link #release(IndexEntry)} when the scope no longer needs
 *       it (classpath changed or server shutdown).</li>
 * </ol>
 * When the ref count drops to zero the entry is evicted.
 *
 * <p>This class is thread-safe.</p>
 */
public class SharedSourceJarIndex {

    private static final Logger logger = LoggerFactory.getLogger(SharedSourceJarIndex.class);

    private static final SharedSourceJarIndex INSTANCE = new SharedSourceJarIndex();

    public static SharedSourceJarIndex getInstance() {
        return INSTANCE;
    }

    /**
     * A shared, immutable snapshot of the source-JAR index for a particular
     * classpath. This is what individual {@link JavaSourceLocator}s delegate
     * their dependency lookups to.
     */
    public static final class IndexEntry {
        private final String classpathKey;
        private volatile Map<String, JavaSourceLocator.SourceJarEntry> classNameToSourceJar;
        private final Set<Path> indexedSourceJars;
        int refCount;

        IndexEntry(String classpathKey,
                   Map<String, JavaSourceLocator.SourceJarEntry> classNameToSourceJar,
                   Set<Path> indexedSourceJars) {
            this.classpathKey = classpathKey;
            this.classNameToSourceJar = Collections.unmodifiableMap(classNameToSourceJar);
            this.indexedSourceJars = indexedSourceJars;
            this.refCount = 1;
        }

        /** Returns the shared FQCN → source-JAR entry map. */
        public Map<String, JavaSourceLocator.SourceJarEntry> getClassNameToSourceJar() {
            return classNameToSourceJar;
        }

        /** Check whether a source JAR has been indexed already. */
        public boolean isIndexed(Path sourceJar) {
            return indexedSourceJars.contains(sourceJar);
        }
    }

    private final ConcurrentHashMap<String, IndexEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IndexEntry, String> reverseIndex = new ConcurrentHashMap<>();

    SharedSourceJarIndex() {
    }

    /**
     * Acquire a shared source-JAR index for the given classpath entries.
     * If a cached index already exists for this classpath, the ref count
     * is incremented and the existing entry is returned. Otherwise, a new
     * index is built by scanning for {@code *-sources.jar} files.
     *
     * @param classpathEntries the classpath JAR paths
     * @return the shared index entry
     */
    public synchronized IndexEntry acquire(List<String> classpathEntries) {
        String key = computeKey(classpathEntries);
        IndexEntry entry = cache.get(key);
        if (entry != null) {
            entry.refCount++;
            logger.debug("SharedSourceJarIndex HIT for key {}… (refCount={}), cache size={}",
                    key.substring(0, Math.min(12, key.length())), entry.refCount, cache.size());
            return entry;
        }

        // Cache miss — build the index
        logger.debug("SharedSourceJarIndex MISS for key {}… — indexing {} classpath entries",
                key.substring(0, Math.min(12, key.length())), classpathEntries.size());
        long start = System.currentTimeMillis();

        Map<String, JavaSourceLocator.SourceJarEntry> sourceJarMap = new HashMap<>();
        Set<Path> indexed = new HashSet<>();

        for (String cpEntry : classpathEntries) {
            Path sourceJar = JavaSourceLocator.findSourceJar(cpEntry);
            if (sourceJar != null && !indexed.contains(sourceJar)) {
                indexed.add(sourceJar);
                JavaSourceLocator.indexSourceJarStatic(sourceJar, sourceJarMap);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        logger.info("SharedSourceJarIndex built in {}ms: {} source JARs, {} classes",
                elapsed, indexed.size(), sourceJarMap.size());

        entry = new IndexEntry(key, sourceJarMap, indexed);
        cache.put(key, entry);
        reverseIndex.put(entry, key);
        return entry;
    }

    /**
     * Release a previously acquired index entry. Decrements the ref count
     * and evicts when it reaches zero.
     */
    public synchronized void release(IndexEntry entry) {
        if (entry == null) {
            return;
        }
        String key = reverseIndex.get(entry);
        if (key == null) {
            return;
        }
        entry.refCount--;
        logger.debug("SharedSourceJarIndex release() for key {}… (refCount={})",
                key.substring(0, Math.min(12, key.length())), entry.refCount);
        if (entry.refCount <= 0) {
            cache.remove(key);
            reverseIndex.remove(entry);
            logger.debug("SharedSourceJarIndex evicted entry, cache size={}", cache.size());
        }
    }

    /**
     * Close all cached entries. Call on server shutdown.
     */
    public synchronized void clear() {
        cache.clear();
        reverseIndex.clear();
        logger.info("SharedSourceJarIndex cleared");
    }

    /**
     * Current cache size (for testing/monitoring).
     */
    public synchronized int size() {
        return cache.size();
    }

    /**
     * Estimates the total heap memory consumed by all cached source-JAR
     * index entries. Each entry in the FQCN→SourceJarEntry map costs roughly
     * ~300 bytes (String key ~120 bytes avg + SourceJarEntry with Path + String ~180 bytes).
     *
     * @return estimated bytes consumed by all cached indexes
     */
    public synchronized long estimateMemoryBytes() {
        long total = 0;
        for (IndexEntry entry : cache.values()) {
            Map<String, ?> map = entry.getClassNameToSourceJar();
            if (map != null) {
                total += (long) map.size() * 300;
            }
        }
        return total;
    }

    private static String computeKey(List<String> classpathEntries) {
        String[] sorted = classpathEntries.toArray(new String[0]);
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String entry : sorted) {
            sb.append(entry).append('\n');
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
            throw new RuntimeException(e);
        }
    }
}
