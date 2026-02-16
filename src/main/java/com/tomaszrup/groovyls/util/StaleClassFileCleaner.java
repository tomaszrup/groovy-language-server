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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes {@code .class} files from Gradle build output directories whose
 * corresponding source files ({@code .java} or {@code .groovy}) no longer
 * exist on disk.
 *
 * <p>This prevents the Groovy compiler (and the ClassGraph symbol index) from
 * resolving deleted classes via stale build artefacts, which would hide
 * missing-class diagnostics.</p>
 *
 * <p>The mapping follows the standard Gradle convention:
 * {@code build/classes/{language}/{sourceSet}} →
 * {@code src/{sourceSet}/{language}}.
 * Inner-class files ({@code Foo$Bar.class}) are mapped to the enclosing
 * top-level class's source file ({@code Foo.java}).</p>
 */
public final class StaleClassFileCleaner {

    private static final Logger logger = LoggerFactory.getLogger(StaleClassFileCleaner.class);

    private StaleClassFileCleaner() {
        // utility class
    }

    /**
     * Cleans stale {@code .class} files from all {@code build/classes/}
     * subdirectories of the given project.
     *
     * @param projectRoot the project root directory
     */
    public static void cleanProject(Path projectRoot) {
        Path classesRoot = projectRoot.resolve("build").resolve("classes");
        if (!Files.isDirectory(classesRoot)) {
            return;
        }
        try (Stream<Path> langDirs = Files.list(classesRoot)) {
            langDirs.filter(Files::isDirectory).forEach(langDir -> {
                String language = langDir.getFileName().toString(); // "java" or "groovy"
                try (Stream<Path> setDirs = Files.list(langDir)) {
                    setDirs.filter(Files::isDirectory).forEach(setDir -> {
                        String sourceSet = setDir.getFileName().toString(); // "main" or "test"
                        Path sourceDir = projectRoot.resolve("src").resolve(sourceSet).resolve(language);
                        cleanClassDir(setDir, sourceDir, "." + language);
                    });
                } catch (IOException e) {
                    logger.debug("Could not list source-set dirs in {}: {}", langDir, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.debug("Could not list language dirs in {}: {}", classesRoot, e.getMessage());
        }
    }

    /**
     * Cleans stale {@code .class} files from the given classpath entries that
     * look like Gradle class output directories (matching the pattern
     * {@code .../build/classes/{language}/{sourceSet}}).
     *
     * @param projectRoot    the project root directory (used to resolve source dirs)
     * @param classpathEntries the classpath entries to inspect
     */
    public static void cleanClasspathEntries(Path projectRoot, List<String> classpathEntries) {
        Path classesRoot = projectRoot.resolve("build").resolve("classes");
        for (String entry : classpathEntries) {
            Path entryPath = Path.of(entry);
            if (entryPath.startsWith(classesRoot)) {
                Path relative = classesRoot.relativize(entryPath);
                if (relative.getNameCount() >= 2) {
                    String language = relative.getName(0).toString();
                    String sourceSet = relative.getName(1).toString();
                    Path sourceDir = projectRoot.resolve("src").resolve(sourceSet).resolve(language);
                    cleanClassDir(entryPath, sourceDir, "." + language);
                }
            }
        }
    }

    /**
     * Removes {@code .class} files from {@code classDir} whose corresponding
     * source file does not exist in {@code sourceDir}.
     *
     * @param classDir       the class output directory to scan
     * @param sourceDir      the source directory to check for source files
     * @param sourceExtension the source file extension (e.g. {@code ".java"})
     */
    private static void cleanClassDir(Path classDir, Path sourceDir, String sourceExtension) {
        if (!Files.isDirectory(sourceDir) || !Files.isDirectory(classDir)) {
            return;
        }
        try (Stream<Path> classFiles = Files.walk(classDir)) {
            List<Path> toDelete = classFiles
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> {
                        Path rel = classDir.relativize(p);
                        String fileName = rel.getFileName().toString();
                        String baseName = fileName.substring(0, fileName.length() - ".class".length());
                        // Inner classes (Foo$Bar.class) → outer class source (Foo.java)
                        int dollar = baseName.indexOf('$');
                        if (dollar >= 0) {
                            baseName = baseName.substring(0, dollar);
                        }
                        Path pkgDir = rel.getParent();
                        Path expected = pkgDir != null
                                ? sourceDir.resolve(pkgDir).resolve(baseName + sourceExtension)
                                : sourceDir.resolve(baseName + sourceExtension);
                        return !Files.exists(expected);
                    })
                    .collect(Collectors.toList());

            int deleted = 0;
            for (Path stale : toDelete) {
                if (deleteStaleClassFile(stale)) {
                    deleted++;
                }
            }
            if (deleted > 0) {
                logger.info("Cleaned {} stale .class file(s) from {}", deleted, classDir);
            }
        } catch (IOException e) {
            logger.debug("Could not scan class dir {} for stale files: {}", classDir, e.getMessage());
        }
    }

    private static boolean deleteStaleClassFile(Path stale) {
        try {
            return Files.deleteIfExists(stale);
        } catch (IOException e) {
            logger.debug("Could not delete stale class file {}: {}", stale, e.getMessage());
            return false;
        }
    }
}
