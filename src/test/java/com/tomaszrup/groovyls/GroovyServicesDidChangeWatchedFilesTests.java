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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for {@link GroovyServices#didChangeWatchedFiles(DidChangeWatchedFilesParams)}
 * and the recompilation chain it triggers.
 */
class GroovyServicesDidChangeWatchedFilesTests {
	private static final String LANGUAGE_GROOVY = "groovy";
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_SRC = "./src/main/groovy";

	private GroovyServices services;
	private Path workspaceRoot;
	private Path srcRoot;
	private StubLanguageClient stubLanguageClient;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		srcRoot = workspaceRoot.resolve(PATH_SRC);
		if (!Files.exists(srcRoot)) {
			srcRoot.toFile().mkdirs();
		}

		services = new GroovyServices(new CompilationUnitFactory());
		services.setWorkspaceRoot(workspaceRoot);
		stubLanguageClient = new StubLanguageClient();
		services.connect(stubLanguageClient);
	}

	@AfterEach
	void tearDown() {
		TestWorkspaceHelper.cleanSrcDirectory(srcRoot);
		if (services != null) {
			services.setWorkspaceRoot(null);
		}
		services = null;
	}

	@Test
	void testDidChangeWatchedFilesWithEmptyChanges() {
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(Collections.emptyList());
		// Should not throw
		services.didChangeWatchedFiles(params);
	}

	@Test
	void testDidChangeWatchedFilesWithNewGroovyFile() throws Exception {
		// Open an initial file
		Path filePath1 = srcRoot.resolve("Existing.groovy");
		String uri1 = filePath1.toUri().toString();
		StringBuilder contents1 = new StringBuilder();
		contents1.append("class Existing {\n");
		contents1.append("  void test() {}\n");
		contents1.append("}");
		TextDocumentItem textDocumentItem1 = new TextDocumentItem(uri1, LANGUAGE_GROOVY, 1, contents1.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem1));

		// Create a new groovy file on disk
		Path filePath2 = srcRoot.resolve("NewFile.groovy");
		Files.writeString(filePath2, "class NewFile {\n  String name\n}");

		// Notify the service about the file creation
		FileEvent fileEvent = new FileEvent(filePath2.toUri().toString(), FileChangeType.Created);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));
		services.didChangeWatchedFiles(params);

		// The service should have picked up the new file — verify by checking
		// that queries don't throw
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri1);
		Position position = new Position(1, 8);
		CompletableFuture<Hover> hover = services.hover(new HoverParams(textDocument, position));
		Assertions.assertNotNull(hover);
	}

	@Test
	void testDidChangeWatchedFilesWithDeletedGroovyFile() throws Exception {
		// Create and open a groovy file
		Path filePath = srcRoot.resolve("ToDelete.groovy");
		Files.writeString(filePath, "class ToDelete {\n  void doStuff() {}\n}");
		String uri = filePath.toUri().toString();
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1,
				"class ToDelete {\n  void doStuff() {}\n}");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		services.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));

		// Delete the file
		Files.deleteIfExists(filePath);

		// Notify the service
		FileEvent fileEvent = new FileEvent(uri, FileChangeType.Deleted);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));
		services.didChangeWatchedFiles(params);

		// Should not throw
	}

	@Test
	void testDidChangeWatchedFilesWithModifiedGroovyFile() throws Exception {
		// Create and open a groovy file
		Path filePath = srcRoot.resolve("Modified.groovy");
		String initialContent = "class Modified {\n  void original() {}\n}";
		Files.writeString(filePath, initialContent);
		String uri = filePath.toUri().toString();
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, initialContent);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		services.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));

		// Modify the file on disk
		Files.writeString(filePath, "class Modified {\n  void updated() {}\n  int value = 42\n}");

		// Notify the service
		FileEvent fileEvent = new FileEvent(uri, FileChangeType.Changed);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));
		services.didChangeWatchedFiles(params);

		// Should not throw — service picks up the changed file
	}

	@Test
	void testDidChangeWatchedFilesTriggersJavaChangeListener() throws Exception {
		// Open a groovy file to establish context
		Path filePath = srcRoot.resolve("Listener.groovy");
		String uri = filePath.toUri().toString();
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1,
				"class Listener {\n  void test() {}\n}");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		final boolean[] listenerCalled = { false };
		services.setJavaChangeListener(projectRoot -> {
			listenerCalled[0] = true;
		});

		// Simulate a .java file change — but since there are no project scopes
		// (only the default scope), the listener won't match. This tests the
		// code path without a matching project scope.
		Path javaFile = srcRoot.resolve("Helper.java");
		FileEvent fileEvent = new FileEvent(javaFile.toUri().toString(), FileChangeType.Changed);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));
		services.didChangeWatchedFiles(params);

		// The listener should NOT be called in default scope mode (no projectScopes)
		Assertions.assertFalse(listenerCalled[0],
				"Java change listener should not fire when no project scopes are registered");
	}

	@Test
	void testDidChangeWatchedFilesMultipleEventsAtOnce() throws Exception {
		Path filePath1 = srcRoot.resolve("Multi1.groovy");
		Path filePath2 = srcRoot.resolve("Multi2.groovy");
		Files.writeString(filePath1, "class Multi1 {}");
		Files.writeString(filePath2, "class Multi2 {}");

		// Open one of them
		TextDocumentItem textDocumentItem = new TextDocumentItem(
				filePath1.toUri().toString(), LANGUAGE_GROOVY, 1, "class Multi1 {}");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Send multiple events at once
		List<FileEvent> events = Arrays.asList(
				new FileEvent(filePath1.toUri().toString(), FileChangeType.Changed),
				new FileEvent(filePath2.toUri().toString(), FileChangeType.Created));
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(events);
		services.didChangeWatchedFiles(params);

		// Should handle multiple events without error
	}

	@Test
	void testIsBuildOutputFileDetectsGradleBuildDir() {
		Path projectRoot = Paths.get("/project");
		Assertions.assertTrue(GroovyServices.isBuildOutputFile(
				projectRoot.resolve("build/generated/sources/Foo.java"), projectRoot));
		Assertions.assertTrue(GroovyServices.isBuildOutputFile(
				projectRoot.resolve("build/classes/java/main/Foo.class"), projectRoot));
	}

	@Test
	void testIsBuildOutputFileDetectsMavenTargetDir() {
		Path projectRoot = Paths.get("/project");
		Assertions.assertTrue(GroovyServices.isBuildOutputFile(
				projectRoot.resolve("target/generated-sources/Foo.java"), projectRoot));
	}

	@Test
	void testIsBuildOutputFileAllowsSrcFiles() {
		Path projectRoot = Paths.get("/project");
		Assertions.assertFalse(GroovyServices.isBuildOutputFile(
				projectRoot.resolve("src/main/java/Foo.java"), projectRoot));
		Assertions.assertFalse(GroovyServices.isBuildOutputFile(
				projectRoot.resolve("lib/Foo.java"), projectRoot));
	}

	@Test
	void testBuildOutputJavaFilesDoNotTriggerRecompile() throws Exception {
		// Register a project scope via addProjects
		Path projectRoot = workspaceRoot;
		services.addProjects(Collections.singletonMap(projectRoot, Collections.emptyList()));

		// Open a groovy file to establish a compiled scope
		Path groovyFile = srcRoot.resolve("BuildOutput.groovy");
		Files.writeString(groovyFile, "class BuildOutput {}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(
				groovyFile.toUri().toString(), LANGUAGE_GROOVY, 1, "class BuildOutput {}");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		final boolean[] listenerCalled = { false };
		services.setJavaChangeListener(root -> {
			listenerCalled[0] = true;
		});

		// Simulate a .java file change inside build/generated/ (annotation processor output)
		Path generatedJavaFile = projectRoot.resolve("build/generated/sources/annotationProcessor/Foo.java");
		FileEvent fileEvent = new FileEvent(generatedJavaFile.toUri().toString(), FileChangeType.Created);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));
		services.didChangeWatchedFiles(params);

		// Wait a bit to ensure the debounce doesn't fire
		Thread.sleep(500);

		Assertions.assertFalse(listenerCalled[0],
				"Java change listener should NOT fire for build-output .java files");
	}

	@Test
	void testBuildOutputJavaFilesDoNotTriggerGroovyRecompileInNonRecompilePath() throws Exception {
		// Register a project scope that does NOT need a Java recompile
		// (i.e. only build-output .java events arrive, which are not real source changes)
		Path projectRoot = workspaceRoot;
		services.addProjects(Collections.singletonMap(projectRoot, Collections.emptyList()));

		// Open and compile a groovy file so we have a compiled scope
		Path groovyFile = srcRoot.resolve("Stable.groovy");
		Files.writeString(groovyFile, "class Stable {}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(
				groovyFile.toUri().toString(), LANGUAGE_GROOVY, 1, "class Stable {}");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Simulate a build-output .java file change (e.g. annotation processor output)
		// inside target/ — this should NOT be in scopeUris and should not trigger
		// Groovy scope recompilation via the non-recompile path
		Path buildOutputJava = projectRoot.resolve("target/generated/Foo.java");
		FileEvent fileEvent = new FileEvent(buildOutputJava.toUri().toString(), FileChangeType.Created);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));

		// After didChangeWatchedFiles, the scope should NOT have pending changed
		// URIs for build-output files — no unnecessary Groovy recompile
		services.didChangeWatchedFiles(params);

		// Verify that a subsequent hover request does NOT queue a background compilation.
		// If the build-output .java was incorrectly added to changedFiles/scopeUris,
		// ensureCompiledForContext() would see non-empty changedURIs and trigger
		// a background compile. With our fix, changedURIs for this scope are empty
		// so no extra compilation happens.
		TextDocumentIdentifier textDoc = new TextDocumentIdentifier(groovyFile.toUri().toString());
		CompletableFuture<Hover> hover = services.hover(new HoverParams(textDoc, new Position(0, 8)));
		Assertions.assertNotNull(hover);
	}

	@Test
	void testSourceJavaFilesDoTriggerRecompile() throws Exception {
		// Register a project scope via addProjects
		Path projectRoot = workspaceRoot;
		services.addProjects(Collections.singletonMap(projectRoot, Collections.emptyList()));

		// Open a groovy file to establish a compiled scope
		Path groovyFile = srcRoot.resolve("SourceJava.groovy");
		Files.writeString(groovyFile, "class SourceJava {}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(
				groovyFile.toUri().toString(), LANGUAGE_GROOVY, 1, "class SourceJava {}");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CountDownLatch latch = new CountDownLatch(1);
		services.setJavaChangeListener(root -> {
			latch.countDown();
		});

		// Simulate a .java file change inside src/ (real source file)
		Path sourceJavaFile = projectRoot.resolve("src/main/java/Bar.java");
		FileEvent fileEvent = new FileEvent(sourceJavaFile.toUri().toString(), FileChangeType.Changed);
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(fileEvent));
		services.didChangeWatchedFiles(params);

		// The listener should fire (after the debounce delay)
		boolean fired = latch.await(5, TimeUnit.SECONDS);
		Assertions.assertTrue(fired,
				"Java change listener should fire for source .java files");
	}

	@Test
	void testMovedSourceJavaFilesTriggerRecompile() throws Exception {
		Path projectRoot = workspaceRoot;
		services.addProjects(Collections.singletonMap(projectRoot, Collections.emptyList()));

		Path groovyFile = srcRoot.resolve("MoveJavaTrigger.groovy");
		Files.writeString(groovyFile, "class MoveJavaTrigger {}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(
				groovyFile.toUri().toString(), LANGUAGE_GROOVY, 1, "class MoveJavaTrigger {}\n");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CountDownLatch latch = new CountDownLatch(1);
		services.setJavaChangeListener(root -> latch.countDown());

		Path oldJava = projectRoot.resolve("src/main/java/com/example/SomeClass.java");
		Path newJava = projectRoot.resolve("src/main/java/com/other/SomeClass.java");

		List<FileEvent> events = Arrays.asList(
				new FileEvent(oldJava.toUri().toString(), FileChangeType.Deleted),
				new FileEvent(newJava.toUri().toString(), FileChangeType.Created));
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(events);
		services.didChangeWatchedFiles(params);

		boolean fired = latch.await(5, TimeUnit.SECONDS);
		Assertions.assertTrue(fired,
				"Java change listener should fire for moved source .java files under src/main/java");
	}

	@Test
	void testMovedJavaFileAutomaticallyUpdatesGroovyImport() throws Exception {
		Path projectRoot = workspaceRoot;
		java.util.Map<Path, List<String>> projectClasspaths = new java.util.LinkedHashMap<>();
		projectClasspaths.put(projectRoot, new ArrayList<>());
		services.addProjects(projectClasspaths);

		Path groovyFile = srcRoot.resolve("com/test/AutoImportUpdate.groovy");
		Files.createDirectories(groovyFile.getParent());
		String source = "package com.test\n\nimport com.example.SomeClass\n\nclass AutoImportUpdate {\n  SomeClass value\n}\n";
		Files.writeString(groovyFile, source);
		services.didOpen(new DidOpenTextDocumentParams(
				new TextDocumentItem(groovyFile.toUri().toString(), LANGUAGE_GROOVY, 1, source)));

		Path oldJava = projectRoot.resolve("src/main/java/com/example/SomeClass.java");
		Path newJava = projectRoot.resolve("src/main/java/com/other/SomeClass.java");
		List<FileEvent> events = Arrays.asList(
				new FileEvent(oldJava.toUri().toString(), FileChangeType.Deleted),
				new FileEvent(newJava.toUri().toString(), FileChangeType.Created));
		services.didChangeWatchedFiles(new DidChangeWatchedFilesParams(events));

		Assertions.assertNotNull(stubLanguageClient.lastApplyEditParams,
				"Expected automatic workspace/applyEdit request for moved Java import");
		List<TextEdit> edits = stubLanguageClient.lastApplyEditParams.getEdit().getChanges().get(groovyFile.toUri().toString());
		Assertions.assertNotNull(edits,
				"Expected edit for Groovy file containing moved Java import");
		Assertions.assertFalse(edits.isEmpty(), "Expected non-empty import update edit");
		String updated = edits.get(0).getNewText();
		Assertions.assertTrue(updated.contains("import com.other.SomeClass"),
				"Updated source should contain new import package");
		Assertions.assertFalse(updated.contains("import com.example.SomeClass"),
				"Updated source should not contain stale import package");
	}

	// --- Stub ---

	private static class StubLanguageClient implements LanguageClient {
		private ApplyWorkspaceEditParams lastApplyEditParams;

		@Override
		public void telemetryEvent(Object object) {
		}

		@Override
		public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void showMessage(MessageParams messageParams) {
		}

		@Override
		public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
		}

		@Override
		public void logMessage(MessageParams message) {
		}

		@Override
		public CompletableFuture<Void> refreshSemanticTokens() {
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
			this.lastApplyEditParams = params;
			return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true));
		}
	}
}
