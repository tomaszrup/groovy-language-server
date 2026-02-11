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

import com.tomaszrup.groovyls.ProjectScope;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.MemoryProfiler;
import org.codehaus.groovy.control.Phases;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extreme-scale memory profiling tests for large multi-project workspaces.
 *
 * <p>These tests simulate enterprise-scale workspaces with 50+ projects, each having
 * 50,000+ classpath entries, to measure memory footprint, identify retention issues,
 * and validate capacity planning assumptions.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Run with profiler-friendly JVM flags:
 * ./gradlew memoryTest
 *
 * # Or manually:
 * ./gradlew test --tests LargeWorkspaceMemoryTests \
 *   -Dgroovyls.test.projectCount=50 \
 *   -Dgroovyls.test.classpathSize=50000 \
 *   -Dgroovyls.test.heapDump=true
 * </pre>
 *
 * <h2>Configuration (via system properties)</h2>
 * <ul>
 *   <li>{@code groovyls.test.projectCount} — Number of projects to simulate (default: 50)</li>
 *   <li>{@code groovyls.test.classpathSize} — Classpath entries per project (default: 50000)</li>
 *   <li>{@code groovyls.test.jarSize} — Size of each dummy JAR in bytes (default: 1024)</li>
 *   <li>{@code groovyls.test.heapDump} — Capture heap dumps at key points (default: false)</li>
 *   <li>{@code groovyls.test.disableEviction} — Disable scope eviction for worst-case retention (default: false)</li>
 * </ul>
 *
 * <h2>Heap Dump Analysis</h2>
 * <p>When {@code groovyls.test.heapDump=true}, heap dumps are written to {@code build/heap-dumps/}
 * with timestamps. Analyze them with:</p>
 * <ul>
 *   <li><strong>Eclipse Memory Analyzer (MAT):</strong> Leak suspects, dominator tree, histogram</li>
 *   <li><strong>VisualVM:</strong> OQL queries, thread dumps, class retention paths</li>
 *   <li><strong>JProfiler / YourKit:</strong> Allocation hot spots, GC root analysis</li>
 * </ul>
 *
 * <h2>Memory Metrics</h2>
 * <p>Tests measure:</p>
 * <ul>
 *   <li><strong>Peak heap during initialization:</strong> Max heap used while loading projects and classpaths</li>
 *   <li><strong>Steady-state heap after compilation:</strong> Memory footprint with all scopes live</li>
 *   <li><strong>Memory per project:</strong> Average heap delta per project scope (for scaling estimates)</li>
 * </ul>
 *
 * <h2>Expected Results (baseline: 8GB heap, G1GC)</h2>
 * <table border="1">
 *   <tr><th>Metric</th><th>50 projects × 50k cp</th><th>Notes</th></tr>
 *   <tr><td>Total classpath entries</td><td>~2.5M unique</td><td>Deduplicated across projects</td></tr>
 *   <tr><td>JAR generation time</td><td>&lt; 5 min</td><td>Parallelized, 1KB JARs</td></tr>
 *   <tr><td>Peak heap (initialization)</td><td>&lt; 6 GB</td><td>Includes classloader overhead</td></tr>
 *   <tr><td>Steady-state heap</td><td>&lt; 5 GB</td><td>After full compilation</td></tr>
 *   <tr><td>Memory per project</td><td>~100 MB</td><td>Includes AST + classloader</td></tr>
 * </table>
 */
class LargeWorkspaceMemoryTests {

    // ---- Configuration from system properties ----

    private static final int PROJECT_COUNT = Integer.getInteger("groovyls.test.projectCount", 50);
    private static final int CLASSPATH_SIZE = Integer.getInteger("groovyls.test.classpathSize", 50_000);
    private static final int JAR_SIZE_BYTES = Integer.getInteger("groovyls.test.jarSize", 1_500_000); // 1.5MB default
    private static final int SOURCE_FILES_PER_PROJECT = Integer.getInteger("groovyls.test.sourceFilesPerProject", 30);
    private static final boolean HEAP_DUMP_ENABLED = Boolean.getBoolean("groovyls.test.heapDump");
    private static final boolean DISABLE_EVICTION = Boolean.getBoolean("groovyls.test.disableEviction");
    private static final boolean SKIP_COMPILATION = Boolean.getBoolean("groovyls.test.skipCompilation");

    // ---- Test state ----

    private Path workspaceRoot;
    private Path tempDir;
    private GradleProjectImporter importer;

    /** Shared JAR directory containing all dummy JARs (unique per project). */
    private static Path sharedJarDir;
    /** All generated JAR paths, organized by project. */
    private static Map<Integer, List<Path>> jarPathsByProject;

    // ---- Lifecycle ----

    @BeforeAll
    static void generateDummyJars() throws IOException {
        long start = System.nanoTime();
        System.out.println("╔═══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  LARGE WORKSPACE MEMORY PROFILING TEST                                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════╝");
        System.out.printf("Configuration:%n");
        System.out.printf("  Projects:             %,d%n", PROJECT_COUNT);
        System.out.printf("  Classpath per project: %,d entries%n", CLASSPATH_SIZE);
        System.out.printf("  JAR size:             %,d bytes (%.1f MB)%n", JAR_SIZE_BYTES, JAR_SIZE_BYTES / 1024.0 / 1024.0);
        System.out.printf("  Source files/project: %,d%n", SOURCE_FILES_PER_PROJECT);
        System.out.printf("  Total classpath:      %,d entries%n", (long) PROJECT_COUNT * CLASSPATH_SIZE);
        System.out.printf("  Total disk space:     %.1f GB%n", (long) PROJECT_COUNT * CLASSPATH_SIZE * JAR_SIZE_BYTES / 1024.0 / 1024.0 / 1024.0);
        System.out.printf("  Heap dump enabled:    %s%n", HEAP_DUMP_ENABLED);
        System.out.printf("  Eviction disabled:    %s%n", DISABLE_EVICTION);
        System.out.printf("  Skip compilation:     %s%n", SKIP_COMPILATION);
        System.out.println();
        
        if (CLASSPATH_SIZE > 10000 && !SKIP_COMPILATION) {
            System.out.println("⚠️  WARNING: Large classpath (" + CLASSPATH_SIZE + " entries) detected.");
            System.out.println("   Phase 6 (Compilation Unit Creation) may take 5-30+ minutes.");
            System.out.println("   To skip compilation and measure only classpath memory, use:");
            System.out.println("   -Dgroovyls.test.skipCompilation=true");
            System.out.println();
        }

        sharedJarDir = Files.createTempDirectory("groovyls-memory-test-jars");
        jarPathsByProject = new HashMap<>();

        System.out.printf("Generating %,d dummy JARs in %s...%n", 
                (long) PROJECT_COUNT * CLASSPATH_SIZE, sharedJarDir);

        // Generate JARs in parallel for speed
        AtomicInteger progress = new AtomicInteger(0);
        int totalJars = PROJECT_COUNT * CLASSPATH_SIZE;
        
        IntStream.range(0, PROJECT_COUNT).parallel().forEach(projectId -> {
            List<Path> jarsForProject = new ArrayList<>(CLASSPATH_SIZE);
            try {
                for (int i = 0; i < CLASSPATH_SIZE; i++) {
                    Path jar = sharedJarDir.resolve(
                            String.format("project-%d-dependency-%d-1.0.jar", projectId, i));
                    generateMinimalJar(jar, JAR_SIZE_BYTES);
                    jarsForProject.add(jar);

                    int current = progress.incrementAndGet();
                    if (current % 10000 == 0) {
                        System.out.printf("  Progress: %,d / %,d JARs (%.1f%%)%n", 
                                current, totalJars, 100.0 * current / totalJars);
                    }
                }
                synchronized (jarPathsByProject) {
                    jarPathsByProject.put(projectId, jarsForProject);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to generate JARs for project " + projectId, e);
            }
        });

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long totalSizeMB = (long) totalJars * JAR_SIZE_BYTES / (1024 * 1024);
        
        System.out.printf("✓ Generated %,d JARs in %,d ms (~%,d MB on disk)%n", 
                totalJars, elapsedMs, totalSizeMB);
        System.out.println();

        if (HEAP_DUMP_ENABLED) {
            dumpHeap("00-baseline-before-test");
        }
    }

    @AfterAll
    static void cleanupDummyJars() throws IOException {
        if (sharedJarDir != null && Files.exists(sharedJarDir)) {
            System.out.println("Cleaning up JAR directory...");
            Files.walk(sharedJarDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("groovyls-memory-workspace");
        workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        importer = new GradleProjectImporter();
    }

    @AfterEach
    void tearDown() throws IOException {
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

    // ---- Helper methods ----

    /** Reusable manifest instance (thread-safe, immutable after creation). */
    private static final Manifest SHARED_MANIFEST = new Manifest();
    
    /** Pre-filled padding buffer (reused across all JARs). */
    private static final byte[] PADDING_BUFFER = new byte[8192];
    static {
        Arrays.fill(PADDING_BUFFER, (byte) 'X');
    }

    /**
     * Generate a realistic JAR file at the specified size.
     * 
     * <p><strong>Realistic structure for memory profiling:</strong></p>
     * <ul>
     *   <li>Contains dummy .class files to simulate Spring Boot JARs</li>
     *   <li>Uses {@link ZipOutputStream#STORED} (no compression) for speed</li>
     *   <li>Pre-calculates CRC32 for all entries</li>
     *   <li>1.5-2MB JARs similar to real libraries (spring-core, hibernate, etc.)</li>
     * </ul>
     */
    private static void generateMinimalJar(Path jarPath, int targetSizeBytes) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream(jarPath), SHARED_MANIFEST)) {
            
            // Disable compression for speed (STORED = uncompressed)
            jos.setMethod(ZipOutputStream.STORED);
            
            // Add multiple dummy class files to simulate real Jars
            // Real JARs contain many classes, we'll add ~20-50 dummy entries
            int numClassFiles = Math.max(10, targetSizeBytes / 50000); // ~50KB per "class"
            int bytesPerFile = targetSizeBytes / numClassFiles;
            
            for (int i = 0; i < numClassFiles; i++) {
                String className = String.format("com/example/lib%d/DummyClass%d.class", i % 10, i);
                
                // Create entry with realistic size
                ZipEntry entry = new ZipEntry(className);
                entry.setMethod(ZipOutputStream.STORED);
                entry.setSize(bytesPerFile);
                entry.setCompressedSize(bytesPerFile);
                
                // Calculate CRC for the dummy content
                CRC32 crc = new CRC32();
                int remaining = bytesPerFile;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, PADDING_BUFFER.length);
                    crc.update(PADDING_BUFFER, 0, chunk);
                    remaining -= chunk;
                }
                entry.setCrc(crc.getValue());
                
                // Write entry
                jos.putNextEntry(entry);
                remaining = bytesPerFile;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, PADDING_BUFFER.length);
                    jos.write(PADDING_BUFFER, 0, chunk);
                    remaining -= chunk;
                }
                jos.closeEntry();
            }
        }
    }

    /**
     * Create a synthetic multi-project Gradle workspace with realistic source files.
     */
    private List<Path> createLargeWorkspace(int projectCount) throws IOException {
        // Root settings.gradle listing all subprojects
        StringBuilder settings = new StringBuilder();
        settings.append("rootProject.name = 'memory-test-workspace'\n");
        for (int i = 0; i < projectCount; i++) {
            settings.append("include 'project-").append(i).append("'\n");
        }
        Files.writeString(workspaceRoot.resolve("settings.gradle"), settings.toString());
        Files.writeString(workspaceRoot.resolve("build.gradle"), 
                "// Root build file for memory profiling test\n");

        List<Path> projectRoots = new ArrayList<>(projectCount);
        for (int i = 0; i < projectCount; i++) {
            Path project = workspaceRoot.resolve("project-" + i);
            Files.createDirectories(project.resolve("src/main/java"));
            Files.createDirectories(project.resolve("src/main/groovy"));
            Files.createDirectories(project.resolve("src/test/groovy"));
            Files.createDirectories(project.resolve("build/classes/java/main"));

            // Create a simple build.gradle
            Files.writeString(project.resolve("build.gradle"),
                    "plugins { id 'java'; id 'groovy' }\n" +
                    "dependencies {\n" +
                    "  // Simulated large classpath\n" +
                    "}\n");

            // Create multiple realistic Groovy source files to generate realistic AST
            for (int fileNum = 0; fileNum < SOURCE_FILES_PER_PROJECT; fileNum++) {
                String className = String.format("Service%d", fileNum);
                Path groovyFile = project.resolve(String.format("src/main/groovy/%s.groovy", className));
                
                // Generate realistic Groovy class with fields, methods, imports
                StringBuilder classContent = new StringBuilder();
                classContent.append("package com.example.project").append(i).append("\n\n");
                classContent.append("import groovy.transform.CompileStatic\n");
                classContent.append("import java.util.List\n");
                classContent.append("import java.util.Map\n\n");
                classContent.append("@CompileStatic\n");
                classContent.append("class ").append(className).append(" {\n\n");
                
                // Add several fields
                classContent.append("    private String name\n");
                classContent.append("    private int count\n");
                classContent.append("    private List<String> items = []\n");
                classContent.append("    private Map<String, Object> config = [:]\n\n");
                
                // Add several methods
                classContent.append("    void doSomething() {\n");
                classContent.append("        println \"").append(className).append(": ${name}\"\n");
                classContent.append("        count++\n");
                classContent.append("    }\n\n");
                
                classContent.append("    String getName() { name }\n");
                classContent.append("    void setName(String name) { this.name = name }\n\n");
                
                classContent.append("    int getCount() { count }\n\n");
                
                classContent.append("    void addItem(String item) {\n");
                classContent.append("        items << item\n");
                classContent.append("    }\n\n");
                
                classContent.append("    List<String> getItems() {\n");
                classContent.append("        items.asImmutable()\n");
                classContent.append("    }\n\n");
                
                classContent.append("    void configure(Map<String, Object> newConfig) {\n");
                classContent.append("        config.putAll(newConfig)\n");
                classContent.append("    }\n");
                
                classContent.append("}\n");
                
                Files.writeString(groovyFile, classContent.toString());
            }
            
            // Add a test file too
            Path testFile = project.resolve("src/test/groovy/TestSpec.groovy");
            Files.writeString(testFile,
                    "package com.example.project" + i + "\n\n" +
                    "import spock.lang.Specification\n\n" +
                    "class TestSpec extends Specification {\n" +
                    "    def \"test something\"() {\n" +
                    "        expect:\n" +
                    "        true\n" +
                    "    }\n" +
                    "}\n");

            projectRoots.add(project);
        }

        return projectRoots;
    }

    /**
     * Build classpath for a specific project.
     */
    private List<String> buildClasspathForProject(Path projectRoot, int projectId) {
        List<Path> jars = jarPathsByProject.get(projectId);
        if (jars == null) {
            throw new IllegalStateException("No JARs generated for project " + projectId);
        }

        List<String> classpath = new ArrayList<>(jars.size() + 1);
        for (Path jar : jars) {
            classpath.add(jar.toAbsolutePath().toString());
        }
        classpath.add(projectRoot.resolve("build/classes/java/main").toAbsolutePath().toString());
        return classpath;
    }

    /**
     * Capture a heap dump using HotSpotDiagnosticMXBean.
     */
    private static void dumpHeap(String label) {
        if (!HEAP_DUMP_ENABLED) {
            return;
        }

        try {
            Path heapDumpDir = Path.of("build/heap-dumps");
            Files.createDirectories(heapDumpDir);

            String timestamp = String.format("%tY%<tm%<td-%<tH%<tM%<tS", System.currentTimeMillis());
            Path heapDumpFile = heapDumpDir.resolve(
                    String.format("heap-%s-%s.hprof", label, timestamp));

            Class<?> mxBeanClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object mxBean = ManagementFactory.newPlatformMXBeanProxy(
                    ManagementFactory.getPlatformMBeanServer(),
                    "com.sun.management:type=HotSpotDiagnostic",
                    mxBeanClass);

            mxBeanClass.getMethod("dumpHeap", String.class, boolean.class)
                    .invoke(mxBean, heapDumpFile.toAbsolutePath().toString(), true);

            System.out.printf("📸 Heap dump: %s (%.1f MB)%n", 
                    heapDumpFile, Files.size(heapDumpFile) / (1024.0 * 1024.0));
        } catch (Exception e) {
            System.err.println("⚠️  Failed to capture heap dump: " + e.getMessage());
        }
    }

    /**
     * Get current heap usage in MB.
     */
    private static long getCurrentHeapMB() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    /**
     * Force garbage collection and wait.
     */
    private static void forceGC() {
        System.gc();
        System.gc();
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
    }

    // ---- Tests ----

    /**
     * Main memory profiling test: measure heap footprint of a workspace with
     * {@value PROJECT_COUNT} projects × {@value CLASSPATH_SIZE} classpath entries.
     *
     * <p>This test simulates the full lifecycle:</p>
     * <ol>
     *   <li>Project discovery</li>
     *   <li>Classpath resolution (simulated)</li>
     *   <li>Classpath aggregation and deduplication</li>
     *   <li>Compilation unit creation with full classpath</li>
     *   <li>Per-project scope instantiation</li>
     * </ol>
     */
    @Test
    void testMemoryFootprintWith50ProjectsAnd50kClasspathEach() throws IOException {
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("TEST: Memory Footprint — " + PROJECT_COUNT + " projects × " + 
                CLASSPATH_SIZE + " classpath entries");
        System.out.println("═══════════════════════════════════════════════════════════════════");

        // ---- Phase 1: Workspace creation ----
        long phaseStart = System.nanoTime();
        forceGC();
        long baselineHeapMB = getCurrentHeapMB();
        System.out.printf("%n[PHASE 1] Workspace Creation%n");
        System.out.printf("  Baseline heap: %,d MB%n", baselineHeapMB);

        List<Path> projectRoots = createLargeWorkspace(PROJECT_COUNT);
        long workspaceMs = (System.nanoTime() - phaseStart) / 1_000_000;
        System.out.printf("  Created %,d projects in %,d ms%n", projectRoots.size(), workspaceMs);

        // ---- Phase 2: Project discovery ----
        phaseStart = System.nanoTime();
        System.out.printf("%n[PHASE 2] Project Discovery%n");
        
        List<Path> discovered = importer.discoverProjects(workspaceRoot);
        long discoveryMs = (System.nanoTime() - phaseStart) / 1_000_000;
        
        assertEquals(PROJECT_COUNT, discovered.size(), 
                "Should discover all " + PROJECT_COUNT + " projects");
        System.out.printf("  Discovered %,d projects in %,d ms%n", discovered.size(), discoveryMs);

        // ---- Phase 3: Classpath resolution ----
        phaseStart = System.nanoTime();
        System.out.printf("%n[PHASE 3] Classpath Resolution (simulated)%n");
        
        Map<Path, List<String>> classpathsByProject = new LinkedHashMap<>();
        for (int i = 0; i < discovered.size(); i++) {
            Path project = discovered.get(i);
            classpathsByProject.put(project, buildClasspathForProject(project, i));
        }
        
        long resolutionMs = (System.nanoTime() - phaseStart) / 1_000_000;
        long totalEntries = classpathsByProject.values().stream()
                .mapToLong(List::size)
                .sum();
        System.out.printf("  Built classpaths for %,d projects in %,d ms%n", 
                classpathsByProject.size(), resolutionMs);
        System.out.printf("  Total classpath entries (raw): %,d%n", totalEntries);

        long heapAfterResolution = getCurrentHeapMB();
        System.out.printf("  Heap after resolution: %,d MB (+%,d MB)%n", 
                heapAfterResolution, heapAfterResolution - baselineHeapMB);

        if (HEAP_DUMP_ENABLED) {
            dumpHeap("01-after-classpath-resolution");
        }

        // ---- Phase 4: Classpath aggregation ----
        phaseStart = System.nanoTime();
        System.out.printf("%n[PHASE 4] Classpath Aggregation%n");
        
        Set<String> aggregatedClasspath = new LinkedHashSet<>();
        for (List<String> cp : classpathsByProject.values()) {
            aggregatedClasspath.addAll(cp);
        }
        
        long aggregationMs = (System.nanoTime() - phaseStart) / 1_000_000;
        System.out.printf("  Aggregated to %,d unique entries in %,d ms%n", 
                aggregatedClasspath.size(), aggregationMs);
        System.out.printf("  Deduplication ratio: %.1f%%%n", 
                100.0 * (1 - (double) aggregatedClasspath.size() / totalEntries));

        // ---- Phase 5: Compilation unit factory setup ----
        phaseStart = System.nanoTime();
        System.out.printf("%n[PHASE 5] Compilation Unit Factory Setup%n");
        
        CompilationUnitFactory factory = new CompilationUnitFactory();
        factory.setAdditionalClasspathList(new ArrayList<>(aggregatedClasspath));
        
        long setupMs = (System.nanoTime() - phaseStart) / 1_000_000;
        System.out.printf("  Set classpath on factory in %,d ms%n", setupMs);

        long heapAfterSetup = getCurrentHeapMB();
        System.out.printf("  Heap after factory setup: %,d MB (+%,d MB)%n", 
                heapAfterSetup, heapAfterSetup - heapAfterResolution);

        // ---- Phase 6: Compilation unit creation (per-project simulation) ----
        List<GroovyLSCompilationUnit> compilationUnits = new ArrayList<>();
        long peakHeapDuringCompilation = heapAfterSetup;
        long compilationMs = 0;
        
        if (SKIP_COMPILATION) {
            System.out.printf("%n[PHASE 6] Compilation Unit Creation (SKIPPED)%n");
            System.out.printf("  Skipped per configuration (groovyls.test.skipCompilation=true)%n");
        } else {
            phaseStart = System.nanoTime();
            System.out.printf("%n[PHASE 6] Compilation Unit Creation%n");
            System.out.printf("  Creating compilation units with %,d classpath entries...%n", 
                    aggregatedClasspath.size());
            System.out.printf("  Note: This may take several minutes with large classpaths.%n");
            System.out.flush();
            
            FileContentsTracker tracker = new FileContentsTracker();
            
            // Create compilation units for all projects
            for (int i = 0; i < projectRoots.size(); i++) {
                Path project = projectRoots.get(i);
                long cuStart = System.nanoTime();
                
                System.out.printf("    Creating compilation unit for project %d/%d...%n", 
                        i + 1, projectRoots.size());
                System.out.flush();
                
                GroovyLSCompilationUnit cu = factory.create(project, tracker);
                
                // Load all Groovy source files into the compilation unit
                Path srcMainGroovy = project.resolve("src/main/groovy");
                if (Files.exists(srcMainGroovy)) {
                    try (var stream = Files.walk(srcMainGroovy)) {
                        stream.filter(p -> p.toString().endsWith(".groovy"))
                              .forEach(groovyFile -> cu.addSource(groovyFile.toFile()));
                    }
                }
                
                Path srcTestGroovy = project.resolve("src/test/groovy");
                if (Files.exists(srcTestGroovy)) {
                    try (var stream = Files.walk(srcTestGroovy)) {
                        stream.filter(p -> p.toString().endsWith(".groovy"))
                              .forEach(groovyFile -> cu.addSource(groovyFile.toFile()));
                    }
                }
                
                // Actually compile to generate AST structures and trigger parsing
                try {
                    cu.compile(Phases.CONVERSION);
                } catch (Exception e) {
                    // Compilation may fail with dummy classpath, but AST is still built
                    System.out.printf("    (Compilation errors expected with dummy classpath)%n");
                }
                
                compilationUnits.add(cu);
                
                long cuMs = (System.nanoTime() - cuStart) / 1_000_000;
                long currentHeap = getCurrentHeapMB();
                peakHeapDuringCompilation = Math.max(peakHeapDuringCompilation, currentHeap);
                
                System.out.printf("    Project %d: created CU in %,d ms (heap: %,d MB)%n", 
                        i + 1, cuMs, currentHeap);
                
                // Invalidate for next iteration to simulate multiple scopes
                factory.invalidateCompilationUnit();
            }
            
            compilationMs = (System.nanoTime() - phaseStart) / 1_000_000;
            System.out.printf("  Created %d compilation unit(s) in %,d ms%n", 
                    compilationUnits.size(), compilationMs);
            System.out.printf("  Peak heap during compilation: %,d MB%n", peakHeapDuringCompilation);
        }

        if (HEAP_DUMP_ENABLED) {
            dumpHeap("02-after-compilation");
        }

        // ---- Phase 7: Steady-state measurement ----
        forceGC();
        long steadyStateHeapMB = getCurrentHeapMB();
        
        System.out.printf("%n[PHASE 7] Steady-State Measurement%n");
        System.out.printf("  Steady-state heap: %,d MB%n", steadyStateHeapMB);
        System.out.printf("  Total heap growth: %,d MB%n", steadyStateHeapMB - baselineHeapMB);
        
        long heapPerProject = compilationUnits.isEmpty() ? 0 : 
                (steadyStateHeapMB - baselineHeapMB) / compilationUnits.size();
        System.out.printf("  Estimated heap per project: ~%,d MB%n", heapPerProject);

        // ---- Memory Profiler breakdown ----
        if (!compilationUnits.isEmpty()) {
            System.out.printf("%n[PHASE 7b] MemoryProfiler Breakdown%n");
            List<ProjectScope> testScopes = new ArrayList<>();
            for (int i = 0; i < projectRoots.size(); i++) {
                CompilationUnitFactory scopeFactory = new CompilationUnitFactory();
                scopeFactory.setAdditionalClasspathList(
                        new ArrayList<>(classpathsByProject.get(projectRoots.get(i))));
                ProjectScope ps = new ProjectScope(projectRoots.get(i), scopeFactory);
                if (i < compilationUnits.size()) {
                    ps.setCompilationUnit(compilationUnits.get(i));
                }
                testScopes.add(ps);
            }
            // Always log in tests regardless of system property
            for (ProjectScope ps : testScopes) {
                Map<String, Double> breakdown = MemoryProfiler.estimateComponentSizes(ps);
                double total = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
                System.out.printf("  %s (%.1f MB)%n", ps.getProjectRoot().getFileName(), total);
                breakdown.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .limit(3)
                        .forEach(e -> System.out.printf("       %s: %.1f MB%n", e.getKey(), e.getValue()));
            }
            // Also call logProfile to exercise the full flow
            MemoryProfiler.logProfile(testScopes);
        }

        if (HEAP_DUMP_ENABLED) {
            dumpHeap("03-steady-state");
        }

        // ---- Summary ----
        System.out.printf("%n╔═══════════════════════════════════════════════════════════════════════╗%n");
        System.out.printf("║  MEMORY PROFILING SUMMARY                                             ║%n");
        System.out.printf("╠═══════════════════════════════════════════════════════════════════════╣%n");
        System.out.printf("║  Projects:                  %,10d                                  ║%n", PROJECT_COUNT);
        System.out.printf("║  Classpath per project:     %,10d entries                         ║%n", CLASSPATH_SIZE);
        System.out.printf("║  Total raw entries:         %,10d                                  ║%n", totalEntries);
        System.out.printf("║  Unique entries:            %,10d                                  ║%n", aggregatedClasspath.size());
        System.out.printf("║  ────────────────────────────────────────────────────────────────────  ║%n");
        System.out.printf("║  Baseline heap:             %,10d MB                               ║%n", baselineHeapMB);
        System.out.printf("║  Peak heap (compilation):   %,10d MB                               ║%n", peakHeapDuringCompilation);
        System.out.printf("║  Steady-state heap:         %,10d MB                               ║%n", steadyStateHeapMB);
        System.out.printf("║  Total growth:              %,10d MB                               ║%n", steadyStateHeapMB - baselineHeapMB);
        System.out.printf("║  Heap per project:          %,10d MB (estimated)                   ║%n", heapPerProject);
        System.out.printf("╚═══════════════════════════════════════════════════════════════════════╝%n");

        // Assertions (lenient — focus is on measurement, not pass/fail)
        if (!SKIP_COMPILATION) {
            assertTrue(compilationUnits.size() > 0, "Should create at least one compilation unit");
        }
        assertTrue(steadyStateHeapMB >= baselineHeapMB, "Heap should grow with workspace loaded");
    }

    /**
     * Measure scaling characteristics: memory growth as project count increases.
     */
    @Test
    @Disabled("Long-running test — enable manually for scaling analysis")
    void testMemoryScalingWithIncrementalProjects() throws IOException {
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("TEST: Memory Scaling Analysis");
        System.out.println("═══════════════════════════════════════════════════════════════════");

        forceGC();
        long baselineHeapMB = getCurrentHeapMB();

        int[] projectCounts = {1, 5, 10, 20, 50};
        List<Long> heapMeasurements = new ArrayList<>();

        for (int count : projectCounts) {
            System.out.printf("%n--- Testing with %d projects ---%n", count);

            List<Path> projectRoots = createLargeWorkspace(count);
            Set<String> aggregated = new LinkedHashSet<>();

            for (int i = 0; i < count; i++) {
                aggregated.addAll(buildClasspathForProject(projectRoots.get(i), i % PROJECT_COUNT));
            }

            CompilationUnitFactory factory = new CompilationUnitFactory();
            factory.setAdditionalClasspathList(new ArrayList<>(aggregated));

            FileContentsTracker tracker = new FileContentsTracker();
            factory.create(projectRoots.get(0), tracker);

            forceGC();
            long heapMB = getCurrentHeapMB();
            heapMeasurements.add(heapMB - baselineHeapMB);

            System.out.printf("  Heap: %,d MB (+%,d MB)%n", heapMB, heapMB - baselineHeapMB);
        }

        System.out.printf("%n╔═══════════════════════════════════════════════════════════════════════╗%n");
        System.out.printf("║  SCALING ANALYSIS                                                     ║%n");
        System.out.printf("╠═══════════════════════════════════════════════════════════════════════╣%n");
        for (int i = 0; i < projectCounts.length; i++) {
            System.out.printf("║  %3d projects:  %,10d MB                                         ║%n",
                    projectCounts[i], heapMeasurements.get(i));
        }
        System.out.printf("╚═══════════════════════════════════════════════════════════════════════╝%n");
    }
}
