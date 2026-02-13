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
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
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
	private ExecutorService executor;

	@BeforeEach
	void setup() throws IOException {
		fileContentsTracker = new FileContentsTracker();
		compilationService = new CompilationService(fileContentsTracker);
		executor = Executors.newSingleThreadExecutor();

		tempDir = Files.createTempDirectory("cs-test");
		srcDir = tempDir.resolve("src/main/groovy");
		Files.createDirectories(srcDir);
	}

	@AfterEach
	void tearDown() {
		if (executor != null) {
			executor.shutdownNow();
			try {
				executor.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
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
		compilationService.ensureScopeCompiled(scope);
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

		compilationService.createOrUpdateCompilationUnit(scope);
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

		compilationService.createOrUpdateCompilationUnit(scope, invalidations);
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
		Assertions.assertEquals(testUri, scope.getPreviousContext());
	}

	@Test
	void testEnsureCompiledForContextReturnsNullWhenNoScope() {
		ProjectScopeManager manager = new ProjectScopeManager(new CompilationUnitFactory(), fileContentsTracker) {
			@Override
			public ProjectScope findProjectScope(URI uri) {
				return null;
			}
		};

		ProjectScope result = compilationService.ensureCompiledForContext(
				URI.create("file:///missing/File.groovy"), manager, executor);

		Assertions.assertNull(result);
	}

	@Test
	void testEnsureCompiledForContextRecompilesWhenChangedAndAlreadyCompiled() throws IOException {
		Path file = srcDir.resolve("EnsureCtx.groovy");
		Files.writeString(file, "class EnsureCtx { int n = 1 }");
		URI uri = file.toUri();

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);
		compilationService.ensureScopeCompiled(scope);

		fileContentsTracker.setContents(uri, "class EnsureCtx { int n = 2 }");
		fileContentsTracker.forceChanged(uri);

		ProjectScopeManager manager = new ProjectScopeManager(factory, fileContentsTracker) {
			@Override
			public ProjectScope findProjectScope(URI lookupUri) {
				return scope;
			}
		};

		ProjectScope result = compilationService.ensureCompiledForContext(uri, manager, executor);
		Assertions.assertSame(scope, result);
		Assertions.assertEquals(uri, scope.getPreviousContext());
	}

	@Test
	void testRecompileIfContextChangedUpdatesPreviousContextWhenNoChangedFiles() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		URI previous = URI.create("file:///old/Context.groovy");
		URI next = URI.create("file:///new/Context.groovy");
		scope.setPreviousContext(previous);

		compilationService.recompileIfContextChanged(scope, next);

		Assertions.assertEquals(next, scope.getPreviousContext());
	}

	@Test
	void testNormalizeDiagnosticsForPublishedDocumentClampsAndNormalizesRanges() throws Exception {
		Path file = srcDir.resolve("Clamp.groovy");
		Files.writeString(file, "abc\nxy");
		URI uri = file.toUri();

		Diagnostic reversed = new Diagnostic();
		reversed.setRange(new Range(new Position(10, 40), new Position(0, 0)));

		PublishDiagnosticsParams params = new PublishDiagnosticsParams();
		params.setUri(uri.toString());
		params.setDiagnostics(List.of(reversed));

		Method normalize = CompilationService.class
				.getDeclaredMethod("normalizeDiagnosticsForPublishedDocument", PublishDiagnosticsParams.class);
		normalize.setAccessible(true);

		PublishDiagnosticsParams result = (PublishDiagnosticsParams) normalize.invoke(compilationService, params);
		Diagnostic first = result.getDiagnostics().get(0);

		Assertions.assertEquals(1, first.getRange().getStart().getLine());
		Assertions.assertEquals(2, first.getRange().getStart().getCharacter());
		Assertions.assertEquals(1, first.getRange().getEnd().getLine());
		Assertions.assertEquals(2, first.getRange().getEnd().getCharacter());
	}

	@Test
	void testClampPositionHandlesNullPosition() throws Exception {
		Method clamp = CompilationService.class
				.getDeclaredMethod("clampPosition", Position.class, String[].class);
		clamp.setAccessible(true);

		Position result = (Position) clamp.invoke(compilationService, null, new String[] { "abc", "xy" });

		Assertions.assertEquals(0, result.getLine());
		Assertions.assertEquals(0, result.getCharacter());
	}

	@Test
	void testPublishDiagnosticsBatchSortsByUriAndSkipsEmptyInput() throws Exception {
		CapturingLanguageClient client = new CapturingLanguageClient();

		PublishDiagnosticsParams b = new PublishDiagnosticsParams("file:///b.groovy", List.of());
		PublishDiagnosticsParams a = new PublishDiagnosticsParams("file:///a.groovy", List.of());
		Set<PublishDiagnosticsParams> batch = new HashSet<>();
		batch.add(b);
		batch.add(a);

		Method publishBatch = CompilationService.class
				.getDeclaredMethod("publishDiagnosticsBatch", LanguageClient.class, Set.class);
		publishBatch.setAccessible(true);

		publishBatch.invoke(compilationService, client, batch);
		Assertions.assertEquals(2, client.publishedDiagnostics.size());
		Assertions.assertEquals("file:///a.groovy", client.publishedDiagnostics.get(0).getUri());
		Assertions.assertEquals("file:///b.groovy", client.publishedDiagnostics.get(1).getUri());

		client.publishedDiagnostics.clear();
		publishBatch.invoke(compilationService, client, Collections.emptySet());
		Assertions.assertTrue(client.publishedDiagnostics.isEmpty());
	}

	@Test
	void testHandleCompilationOOMMarksScopeAndSendsFeedback() throws Exception {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setCompiled(false);
		scope.setCompilationFailed(false);

		CapturingLanguageClient client = new CapturingLanguageClient();
		compilationService.setLanguageClient(client);

		Method handleOOM = CompilationService.class.getDeclaredMethod(
				"handleCompilationOOM", ProjectScope.class, VirtualMachineError.class, String.class);
		handleOOM.setAccessible(true);
		handleOOM.invoke(compilationService, scope, new OutOfMemoryError("simulated"), "test");

		Assertions.assertTrue(scope.isCompilationFailed());
		Assertions.assertTrue(scope.isCompiled());
		Assertions.assertFalse(client.messages.isEmpty());
		Assertions.assertFalse(client.publishedDiagnostics.isEmpty());
	}

	@Test
	void testTryIncrementalCompilePathIsReachableFromCompileAndVisitAST() throws IOException {
		Path aFile = srcDir.resolve("A.groovy");
		Path bFile = srcDir.resolve("B.groovy");
		Files.writeString(aFile, "class A { B b }");
		Files.writeString(bFile, "class B { int x = 1 }");
		URI aUri = aFile.toUri();

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);
		compilationService.ensureScopeCompiled(scope);

		fileContentsTracker.setContents(aUri, "class A { B b; String s = 'changed' }");
		fileContentsTracker.forceChanged(aUri);

		compilationService.compileAndVisitAST(scope, aUri);

		Assertions.assertEquals(aUri, scope.getPreviousContext());
		Assertions.assertTrue(scope.isCompiled());
	}

	@Test
	void testCaptureClassSignaturesReturnsEntriesForCompiledClasses() throws Exception {
		Path sigFile = srcDir.resolve("SigExample.groovy");
		Files.writeString(sigFile, "class SigExample { String name; int age }");
		URI sigUri = sigFile.toUri();

		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);
		compilationService.ensureScopeCompiled(scope);

		Method capture = CompilationService.class
				.getDeclaredMethod("captureClassSignatures",
						com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor.class, Set.class);
		capture.setAccessible(true);

		@SuppressWarnings("unchecked")
		java.util.Map<String, com.tomaszrup.groovyls.compiler.ClassSignature> signatures =
				(java.util.Map<String, com.tomaszrup.groovyls.compiler.ClassSignature>) capture.invoke(
						compilationService, scope.getAstVisitor(), Set.of(sigUri));

		Assertions.assertFalse(signatures.isEmpty());
		Assertions.assertTrue(signatures.keySet().stream().anyMatch(name -> name.endsWith("SigExample")));
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

	private static class CapturingLanguageClient implements LanguageClient {
		private final List<PublishDiagnosticsParams> publishedDiagnostics = new ArrayList<>();
		private final List<MessageParams> messages = new ArrayList<>();

		@Override
		public void telemetryEvent(Object object) {
		}

		@Override
		public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(
				ShowMessageRequestParams requestParams) {
			return null;
		}

		@Override
		public void showMessage(MessageParams messageParams) {
			messages.add(messageParams);
		}

		@Override
		public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
			publishedDiagnostics.add(diagnostics);
		}

		@Override
		public void logMessage(MessageParams message) {
		}
	}
}
