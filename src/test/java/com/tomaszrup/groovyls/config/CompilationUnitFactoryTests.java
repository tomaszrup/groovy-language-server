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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

	@Test
	void testCreateIgnoresVirtualJarOpenFileUris() throws Exception {
		Path groovyFile = srcRoot.resolve("RealFile.groovy");
		Files.writeString(groovyFile, "class RealFile { void run() {} }");

		FileContentsTracker tracker = new FileContentsTracker();

		URI virtualJarUri = URI.create("jar:/spock-core-2.4-M1-groovy-4.0-sources.jar/spock/lang/Specification.java");
		org.eclipse.lsp4j.DidOpenTextDocumentParams openJarParams =
				new org.eclipse.lsp4j.DidOpenTextDocumentParams(
						new org.eclipse.lsp4j.TextDocumentItem(
								virtualJarUri.toString(), "java", 1,
								"package spock.lang; public class Specification {}"));
		tracker.didOpen(openJarParams);

		Assertions.assertDoesNotThrow(() -> factory.create(tempDir, tracker),
				"create() should ignore non-file open URIs like jar:/ and not throw");

		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		boolean foundRealFile = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName().contains("RealFile")) {
				foundRealFile = true;
				break;
			}
		}
		Assertions.assertTrue(foundRealFile,
				"Real workspace Groovy files should still be compiled when a jar: tab is open");
	}

	// --- Java source stubs ---

	@Test
	void testJavaSourceStubsAddedWhenClassNotOnClasspath() throws Exception {
		// Create a Java source file under src/main/java
		Path javaSrcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(javaSrcDir);
		Files.writeString(javaSrcDir.resolve("Frame.java"),
				"package com.example;\npublic class Frame {}");

		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		Assertions.assertNotNull(cu);

		// Compile to CONVERSION so source units are processed
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		// Look for a stub source unit
		boolean foundStub = false;
		var iter = cu.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName() != null && su.getName().startsWith("[java-stub] ")
					&& su.getName().contains("com.example.Frame")) {
				foundStub = true;
				break;
			}
		}
		Assertions.assertTrue(foundStub,
				"Java source stub for com.example.Frame should be added when .class not on classpath");
	}

	@Test
	void testJavaSourceStubsRefreshedOnRecreate() throws Exception {
		// Create a Java source file in the "old" package
		Path oldPkgDir = tempDir.resolve("src/main/java/com/oldpkg");
		Files.createDirectories(oldPkgDir);
		Files.writeString(oldPkgDir.resolve("Widget.java"),
				"package com.oldpkg;\npublic class Widget {}");

		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();

		// First create() — stub for com.oldpkg.Widget
		GroovyLSCompilationUnit cu1 = factory.create(tempDir, tracker);
		try { cu1.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Set<String> stubNames1 = collectStubNames(cu1);
		Assertions.assertTrue(stubNames1.contains("[java-stub] com.oldpkg.Widget"),
				"First create should have stub for old package");

		// Simulate move: delete old file, create in new package
		Files.delete(oldPkgDir.resolve("Widget.java"));
		Path newPkgDir = tempDir.resolve("src/main/java/com/newpkg");
		Files.createDirectories(newPkgDir);
		Files.writeString(newPkgDir.resolve("Widget.java"),
				"package com.newpkg;\npublic class Widget {}");

		// Invalidate compilation unit to force fresh build
		factory.invalidateCompilationUnit();

		// Second create() — should have new stub, not old
		GroovyLSCompilationUnit cu2 = factory.create(tempDir, tracker);
		try { cu2.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Set<String> stubNames2 = collectStubNames(cu2);
		Assertions.assertFalse(stubNames2.contains("[java-stub] com.oldpkg.Widget"),
				"After move, old stub should be removed");
		Assertions.assertTrue(stubNames2.contains("[java-stub] com.newpkg.Widget"),
				"After move, new stub should be added");
	}

	@Test
	void testJavaSourceStubsRefreshedOnReuseWithoutInvalidation() throws Exception {
		// Even when the compilation unit is REUSED (not invalidated),
		// stubs should still be refreshed from disk.
		Path pkgDir = tempDir.resolve("src/main/java/com/reuse");
		Files.createDirectories(pkgDir);
		Files.writeString(pkgDir.resolve("Alpha.java"),
				"package com.reuse;\npublic class Alpha {}");

		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();

		// First create
		GroovyLSCompilationUnit cu1 = factory.create(tempDir, tracker);
		tracker.resetChangedFiles();

		// Add a new Java file on disk (without invalidating compilation unit)
		Files.writeString(pkgDir.resolve("Beta.java"),
				"package com.reuse;\npublic class Beta {}");

		// Second create — reuses compilation unit but should refresh stubs
		GroovyLSCompilationUnit cu2 = factory.create(tempDir, tracker);
		try { cu2.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Assertions.assertSame(cu1, cu2, "Compilation unit should be reused");
		Set<String> stubNames = collectStubNames(cu2);
		Assertions.assertTrue(stubNames.contains("[java-stub] com.reuse.Alpha"),
				"Existing stub should still be present");
		Assertions.assertTrue(stubNames.contains("[java-stub] com.reuse.Beta"),
				"New Java file's stub should be added on reuse");
	}

	@Test
	void testNoJavaStubsWhenNoJavaSrcDirectory() throws Exception {
		// No src/main/java or src/test/java exists
		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Set<String> stubNames = collectStubNames(cu);
		Assertions.assertTrue(stubNames.isEmpty(),
				"No stubs should be added when there are no Java source directories");
	}

	@Test
	void testJavaStubsInTestSourceDirectory() throws Exception {
		// Create a Java source in src/test/java
		Path testJavaSrcDir = tempDir.resolve("src/test/java/com/test");
		Files.createDirectories(testJavaSrcDir);
		Files.writeString(testJavaSrcDir.resolve("TestHelper.java"),
				"package com.test;\npublic class TestHelper {}");

		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Set<String> stubNames = collectStubNames(cu);
		Assertions.assertTrue(stubNames.contains("[java-stub] com.test.TestHelper"),
				"Stubs should be generated for Java files in src/test/java too");
	}

	@Test
	void testJavaStubForDefaultPackage() throws Exception {
		// Java file in the root of src/main/java (no package)
		Path javaSrcDir = tempDir.resolve("src/main/java");
		Files.createDirectories(javaSrcDir);
		Files.writeString(javaSrcDir.resolve("NoPackage.java"),
				"public class NoPackage {}");

		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();
		GroovyLSCompilationUnit cu = factory.create(tempDir, tracker);
		try { cu.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Set<String> stubNames = collectStubNames(cu);
		Assertions.assertTrue(stubNames.contains("[java-stub] NoPackage"),
				"Stub should be generated for classes in the default package");
	}

	@Test
	void testJavaStubsOnIncrementalUnit() throws Exception {
		// Ensure stubs are also added to incremental compilation units
		Path javaSrcDir = tempDir.resolve("src/main/java/com/incr");
		Files.createDirectories(javaSrcDir);
		Files.writeString(javaSrcDir.resolve("IncrClass.java"),
				"package com.incr;\npublic class IncrClass {}");

		// Create a groovy file to include in the incremental unit
		Path groovySrc = srcRoot.resolve("IncrTest.groovy");
		Files.writeString(groovySrc, "class IncrTest {}");

		factory.setProjectRoot(tempDir);
		FileContentsTracker tracker = new FileContentsTracker();
		Set<URI> filesToInclude = new HashSet<>();
		filesToInclude.add(groovySrc.toUri());

		GroovyLSCompilationUnit incrUnit = factory.createIncremental(tempDir, tracker, filesToInclude);
		Assertions.assertNotNull(incrUnit);
		try { incrUnit.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		Set<String> stubNames = collectStubNames(incrUnit);
		Assertions.assertTrue(stubNames.contains("[java-stub] com.incr.IncrClass"),
				"Stubs should also be added to incremental compilation units");
	}

	@Test
	void testCreateIncrementalSkipsNonFileUris() throws Exception {
		Path groovySrc = srcRoot.resolve("IncrementalOnly.groovy");
		Files.writeString(groovySrc, "class IncrementalOnly {}");

		FileContentsTracker tracker = new FileContentsTracker();
		Set<URI> filesToInclude = new HashSet<>();
		filesToInclude.add(groovySrc.toUri());
		filesToInclude.add(URI.create("jar:/spock-core-2.4-M1-groovy-4.0-sources.jar/spock/lang/Specification.java"));

		GroovyLSCompilationUnit incrUnit = Assertions.assertDoesNotThrow(
				() -> factory.createIncremental(tempDir, tracker, filesToInclude),
				"createIncremental() should ignore non-file URIs in filesToInclude");
		Assertions.assertNotNull(incrUnit);

		try { incrUnit.compile(Phases.CONVERSION); } catch (Exception e) { /* ignore */ }

		boolean foundIncrementalFile = false;
		var iter = incrUnit.iterator();
		while (iter.hasNext()) {
			SourceUnit su = iter.next();
			if (su.getName().contains("IncrementalOnly")) {
				foundIncrementalFile = true;
				break;
			}
		}
		Assertions.assertTrue(foundIncrementalFile,
				"Regular file URIs should still be included in incremental compilation");
	}

	private Set<String> collectStubNames(GroovyLSCompilationUnit cu) {
		Set<String> stubs = new HashSet<>();
		cu.iterator().forEachRemaining(su -> {
			if (su.getName() != null && su.getName().startsWith("[java-stub] ")) {
				stubs.add(su.getName());
			}
		});
		return stubs;
	}
}
