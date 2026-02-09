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
import java.util.stream.Collectors;

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
    private static final int CACHE_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Build file names whose content should be hashed for cache validation.
     * Any change to these files invalidates the cached classpath.
     */
    private static final String[] BUILD_FILE_NAMES = {
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "pom.xml",
            "gradle.lockfile",
            "gradle/libs.versions.toml"
    };

    // ---- Serialized model ----

    /** JSON-serializable cache entry. */
    public static class CacheData {
        int version;
        long timestamp;
        /** Relative path → SHA-256 hex of each build file that existed at cache time. */
        Map<String, String> buildFileHashes;
        /** Normalised project root path → list of classpath entry strings. */
        Map<String, List<String>> classpaths;
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
            if (data == null || data.version != CACHE_VERSION
                    || data.buildFileHashes == null || data.classpaths == null) {
                logger.info("Classpath cache version mismatch or corrupt — will re-import");
                return Optional.empty();
            }
            return Optional.of(data);
        } catch (Exception e) {
            logger.warn("Failed to read classpath cache {}: {}", cacheFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persist the resolved classpaths to disk for future server starts.
     *
     * @param workspaceRoot   the workspace root (used to derive cache file name)
     * @param classpaths      map from project root to classpath entries
     * @param buildFileHashes the build-file hashes computed via
     *                        {@link #computeBuildFileHashes(Collection)}
     */
    public static void save(Path workspaceRoot,
                            Map<Path, List<String>> classpaths,
                            Map<String, String> buildFileHashes) {
        Path cacheFile = getCacheFile(workspaceRoot);
        try {
            Files.createDirectories(cacheFile.getParent());

            CacheData data = new CacheData();
            data.version = CACHE_VERSION;
            data.timestamp = System.currentTimeMillis();
            data.buildFileHashes = buildFileHashes;
            // Normalise paths to strings for JSON portability
            data.classpaths = new LinkedHashMap<>();
            for (Map.Entry<Path, List<String>> entry : classpaths.entrySet()) {
                data.classpaths.put(entry.getKey().toAbsolutePath().normalize().toString(),
                        entry.getValue());
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
        return cached.buildFileHashes.equals(currentHashes);
    }

    /**
     * Compute SHA-256 hashes of all relevant build files found under the given
     * project roots.
     *
     * @param projectRoots all discovered project root directories
     * @return map from relative-ish key ({@code "<root>/<filename>"}) to hex
     *         digest; files that don't exist are silently omitted
     */
    public static Map<String, String> computeBuildFileHashes(Collection<Path> projectRoots) {
        Map<String, String> hashes = new TreeMap<>();
        for (Path root : projectRoots) {
            Path absRoot = root.toAbsolutePath().normalize();
            for (String name : BUILD_FILE_NAMES) {
                Path buildFile = absRoot.resolve(name);
                if (Files.isRegularFile(buildFile)) {
                    String hash = sha256(buildFile);
                    if (hash != null) {
                        // Key: <normalised root>/<build file name>
                        hashes.put(absRoot + "/" + name, hash);
                    }
                }
            }
        }
        return hashes;
    }

    /**
     * Convert cached classpath data back into the {@code Map<Path, List<String>>}
     * format expected by {@code GroovyServices.addProjects()}.
     */
    public static Map<Path, List<String>> toClasspathMap(CacheData data) {
        Map<Path, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : data.classpaths.entrySet()) {
            result.put(Paths.get(entry.getKey()), entry.getValue());
        }
        return result;
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

    private static String sha256(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return sha256Hex(bytes);
        } catch (IOException e) {
            logger.debug("Could not read file for hashing: {}", file);
            return null;
        }
    }

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
            throw new RuntimeException(e);
        }
    }
}
