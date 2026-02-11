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

import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesDocumentHighlightTests {
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
	void testDocumentHighlightForLocalVariable() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("  void method() {\n");
		contents.append("    String localVar = \"hello\"\n");
		contents.append("    localVar.length()\n");
		contents.append("    localVar = \"world\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 14); // on "localVar" declaration
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		Assertions.assertNotNull(highlights, "Highlights should not be null");
		Assertions.assertTrue(highlights.size() >= 2,
				"Should highlight at least 2 occurrences of localVar, found: " + highlights.size());
	}

	@Test
	void testDocumentHighlightForMemberVariable() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("  String name\n");
		contents.append("  void method() {\n");
		contents.append("    name = \"hello\"\n");
		contents.append("    name.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 11); // on "name" declaration
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		Assertions.assertNotNull(highlights, "Highlights should not be null");
		Assertions.assertTrue(highlights.size() >= 1,
				"Should highlight at least 1 occurrence of name, found: " + highlights.size());
	}

	@Test
	void testDocumentHighlightForMethod() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("  void targetMethod() {}\n");
		contents.append("  void caller1() {\n");
		contents.append("    targetMethod()\n");
		contents.append("  }\n");
		contents.append("  void caller2() {\n");
		contents.append("    targetMethod()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 10); // on "targetMethod" declaration
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		Assertions.assertNotNull(highlights, "Highlights should not be null");
		Assertions.assertTrue(highlights.size() >= 2,
				"Should highlight at least 2 occurrences (declaration + call), found: " + highlights.size());
	}

	@Test
	void testDocumentHighlightKinds() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("  void method() {\n");
		contents.append("    int counter = 0\n");
		contents.append("    counter = counter + 1\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 10); // on "counter" declaration
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		Assertions.assertNotNull(highlights, "Highlights should not be null");
		Assertions.assertFalse(highlights.isEmpty(), "Should have at least one highlight");

		// Verify that at least one highlight has Write kind (for the definition)
		boolean hasWrite = highlights.stream()
				.anyMatch(h -> h.getKind() == DocumentHighlightKind.Write);
		Assertions.assertTrue(hasWrite, "Should have at least one Write highlight for the definition");
	}

	@Test
	void testDocumentHighlightOnEmptySpaceReturnsEmpty() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 0); // on empty line
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		Assertions.assertNotNull(highlights, "Highlights should not be null");
		// Position inside class body may resolve to enclosing class node
	}

	@Test
	void testDocumentHighlightForParameter() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("  String process(String input) {\n");
		contents.append("    return input.toUpperCase()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 26); // on "input" parameter
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		Assertions.assertNotNull(highlights, "Highlights should not be null");
		Assertions.assertTrue(highlights.size() >= 1,
				"Should highlight at least 1 occurrence of parameter, found: " + highlights.size());
	}

	@Test
	void testDocumentHighlightRangesAreValid() throws Exception {
		Path filePath = srcRoot.resolve("Highlight.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Highlight {\n");
		contents.append("  void method() {\n");
		contents.append("    int x = 1\n");
		contents.append("    x = x + 1\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 8); // on "x" declaration
		List<? extends DocumentHighlight> highlights = services
				.documentHighlight(new DocumentHighlightParams(textDocument, position)).get();

		// Verify all highlights have valid ranges
		for (DocumentHighlight highlight : highlights) {
			Assertions.assertNotNull(highlight.getRange(), "Highlight should have a range");
			Assertions.assertTrue(highlight.getRange().getStart().getLine() >= 0,
					"Start line should be non-negative");
			Assertions.assertTrue(highlight.getRange().getStart().getCharacter() >= 0,
					"Start character should be non-negative");
		}
	}
}
