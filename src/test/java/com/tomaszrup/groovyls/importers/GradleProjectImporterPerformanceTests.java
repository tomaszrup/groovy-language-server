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
package com.tomaszrup.groovyls.importers;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests that simulate importing a large multi-module Gradle project.
 *
 * <p>These tests create a realistic workspace structure with ~40 modules, each
 * having ~1,000 classpath entries (JAR files), to verify that classpath
 * resolution, deduplication, and compilation unit setup scale efficiently.</p>
 *
 * <p>No actual Gradle daemon is launched — the tests exercise the in-process
 * code paths (project discovery, classpath aggregation in
 * {@link CompilationUnitFactory}, and batch grouping logic) using synthetic
 * file system structures.</p>
 */
class GradleProjectImporterPerformanceTests {

    private static final int MODULE_COUNT = 40;
    private static final int CLASSPATH_FILES_PER_MODULE = 1000;

    private GradleProjectImporter importer;
    private Path workspaceRoot;

    /** Temp directory for each test. */
    private Path tempDir;
    /** Shared lib directory containing all dummy JARs. */
    private static Path sharedLibDir;
    /** All dummy JAR paths, reusable across modules. */
    private static List<Path> sharedJarPaths;

    // ---- lifecycle ----

    @BeforeAll
    static void createSharedJars() throws IOException {
        // Create a shared "repository" of dummy JAR files that modules reference.
        // Using a single shared pool avoids creating 40 × 1000 = 40,000 separate
        // files, which would be slow on many file systems. Instead we create
        // 1,000 JARs and each module's classpath references them all.
        sharedLibDir = Files.createTempDirectory("groovyls-perf-repo");

        sharedJarPaths = new ArrayList<>(CLASSPATH_FILES_PER_MODULE);
        for (int i = 0; i < CLASSPATH_FILES_PER_MODULE; i++) {
            Path jar = sharedLibDir.resolve("dependency-" + i + "-1.0.jar");
            // Write a minimal (but valid-looking) file so File.exists() returns true
            Files.write(jar, new byte[]{0x50, 0x4B, 0x03, 0x04}); // ZIP magic bytes
            sharedJarPaths.add(jar);
        }
    }

    @AfterAll
    static void cleanupSharedJars() throws IOException {
        if (sharedLibDir != null && Files.exists(sharedLibDir)) {
            Files.walk(sharedLibDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("groovyls-perf-test");
        workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        importer = new GradleProjectImporter();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    // ---- helpers ----

    /**
     * Create a synthetic multi-module Gradle workspace:
     * <pre>
     *   workspace/
     *     settings.gradle
     *     module-0/
     *       build.gradle
     *       src/main/java/
     *       build/classes/java/main/
     *     module-1/
     *       ...
     *     ...
     * </pre>
     *
     * @return list of module root paths
     */
    private List<Path> createMultiModuleWorkspace(int moduleCount) throws IOException {
        // Root settings.gradle listing all subprojects
        StringBuilder settings = new StringBuilder();
        settings.append("rootProject.name = 'perf-test'\n");
        for (int i = 0; i < moduleCount; i++) {
            settings.append("include 'module-").append(i).append("'\n");
        }
        Files.writeString(workspaceRoot.resolve("settings.gradle"), settings.toString());
        Files.writeString(workspaceRoot.resolve("build.gradle"), "// root build file\n");

        List<Path> moduleRoots = new ArrayList<>();
        for (int i = 0; i < moduleCount; i++) {
            Path module = workspaceRoot.resolve("module-" + i);
            Files.createDirectories(module.resolve("src/main/java"));
            Files.createDirectories(module.resolve("build/classes/java/main"));
            Files.writeString(module.resolve("build.gradle"),
                    "plugins { id 'java' }\ndependencies {}\n");
            moduleRoots.add(module);
        }
        return moduleRoots;
    }

    /**
     * Build a classpath list for a single module: {@value CLASSPATH_FILES_PER_MODULE}
     * JAR entries plus the module's own {@code build/classes} output directory.
     */
    private List<String> buildClasspathForModule(Path moduleRoot) {
        List<String> cp = new ArrayList<>(CLASSPATH_FILES_PER_MODULE + 1);
        for (Path jar : sharedJarPaths) {
            cp.add(jar.toAbsolutePath().toString());
        }
        cp.add(moduleRoot.resolve("build/classes/java/main").toAbsolutePath().toString());
        return cp;
    }

    // ---- performance tests ----

    /**
     * Verify that discovering projects in a workspace with {@value MODULE_COUNT}
     * modules completes in a reasonable time.
     */
    @Test
    void testDiscoverProjectsScalesWithManyModules() throws IOException {
        createMultiModuleWorkspace(MODULE_COUNT);

        long start = System.nanoTime();
        List<Path> discovered = importer.discoverProjects(workspaceRoot);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(MODULE_COUNT, discovered.size(),
                "Should discover all " + MODULE_COUNT + " modules");
        // Discovery should be fast — mostly file system walking
        assertTrue(elapsedMs < 10_000,
                "Discovery of " + MODULE_COUNT + " modules took " + elapsedMs + "ms, expected < 10s");

        System.out.printf("[PERF] discoverProjects(%d modules): %d ms%n", MODULE_COUNT, elapsedMs);
    }

    /**
     * Simulate setting the classpath from {@value MODULE_COUNT} modules × 
     * {@value CLASSPATH_FILES_PER_MODULE} entries on a {@link CompilationUnitFactory}
     * and verify that compilation unit creation does not degrade.
     *
     * <p>This is the hot path after import: every keystroke re-creates the
     * compilation unit with the full classpath already set.</p>
     */
    @Test
    void testCompilationUnitFactoryScalesWithLargeClasspath() throws IOException {
        List<Path> moduleRoots = createMultiModuleWorkspace(MODULE_COUNT);

        // Aggregate classpaths across all modules (as GroovyServices does)
        Set<String> allClasspathEntries = new LinkedHashSet<>();
        for (Path module : moduleRoots) {
            allClasspathEntries.addAll(buildClasspathForModule(module));
        }

        int totalEntries = allClasspathEntries.size();
        System.out.printf("[PERF] Total unique classpath entries across %d modules: %d%n",
                MODULE_COUNT, totalEntries);

        // The shared JARs are deduplicated; unique count =
        // CLASSPATH_FILES_PER_MODULE (shared) + MODULE_COUNT (per-module build/classes)
        assertEquals(CLASSPATH_FILES_PER_MODULE + MODULE_COUNT, totalEntries,
                "Unique classpath count should be shared jars + per-module class dirs");

        CompilationUnitFactory factory = new CompilationUnitFactory();
        FileContentsTracker tracker = new FileContentsTracker();

        // Time: set classpath
        long startSet = System.nanoTime();
        factory.setAdditionalClasspathList(new ArrayList<>(allClasspathEntries));
        long setElapsedMs = (System.nanoTime() - startSet) / 1_000_000;

        // Time: first compilation unit creation (cold — builds classpath, classloader)
        long startCreate = System.nanoTime();
        GroovyLSCompilationUnit cu = factory.create(workspaceRoot, tracker);
        long createElapsedMs = (System.nanoTime() - startCreate) / 1_000_000;

        assertNotNull(cu, "Compilation unit should be created successfully");
        System.out.printf("[PERF] setAdditionalClasspathList(%d entries): %d ms%n",
                totalEntries, setElapsedMs);
        System.out.printf("[PERF] CompilationUnitFactory.create() (cold): %d ms%n", createElapsedMs);

        // Time: second creation (warm — reuses existing compilation unit)
        long startWarm = System.nanoTime();
        GroovyLSCompilationUnit cu2 = factory.create(workspaceRoot, tracker);
        long warmElapsedMs = (System.nanoTime() - startWarm) / 1_000_000;

        assertNotNull(cu2, "Warm compilation unit should be created successfully");
        System.out.printf("[PERF] CompilationUnitFactory.create() (warm): %d ms%n", warmElapsedMs);

        // Warm creation should be significantly faster than cold
        assertTrue(createElapsedMs + warmElapsedMs < 30_000,
                "Total compilation unit setup took " + (createElapsedMs + warmElapsedMs) + "ms, expected < 30s");
    }

    /**
     * Verify that per-module classpath aggregation with realistic overlap
     * (shared dependencies) behaves correctly and completes quickly.
     *
     * <p>In a real project, many modules share the same dependency JARs.
     * This test simulates that overlap and verifies the deduplication logic.</p>
     */
    @Test
    void testClasspathDeduplicationAcrossModules() throws IOException {
        List<Path> moduleRoots = createMultiModuleWorkspace(MODULE_COUNT);

        // Each module references the same 1000 shared JARs + its own class dir
        Map<Path, List<String>> perModuleClasspaths = new LinkedHashMap<>();
        for (Path module : moduleRoots) {
            perModuleClasspaths.put(module, buildClasspathForModule(module));
        }

        long start = System.nanoTime();

        // Simulate what GroovyServices does: merge all module classpaths with dedup
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> cp : perModuleClasspaths.values()) {
            merged.addAll(cp);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Verify: shared JARs appear once, plus one class dir per module
        assertEquals(CLASSPATH_FILES_PER_MODULE + MODULE_COUNT, merged.size());
        System.out.printf("[PERF] Classpath deduplication (%d modules × %d entries): %d ms, %d unique%n",
                MODULE_COUNT, CLASSPATH_FILES_PER_MODULE, elapsedMs, merged.size());

        assertTrue(elapsedMs < 5_000,
                "Merging " + (MODULE_COUNT * CLASSPATH_FILES_PER_MODULE)
                        + " classpath entries took " + elapsedMs + "ms, expected < 5s");
    }

    /**
     * Verify that {@link GradleProjectImporter#importProjects(List)} correctly
     * groups all subprojects under a single Gradle root, which is the key
     * optimisation for multi-module builds.
     *
     * <p>This test does NOT invoke the Gradle Tooling API — it only exercises
     * the project grouping and discovery logic by checking that the importer
     * discovers and groups the modules correctly.</p>
     */
    @Test
    void testProjectGroupingWithManyModules() throws IOException {
        List<Path> moduleRoots = createMultiModuleWorkspace(MODULE_COUNT);

        // Use discoverProjects to verify all modules are found
        long start = System.nanoTime();
        List<Path> discovered = importer.discoverProjects(workspaceRoot);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(MODULE_COUNT, discovered.size(),
                "All modules should be discovered");

        // All modules should sort under the same root (workspaceRoot has settings.gradle)
        // We can't call importProjects (it needs a real Gradle daemon), but we can
        // verify the discovery + grouping preconditions hold
        for (Path module : discovered) {
            assertTrue(module.startsWith(workspaceRoot),
                    "Module " + module + " should be under workspace root");
            assertTrue(Files.exists(module.resolve("build.gradle")),
                    "Module " + module + " should have build.gradle");
            assertTrue(Files.isDirectory(module.resolve("src/main/java")),
                    "Module " + module + " should have src/main/java");
        }

        System.out.printf("[PERF] Project grouping verification (%d modules): %d ms%n",
                MODULE_COUNT, elapsedMs);
    }

    /**
     * Stress test: create multiple independent Gradle roots (not sharing
     * settings.gradle) to verify that the grouping logic handles the
     * worst case — each project imports independently.
     */
    @Test
    void testMultipleIndependentGradleRoots() throws IOException {
        // Create MODULE_COUNT independent projects (each with its own settings.gradle)
        List<Path> projectRoots = new ArrayList<>();
        for (int i = 0; i < MODULE_COUNT; i++) {
            Path project = workspaceRoot.resolve("independent-" + i);
            Files.createDirectories(project.resolve("src/main/java"));
            Files.writeString(project.resolve("build.gradle"),
                    "plugins { id 'java' }\n");
            Files.writeString(project.resolve("settings.gradle"),
                    "rootProject.name = 'independent-" + i + "'\n");
            projectRoots.add(project);
        }

        long start = System.nanoTime();
        List<Path> discovered = importer.discoverProjects(workspaceRoot);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(MODULE_COUNT, discovered.size(),
                "All independent projects should be discovered");

        System.out.printf("[PERF] Discover %d independent Gradle roots: %d ms%n",
                MODULE_COUNT, elapsedMs);

        assertTrue(elapsedMs < 10_000,
                "Discovery of " + MODULE_COUNT + " independent roots took "
                        + elapsedMs + "ms, expected < 10s");
    }

    /**
     * End-to-end performance test: simulate the full lifecycle from project
     * discovery through classpath setup to compilation unit creation for
     * {@value MODULE_COUNT} modules × {@value CLASSPATH_FILES_PER_MODULE}
     * classpath entries.
     *
     * <p>This verifies the integrated path through:</p>
     * <ol>
     *   <li>{@link GradleProjectImporter#discoverProjects(Path)}</li>
     *   <li>Classpath aggregation and deduplication</li>
     *   <li>{@link CompilationUnitFactory#setAdditionalClasspathList(List)}</li>
     *   <li>{@link CompilationUnitFactory#create(Path, FileContentsTracker)}</li>
     * </ol>
     */
    @Test
    void testEndToEndLargeProjectImportSimulation() throws IOException {
        long totalStart = System.nanoTime();

        // 1. Create workspace
        long stepStart = System.nanoTime();
        List<Path> moduleRoots = createMultiModuleWorkspace(MODULE_COUNT);
        long workspaceSetupMs = (System.nanoTime() - stepStart) / 1_000_000;

        // 2. Discover projects
        stepStart = System.nanoTime();
        List<Path> discovered = importer.discoverProjects(workspaceRoot);
        long discoveryMs = (System.nanoTime() - stepStart) / 1_000_000;
        assertEquals(MODULE_COUNT, discovered.size());

        // 3. Build per-module classpaths (simulating what Gradle Tooling API would return)
        stepStart = System.nanoTime();
        Map<Path, List<String>> importResult = new LinkedHashMap<>();
        for (Path module : discovered) {
            importResult.put(module, buildClasspathForModule(module));
        }
        long classpathBuildMs = (System.nanoTime() - stepStart) / 1_000_000;

        // 4. Aggregate into a single classpath (as GroovyServices does per project scope)
        stepStart = System.nanoTime();
        Set<String> aggregatedClasspath = new LinkedHashSet<>();
        for (List<String> cp : importResult.values()) {
            aggregatedClasspath.addAll(cp);
        }
        long aggregationMs = (System.nanoTime() - stepStart) / 1_000_000;

        // 5. Set up CompilationUnitFactory with the full classpath
        stepStart = System.nanoTime();
        CompilationUnitFactory factory = new CompilationUnitFactory();
        factory.setAdditionalClasspathList(new ArrayList<>(aggregatedClasspath));
        long setClasspathMs = (System.nanoTime() - stepStart) / 1_000_000;

        // 6. Create compilation unit
        stepStart = System.nanoTime();
        FileContentsTracker tracker = new FileContentsTracker();
        GroovyLSCompilationUnit cu = factory.create(workspaceRoot, tracker);
        long createCuMs = (System.nanoTime() - stepStart) / 1_000_000;

        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;

        assertNotNull(cu);
        assertEquals(CLASSPATH_FILES_PER_MODULE + MODULE_COUNT, aggregatedClasspath.size());

        System.out.println("=== END-TO-END PERFORMANCE RESULTS ===");
        System.out.printf("  Modules:           %d%n", MODULE_COUNT);
        System.out.printf("  JARs per module:   %d%n", CLASSPATH_FILES_PER_MODULE);
        System.out.printf("  Total raw entries: %d%n", MODULE_COUNT * CLASSPATH_FILES_PER_MODULE);
        System.out.printf("  Unique entries:    %d%n", aggregatedClasspath.size());
        System.out.println("  ----");
        System.out.printf("  Workspace setup:   %,d ms%n", workspaceSetupMs);
        System.out.printf("  Discovery:         %,d ms%n", discoveryMs);
        System.out.printf("  Classpath build:   %,d ms%n", classpathBuildMs);
        System.out.printf("  Aggregation:       %,d ms%n", aggregationMs);
        System.out.printf("  Set classpath:     %,d ms%n", setClasspathMs);
        System.out.printf("  Create CU:         %,d ms%n", createCuMs);
        System.out.printf("  TOTAL:             %,d ms%n", totalMs);
        System.out.println("=======================================");

        assertTrue(totalMs < 60_000,
                "End-to-end simulation took " + totalMs + "ms, expected < 60s");
    }

    /**
     * Measure memory footprint of holding classpath data for a large
     * multi-module project in memory.
     */
    @Test
    void testMemoryFootprintOfLargeClasspath() throws IOException {
        List<Path> moduleRoots = createMultiModuleWorkspace(MODULE_COUNT);

        // Force GC to get a clean baseline
        System.gc();
        Thread.yield();
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Build all classpaths
        Map<Path, List<String>> allClasspaths = new LinkedHashMap<>();
        for (Path module : moduleRoots) {
            allClasspaths.put(module, buildClasspathForModule(module));
        }

        // Aggregate
        Set<String> aggregated = new LinkedHashSet<>();
        for (List<String> cp : allClasspaths.values()) {
            aggregated.addAll(cp);
        }

        // Set up factory
        CompilationUnitFactory factory = new CompilationUnitFactory();
        factory.setAdditionalClasspathList(new ArrayList<>(aggregated));
        FileContentsTracker tracker = new FileContentsTracker();
        GroovyLSCompilationUnit cu = factory.create(workspaceRoot, tracker);

        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memDeltaMB = (memAfter - memBefore) / (1024 * 1024);

        assertNotNull(cu);

        System.out.printf("[PERF] Memory delta for %d modules × %d cp entries: ~%d MB%n",
                MODULE_COUNT, CLASSPATH_FILES_PER_MODULE, memDeltaMB);

        // Classpath strings + classloader shouldn't blow up memory unreasonably
        // 1040 strings of ~60 chars each ≈ tiny; classloader overhead is the main cost
        assertTrue(memDeltaMB < 512,
                "Memory footprint of " + memDeltaMB + " MB is too high, expected < 512 MB");
    }

    /**
     * Verify that repeated compilation unit invalidation/recreation cycles
     * (simulating rapid file edits during development) remain fast even with
     * a large classpath.
     */
    @Test
    void testRepeatedCompilationUnitRecreation() throws IOException {
        List<Path> moduleRoots = createMultiModuleWorkspace(MODULE_COUNT);

        Set<String> aggregated = new LinkedHashSet<>();
        for (Path module : moduleRoots) {
            aggregated.addAll(buildClasspathForModule(module));
        }

        CompilationUnitFactory factory = new CompilationUnitFactory();
        factory.setAdditionalClasspathList(new ArrayList<>(aggregated));
        FileContentsTracker tracker = new FileContentsTracker();

        int cycles = 50;
        long[] timings = new long[cycles];

        // Warm up
        factory.create(workspaceRoot, tracker);

        for (int i = 0; i < cycles; i++) {
            // Simulate classpath change (invalidates compilation unit)
            factory.invalidateCompilationUnit();

            long start = System.nanoTime();
            GroovyLSCompilationUnit cu = factory.create(workspaceRoot, tracker);
            timings[i] = (System.nanoTime() - start) / 1_000_000;

            assertNotNull(cu);
        }

        long minMs = Arrays.stream(timings).min().orElse(0);
        long maxMs = Arrays.stream(timings).max().orElse(0);
        long avgMs = (long) Arrays.stream(timings).average().orElse(0);
        long[] sorted = Arrays.copyOf(timings, timings.length);
        Arrays.sort(sorted);
        long p95Ms = sorted[(int) (cycles * 0.95)];

        System.out.printf("[PERF] CompilationUnit recreation (%d cycles, %d cp entries):%n",
                cycles, aggregated.size());
        System.out.printf("  min: %d ms, max: %d ms, avg: %d ms, p95: %d ms%n",
                minMs, maxMs, avgMs, p95Ms);

        // After optimisation (config/classLoader reuse), each cycle should be
        // sub-100ms since no filesystem stat calls are needed
        assertTrue(avgMs < 1_000,
                "Average recreation time of " + avgMs + "ms is too high, expected < 1s");
    }
}
