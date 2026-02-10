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
package com.tomaszrup.groovyls;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Unit tests for {@link CompilationService}: compilation unit management,
 * full/incremental compilation, AST visiting, dependency graph updates,
 * placeholder injection, and changed-file tracking.
 */
class CompilationServiceTests {

	private CompilationService compilationService;
	private FileContentsTracker fileContentsTracker;
	private Path tempDir;
	private Path srcDir;

	@BeforeEach
	void setup() throws IOException {
		fileContentsTracker = new FileContentsTracker();
		compilationService = new CompilationService(fileContentsTracker);

		tempDir = Files.createTempDirectory("cs-test");
		srcDir = tempDir.resolve("src/main/groovy");
		Files.createDirectories(srcDir);
	}

	@AfterEach
	void tearDown() {
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

	// --- Basic construction ---

	@Test
	void testConstructor() {
		Assertions.assertNotNull(compilationService);
		Assertions.assertSame(fileContentsTracker, compilationService.getFileContentsTracker());
	}

	@Test
	void testSetLanguageClientDoesNotThrow() {
		compilationService.setLanguageClient(null);
	}

	// --- ensureScopeCompiled ---

	@Test
	void testEnsureScopeCompiledSkipsAlreadyCompiled() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setCompiled(true);
		scope.setClasspathResolved(true);

		boolean result = compilationService.ensureScopeCompiled(scope);
		Assertions.assertFalse(result);
	}

	@Test
	void testEnsureScopeCompiledSkipsUnresolvedClasspath() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setCompiled(false);
		scope.setClasspathResolved(false);

		boolean result = compilationService.ensureScopeCompiled(scope);
		Assertions.assertFalse(result);
	}

	@Test
	void testEnsureScopeCompiledPerformsCompilation() throws IOException {
		// Create a simple Groovy source file
		Files.writeString(srcDir.resolve("Hello.groovy"), "class Hello {}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		boolean result = compilationService.ensureScopeCompiled(scope);
		Assertions.assertTrue(result);
		Assertions.assertTrue(scope.isCompiled());
	}

	@Test
	void testEnsureScopeCompiledNullProjectRootWithUnresolvedClasspath() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(null, factory);
		scope.setCompiled(false);
		scope.setClasspathResolved(false);

		// projectRoot == null → the classPath guard is skipped, compilation runs
		// on the default factory's compilation unit (which may produce nothing
		// useful, but it should not crash)
		boolean result = compilationService.ensureScopeCompiled(scope);
		// Expected: either compiles or returns false, but must not throw
		// (the null projectRoot path may fail in factory.create, so we just
		// verify no uncaught exception)
	}

	// --- createOrUpdateCompilationUnit ---

	@Test
	void testCreateOrUpdateCompilationUnit() throws IOException {
		Files.writeString(srcDir.resolve("Foo.groovy"), "class Foo {}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		boolean result = compilationService.createOrUpdateCompilationUnit(scope);
		Assertions.assertNotNull(scope.getCompilationUnit());
	}

	@Test
	void testCreateOrUpdateCompilationUnitWithAdditionalInvalidations() throws IOException {
		Files.writeString(srcDir.resolve("Bar.groovy"), "class Bar {}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		URI barUri = srcDir.resolve("Bar.groovy").toUri();
		Set<URI> invalidations = new HashSet<>();
		invalidations.add(barUri);

		boolean result = compilationService.createOrUpdateCompilationUnit(scope, invalidations);
		Assertions.assertNotNull(scope.getCompilationUnit());
	}

	// --- compile ---

	@Test
	void testCompileWithNullCompilationUnitDoesNotThrow() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setCompilationUnit(null);

		// Should handle null compilationUnit gracefully
		compilationService.compile(scope);
	}

	// --- visitAST ---

	@Test
	void testVisitASTWithNullCompilationUnit() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setCompilationUnit(null);

		// Should handle null gracefully
		compilationService.visitAST(scope);
	}

	// --- compileAndVisitAST ---

	@Test
	void testCompileAndVisitASTWithNoChanges() throws IOException {
		Files.writeString(srcDir.resolve("Test.groovy"), "class Test {}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		// Initial full compile
		compilationService.ensureScopeCompiled(scope);
		Assertions.assertTrue(scope.isCompiled());

		// No changes — compileAndVisitAST should return early
		URI testUri = srcDir.resolve("Test.groovy").toUri();
		compilationService.compileAndVisitAST(scope, testUri);
		// Should not throw
	}

	// --- resetChangedFilesForScope ---

	@Test
	void testResetChangedFilesForScopeWithProjectRoot() throws IOException {
		Files.writeString(srcDir.resolve("A.groovy"), "class A {}");
		URI aUri = srcDir.resolve("A.groovy").toUri();

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);

		fileContentsTracker.forceChanged(aUri);
		Assertions.assertTrue(fileContentsTracker.getChangedURIs().contains(aUri));

		compilationService.resetChangedFilesForScope(scope);

		// The changed URI under this scope's root should be cleared
		Assertions.assertFalse(fileContentsTracker.getChangedURIs().contains(aUri));
	}

	@Test
	void testResetChangedFilesForScopeWithNullRoot() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(null, factory);

		URI someUri = URI.create("file:///other/file.groovy");
		fileContentsTracker.forceChanged(someUri);

		compilationService.resetChangedFilesForScope(scope);

		// Null root means reset all
		Assertions.assertTrue(fileContentsTracker.getChangedURIs().isEmpty());
	}

	// --- clearProcessedChanges ---

	@Test
	void testClearProcessedChangesWithProjectRoot() throws IOException {
		Files.writeString(srcDir.resolve("X.groovy"), "class X {}");
		URI xUri = srcDir.resolve("X.groovy").toUri();

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);

		fileContentsTracker.forceChanged(xUri);

		Set<URI> snapshot = new HashSet<>();
		snapshot.add(xUri);
		compilationService.clearProcessedChanges(scope, snapshot);

		Assertions.assertFalse(fileContentsTracker.getChangedURIs().contains(xUri));
	}

	@Test
	void testClearProcessedChangesWithNullRoot() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(null, factory);

		URI someUri = URI.create("file:///any/file.groovy");
		fileContentsTracker.forceChanged(someUri);

		Set<URI> snapshot = Collections.singleton(someUri);
		compilationService.clearProcessedChanges(scope, snapshot);

		Assertions.assertFalse(fileContentsTracker.getChangedURIs().contains(someUri));
	}

	// --- updateDependencyGraph ---

	@Test
	void testUpdateDependencyGraphWithNullVisitor() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setAstVisitor(null);

		// Should be a no-op
		compilationService.updateDependencyGraph(scope, Collections.singleton(URI.create("file:///foo.groovy")));
		Assertions.assertTrue(scope.getDependencyGraph().isEmpty());
	}

	// --- syntaxCheckSingleFile ---

	@Test
	void testSyntaxCheckSingleFileValidSource() {
		URI testUri = URI.create("file:///test/Valid.groovy");
		fileContentsTracker.setContents(testUri, "class Valid {}");

		// Should not throw
		compilationService.syntaxCheckSingleFile(testUri);
	}

	@Test
	void testSyntaxCheckSingleFileSyntaxError() {
		URI testUri = URI.create("file:///test/Invalid.groovy");
		fileContentsTracker.setContents(testUri, "class { broken");

		// Should not throw even with syntax errors
		compilationService.syntaxCheckSingleFile(testUri);
	}

	@Test
	void testSyntaxCheckSingleFileNullContents() {
		URI testUri = URI.create("file:///test/Unknown.groovy");
		// No contents set — should return early
		compilationService.syntaxCheckSingleFile(testUri);
	}

	// --- recompileForClasspathChange ---

	@Test
	void testRecompileForClasspathChange() throws IOException {
		Files.writeString(srcDir.resolve("Recomp.groovy"), "class Recomp {}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		// First compile
		compilationService.ensureScopeCompiled(scope);

		// Now simulate classpath change
		compilationService.recompileForClasspathChange(scope);
		Assertions.assertTrue(scope.isCompiled());
	}

	// --- recompileAfterJavaChange ---

	@Test
	void testRecompileAfterJavaChange() throws IOException {
		Files.writeString(srcDir.resolve("JavaDep.groovy"), "class JavaDep {}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		compilationService.ensureScopeCompiled(scope);

		// Should not throw
		compilationService.recompileAfterJavaChange(scope);
	}

	// --- Full compilation flow ---

	@Test
	void testFullCompileAndVisitASTFlow() throws IOException {
		Files.writeString(srcDir.resolve("Person.groovy"),
				"class Person {\n  String name\n  int age\n}");

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		compilationService.ensureScopeCompiled(scope);

		Assertions.assertTrue(scope.isCompiled());
		Assertions.assertNotNull(scope.getAstVisitor());
		Assertions.assertNotNull(scope.getCompilationUnit());
	}
}
