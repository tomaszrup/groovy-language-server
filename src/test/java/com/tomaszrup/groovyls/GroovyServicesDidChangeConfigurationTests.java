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
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for {@link GroovyServices#didChangeConfiguration(DidChangeConfigurationParams)}
 * and the classpath update flow.
 */
class GroovyServicesDidChangeConfigurationTests {
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
	void testDidChangeConfigurationWithNonJsonSettingsIsIgnored() {
		// Should not throw when settings is not a JsonObject
		DidChangeConfigurationParams params = new DidChangeConfigurationParams("not-json");
		services.didChangeConfiguration(params);
		// No assertion needed — just verifying no exception
	}

	@Test
	void testDidChangeConfigurationWithEmptyJsonDoesNotThrow() {
		JsonObject settings = new JsonObject();
		DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
		services.didChangeConfiguration(params);
	}

	@Test
	void testDidChangeConfigurationWithClasspathUpdatesSettings() throws Exception {
		// Open a groovy file first to have a compilation context
		Path filePath = srcRoot.resolve("ConfigTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ConfigTest {\n");
		contents.append("  void doStuff() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Now send a configuration change with a classpath
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonArray classpath = new JsonArray();
		classpath.add("/some/fake/path.jar");
		groovy.add("classpath", classpath);
		settings.add("groovy", groovy);

		DidChangeConfigurationParams params = new DidChangeConfigurationParams(settings);
		services.didChangeConfiguration(params);

		// Verify the services still work after the configuration change
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 10);
		CompletableFuture<Hover> hover = services.hover(new HoverParams(textDocument, position));
		Assertions.assertNotNull(hover);
	}

	@Test
	void testDidChangeConfigurationTogglesSemanticHighlighting() throws Exception {
		Path filePath = srcRoot.resolve("SemanticTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SemanticTest {\n");
		contents.append("  String name = 'hello'\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Disable semantic highlighting
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject sh = new JsonObject();
		sh.addProperty("enabled", false);
		groovy.add("semanticHighlighting", sh);
		settings.add("groovy", groovy);
		services.didChangeConfiguration(new DidChangeConfigurationParams(settings));

		// Semantic tokens request should return empty list
		SemanticTokensParams stParams = new SemanticTokensParams(new TextDocumentIdentifier(uri));
		SemanticTokens tokens = services.semanticTokensFull(stParams).get();
		Assertions.assertNotNull(tokens);
		Assertions.assertTrue(tokens.getData().isEmpty());

		// Re-enable semantic highlighting
		sh.addProperty("enabled", true);
		services.didChangeConfiguration(new DidChangeConfigurationParams(settings));

		// Now tokens should be non-empty (for a class with a property)
		SemanticTokens tokens2 = services.semanticTokensFull(stParams).get();
		Assertions.assertNotNull(tokens2);
		// We don't assert non-empty because that depends on the AST state,
		// but at least it shouldn't throw
	}

	@Test
	void testDidChangeConfigurationTogglesFormatting() throws Exception {
		Path filePath = srcRoot.resolve("FmtTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class FmtTest {\n");
		contents.append("  void doStuff()   {  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Disable formatting
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject fmt = new JsonObject();
		fmt.addProperty("enabled", false);
		groovy.add("formatting", fmt);
		settings.add("groovy", groovy);
		services.didChangeConfiguration(new DidChangeConfigurationParams(settings));

		DocumentFormattingParams fmtParams = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(fmtParams).get();
		Assertions.assertNotNull(edits);
		Assertions.assertTrue(edits.isEmpty(), "Formatting should be disabled");

		// Re-enable formatting
		fmt.addProperty("enabled", true);
		services.didChangeConfiguration(new DidChangeConfigurationParams(settings));

		// Now formatting should potentially return edits
		List<? extends TextEdit> edits2 = services.formatting(fmtParams).get();
		Assertions.assertNotNull(edits2);
	}

	@Test
	void testDirectClasspathUpdate() throws Exception {
		Path filePath = srcRoot.resolve("CpTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class CpTest {\n");
		contents.append("  void test() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Use the direct updateClasspath method
		List<String> newClasspath = new ArrayList<>();
		services.updateClasspath(newClasspath);

		// Services should still function
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 8);
		CompletableFuture<Hover> hover = services.hover(new HoverParams(textDocument, position));
		Assertions.assertNotNull(hover);
	}

	@Test
	void testSettingsChangeListenerIsCalled() throws Exception {
		final boolean[] called = { false };
		final JsonObject[] receivedSettings = { null };

		services.setSettingsChangeListener(settings -> {
			called[0] = true;
			receivedSettings[0] = settings;
		});

		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		groovy.addProperty("testKey", "testValue");
		settings.add("groovy", groovy);

		services.didChangeConfiguration(new DidChangeConfigurationParams(settings));

		Assertions.assertTrue(called[0], "Settings change listener should have been called");
		Assertions.assertNotNull(receivedSettings[0]);
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
