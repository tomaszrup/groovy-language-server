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

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Performs a <b>single</b> filesystem walk to discover all build-tool project
 * roots (Gradle and Maven) in one pass.  This replaces the previous approach
 * where each {@link ProjectImporter} performed its own {@code Files.walk()},
 * doubling the I/O on every cold start.
 *
 * <h3>Directory pruning</h3>
 * The walk skips directories that are known to never contain build files:
 * {@code .git}, {@code .gradle}, {@code .idea}, {@code node_modules},
 * {@code build}, {@code target}, {@code bin}, {@code out}, {@code .svn},
 * {@code .hg}, and any hidden directory (name starting with {@code .}).
 * This dramatically reduces traversal time in large monorepos that contain
 * {@code node_modules} trees or deep build output directories.
 *
 * <h3>JVM project filtering</h3>
 * A directory containing a build file ({@code build.gradle},
 * {@code build.gradle.kts}, or {@code pom.xml}) is only considered a valid
 * project if it also contains at least one of the standard JVM source
 * directories:
 * <ul>
 *   <li>{@code src/main/java}</li>
 *   <li>{@code src/main/groovy}</li>
 *   <li>{@code src/test/java}</li>
 *   <li>{@code src/test/groovy}</li>
 * </ul>
 */
public final class ProjectDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(ProjectDiscovery.class);

    /**
     * Directory names that are pruned during the walk.  These are matched
     * case-insensitively against the <em>simple</em> directory name (not
     * the full path).
     */
    private static final Set<String> PRUNED_DIRS = Set.of(
            ".git", ".gradle", ".idea", ".svn", ".hg", ".settings",
            "node_modules", "build", "target", "bin", "out", "__pycache__"
    );

    /** Build-file names we're looking for. */
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
            "build.gradle", "build.gradle.kts", "pom.xml"
    );

    private ProjectDiscovery() {
        // utility class
    }

    /**
     * Result of a unified project discovery walk.
     */
    public static class DiscoveryResult {
        /** Project roots that have a Gradle build file. */
        public final List<Path> gradleProjects;
        /** Project roots that have a Maven build file. */
        public final List<Path> mavenProjects;

        DiscoveryResult(List<Path> gradleProjects, List<Path> mavenProjects) {
            this.gradleProjects = Collections.unmodifiableList(gradleProjects);
            this.mavenProjects = Collections.unmodifiableList(mavenProjects);
        }
    }

    /**
     * Walk the workspace root once and discover all Gradle and Maven project
     * roots, pruning irrelevant directories for speed.
     *
     * @param workspaceRoot the root directory to scan
     * @param enabledImporters set of enabled importer names (e.g. "Gradle", "Maven").
     *                         If null or empty, all importers are enabled.
     * @return discovery result containing categorised project roots
     * @throws IOException if the walk fails
     */
    public static DiscoveryResult discoverAll(Path workspaceRoot, Set<String> enabledImporters) throws IOException {
        boolean gradleEnabled = enabledImporters == null || enabledImporters.isEmpty()
                || enabledImporters.contains("Gradle");
        boolean mavenEnabled = enabledImporters == null || enabledImporters.isEmpty()
                || enabledImporters.contains("Maven");

        List<Path> gradleProjects = new ArrayList<>();
        List<Path> mavenProjects = new ArrayList<>();

        // Use a FileVisitor so we can SKIP_SUBTREE for pruned directories
        Files.walkFileTree(workspaceRoot, EnumSet.noneOf(FileVisitOption.class),
                Integer.MAX_VALUE, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Never prune the root itself
                if (dir.equals(workspaceRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                String dirName = dir.getFileName().toString();
                // Skip hidden directories (except .gradle which is already in PRUNED_DIRS)
                if (dirName.startsWith(".") || PRUNED_DIRS.contains(dirName.toLowerCase())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String fileName = file.getFileName().toString();
                if (!BUILD_FILE_NAMES.contains(fileName)) {
                    return FileVisitResult.CONTINUE;
                }

                Path projectDir = file.getParent();
                if (projectDir == null) {
                    return FileVisitResult.CONTINUE;
                }

                if (!isJvmProject(projectDir)) {
                    return FileVisitResult.CONTINUE;
                }

                if (gradleEnabled && ("build.gradle".equals(fileName)
                        || "build.gradle.kts".equals(fileName))) {
                    gradleProjects.add(projectDir);
                } else if (mavenEnabled && "pom.xml".equals(fileName)) {
                    mavenProjects.add(projectDir);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Log and skip inaccessible files/directories
                logger.debug("Cannot access {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        logger.info("Unified discovery: {} Gradle project(s), {} Maven project(s) in {}",
                gradleProjects.size(), mavenProjects.size(), workspaceRoot);

        return new DiscoveryResult(gradleProjects, mavenProjects);
    }

    /**
     * Checks if the given directory looks like a JVM project by verifying
     * the presence of standard source directories.
     */
    static boolean isJvmProject(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/groovy"))
                || Files.isDirectory(projectDir.resolve("src/test/java"))
                || Files.isDirectory(projectDir.resolve("src/test/groovy"));
    }
}
