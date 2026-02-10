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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for JavaSourceLocator — verifies FQCN-to-source-file mapping,
 * class/method/constructor/field location finding, and edge cases.
 */
class JavaSourceLocatorTests {

	@TempDir
	Path tempProjectRoot;

	private JavaSourceLocator locator;

	@BeforeEach
	void setup() {
		locator = new JavaSourceLocator();
	}

	@AfterEach
	void tearDown() {
		locator = null;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Path createJavaSource(String relativePath, String content) throws IOException {
		Path file = tempProjectRoot.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

	// ------------------------------------------------------------------
	// Indexing and basic lookup
	// ------------------------------------------------------------------

	@Test
	void testFindSourceURIForIndexedClass() throws Exception {
		createJavaSource("src/main/java/com/example/Foo.java",
				"package com.example;\npublic class Foo {\n}\n");

		locator.addProjectRoot(tempProjectRoot);

		URI uri = locator.findSourceURI("com.example.Foo");
		Assertions.assertNotNull(uri, "Should find source URI for indexed class");
		Assertions.assertTrue(uri.toString().endsWith("Foo.java"), "URI should point to Foo.java");
	}

	@Test
	void testFindSourceURIReturnsNullForUnknownClass() {
		locator.addProjectRoot(tempProjectRoot);
		URI uri = locator.findSourceURI("com.nonexistent.Bar");
		Assertions.assertNull(uri, "Should return null for unknown class");
	}

	@Test
	void testMultipleSourceDirectories() throws Exception {
		createJavaSource("src/main/java/com/example/Main.java",
				"package com.example;\npublic class Main {}\n");
		createJavaSource("src/test/java/com/example/MainTest.java",
				"package com.example;\npublic class MainTest {}\n");

		locator.addProjectRoot(tempProjectRoot);

		Assertions.assertNotNull(locator.findSourceURI("com.example.Main"));
		Assertions.assertNotNull(locator.findSourceURI("com.example.MainTest"));
	}

	// ------------------------------------------------------------------
	// Class location
	// ------------------------------------------------------------------

	@Test
	void testFindLocationForClassDeclaration() throws Exception {
		String source = "package com.example;\n\npublic class Widget {\n    int x;\n}\n";
		createJavaSource("src/main/java/com/example/Widget.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForClass("com.example.Widget");
		Assertions.assertNotNull(loc, "Should find class location");
		Position start = loc.getRange().getStart();
		// "public class Widget" is on line 2 (0-indexed)
		Assertions.assertEquals(2, start.getLine(), "Class declaration should be on line 2");
	}

	@Test
	void testFindLocationForClassSkipsComments() throws Exception {
		String source = "package com.example;\n"
				+ "/**\n * This is a Javadoc.\n * class Decoy\n */\n"
				+ "public class RealClass {\n}\n";
		createJavaSource("src/main/java/com/example/RealClass.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForClass("com.example.RealClass");
		Assertions.assertNotNull(loc);
		// The actual class declaration is after the comment block
		Assertions.assertEquals(5, loc.getRange().getStart().getLine(),
				"Should skip comment and find real class declaration");
	}

	@Test
	void testFindLocationForInterface() throws Exception {
		String source = "package com.example;\n\npublic interface MyService {\n    void process();\n}\n";
		createJavaSource("src/main/java/com/example/MyService.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForClass("com.example.MyService");
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(2, loc.getRange().getStart().getLine());
	}

	@Test
	void testFindLocationForEnum() throws Exception {
		String source = "package com.example;\n\npublic enum Color {\n    RED, GREEN, BLUE\n}\n";
		createJavaSource("src/main/java/com/example/Color.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForClass("com.example.Color");
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(2, loc.getRange().getStart().getLine());
	}

	// ------------------------------------------------------------------
	// Method location
	// ------------------------------------------------------------------

	@Test
	void testFindLocationForMethod() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Calculator {\n"
				+ "    public int add(int a, int b) {\n"
				+ "        return a + b;\n"
				+ "    }\n"
				+ "    public int subtract(int a, int b) {\n"
				+ "        return a - b;\n"
				+ "    }\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Calculator.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForMethod("com.example.Calculator", "subtract", 2);
		Assertions.assertNotNull(loc, "Should find method location");
		Assertions.assertEquals(6, loc.getRange().getStart().getLine(),
				"'subtract' should be on line 6");
	}

	@Test
	void testFindLocationForMethodAnyOverload() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Overloaded {\n"
				+ "    public void doWork() {}\n"
				+ "    public void doWork(String arg) {}\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Overloaded.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForMethod("com.example.Overloaded", "doWork", -1);
		Assertions.assertNotNull(loc, "Should find first overload with paramCount=-1");
		Assertions.assertEquals(3, loc.getRange().getStart().getLine());
	}

	@Test
	void testFindLocationForMethodWithParamCount() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Multi {\n"
				+ "    public void process() {}\n"      // line 3
				+ "    public void process(int x) {}\n"  // line 4
				+ "    public void process(int x, int y) {}\n" // line 5
				+ "}\n";
		createJavaSource("src/main/java/com/example/Multi.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForMethod("com.example.Multi", "process", 2);
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(5, loc.getRange().getStart().getLine(),
				"Should find 2-arg overload on line 5");
	}

	// ------------------------------------------------------------------
	// Constructor location
	// ------------------------------------------------------------------

	@Test
	void testFindLocationForConstructor() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Person {\n"
				+ "    private String name;\n"
				+ "    public Person(String name) {\n"
				+ "        this.name = name;\n"
				+ "    }\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Person.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForConstructor("com.example.Person", 1);
		Assertions.assertNotNull(loc, "Should find constructor location");
		Assertions.assertEquals(4, loc.getRange().getStart().getLine());
	}

	@Test
	void testFindLocationForNoArgConstructor() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Empty {\n"
				+ "    public Empty() {}\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Empty.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForConstructor("com.example.Empty", 0);
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(3, loc.getRange().getStart().getLine());
	}

	// ------------------------------------------------------------------
	// Field location
	// ------------------------------------------------------------------

	@Test
	void testFindLocationForField() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Config {\n"
				+ "    private static final String DEFAULT_NAME = \"test\";\n"
				+ "    private int count;\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Config.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForField("com.example.Config", "count");
		Assertions.assertNotNull(loc, "Should find field location");
		Assertions.assertEquals(4, loc.getRange().getStart().getLine());
	}

	@Test
	void testFindLocationForStaticField() throws Exception {
		String source = "package com.example;\n\n"
				+ "public class Constants {\n"
				+ "    public static final int MAX_SIZE = 100;\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Constants.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForField("com.example.Constants", "MAX_SIZE");
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(3, loc.getRange().getStart().getLine());
	}

	// ------------------------------------------------------------------
	// Refresh
	// ------------------------------------------------------------------

	@Test
	void testRefreshPicksUpNewFiles() throws Exception {
		locator.addProjectRoot(tempProjectRoot);
		Assertions.assertNull(locator.findSourceURI("com.example.Late"));

		createJavaSource("src/main/java/com/example/Late.java",
				"package com.example;\npublic class Late {}\n");
		locator.refresh();

		Assertions.assertNotNull(locator.findSourceURI("com.example.Late"),
				"After refresh, should find newly added class");
	}

	@Test
	void testRefreshRemovesDeletedFiles() throws Exception {
		Path file = createJavaSource("src/main/java/com/example/Temp.java",
				"package com.example;\npublic class Temp {}\n");
		locator.addProjectRoot(tempProjectRoot);
		Assertions.assertNotNull(locator.findSourceURI("com.example.Temp"));

		Files.delete(file);
		locator.refresh();

		Assertions.assertNull(locator.findSourceURI("com.example.Temp"),
				"After refresh with deleted file, should return null");
	}

	// ------------------------------------------------------------------
	// Edge cases
	// ------------------------------------------------------------------

	@Test
	void testEmptyProjectRoot() {
		locator.addProjectRoot(tempProjectRoot);
		Assertions.assertNull(locator.findSourceURI("anything.at.all"));
	}

	@Test
	void testFindLocationForUnknownClassReturnsNull() {
		locator.addProjectRoot(tempProjectRoot);
		Location loc = locator.findLocationForClass("com.unknown.Missing");
		Assertions.assertNull(loc);
	}

	@Test
	void testFindLocationForMethodInUnknownClassReturnsNull() {
		locator.addProjectRoot(tempProjectRoot);
		Location loc = locator.findLocationForMethod("com.unknown.Missing", "doStuff", -1);
		Assertions.assertNull(loc);
	}

	@Test
	void testFindLocationForFieldInUnknownClassReturnsNull() {
		locator.addProjectRoot(tempProjectRoot);
		Location loc = locator.findLocationForField("com.unknown.Missing", "field");
		Assertions.assertNull(loc);
	}

	@Test
	void testClassWithSingleLineCommentAbove() throws Exception {
		String source = "package com.example;\n"
				+ "// class FakeDecoy\n"
				+ "public class Actual {\n"
				+ "}\n";
		createJavaSource("src/main/java/com/example/Actual.java", source);
		locator.addProjectRoot(tempProjectRoot);

		Location loc = locator.findLocationForClass("com.example.Actual");
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(2, loc.getRange().getStart().getLine(),
				"Should skip single-line comment and find real class");
	}

	@Test
	void testDefaultPackageClass() throws Exception {
		String source = "public class TopLevel {\n}\n";
		createJavaSource("src/main/java/TopLevel.java", source);
		locator.addProjectRoot(tempProjectRoot);

		URI uri = locator.findSourceURI("TopLevel");
		Assertions.assertNotNull(uri, "Should find default-package class");
	}

	// ------------------------------------------------------------------
	// Stub vs real source: race condition regression tests
	// ------------------------------------------------------------------

	/**
	 * Helper to create a source JAR containing a single Java source entry.
	 */
	private Path createSourceJar(String jarName, String entryName, String sourceContent) throws Exception {
		Path jarPath = tempProjectRoot.resolve(jarName);
		Files.createDirectories(jarPath.getParent());
		try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
				java.nio.file.Files.newOutputStream(jarPath))) {
			jos.putNextEntry(new java.util.jar.JarEntry(entryName));
			jos.write(sourceContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		return jarPath;
	}

	/**
	 * Helper to create a regular (non-source) JAR so that findSourceJar()
	 * can locate the sibling -sources.jar by naming convention.
	 */
	private Path createDummyJar(String jarName) throws Exception {
		Path jarPath = tempProjectRoot.resolve(jarName);
		Files.createDirectories(jarPath.getParent());
		try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
				java.nio.file.Files.newOutputStream(jarPath))) {
			jos.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
			jos.write("Manifest-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		return jarPath;
	}

	@Test
	void testRegisterDecompiledContentDoesNotOverwriteRealSource() throws Exception {
		// Set up: a classpath JAR has a sibling -sources.jar with real source
		String realSource = "package com.example;\n\npublic class Lib {\n    public void doWork() {}\n}\n";
		Path dummyJar = createDummyJar("libs/mylib-1.0.jar");
		createSourceJar("libs/mylib-1.0-sources.jar", "com/example/Lib.java", realSource);

		// Index the source JARs via addClasspathJars
		locator.addClasspathJars(java.util.Collections.singletonList(dummyJar.toString()));
		Assertions.assertTrue(locator.hasSource("com.example.Lib"),
				"Source JAR should be indexed for com.example.Lib");

		// Now simulate the decompilation fallback trying to register a stub
		java.util.List<String> stubLines = java.util.Arrays.asList(
				"// Decompiled from bytecode — source not available",
				"",
				"package com.example;",
				"",
				"public class Lib {",
				"    public void doWork() { }",
				"}");
		URI resultURI = locator.registerDecompiledContent("com.example.Lib", stubLines);

		// The cached content should be the REAL source, not the stub
		String content = locator.getDecompiledContent("com.example.Lib");
		Assertions.assertNotNull(content, "Content should be cached");
		Assertions.assertFalse(content.contains("Decompiled from bytecode"),
				"Should serve real source, not decompiled stub");
		Assertions.assertTrue(content.contains("public void doWork()"),
				"Should contain real source content");
	}

	@Test
	void testGetDecompiledContentByURIRefreshesStaleStub() throws Exception {
		// Step 1: Register a decompiled stub (simulating the race: definition
		// requested BEFORE classpath is resolved)
		java.util.List<String> stubLines = java.util.Arrays.asList(
				"// Decompiled from bytecode — source not available",
				"",
				"package com.dep;",
				"",
				"public class Service {",
				"    public void serve() { }",
				"}");
		URI stubURI = locator.registerDecompiledContent("com.dep.Service", stubLines);

		// Verify the stub is cached
		String initialContent = locator.getDecompiledContentByURI(stubURI.toString());
		Assertions.assertNotNull(initialContent);
		Assertions.assertTrue(initialContent.contains("Decompiled from bytecode"));

		// Step 2: Now classpath resolves and source JAR is indexed
		String realSource = "package com.dep;\n\n/**\n * Real implementation.\n */\npublic class Service {\n    public void serve() {\n        System.out.println(\"serving\");\n    }\n}\n";
		Path dummyJar = createDummyJar("cache/service-2.0.jar");
		createSourceJar("cache/service-2.0-sources.jar", "com/dep/Service.java", realSource);
		locator.addClasspathJars(java.util.Collections.singletonList(dummyJar.toString()));

		// Step 3: Next time content is requested by URI, it should serve real source
		String refreshedContent = locator.getDecompiledContentByURI(stubURI.toString());
		Assertions.assertNotNull(refreshedContent);
		Assertions.assertFalse(refreshedContent.contains("Decompiled from bytecode"),
				"Should serve real source after classpath resolves, not stale stub");
		Assertions.assertTrue(refreshedContent.contains("Real implementation"),
				"Should contain content from -sources.jar");
	}

	@Test
	void testAddClasspathJarsEvictsStaleStubs() throws Exception {
		// Pre-populate the cache with a decompiled stub
		java.util.List<String> stubLines = java.util.Arrays.asList(
				"// Decompiled from bytecode — source not available",
				"",
				"package org.lib;",
				"",
				"public class Helper {",
				"}");
		locator.registerDecompiledContent("org.lib.Helper", stubLines);

		String beforeContent = locator.getDecompiledContent("org.lib.Helper");
		Assertions.assertTrue(beforeContent.contains("Decompiled from bytecode"),
				"Before: should have stub content");

		// Index source JARs that cover the same class
		String realSource = "package org.lib;\n\npublic class Helper {\n    public static void help() {}\n}\n";
		Path dummyJar = createDummyJar("repo/helper-3.0.jar");
		createSourceJar("repo/helper-3.0-sources.jar", "org/lib/Helper.java", realSource);
		locator.addClasspathJars(java.util.Collections.singletonList(dummyJar.toString()));

		// The stale stub should have been evicted
		String afterContent = locator.getDecompiledContent("org.lib.Helper");
		// After eviction, getDecompiledContent returns null (cache entry removed)
		// OR on next resolveSource call it will be re-populated with real source
		Assertions.assertTrue(afterContent == null || !afterContent.contains("Decompiled from bytecode"),
				"After source JAR indexing, stub should be evicted from cache");
	}

	@Test
	void testFindLocationForClassPrefersSourceJarOverStub() throws Exception {
		// Register stub first (simulating race)
		java.util.List<String> stubLines = java.util.Arrays.asList(
				"// Decompiled from bytecode — source not available",
				"",
				"package com.api;",
				"",
				"public class Client {",
				"}");
		locator.registerDecompiledContent("com.api.Client", stubLines);

		// Now index source JARs
		String realSource = "package com.api;\n\n/** HTTP client. */\npublic class Client {\n    public void get(String url) {}\n}\n";
		Path dummyJar = createDummyJar("deps/api-1.0.jar");
		createSourceJar("deps/api-1.0-sources.jar", "com/api/Client.java", realSource);
		locator.addClasspathJars(java.util.Collections.singletonList(dummyJar.toString()));

		// findLocationForClass should use source JAR, not stub
		Location loc = locator.findLocationForClass("com.api.Client");
		Assertions.assertNotNull(loc, "Should find location from source JAR");
		// The class declaration "public class Client" is on line 3 (0-indexed)
		Assertions.assertEquals(3, loc.getRange().getStart().getLine(),
				"Should point to real class declaration in source JAR");
	}
}
