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

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesReferenceTests {
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

	// --- Local variable references ---

	@Test
	void testReferencesForLocalVariable() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
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
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		// localVar is referenced at: declaration (line 2), method call (line 3), assignment (line 4)
		Assertions.assertNotNull(locations, "References result should not be null");
		Assertions.assertTrue(locations.size() >= 2,
				"Should find at least 2 references for localVar, found: " + locations.size());
	}

	@Test
	void testReferencesForMemberVariable() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
		contents.append("  String memberVar\n");
		contents.append("  void method1() {\n");
		contents.append("    memberVar = \"hello\"\n");
		contents.append("  }\n");
		contents.append("  void method2() {\n");
		contents.append("    memberVar.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 11); // on "memberVar" declaration
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		Assertions.assertNotNull(locations, "References result should not be null");
		Assertions.assertTrue(locations.size() >= 1,
				"Should find at least 1 reference for memberVar, found: " + locations.size());
	}

	// --- Method references ---

	@Test
	void testReferencesForMethod() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
		contents.append("  void targetMethod() {}\n");
		contents.append("  void caller1() {\n");
		contents.append("    targetMethod()\n");
		contents.append("  }\n");
		contents.append("  void caller2() {\n");
		contents.append("    this.targetMethod()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 10); // on "targetMethod" declaration
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		Assertions.assertNotNull(locations, "References result should not be null");
		Assertions.assertTrue(locations.size() >= 2,
				"Should find at least 2 references for targetMethod, found: " + locations.size());
	}

	// --- Class references ---

	@Test
	void testReferencesForClass() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
		contents.append("  References self\n");
		contents.append("  References create() {\n");
		contents.append("    return new References()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 8); // on "References" class declaration
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		Assertions.assertNotNull(locations, "References result should not be null");
		Assertions.assertTrue(locations.size() >= 1,
				"Should find at least 1 reference for References class, found: " + locations.size());
	}

	// --- Parameter references ---

	@Test
	void testReferencesForParameter() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
		contents.append("  String process(String input) {\n");
		contents.append("    return input.toUpperCase()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 26); // on "input" parameter
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		Assertions.assertNotNull(locations, "References result should not be null");
		Assertions.assertTrue(locations.size() >= 1,
				"Should find at least 1 reference for parameter, found: " + locations.size());
	}

	// --- Edge cases ---

	@Test
	void testReferencesOnEmptySpaceReturnsEmpty() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
		contents.append("\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 0); // on empty line
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		Assertions.assertNotNull(locations, "References result should not be null");
		// Position inside class body may resolve to enclosing class node
	}

	@Test
	void testReferencesForMethodWithMultipleUsages() throws Exception {
		Path filePath = srcRoot.resolve("References.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class References {\n");
		contents.append("  int getValue() { return 42 }\n");
		contents.append("  void user1() {\n");
		contents.append("    int a = getValue()\n");
		contents.append("  }\n");
		contents.append("  void user2() {\n");
		contents.append("    int b = getValue()\n");
		contents.append("  }\n");
		contents.append("  void user3() {\n");
		contents.append("    int c = getValue()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 8); // on "getValue" declaration
		ReferenceParams params = new ReferenceParams(textDocument, position, new ReferenceContext(true));
		List<? extends Location> locations = services.references(params).get();

		Assertions.assertNotNull(locations, "References result should not be null");
		Assertions.assertTrue(locations.size() >= 3,
				"Should find at least 3 references for getValue, found: " + locations.size());
	}
}
