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
import java.util.List;
import java.util.Map;

/**
 * Additional tests for {@link GradleProjectImporter} covering:
 * <ul>
 *   <li>{@code getGradleRoot()} / {@code findGradleRoot()} — Gradle root detection</li>
 *   <li>{@code resolveClasspath()} — single-project class-dir discovery</li>
 *   <li>{@code setWorkspaceBound()} — bounding root search</li>
 * </ul>
 */
class GradleProjectImporterAdditionalTests {

    private GradleProjectImporter importer;
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        importer = new GradleProjectImporter();
        tempDir = Files.createTempDirectory("groovyls-gradle-extra-test");
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

    // --- getGradleRoot / findGradleRoot tests ---

    @Test
    void testGetGradleRootFindsSettingsGradle() throws IOException {
        // workspace/
        //   settings.gradle
        //   subA/
        //     build.gradle
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve("subA"));
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(root.resolve("subA/build.gradle"));

        importer.setWorkspaceBound(root);
        Path gradleRoot = importer.getGradleRoot(root.resolve("subA"));
        Assertions.assertEquals(root, gradleRoot,
                "Should walk up to directory with settings.gradle");
    }

    @Test
    void testGetGradleRootFindsSettingsGradleKts() throws IOException {
        // workspace/
        //   settings.gradle.kts
        //   subB/
        //     build.gradle.kts
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve("subB"));
        Files.createFile(root.resolve("settings.gradle.kts"));
        Files.createFile(root.resolve("subB/build.gradle.kts"));

        importer.setWorkspaceBound(root);
        Path gradleRoot = importer.getGradleRoot(root.resolve("subB"));
        Assertions.assertEquals(root, gradleRoot,
                "Should walk up to directory with settings.gradle.kts");
    }

    @Test
    void testGetGradleRootReturnsProjectDirWhenNoSettings() throws IOException {
        // workspace/
        //   standalone/
        //     build.gradle
        Path root = tempDir.resolve("workspace");
        Path standalone = root.resolve("standalone");
        Files.createDirectories(standalone);
        Files.createFile(standalone.resolve("build.gradle"));

        importer.setWorkspaceBound(root);
        Path gradleRoot = importer.getGradleRoot(standalone);
        Assertions.assertEquals(standalone, gradleRoot,
                "Should return project dir when no settings.gradle found");
    }

    @Test
    void testGetGradleRootStopsAtWorkspaceBound() throws IOException {
        // parent/
        //   settings.gradle  <-- should NOT be found (above workspace bound)
        //   workspace/       <-- workspace bound
        //     project/
        //       build.gradle
        Path parent = tempDir.resolve("parent");
        Path workspace = parent.resolve("workspace");
        Path project = workspace.resolve("project");
        Files.createDirectories(project);
        Files.createFile(parent.resolve("settings.gradle"));
        Files.createFile(project.resolve("build.gradle"));

        importer.setWorkspaceBound(workspace);
        Path gradleRoot = importer.getGradleRoot(project);
        // Should NOT traverse above workspace bound
        Assertions.assertNotEquals(parent, gradleRoot,
                "Should not traverse above workspace bound");
        Assertions.assertEquals(project, gradleRoot,
                "Should return project dir when settings.gradle is above workspace bound");
    }

    @Test
    void testGetGradleRootMultiLevelNesting() throws IOException {
        // workspace/
        //   settings.gradle
        //   moduleA/
        //     submodule/
        //       build.gradle
        Path root = tempDir.resolve("workspace");
        Path submodule = root.resolve("moduleA/submodule");
        Files.createDirectories(submodule);
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(submodule.resolve("build.gradle"));

        importer.setWorkspaceBound(root);
        Path gradleRoot = importer.getGradleRoot(submodule);
        Assertions.assertEquals(root, gradleRoot,
                "Should find settings.gradle 2 levels up");
    }

    @Test
    void testGetGradleRootWithSettingsAtMultipleLevels() throws IOException {
        // workspace/
        //   settings.gradle          <-- outermost (should win)
        //   moduleA/
        //     settings.gradle        <-- intermediate
        //     sub/
        //       build.gradle
        Path root = tempDir.resolve("workspace");
        Path moduleA = root.resolve("moduleA");
        Path sub = moduleA.resolve("sub");
        Files.createDirectories(sub);
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(moduleA.resolve("settings.gradle"));
        Files.createFile(sub.resolve("build.gradle"));

        importer.setWorkspaceBound(root);
        Path gradleRoot = importer.getGradleRoot(sub);
        // findGradleRoot picks the LAST (outermost) settings.gradle
        Assertions.assertEquals(root, gradleRoot,
                "Should pick the outermost settings.gradle within workspace bound");
    }

    // --- resolveClasspath: class-dir discovery ---

    @Test
    void testResolveClasspathFindsClassDirs() throws IOException {
        // Create a project with build/classes subdirectories
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("build/classes/java/main"));
        Files.createDirectories(project.resolve("build/classes/java/test"));
        Files.createDirectories(project.resolve("build/classes/groovy/main"));
        Files.createFile(project.resolve("build.gradle"));
        Files.createDirectories(project.resolve("src/main/java"));

        // resolveClasspath will try the Tooling API (which will fail without
        // a real Gradle installation), but it should still discover class dirs
        List<String> classpath = importer.resolveClasspath(project);

        // The classpath should contain the leaf class directories
        Assertions.assertTrue(classpath.stream().anyMatch(e -> e.contains("java") && e.contains("main")),
                "Should discover build/classes/java/main");
        Assertions.assertTrue(classpath.stream().anyMatch(e -> e.contains("java") && e.contains("test")),
                "Should discover build/classes/java/test");
        Assertions.assertTrue(classpath.stream().anyMatch(e -> e.contains("groovy") && e.contains("main")),
                "Should discover build/classes/groovy/main");
    }

    @Test
    void testResolveClasspathEmptyBuildDir() throws IOException {
        // Project with no build/ directory
        Path project = tempDir.resolve("nobuild");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createFile(project.resolve("build.gradle"));

        List<String> classpath = importer.resolveClasspath(project);
        // Should return an empty (or near-empty) list — no crash
        Assertions.assertNotNull(classpath);
    }

    // --- groupByGradleRoot: batch grouping ---

    @Test
    void testResolveClasspathsGroupsSubprojects() throws IOException {
        // workspace/
        //   settings.gradle
        //   build.gradle
        //   src/main/java/
        //   subA/
        //     build.gradle
        //     src/main/java/
        //   subB/
        //     build.gradle
        //     src/main/groovy/
        Path root = tempDir.resolve("workspace");
        Path subA = root.resolve("subA");
        Path subB = root.resolve("subB");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(subA.resolve("src/main/java"));
        Files.createDirectories(subB.resolve("src/main/groovy"));
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(root.resolve("build.gradle"));
        Files.createFile(subA.resolve("build.gradle"));
        Files.createFile(subB.resolve("build.gradle"));

        importer.setWorkspaceBound(root);

        // Verify that all 3 projects share the same gradle root
        Assertions.assertEquals(root, importer.getGradleRoot(root));
        Assertions.assertEquals(root, importer.getGradleRoot(subA));
        Assertions.assertEquals(root, importer.getGradleRoot(subB));
    }

    // --- setWorkspaceBound ---

    @Test
    void testSetWorkspaceBoundNull() throws IOException {
        // Should not throw
        importer.setWorkspaceBound(null);
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.createFile(project.resolve("build.gradle"));
        // getGradleRoot should still work (traverses up without bound)
        Path gradleRoot = importer.getGradleRoot(project);
        Assertions.assertNotNull(gradleRoot);
    }
}
