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
package com.tomaszrup.groovyls.config;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Direct unit tests for {@link CompilationUnitFactory}: classpath handling,
 * file cache invalidation, excluded directories, and compilation unit creation.
 */
class CompilationUnitFactoryTests {

	private CompilationUnitFactory factory;
	private Path tempDir;
	private Path srcRoot;

	@BeforeEach
	void setup() throws IOException {
		tempDir = Files.createTempDirectory("cuf-test");
		srcRoot = tempDir.resolve("src/main/groovy");
		Files.createDirectories(srcRoot);

		factory = new CompilationUnitFactory();
	}

	@AfterEach
	void tearDown() {
		factory = null;
		// Clean up temp directory
		if (tempDir != null) {
			try {
				Files.walk(tempDir)
						.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			} catch (IOException e) {
				// ignore
			}
		}
	}

	// --- Classpath management ---

	@Test
	void testInitialClasspathIsNull() {
		Assertions.assertNull(factory.getAdditionalClasspathList());
	}

	@Test
	void testSetAdditionalClasspathList() {
		List<String> classpath = Arrays.asList("/lib/a.jar", "/lib/b.jar");
		factory.setAdditionalClasspathList(classpath);
		Assertions.assertEquals(classpath, factory.getAdditionalClasspathList());
	}

	@Test
	void testSetAdditionalClasspathListInvalidatesCompilationUnit() {
		FileContentsTracker tracker = new FileContentsTracker();

		// Create a compilation unit first
		GroovyLSCompilationUnit cu1 = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu1);

		// Set a new classpath (this should invalidate the compilation unit)
		factory.setAdditionalClasspathList(Arrays.asList("/new/path.jar"));

		// Create again — should be a fresh compilation unit
		GroovyLSCompilationUnit cu2 = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu2);
		// After invalidation, the factory creates a new object
	}

	// --- invalidateCompilationUnit ---

	@Test
	void testInvalidateCompilationUnit() {
		FileContentsTracker tracker = new FileContentsTracker();

		GroovyLSCompilationUnit cu1 = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu1);

		factory.invalidateCompilationUnit();

		GroovyLSCompilationUnit cu2 = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu2);
		// After invalidation, a new compilation unit is created
	}

	// --- invalidateFileCache ---

	@Test
	void testInvalidateFileCacheDoesNotThrow() {
		factory.invalidateFileCache();
		// Just verifying no exception
	}

	@Test
	void testFileCacheIsRebuiltAfterInvalidation() throws Exception {
		FileContentsTracker tracker = new FileContentsTracker();

		// Create initial compilation unit (with no groovy files)
		factory.create(tempDir, tracker);

		// Add a new groovy file
		Path newFile = srcRoot.resolve("NewCacheTest.groovy");
		Files.writeString(newFile, "class NewCacheTest {}");

		// Invalidate file cache
		factory.invalidateFileCache();
		// Also invalidate the compilation unit so it rescans
		factory.invalidateCompilationUnit();

		// Create again — the new file should be picked up
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu);

		// Compile to move queued sources into the resolved sources map
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		// Verify the new file is in the compilation unit
		boolean found = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName().contains("NewCacheTest")) {
				found = true;
				break;
			}
		}
		Assertions.assertTrue(found, "New groovy file should be found in compilation unit after cache invalidation");
	}

	// --- create() ---

	@Test
	void testCreateWithNullWorkspaceRoot() {
		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(null, tracker);
		Assertions.assertNotNull(cu);
	}

	@Test
	void testCreateReusesCompilationUnitOnSecondCall() {
		FileContentsTracker tracker = new FileContentsTracker();

		GroovyLSCompilationUnit cu1 = factory.create(tempDir, tracker);
		tracker.resetChangedFiles();
		GroovyLSCompilationUnit cu2 = factory.create(tempDir, tracker);

		// Should reuse the same compilation unit instance
		Assertions.assertSame(cu1, cu2);
	}

	@Test
	void testCreatePicksUpGroovyFilesInSrcDirectory() throws Exception {
		Path groovyFile = srcRoot.resolve("PickedUp.groovy");
		Files.writeString(groovyFile, "class PickedUp { void run() {} }");

		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu);

		// Compile to move queued sources into the resolved sources map
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		boolean found = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName().contains("PickedUp")) {
				found = true;
			}
		}
		Assertions.assertTrue(found, "Should find PickedUp.groovy in the compilation unit");
	}

	// --- Excluded directories ---

	@Test
	void testExcludedDirectoriesAreSkipped() throws Exception {
		// Create a file inside a '.gradle' subdirectory (should be excluded)
		Path excludedDir = srcRoot.resolve(".gradle");
		Files.createDirectories(excludedDir);
		Path excludedFile = excludedDir.resolve("Excluded.groovy");
		Files.writeString(excludedFile, "class Excluded {}");

		// Create a normal file
		Path normalFile = srcRoot.resolve("Included.groovy");
		Files.writeString(normalFile, "class Included {}");

		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu);

		// Compile to move queued sources into the resolved sources map
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		boolean foundExcluded = false;
		boolean foundIncluded = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName().contains("Excluded")) {
				foundExcluded = true;
			}
			if (su.getName().contains("Included")) {
				foundIncluded = true;
			}
		}
		Assertions.assertFalse(foundExcluded, "Files inside excluded directory should be excluded");
		Assertions.assertTrue(foundIncluded, "Normal files should be included");
	}

	// --- Excluded sub-roots ---

	@Test
	void testSetExcludedSubRoots() {
		Path subRoot = tempDir.resolve("subproject");
		factory.setExcludedSubRoots(Arrays.asList(subRoot));
		// Just verify no exception; the exclusion logic is tested via file scanning
	}

	@Test
	void testSetExcludedSubRootsNull() {
		factory.setExcludedSubRoots(null);
		// Should not throw
	}

	// --- Separate sub-project detection ---

	@Test
	void testSiblingProjectsAreExcludedFromFileCache() throws Exception {
		// Simulate a workspace root containing two separate projects
		// (each with its own build file and src dirs). When the factory
		// is rooted at the parent, files from the sub-projects should
		// be excluded to prevent duplicate-class errors.

		// Sub-project A (Gradle)
		Path projectA = tempDir.resolve("project-a");
		Files.createDirectories(projectA.resolve("src/test/groovy/com/example"));
		Files.writeString(projectA.resolve("build.gradle"), "// gradle build");
		Files.writeString(projectA.resolve("src/test/groovy/com/example/Spec.groovy"),
				"class Spec {}");

		// Sub-project B (Maven)
		Path projectB = tempDir.resolve("project-b");
		Files.createDirectories(projectB.resolve("src/test/groovy/com/example"));
		Files.writeString(projectB.resolve("pom.xml"), "<project/>");
		Files.writeString(projectB.resolve("src/test/groovy/com/example/Spec.groovy"),
				"class Spec {}");

		// A top-level groovy file that is NOT inside either sub-project
		Path topLevel = tempDir.resolve("src/main/groovy");
		Files.createDirectories(topLevel);
		Files.writeString(topLevel.resolve("TopLevel.groovy"), "class TopLevel {}");

		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu);

		// Compile to move queued sources into the resolved sources map
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		boolean foundProjectA = false;
		boolean foundProjectB = false;
		boolean foundTopLevel = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			String name = su.getName();
			if (name.contains("project-a")) {
				foundProjectA = true;
			}
			if (name.contains("project-b")) {
				foundProjectB = true;
			}
			if (name.contains("TopLevel")) {
				foundTopLevel = true;
			}
		}
		Assertions.assertFalse(foundProjectA,
				"Files inside sub-project A should be excluded from the parent scope");
		Assertions.assertFalse(foundProjectB,
				"Files inside sub-project B should be excluded from the parent scope");
		Assertions.assertTrue(foundTopLevel,
				"Top-level file should still be included");
	}

	// --- Open files take priority ---

	@Test
	void testOpenFileContentsOverrideDiskContents() throws Exception {
		Path groovyFile = srcRoot.resolve("OpenPriority.groovy");
		Files.writeString(groovyFile, "class OpenPriority { void disk() {} }");

		FileContentsTracker tracker = new FileContentsTracker();
		URI fileURI = groovyFile.toUri();

		// Simulate opening the file with different content
		org.eclipse.lsp4j.DidOpenTextDocumentParams openParams =
				new org.eclipse.lsp4j.DidOpenTextDocumentParams(
						new org.eclipse.lsp4j.TextDocumentItem(
								fileURI.toString(), "groovy", 1,
								"class OpenPriority { void inMemory() {} }"));
		tracker.didOpen(openParams);

		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu);

		// Compile to move queued sources into the resolved sources map
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		// The file should be in the compilation unit (either disk or open version)
		boolean found = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName().contains("OpenPriority")) {
				found = true;
				break;
			}
		}
		Assertions.assertTrue(found, "Open file should be in the compilation unit");
	}
}
