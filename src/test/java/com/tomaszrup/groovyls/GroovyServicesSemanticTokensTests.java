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
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;
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
	void testSemanticTokensExtendsImplementsKeywords() throws Exception {
		Path filePath = srcRoot.resolve("SemanticExtendsImpl.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface MyIface {}\n");
		contents.append("class Base {}\n");
		contents.append("class Child extends Base implements MyIface {\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());

		int keywordTypeIndex = SemanticTokensProvider.TOKEN_TYPES.indexOf("keyword");
		boolean foundExtends = false;
		boolean foundImplements = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == keywordTypeIndex && length == "extends".length()) {
				foundExtends = true;
			}
			if (tokenType == keywordTypeIndex && length == "implements".length()) {
				foundImplements = true;
			}
		}
		Assertions.assertTrue(foundExtends, "Should find a keyword token for 'extends'");
		Assertions.assertTrue(foundImplements, "Should find a keyword token for 'implements'");
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

	@Test
	void testSemanticTokensRangeReturnsOnlyVisibleTokens() throws Exception {
		Path filePath = srcRoot.resolve("SemanticRange.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		// Line 0: class First {
		// Line 1:   void firstMethod() {}
		// Line 2: }
		// Line 3: class Second {
		// Line 4:   void secondMethod() {}
		// Line 5: }
		contents.append("class First {\n");
		contents.append("  void firstMethod() {}\n");
		contents.append("}\n");
		contents.append("class Second {\n");
		contents.append("  void secondMethod() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		// Request range covering only the second class (lines 3-5, 0-based)
		Range range = new Range(new Position(3, 0), new Position(5, 1));
		SemanticTokensRangeParams rangeParams = new SemanticTokensRangeParams(textDocument, range);
		SemanticTokens rangeResult = services.semanticTokensRange(rangeParams).get();
		Assertions.assertNotNull(rangeResult);
		List<Integer> rangeData = rangeResult.getData();
		Assertions.assertNotNull(rangeData);
		Assertions.assertEquals(0, rangeData.size() % 5, "Range token data should be a multiple of 5");

		// Should find 'Second' class declaration but NOT 'First'
		boolean foundSecond = false;
		boolean foundFirst = false;
		for (int i = 0; i + 4 < rangeData.size(); i += 5) {
			int tokenType = rangeData.get(i + 3);
			int length = rangeData.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("class")
					&& length == "Second".length()) {
				foundSecond = true;
			}
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("class")
					&& length == "First".length()) {
				foundFirst = true;
			}
		}
		Assertions.assertTrue(foundSecond, "Should find class token for 'Second' in range");
		Assertions.assertFalse(foundFirst, "Should NOT find class token for 'First' outside range");

		// Also verify full tokens contain both
		SemanticTokens fullResult = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> fullData = fullResult.getData();
		boolean fullHasFirst = false;
		boolean fullHasSecond = false;
		for (int i = 0; i + 4 < fullData.size(); i += 5) {
			int tokenType = fullData.get(i + 3);
			int length = fullData.get(i + 2);
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("class")
					&& length == "First".length()) {
				fullHasFirst = true;
			}
			if (tokenType == SemanticTokensProvider.TOKEN_TYPES.indexOf("class")
					&& length == "Second".length()) {
				fullHasSecond = true;
			}
		}
		Assertions.assertTrue(fullHasFirst, "Full tokens should contain 'First'");
		Assertions.assertTrue(fullHasSecond, "Full tokens should contain 'Second'");
	}

	@Test
	void testSemanticTokensRangeEmptyRange() throws Exception {
		Path filePath = srcRoot.resolve("SemanticRangeEmpty.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class OnlyClass {\n");
		contents.append("  void myMethod() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		// Request a range that covers no code (far beyond file end)
		Range range = new Range(new Position(100, 0), new Position(200, 0));
		SemanticTokensRangeParams rangeParams = new SemanticTokensRangeParams(textDocument, range);
		SemanticTokens result = services.semanticTokensRange(rangeParams).get();
		Assertions.assertNotNull(result);
		Assertions.assertNotNull(result.getData());
		Assertions.assertTrue(result.getData().isEmpty(), "Range beyond file should return empty tokens");
	}

	@Test
	void testSemanticTokensMethodReturnType() throws Exception {
		Path filePath = srcRoot.resolve("SemanticReturnType.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ReturnTypeTest {\n");
		contents.append("  String getName() {\n");
		contents.append("    return 'hello'\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// Should find a class token for 'String' (the return type)
		int classTypeIndex = SemanticTokensProvider.TOKEN_TYPES.indexOf("class");
		boolean foundReturnType = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == classTypeIndex && length == "String".length()) {
				foundReturnType = true;
				break;
			}
		}
		Assertions.assertTrue(foundReturnType, "Should find a type token for return type 'String'");
	}

	@Test
	void testSemanticTokensParameterType() throws Exception {
		Path filePath = srcRoot.resolve("SemanticParamType.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ParamTypeTest {\n");
		contents.append("  void process(String input) {\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// Should find a class token for 'String' (the parameter type)
		int classTypeIndex = SemanticTokensProvider.TOKEN_TYPES.indexOf("class");
		boolean foundParamType = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == classTypeIndex && length == "String".length()) {
				foundParamType = true;
				break;
			}
		}
		Assertions.assertTrue(foundParamType, "Should find a type token for parameter type 'String'");
	}

	@Test
	void testSemanticTokensTypeParameter() throws Exception {
		Path filePath = srcRoot.resolve("SemanticGeneric.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Container<T> {\n");
		contents.append("  T value\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// Should find a typeParameter token for 'T'
		int typeParamIndex = SemanticTokensProvider.TOKEN_TYPES.indexOf("typeParameter");
		boolean foundTypeParam = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == typeParamIndex && length == "T".length()) {
				foundTypeParam = true;
				break;
			}
		}
		Assertions.assertTrue(foundTypeParam, "Should find a typeParameter token for 'T'");
	}

	@Test
	void testSemanticTokensImportNamespace() throws Exception {
		Path filePath = srcRoot.resolve("SemanticImportNs.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.List\n");
		contents.append("class ImportTest {\n");
		contents.append("  List items\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);

		SemanticTokens result = services.semanticTokensFull(new SemanticTokensParams(textDocument)).get();
		List<Integer> data = result.getData();
		Assertions.assertFalse(data.isEmpty());
		// Should find namespace tokens for 'java' and 'util' in the import
		int nsIndex = SemanticTokensProvider.TOKEN_TYPES.indexOf("namespace");
		boolean foundNamespace = false;
		for (int i = 0; i + 4 < data.size(); i += 5) {
			int tokenType = data.get(i + 3);
			int length = data.get(i + 2);
			if (tokenType == nsIndex && (length == "java".length() || length == "util".length())) {
				foundNamespace = true;
				break;
			}
		}
		Assertions.assertTrue(foundNamespace, "Should find namespace tokens for import package segments");
	}

	@Test
	void testSemanticTokensTypeParameterIndex() throws Exception {
		// Verify that typeParameter index is 13
		Assertions.assertEquals(13, SemanticTokensProvider.TOKEN_TYPES.indexOf("typeParameter"),
				"typeParameter should be at index 13 in TOKEN_TYPES");
	}
}
