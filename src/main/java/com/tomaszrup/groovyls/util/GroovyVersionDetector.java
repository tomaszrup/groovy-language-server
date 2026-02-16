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
package com.tomaszrup.groovyls.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort detector for Groovy dependency versions from resolved classpath
 * entries. Used to decide which language features/parsing quirks to apply per
 * project scope.
 */
public final class GroovyVersionDetector {

    private static final Pattern TRAILING_VERSION =
            Pattern.compile("-(\\d+\\.\\d+(?:\\.\\d+)?(?:[-.][A-Za-z0-9]+)?)$");
    private static final Pattern GROOVY_CLASSIFIER_VERSION =
            Pattern.compile("(?:^|-)groovy-(\\d+\\.\\d+(?:\\.\\d+)?(?:[-.][A-Za-z0-9]+)?)(?:$|-)");

    private GroovyVersionDetector() {
    }

    /**
     * Detect a Groovy version from resolved classpath entries.
     *
     * <p>Preference order:</p>
     * <ol>
     *   <li>{@code groovy-<ver>.jar} and {@code groovy-all-<ver>.jar}</li>
     *   <li>Any other {@code groovy-*-<ver>.jar} module</li>
     * </ol>
     *
     * @param classpathEntries resolved classpath entries
     * @return detected version if found
     */
    public static Optional<String> detect(List<String> classpathEntries) {
        if (classpathEntries == null || classpathEntries.isEmpty()) {
            return Optional.empty();
        }

        List<String> coreCandidates = new ArrayList<>();
        List<String> moduleCandidates = new ArrayList<>();
        List<String> classifierCandidates = new ArrayList<>();

        for (String entry : classpathEntries) {
            addCandidates(entry, coreCandidates, moduleCandidates, classifierCandidates);
        }

        List<String> all;
        if (!coreCandidates.isEmpty()) {
            all = coreCandidates;
        } else if (!moduleCandidates.isEmpty()) {
            all = moduleCandidates;
        } else {
            all = classifierCandidates;
        }
        return all.stream().max(new VersionComparator());
    }

    private static void addCandidates(
            String classpathEntry,
            List<String> coreCandidates,
            List<String> moduleCandidates,
            List<String> classifierCandidates) {
        String fileName = toFileName(classpathEntry);
        if (fileName == null || !fileName.endsWith(".jar")) {
            return;
        }

        String base = fileName.substring(0, fileName.length() - 4);
        extractGroovyClassifierVersion(base).ifPresent(classifierCandidates::add);

        boolean isGroovyModule = base.startsWith("groovy-");
        boolean isSourceLikeArtifact = base.endsWith("-sources") || base.endsWith("-javadoc") || base.endsWith("-tests");
        if (isGroovyModule && !isSourceLikeArtifact) {
            extractTrailingVersion(base).ifPresent(version -> addVersionCandidate(base, version, coreCandidates, moduleCandidates));
        }
    }

    private static void addVersionCandidate(
            String base,
            String version,
            List<String> coreCandidates,
            List<String> moduleCandidates) {
        if (base.startsWith("groovy-all-") || isCoreGroovyArtifact(base)) {
            coreCandidates.add(version);
        } else {
            moduleCandidates.add(version);
        }
    }

    private static boolean isCoreGroovyArtifact(String base) {
        String prefix = "groovy-";
        if (!base.startsWith(prefix) || base.length() <= prefix.length()) {
            return false;
        }
        return Character.isDigit(base.charAt(prefix.length()));
    }

    /**
     * Parse major version from a semantic version string.
     */
    public static Optional<Integer> major(String version) {
        if (version == null || version.isEmpty()) {
            return Optional.empty();
        }
        int dot = version.indexOf('.');
        String majorPart = dot >= 0 ? version.substring(0, dot) : version;
        try {
            return Optional.of(Integer.parseInt(majorPart));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> extractTrailingVersion(String baseName) {
        Matcher m = TRAILING_VERSION.matcher(baseName);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1));
    }

    private static Optional<String> extractGroovyClassifierVersion(String baseName) {
        Matcher m = GROOVY_CLASSIFIER_VERSION.matcher(baseName);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1));
    }

    private static String toFileName(String classpathEntry) {
        if (classpathEntry == null || classpathEntry.isEmpty()) {
            return null;
        }
        try {
            return Path.of(classpathEntry).getFileName().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class VersionComparator implements Comparator<String> {
        @Override
        public int compare(String left, String right) {
            int[] a = versionNumbers(left);
            int[] b = versionNumbers(right);
            int max = Math.max(a.length, b.length);
            for (int i = 0; i < max; i++) {
                int ai = i < a.length ? a[i] : 0;
                int bi = i < b.length ? b[i] : 0;
                if (ai != bi) {
                    return Integer.compare(ai, bi);
                }
            }

            boolean aRelease = !hasQualifier(left);
            boolean bRelease = !hasQualifier(right);
            if (aRelease != bRelease) {
                return aRelease ? 1 : -1;
            }
            return left.compareToIgnoreCase(right);
        }

        private int[] versionNumbers(String version) {
            String numeric = version;
            int qualifierIdx = numeric.indexOf('-');
            if (qualifierIdx >= 0) {
                numeric = numeric.substring(0, qualifierIdx);
            }
            String[] parts = numeric.split("\\.");
            int[] numbers = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    numbers[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    numbers[i] = 0;
                }
            }
            return numbers;
        }

        private boolean hasQualifier(String version) {
            for (int i = 0; i < version.length(); i++) {
                char c = version.charAt(i);
                if (Character.isLetter(c)) {
                    return true;
                }
            }
            return false;
        }
    }
}