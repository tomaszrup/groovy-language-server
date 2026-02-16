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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

class MavenProjectImporterTests {

    private MavenProjectImporter importer;
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        importer = new MavenProjectImporter();
        tempDir = Files.createTempDirectory("groovyls-maven-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(this::deletePathQuietly);
        }
    }

    @Test
    void testGetName() {
        Assertions.assertEquals("Maven", importer.getName());
    }

    // --- isProjectFile tests ---

    @Test
    void testIsProjectFileWithPomXml() {
        Assertions.assertTrue(importer.isProjectFile("/some/path/pom.xml"));
    }

    @Test
    void testIsProjectFileWithNestedPomXml() {
        Assertions.assertTrue(importer.isProjectFile("/workspace/submodule/pom.xml"));
    }

    @Test
    void testIsProjectFileWithBuildGradle() {
        Assertions.assertFalse(importer.isProjectFile("/some/path/build.gradle"));
    }

    @Test
    void testIsProjectFileWithBuildGradleKts() {
        Assertions.assertFalse(importer.isProjectFile("/some/path/build.gradle.kts"));
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

    @Test
    void testIsProjectFileWithPartialMatch() {
        // "pom.xml.bak" should NOT match
        Assertions.assertFalse(importer.isProjectFile("/some/path/pom.xml.bak"));
    }

    // --- discoverProjects tests ---

    @Test
    void testDiscoverProjectsFindsMavenProjectWithJvmSrc() throws IOException {
        for (String sourceDir : new String[] {"src/main/java", "src/main/groovy", "src/test/java", "src/test/groovy"}) {
            Path caseRoot = tempDir.resolve("case-" + sourceDir.replace('/', '-'));
            Files.createDirectories(caseRoot.resolve(sourceDir));
            Files.createFile(caseRoot.resolve("pom.xml"));

            List<Path> discovered = importer.discoverProjects(caseRoot);
            Assertions.assertEquals(1, discovered.size());
            Assertions.assertEquals(caseRoot, discovered.get(0));
        }
    }

    @Test
    void testDiscoverProjectsSkipsMavenProjectWithoutJvmSrc() throws IOException {
        // Maven project without any recognized source directories (e.g. parent pom)
        Path project = tempDir.resolve("parentpom");
        Files.createDirectories(project);
        Files.createFile(project.resolve("pom.xml"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertTrue(discovered.isEmpty());
    }

    @Test
    void testDiscoverProjectsReturnsEmptyWhenNoPomXml() throws IOException {
        Files.createDirectories(tempDir.resolve("empty"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertTrue(discovered.isEmpty());
    }

    @Test
    void testDiscoverProjectsIgnoresBuildGradle() throws IOException {
        // Create a Gradle project — Maven importer should not find it
        Path project = tempDir.resolve("gradleproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("build.gradle"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertTrue(discovered.isEmpty());
    }

    @Test
    void testDiscoverProjectsFindsMultipleProjects() throws IOException {
        Path project1 = tempDir.resolve("module-a");
        Files.createDirectories(project1.resolve("src/main/java"));
        Files.createFile(project1.resolve("pom.xml"));

        Path project2 = tempDir.resolve("module-b");
        Files.createDirectories(project2.resolve("src/test/groovy"));
        Files.createFile(project2.resolve("pom.xml"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        Assertions.assertEquals(2, discovered.size());
        Assertions.assertTrue(discovered.contains(project1));
        Assertions.assertTrue(discovered.contains(project2));
    }

    @Test
    void testDiscoverProjectsFindsNestedMavenModules() throws IOException {
        // Parent with pom.xml but no sources (should be excluded)
        Path parent = tempDir.resolve("parent");
        Files.createDirectories(parent);
        Files.createFile(parent.resolve("pom.xml"));

        // Child module with sources (should be found)
        Path child = parent.resolve("child");
        Files.createDirectories(child.resolve("src/main/java"));
        Files.createFile(child.resolve("pom.xml"));

        List<Path> discovered = importer.discoverProjects(tempDir);
        // Only the child should be discovered (parent has no src dirs)
        Assertions.assertEquals(1, discovered.size());
        Assertions.assertEquals(child, discovered.get(0));
    }

    // --- importProject: discoverClassDirs tests ---

    @Test
    void testImportProjectIncludesTargetClassesDir() throws IOException {
        Path project = tempDir.resolve("mvnproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("pom.xml"));
        Files.createDirectories(project.resolve("target/classes"));

        // importProject will try to run mvn (which will fail since there's no real pom),
        // but discoverClassDirs should still find target/classes
        List<String> classpath = importer.importProject(project);
        Assertions.assertTrue(classpath.stream().anyMatch(
                entry -> entry.endsWith("target" + java.io.File.separator + "classes")
                        || entry.endsWith("target/classes")));
    }

    @Test
    void testImportProjectIncludesTargetTestClassesDir() throws IOException {
        Path project = tempDir.resolve("mvnproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("pom.xml"));
        Files.createDirectories(project.resolve("target/test-classes"));

        List<String> classpath = importer.importProject(project);
        Assertions.assertTrue(classpath.stream().anyMatch(
                entry -> entry.endsWith("target" + java.io.File.separator + "test-classes")
                        || entry.endsWith("target/test-classes")));
    }

    @Test
    void testImportProjectIncludesBothClassDirs() throws IOException {
        Path project = tempDir.resolve("mvnproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("pom.xml"));
        Files.createDirectories(project.resolve("target/classes"));
        Files.createDirectories(project.resolve("target/test-classes"));

        List<String> classpath = importer.importProject(project);
        boolean hasClasses = classpath.stream().anyMatch(
                entry -> entry.endsWith("target" + java.io.File.separator + "classes")
                        || entry.endsWith("target/classes"));
        boolean hasTestClasses = classpath.stream().anyMatch(
                entry -> entry.endsWith("target" + java.io.File.separator + "test-classes")
                        || entry.endsWith("target/test-classes"));
        Assertions.assertTrue(hasClasses, "Expected target/classes in classpath");
        Assertions.assertTrue(hasTestClasses, "Expected target/test-classes in classpath");
    }

    @Test
    void testImportProjectReturnsEmptyWhenNoTargetDirs() throws IOException {
        // No target/ directories exist — classpath should still be returned (empty or
        // may contain entries from mvn output if mvn is available)
        Path project = tempDir.resolve("mvnproject");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("pom.xml"));

        // Should not throw, even if mvn is not installed
        List<String> classpath = importer.importProject(project);
        Assertions.assertNotNull(classpath);
    }

    // --- setMavenHome tests ---

    @Test
    void testSetMavenHomeDoesNotThrow() {
        // Just verify the setter works without error
        Assertions.assertDoesNotThrow(() -> importer.setMavenHome("/path/to/maven"));
    }

    @Test
    void testSetMavenHomeNull() {
        Assertions.assertDoesNotThrow(() -> importer.setMavenHome(null));
    }

    // --- Maven Wrapper tests ---

    @Test
    void testImportProjectUsesMavenWrapperWhenPresent() throws IOException {
        // Create a project with a Maven wrapper script
        Path project = tempDir.resolve("wrapper-project");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("pom.xml"));
        Files.createDirectories(project.resolve("target/classes"));

        // Create a fake mvnw / mvnw.cmd file
        String wrapperName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "mvnw.cmd" : "mvnw";
        Files.createFile(project.resolve(wrapperName));

        // importProject should not throw — even though the wrapper is fake,
        // the code should attempt to use it and handle the failure gracefully
        List<String> classpath = importer.importProject(project);
        Assertions.assertNotNull(classpath);
        // target/classes should still be discovered regardless of mvn execution result
        Assertions.assertTrue(classpath.stream().anyMatch(
                entry -> entry.endsWith("target" + java.io.File.separator + "classes")
                        || entry.endsWith("target/classes")));
    }

    @Test
    void testImportProjectFindsWrapperInParentDir() throws IOException {
        // Multi-module layout: wrapper in parent, submodule discovered separately
        Path parent = tempDir.resolve("parent");
        Files.createDirectories(parent);
        Files.createFile(parent.resolve("pom.xml"));

        String wrapperName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "mvnw.cmd" : "mvnw";
        Files.createFile(parent.resolve(wrapperName));

        // Child module
        Path child = parent.resolve("child");
        Files.createDirectories(child.resolve("src/main/java"));
        Files.createFile(child.resolve("pom.xml"));
        Files.createDirectories(child.resolve("target/classes"));

        // importProject on the child should find the wrapper in the parent
        List<String> classpath = importer.importProject(child);
        Assertions.assertNotNull(classpath);
        Assertions.assertTrue(classpath.stream().anyMatch(
                entry -> entry.endsWith("target" + java.io.File.separator + "classes")
                        || entry.endsWith("target/classes")));
    }

        @Test
        void testDetectProjectGroovyVersionFromPomDependency() throws IOException {
        Path project = tempDir.resolve("groovy-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>app</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.apache.groovy</groupId>\n"
                + "      <artifactId>groovy</artifactId>\n"
                + "      <version>5.0.4</version>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n");

        Optional<String> detected = importer.detectProjectGroovyVersion(project, Arrays.asList(
            "/repo/org/apache/groovy/groovy/4.0.30/groovy-4.0.30.jar"));

        Assertions.assertTrue(detected.isPresent());
        Assertions.assertEquals("5.0.4", detected.get());
        }

        @Test
        void testDetectProjectGroovyVersionFromPomPropertyReference() throws IOException {
        Path project = tempDir.resolve("groovy-property-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>app</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <properties>\n"
                + "    <groovy.version>4.0.30</groovy.version>\n"
                + "  </properties>\n"
                + "  <dependencyManagement>\n"
                + "    <dependencies>\n"
                + "      <dependency>\n"
                + "        <groupId>org.apache.groovy</groupId>\n"
                + "        <artifactId>groovy-bom</artifactId>\n"
                + "        <version>${groovy.version}</version>\n"
                + "        <type>pom</type>\n"
                + "        <scope>import</scope>\n"
                + "      </dependency>\n"
                + "    </dependencies>\n"
                + "  </dependencyManagement>\n"
                + "</project>\n");

        Optional<String> detected = importer.detectProjectGroovyVersion(project, Arrays.asList(
            "/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar"));

        Assertions.assertTrue(detected.isPresent());
        Assertions.assertEquals("4.0.30", detected.get());
        }

        @Test
        void testDetectProjectGroovyVersionFallsBackToClasspathWhenPomHasNoGroovy() throws IOException {
        Path project = tempDir.resolve("no-groovy-pom-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>app</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "</project>\n");

        Optional<String> detected = importer.detectProjectGroovyVersion(project, Arrays.asList(
            "/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar"));

        Assertions.assertTrue(detected.isPresent());
        Assertions.assertEquals("5.0.4", detected.get());
        }

        @Test
        void testShouldMarkClasspathResolvedFalseForTargetOnlyWhenPomHasDependencies() throws IOException {
        Path project = tempDir.resolve("target-only-with-deps");
        Files.createDirectories(project.resolve("target/classes"));
        Files.createDirectories(project.resolve("target/test-classes"));
        Files.writeString(project.resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>app</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.spockframework</groupId>\n"
                + "      <artifactId>spock-core</artifactId>\n"
                + "      <version>2.4-M1-groovy-4.0</version>\n"
                + "      <scope>test</scope>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n"
                + "</project>\n");

        List<String> classpath = Arrays.asList(
            project.resolve("target/classes").toString(),
            project.resolve("target/test-classes").toString());

        Assertions.assertFalse(importer.shouldMarkClasspathResolved(project, classpath));
        }

        @Test
        void testShouldMarkClasspathResolvedTrueForTargetOnlyWhenPomHasNoDependencies() throws IOException {
        Path project = tempDir.resolve("target-only-no-deps");
        Files.createDirectories(project.resolve("target/classes"));
        Files.createDirectories(project.resolve("target/test-classes"));
        Files.writeString(project.resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>app</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "</project>\n");

        List<String> classpath = Arrays.asList(
            project.resolve("target/classes").toString(),
            project.resolve("target/test-classes").toString());

        Assertions.assertTrue(importer.shouldMarkClasspathResolved(project, classpath));
        }

        private void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup for temporary test files
        }
        }
}
