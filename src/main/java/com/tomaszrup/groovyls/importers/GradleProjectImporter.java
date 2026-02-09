////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.importers;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers and imports Gradle-based JVM projects. Uses the Gradle Tooling API
 * to compile Java sources, resolve dependency classpaths via an injected init
 * script, and discover compiled class output directories.
 *
 * <p><b>Performance optimisation for multi-project builds:</b> when multiple
 * subprojects share a common Gradle root (detected via {@code settings.gradle}
 * or {@code settings.gradle.kts}), a single Tooling API connection to the root
 * is used to compile <em>all</em> subprojects at once and resolve classpaths
 * for every subproject in a single init-script run. This avoids the overhead
 * of starting a separate Gradle daemon interaction per subproject, reducing
 * import time for large workspaces (e.g. 40 subprojects) from ~15 minutes to
 * under a minute.</p>
 */
public class GradleProjectImporter implements ProjectImporter {

    private static final Logger logger = LoggerFactory.getLogger(GradleProjectImporter.class);

    @Override
    public String getName() {
        return "Gradle";
    }

    @Override
    public List<Path> discoverProjects(Path workspaceRoot) throws IOException {
        List<Path> gradleProjects = new ArrayList<>();
        try (Stream<Path> fileStream = Files.walk(workspaceRoot)) {
            fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> Set.of("build.gradle", "build.gradle.kts").contains(p.getFileName().toString()))
                    .map(buildFile -> buildFile.getParent())
                    .filter(this::isJvmProject)
                    .forEach(gradleProjects::add);
        }
        return gradleProjects;
    }

    @Override
    public List<String> importProject(Path projectRoot) {
        List<String> classpathList = new ArrayList<>();
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            // Try to compile both main and test classes, but don't fail if tasks don't exist
            runCompileTasks(connection, projectRoot);

            // Resolve all dependency jars from Gradle configurations via init script
            classpathList.addAll(resolveClasspathViaInitScript(connection));

            // Also add discovered class output dirs (build/classes/**)
            classpathList.addAll(discoverClassDirs(projectRoot));

            logger.info("Classpath for project {}: {} entries", projectRoot, classpathList.size());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return classpathList;
    }

    /**
     * Batch-import all discovered Gradle projects. Groups subprojects by their
     * common Gradle root (the directory containing {@code settings.gradle}) and
     * uses a <b>single</b> Tooling API connection per root to:
     * <ol>
     *   <li>Compile all subprojects at once ({@code classes testClasses})</li>
     *   <li>Resolve classpath entries for every subproject in one init-script
     *       run, tagged by {@code projectDir} so entries can be attributed to
     *       the correct subproject</li>
     * </ol>
     *
     * <p>Standalone projects (where the project root itself contains
     * {@code settings.gradle}) are handled as single-project groups.</p>
     */
    @Override
    public Map<Path, List<String>> importProjects(List<Path> projectRoots) {
        return doImportProjects(projectRoots, true);
    }

    /**
     * Resolve classpaths for the given projects <b>without compiling</b>
     * source code.  This skips the expensive {@code classes}/{@code testClasses}
     * Gradle tasks and only resolves dependency JARs via the init-script plus
     * any already-existing compiled class output directories.
     */
    @Override
    public Map<Path, List<String>> resolveClasspaths(List<Path> projectRoots) {
        return doImportProjects(projectRoots, false);
    }

    private Map<Path, List<String>> doImportProjects(List<Path> projectRoots, boolean compile) {
        Map<Path, List<String>> result = new LinkedHashMap<>();

        // Group subprojects by their Gradle root (the dir with settings.gradle)
        Map<Path, List<Path>> groupedByRoot = groupByGradleRoot(projectRoots);

        logger.info("Grouped {} project(s) into {} Gradle root(s) (compile={})",
                projectRoots.size(), groupedByRoot.size(), compile);

        for (Map.Entry<Path, List<Path>> entry : groupedByRoot.entrySet()) {
            Path gradleRoot = entry.getKey();
            List<Path> subprojects = entry.getValue();

            logger.info("Importing Gradle root {} with {} subproject(s)", gradleRoot, subprojects.size());

            try {
                Map<Path, List<String>> batchResult = importBatch(gradleRoot, subprojects, compile);
                result.putAll(batchResult);
            } catch (Exception e) {
                logger.error("Batch import failed for Gradle root {}, falling back to individual imports: {}",
                        gradleRoot, e.getMessage(), e);
                // Fallback: import each project individually
                for (Path projectRoot : subprojects) {
                    result.put(projectRoot, importProject(projectRoot));
                }
            }
        }

        return result;
    }

    /**
     * Import a group of subprojects that share the same Gradle root using a
     * single Tooling API connection.
     *
     * @param compile if {@code true}, runs {@code classes testClasses} before
     *                resolving classpaths; if {@code false}, skips compilation
     *                (faster — only resolves dependency JARs)
     */
    private Map<Path, List<String>> importBatch(Path gradleRoot, List<Path> subprojects, boolean compile) {
        Map<Path, List<String>> result = new LinkedHashMap<>();

        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(gradleRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            // 1. Compile everything at the root level (one invocation)
            if (compile) {
                logger.info("Compiling all projects from Gradle root: {}", gradleRoot);
                runCompileTasks(connection, gradleRoot);
            } else {
                logger.info("Skipping compilation, resolving classpaths only for Gradle root: {}", gradleRoot);
            }

            // 2. Resolve classpath for ALL subprojects in a single init-script run
            logger.info("Resolving classpaths for {} subproject(s) from root: {}", subprojects.size(), gradleRoot);
            Set<String> subprojectDirStrings = new LinkedHashSet<>();
            for (Path sub : subprojects) {
                subprojectDirStrings.add(normalise(sub));
            }

            Map<String, List<String>> classpathsByProjectDir = resolveAllClasspathsViaInitScript(connection);

            // 3. Match resolved classpaths to our discovered subprojects & add class dirs
            for (Path subproject : subprojects) {
                List<String> cp = new ArrayList<>();
                String normSub = normalise(subproject);

                List<String> resolved = classpathsByProjectDir.get(normSub);
                if (resolved != null) {
                    cp.addAll(resolved);
                } else {
                    logger.warn("No classpath resolved for subproject {} (normalised: {})", subproject, normSub);
                }

                // Add build/classes/** output dirs
                try {
                    cp.addAll(discoverClassDirs(subproject));
                } catch (IOException e) {
                    logger.warn("Could not discover class dirs for {}: {}", subproject, e.getMessage());
                }

                logger.info("Classpath for project {}: {} entries", subproject, cp.size());
                result.put(subproject, cp);
            }
        } catch (Exception e) {
            logger.error("Batch import via Gradle root {} failed: {}", gradleRoot, e.getMessage(), e);
            throw new RuntimeException(e);
        }

        return result;
    }

    @Override
    public void recompile(Path projectRoot) {
        logger.info("Recompiling Gradle project: {}", projectRoot);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            try {
                connection.newBuild()
                        .forTasks("classes", "testClasses")
                        .setStandardOutput(newLogOutputStream())
                        .setStandardError(newLogOutputStream())
                        .run();
                logger.info("Gradle recompile succeeded for {}", projectRoot);
            } catch (Exception buildEx) {
                logger.warn("Gradle recompile failed for {}: {}", projectRoot, buildEx.getMessage());
                try {
                    connection.newBuild()
                            .forTasks("classes")
                            .setStandardOutput(newLogOutputStream())
                            .setStandardError(newLogOutputStream())
                            .run();
                } catch (Exception ex) {
                    logger.warn("Gradle recompile fallback also failed for {}: {}", projectRoot, ex.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Could not connect to Gradle for recompile of {}: {}", projectRoot, e.getMessage());
        }
    }

    @Override
    public boolean isProjectFile(String filePath) {
        return filePath != null
                && (filePath.endsWith("build.gradle") || filePath.endsWith("build.gradle.kts"));
    }

    // ---- private helpers ----

    /**
     * Run compile tasks on the given connection. Tries {@code classes testClasses}
     * first, falling back to just {@code classes}.
     */
    private void runCompileTasks(ProjectConnection connection, Path projectRoot) {
        try {
            connection.newBuild()
                    .forTasks("classes", "testClasses")
                    .setStandardOutput(newLogOutputStream())
                    .setStandardError(newLogOutputStream())
                    .run();
        } catch (Exception buildEx) {
            logger.warn("Could not run compile tasks for project {}: {}", projectRoot, buildEx.getMessage());
            try {
                connection.newBuild()
                        .forTasks("classes")
                        .setStandardOutput(newLogOutputStream())
                        .setStandardError(newLogOutputStream())
                        .run();
            } catch (Exception ex) {
                logger.warn("Could not run 'classes' task for project {}: {}", projectRoot, ex.getMessage());
            }
        }
    }

    /**
     * Group discovered project roots by their Gradle root — the nearest ancestor
     * (or self) that contains {@code settings.gradle} or {@code settings.gradle.kts}.
     */
    private Map<Path, List<Path>> groupByGradleRoot(List<Path> projectRoots) {
        Map<Path, List<Path>> grouped = new LinkedHashMap<>();
        for (Path projectRoot : projectRoots) {
            Path gradleRoot = findGradleRoot(projectRoot);
            grouped.computeIfAbsent(gradleRoot, k -> new ArrayList<>()).add(projectRoot);
        }
        return grouped;
    }

    /**
     * Walk up from the given directory to find the Gradle root (the directory
     * containing {@code settings.gradle} or {@code settings.gradle.kts}).
     * Returns the given directory itself if no parent with settings is found.
     */
    private Path findGradleRoot(Path projectDir) {
        Path dir = projectDir;
        Path lastSettingsDir = null;
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                lastSettingsDir = dir;
            }
            dir = dir.getParent();
        }
        return lastSettingsDir != null ? lastSettingsDir : projectDir;
    }

    /**
     * Resolve classpaths for ALL subprojects in one init-script invocation.
     * The output is tagged with the project directory so we can attribute
     * each classpath entry to the correct subproject.
     *
     * <p>Output format: {@code GROOVYLS_CP:<projectDir>:<classpathEntry>}</p>
     */
    private Map<String, List<String>> resolveAllClasspathsViaInitScript(ProjectConnection connection) {
        Map<String, List<String>> classpathsByProject = new LinkedHashMap<>();
        Path initScript = null;
        try {
            initScript = Files.createTempFile("groovyls-init", ".gradle");
            String initScriptContent =
                "allprojects {\n" +
                "    tasks.register('_groovyLSResolveClasspath') {\n" +
                "        doLast {\n" +
                "            ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'].each { configName ->\n" +
                "                def config = configurations.findByName(configName)\n" +
                "                if (config != null && config.canBeResolved) {\n" +
                "                    try {\n" +
                "                        config.files.each { f ->\n" +
                "                            println \"GROOVYLS_CP:${project.projectDir.absolutePath}:${f.absolutePath}\"\n" +
                "                        }\n" +
                "                    } catch (Exception e) {\n" +
                "                        // skip unresolvable configurations\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            Files.write(initScript, initScriptContent.getBytes("UTF-8"));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            connection.newBuild()
                    .forTasks("_groovyLSResolveClasspath")
                    .withArguments("--init-script", initScript.toString())
                    .setStandardOutput(baos)
                    .setStandardError(newLogOutputStream())
                    .run();

            String output = baos.toString("UTF-8");
            for (String line : output.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("GROOVYLS_CP:")) {
                    String rest = line.substring("GROOVYLS_CP:".length());
                    // Format: <projectDir>:<classpathEntry>
                    // On Windows, projectDir contains ":" (e.g. C:\...), so we
                    // split carefully: find the last path separator pattern
                    int separatorIdx = findProjectDirSeparator(rest);
                    if (separatorIdx < 0) {
                        continue;
                    }
                    String projectDir = normalise(rest.substring(0, separatorIdx));
                    String cpEntry = rest.substring(separatorIdx + 1);
                    if (new File(cpEntry).exists()) {
                        classpathsByProject
                                .computeIfAbsent(projectDir, k -> new ArrayList<>())
                                .add(cpEntry);
                    }
                }
            }

            logger.info("Resolved classpaths for {} project(s) via batch init script",
                    classpathsByProject.size());
        } catch (Exception e) {
            logger.warn("Could not resolve classpaths via batch init script: {}", e.getMessage());
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (IOException ignored) {
                }
            }
        }
        return classpathsByProject;
    }

    /**
     * In the output {@code <projectDir>:<cpEntry>}, find the colon that separates
     * the two paths. On Windows both paths contain {@code :} after the drive letter,
     * so we look for {@code :<drive-letter>:\} or {@code :<drive-letter>:/} as the
     * separator. On Unix the first {@code :} is the separator.
     */
    private int findProjectDirSeparator(String rest) {
        boolean isWindows = File.separatorChar == '\\';
        if (isWindows) {
            // Look for pattern `:X:\` or `:X:/` where X is a drive letter
            // starting from position 2 (skip the first drive letter of projectDir)
            for (int i = 3; i < rest.length() - 2; i++) {
                if (rest.charAt(i) == ':'
                        && Character.isLetter(rest.charAt(i + 1))
                        && (rest.charAt(i + 2) == ':')) {
                    return i;
                }
            }
            return -1;
        } else {
            return rest.indexOf(':');
        }
    }

    /** Normalise a path string for comparison (resolve to absolute, normalise separators). */
    private String normalise(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase();
    }

    /** Normalise a path string for comparison. */
    private String normalise(String pathStr) {
        return Path.of(pathStr).toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase();
    }

    private boolean isJvmProject(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/groovy"))
                || Files.isDirectory(projectDir.resolve("src/test/java"))
                || Files.isDirectory(projectDir.resolve("src/test/groovy"));
    }

    /**
     * Single-project classpath resolution (used by the fallback {@link #importProject(Path)}).
     * Resolves classpath entries for the current connection's project only.
     */
    private List<String> resolveClasspathViaInitScript(ProjectConnection connection) {
        Map<String, List<String>> allClasspaths = resolveAllClasspathsViaInitScript(connection);
        // Flatten all entries since we only have one project in this connection
        List<String> result = new ArrayList<>();
        for (List<String> entries : allClasspaths.values()) {
            result.addAll(entries);
        }
        return result;
    }

    private List<String> discoverClassDirs(Path projectDir) throws IOException {
        Path classesRoot = projectDir.resolve("build/classes");
        List<String> classDirs = new ArrayList<>();

        if (Files.exists(classesRoot)) {
            List<Path> allDirs = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(classesRoot, 2)) {
                stream.filter(Files::isDirectory).forEach(allDirs::add);
            }
            // Only keep leaf directories — skip parents that contain subdirectories
            for (Path dir : allDirs) {
                boolean isLeaf = allDirs.stream()
                        .noneMatch(other -> !other.equals(dir) && other.startsWith(dir));
                if (isLeaf) {
                    classDirs.add(dir.toString());
                }
            }
        }
        return classDirs;
    }

    /**
     * Creates an OutputStream that routes each line of output to the SLF4J
     * logger at DEBUG level, instead of dumping to System.out/System.err.
     */
    private OutputStream newLogOutputStream() {
        return new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    flush();
                } else {
                    buffer.write(b);
                }
            }

            @Override
            public void write(byte[] bytes, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    write(bytes[i]);
                }
            }

            @Override
            public void flush() {
                String line = buffer.toString(StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    logger.debug("[Gradle] {}", line);
                }
                buffer.reset();
            }

            @Override
            public void close() {
                flush();
            }
        };
    }
}
