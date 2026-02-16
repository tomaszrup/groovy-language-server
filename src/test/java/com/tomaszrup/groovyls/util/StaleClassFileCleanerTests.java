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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("all")
class StaleClassFileCleanerTests {

    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("groovyls-stale-class-test");
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

    // --- cleanProject tests ---

    @Test
    void testCleanProjectDeletesStaleJavaClasses() throws IOException {
        Path project = tempDir.resolve("proj");
        Path srcDir = project.resolve("src/main/java/com/example");
        Path classDir = project.resolve("build/classes/java/main/com/example");
        Files.createDirectories(srcDir);
        Files.createDirectories(classDir);

        // Source that still exists
        Files.writeString(srcDir.resolve("Keeper.java"), "package com.example;");
        Files.writeString(classDir.resolve("Keeper.class"), "fake");

        // Stale class files (no source)
        Files.writeString(classDir.resolve("Deleted.class"), "fake");
        Files.writeString(classDir.resolve("Deleted$Inner.class"), "fake");

        StaleClassFileCleaner.cleanProject(project);

        Assertions.assertFalse(Files.exists(classDir.resolve("Deleted.class")));
        Assertions.assertFalse(Files.exists(classDir.resolve("Deleted$Inner.class")));
        Assertions.assertTrue(Files.exists(classDir.resolve("Keeper.class")));
    }

    @Test
    void testCleanProjectDeletesStaleGroovyClasses() throws IOException {
        Path project = tempDir.resolve("proj");
        Path srcDir = project.resolve("src/test/groovy/com/example");
        Path classDir = project.resolve("build/classes/groovy/test/com/example");
        Files.createDirectories(srcDir);
        Files.createDirectories(classDir);

        Files.writeString(srcDir.resolve("ValidSpec.groovy"), "package com.example");
        Files.writeString(classDir.resolve("ValidSpec.class"), "fake");
        Files.writeString(classDir.resolve("Removed.class"), "fake");

        StaleClassFileCleaner.cleanProject(project);

        Assertions.assertFalse(Files.exists(classDir.resolve("Removed.class")));
        Assertions.assertTrue(Files.exists(classDir.resolve("ValidSpec.class")));
    }

    @Test
    void testCleanProjectSkipsWhenNoSourceDir() throws IOException {
        Path project = tempDir.resolve("proj");
        Path classDir = project.resolve("build/classes/java/main/com/example");
        Files.createDirectories(classDir);
        Files.writeString(classDir.resolve("NoSource.class"), "fake");
        // No src/main/java exists

        StaleClassFileCleaner.cleanProject(project);

        Assertions.assertTrue(Files.exists(classDir.resolve("NoSource.class")),
                "Should not delete when source directory doesn't exist");
    }

    @Test
    void testCleanProjectNoBuildDir() {
        Path project = tempDir.resolve("no-build");
        // No build dir at all — should not throw
        Assertions.assertDoesNotThrow(() -> StaleClassFileCleaner.cleanProject(project));
    }

    // --- cleanClasspathEntries tests ---

    @Test
    void testCleanClasspathEntriesDeletesStaleClasses() throws IOException {
        Path project = tempDir.resolve("proj");
        Path srcDir = project.resolve("src/main/java/com/example");
        Path classDir = project.resolve("build/classes/java/main");
        Path classPackageDir = classDir.resolve("com/example");
        Files.createDirectories(srcDir);
        Files.createDirectories(classPackageDir);

        Files.writeString(srcDir.resolve("Keeper.java"), "package com.example;");
        Files.writeString(classPackageDir.resolve("Keeper.class"), "fake");
        Files.writeString(classPackageDir.resolve("Frame.class"), "fake");

        // Simulate cached classpath entry
        List<String> classpath = List.of(
                classDir.toString(),
                project.resolve("lib/some.jar").toString() // non-class-dir entry
        );

        StaleClassFileCleaner.cleanClasspathEntries(project, classpath);

        Assertions.assertFalse(Files.exists(classPackageDir.resolve("Frame.class")),
                "Frame.class should be deleted (no Frame.java source)");
        Assertions.assertTrue(Files.exists(classPackageDir.resolve("Keeper.class")),
                "Keeper.class should remain (Keeper.java exists)");
    }

    @Test
    void testCleanClasspathEntriesIgnoresNonClassDirs() throws IOException {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project);

        // Classpath entries that are NOT under build/classes/
        List<String> classpath = List.of(
                project.resolve("lib/dependency.jar").toString(),
                project.resolve("other/path").toString()
        );

        // Should not throw or do anything harmful
        Assertions.assertDoesNotThrow(() ->
                StaleClassFileCleaner.cleanClasspathEntries(project, classpath));
    }

    @Test
    void testCleanClasspathEntriesHandlesMultipleSourceSets() throws IOException {
        Path project = tempDir.resolve("proj");
        Path mainSrcDir = project.resolve("src/main/java/pkg");
        Path testSrcDir = project.resolve("src/test/java/pkg");
        Path mainClassDir = project.resolve("build/classes/java/main");
        Path testClassDir = project.resolve("build/classes/java/test");
        Files.createDirectories(mainSrcDir);
        Files.createDirectories(testSrcDir);
        Files.createDirectories(mainClassDir.resolve("pkg"));
        Files.createDirectories(testClassDir.resolve("pkg"));

        Files.writeString(mainSrcDir.resolve("Main.java"), "package pkg;");
        Files.writeString(mainClassDir.resolve("pkg/Main.class"), "fake");
        Files.writeString(mainClassDir.resolve("pkg/StaleMain.class"), "fake");

        Files.writeString(testSrcDir.resolve("Test.java"), "package pkg;");
        Files.writeString(testClassDir.resolve("pkg/Test.class"), "fake");
        Files.writeString(testClassDir.resolve("pkg/StaleTest.class"), "fake");

        List<String> classpath = List.of(
                mainClassDir.toString(),
                testClassDir.toString()
        );

        StaleClassFileCleaner.cleanClasspathEntries(project, classpath);

        Assertions.assertTrue(Files.exists(mainClassDir.resolve("pkg/Main.class")));
        Assertions.assertFalse(Files.exists(mainClassDir.resolve("pkg/StaleMain.class")));
        Assertions.assertTrue(Files.exists(testClassDir.resolve("pkg/Test.class")));
        Assertions.assertFalse(Files.exists(testClassDir.resolve("pkg/StaleTest.class")));
    }
}
