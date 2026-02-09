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

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.providers.SemanticTokensProvider;

class GroovyServicesSemanticTokensTests {
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

	@Test
	void testSemanticTokensReturnsNonEmptyForClass() throws Exception {
		Path filePath = srcRoot.resolve("SemanticClass.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SemanticClass {\n");
		contents.append("  String name\n");
		contents.append("  void doSomething(int count) {\n");
		contents.append("    int localVar = count\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		Assertions.assertNotNull(result);
		List<Integer> data = result.getData();
		Assertions.assertNotNull(data);
		// Data should be non-empty and a multiple of 5 (each token is 5 integers)
		Assertions.assertFalse(data.isEmpty(), "Semantic tokens data should not be empty");
		Assertions.assertEquals(0, data.size() % 5, "Semantic tokens data should be a multiple of 5");
	}

	@Test
	void testSemanticTokensClassDeclaration() throws Exception {
		Path filePath = srcRoot.resolve("SemanticSimple.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Foo {\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// The class name 'Foo' should appear as a token of type 'class' (index 2)
		// with 'declaration' modifier (bit 0 = 1)
		boolean foundClassDecl = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int tokenModifiers = data.get(i + 4);
			int length = data.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("class")
					&& (tokenModifiers & 1) == 1 // declaration modifier
					&& length == "Foo".length()) {
				foundClassDecl = true;
				break;
			}
		}
		Assertions.assertTrue(foundClassDecl, "Should find a class declaration token for 'Foo'");
	}

	@Test
	void testSemanticTokensMethodDeclaration() throws Exception {
		Path filePath = srcRoot.resolve("SemanticMethod.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Bar {\n");
		contents.append("  void myMethod() {\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// Should find a method declaration token for 'myMethod'
		boolean foundMethodDecl = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int tokenModifiers = data.get(i + 4);
			int length = data.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("method")
					&& (tokenModifiers & 1) == 1 // declaration modifier
					&& length == "myMethod".length()) {
				foundMethodDecl = true;
				break;
			}
		}
		Assertions.assertTrue(foundMethodDecl, "Should find a method declaration token for 'myMethod'");
	}

	@Test
	void testSemanticTokensParameterDeclaration() throws Exception {
		Path filePath = srcRoot.resolve("SemanticParam.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Baz {\n");
		contents.append("  void action(String arg) {\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		boolean foundParam = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int tokenModifiers = data.get(i + 4);
			int length = data.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("parameter")
					&& (tokenModifiers & 1) == 1 // declaration modifier
					&& length == "arg".length()) {
				foundParam = true;
				break;
			}
		}
		Assertions.assertTrue(foundParam, "Should find a parameter declaration token for 'arg'");
	}

	@Test
	void testSemanticTokensEmptyFile() throws Exception {
		Path filePath = srcRoot.resolve("SemanticEmpty.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Empty {\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		Assertions.assertNotNull(result);
		Assertions.assertNotNull(result.getData());
		Assertions.assertEquals(0, result.getData().size() % 5);
	}

	@Test
	void testSemanticTokensInterface() throws Exception {
		Path filePath = srcRoot.resolve("SemanticIface.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface MyInterface {\n");
		contents.append("  void doWork()\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		boolean foundInterface = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("interface")
					&& length == "MyInterface".length()) {
				foundInterface = true;
				break;
			}
		}
		Assertions.assertTrue(foundInterface, "Should find an interface token for 'MyInterface'");
	}

	@Test
	void testSemanticTokensEnum() throws Exception {
		Path filePath = srcRoot.resolve("SemanticEnum.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("enum Color {\n");
		contents.append("  RED, GREEN, BLUE\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		boolean foundEnum = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("enum")
					&& length == "Color".length()) {
				foundEnum = true;
				break;
			}
		}
		Assertions.assertTrue(foundEnum, "Should find an enum token for 'Color'");
	}

	@Test
	void testSemanticTokensStaticField() throws Exception {
		Path filePath = srcRoot.resolve("SemanticStatic.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class StaticTest {\n");
		contents.append("  static final String CONSTANT = 'hello'\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// find a property token with static+readonly+declaration modifiers
		boolean foundStaticConst = false;
		int staticBit = 1 << 1;
		int readonlyBit = 1 << 2;
		int declBit = 1;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int tokenModifiers = data.get(i + 4);
			int length = data.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("property")
					&& length == "CONSTANT".length()
					&& (tokenModifiers & declBit) == declBit
					&& (tokenModifiers & staticBit) == staticBit
					&& (tokenModifiers & readonlyBit) == readonlyBit) {
				foundStaticConst = true;
				break;
			}
		}
		Assertions.assertTrue(foundStaticConst,
				"Should find a property token for 'CONSTANT' with static+readonly+declaration modifiers");
	}

	@Test
	void testSemanticTokensDataMultipleOfFive() throws Exception {
		Path filePath = srcRoot.resolve("SemanticMulti.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Multi {\n");
		contents.append("  int x\n");
		contents.append("  String y\n");
		contents.append("  void foo(int a, String b) {\n");
		contents.append("    int local = a\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertNotNull(data);
		Assertions.assertEquals(0, data.size() % 5, "Token data size must be a multiple of 5");
		// Verify all delta values are non-negative
		for (int i = 0; i + 4 < data.size(); i += 5) {
			Assertions.assertTrue(data.get(i) >= 0, "deltaLine should be non-negative at index " + i);
			Assertions.assertTrue(data.get(i + 1) >= 0, "deltaStartChar should be non-negative at index " + (i + 1));
			Assertions.assertTrue(data.get(i + 2) > 0, "length should be positive at index " + (i + 2));
		}
	}
}
