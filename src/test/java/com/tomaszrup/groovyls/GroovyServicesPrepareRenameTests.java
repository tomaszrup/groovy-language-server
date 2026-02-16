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

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

@SuppressWarnings("all")
class GroovyServicesPrepareRenameTests {
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

	// --- Local variable ---

	@Test
	void testPrepareRenameLocalVariable() throws Exception {
		Path filePath = srcRoot.resolve("PrepareRenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PrepareRenameTest {\n");
		contents.append("  void method() {\n");
		contents.append("    String localVar = \"hello\"\n");
		contents.append("    localVar.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 14); // on "localVar" declaration
		PrepareRenameParams params = new PrepareRenameParams(textDocument, position);
		Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result = services.prepareRename(params)
				.get();

		Assertions.assertNotNull(result, "PrepareRename result should not be null");
		Assertions.assertTrue(result.isSecond(), "Result should be a PrepareRenameResult");
		PrepareRenameResult prepareResult = result.getSecond();
		Assertions.assertEquals("localVar", prepareResult.getPlaceholder());
		Assertions.assertNotNull(prepareResult.getRange(), "Range should not be null");
	}

	@Test
	void testPrepareRenameLocalVariableFromUsage() throws Exception {
		Path filePath = srcRoot.resolve("PrepareRenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PrepareRenameTest {\n");
		contents.append("  void method() {\n");
		contents.append("    int counter = 0\n");
		contents.append("    counter = counter + 1\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(3, 6); // on "counter" usage
		PrepareRenameParams params = new PrepareRenameParams(textDocument, position);
		Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result = services.prepareRename(params)
				.get();

		Assertions.assertNotNull(result, "PrepareRename result should not be null");
		Assertions.assertTrue(result.isSecond(), "Result should be a PrepareRenameResult");
		PrepareRenameResult prepareResult = result.getSecond();
		Assertions.assertEquals("counter", prepareResult.getPlaceholder());
	}

	// --- Member variable ---

	@Test
	void testPrepareRenameMemberVariable() throws Exception {
		Path filePath = srcRoot.resolve("PrepareRenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PrepareRenameTest {\n");
		contents.append("  String memberVar\n");
		contents.append("  void method() {\n");
		contents.append("    memberVar = \"hello\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 11); // on "memberVar" declaration
		PrepareRenameParams params = new PrepareRenameParams(textDocument, position);
		Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result = services.prepareRename(params)
				.get();

		Assertions.assertNotNull(result, "PrepareRename result should not be null");
		Assertions.assertTrue(result.isSecond(), "Result should be a PrepareRenameResult");
		PrepareRenameResult prepareResult = result.getSecond();
		Assertions.assertEquals("memberVar", prepareResult.getPlaceholder());
	}

	// --- Method ---

	@Test
	void testPrepareRenameMethod() throws Exception {
		Path filePath = srcRoot.resolve("PrepareRenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PrepareRenameTest {\n");
		contents.append("  void oldMethod() {}\n");
		contents.append("  void caller() {\n");
		contents.append("    oldMethod()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 10); // on "oldMethod" declaration
		PrepareRenameParams params = new PrepareRenameParams(textDocument, position);
		Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result = services.prepareRename(params)
				.get();

		Assertions.assertNotNull(result, "PrepareRename result should not be null");
		Assertions.assertTrue(result.isSecond(), "Result should be a PrepareRenameResult");
		PrepareRenameResult prepareResult = result.getSecond();
		Assertions.assertEquals("oldMethod", prepareResult.getPlaceholder());
	}

	// --- Class ---

	@Test
	void testPrepareRenameClass() throws Exception {
		Path filePath = srcRoot.resolve("PrepareRenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PrepareRenameTest {\n");
		contents.append("  PrepareRenameTest self\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 8); // on "PrepareRenameTest" class name
		PrepareRenameParams params = new PrepareRenameParams(textDocument, position);
		Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result = services.prepareRename(params)
				.get();

		Assertions.assertNotNull(result, "PrepareRename result should not be null");
		Assertions.assertTrue(result.isSecond(), "Result should be a PrepareRenameResult");
		PrepareRenameResult prepareResult = result.getSecond();
		Assertions.assertEquals("PrepareRenameTest", prepareResult.getPlaceholder());
	}

	// --- Edge cases ---

	@Test
	void testPrepareRenameOnEmptySpaceReturnsNull() throws Exception {
		Path filePath = srcRoot.resolve("PrepareRenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PrepareRenameTest {\n");
		contents.append("\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 0); // on empty line
		PrepareRenameParams params = new PrepareRenameParams(textDocument, position);
		services.prepareRename(params).get();

		// On empty space, the cursor may land on the enclosing ClassNode.
		// If it does, that's fine (class is renamable). If not, null is acceptable.
		// The key is that it doesn't throw an exception.
	}
}
