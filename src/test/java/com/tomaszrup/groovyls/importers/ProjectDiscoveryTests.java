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
import java.util.Comparator;
import java.util.Set;

class ProjectDiscoveryTests {

    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("groovyls-discovery-test");
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

    // --- Single walk finds both Gradle and Maven ---

    @Test
    void testDiscoverAllFindsBothGradleAndMaven() throws IOException {
        // Gradle project
        Path gradleProject = tempDir.resolve("gradle-app");
        Files.createDirectories(gradleProject.resolve("src/main/java"));
        Files.createFile(gradleProject.resolve("build.gradle"));

        // Maven project
        Path mavenProject = tempDir.resolve("maven-app");
        Files.createDirectories(mavenProject.resolve("src/main/java"));
        Files.createFile(mavenProject.resolve("pom.xml"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertEquals(1, result.gradleProjects.size());
        Assertions.assertEquals(1, result.mavenProjects.size());
        Assertions.assertEquals(gradleProject, result.gradleProjects.get(0));
        Assertions.assertEquals(mavenProject, result.mavenProjects.get(0));
    }

    @Test
    void testDiscoverAllSkipsNonJvmProjects() throws IOException {
        // Gradle project without src dirs
        Path project = tempDir.resolve("nonjvm");
        Files.createDirectories(project);
        Files.createFile(project.resolve("build.gradle"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertTrue(result.gradleProjects.isEmpty());
        Assertions.assertTrue(result.mavenProjects.isEmpty());
    }

    @Test
    void testDiscoverAllReturnsEmptyForEmptyWorkspace() throws IOException {
        Files.createDirectories(tempDir.resolve("empty"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertTrue(result.gradleProjects.isEmpty());
        Assertions.assertTrue(result.mavenProjects.isEmpty());
    }

    // --- Directory pruning ---

    @Test
    void testDiscoverAllSkipsNodeModules() throws IOException {
        // Build file hidden inside node_modules should be ignored
        Path nodeModules = tempDir.resolve("node_modules/some-package");
        Files.createDirectories(nodeModules.resolve("src/main/java"));
        Files.createFile(nodeModules.resolve("build.gradle"));

        // Real project at top level
        Path real = tempDir.resolve("real-app");
        Files.createDirectories(real.resolve("src/main/java"));
        Files.createFile(real.resolve("build.gradle"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertEquals(1, result.gradleProjects.size());
        Assertions.assertEquals(real, result.gradleProjects.get(0));
    }

    @Test
    void testDiscoverAllSkipsBuildDirectory() throws IOException {
        // Build file inside build/ output dir should be ignored
        Path buildDir = tempDir.resolve("build/generated");
        Files.createDirectories(buildDir.resolve("src/main/java"));
        Files.createFile(buildDir.resolve("build.gradle"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertTrue(result.gradleProjects.isEmpty());
    }

    @Test
    void testDiscoverAllSkipsTargetDirectory() throws IOException {
        // pom.xml inside target/ output dir should be ignored
        Path targetDir = tempDir.resolve("target/generated");
        Files.createDirectories(targetDir.resolve("src/main/java"));
        Files.createFile(targetDir.resolve("pom.xml"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertTrue(result.mavenProjects.isEmpty());
    }

    @Test
    void testDiscoverAllSkipsDotGitDirectory() throws IOException {
        // Build file inside .git should be ignored
        Path gitDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(gitDir.resolve("src/main/java"));
        Files.createFile(gitDir.resolve("build.gradle"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertTrue(result.gradleProjects.isEmpty());
    }

    @Test
    void testDiscoverAllSkipsHiddenDirectories() throws IOException {
        // Build file inside .hidden dir should be ignored
        Path hidden = tempDir.resolve(".hidden-project");
        Files.createDirectories(hidden.resolve("src/main/java"));
        Files.createFile(hidden.resolve("build.gradle"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertTrue(result.gradleProjects.isEmpty());
    }

    // --- Enabled importers filtering ---

    @Test
    void testDiscoverAllWithOnlyGradleEnabled() throws IOException {
        Path gradleProject = tempDir.resolve("gradle-app");
        Files.createDirectories(gradleProject.resolve("src/main/java"));
        Files.createFile(gradleProject.resolve("build.gradle"));

        Path mavenProject = tempDir.resolve("maven-app");
        Files.createDirectories(mavenProject.resolve("src/main/java"));
        Files.createFile(mavenProject.resolve("pom.xml"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(
                tempDir, Set.of("Gradle"));

        Assertions.assertEquals(1, result.gradleProjects.size());
        Assertions.assertTrue(result.mavenProjects.isEmpty());
    }

    @Test
    void testDiscoverAllWithOnlyMavenEnabled() throws IOException {
        Path gradleProject = tempDir.resolve("gradle-app");
        Files.createDirectories(gradleProject.resolve("src/main/java"));
        Files.createFile(gradleProject.resolve("build.gradle"));

        Path mavenProject = tempDir.resolve("maven-app");
        Files.createDirectories(mavenProject.resolve("src/main/java"));
        Files.createFile(mavenProject.resolve("pom.xml"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(
                tempDir, Set.of("Maven"));

        Assertions.assertTrue(result.gradleProjects.isEmpty());
        Assertions.assertEquals(1, result.mavenProjects.size());
    }

    @Test
    void testDiscoverAllWithEmptyEnabledImporters() throws IOException {
        Path gradleProject = tempDir.resolve("gradle-app");
        Files.createDirectories(gradleProject.resolve("src/main/java"));
        Files.createFile(gradleProject.resolve("build.gradle"));

        Path mavenProject = tempDir.resolve("maven-app");
        Files.createDirectories(mavenProject.resolve("src/main/java"));
        Files.createFile(mavenProject.resolve("pom.xml"));

        // Empty set means all enabled
        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(
                tempDir, Set.of());

        Assertions.assertEquals(1, result.gradleProjects.size());
        Assertions.assertEquals(1, result.mavenProjects.size());
    }

    // --- Gradle Kotlin DSL ---

    @Test
    void testDiscoverAllFindsGradleKtsProjects() throws IOException {
        Path project = tempDir.resolve("kts-app");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("build.gradle.kts"));

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertEquals(1, result.gradleProjects.size());
        Assertions.assertEquals(project, result.gradleProjects.get(0));
    }

    // --- Multiple projects ---

    @Test
    void testDiscoverAllFindsMultipleProjectsOfEachType() throws IOException {
        // Two Gradle projects
        for (String name : new String[]{"gradle1", "gradle2"}) {
            Path p = tempDir.resolve(name);
            Files.createDirectories(p.resolve("src/main/java"));
            Files.createFile(p.resolve("build.gradle"));
        }
        // Two Maven projects
        for (String name : new String[]{"maven1", "maven2"}) {
            Path p = tempDir.resolve(name);
            Files.createDirectories(p.resolve("src/test/groovy"));
            Files.createFile(p.resolve("pom.xml"));
        }

        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(tempDir, null);

        Assertions.assertEquals(2, result.gradleProjects.size());
        Assertions.assertEquals(2, result.mavenProjects.size());
    }

    // --- isJvmProject ---

    @Test
    void testIsJvmProjectWithMainJava() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("src/main/java"));
        Assertions.assertTrue(ProjectDiscovery.isJvmProject(project));
    }

    @Test
    void testIsJvmProjectWithMainGroovy() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("src/main/groovy"));
        Assertions.assertTrue(ProjectDiscovery.isJvmProject(project));
    }

    @Test
    void testIsJvmProjectWithTestJava() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("src/test/java"));
        Assertions.assertTrue(ProjectDiscovery.isJvmProject(project));
    }

    @Test
    void testIsJvmProjectWithTestGroovy() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("src/test/groovy"));
        Assertions.assertTrue(ProjectDiscovery.isJvmProject(project));
    }

    @Test
    void testIsJvmProjectReturnsFalseWithoutSrcDirs() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Assertions.assertFalse(ProjectDiscovery.isJvmProject(project));
    }
}
