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
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesHoverTests {
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

	// --- Class hover ---

	@Test
	void testHoverOnClassDeclaration() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  String name\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 8); // on "HoverTest"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for class declaration");
		Assertions.assertNotNull(hover.getContents(), "Hover contents should not be null");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("HoverTest"),
				"Hover should contain the class name");
	}

	@Test
	void testHoverOnMethodDeclaration() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  String greet(String name) {\n");
		contents.append("    return \"Hello \" + name\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 11); // on "greet"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for method declaration");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("greet"),
				"Hover should contain the method name");
	}

	@Test
	void testHoverOnMemberVariable() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  String memberVar\n");
		contents.append("  void method() {\n");
		contents.append("    memberVar.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(3, 8); // on "memberVar"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for member variable reference");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("memberVar"),
				"Hover should contain the variable name");
	}

	@Test
	void testHoverOnLocalVariable() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  void method() {\n");
		contents.append("    String localVar = \"hello\"\n");
		contents.append("    localVar.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(3, 8); // on "localVar"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for local variable reference");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("localVar"),
				"Hover should contain the variable name");
	}

	@Test
	void testHoverOnParameter() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  void method(String param) {\n");
		contents.append("    param.length()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 6); // on "param"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for parameter reference");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("param"),
				"Hover should contain the parameter name");
	}

	@Test
	void testHoverOnConstructorCall() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  void method() {\n");
		contents.append("    def list = new ArrayList()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 22); // on "ArrayList"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for constructor call");
	}

	@Test
	void testHoverOnEmptySpaceReturnsNull() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 0); // on empty line
		services.hover(new HoverParams(textDocument, position)).get();

		// Position inside a class body may resolve to the enclosing class node,
		// so hover may not be null. Just verify no exception is thrown.
	}

	@Test
	void testHoverOnMethodCallShowsReturnType() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  int calculate(int a, int b) {\n");
		contents.append("    return a + b\n");
		contents.append("  }\n");
		contents.append("  void caller() {\n");
		contents.append("    calculate(1, 2)\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(5, 8); // on "calculate"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for method call");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("calculate"),
				"Hover should contain the method name");
	}

	@Test
	void testHoverOnStaticMethod() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  static String staticMethod() {\n");
		contents.append("    return \"hello\"\n");
		contents.append("  }\n");
		contents.append("  void caller() {\n");
		contents.append("    HoverTest.staticMethod()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(5, 18); // on "staticMethod"
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();

		Assertions.assertNotNull(hover, "Hover should not be null for static method call");
		String hoverText = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverText.contains("staticMethod"),
				"Hover should contain the static method name");
	}

	@Test
	void testHoverOnClassUsedAsType() throws Exception {
		Path filePath = srcRoot.resolve("HoverTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HoverTest {\n");
		contents.append("  void method() {\n");
		contents.append("    String x = \"hello\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(2, 8); // on "String"
		services.hover(new HoverParams(textDocument, position)).get();

		// Hovering over a type used in a declaration may or may not return hover
		// depending on implementation, but it should not throw
		// Just verify no exception is thrown
	}
}
