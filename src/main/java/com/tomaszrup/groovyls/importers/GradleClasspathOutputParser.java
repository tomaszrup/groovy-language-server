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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tomaszrup.groovyls.util.GroovyVersionDetector;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses and applies the output produced by Gradle init scripts that resolve
 * classpaths and Groovy versions. Also provides path normalisation and a
 * logging {@link OutputStream} for Gradle Tooling API output.
 *
 * <p>This is a package-private helper extracted from
 * {@link GradleProjectImporter} to keep the importer under 1000 lines.</p>
 */
class GradleClasspathOutputParser {

    private static final Logger logger = LoggerFactory.getLogger(GradleClasspathOutputParser.class);

    static final String OUTPUT_PREFIX_MAIN = "GROOVYLS_CP_MAIN:";
    static final String OUTPUT_PREFIX_TEST = "GROOVYLS_CP_TEST:";
    static final String OUTPUT_PREFIX_GROOVY_VERSION = "GROOVYLS_GROOVY_VERSION:";
    static final String OUTPUT_PREFIX_LEGACY = "GROOVYLS_CP:";

    static final class ParsedInitOutputLine {
        final String projectDir;
        final String value;
        final boolean test;
        final boolean groovyVersion;

        ParsedInitOutputLine(String projectDir, String value, boolean test, boolean groovyVersion) {
            this.projectDir = projectDir;
            this.value = value;
            this.test = test;
            this.groovyVersion = groovyVersion;
        }
    }

    private final Map<String, String> detectedGroovyVersionByProject;

    GradleClasspathOutputParser(Map<String, String> detectedGroovyVersionByProject) {
        this.detectedGroovyVersionByProject = detectedGroovyVersionByProject;
    }

    // ----------------------------------------------------------------
    // Output parsing
    // ----------------------------------------------------------------

    void processBatchClasspathOutput(String output, Map<String, Set<String>> mainByProject,
            Map<String, Set<String>> testByProject) {
        for (String line : output.split("\\r?\\n")) {
            Optional<ParsedInitOutputLine> parsed = parseInitOutputLine(line);
            parsed.ifPresent(value -> applyBatchClasspathOutput(mainByProject, testByProject, value));
        }
    }

    void processSingleClasspathOutput(String output, Map<String, Set<String>> dedupByProject) {
        for (String line : output.split("\\r?\\n")) {
            Optional<ParsedInitOutputLine> parsed = parseInitOutputLine(line);
            parsed.ifPresent(value -> applySingleClasspathOutput(dedupByProject, value));
        }
    }

    Optional<ParsedInitOutputLine> parseInitOutputLine(String rawLine) {
        String line = rawLine.trim();
        boolean isMain = line.startsWith(OUTPUT_PREFIX_MAIN);
        boolean isTest = line.startsWith(OUTPUT_PREFIX_TEST);
        boolean isGroovyVersion = line.startsWith(OUTPUT_PREFIX_GROOVY_VERSION);
        boolean isLegacy = !isMain && !isTest && line.startsWith(OUTPUT_PREFIX_LEGACY);
        if (!isMain && !isTest && !isLegacy && !isGroovyVersion) {
            return Optional.empty();
        }

        String prefix = resolveOutputPrefix(isMain, isTest, isGroovyVersion);
        String rest = line.substring(prefix.length());
        int separatorIdx = findProjectDirSeparator(rest);
        if (separatorIdx < 0) {
            return Optional.empty();
        }

        String projectDir = normalise(rest.substring(0, separatorIdx));
        String value = rest.substring(separatorIdx + 1);
        return Optional.of(new ParsedInitOutputLine(projectDir, value, isTest, isGroovyVersion));
    }

    // ----------------------------------------------------------------
    // Output application
    // ----------------------------------------------------------------

    private void applyBatchClasspathOutput(
            Map<String, Set<String>> mainByProject,
            Map<String, Set<String>> testByProject,
            ParsedInitOutputLine parsed) {
        if (parsed.groovyVersion) {
            registerDetectedGroovyVersion(parsed.projectDir, parsed.value);
        } else {
            addClasspathEntry(mainByProject, testByProject, parsed.projectDir, parsed.value, parsed.test);
        }
    }

    private void applySingleClasspathOutput(Map<String, Set<String>> dedupByProject, ParsedInitOutputLine parsed) {
        if (parsed.groovyVersion) {
            registerDetectedGroovyVersion(parsed.projectDir, parsed.value);
        } else {
            String canonical = canonicalizeExistingPath(parsed.value);
            if (canonical != null) {
                dedupByProject.computeIfAbsent(parsed.projectDir, k -> new LinkedHashSet<>()).add(canonical);
            }
        }
    }

    private void addClasspathEntry(
            Map<String, Set<String>> mainByProject,
            Map<String, Set<String>> testByProject,
            String projectDir,
            String entry,
            boolean testEntry) {
        String canonical = canonicalizeExistingPath(entry);
        if (canonical != null) {
            Map<String, Set<String>> target = testEntry ? testByProject : mainByProject;
            target.computeIfAbsent(projectDir, k -> new LinkedHashSet<>()).add(canonical);
        }
    }

    // ----------------------------------------------------------------
    // Groovy version tracking
    // ----------------------------------------------------------------

    private String pickHigherGroovyVersion(String left, String right) {
        Optional<String> detected = GroovyVersionDetector.detect(Arrays.asList(
                "/fake/groovy-" + left + ".jar",
                "/fake/groovy-" + right + ".jar"));
        return detected.orElse(right);
    }

    private void registerDetectedGroovyVersion(String projectDir, String version) {
        if (!version.isEmpty()) {
            detectedGroovyVersionByProject.merge(projectDir, version, this::pickHigherGroovyVersion);
        }
    }

    // ----------------------------------------------------------------
    // Path & prefix utilities
    // ----------------------------------------------------------------

    private String resolveOutputPrefix(boolean isMain, boolean isTest, boolean isGroovyVersion) {
        if (isMain) {
            return OUTPUT_PREFIX_MAIN;
        }
        if (isTest) {
            return OUTPUT_PREFIX_TEST;
        }
        if (isGroovyVersion) {
            return OUTPUT_PREFIX_GROOVY_VERSION;
        }
        return OUTPUT_PREFIX_LEGACY;
    }

    String canonicalizeExistingPath(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException ex) {
            return path;
        }
    }

    /**
     * In the output {@code <projectDir>:<cpEntry>}, find the colon that separates
     * the two paths. On Windows both paths contain {@code :} after the drive letter,
     * so we look for {@code :<drive-letter>:\} or {@code :<drive-letter>:/} as the
     * separator. On Unix the first {@code :} is the separator.
     */
    int findProjectDirSeparator(String rest) {
        boolean isWindows = File.separatorChar == '\\';
        if (isWindows) {
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

    /** Normalise a path for comparison (resolve to absolute, normalise separators). */
    String normalise(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase();
    }

    /** Normalise a path string for comparison. */
    String normalise(String pathStr) {
        return Path.of(pathStr).toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase();
    }

    // ----------------------------------------------------------------
    // Gradle output logging
    // ----------------------------------------------------------------

    /**
     * Creates an OutputStream that routes each line of output to the SLF4J
     * logger at TRACE level, instead of dumping to System.out/System.err.
     */
    OutputStream newLogOutputStream() {
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
                int start = off;
                for (int i = off; i < off + len; i++) {
                    if (bytes[i] == '\n') {
                        buffer.write(bytes, start, i - start);
                        flush();
                        start = i + 1;
                    }
                }
                if (start < off + len) {
                    buffer.write(bytes, start, off + len - start);
                }
            }

            @Override
            public void flush() {
                String line = buffer.toString(StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    logger.trace("[Gradle] {}", line);
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
