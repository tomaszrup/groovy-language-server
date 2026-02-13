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
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for {@link GroovyServices#resolveCompletionItem(CompletionItem)} —
 * the lazy documentation loading path.
 */
class GroovyServicesResolveCompletionItemTests {
	private static final String LANGUAGE_GROOVY = "groovy";
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_SRC = "./src/main/groovy";

	private GroovyServices services;
	private Path workspaceRoot;
	private Path srcRoot;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		srcRoot = workspaceRoot.resolve(PATH_SRC);
		if (!Files.exists(srcRoot)) {
			srcRoot.toFile().mkdirs();
		}

		services = new GroovyServices(new CompilationUnitFactory());
		services.setWorkspaceRoot(workspaceRoot);
		services.connect(new StubLanguageClient());
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
	void testResolveAlreadyResolvedItemReturnsAsIs() throws Exception {
		CompletionItem item = new CompletionItem("test");
		item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, "existing docs"));

		CompletionItem resolved = services.resolveCompletionItem(item).get();
		Assertions.assertNotNull(resolved);
		MarkupContent doc = (MarkupContent) resolved.getDocumentation().getRight();
		Assertions.assertEquals("existing docs", doc.getValue());
	}

	@Test
	void testResolveItemWithNoKindReturnsWithoutDoc() throws Exception {
		// CompletionItem with a label but no kind — should short-circuit
		CompletionItem item = new CompletionItem("someLabel");
		item.setKind(null);

		CompletionItem resolved = services.resolveCompletionItem(item).get();
		Assertions.assertNotNull(resolved);
		Assertions.assertNull(resolved.getDocumentation());
	}

	@Test
	void testResolveItemWithNullKindReturnsAsIs() throws Exception {
		CompletionItem item = new CompletionItem("test");
		item.setKind(null);

		CompletionItem resolved = services.resolveCompletionItem(item).get();
		Assertions.assertNotNull(resolved);
		Assertions.assertNull(resolved.getDocumentation());
	}

	@Test
	void testResolveGroovydocForMethodInWorkspace() throws Exception {
		Path filePath = srcRoot.resolve("DocTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class DocTest {\n");
		contents.append("  /**\n");
		contents.append("   * Computes the sum of two numbers.\n");
		contents.append("   */\n");
		contents.append("  int add(int a, int b) {\n");
		contents.append("    return a + b\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Build a CompletionItem that references the method
		CompletionItem item = new CompletionItem("add");
		item.setKind(CompletionItemKind.Method);
		JsonObject data = new JsonObject();
		data.addProperty("declaringClass", "DocTest");
		data.addProperty("signature", "int,int");
		item.setData(data);

		CompletionItem resolved = services.resolveCompletionItem(item).get();
		Assertions.assertNotNull(resolved);
		// Groovydoc should be resolved
		if (resolved.getDocumentation() != null) {
			MarkupContent doc = (MarkupContent) resolved.getDocumentation().getRight();
			Assertions.assertTrue(doc.getValue().contains("sum of two numbers"));
		}
	}

	@Test
	void testResolveUnknownMethodReturnsNoDoc() throws Exception {
		Path filePath = srcRoot.resolve("NoDocs.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class NoDocs {\n");
		contents.append("  void doStuff() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CompletionItem item = new CompletionItem("nonExistentMethod");
		item.setKind(CompletionItemKind.Method);

		CompletionItem resolved = services.resolveCompletionItem(item).get();
		Assertions.assertNotNull(resolved);
		// No documentation expected for a method that doesn't exist
		Assertions.assertNull(resolved.getDocumentation());
	}

	@Test
	void testResolvePropertyWithGroovydoc() throws Exception {
		Path filePath = srcRoot.resolve("PropDoc.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PropDoc {\n");
		contents.append("  /**\n");
		contents.append("   * The user's name.\n");
		contents.append("   */\n");
		contents.append("  String name\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CompletionItem item = new CompletionItem("name");
		item.setKind(CompletionItemKind.Property);
		JsonObject data = new JsonObject();
		data.addProperty("declaringClass", "PropDoc");
		item.setData(data);

		CompletionItem resolved = services.resolveCompletionItem(item).get();
		Assertions.assertNotNull(resolved);
		if (resolved.getDocumentation() != null) {
			MarkupContent doc = (MarkupContent) resolved.getDocumentation().getRight();
			Assertions.assertTrue(doc.getValue().contains("user's name"));
		}
	}

	// --- Stub ---

	private static class StubLanguageClient implements LanguageClient {
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
	}
}
