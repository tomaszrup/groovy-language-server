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
package com.tomaszrup.groovyls.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Persistent on-disk cache for resolved Gradle/Maven classpaths.
 *
 * <p>On server startup the language server can skip the expensive Gradle Tooling
 * API or Maven dependency resolution when the build files have not changed since
 * the last import. The cache stores a map from project root to classpath entries
 * alongside SHA-256 hashes of all relevant build files. Validation is hash-based:
 * if any build file content differs the cache is considered stale.</p>
 *
 * <p>Cache files are written atomically (write-to-temp then rename) and stored
 * in a user-level directory ({@code ~/.groovyls/cache/}) so they survive across
 * editor restarts without polluting the workspace.</p>
 */
public class ClasspathCache {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathCache.class);

    /** Bump when the JSON schema changes to force cache invalidation. */
    private static final int CACHE_VERSION = 4;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClasspathCache() {
    }

    /**
     * Build file names whose modification is tracked for cache validation.
     * {@code gradle.lockfile} is intentionally excluded — it is a build
     * output, not a classpath-affecting input.
     */
    private static final String[] BUILD_FILE_NAMES = {
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "pom.xml",
            "gradle/libs.versions.toml"
    };

    // ---- Serialized model ----

    /**
     * Per-project cache entry (v3). Each project stores its own build-file
     * stamps and classpath independently, so a change in one project's
     * {@code build.gradle} invalidates only that project's cache.
     */
    public static class ProjectCacheEntry {
        /** Build-file stamps for this project only (key → stamp). */
        Map<String, String> stamps;
        /** Resolved classpath entries for this project. */
        List<String> classpath;
        /** Detected Groovy dependency version for this project, if known. */
        String groovyVersion;

        public ProjectCacheEntry() {}

        public ProjectCacheEntry(Map<String, String> stamps, List<String> classpath) {
            this.stamps = stamps;
            this.classpath = classpath;
        }

        public ProjectCacheEntry(Map<String, String> stamps, List<String> classpath, String groovyVersion) {
            this.stamps = stamps;
            this.classpath = classpath;
            this.groovyVersion = groovyVersion;
        }
    }

    /** JSON-serializable cache entry. */
    public static class CacheData {
        int version;
        long timestamp;
        /**
         * <b>v2 (legacy):</b> global build-file stamps. Retained for
         * backward-compatible loading; new caches use per-project stamps
         * inside {@link #projects}.
         */
        Map<String, String> buildFileHashes;
        /**
         * <b>v2 (legacy):</b> global classpath map. Retained for
         * backward-compatible loading; new caches use per-project entries
         * inside {@link #projects}.
         */
        Map<String, List<String>> classpaths;
        /**
         * <b>v3:</b> Per-project cache entries keyed by normalised absolute
         * project root path. Each entry contains its own build-file stamps
         * and classpath so validation and invalidation are per-project.
         */
        Map<String, ProjectCacheEntry> projects;
        /**
         * Discovered project root paths (absolute, normalised).  Persisted so
         * that the expensive {@code Files.walk()} discovery phase can be
         * skipped on subsequent starts when the cache is valid.
         * May be {@code null} for caches created before this field was added.
         */
        List<String> discoveredProjects;
    }

    // ---- Public API ----

    /**
     * Attempt to load a previously saved classpath cache for the given workspace.
     *
     * @param workspaceRoot the workspace root used to derive the cache file name
     * @return the cached classpaths ({@code Map<Path, List<String>>}) if the
     *         cache file exists, is valid JSON, and has the current version;
     *         {@link Optional#empty()} otherwise
     */
    public static Optional<CacheData> load(Path workspaceRoot) {
        Path cacheFile = getCacheFile(workspaceRoot);
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            CacheData data = GSON.fromJson(reader, CacheData.class);
            if (data == null || data.version != CACHE_VERSION) {
                logger.info("Classpath cache version mismatch or corrupt — will re-import");
                return Optional.empty();
            }
            // v3 requires the projects map
            if (data.projects == null || data.projects.isEmpty()) {
                logger.info("Classpath cache missing per-project data — will re-import");
                return Optional.empty();
            }
            return Optional.of(data);
        } catch (Exception e) {
            logger.warn("Failed to read classpath cache {}: {}", cacheFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persist the resolved classpaths and discovered project list to disk.
     *
     * @param workspaceRoot      the workspace root (used to derive cache file name)
     * @param classpaths         map from project root to classpath entries
     * @param buildFileHashes    the build-file stamps computed via
     *                           {@link #computeBuildFileStamps(Collection)}
     * @param discoveredProjects all discovered project root paths (may be {@code null})
     */
    public static void save(Path workspaceRoot,
                            Map<Path, List<String>> classpaths,
                            Map<String, String> buildFileHashes,
                            List<Path> discoveredProjects) {
        save(workspaceRoot, classpaths, Collections.emptyMap(), buildFileHashes, discoveredProjects);
    }

    /**
     * Persist resolved classpaths and optional per-project Groovy versions.
     */
    public static void save(Path workspaceRoot,
                            Map<Path, List<String>> classpaths,
                            Map<Path, String> projectGroovyVersions,
                            Map<String, String> buildFileHashes,
                            List<Path> discoveredProjects) {
        Path cacheFile = getCacheFile(workspaceRoot);
        try {
            Files.createDirectories(cacheFile.getParent());

            CacheData data = new CacheData();
            data.version = CACHE_VERSION;
            data.timestamp = System.currentTimeMillis();

            // Build per-project entries (v3)
            data.projects = new LinkedHashMap<>();
            for (Map.Entry<Path, List<String>> entry : classpaths.entrySet()) {
                String rootKey = entry.getKey().toAbsolutePath().normalize().toString();
                Map<String, String> projectStamps = computeBuildFileStampsForProject(entry.getKey());
                String groovyVersion = projectGroovyVersions.get(entry.getKey());
                data.projects.put(rootKey, new ProjectCacheEntry(projectStamps, entry.getValue(), groovyVersion));
            }

            // Also persist the legacy fields for diagnostic / tooling purposes
            data.buildFileHashes = buildFileHashes;
            data.classpaths = new LinkedHashMap<>();
            for (Map.Entry<Path, List<String>> entry : classpaths.entrySet()) {
                data.classpaths.put(entry.getKey().toAbsolutePath().normalize().toString(),
                        entry.getValue());
            }

            // Persist discovered project roots so discovery can be skipped
            if (discoveredProjects != null) {
                data.discoveredProjects = new ArrayList<>();
                for (Path p : discoveredProjects) {
                    data.discoveredProjects.add(p.toAbsolutePath().normalize().toString());
                }
            }

            // Atomic write: temp file → rename
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            logger.info("Classpath cache saved to {}", cacheFile);
        } catch (IOException e) {
            logger.warn("Failed to write classpath cache: {}", e.getMessage());
        }
    }

    /**
     * Merge a single project's classpath into the existing cache, updating
     * only that project's entry without affecting others.  If no cache file
     * exists, a new one is created.
     *
     * @param workspaceRoot      the workspace root
     * @param projectRoot        the project to update
     * @param classpath          the resolved classpath entries
     * @param discoveredProjects all discovered project root paths (may be {@code null})
     */
    public static void mergeProject(Path workspaceRoot,
                                    Path projectRoot,
                                    List<String> classpath,
                                    List<Path> discoveredProjects) {
        mergeProject(workspaceRoot, projectRoot, classpath, null, discoveredProjects);
    }

    /**
     * Merge one project's classpath and optional Groovy version into cache.
     */
    public static void mergeProject(Path workspaceRoot,
                                    Path projectRoot,
                                    List<String> classpath,
                                    String groovyVersion,
                                    List<Path> discoveredProjects) {
        Path cacheFile = getCacheFile(workspaceRoot);
        try {
            Files.createDirectories(cacheFile.getParent());

            // Load existing cache (or start fresh)
            CacheData data = loadOrCreateCacheData(cacheFile);
            data.timestamp = System.currentTimeMillis();

            if (data.projects == null) {
                data.projects = new LinkedHashMap<>();
            }
            if (data.classpaths == null) {
                data.classpaths = new LinkedHashMap<>();
            }

            String rootKey = projectRoot.toAbsolutePath().normalize().toString();
            Map<String, String> projectStamps = computeBuildFileStampsForProject(projectRoot);
            data.projects.put(rootKey, new ProjectCacheEntry(projectStamps, classpath, groovyVersion));
            data.classpaths.put(rootKey, classpath);

            // Rebuild global stamps from all per-project stamps
            Map<String, String> allStamps = new TreeMap<>();
            for (ProjectCacheEntry pce : data.projects.values()) {
                if (pce.stamps != null) {
                    allStamps.putAll(pce.stamps);
                }
            }
            data.buildFileHashes = allStamps;

            if (discoveredProjects != null) {
                data.discoveredProjects = new ArrayList<>();
                for (Path p : discoveredProjects) {
                    data.discoveredProjects.add(p.toAbsolutePath().normalize().toString());
                }
            }

            // Atomic write
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            logger.info("Classpath cache merged for project {} → {}", projectRoot, cacheFile);
        } catch (IOException e) {
            logger.warn("Failed to merge classpath cache for {}: {}", projectRoot, e.getMessage());
        }
    }

    private static CacheData loadOrCreateCacheData(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return newCacheData();
        }
        try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            CacheData data = GSON.fromJson(reader, CacheData.class);
            return (data != null && data.version == CACHE_VERSION) ? data : newCacheData();
        } catch (IOException | RuntimeException e) {
            return newCacheData();
        }
    }

    private static CacheData newCacheData() {
        CacheData data = new CacheData();
        data.version = CACHE_VERSION;
        return data;
    }

    /**
     * Delete the cache file for the given workspace, e.g. when build files
     * change and the cache is known to be stale.
     */
    public static void invalidate(Path workspaceRoot) {
        Path cacheFile = getCacheFile(workspaceRoot);
        try {
            if (Files.deleteIfExists(cacheFile)) {
                logger.info("Classpath cache invalidated: {}", cacheFile);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete classpath cache: {}", e.getMessage());
        }
    }

    /**
     * Check whether a cached {@link CacheData} is still valid by comparing its
     * stored build-file hashes against freshly computed ones.
     *
     * @return {@code true} if every hash matches (no build file has changed)
     */
    public static boolean isValid(CacheData cached, Map<String, String> currentHashes) {
        if (cached.buildFileHashes != null) {
            return cached.buildFileHashes.equals(currentHashes);
        }
        // v3 cache without legacy hashes — validate per-project
        if (cached.projects == null) return false;
        Map<String, String> allStamps = new TreeMap<>();
        for (ProjectCacheEntry pce : cached.projects.values()) {
            if (pce.stamps != null) allStamps.putAll(pce.stamps);
        }
        return allStamps.equals(currentHashes);
    }

    /**
     * Check whether a single project's cache entry is still valid by comparing
     * its stored build-file stamps against the current state on disk.
     *
     * @param cached      the full cache data
     * @param projectRoot the project root to validate
     * @return {@code true} if the project's stamps match and its cached
     *         classpath entries still exist; {@code false} otherwise
     */
    public static boolean isValidForProject(CacheData cached, Path projectRoot) {
        if (cached.projects == null) return false;
        String key = projectRoot.toAbsolutePath().normalize().toString();
        ProjectCacheEntry entry = cached.projects.get(key);
        if (entry == null || entry.stamps == null || entry.classpath == null) {
            return false;
        }
        // Check stamps match current disk state
        Map<String, String> currentStamps = computeBuildFileStampsForProject(projectRoot);
        if (!entry.stamps.equals(currentStamps)) {
            logger.debug("Cache stale for project {} — stamps mismatch", projectRoot);
            return false;
        }
        // Spot-check that a few classpath entries still exist
        if (!entry.classpath.isEmpty()) {
            int toCheck = Math.min(3, entry.classpath.size());
            int missing = 0;
            for (int i = 0; i < toCheck; i++) {
                if (!Files.exists(Paths.get(entry.classpath.get(i)))) {
                    missing++;
                }
            }
            if (missing > 0 && missing > toCheck / 2) {
                logger.debug("Cache stale for project {} — classpath entries missing", projectRoot);
                return false;
            }
        }
        return true;
    }

    /**
     * Get the cached classpath for a single project, or empty if not cached.
     */
    public static Optional<List<String>> getProjectClasspath(CacheData cached, Path projectRoot) {
        if (cached.projects == null) return Optional.empty();
        String key = projectRoot.toAbsolutePath().normalize().toString();
        ProjectCacheEntry entry = cached.projects.get(key);
        if (entry == null || entry.classpath == null) return Optional.empty();
        return Optional.of(entry.classpath);
    }

    /**
     * Get the cached Groovy version for a single project, or empty if unknown.
     */
    public static Optional<String> getProjectGroovyVersion(CacheData cached, Path projectRoot) {
        if (cached.projects == null) return Optional.empty();
        String key = projectRoot.toAbsolutePath().normalize().toString();
        ProjectCacheEntry entry = cached.projects.get(key);
        if (entry == null || entry.groovyVersion == null || entry.groovyVersion.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(entry.groovyVersion);
    }

    /**
     * Verify that the classpath entries stored in the cache still exist on disk.
     * <p>This catches scenarios where {@code gradle clean} was run or the Gradle
     * cache was wiped — the build files haven't changed so stamp-based
     * validation passes, but the resolved JARs and {@code build/classes}
     * directories are gone, leading to unresolved-import diagnostics.</p>
     *
     * <p>For performance, we sample up to {@code sampleSize} entries from the
     * first project that has classpath data rather than checking every entry
     * in every project.</p>
     *
     * @param cached     the cached data to validate
     * @param sampleSize maximum number of entries to spot-check (0 = check all)
     * @return {@code true} if the sampled entries exist, {@code false} if any
     *         are missing
     */
    public static boolean areClasspathEntriesPresent(CacheData cached, int sampleSize) {
        if (cached.classpaths == null || cached.classpaths.isEmpty()) {
            return true; // nothing to validate
        }

        Map.Entry<String, List<String>> firstProject = findFirstProjectWithClasspath(cached.classpaths);
        if (firstProject == null) {
            return true;
        }

        int toCheck = determineSampleCount(firstProject.getValue(), sampleSize);
        int missing = countMissingClasspathEntries(firstProject.getValue(), toCheck);
        if (missing > 0 && missing > toCheck / 2) {
            logger.info("Classpath cache stale: {}/{} sampled entries missing in project {}",
                    missing, toCheck, firstProject.getKey());
            return false;
        }
        return true;
    }

    private static Map.Entry<String, List<String>> findFirstProjectWithClasspath(Map<String, List<String>> classpaths) {
        for (Map.Entry<String, List<String>> entry : classpaths.entrySet()) {
            List<String> cpEntries = entry.getValue();
            if (cpEntries != null && !cpEntries.isEmpty()) {
                return entry;
            }
        }
        return null;
    }

    private static int determineSampleCount(List<String> cpEntries, int sampleSize) {
        return sampleSize > 0 ? Math.min(sampleSize, cpEntries.size()) : cpEntries.size();
    }

    private static int countMissingClasspathEntries(List<String> cpEntries, int toCheck) {
        int missing = 0;
        for (int i = 0; i < toCheck; i++) {
            Path path = Paths.get(cpEntries.get(i));
            if (!Files.exists(path)) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * Compute lightweight file stamps ({@code "<lastModified>:<size>"}) for all
     * relevant build files found under the given project roots.  This replaces
     * the previous SHA-256 content hashing approach: no file I/O is needed
     * beyond a single {@code stat} call per file, making cache validation
     * dramatically faster.
     *
     * @param projectRoots all discovered project root directories
     * @return map from key ({@code "<root>/<filename>"}) to stamp string;
     *         files that don't exist are silently omitted
     */
    public static Map<String, String> computeBuildFileStamps(Collection<Path> projectRoots) {
        Map<String, String> stamps = new TreeMap<>();
        for (Path root : projectRoots) {
            Path absRoot = root.toAbsolutePath().normalize();
            for (String name : BUILD_FILE_NAMES) {
                Path buildFile = absRoot.resolve(name);
                if (Files.isRegularFile(buildFile)) {
                    try {
                        long lastModified = Files.getLastModifiedTime(buildFile).toMillis();
                        long size = Files.size(buildFile);
                        stamps.put(absRoot + "/" + name, lastModified + ":" + size);
                    } catch (IOException e) {
                        logger.debug("Could not stat file for stamping: {}", buildFile);
                    }
                }
            }
        }
        return stamps;
    }

    /**
     * Compatibility alias for {@link #computeBuildFileStamps(Collection)}.
     * Retained for backward compatibility with existing tests.
     */
    public static Map<String, String> computeBuildFileHashes(Collection<Path> projectRoots) {
        return computeBuildFileStamps(projectRoots);
    }

    /**
     * Compute build-file stamps for a single project root.
     *
     * @param projectRoot the project root directory
     * @return map from key to stamp string for this project's build files
     */
    public static Map<String, String> computeBuildFileStampsForProject(Path projectRoot) {
        return computeBuildFileStamps(Collections.singletonList(projectRoot));
    }

    /**
     * Convert cached classpath data back into the {@code Map<Path, List<String>>}
     * format expected by {@code GroovyServices.addProjects()}.
     */
    public static Map<Path, List<String>> toClasspathMap(CacheData data) {
        Map<Path, List<String>> result = new LinkedHashMap<>();
        // Prefer v3 per-project entries
        if (data.projects != null && !data.projects.isEmpty()) {
            for (Map.Entry<String, ProjectCacheEntry> entry : data.projects.entrySet()) {
                if (entry.getValue().classpath != null) {
                    result.put(Paths.get(entry.getKey()), entry.getValue().classpath);
                }
            }
        } else if (data.classpaths != null) {
            // Fallback to legacy v2 format
            for (Map.Entry<String, List<String>> entry : data.classpaths.entrySet()) {
                result.put(Paths.get(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Convert cached discovered project roots back into a {@code List<Path>}.
     *
     * @return the list of project root paths, or {@link Optional#empty()} if
     *         the cache does not contain discovered project data
     */
    public static Optional<List<Path>> toDiscoveredProjectsList(CacheData data) {
        if (data.discoveredProjects == null || data.discoveredProjects.isEmpty()) {
            return Optional.empty();
        }
        List<Path> result = new ArrayList<>();
        for (String s : data.discoveredProjects) {
            result.add(Paths.get(s));
        }
        return Optional.of(result);
    }

    // ---- Cache directory / file helpers ----

    /**
     * Returns the user-level cache directory ({@code ~/.groovyls/cache/}).
     */
    static Path getCacheDir() {
        return Paths.get(System.getProperty("user.home"), ".groovyls", "cache");
    }

    /**
     * Derives a deterministic cache file path for the given workspace root.
     * The file name is based on a SHA-256 hash of the workspace root path so
     * that multiple workspaces don't collide.
     */
    static Path getCacheFile(Path workspaceRoot) {
        String rootKey = workspaceRoot.toAbsolutePath().normalize().toString();
        String hash = sha256Hex(rootKey.getBytes(StandardCharsets.UTF_8));
        return getCacheDir().resolve("classpath-" + hash.substring(0, 16) + ".json");
    }

    // ---- Hashing helpers ----

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java spec — should never happen
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
