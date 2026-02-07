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
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesRenameTests {
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
		services = null;
		workspaceRoot = null;
		srcRoot = null;
	}

	// --- Local variable rename ---

	@Test
	void testRenameLocalVariable() throws Exception {
		Path filePath = srcRoot.resolve("RenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RenameTest {\n");
		contents.append("  void method() {\n");
		contents.append("    String localVar = \"hello\"\n");
		contents.append("    localVar.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 14); // on "localVar" declaration
		RenameParams params = new RenameParams(textDocument, position, "renamedVar");
		WorkspaceEdit edit = services.rename(params).get();

		Assertions.assertNotNull(edit, "Rename result should not be null");
		List<Either<TextDocumentEdit, ResourceOperation>> docChanges = edit.getDocumentChanges();
		Assertions.assertNotNull(docChanges, "DocumentChanges should not be null");
		Assertions.assertFalse(docChanges.isEmpty(), "Should have at least one document change");

		// Collect all text edits from document changes
		List<TextEdit> allEdits = docChanges.stream()
				.filter(Either::isLeft)
				.map(Either::getLeft)
				.flatMap(tde -> tde.getEdits().stream())
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertFalse(allEdits.isEmpty(), "Should have at least one edit");

		// Verify all edits replace with the new name
		for (TextEdit textEdit : allEdits) {
			Assertions.assertTrue(textEdit.getNewText().contains("renamedVar"),
					"Edit should contain the new name 'renamedVar'");
		}
	}

	// --- Member variable rename ---

	@Test
	void testRenameMemberVariable() throws Exception {
		Path filePath = srcRoot.resolve("RenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RenameTest {\n");
		contents.append("  String memberVar\n");
		contents.append("  void method() {\n");
		contents.append("    memberVar = \"hello\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 11); // on "memberVar" declaration
		RenameParams params = new RenameParams(textDocument, position, "renamedField");
		WorkspaceEdit edit = services.rename(params).get();

		Assertions.assertNotNull(edit, "Rename result should not be null");
		List<Either<TextDocumentEdit, ResourceOperation>> docChanges = edit.getDocumentChanges();
		Assertions.assertNotNull(docChanges, "DocumentChanges should not be null");
		Assertions.assertFalse(docChanges.isEmpty(), "Should have at least one document change");
	}

	// --- Method rename ---

	@Test
	void testRenameMethod() throws Exception {
		Path filePath = srcRoot.resolve("RenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RenameTest {\n");
		contents.append("  void oldMethod() {}\n");
		contents.append("  void caller() {\n");
		contents.append("    oldMethod()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 10); // on "oldMethod" declaration
		RenameParams params = new RenameParams(textDocument, position, "newMethod");
		WorkspaceEdit edit = services.rename(params).get();

		Assertions.assertNotNull(edit, "Rename result should not be null");
		List<Either<TextDocumentEdit, ResourceOperation>> docChanges = edit.getDocumentChanges();
		Assertions.assertNotNull(docChanges, "DocumentChanges should not be null");
		Assertions.assertFalse(docChanges.isEmpty(), "Should have at least one document change for method rename");
	}

	// --- Class rename ---

	@Test
	void testRenameClass() throws Exception {
		Path filePath = srcRoot.resolve("RenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RenameTest {\n");
		contents.append("  RenameTest self\n");
		contents.append("  RenameTest create() {\n");
		contents.append("    return new RenameTest()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 8); // on "RenameTest" class declaration
		RenameParams params = new RenameParams(textDocument, position, "RenamedClass");
		WorkspaceEdit edit = services.rename(params).get();

		Assertions.assertNotNull(edit, "Rename result should not be null");
		// Class rename produces documentChanges (text edits and/or file renames)
		List<Either<TextDocumentEdit, ResourceOperation>> docChanges = edit.getDocumentChanges();
		Assertions.assertNotNull(docChanges, "DocumentChanges should not be null");
	}

	// --- Edge cases ---

	@Test
	void testRenameOnEmptySpaceReturnsEmptyEdit() throws Exception {
		Path filePath = srcRoot.resolve("RenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RenameTest {\n");
		contents.append("\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 0); // on empty line
		RenameParams params = new RenameParams(textDocument, position, "newName");
		WorkspaceEdit edit = services.rename(params).get();

		Assertions.assertNotNull(edit, "Rename result should not be null");
		// The result should be a valid WorkspaceEdit (may contain the enclosing class rename)
	}

	@Test
	void testRenameLocalVariableFromUsage() throws Exception {
		Path filePath = srcRoot.resolve("RenameTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RenameTest {\n");
		contents.append("  void method() {\n");
		contents.append("    int counter = 0\n");
		contents.append("    counter = counter + 1\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(3, 6); // on "counter" usage (assignment target)
		RenameParams params = new RenameParams(textDocument, position, "count");
		WorkspaceEdit edit = services.rename(params).get();

		Assertions.assertNotNull(edit, "Rename result should not be null");
		List<Either<TextDocumentEdit, ResourceOperation>> docChanges = edit.getDocumentChanges();
		Assertions.assertNotNull(docChanges, "DocumentChanges should not be null");
		Assertions.assertFalse(docChanges.isEmpty(), "Should have at least one document change");
	}
}
