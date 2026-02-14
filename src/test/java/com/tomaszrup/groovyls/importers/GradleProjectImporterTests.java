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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class GradleProjectImporterTests {

    private GradleProjectImporter importer;
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        importer = new GradleProjectImporter();
        tempDir = Files.createTempDirectory("groovyls-gradle-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    @Test
    void testGetName() {
        Assertions.assertEquals("Gradle", importer.getName());
    }

    // --- isProjectFile tests ---

    @Test
    void testIsProjectFileWithBuildGradle() {
        Assertions.assertTrue(importer.isProjectFile("/some/path/build.gradle"));
    }

    @Test
    void testIsProjectFileWithBuildGradleKts() {
        Assertions.assertTrue(importer.isProjectFile("/some/path/build.gradle.kts"));
    }

    @Test
    void testIsProjectFileWithPomXml() {
        Assertions.assertFalse(importer.isProjectFile("/some/path/pom.xml"));
    }

    @Test
    void testIsProjectFileWithJavaFile() {
        Assertions.assertFalse(importer.isProjectFile("/some/path/Main.java"));
    }

    @Test
    void testIsProjectFileWithNull() {
        Assertions.assertFalse(importer.isProjectFile(null));
    }

    @Test
    void testIsProjectFileWithGroovyFile() {
        Assertions.assertFalse(importer.isProjectFile("/some/path/Script.groovy"));
    }

    // --- discoverProjects tests ---

    @Test
    void testDiscoverProjectsFindsGradleProjectWithJavaSrc() throws IOException {
        // Create a Gradle project with src/main/java
        Path project = tempDir.resolve("myproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(1, discovered.size());
        Assertions.assertEquals(project, discovered.get(0));
    }

    @Test
    void testDiscoverProjectsFindsGradleProjectWithGroovySrc() throws IOException {
        // Create a Gradle project with src/main/groovy
        Path project = tempDir.resolve("myproject");
        Files.createDirectories(project.resolve("src/main/groovy"));
        Files.createFile(project.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(1, discovered.size());
        Assertions.assertEquals(project, discovered.get(0));
    }

    @Test
    void testDiscoverProjectsFindsGradleKtsProject() throws IOException {
        // Create a Gradle Kotlin DSL project
        Path project = tempDir.resolve("ktsproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("build.gradle.kts"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(1, discovered.size());
        Assertions.assertEquals(project, discovered.get(0));
    }

    @Test
    void testDiscoverProjectsSkipsGradleProjectWithoutJvmSrc() throws IOException {
        // Create a Gradle project without any recognized source directories
        Path project = tempDir.resolve("nonjvm");
        Files.createDirectories(project);
        Files.createFile(project.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertTrue(discovered.isEmpty());
    }

    @Test
    void testDiscoverProjectsReturnsEmptyWhenNoGradleFiles() throws IOException {
        // Empty directory — no build.gradle at all
        Files.createDirectories(tempDir.resolve("empty"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertTrue(discovered.isEmpty());
    }

    @Test
    void testDiscoverProjectsIgnoresPomXml() throws IOException {
        // Create a Maven project — Gradle importer should not find it
        Path project = tempDir.resolve("mavenproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("pom.xml"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertTrue(discovered.isEmpty());
    }

    @Test
    void testDiscoverProjectsFindsMultipleProjects() throws IOException {
        // Create two Gradle projects
        Path project1 = tempDir.resolve("project1");
        Files.createDirectories(project1.resolve("src/main/java"));
        Files.createFile(project1.resolve("build.gradle"));

        Path project2 = tempDir.resolve("project2");
        Files.createDirectories(project2.resolve("src/test/groovy"));
        Files.createFile(project2.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(2, discovered.size());
        Assertions.assertTrue(discovered.contains(project1));
        Assertions.assertTrue(discovered.contains(project2));
    }

    @Test
    void testDiscoverProjectsFindsTestJavaSrcDir() throws IOException {
        // Project with only src/test/java
        Path project = tempDir.resolve("testonly");
        Files.createDirectories(project.resolve("src/test/java"));
        Files.createFile(project.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(1, discovered.size());
        Assertions.assertEquals(project, discovered.get(0));
    }

    @Test
    void testDiscoverProjectsFindsTestGroovySrcDir() throws IOException {
        // Project with only src/test/groovy
        Path project = tempDir.resolve("testgroovy");
        Files.createDirectories(project.resolve("src/test/groovy"));
        Files.createFile(project.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(1, discovered.size());
        Assertions.assertEquals(project, discovered.get(0));
    }

    @Test
    void testDetectProjectGroovyVersionFallsBackToClasspath() {
        Optional<String> detected = importer.detectProjectGroovyVersion(
                tempDir,
                Arrays.asList("/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar"));

        Assertions.assertTrue(detected.isPresent());
        Assertions.assertEquals("5.0.4", detected.get());
    }

    @Test
    void testDetectProjectGroovyVersionUsesCachedDetectedValue() throws Exception {
        Path project = tempDir.resolve("cached-version-project").toAbsolutePath().normalize();
        Files.createDirectories(project);

        Field f = GradleProjectImporter.class.getDeclaredField("detectedGroovyVersionByProject");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) f.get(importer);
        cache.put(project.toString().replace('\\', '/').toLowerCase(), "4.0.30");

        Optional<String> detected = importer.detectProjectGroovyVersion(
                project,
                Arrays.asList("/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar"));

        Assertions.assertTrue(detected.isPresent());
        Assertions.assertEquals("4.0.30", detected.get());
    }
}
