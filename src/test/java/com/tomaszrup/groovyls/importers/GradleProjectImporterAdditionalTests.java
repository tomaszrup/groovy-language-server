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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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

    @Test
    void testClaimsProjectAndBatchingFlags() throws IOException {
        Path project = tempDir.resolve("claims");
        Files.createDirectories(project);
        Files.createFile(project.resolve("build.gradle"));

        Assertions.assertTrue(importer.claimsProject(project));
        Assertions.assertFalse(importer.claimsProject(project.resolve("missing")));
        Assertions.assertFalse(importer.claimsProject(null));
        Assertions.assertTrue(importer.supportsSiblingBatching());
    }

    @Test
    void testGetBuildToolRootDelegatesToFindGradleRoot() throws IOException {
        Path root = tempDir.resolve("workspace");
        Path sub = root.resolve("sub");
        Files.createDirectories(sub);
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(sub.resolve("build.gradle"));

        importer.setWorkspaceBound(root);
        Assertions.assertEquals(root, importer.getBuildToolRoot(sub));
    }

    @Test
    void testFindProjectDirSeparatorUnixPath() throws Exception {
        Method method = GradleProjectImporter.class
                .getDeclaredMethod("findProjectDirSeparator", String.class);
        method.setAccessible(true);

        String sample;
        if (java.io.File.separatorChar == '\\') {
            sample = "C:/workspace/project:D:/repo/project/build/classes/java/main";
        } else {
            sample = "/workspace/project:/workspace/project/build/classes/java/main";
        }
        int idx = (int) method.invoke(importer, sample);
        Assertions.assertTrue(idx > 0);
        Assertions.assertEquals(':', sample.charAt(idx));
    }

    @Test
    void testNormaliseHelpers() throws Exception {
        Method normPath = GradleProjectImporter.class
                .getDeclaredMethod("normalise", Path.class);
        normPath.setAccessible(true);

        Method normString = GradleProjectImporter.class
                .getDeclaredMethod("normalise", String.class);
        normString.setAccessible(true);

        Path path = tempDir.resolve("A").resolve("..").resolve("B");
        String a = (String) normPath.invoke(importer, path);
        String b = (String) normString.invoke(importer, path.toString());

        Assertions.assertNotNull(a);
        Assertions.assertNotNull(b);
        Assertions.assertEquals(a, b);
        Assertions.assertEquals(a, a.toLowerCase());
        Assertions.assertFalse(a.contains("\\"));
    }

    @Test
    void testValidateGradleWrapperBranches() throws Exception {
        Method validate = GradleProjectImporter.class
                .getDeclaredMethod("validateGradleWrapper", Path.class);
        validate.setAccessible(true);

        // branch 1: no gradle-wrapper.properties
        Path noWrapper = tempDir.resolve("no-wrapper");
        Files.createDirectories(noWrapper);
        validate.invoke(importer, noWrapper);

        // branch 2: wrapper properties without checksum
        Path missingSha = tempDir.resolve("missing-sha");
        Path wrapperDir = missingSha.resolve("gradle/wrapper");
        Files.createDirectories(wrapperDir);
        Files.writeString(wrapperDir.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        validate.invoke(importer, missingSha);

        // branch 3: wrapper properties with checksum
        Path withSha = tempDir.resolve("with-sha");
        Path wrapperDir2 = withSha.resolve("gradle/wrapper");
        Files.createDirectories(wrapperDir2);
        Files.writeString(wrapperDir2.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n"
                        + "distributionSha256Sum=abc123\n");
        validate.invoke(importer, withSha);

        // branch 4: repeated validation hit cached path and returns quickly
        validate.invoke(importer, withSha);
    }

    @Test
    void testResolveClasspathsBatchPathReturnsEntriesForAllProjects() throws IOException {
        Path root = tempDir.resolve("batch-root");
        Path subA = root.resolve("subA");
        Path subB = root.resolve("subB");
        Files.createDirectories(subA.resolve("src/main/java"));
        Files.createDirectories(subB.resolve("src/main/groovy"));
        Files.createDirectories(subA.resolve("build/classes/java/main"));
        Files.createDirectories(subB.resolve("build/classes/groovy/main"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(subA.resolve("build.gradle"));
        Files.createFile(subB.resolve("build.gradle"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n"
                        + "distributionSha256Sum=abc123\n");

        importer.setWorkspaceBound(root);

        List<Path> projects = List.of(subA, subB);
        java.util.Map<Path, List<String>> result = importer.resolveClasspaths(projects);

        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.containsKey(subA));
        Assertions.assertTrue(result.containsKey(subB));
        // Even if tooling API fails in test env, class-dir discovery should still contribute.
        Assertions.assertTrue(result.get(subA).stream().anyMatch(s -> s.contains("build") && s.contains("classes")));
        Assertions.assertTrue(result.get(subB).stream().anyMatch(s -> s.contains("build") && s.contains("classes")));
    }

    @Test
    void testImportProjectsBatchPathReturnsMapForAllProjects() throws IOException {
        Path root = tempDir.resolve("import-root");
        Path subA = root.resolve("subA");
        Path subB = root.resolve("subB");
        Files.createDirectories(subA.resolve("src/main/java"));
        Files.createDirectories(subB.resolve("src/main/java"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(subA.resolve("build.gradle"));
        Files.createFile(subB.resolve("build.gradle"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");

        importer.setWorkspaceBound(root);

        java.util.Map<Path, List<String>> result = importer.importProjects(List.of(subA, subB));
        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.containsKey(subA));
        Assertions.assertTrue(result.containsKey(subB));
    }

    @Test
    void testRecompileAndDownloadSourcesAreBestEffort() throws IOException {
        Path root = tempDir.resolve("recompile-root");
        Path project = root.resolve("sub");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.createFile(root.resolve("settings.gradle"));
        Files.createFile(project.resolve("build.gradle"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");

        importer.setWorkspaceBound(root);

        Assertions.assertDoesNotThrow(() -> importer.recompile(project));
        Assertions.assertDoesNotThrow(() -> importer.downloadSourceJarsAsync(project));
    }
}
