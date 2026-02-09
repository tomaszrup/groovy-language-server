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
package com.tomaszrup.groovyls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesDocumentSymbolTests {
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
		services.connect(new LanguageClient() {
			@Override
			public void telemetryEvent(Object object) {
			}

			@Override
			public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
				return null;
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
		});
	}

	@AfterEach
	void tearDown() {
		TestWorkspaceHelper.cleanSrcDirectory(srcRoot);
		if (services != null) {
			services.setWorkspaceRoot(null);
		}
		services = null;
		workspaceRoot = null;
		srcRoot = null;
	}

	// --- Class symbols ---

	@Test
	void testDocumentSymbolForClass() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SymbolTest {\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		Assertions.assertNotNull(symbols, "Document symbols should not be null");
		Assertions.assertFalse(symbols.isEmpty(), "Should have at least one symbol for the class");

		List<SymbolInformation> classSymbols = symbols.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.filter(s -> s.getKind() == SymbolKind.Class)
				.collect(Collectors.toList());
		Assertions.assertFalse(classSymbols.isEmpty(), "Should have a class symbol");
		Assertions.assertTrue(classSymbols.stream().anyMatch(s -> s.getName().contains("SymbolTest")),
				"Class symbol should contain 'SymbolTest'");
	}

	@Test
	void testDocumentSymbolForMethod() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SymbolTest {\n");
		contents.append("  void myMethod() {}\n");
		contents.append("  String anotherMethod(int x) { return \"\" }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		List<SymbolInformation> methodSymbols = symbols.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.filter(s -> s.getKind() == SymbolKind.Method)
				.collect(Collectors.toList());
		Assertions.assertTrue(methodSymbols.size() >= 2,
				"Should have at least 2 method symbols, found: " + methodSymbols.size());
	}

	@Test
	void testDocumentSymbolForField() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SymbolTest {\n");
		contents.append("  String name\n");
		contents.append("  int count\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		List<SymbolInformation> fieldSymbols = symbols.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.filter(s -> s.getKind() == SymbolKind.Field || s.getKind() == SymbolKind.Property)
				.collect(Collectors.toList());
		Assertions.assertTrue(fieldSymbols.size() >= 2,
				"Should have at least 2 field/property symbols, found: " + fieldSymbols.size());
	}

	@Test
	void testDocumentSymbolForInterface() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface SymbolTest {\n");
		contents.append("  void doSomething()\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		List<SymbolInformation> interfaceSymbols = symbols.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.filter(s -> s.getKind() == SymbolKind.Interface)
				.collect(Collectors.toList());
		Assertions.assertFalse(interfaceSymbols.isEmpty(), "Should have an interface symbol");
	}

	@Test
	void testDocumentSymbolForEnum() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("enum SymbolTest {\n");
		contents.append("  RED, GREEN, BLUE\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		List<SymbolInformation> enumSymbols = symbols.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.filter(s -> s.getKind() == SymbolKind.Enum)
				.collect(Collectors.toList());
		Assertions.assertFalse(enumSymbols.isEmpty(), "Should have an enum symbol");
	}

	@Test
	void testDocumentSymbolContainsClassAndMembers() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SymbolTest {\n");
		contents.append("  String name\n");
		contents.append("  void greet() {}\n");
		contents.append("  static int count = 0\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		// Should have at least 3 symbols: 1 class + 1 property/field + 1 method (+ possibly count field)
		Assertions.assertTrue(symbols.size() >= 3,
				"Should have at least 3 symbols (class + field + method), found: " + symbols.size());
	}

	@Test
	void testDocumentSymbolEmptyFile() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, "");
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		Assertions.assertNotNull(symbols, "Document symbols should not be null for empty file");
		Assertions.assertTrue(symbols.isEmpty(), "Empty file should have no symbols");
	}

	@Test
	void testDocumentSymbolForConstructor() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SymbolTest {\n");
		contents.append("  String name\n");
		contents.append("  SymbolTest(String name) {\n");
		contents.append("    this.name = name\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		// Should include the constructor as a method symbol
		List<SymbolInformation> methodSymbols = symbols.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.filter(s -> s.getKind() == SymbolKind.Method || s.getKind() == SymbolKind.Constructor)
				.collect(Collectors.toList());
		Assertions.assertFalse(methodSymbols.isEmpty(),
				"Should have at least one method/constructor symbol");
	}

	@Test
	void testDocumentSymbolLocations() throws Exception {
		Path filePath = srcRoot.resolve("SymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SymbolTest {\n");
		contents.append("  void myMethod() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();

		// Verify that all symbols have valid locations
		for (Either<SymbolInformation, DocumentSymbol> symbol : symbols) {
			if (symbol.isLeft()) {
				SymbolInformation info = symbol.getLeft();
				Assertions.assertNotNull(info.getLocation(), "Symbol should have a location");
				Assertions.assertEquals(uri, info.getLocation().getUri(),
						"Symbol location URI should match the document");
			}
		}
	}
}
