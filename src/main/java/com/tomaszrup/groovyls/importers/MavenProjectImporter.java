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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Discovers and imports Maven-based JVM projects. Shells out to the {@code mvn}
 * command to compile Java sources and resolve the dependency classpath via
 * {@code dependency:build-classpath}.
 *
 * <p>Maven projects are imported in parallel (up to the number of available
 * CPU cores) since they are typically independent of each other.</p>
 */
public class MavenProjectImporter implements ProjectImporter {

    private static final Logger logger = LoggerFactory.getLogger(MavenProjectImporter.class);

    /** Optional override for the Maven home directory (set from VS Code setting). */
    private String mavenHome;

    public void setMavenHome(String mavenHome) {
        this.mavenHome = mavenHome;
    }

    @Override
    public String getName() {
        return "Maven";
    }

    @Override
    public List<Path> discoverProjects(Path workspaceRoot) throws IOException {
        // Delegate to the unified discovery with directory pruning
        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(
                workspaceRoot, Set.of("Maven"));
        return result.mavenProjects;
    }

    @Override
    public List<String> importProject(Path projectRoot) {
        List<String> classpathList = new ArrayList<>();

        // 1. Compile Java sources
        compileProject(projectRoot);

        // 2. Resolve dependency classpath via mvn dependency:build-classpath
        classpathList.addAll(resolveClasspath(projectRoot));

        // 3. Add compiled class output directories
        classpathList.addAll(discoverClassDirs(projectRoot));

        logger.info("Classpath for Maven project {}: {} entries", projectRoot, classpathList.size());
        return classpathList;
    }

    /**
     * Import all Maven projects in parallel. Each project gets its own
     * {@code mvn} invocation, but they run concurrently since Maven
     * modules are typically independent.
     */
    @Override
    public Map<Path, List<String>> importProjects(List<Path> projectRoots) {
        return doImportProjects(projectRoots, true);
    }

    /**
     * Resolve classpaths for all Maven projects <b>without compiling</b>
     * source code.  Only resolves dependency JARs and discovers existing
     * compiled class directories from prior builds.
     */
    @Override
    public Map<Path, List<String>> resolveClasspaths(List<Path> projectRoots) {
        return doImportProjects(projectRoots, false);
    }

    private Map<Path, List<String>> doImportProjects(List<Path> projectRoots, boolean compile) {
        Map<Path, List<String>> result = new ConcurrentHashMap<>();
        int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "groovyls-maven-import");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path root : projectRoots) {
                futures.add(pool.submit(() -> {
                    try {
                        List<String> cp = new ArrayList<>();
                        if (compile) {
                            compileProject(root);
                        }
                        cp.addAll(resolveClasspath(root));
                        cp.addAll(discoverClassDirs(root));
                        logger.info("Classpath for Maven project {}: {} entries (compile={})",
                                root, cp.size(), compile);
                        result.put(root, cp);
                    } catch (Exception e) {
                        logger.error("Error importing Maven project {}: {}", root, e.getMessage(), e);
                        result.put(root, new ArrayList<>());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    logger.error("Maven import task failed: {}", e.getCause().getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // Preserve insertion order
        Map<Path, List<String>> ordered = new LinkedHashMap<>();
        for (Path root : projectRoots) {
            List<String> cp = result.get(root);
            if (cp != null) {
                ordered.put(root, cp);
            }
        }
        return ordered;
    }

    @Override
    public void recompile(Path projectRoot) {
        logger.info("Recompiling Maven project: {}", projectRoot);
        compileProject(projectRoot);
    }

    @Override
    public boolean isProjectFile(String filePath) {
        return filePath != null && filePath.endsWith("pom.xml");
    }

    // ---- private helpers ----

    private boolean isJvmProject(Path projectDir) {
        return ProjectDiscovery.isJvmProject(projectDir);
    }

    /**
     * Compile both main and test sources. Failures are logged but not fatal —
     * classpath resolution can still succeed even if compilation fails.
     */
    private void compileProject(Path projectRoot) {
        try {
            int exitCode = runMaven(projectRoot, "compile", "test-compile", "-q");
            if (exitCode != 0) {
                logger.warn("Maven compile failed for {} (exit code {}), trying compile only", projectRoot, exitCode);
                int fallbackCode = runMaven(projectRoot, "compile", "-q");
                if (fallbackCode != 0) {
                    logger.warn("Maven compile fallback also failed for {} (exit code {})", projectRoot, fallbackCode);
                }
            } else {
                logger.info("Maven compile succeeded for {}", projectRoot);
            }
        } catch (IOException e) {
            logger.error("Could not run Maven for compile of {}: {}", projectRoot, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Maven compile interrupted for {}", projectRoot);
        }
    }

    /**
     * Resolve the full dependency classpath by running
     * {@code mvn dependency:build-classpath} and writing output to a temp file.
     */
    private List<String> resolveClasspath(Path projectRoot) {
        List<String> classpathEntries = new ArrayList<>();
        Path cpFile = null;
        try {
            cpFile = Files.createTempFile("groovyls-mvn-cp", ".txt");
            int exitCode = runMaven(projectRoot,
                    "dependency:build-classpath",
                    "-DincludeScope=test",
                    "-Dmdep.outputFile=" + cpFile.toString(),
                    "-q");

            if (exitCode == 0) {
                String content = new String(Files.readAllBytes(cpFile), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    String[] entries = content.split(File.pathSeparator.equals(";")
                            ? ";" : File.pathSeparator);
                    for (String entry : entries) {
                        entry = entry.trim();
                        if (!entry.isEmpty() && new File(entry).exists()) {
                            classpathEntries.add(entry);
                        }
                    }
                }
                logger.info("Resolved {} dependency classpath entries for {}", classpathEntries.size(), projectRoot);
            } else {
                logger.warn("mvn dependency:build-classpath failed for {} (exit code {})", projectRoot, exitCode);
            }
        } catch (IOException e) {
            logger.error("Could not resolve Maven classpath for {}: {}", projectRoot, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Maven classpath resolution interrupted for {}", projectRoot);
        } finally {
            if (cpFile != null) {
                try {
                    Files.deleteIfExists(cpFile);
                } catch (IOException ignored) {
                }
            }
        }
        return classpathEntries;
    }

    /**
     * Discover compiled class output directories under {@code target/}.
     */
    private List<String> discoverClassDirs(Path projectDir) {
        List<String> classDirs = new ArrayList<>();
        Path targetClasses = projectDir.resolve("target/classes");
        Path targetTestClasses = projectDir.resolve("target/test-classes");

        if (Files.isDirectory(targetClasses)) {
            classDirs.add(targetClasses.toString());
        }
        if (Files.isDirectory(targetTestClasses)) {
            classDirs.add(targetTestClasses.toString());
        }
        return classDirs;
    }

    /**
     * Run a Maven command with the given goals/arguments in the specified
     * project directory.
     *
     * @return the process exit code
     */
    private int runMaven(Path projectRoot, String... args) throws IOException, InterruptedException {
        String mvnCommand = findMvnCommand(projectRoot);
        List<String> command = new ArrayList<>();
        command.add(mvnCommand);
        command.addAll(Arrays.asList(args));

        logger.info("Running Maven: {} in {}", command, projectRoot);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false);

        // Inherit environment so that JAVA_HOME, M2_HOME etc. are available
        Process process = pb.start();

        // Consume stdout and stderr to prevent blocking
        StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
        StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
        stdoutGobbler.start();
        stderrGobbler.start();

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Maven process timed out after 120 seconds: " + command);
        }
        int exitCode = process.exitValue();
        stdoutGobbler.join(5000);
        stderrGobbler.join(5000);

        if (exitCode != 0) {
            String stderr = stderrGobbler.getOutput();
            if (!stderr.isEmpty()) {
                logger.warn("Maven stderr: {}", stderr);
            }
        }

        return exitCode;
    }

    /**
     * Locate the Maven executable. Checks in order:
     * 1. Maven Wrapper ({@code mvnw.cmd} / {@code mvnw}) in the project directory or a parent
     * 2. {@code mavenHome} setting (if configured)
     * 3. {@code mvn} / {@code mvn.cmd} on PATH
     */
    private String findMvnCommand(Path projectRoot) {
        // 1. Check for Maven Wrapper in project dir and parent directories
        String wrapper = findMavenWrapper(projectRoot);
        if (wrapper != null) {
            logger.info("Using Maven Wrapper: {}", wrapper);
            return wrapper;
        }

        // 2. Check configured mavenHome
        if (mavenHome != null && !mavenHome.isEmpty()) {
            Path mvnBin = Path.of(mavenHome, "bin",
                    isWindows() ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(mvnBin)) {
                return mvnBin.toString();
            }
            // Also try without .cmd extension on Windows (wrapper scripts)
            if (isWindows()) {
                Path mvnBinAlt = Path.of(mavenHome, "bin", "mvn");
                if (Files.isRegularFile(mvnBinAlt)) {
                    return mvnBinAlt.toString();
                }
            }
            logger.warn("Maven home '{}' does not contain a valid mvn binary, falling back to PATH", mavenHome);
        }

        // 3. Fall back to mvn on PATH
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    /**
     * Search for a Maven Wrapper script ({@code mvnw.cmd} / {@code mvnw}) starting
     * from the given directory and walking up to parent directories. This supports
     * multi-module projects where the wrapper lives in the root but submodules are
     * discovered individually.
     *
     * @return absolute path to the wrapper script, or {@code null} if not found
     */
    private String findMavenWrapper(Path startDir) {
        Path dir = startDir;
        // Limit traversal to prevent walking past the workspace root into system dirs
        int maxDepth = 10;
        int depth = 0;
        while (dir != null && depth < maxDepth) {
            Path wrapper = dir.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(wrapper)) {
                return wrapper.toAbsolutePath().toString();
            }
            // On Windows also check for mvnw (batch-less wrapper)
            if (isWindows()) {
                Path wrapperAlt = dir.resolve("mvnw");
                if (Files.isRegularFile(wrapperAlt)) {
                    return wrapperAlt.toAbsolutePath().toString();
                }
            }
            dir = dir.getParent();
            depth++;
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Simple thread to consume a process stream and capture its output.
     */
    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final StringBuilder output = new StringBuilder();

        StreamGobbler(InputStream inputStream) {
            this.inputStream = inputStream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                // stream closed, ignore
            }
        }

        String getOutput() {
            return output.toString();
        }
    }
}
