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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for {@link GroovyLanguageServer}: initialize(), shutdown(), background
 * import flow, and settings forwarding to importers.
 */
class GroovyLanguageServerTests {

	private GroovyLanguageServer server;

	@BeforeEach
	void setup() {
		server = new GroovyLanguageServer(new CompilationUnitFactory());
		server.connect(new StubLanguageClient());
	}

	@AfterEach
	void tearDown() throws Exception {
		if (server != null) {
			server.shutdown().get();
		}
		server = null;
	}

	// --- initialize() ---

	@Test
	void testInitializeReturnsServerCapabilities() throws Exception {
		InitializeParams params = new InitializeParams();
		InitializeResult result = server.initialize(params).get();

		Assertions.assertNotNull(result);
		ServerCapabilities caps = result.getCapabilities();
		Assertions.assertNotNull(caps);
		Assertions.assertNotNull(caps.getCompletionProvider());
		Assertions.assertTrue(caps.getCompletionProvider().getResolveProvider());
		Assertions.assertEquals(TextDocumentSyncKind.Incremental, caps.getTextDocumentSync().getLeft());
		Assertions.assertTrue(caps.getDocumentSymbolProvider().getLeft());
		Assertions.assertTrue(caps.getWorkspaceSymbolProvider().getLeft());
		Assertions.assertTrue(caps.getReferencesProvider().getLeft());
		Assertions.assertTrue(caps.getDefinitionProvider().getLeft());
		Assertions.assertTrue(caps.getHoverProvider().getLeft());
		Assertions.assertTrue(caps.getDocumentHighlightProvider().getLeft());
		Assertions.assertNotNull(caps.getSignatureHelpProvider());
		Assertions.assertNotNull(caps.getSemanticTokensProvider());
		Assertions.assertNotNull(caps.getCodeActionProvider());
		Assertions.assertTrue(caps.getDocumentFormattingProvider().getLeft());
	}

	@Test
	void testInitializeWithNullRootUriDoesNotThrow() throws Exception {
		InitializeParams params = new InitializeParams();
		params.setRootUri(null);
		InitializeResult result = server.initialize(params).get();
		Assertions.assertNotNull(result);
	}

	@Test
	void testInitializeCompletionTriggerCharacters() throws Exception {
		InitializeParams params = new InitializeParams();
		InitializeResult result = server.initialize(params).get();

		List<String> triggers = result.getCapabilities().getCompletionProvider().getTriggerCharacters();
		Assertions.assertNotNull(triggers);
		Assertions.assertTrue(triggers.contains("."));
	}

	@Test
	void testInitializeSignatureHelpTriggerCharacters() throws Exception {
		InitializeParams params = new InitializeParams();
		InitializeResult result = server.initialize(params).get();

		List<String> triggers = result.getCapabilities().getSignatureHelpProvider().getTriggerCharacters();
		Assertions.assertNotNull(triggers);
		Assertions.assertTrue(triggers.contains("("));
		Assertions.assertTrue(triggers.contains(","));
	}

	@Test
	void testInitializeRenameProviderHasPrepareSupport() throws Exception {
		InitializeParams params = new InitializeParams();
		InitializeResult result = server.initialize(params).get();

		// RenameOptions with prepareProvider=true
		Assertions.assertNotNull(result.getCapabilities().getRenameProvider());
		Assertions.assertTrue(result.getCapabilities().getRenameProvider().getRight().getPrepareProvider());
	}

	@Test
	void testInitializeCodeActionKinds() throws Exception {
		InitializeParams params = new InitializeParams();
		InitializeResult result = server.initialize(params).get();

		CodeActionOptions codeActionOpts = (CodeActionOptions) result.getCapabilities()
				.getCodeActionProvider().getRight();
		List<String> kinds = codeActionOpts.getCodeActionKinds();
		Assertions.assertTrue(kinds.contains(CodeActionKind.QuickFix));
		Assertions.assertTrue(kinds.contains(CodeActionKind.Refactor));
		Assertions.assertTrue(kinds.contains(CodeActionKind.SourceOrganizeImports));
	}

	// --- shutdown() ---

	@Test
	void testShutdownReturnsNonNull() throws Exception {
		server.initialize(new InitializeParams()).get();
		Object result = server.shutdown().get();
		Assertions.assertNotNull(result);
	}

	@Test
	void testShutdownWithoutInitialize() throws Exception {
		Object result = server.shutdown().get();
		Assertions.assertNotNull(result);
	}

	@Test
	void testDoubleShutdownDoesNotThrow() throws Exception {
		server.initialize(new InitializeParams()).get();
		server.shutdown().get();
		Object result = server.shutdown().get();
		Assertions.assertNotNull(result);
	}

	// --- getTextDocumentService / getWorkspaceService ---

	@Test
	void testGetTextDocumentServiceReturnsNonNull() {
		Assertions.assertNotNull(server.getTextDocumentService());
	}

	@Test
	void testGetWorkspaceServiceReturnsNonNull() {
		Assertions.assertNotNull(server.getWorkspaceService());
	}

	// --- importers ---

	@Test
	void testDefaultImportersAreRegistered() {
		List<?> importers = server.getImporters();
		Assertions.assertNotNull(importers);
		Assertions.assertTrue(importers.size() >= 2,
				"Expected at least Gradle and Maven importers");
	}

	// --- Stub client ---

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
