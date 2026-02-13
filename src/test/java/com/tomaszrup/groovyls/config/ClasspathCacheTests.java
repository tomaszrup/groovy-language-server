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
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClasspathCache}: round-trip serialization, hash-based
 * validation, invalidation, and edge cases.
 */
class ClasspathCacheTests {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("classpath-cache-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp directory recursively
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    // ---- Round-trip save/load ----

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        // Prepare dummy classpath data
        Map<Path, List<String>> classpaths = new LinkedHashMap<>();
        classpaths.put(workspaceRoot.resolve("projectA"),
                Arrays.asList("/libs/a.jar", "/libs/b.jar"));
        classpaths.put(workspaceRoot.resolve("projectB"),
                Arrays.asList("/libs/c.jar"));

        // Create a build file so we can compute hashes
        Path buildFile = workspaceRoot.resolve("projectA");
        Files.createDirectories(buildFile);
        Files.write(buildFile.resolve("build.gradle"), "apply plugin: 'java'".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = ClasspathCache.computeBuildFileStamps(
                Arrays.asList(workspaceRoot.resolve("projectA"), workspaceRoot.resolve("projectB")));

        List<Path> discoveredProjects = Arrays.asList(
                workspaceRoot.resolve("projectA"), workspaceRoot.resolve("projectB"));

        // Save
        ClasspathCache.save(workspaceRoot, classpaths, hashes, discoveredProjects);

        // Load
        Optional<ClasspathCache.CacheData> loaded = ClasspathCache.load(workspaceRoot);
        assertTrue(loaded.isPresent(), "Cache should load successfully");

        ClasspathCache.CacheData data = loaded.get();
        assertEquals(hashes, data.buildFileHashes);

        // Convert back and verify
        Map<Path, List<String>> restored = ClasspathCache.toClasspathMap(data);
        assertEquals(classpaths.size(), restored.size());
        for (Map.Entry<Path, List<String>> entry : classpaths.entrySet()) {
            Path normalised = entry.getKey().toAbsolutePath().normalize();
            assertTrue(restored.containsKey(normalised),
                    "Restored map should contain " + normalised);
            assertEquals(entry.getValue(), restored.get(normalised));
        }
    }

    // ---- Validation ----

    @Test
    void isValidReturnsTrueWhenHashesMatch() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("build.gradle"), "v1".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        ClasspathCache.CacheData data = new ClasspathCache.CacheData();
        data.buildFileHashes = hashes;

        assertTrue(ClasspathCache.isValid(data, hashes));
    }

    @Test
    void isValidReturnsFalseWhenHashesDiffer() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("build.gradle"), "v1".getBytes(StandardCharsets.UTF_8));

        Map<String, String> originalHashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        // Modify the build file — use different size to guarantee stamp change
        // even if filesystem timestamp resolution is coarse.
        Files.write(projectRoot.resolve("build.gradle"),
                "version 2 — changed content with different length".getBytes(StandardCharsets.UTF_8));

        Map<String, String> newHashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        ClasspathCache.CacheData data = new ClasspathCache.CacheData();
        data.buildFileHashes = originalHashes;

        assertFalse(ClasspathCache.isValid(data, newHashes));
    }

    @Test
    void isValidDetectsNewBuildFile() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("build.gradle"), "v1".getBytes(StandardCharsets.UTF_8));

        Map<String, String> originalHashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        // Add a settings.gradle that didn't exist before
        Files.write(projectRoot.resolve("settings.gradle"),
                "rootProject.name = 'test'".getBytes(StandardCharsets.UTF_8));

        Map<String, String> newHashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        ClasspathCache.CacheData data = new ClasspathCache.CacheData();
        data.buildFileHashes = originalHashes;

        assertFalse(ClasspathCache.isValid(data, newHashes),
                "Cache should be invalid when a new build file appears");
    }

    @Test
    void isValidDetectsDeletedBuildFile() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("build.gradle"), "v1".getBytes(StandardCharsets.UTF_8));
        Files.write(projectRoot.resolve("settings.gradle"), "s1".getBytes(StandardCharsets.UTF_8));

        Map<String, String> originalHashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        // Delete settings.gradle
        Files.delete(projectRoot.resolve("settings.gradle"));

        Map<String, String> newHashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        ClasspathCache.CacheData data = new ClasspathCache.CacheData();
        data.buildFileHashes = originalHashes;

        assertFalse(ClasspathCache.isValid(data, newHashes),
                "Cache should be invalid when a build file is deleted");
    }

    // ---- Invalidation (delete) ----

    @Test
    void invalidateDeletesCacheFile() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        // Save something
        Map<Path, List<String>> classpaths = new LinkedHashMap<>();
        classpaths.put(workspaceRoot, Arrays.asList("/a.jar"));
        ClasspathCache.save(workspaceRoot, classpaths, Collections.emptyMap(), null);

        // Verify it exists
        assertTrue(ClasspathCache.load(workspaceRoot).isPresent());

        // Invalidate
        ClasspathCache.invalidate(workspaceRoot);

        // Verify it's gone
        assertFalse(ClasspathCache.load(workspaceRoot).isPresent());
    }

    @Test
    void invalidateNoOpWhenNoCacheExists() {
        Path workspaceRoot = tempDir.resolve("nonexistent");
        // Should not throw
        assertDoesNotThrow(() -> ClasspathCache.invalidate(workspaceRoot));
    }

    // ---- Load edge cases ----

    @Test
    void loadReturnsEmptyWhenFileDoesNotExist() {
        Path workspaceRoot = tempDir.resolve("no-such-workspace");
        Optional<ClasspathCache.CacheData> result = ClasspathCache.load(workspaceRoot);
        assertFalse(result.isPresent());
    }

    @Test
    void loadReturnsEmptyForCorruptJson() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        // Write garbage to the cache file location
        Path cacheFile = ClasspathCache.getCacheFile(workspaceRoot);
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, "not valid json {{{".getBytes(StandardCharsets.UTF_8));

        Optional<ClasspathCache.CacheData> result = ClasspathCache.load(workspaceRoot);
        assertFalse(result.isPresent());
    }

    @Test
    void loadReturnsEmptyForEmptyFile() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        Path cacheFile = ClasspathCache.getCacheFile(workspaceRoot);
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, new byte[0]);

        Optional<ClasspathCache.CacheData> result = ClasspathCache.load(workspaceRoot);
        assertFalse(result.isPresent());
    }

    // ---- Hash computation ----

    @Test
    void computeBuildFileHashesIgnoresNonexistentFiles() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        // Only create build.gradle, not settings.gradle etc.
        Files.write(projectRoot.resolve("build.gradle"), "apply plugin: 'java'".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        assertEquals(1, hashes.size(), "Only existing build files should be hashed");
        assertTrue(hashes.containsKey(projectRoot.toAbsolutePath().normalize() + "/build.gradle"));
    }

    @Test
    void computeBuildFileHashesIncludesMultipleBuildFiles() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("build.gradle"), "v1".getBytes(StandardCharsets.UTF_8));
        Files.write(projectRoot.resolve("settings.gradle"), "s1".getBytes(StandardCharsets.UTF_8));
        Files.write(projectRoot.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        assertEquals(3, hashes.size());
    }

    @Test
    void computeBuildFileHashesHandlesMultipleRoots() throws IOException {
        Path rootA = tempDir.resolve("a");
        Path rootB = tempDir.resolve("b");
        Files.createDirectories(rootA);
        Files.createDirectories(rootB);
        Files.write(rootA.resolve("build.gradle"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(rootB.resolve("pom.xml"), "b".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = ClasspathCache.computeBuildFileStamps(
                Arrays.asList(rootA, rootB));

        assertEquals(2, hashes.size());
    }

    @Test
    void sha256HexProducesDeterministicResult() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        String hash1 = ClasspathCache.sha256Hex(data);
        String hash2 = ClasspathCache.sha256Hex(data);
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length(), "SHA-256 hex should be 64 characters");
    }

    @Test
    void sha256HexDiffersForDifferentInput() {
        String hash1 = ClasspathCache.sha256Hex("foo".getBytes(StandardCharsets.UTF_8));
        String hash2 = ClasspathCache.sha256Hex("bar".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(hash1, hash2);
    }

    // ---- Cache file naming ----

    @Test
    void getCacheFileIsDeterministic() {
        Path workspace = tempDir.resolve("workspace");
        Path file1 = ClasspathCache.getCacheFile(workspace);
        Path file2 = ClasspathCache.getCacheFile(workspace);
        assertEquals(file1, file2);
    }

    @Test
    void getCacheFileDiffersForDifferentWorkspaces() {
        Path ws1 = tempDir.resolve("ws1");
        Path ws2 = tempDir.resolve("ws2");
        Path file1 = ClasspathCache.getCacheFile(ws1);
        Path file2 = ClasspathCache.getCacheFile(ws2);
        assertNotEquals(file1, file2);
    }

    // ---- Gradle version catalog ----

    @Test
    void computeBuildFileHashesIncludesVersionCatalog() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Path gradleDir = projectRoot.resolve("gradle");
        Files.createDirectories(gradleDir);
        Files.write(projectRoot.resolve("build.gradle"), "v1".getBytes(StandardCharsets.UTF_8));
        Files.write(gradleDir.resolve("libs.versions.toml"), "[versions]".getBytes(StandardCharsets.UTF_8));

        Map<String, String> hashes = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        assertEquals(2, hashes.size());
        String absRoot = projectRoot.toAbsolutePath().normalize().toString();
        assertTrue(hashes.containsKey(absRoot + "/gradle/libs.versions.toml"));
    }

    // ---- Discovered projects round-trip ----

    @Test
    void discoveredProjectsSavedAndRestored() throws IOException {
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        Map<Path, List<String>> classpaths = new LinkedHashMap<>();
        classpaths.put(workspaceRoot.resolve("p1"), Arrays.asList("/a.jar"));

        List<Path> discoveredProjects = Arrays.asList(
                workspaceRoot.resolve("p1"),
                workspaceRoot.resolve("p2"));

        ClasspathCache.save(workspaceRoot, classpaths, Collections.emptyMap(), discoveredProjects);

        Optional<ClasspathCache.CacheData> loaded = ClasspathCache.load(workspaceRoot);
        assertTrue(loaded.isPresent());

        Optional<List<Path>> restored = ClasspathCache.toDiscoveredProjectsList(loaded.get());
        assertTrue(restored.isPresent(), "Discovered projects should be present");
        assertEquals(2, restored.get().size());
        assertEquals(workspaceRoot.resolve("p1").toAbsolutePath().normalize(), restored.get().get(0));
        assertEquals(workspaceRoot.resolve("p2").toAbsolutePath().normalize(), restored.get().get(1));
    }

    @Test
    void toDiscoveredProjectsListReturnsEmptyWhenNull() {
        ClasspathCache.CacheData data = new ClasspathCache.CacheData();
        data.discoveredProjects = null;
        assertFalse(ClasspathCache.toDiscoveredProjectsList(data).isPresent());
    }

    @Test
    void toDiscoveredProjectsListReturnsEmptyWhenEmpty() {
        ClasspathCache.CacheData data = new ClasspathCache.CacheData();
        data.discoveredProjects = Collections.emptyList();
        assertFalse(ClasspathCache.toDiscoveredProjectsList(data).isPresent());
    }

    // ---- Build file stamp format ----

    @Test
    void computeBuildFileStampsUsesLastModifiedAndSize() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.write(projectRoot.resolve("build.gradle"), "apply plugin: 'java'".getBytes(StandardCharsets.UTF_8));

        Map<String, String> stamps = ClasspathCache.computeBuildFileStamps(
                Collections.singletonList(projectRoot));

        assertEquals(1, stamps.size());
        String stamp = stamps.values().iterator().next();
        // Stamp format: "<lastModified>:<size>"
        assertTrue(stamp.contains(":"), "Stamp should contain ':' separator");
        String[] parts = stamp.split(":");
        assertEquals(2, parts.length, "Stamp should have exactly two parts");
        long lastModified = Long.parseLong(parts[0]);
        long size = Long.parseLong(parts[1]);
        assertTrue(lastModified > 0, "Last modified should be positive");
        assertEquals(20, size, "Size should match 'apply plugin: \'java\'' (20 bytes)");
    }
}
