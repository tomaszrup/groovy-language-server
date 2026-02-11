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
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesInlayHintTests {
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

	// --- Type hints ---

	@Test
	void testTypeHintForDefWithStringLiteral() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  void method() {\n");
		contents.append("    def x = \"hello\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(4, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		// Should have at least one type hint for the def variable
		List<InlayHint> typeHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Type)
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertFalse(typeHints.isEmpty(), "Expected at least one type hint for def variable");

		// The type hint should indicate String
		InlayHint typeHint = typeHints.stream()
				.filter(h -> h.getLabel().getLeft().contains("String"))
				.findFirst()
				.orElse(null);
		Assertions.assertNotNull(typeHint, "Expected a type hint containing 'String'");
		Assertions.assertEquals(InlayHintKind.Type, typeHint.getKind());
	}

	@Test
	void testTypeHintForDefWithIntegerLiteral() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  void method() {\n");
		contents.append("    def count = 42\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(4, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		List<InlayHint> typeHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Type)
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertFalse(typeHints.isEmpty(), "Expected at least one type hint for def variable");
	}

	@Test
	void testNoTypeHintForExplicitlyTypedVariable() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  void method() {\n");
		contents.append("    String x = \"hello\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(4, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		List<InlayHint> typeHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Type)
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertTrue(typeHints.isEmpty(), "Should not show type hints for explicitly typed variables");
	}

	// --- Parameter hints ---

	@Test
	void testParameterHintsOnMethodCall() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  void greet(String name, int count) {}\n");
		contents.append("  void caller() {\n");
		contents.append("    greet(\"world\", 3)\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(5, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		List<InlayHint> paramHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Parameter)
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertTrue(paramHints.size() >= 2,
				"Expected at least 2 parameter hints, got " + paramHints.size());

		// Check that we have hints for "name:" and "count:"
		boolean hasNameHint = paramHints.stream()
				.anyMatch(h -> h.getLabel().getLeft().equals("name:"));
		boolean hasCountHint = paramHints.stream()
				.anyMatch(h -> h.getLabel().getLeft().equals("count:"));
		Assertions.assertTrue(hasNameHint, "Expected parameter hint 'name:'");
		Assertions.assertTrue(hasCountHint, "Expected parameter hint 'count:'");
	}

	@Test
	void testNoParameterHintWhenArgMatchesParamName() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  void greet(String name) {}\n");
		contents.append("  void caller() {\n");
		contents.append("    def name = \"world\"\n");
		contents.append("    greet(name)\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(6, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		// Filter for parameter hints with "name:" label
		List<InlayHint> nameParamHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Parameter)
				.filter(h -> h.getLabel().getLeft().equals("name:"))
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertTrue(nameParamHints.isEmpty(),
				"Should not show parameter hint when argument name matches parameter name");
	}

	@Test
	void testNoParameterHintForNoArgMethod() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  void doSomething() {}\n");
		contents.append("  void caller() {\n");
		contents.append("    doSomething()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(5, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		List<InlayHint> paramHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Parameter)
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertTrue(paramHints.isEmpty(),
				"Should not show parameter hints for zero-arg method calls");
	}

	@Test
	void testParameterHintsOnConstructorCall() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class MyClass {\n");
		contents.append("  MyClass(String label, int priority) {}\n");
		contents.append("}\n");
		contents.append("class InlayHints {\n");
		contents.append("  void caller() {\n");
		contents.append("    new MyClass(\"test\", 5)\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(7, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		List<InlayHint> paramHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Parameter)
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertTrue(paramHints.size() >= 2,
				"Expected at least 2 parameter hints for constructor call, got " + paramHints.size());

		boolean hasLabelHint = paramHints.stream()
				.anyMatch(h -> h.getLabel().getLeft().equals("label:"));
		boolean hasPriorityHint = paramHints.stream()
				.anyMatch(h -> h.getLabel().getLeft().equals("priority:"));
		Assertions.assertTrue(hasLabelHint, "Expected parameter hint 'label:'");
		Assertions.assertTrue(hasPriorityHint, "Expected parameter hint 'priority:'");
	}

	@Test
	void testEmptyFileReturnsNoHints() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class Empty {}";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(0, 14));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		Assertions.assertTrue(hints.isEmpty(), "Expected no hints for an empty class");
	}

	@Test
	void testTypeHintForDefWithMethodCallReturn() throws Exception {
		Path filePath = srcRoot.resolve("InlayHints.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class InlayHints {\n");
		contents.append("  String getName() { return \"hello\" }\n");
		contents.append("  void method() {\n");
		contents.append("    def result = getName()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Range range = new Range(new Position(0, 0), new Position(5, 1));
		List<InlayHint> hints = services.inlayHint(new InlayHintParams(textDocument, range)).get();

		List<InlayHint> typeHints = hints.stream()
				.filter(h -> h.getKind() == InlayHintKind.Type)
				.filter(h -> h.getLabel().getLeft().contains("String"))
				.collect(java.util.stream.Collectors.toList());
		Assertions.assertFalse(typeHints.isEmpty(),
				"Expected type hint 'String' for def variable initialized from method call");
	}
}
