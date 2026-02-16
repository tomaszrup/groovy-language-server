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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility methods for discovering, validating, and indexing
 * source JARs ({@code *-sources.jar}).
 * Extracted from {@link JavaSourceLocator} for single-responsibility.
 */
class SourceJarIndexer {
    private static final Logger logger = LoggerFactory.getLogger(SourceJarIndexer.class);

    static final int MAX_SOURCE_JAR_ENTRIES_TO_INDEX = 200_000;
    static final long MAX_SOURCE_JAR_ENTRY_BYTES = 16L * 1024L * 1024L;
    static final long MAX_SOURCE_JAR_TOTAL_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    static final long MAX_SUSPICIOUS_COMPRESSION_RATIO = 400L;

    private static final String JAVA_EXTENSION = ".java";
    private static final String GROOVY_EXTENSION = ".groovy";

    private SourceJarIndexer() {
        // utility class
    }

    /**
     * Locate the {@code *-sources.jar} corresponding to a compiled JAR on the
     * classpath.
     */
    static Path findSourceJar(String classpathEntry) {
        if (classpathEntry == null) {
            return null;
        }
        Path jarPath = Paths.get(classpathEntry);
        if (!Files.isRegularFile(jarPath)) {
            return null;
        }
        String fileName = jarPath.getFileName().toString();
        if (!fileName.endsWith(".jar")) {
            return null;
        }

        String baseName = fileName.substring(0, fileName.length() - 4);
        String sourcesFileName = baseName + "-sources.jar";

        // 1. Maven / sibling convention
        Path sourcesJar = jarPath.resolveSibling(sourcesFileName);
        if (Files.isRegularFile(sourcesJar)) {
            return sourcesJar;
        }

        // 2. Gradle module cache convention
        Path hashDir = jarPath.getParent();
        if (hashDir != null) {
            Path versionDir = hashDir.getParent();
            if (versionDir != null && isGradleCacheLayout(versionDir)) {
                try (Stream<Path> dirs = Files.list(versionDir)) {
                    Path found = dirs
                            .filter(Files::isDirectory)
                            .filter(d -> !d.equals(hashDir))
                            .map(d -> d.resolve(sourcesFileName))
                            .filter(Files::isRegularFile)
                            .findFirst()
                            .orElse(null);
                    if (found != null) {
                        return found;
                    }
                } catch (IOException ignored) {
                    // ignore errors during Gradle cache search
                }
            }
        }

        return null;
    }

    /**
     * Check whether the given directory appears to be a version directory
     * inside the Gradle module cache.
     */
    static boolean isGradleCacheLayout(Path versionDir) {
        Path artifactDir = versionDir.getParent();
        if (artifactDir == null) return false;
        Path groupDir = artifactDir.getParent();
        if (groupDir == null) return false;
        Path cacheDir = groupDir.getParent();
        if (cacheDir == null) return false;
        return "files-2.1".equals(cacheDir.getFileName().toString());
    }

    /**
     * Index a source JAR, adding entries to the target map.
     */
    static void indexSourceJar(Path sourceJar, Map<String, JavaSourceLocator.SourceJarEntry> target) {
        if (!isSafeSourceJarPath(sourceJar)) {
            logger.warn("Skipping unsafe source JAR path: {}", sourceJar);
            return;
        }
        try (JarFile jar = new JarFile(sourceJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            int processedEntries = 0;
            long totalExpandedBytes = 0;
            boolean stopIndexing = false;
            while (entries.hasMoreElements() && !stopIndexing) {
                processedEntries++;
                JarEntry entry = entries.nextElement();
                stopIndexing = reachedEntryLimit(processedEntries, sourceJar)
                        || exceededExpandedSizeLimit(entry, sourceJar, totalExpandedBytes);
                if (!stopIndexing) {
                    totalExpandedBytes = updateExpandedBytes(totalExpandedBytes, entry);
                    indexSourceJarEntry(sourceJar, target, entry);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to index source JAR {}: {}", sourceJar, e.getMessage());
        }
    }

    private static boolean reachedEntryLimit(int processedEntries, Path sourceJar) {
        if (processedEntries <= MAX_SOURCE_JAR_ENTRIES_TO_INDEX) {
            return false;
        }
        logger.warn("Stopped indexing source JAR {} after {} entries (safety limit)",
                sourceJar, MAX_SOURCE_JAR_ENTRIES_TO_INDEX);
        return true;
    }

    private static boolean exceededExpandedSizeLimit(JarEntry entry, Path sourceJar, long totalExpandedBytes) {
        long nextTotal = updateExpandedBytes(totalExpandedBytes, entry);
        if (nextTotal <= MAX_SOURCE_JAR_TOTAL_UNCOMPRESSED_BYTES) {
            return false;
        }
        logger.warn("Stopped indexing source JAR {} after exceeding total expanded size limit", sourceJar);
        return true;
    }

    private static long updateExpandedBytes(long current, JarEntry entry) {
        long size = entry != null ? entry.getSize() : -1;
        return size > 0 ? current + size : current;
    }

    private static void indexSourceJarEntry(
            Path sourceJar, Map<String, JavaSourceLocator.SourceJarEntry> target, JarEntry entry) {
        if (entry == null) {
            return;
        }
        if (!isSafeArchiveEntryName(entry.getName()) || isSuspiciousArchiveEntry(entry)) {
            logger.warn("Skipping suspicious source JAR entry {} from {}", entry.getName(), sourceJar);
            return;
        }
        String name = entry.getName();
        boolean sourceLike = (name.endsWith(JAVA_EXTENSION) || name.endsWith(GROOVY_EXTENSION))
                && !entry.isDirectory();
        if (!sourceLike) {
            return;
        }
        String fqcn = jarEntryToClassName(name);
        if (fqcn != null) {
            target.putIfAbsent(fqcn, new JavaSourceLocator.SourceJarEntry(sourceJar, name));
        }
    }

    /**
     * Convert a JAR entry path to a fully-qualified class name.
     */
    static String jarEntryToClassName(String entryName) {
        if (entryName == null) {
            return null;
        }
        int extLen;
        if (entryName.endsWith(JAVA_EXTENSION)) {
            extLen = JAVA_EXTENSION.length();
        } else if (entryName.endsWith(GROOVY_EXTENSION)) {
            extLen = GROOVY_EXTENSION.length();
        } else {
            return null;
        }
        String path = entryName;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = path.substring(0, path.length() - extLen);
        return path.replace('/', '.');
    }

    /**
     * Read the content of a source file from inside a source JAR.
     */
    static List<String> readSourceFromJar(JavaSourceLocator.SourceJarEntry entry) {
        if (entry == null || !isSafeSourceJarPath(entry.sourceJarPath)
                || !isSafeArchiveEntryName(entry.entryName)) {
            return Collections.emptyList();
        }
        try (JarFile jar = new JarFile(entry.sourceJarPath.toFile())) {
            JarEntry jarEntry = jar.getJarEntry(entry.entryName);
            if (jarEntry == null) {
                return Collections.emptyList();
            }
            if (isSuspiciousArchiveEntry(jarEntry)) {
                logger.warn("Skipping suspicious source JAR entry {} from {}",
                        entry.entryName, entry.sourceJarPath);
                return Collections.emptyList();
            }
            long size = jarEntry.getSize();
            if (size > MAX_SOURCE_JAR_ENTRY_BYTES) {
                logger.warn("Skipping oversized source JAR entry {} ({} bytes) from {}",
                        entry.entryName, size, entry.sourceJarPath);
                return Collections.emptyList();
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jar.getInputStream(jarEntry), StandardCharsets.UTF_8))) {
                String line;
                long accumulatedChars = 0;
                while ((line = reader.readLine()) != null) {
                    accumulatedChars += line.length();
                    if (accumulatedChars > MAX_SOURCE_JAR_ENTRY_BYTES) {
                        logger.warn("Skipping source JAR entry {} from {} after exceeding size limit during read",
                                entry.entryName, entry.sourceJarPath);
                        return Collections.emptyList();
                    }
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            logger.warn("Failed to read {} from {}: {}",
                    entry.entryName, entry.sourceJarPath, e.getMessage());
            return Collections.emptyList();
        }
    }

    static boolean isSuspiciousArchiveEntry(JarEntry entry) {
        if (entry == null || entry.isDirectory()) {
            return false;
        }
        long uncompressed = entry.getSize();
        long compressed = entry.getCompressedSize();
        if (uncompressed > MAX_SOURCE_JAR_ENTRY_BYTES) {
            return true;
        }
        if (uncompressed > 0 && compressed > 0) {
            long ratio = uncompressed / Math.max(1L, compressed);
            return ratio > MAX_SUSPICIOUS_COMPRESSION_RATIO;
        }
        return false;
    }

    static boolean isSafeSourceJarPath(Path sourceJarPath) {
        if (sourceJarPath == null) {
            return false;
        }
        try {
            Path normalized = sourceJarPath.normalize();
            return Files.isRegularFile(normalized)
                    && normalized.getFileName() != null
                    && normalized.getFileName().toString().endsWith(".jar");
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isSafeArchiveEntryName(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.contains("..\\")) {
            return false;
        }
        return !normalized.contains(":");
    }
}
