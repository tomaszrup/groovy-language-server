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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesWorkspaceSymbolTests {
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
		services.connect(new TestLanguageClient());
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

	@Test
	void testWorkspaceSymbolFindsClass() throws Exception {
		Path filePath = srcRoot.resolve("WorkspaceSymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WorkspaceSymbolTest {\n");
		contents.append("  void myMethod() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("WorkspaceSymbol")).get();

		Assertions.assertTrue(result.isRight(), "Result should be WorkspaceSymbol list");
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		Assertions.assertFalse(symbols.isEmpty(),
				"Should find at least one symbol matching 'WorkspaceSymbol'");

		boolean hasClass = symbols.stream()
				.anyMatch(s -> s.getName().contains("WorkspaceSymbolTest") && s.getKind() == SymbolKind.Class);
		Assertions.assertTrue(hasClass, "Should find class WorkspaceSymbolTest");
	}

	@Test
	void testWorkspaceSymbolFindsMethod() throws Exception {
		Path filePath = srcRoot.resolve("WorkspaceSymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WorkspaceSymbolTest {\n");
		contents.append("  void uniqueMethodName() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("uniqueMethodName")).get();

		Assertions.assertTrue(result.isRight());
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		Assertions.assertFalse(symbols.isEmpty(),
				"Should find at least one symbol matching 'uniqueMethodName'");

		boolean hasMethod = symbols.stream()
				.anyMatch(s -> s.getName().contains("uniqueMethodName"));
		Assertions.assertTrue(hasMethod, "Should find method uniqueMethodName");
	}

	@Test
	void testWorkspaceSymbolEmptyQueryReturnsAll() throws Exception {
		Path filePath = srcRoot.resolve("WorkspaceSymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WorkspaceSymbolTest {\n");
		contents.append("  String name\n");
		contents.append("  void greet() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("")).get();

		Assertions.assertTrue(result.isRight());
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		// Empty query should return all symbols
		Assertions.assertTrue(symbols.size() >= 3,
				"Empty query should return all symbols, found: " + symbols.size());
	}

	@Test
	void testWorkspaceSymbolNoMatchReturnsEmpty() throws Exception {
		Path filePath = srcRoot.resolve("WorkspaceSymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WorkspaceSymbolTest {\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("xyzNonExistent123")).get();

		Assertions.assertTrue(result.isRight());
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		Assertions.assertTrue(symbols.isEmpty(),
				"Non-matching query should return empty list");
	}

	@Test
	void testWorkspaceSymbolCaseInsensitive() throws Exception {
		Path filePath = srcRoot.resolve("WorkspaceSymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WorkspaceSymbolTest {\n");
		contents.append("  void mySpecialMethod() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("myspecialmethod")).get();

		Assertions.assertTrue(result.isRight());
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		Assertions.assertFalse(symbols.isEmpty(),
				"Case-insensitive search should find symbols");
	}

	@Test
	void testWorkspaceSymbolAcrossMultipleFiles() throws Exception {
		Path filePath1 = srcRoot.resolve("WsSymbolFile1.groovy");
		String uri1 = filePath1.toUri().toString();
		StringBuilder contents1 = new StringBuilder();
		contents1.append("class WsSymbolFile1 {\n");
		contents1.append("  void sharedPrefix() {}\n");
		contents1.append("}");
		TextDocumentItem textDocItem1 = new TextDocumentItem(uri1, LANGUAGE_GROOVY, 1, contents1.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocItem1));

		Path filePath2 = srcRoot.resolve("WsSymbolFile2.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class WsSymbolFile2 {\n");
		contents2.append("  void sharedPrefix() {}\n");
		contents2.append("}");
		TextDocumentItem textDocItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocItem2));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("WsSymbolFile")).get();

		Assertions.assertTrue(result.isRight());
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		Assertions.assertTrue(symbols.size() >= 2,
				"Should find symbols from both files, found: " + symbols.size());
	}

	@Test
	void testWorkspaceSymbolFindsField() throws Exception {
		Path filePath = srcRoot.resolve("WorkspaceSymbolTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WorkspaceSymbolTest {\n");
		contents.append("  String uniqueFieldName\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> result = services
				.symbol(new WorkspaceSymbolParams("uniqueFieldName")).get();

		Assertions.assertTrue(result.isRight());
		List<? extends WorkspaceSymbol> symbols = result.getRight();
		Assertions.assertFalse(symbols.isEmpty(),
				"Should find at least one symbol matching 'uniqueFieldName'");
	}

	@Test
	void testWorkspaceSymbolWithMultipleScopesUsesOpenProjectScopesOnly() throws Exception {
		Path projectA = workspaceRoot.resolve("ws-scope-a");
		Path projectB = workspaceRoot.resolve("ws-scope-b");
		Path projectASrc = projectA.resolve("src/main/groovy");
		Path projectBSrc = projectB.resolve("src/main/groovy");
		Files.createDirectories(projectASrc);
		Files.createDirectories(projectBSrc);

		services.registerDiscoveredProjects(Arrays.asList(projectA, projectB));

		Path fileA = projectASrc.resolve("OnlyA.groovy");
		String uriA = fileA.toUri().toString();
		String contentA = "class OnlyA { void fromA() {} }";
		services.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uriA, LANGUAGE_GROOVY, 1, contentA)));

		Path fileB = projectBSrc.resolve("OnlyB.groovy");
		String contentB = "class OnlyB { void fromB() {} }";
		Files.writeString(fileB, contentB);

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> resultA = services
				.symbol(new WorkspaceSymbolParams("OnlyA")).get();
		Assertions.assertTrue(resultA.isRight());
		Assertions.assertFalse(resultA.getRight().isEmpty(),
				"Should return symbols from project A when project A has open files");

		Either<List<? extends org.eclipse.lsp4j.SymbolInformation>, List<? extends WorkspaceSymbol>> resultB = services
				.symbol(new WorkspaceSymbolParams("OnlyB")).get();
		Assertions.assertTrue(resultB.isRight());
		Assertions.assertTrue(resultB.getRight().isEmpty(),
				"Should not return symbols from sibling project B when no file from project B is open");

		deleteDirectoryRecursively(projectA);
		deleteDirectoryRecursively(projectB);
	}

	private void deleteDirectoryRecursively(Path root) throws Exception {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Exception ignored) {
					// Best-effort cleanup in tests; ignore deletions blocked by file locks.
				}
			});
		}
	}
}
