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

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.ClasspathCache;
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
	void testInitializeWithNoWorkspaceFoldersDoesNotThrow() throws Exception {
		InitializeParams params = new InitializeParams();
		params.setWorkspaceFolders(Collections.emptyList());
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

	@Test
	void testInitializeParsesInitializationOptions() throws Exception {
		InitializeParams params = new InitializeParams();
		com.google.gson.JsonObject opts = new com.google.gson.JsonObject();
		opts.addProperty("classpathCache", false);
		opts.addProperty("backfillSiblingProjects", true);
		opts.addProperty("scopeEvictionTTLSeconds", 42);
		opts.addProperty("memoryPressureThreshold", 0.75d);
		com.google.gson.JsonArray enabled = new com.google.gson.JsonArray();
		enabled.add("Gradle");
		opts.add("enabledImporters", enabled);
		params.setInitializationOptions(opts);

		server.initialize(params).get();

		java.lang.reflect.Field classpathCacheEnabled = GroovyLanguageServer.class
				.getDeclaredField("classpathCacheEnabled");
		classpathCacheEnabled.setAccessible(true);
		Assertions.assertFalse(classpathCacheEnabled.getBoolean(server));

		java.lang.reflect.Field backfill = GroovyLanguageServer.class
				.getDeclaredField("backfillSiblingProjects");
		backfill.setAccessible(true);
		Assertions.assertTrue(backfill.getBoolean(server));

		java.lang.reflect.Field ttl = GroovyLanguageServer.class
				.getDeclaredField("scopeEvictionTTLSeconds");
		ttl.setAccessible(true);
		Assertions.assertEquals(42L, ttl.getLong(server));
	}

	@Test
	void testInitializeIgnoresJsonNullClasspathCacheOption() throws Exception {
		InitializeParams params = new InitializeParams();
		com.google.gson.JsonObject opts = new com.google.gson.JsonObject();
		opts.add("classpathCache", com.google.gson.JsonNull.INSTANCE);
		params.setInitializationOptions(opts);

		server.initialize(params).get();

		java.lang.reflect.Field classpathCacheEnabled = GroovyLanguageServer.class
				.getDeclaredField("classpathCacheEnabled");
		classpathCacheEnabled.setAccessible(true);
		Assertions.assertTrue(classpathCacheEnabled.getBoolean(server));
	}

	@Test
	void testInitializeWithWorkspaceFolderTriggersImportStatusFlow() throws Exception {
		CapturingGroovyLanguageClient capturingClient = new CapturingGroovyLanguageClient();
		server.connect(capturingClient);

		Path tempWs = Files.createTempDirectory("gls-ws-");
		try {
			InitializeParams params = new InitializeParams();
			WorkspaceFolder folder = new WorkspaceFolder(tempWs.toUri().toString(), "tmp");
			params.setWorkspaceFolders(java.util.Collections.singletonList(folder));

			server.initialize(params).get();

			long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(3);
			while (System.currentTimeMillis() < deadline
					&& capturingClient.statusUpdates.stream().noneMatch(s -> "ready".equals(s.getState()))) {
				Thread.sleep(25);
			}

			Assertions.assertTrue(
					capturingClient.statusUpdates.stream().anyMatch(s -> "importing".equals(s.getState())
							|| "ready".equals(s.getState()) || "error".equals(s.getState())),
					"Expected at least one structured status update");
		} finally {
			Files.deleteIfExists(tempWs);
		}
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

	@Test
	void testConnectWithPlainLanguageClientClearsGroovyClientField() throws Exception {
		server.connect(new StubLanguageClient());

		Field clientField = GroovyLanguageServer.class.getDeclaredField("client");
		clientField.setAccessible(true);
		Assertions.assertNull(clientField.get(server));
	}

	@Test
	void testGetDecompiledContentWithMissingUriReturnsNull() throws Exception {
		com.google.gson.JsonObject params = new com.google.gson.JsonObject();

		String result = server.getDecompiledContent(params).get(2, TimeUnit.SECONDS);
		Assertions.assertNull(result);
	}

	@Test
	void testSendStatusUpdateAndLogProgressWithNullClientDoNotThrow() throws Exception {
		Method sendStatusUpdate = GroovyLanguageServer.class
				.getDeclaredMethod("sendStatusUpdate", String.class, String.class);
		sendStatusUpdate.setAccessible(true);

		Method logProgress = GroovyLanguageServer.class
				.getDeclaredMethod("logProgress", String.class);
		logProgress.setAccessible(true);

		sendStatusUpdate.invoke(server, "importing", "Testing status");
		logProgress.invoke(server, "Testing progress");
	}

	@Test
	void testApplyLogLevelHandlesKnownAndUnknownValues() throws Exception {
		Method applyLogLevel = GroovyLanguageServer.class
				.getDeclaredMethod("applyLogLevel", String.class);
		applyLogLevel.setAccessible(true);

		applyLogLevel.invoke(null, "INFO");
		applyLogLevel.invoke(null, "NOT_A_LEVEL");
	}

	@Test
	void testImportProjectsAsyncWithWorkspaceFolderPath() throws Exception {
		Path tempWs = Files.createTempDirectory("gls-import-");
		try {
			Path project = tempWs.resolve("project");
			Files.createDirectories(project.resolve("src/main/java"));
			Files.createFile(project.resolve("build.gradle"));

			Method importProjectsAsync = GroovyLanguageServer.class
					.getDeclaredMethod("importProjectsAsync", List.class);
			importProjectsAsync.setAccessible(true);

			WorkspaceFolder folder = new WorkspaceFolder(tempWs.toUri().toString(), "ws");
			Assertions.assertDoesNotThrow(() -> importProjectsAsync.invoke(server,
					java.util.Collections.singletonList(folder)));

			Field coordinatorField = GroovyLanguageServer.class.getDeclaredField("resolutionCoordinator");
			coordinatorField.setAccessible(true);
			Assertions.assertNotNull(coordinatorField.get(server));
		} finally {
			if (Files.exists(tempWs)) {
				java.nio.file.Files.walk(tempWs)
						.sorted(java.util.Comparator.reverseOrder())
						.forEach(p -> {
							try {
								Files.deleteIfExists(p);
							} catch (Exception ignored) {
							}
						});
			}
		}
	}

	@Test
	void testImportProjectsAsyncUsesValidClasspathCache() throws Exception {
		Path tempWs = Files.createTempDirectory("gls-cache-");
		try {
			Path project = tempWs.resolve("project");
			Files.createDirectories(project.resolve("src/main/java"));
			Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");

			Path cp1 = tempWs.resolve("cp-entry-1.jar");
			Path cp2 = tempWs.resolve("cp-entry-2.jar");
			Files.writeString(cp1, "dummy");
			Files.writeString(cp2, "dummy");

			Map<Path, List<String>> classpaths = new LinkedHashMap<>();
			classpaths.put(project, List.of(cp1.toString(), cp2.toString()));

			Map<String, String> stamps = ClasspathCache.computeBuildFileStamps(List.of(project));
			ClasspathCache.save(tempWs, classpaths, stamps, List.of(project));

			Field groovyServicesField = GroovyLanguageServer.class.getDeclaredField("groovyServices");
			groovyServicesField.setAccessible(true);
			GroovyServices services = (GroovyServices) groovyServicesField.get(server);
			services.setWorkspaceRoot(tempWs);

			Method importProjectsAsync = GroovyLanguageServer.class
					.getDeclaredMethod("importProjectsAsync", List.class);
			importProjectsAsync.setAccessible(true);

			WorkspaceFolder folder = new WorkspaceFolder(tempWs.toUri().toString(), "ws");
			Assertions.assertDoesNotThrow(() -> importProjectsAsync.invoke(server,
					java.util.Collections.singletonList(folder)));

			Field coordinatorField = GroovyLanguageServer.class.getDeclaredField("resolutionCoordinator");
			coordinatorField.setAccessible(true);
			Assertions.assertNotNull(coordinatorField.get(server));
		} finally {
			ClasspathCache.invalidate(tempWs);
			if (Files.exists(tempWs)) {
				java.nio.file.Files.walk(tempWs)
						.sorted(java.util.Comparator.reverseOrder())
						.forEach(p -> {
							try {
								Files.deleteIfExists(p);
							} catch (Exception ignored) {
							}
						});
			}
		}
	}

	@Test
	void testSendStatusUpdateSwallowsClientException() throws Exception {
		server.connect(new ThrowingGroovyLanguageClient());

		Method sendStatusUpdate = GroovyLanguageServer.class
				.getDeclaredMethod("sendStatusUpdate", String.class, String.class);
		sendStatusUpdate.setAccessible(true);

		Assertions.assertDoesNotThrow(() -> sendStatusUpdate.invoke(server, "importing", "boom"));
	}

	@Test
	void testMemoryReporterSendsUsageToGroovyClient() throws Exception {
		CapturingGroovyLanguageClient capturingClient = new CapturingGroovyLanguageClient();
		server.connect(capturingClient);

		server.initialize(new InitializeParams()).get();

		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(7);
		while (System.currentTimeMillis() < deadline && capturingClient.memoryUpdates.isEmpty()) {
			Thread.sleep(100);
		}

		Assertions.assertFalse(capturingClient.memoryUpdates.isEmpty(),
				"Expected at least one memoryUsage notification from periodic reporter");
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

	private static class CapturingGroovyLanguageClient extends StubLanguageClient implements GroovyLanguageClient {
		private final List<StatusUpdateParams> statusUpdates = new ArrayList<>();
		private final List<MemoryUsageParams> memoryUpdates = new ArrayList<>();

		@Override
		public void statusUpdate(StatusUpdateParams params) {
			statusUpdates.add(params);
		}

		@Override
		public void memoryUsage(MemoryUsageParams params) {
			memoryUpdates.add(params);
		}
	}

	private static class ThrowingGroovyLanguageClient extends StubLanguageClient implements GroovyLanguageClient {
		@Override
		public void statusUpdate(StatusUpdateParams params) {
			throw new RuntimeException("simulated client failure");
		}

		@Override
		public void memoryUsage(MemoryUsageParams params) {
		}
	}
}
