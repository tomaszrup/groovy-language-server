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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link JavaSourceAwareClassLoader}.
 */
class JavaSourceAwareClassLoaderTests {

    @TempDir
    Path tempDir;

    @Test
    void testResolveClassFromMainJavaSource() throws Exception {
        // Create src/main/java/com/example/Frame.java
        Path sourceDir = tempDir.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Frame.java"),
                "package com.example;\npublic class Frame {}");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Class<?> clazz = cl.loadClass("com.example.Frame");
        assertNotNull(clazz);
        assertEquals("com.example.Frame", clazz.getName());
    }

    @Test
    void testResolveClassFromTestJavaSource() throws Exception {
        // Create src/test/java/com/example/FrameTest.java
        Path sourceDir = tempDir.resolve("src").resolve("test").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("FrameTest.java"),
                "package com.example;\npublic class FrameTest {}");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Class<?> clazz = cl.loadClass("com.example.FrameTest");
        assertNotNull(clazz);
        assertEquals("com.example.FrameTest", clazz.getName());
    }

    @Test
    void testInnerClassResolvesViaOuterSource() throws Exception {
        // Create src/main/java/com/example/Outer.java
        Path sourceDir = tempDir.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Outer.java"),
                "package com.example;\npublic class Outer { public static class Inner {} }");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Class<?> clazz = cl.loadClass("com.example.Outer$Inner");
        assertNotNull(clazz);
        assertEquals("com.example.Outer$Inner", clazz.getName());
    }

    @Test
    void testClassNotFoundWhenNoSource() {
        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        assertThrows(ClassNotFoundException.class, () -> cl.loadClass("com.example.DoesNotExist"));
    }

    @Test
    void testGetDiscoveredClasses() throws IOException {
        Path mainDir = tempDir.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(mainDir);
        Files.writeString(mainDir.resolve("Foo.java"), "package com.example;\npublic class Foo {}");
        Files.writeString(mainDir.resolve("Bar.java"), "package com.example;\npublic class Bar {}");

        Path testDir = tempDir.resolve("src").resolve("test").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("FooTest.java"), "package com.example;\npublic class FooTest {}");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Set<String> discovered = cl.getDiscoveredClasses();
        assertTrue(discovered.contains("com.example.Foo"));
        assertTrue(discovered.contains("com.example.Bar"));
        assertTrue(discovered.contains("com.example.FooTest"));
        assertEquals(3, discovered.size());
    }

    @Test
    void testInvalidateIndexPicksUpNewFiles() throws Exception {
        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        // Initially no sources
        assertTrue(cl.getDiscoveredClasses().isEmpty());

        // Create a source file
        Path sourceDir = tempDir.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("NewClass.java"),
                "package com.example;\npublic class NewClass {}");

        // Still empty because index is cached
        assertTrue(cl.getDiscoveredClasses().isEmpty());

        // Invalidate and check again
        cl.invalidateIndex();
        Set<String> discovered = cl.getDiscoveredClasses();
        assertTrue(discovered.contains("com.example.NewClass"));
    }

    @Test
    void testStubClassIsPublic() throws Exception {
        Path sourceDir = tempDir.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("MyClass.java"),
                "package com.example;\npublic class MyClass {}");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Class<?> clazz = cl.loadClass("com.example.MyClass");
        assertTrue(java.lang.reflect.Modifier.isPublic(clazz.getModifiers()));
        assertEquals(Object.class, clazz.getSuperclass());
    }

    @Test
    void testSameClassReturnedOnMultipleLoads() throws Exception {
        Path sourceDir = tempDir.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("example");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Singleton.java"),
                "package com.example;\npublic class Singleton {}");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Class<?> first = cl.loadClass("com.example.Singleton");
        Class<?> second = cl.loadClass("com.example.Singleton");
        assertSame(first, second);
    }

    @Test
    void testNoSourceDirsDoesNotFail() {
        // tempDir has no src/ directory at all
        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        assertTrue(cl.getDiscoveredClasses().isEmpty());
        assertThrows(ClassNotFoundException.class, () -> cl.loadClass("com.example.Missing"));
    }

    @Test
    void testDefaultPackageClass() throws Exception {
        Path sourceDir = tempDir.resolve("src").resolve("main").resolve("java");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("TopLevel.java"),
                "public class TopLevel {}");

        JavaSourceAwareClassLoader cl = new JavaSourceAwareClassLoader(
                ClassLoader.getSystemClassLoader().getParent(), tempDir);

        Class<?> clazz = cl.loadClass("TopLevel");
        assertNotNull(clazz);
        assertEquals("TopLevel", clazz.getName());
    }
}
