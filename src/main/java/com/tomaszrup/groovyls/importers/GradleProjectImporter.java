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

import com.tomaszrup.groovyls.util.TempFileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;

import com.tomaszrup.groovyls.util.GroovyVersionDetector;

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
    private static final String TASK_CLASSES = "classes";
    private static final String TASK_TEST_CLASSES = "testClasses";
    private static final String INIT_SCRIPT_EXTENSION = ".gradle";
    private static final String INIT_SCRIPT_ARGUMENT = "--init-script";
    private static final String BUILD_GRADLE = "build.gradle";
    private static final String BUILD_GRADLE_KTS = "build.gradle.kts";

    private static final class GradleImportException extends RuntimeException {
        GradleImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Optional upper bounds for {@link #findGradleRoot(Path)} so that the
     * search stops at a workspace root instead of walking all the way to
     * the filesystem root.  Set via {@link #setWorkspaceBounds(List)}.
     * In a multi-root workspace each folder becomes a separate bound so
     * that each project resolves its Gradle root within its own workspace folder.
     */
    private final AtomicReference<List<Path>> workspaceBounds = new AtomicReference<>(Collections.emptyList());

    /**
     * Tracks Gradle roots that have already been validated for wrapper
     * integrity, to avoid re-reading properties on every connector call.
     */
    private final Set<Path> validatedRoots = ConcurrentHashMap.newKeySet();

    /**
     * Best-effort cache of detected Groovy versions keyed by normalized project
     * root path. Populated from init-script output during classpath resolution.
     */
    private final Map<String, String> detectedGroovyVersionByProject = new ConcurrentHashMap<>();

    private final GradleClasspathOutputParser parser = new GradleClasspathOutputParser(detectedGroovyVersionByProject);

    /**
     * Sets upper bounds for the Gradle-root search from multiple workspace
     * folders.  {@link #findGradleRoot(Path)} will not walk above the
     * nearest ancestor bound for a given project directory.
     */
    @Override
    public void setWorkspaceBounds(List<Path> bounds) {
        this.workspaceBounds.set(bounds != null ? new ArrayList<>(bounds) : Collections.emptyList());
    }

    @Override
    public boolean claimsProject(Path projectRoot) {
        return projectRoot != null
                && (projectRoot.resolve(BUILD_GRADLE).toFile().exists()
                    || projectRoot.resolve(BUILD_GRADLE_KTS).toFile().exists());
    }

    @Override
    public boolean supportsSiblingBatching() {
        return true;
    }

    @Override
    public String getName() {
        return "Gradle";
    }

    @Override
    public Optional<String> detectProjectGroovyVersion(Path projectRoot, List<String> classpathEntries) {
        if (projectRoot != null) {
            String cached = detectedGroovyVersionByProject.get(parser.normalise(projectRoot));
            if (cached != null && !cached.isEmpty()) {
                return Optional.of(cached);
            }
        }
        return GroovyVersionDetector.detect(classpathEntries);
    }

    @Override
    public List<Path> discoverProjects(Path workspaceRoot) throws IOException {
        // Delegate to the unified discovery with directory pruning
        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(
                workspaceRoot, Set.of("Gradle"));
        return result.gradleProjects;
    }

    @Override
    public List<String> importProject(Path projectRoot) {
        List<String> classpathList = new ArrayList<>();
        validateGradleWrapper(projectRoot);
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
            logger.error("Failed to import Gradle project {}: {}", projectRoot, e.getMessage(), e);
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

    /**
     * Resolve classpath for a <b>single</b> Gradle subproject without
     * compiling.  Opens a Tooling API connection to the Gradle root
     * (found via {@link #findGradleRoot(Path)}) and runs the init-script
     * classpath task, but only parses entries matching this subproject's
     * directory.
     *
     * <p>This is the lazy on-demand variant called when the user opens a
     * file in a project whose classpath hasn't been resolved yet.</p>
     */
    @Override
    public List<String> resolveClasspath(Path projectRoot) {
        Path gradleRoot = findGradleRoot(projectRoot);
        logger.info("Lazy-resolving classpath for single project {} via Gradle root {}", projectRoot, gradleRoot);

        validateGradleWrapper(gradleRoot);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(gradleRoot.toFile());
        try (ProjectConnection connection = connector.connect()) {
            // Use the filtered init script that only resolves the requested
            // project, avoiding the cost of resolving all ~N sibling projects.
            Map<String, List<String>> classpathsByProjectDir =
                    resolveSingleProjectClasspathViaInitScript(connection, projectRoot);

            List<String> cp = new ArrayList<>();
            String normSub = parser.normalise(projectRoot);

            List<String> resolved = classpathsByProjectDir.get(normSub);
            if (resolved != null) {
                cp.addAll(resolved);
            } else {
                // Fallback: try the unfiltered variant in case the filter
                // didn't match (e.g. projectDir mismatch on Windows)
                logger.warn("Filtered init script returned no classpath for {} — "
                        + "falling back to allprojects resolution", projectRoot);
                classpathsByProjectDir = resolveAllClasspathsViaInitScript(connection);
                resolved = classpathsByProjectDir.get(normSub);
                if (resolved != null) {
                    cp.addAll(resolved);
                } else {
                    logger.warn("No classpath resolved for project {} (normalised: {})", projectRoot, normSub);
                }
            }

            addDiscoveredClassDirs(cp, projectRoot);

            // Deduplicate
            cp = new ArrayList<>(new LinkedHashSet<>(cp));
            logger.info("Lazy-resolved classpath for {}: {} entries", projectRoot, cp.size());
            return cp;
        } catch (Exception e) {
            logger.error("Failed to resolve classpath for {}: {}", projectRoot, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Resolve classpaths for multiple subprojects sharing the same Gradle root
     * in a single Tooling API connection. Used by the backfill mechanism after
     * a lazy single-project resolve completes.
     *
     * @param gradleRoot   the Gradle root directory (with settings.gradle)
     * @param subprojects  the subprojects to resolve
     * @return map from subproject root to its classpath entries
     */
    @Override
    public Map<Path, List<String>> resolveClasspathsForRoot(Path gradleRoot, List<Path> subprojects) {
        logger.info("Backfill: resolving classpaths for {} subproject(s) under Gradle root {}",
                subprojects.size(), gradleRoot);
        try {
            return importBatch(gradleRoot, subprojects, false);
        } catch (Exception e) {
            logger.error("Backfill batch failed for Gradle root {}: {}", gradleRoot, e.getMessage(), e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Expose Gradle root lookup for use by the resolution coordinator.
     */
    public Path getGradleRoot(Path projectRoot) {
        return findGradleRoot(projectRoot);
    }

    @Override
    public Path getBuildToolRoot(Path projectRoot) {
        return findGradleRoot(projectRoot);
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

        validateGradleWrapper(gradleRoot);
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
            Map<String, List<String>> classpathsByProjectDir = resolveAllClasspathsViaInitScript(connection);

            // 3. Match resolved classpaths to our discovered subprojects & add class dirs
            for (Path subproject : subprojects) {
                List<String> cp = new ArrayList<>();
                String normSub = parser.normalise(subproject);

                List<String> resolved = classpathsByProjectDir.get(normSub);
                if (resolved != null) {
                    cp.addAll(resolved);
                } else {
                    logger.warn("No classpath resolved for subproject {} (normalised: {})", subproject, normSub);
                }

                // Add build/classes/** output dirs
                addDiscoveredClassDirs(cp, subproject);

                // Deduplicate (preserving order) — init-script results and
                // discoverClassDirs may overlap, and Windows path casing can
                // cause string-equal misses.
                cp = new ArrayList<>(new LinkedHashSet<>(cp));

                logger.info("Classpath for project {}: {} entries", subproject, cp.size());
                result.put(subproject, cp);
            }
        } catch (Exception e) {
            throw new GradleImportException("Batch import failed for Gradle root " + gradleRoot, e);
        }

        return result;
    }

    @Override
    public void recompile(Path projectRoot) {
        logger.info("Recompiling Gradle project: {}", projectRoot);
        validateGradleWrapper(projectRoot);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            if (runRecompileBuild(connection, projectRoot)) {
                logger.info("Gradle recompile succeeded for {}", projectRoot);
            }
        } catch (Exception e) {
			throw new GradleImportException("Could not recompile Gradle project " + projectRoot, e);
        }
    }

    private boolean runRecompileBuild(ProjectConnection connection, Path projectRoot) {
        if (runBuildTasks(connection, projectRoot, TASK_CLASSES, TASK_TEST_CLASSES)) {
            return true;
        }
        logger.warn("Gradle recompile failed for {}. Retrying with main classes only.", projectRoot);
        return runBuildTasks(connection, projectRoot, TASK_CLASSES);
    }

    private boolean runBuildTasks(ProjectConnection connection, Path projectRoot, String... tasks) {
        try {
            connection.newBuild()
                    .forTasks(tasks)
                    .setStandardOutput(parser.newLogOutputStream())
                    .setStandardError(parser.newLogOutputStream())
                    .run();
            return true;
        } catch (Exception ex) {
            logger.warn("Gradle build task(s) {} failed for {}: {}", Arrays.toString(tasks), projectRoot,
                    ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public boolean isProjectFile(String filePath) {
        return filePath != null
                && (filePath.endsWith(BUILD_GRADLE) || filePath.endsWith(BUILD_GRADLE_KTS));
    }

    /**
     * Compile Java/Kotlin sources for all given projects, grouped by Gradle
     * root so that a single {@code classes testClasses} invocation covers all
     * subprojects under the same root build.
     *
     * <p>If a Gradle root already has compiled output directories that are
     * newer than the corresponding build files, the expensive Gradle daemon
     * invocation is skipped for that root.</p>
     */
    @Override
    public void compileSources(List<Path> projectRoots) {
        Map<Path, List<Path>> grouped = groupByGradleRoot(projectRoots);
        for (Map.Entry<Path, List<Path>> entry : grouped.entrySet()) {
            Path gradleRoot = entry.getKey();
            List<Path> subprojects = entry.getValue();
            if (allSubprojectsHaveFreshOutput(subprojects)) {
                logger.info("Skipping source compilation for Gradle root {} " +
                        "— all {} subproject(s) already have up-to-date output",
                        gradleRoot, subprojects.size());
                continue;
            }
            logger.info("Compiling sources for Gradle root {} ({} subproject(s))",
                    gradleRoot, subprojects.size());
            validateGradleWrapper(gradleRoot);
            GradleConnector connector = GradleConnector.newConnector()
                    .forProjectDirectory(gradleRoot.toFile());
            try (ProjectConnection connection = connector.connect()) {
                runCompileTasks(connection, gradleRoot);
            } catch (Exception e) {
                logger.warn("Failed to compile sources for Gradle root {}: {}",
                        gradleRoot, e.getMessage(), e);
            }
        }
    }

    /**
     * Returns {@code true} if every subproject under the given Gradle root
     * already has at least one compiled-output directory (e.g.
     * {@code build/classes/java/main}) whose last-modified time is newer than
     * the project's build file.  This is a cheap heuristic to avoid launching
     * a Gradle daemon when everything is already compiled.
     */
    private boolean allSubprojectsHaveFreshOutput(List<Path> subprojects) {
        for (Path subproject : subprojects) {
            if (!hasCompiledOutputNewerThanBuildFile(subproject)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasCompiledOutputNewerThanBuildFile(Path projectRoot) {
        try {
            long buildFileTime = getBuildFileLastModified(projectRoot);
            if (buildFileTime <= 0) {
                return false; // no build file found — cannot determine freshness
            }
            // Check common Gradle output directories
            String[] outputDirs = {
                "build/classes/java/main",
                "build/classes/groovy/main",
                "build/classes/kotlin/main"
            };
            for (String dir : outputDirs) {
                Path outputPath = projectRoot.resolve(dir);
                if (Files.isDirectory(outputPath)) {
                    long outputTime = Files.getLastModifiedTime(outputPath).toMillis();
                    if (outputTime >= buildFileTime) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not check compiled output freshness for {}: {}",
                    projectRoot, e.getMessage());
        }
        return false;
    }

    private long getBuildFileLastModified(Path projectRoot) {
        try {
            Path buildGradle = projectRoot.resolve(BUILD_GRADLE);
            if (Files.isRegularFile(buildGradle)) {
                return Files.getLastModifiedTime(buildGradle).toMillis();
            }
            Path buildGradleKts = projectRoot.resolve(BUILD_GRADLE_KTS);
            if (Files.isRegularFile(buildGradleKts)) {
                return Files.getLastModifiedTime(buildGradleKts).toMillis();
            }
        } catch (Exception e) {
            logger.debug("Could not read build file for {}: {}", projectRoot, e.getMessage());
        }
        return 0;
    }

    // ---- private helpers ----

    /**
     * Run compile tasks on the given connection. Tries {@code classes testClasses}
     * first, falling back to just {@code classes}.
     */
    private void runCompileTasks(ProjectConnection connection, Path projectRoot) {
        try {
            connection.newBuild()
                    .forTasks(TASK_CLASSES, TASK_TEST_CLASSES)
                    .setStandardOutput(parser.newLogOutputStream())
                    .setStandardError(parser.newLogOutputStream())
                    .run();
        } catch (Exception buildEx) {
            logger.warn("Could not run compile tasks for project {}: {}", projectRoot, buildEx.getMessage());
            try {
                connection.newBuild()
                        .forTasks(TASK_CLASSES)
                        .setStandardOutput(parser.newLogOutputStream())
                        .setStandardError(parser.newLogOutputStream())
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
     *
     * <p>The search is bounded by {@link #workspaceBound} (if set) so that we
     * never walk above the workspace root into unrelated parent directories.
     * Without a bound, the old behaviour of walking to the filesystem root
     * is preserved for backward compatibility.</p>
     */
    private Path findGradleRoot(Path projectDir) {
        Path dir = projectDir;
        Path lastSettingsDir = null;
        Path bound = findNearestBound(projectDir);
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                lastSettingsDir = dir;
            }
            // Stop if we've reached the workspace root
            if (bound != null && dir.equals(bound)) {
                break;
            }
            dir = dir.getParent();
        }
        return lastSettingsDir != null ? lastSettingsDir : projectDir;
    }

    /**
     * Find the workspace bound that is the nearest ancestor of (or equal to)
     * the given path.  In a multi-root workspace different projects may fall
     * under different workspace folders; this ensures each project's Gradle
     * root search is bounded by its own workspace folder.
     *
     * @return the nearest ancestor bound, or {@code null} if none applies
     */
    private Path findNearestBound(Path projectDir) {
        List<Path> bounds = workspaceBounds.get();
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path nearest = null;
        int nearestDepth = -1;
        for (Path bound : bounds) {
            Path normalizedBound = bound.toAbsolutePath().normalize();
            if (normalizedProject.startsWith(normalizedBound)) {
                // This bound is an ancestor — pick the deepest (most specific) one
                int depth = normalizedBound.getNameCount();
                if (depth > nearestDepth) {
                    nearestDepth = depth;
                    nearest = normalizedBound;
                }
            }
        }
        return nearest;
    }

    private void addDiscoveredClassDirs(List<String> classpath, Path projectRoot) {
        try {
            classpath.addAll(discoverClassDirs(projectRoot));
        } catch (IOException e) {
            logger.warn("Could not discover class dirs for {}: {}", projectRoot, e.getMessage());
        }
    }

    /**
     * Download source JARs for all dependencies of the given project in the
     * background. This is a best-effort operation — failures are logged but
     * do not affect classpath resolution or diagnostics. Separated from
     * {@link #resolveAllClasspathsViaInitScript} to avoid blocking the
     * critical path to first diagnostic.
     *
     * @param projectRoot the project root (its Gradle root will be resolved
     *                    automatically)
     */
    @Override
    public void downloadSourceJarsAsync(Path projectRoot) {
        Path gradleRoot = findGradleRoot(projectRoot);
        logger.info("Background source JAR download for Gradle root {}", gradleRoot);

        validateGradleWrapper(gradleRoot);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(gradleRoot.toFile());
        Path initScript = null;
        try (ProjectConnection connection = connector.connect()) {
            initScript = TempFileUtils.createSecureTempFile("groovyls-sources-init", INIT_SCRIPT_EXTENSION);
            String initScriptContent =
                "allprojects {\n" +
                "    tasks.register('_groovyLSDownloadSources') {\n" +
                "        doLast {\n" +
                "            def allCoords = new LinkedHashSet()\n" +
                "            ['testCompileClasspath', 'runtimeClasspath'].each { configName ->\n" +
                "                def config = configurations.findByName(configName)\n" +
                "                if (config != null && config.canBeResolved) {\n" +
                "                    try {\n" +
                "                        config.resolvedConfiguration.resolvedArtifacts.each { artifact ->\n" +
                "                            def id = artifact.moduleVersion.id\n" +
                "                            allCoords.add(\"${id.group}:${id.name}:${id.version}\")\n" +
                "                        }\n" +
                "                    } catch (Exception e) { /* skip */ }\n" +
                "                }\n" +
                "            }\n" +
                "            if (!allCoords.isEmpty()) {\n" +
                "                try {\n" +
                "                    def sourceDeps = []\n" +
                "                    allCoords.each { coord ->\n" +
                "                        def parts = coord.split(':')\n" +
                "                        sourceDeps.add(project.dependencies.create(\n" +
                "                            group: parts[0], name: parts[1], version: parts[2],\n" +
                "                            classifier: 'sources', ext: 'jar'))\n" +
                "                    }\n" +
                "                    def sourcesConfig = project.configurations.detachedConfiguration(\n" +
                "                        *sourceDeps)\n" +
                "                    sourcesConfig.transitive = false\n" +
                "                    sourcesConfig.resolvedConfiguration.lenientConfiguration.files\n" +
                "                } catch (Exception ignored) { /* best-effort */ }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            Files.write(initScript, initScriptContent.getBytes(StandardCharsets.UTF_8));

            connection.newBuild()
                    .forTasks("_groovyLSDownloadSources")
                    .withArguments(INIT_SCRIPT_ARGUMENT, initScript.toString())
                    .setStandardOutput(parser.newLogOutputStream())
                    .setStandardError(parser.newLogOutputStream())
                    .run();

            logger.info("Background source JAR download completed for {}", gradleRoot);
        } catch (Exception e) {
            logger.warn("Background source JAR download failed for {}: {}", gradleRoot, e.getMessage(), e);
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (IOException ignored) {
                    // Temporary script cleanup is best-effort.
                }
            }
        }
    }

    /**
     * Resolve classpaths for ALL subprojects in one init-script invocation.
     * The output is tagged with the project directory so we can attribute
     * each classpath entry to the correct subproject.
     *
     * <p>Output format: {@code GROOVYLS_CP_MAIN:<projectDir>:<classpathEntry>}
     * for production dependencies and {@code GROOVYLS_CP_TEST:<projectDir>:<classpathEntry>}
     * for test-only dependencies. The parser collects both; entries from
     * {@code testCompileClasspath} that are not already in
     * {@code compileClasspath}/{@code runtimeClasspath} are tagged as TEST.</p>
     */
    private Map<String, List<String>> resolveAllClasspathsViaInitScript(ProjectConnection connection) {
        return resolveAllClasspathsViaInitScript(connection, null);
    }

    /**
     * Overload that also populates a separate map of test-only classpath
     * entries per project.
     *
     * @param connection        Tooling API connection
     * @param testOnlyByProject if non-null, populated with test-only entries
     * @return map of projectDir → full classpath (main + test)
     */
    private Map<String, List<String>> resolveAllClasspathsViaInitScript(
            ProjectConnection connection,
            Map<String, List<String>> testOnlyByProject) {
        // Use LinkedHashSet per project to deduplicate entries from overlapping
        // Gradle configurations (compileClasspath, runtimeClasspath, etc.)
        Map<String, Set<String>> mainByProject = new LinkedHashMap<>();
        Map<String, Set<String>> testByProject = new LinkedHashMap<>();
        Path initScript = null;
        try {
            initScript = TempFileUtils.createSecureTempFile("groovyls-init", INIT_SCRIPT_EXTENSION);
            // Resolve main configs first, then test configs, each tagged separately.
            // This lets the Java parser differentiate main vs test-only entries.
            String initScriptContent =
                "allprojects {\n" +
                "    tasks.register('_groovyLSResolveClasspath') {\n" +
                "        doLast {\n" +
                "            def mainFiles = new LinkedHashSet()\n" +
                "            ['compileClasspath', 'runtimeClasspath'].each { configName ->\n" +
                "                def config = configurations.findByName(configName)\n" +
                "                if (config != null && config.canBeResolved) {\n" +
                "                    try {\n" +
                "                        config.files.each { f ->\n" +
                "                            mainFiles.add(f.absolutePath)\n" +
                "                            println \"GROOVYLS_CP_MAIN:${project.projectDir.absolutePath}:${f.absolutePath}\"\n" +
                "                        }\n" +
                "                    } catch (Exception e) { /* skip */ }\n" +
                "                }\n" +
                "            }\n" +
                "            ['testCompileClasspath', 'testRuntimeClasspath'].each { configName ->\n" +
                "                def config = configurations.findByName(configName)\n" +
                "                if (config != null && config.canBeResolved) {\n" +
                "                    try {\n" +
                "                        config.files.each { f ->\n" +
                "                            if (!mainFiles.contains(f.absolutePath)) {\n" +
                "                                println \"GROOVYLS_CP_TEST:${project.projectDir.absolutePath}:${f.absolutePath}\"\n" +
                "                            }\n" +
                "                        }\n" +
                "                    } catch (Exception e) { /* skip */ }\n" +
                "                }\n" +
                "            }\n" +
                "            def groovyVersions = new LinkedHashSet()\n" +
                "            ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'].each { configName ->\n" +
                "                def config = configurations.findByName(configName)\n" +
                "                if (config != null && config.canBeResolved) {\n" +
                "                    try {\n" +
                "                        config.resolvedConfiguration.resolvedArtifacts.each { artifact ->\n" +
                "                            def id = artifact.moduleVersion.id\n" +
                "                            if ((id.group == 'org.apache.groovy' || id.group == 'org.codehaus.groovy')\n" +
                "                                    && id.name.startsWith('groovy')) {\n" +
                "                                groovyVersions.add(id.version)\n" +
                "                            }\n" +
                "                        }\n" +
                "                    } catch (Exception e) { /* skip */ }\n" +
                "                }\n" +
                "            }\n" +
                "            groovyVersions.each { version ->\n" +
                "                println \"GROOVYLS_GROOVY_VERSION:${project.projectDir.absolutePath}:${version}\"\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            Files.write(initScript, initScriptContent.getBytes(StandardCharsets.UTF_8));

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                connection.newBuild()
                        .forTasks("_groovyLSResolveClasspath")
                        .withArguments(INIT_SCRIPT_ARGUMENT, initScript.toString())
                        .setStandardOutput(baos)
                        .setStandardError(parser.newLogOutputStream())
                        .run();

                parser.processBatchClasspathOutput(baos.toString(StandardCharsets.UTF_8), mainByProject, testByProject);
            }

            logger.info("Resolved classpaths for {} project(s) via batch init script",
                    mainByProject.size());
        } catch (Exception e) {
            logger.warn("Could not resolve classpaths via batch init script: {}", e.getMessage(), e);
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (IOException ignored) {
                    // Temporary script cleanup is best-effort.
                }
            }
        }
        // Convert sets to lists for the public API (combined main + test)
        Map<String, List<String>> classpathsByProject = new LinkedHashMap<>();
        Set<String> allProjects = new LinkedHashSet<>(mainByProject.keySet());
        allProjects.addAll(testByProject.keySet());
        for (String projectDir : allProjects) {
            List<String> combined = new ArrayList<>();
            Set<String> main = mainByProject.get(projectDir);
            if (main != null) {
                combined.addAll(main);
            }
            Set<String> test = testByProject.get(projectDir);
            if (test != null) {
                combined.addAll(test);
            }
            classpathsByProject.put(projectDir, combined);

            // Populate test-only output map if requested
            if (testOnlyByProject != null && test != null && !test.isEmpty()) {
                testOnlyByProject.put(projectDir, new ArrayList<>(test));
            }
        }

        int mainTotal = mainByProject.values().stream().mapToInt(Set::size).sum();
        int testTotal = testByProject.values().stream().mapToInt(Set::size).sum();
        logger.info("Classpath breakdown: {} main entries, {} test-only entries across {} project(s)",
                mainTotal, testTotal, allProjects.size());

        return classpathsByProject;
    }

    /**
     * Resolve classpath for a <b>single</b> subproject using a filtered init
     * script that only processes the target project directory. This avoids the
     * cost of Gradle configuring and resolving all ~N sibling subprojects when
     * only one is needed (lazy on-demand resolution).
     *
     * <p>The init script uses {@code allprojects} but wraps the resolution
     * logic in a {@code projectDir} check, so Gradle still configures all
     * projects (required by the Tooling API) but only resolves dependency
     * configurations for the matching project.</p>
     *
     * @param connection  an open Tooling API connection to the Gradle root
     * @param targetProject the specific subproject to resolve
     * @return map with a single entry for the target project
     */
    private Map<String, List<String>> resolveSingleProjectClasspathViaInitScript(
            ProjectConnection connection, Path targetProject) {
        Map<String, Set<String>> dedupByProject = new LinkedHashMap<>();
        Path initScript = null;
        try {
            initScript = TempFileUtils.createSecureTempFile("groovyls-single-init", INIT_SCRIPT_EXTENSION);
            // Escape backslashes, single quotes, and strip newlines for the
            // Groovy single-quoted string in the init script to prevent injection.
            String targetDir = targetProject.toAbsolutePath().normalize().toString()
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "")
                    .replace("\r", "");
            String initScriptContent =
                "allprojects {\n" +
                "    tasks.register('_groovyLSResolveSingleClasspath') {\n" +
                "        doLast {\n" +
                "            def targetDir = '" + targetDir + "'\n" +
                "            def normalizedProjectDir = project.projectDir.absolutePath.replace('\\\\', '/')\n" +
                "            def normalizedTargetDir = targetDir.replace('\\\\', '/')\n" +
                "            if (normalizedProjectDir.equalsIgnoreCase(normalizedTargetDir)) {\n" +
                "                def mainFiles = new LinkedHashSet()\n" +
                "                ['compileClasspath', 'runtimeClasspath'].each { configName ->\n" +
                "                    def config = configurations.findByName(configName)\n" +
                "                    if (config != null && config.canBeResolved) {\n" +
                "                        try {\n" +
                "                            config.files.each { f ->\n" +
                "                                mainFiles.add(f.absolutePath)\n" +
                "                                println \"GROOVYLS_CP_MAIN:${project.projectDir.absolutePath}:${f.absolutePath}\"\n" +
                "                            }\n" +
                "                        } catch (Exception e) { /* skip */ }\n" +
                "                    }\n" +
                "                }\n" +
                "                ['testCompileClasspath', 'testRuntimeClasspath'].each { configName ->\n" +
                "                    def config = configurations.findByName(configName)\n" +
                "                    if (config != null && config.canBeResolved) {\n" +
                "                        try {\n" +
                "                            config.files.each { f ->\n" +
                "                                if (!mainFiles.contains(f.absolutePath)) {\n" +
                "                                    println \"GROOVYLS_CP_TEST:${project.projectDir.absolutePath}:${f.absolutePath}\"\n" +
                "                                }\n" +
                "                            }\n" +
                "                        } catch (Exception e) { /* skip */ }\n" +
                "                    }\n" +
                "                }\n" +
                "                def groovyVersions = new LinkedHashSet()\n" +
                "                ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'].each { configName ->\n" +
                "                    def config = configurations.findByName(configName)\n" +
                "                    if (config != null && config.canBeResolved) {\n" +
                "                        try {\n" +
                "                            config.resolvedConfiguration.resolvedArtifacts.each { artifact ->\n" +
                "                                def id = artifact.moduleVersion.id\n" +
                "                                if ((id.group == 'org.apache.groovy' || id.group == 'org.codehaus.groovy')\n" +
                "                                        && id.name.startsWith('groovy')) {\n" +
                "                                    groovyVersions.add(id.version)\n" +
                "                                }\n" +
                "                            }\n" +
                "                        } catch (Exception e) { /* skip */ }\n" +
                "                    }\n" +
                "                }\n" +
                "                groovyVersions.each { version ->\n" +
                "                    println \"GROOVYLS_GROOVY_VERSION:${project.projectDir.absolutePath}:${version}\"\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            Files.write(initScript, initScriptContent.getBytes(StandardCharsets.UTF_8));

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                connection.newBuild()
                        .forTasks("_groovyLSResolveSingleClasspath")
                        .withArguments(INIT_SCRIPT_ARGUMENT, initScript.toString())
                        .setStandardOutput(baos)
                        .setStandardError(parser.newLogOutputStream())
                        .run();

                parser.processSingleClasspathOutput(baos.toString(StandardCharsets.UTF_8), dedupByProject);
            }

            logger.info("Single-project init script resolved classpath for {} project(s)",
                    dedupByProject.size());
        } catch (Exception e) {
            logger.warn("Could not resolve single-project classpath via init script: {}", e.getMessage(), e);
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (IOException ignored) {
                    // Temporary script cleanup is best-effort.
                }
            }
        }
        Map<String, List<String>> classpathsByProject = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : dedupByProject.entrySet()) {
            classpathsByProject.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return classpathsByProject;
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

            // Remove stale .class files whose source files no longer exist.
            // This prevents the Groovy compiler from resolving deleted Java/Groovy
            // classes via leftover build output, which would hide missing-class
            // diagnostics.
            com.tomaszrup.groovyls.util.StaleClassFileCleaner.cleanProject(projectDir);
        }
        return classDirs;
    }

    // ----------------------------------------------------------------
    // Gradle wrapper integrity validation
    // ----------------------------------------------------------------

    /**
     * Validate the target project's Gradle wrapper configuration before
     * connecting via the Tooling API.
     *
     * <p>Checks that {@code distributionSha256Sum} is present in the wrapper
     * properties so that the downloaded distribution can be verified. If the
     * checksum is missing, a warning is logged but execution continues —
     * many legitimate projects omit it.</p>
     *
     * <p>Results are cached per {@code gradleRoot} so the check runs at most
     * once per directory.</p>
     *
     * @param gradleRoot the Gradle root directory (containing
     *                    {@code gradle/wrapper/gradle-wrapper.properties})
     */
    private void validateGradleWrapper(Path gradleRoot) {
        if (!validatedRoots.add(gradleRoot)) {
            return; // already validated
        }

        Path propsFile = gradleRoot.resolve("gradle")
                .resolve("wrapper")
                .resolve("gradle-wrapper.properties");

        if (!Files.isRegularFile(propsFile)) {
            // No wrapper properties — the Tooling API will use its own default
            // distribution, which is safe.
            logger.debug("No gradle-wrapper.properties found in {} — using Tooling API default", gradleRoot);
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("Could not read {}: {}", propsFile, e.getMessage(), e);
            return;
        }

        String sha256 = props.getProperty("distributionSha256Sum");
        boolean hasSha = sha256 != null && !sha256.isBlank();

        if (!hasSha) {
            logger.warn("Gradle wrapper in {} does not specify distributionSha256Sum — " +
                    "distribution integrity cannot be verified. Consider adding a " +
                    "SHA-256 checksum to gradle-wrapper.properties.", gradleRoot);
        }
    }
}
