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
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.compiler;

import java.net.URI;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Unit tests for {@link CompilationOrchestrator}: placeholder injection,
 * restoration, and AST visiting methods.
 */
class CompilationOrchestratorTests {

	private CompilationOrchestrator orchestrator;
	private FileContentsTracker tracker;

	private static final URI TEST_URI = URI.create("file:///test.groovy");

	@BeforeEach
	void setup() {
		orchestrator = new CompilationOrchestrator();
		tracker = new FileContentsTracker();
	}

	// ------------------------------------------------------------------
	// injectCompletionPlaceholder
	// ------------------------------------------------------------------

	@Test
	void testInjectCompletionPlaceholderBasic() {
		String source = "class Foo {\n  void bar() {\n    \n  }\n}";
		tracker.setContents(TEST_URI, source);

		String original = orchestrator.injectCompletionPlaceholder(
				new ASTNodeVisitor(), tracker, TEST_URI, new Position(2, 4));

		Assertions.assertNotNull(original);
		Assertions.assertEquals(source, original);

		String modified = tracker.getContents(TEST_URI);
		Assertions.assertNotNull(modified);
		// Placeholder 'a' should be injected at the cursor position
		Assertions.assertTrue(modified.contains("a"), "Should inject placeholder");
		Assertions.assertNotEquals(source, modified);
	}

	@Test
	void testInjectCompletionPlaceholderForConstructorCall() {
		// Line before offset matches "new \\w*$" pattern
		String source = "class Foo {\n  void bar() {\n    new Foo\n  }\n}";
		tracker.setContents(TEST_URI, source);

		String original = orchestrator.injectCompletionPlaceholder(
				new ASTNodeVisitor(), tracker, TEST_URI, new Position(2, 11));

		Assertions.assertNotNull(original);
		String modified = tracker.getContents(TEST_URI);
		// For constructor calls, placeholder should be "a()"
		Assertions.assertTrue(modified.contains("a()"),
				"Should inject 'a()' placeholder for constructor calls");
	}

	@Test
	void testInjectCompletionPlaceholderNullSource() {
		// No contents set for the URI
		String result = orchestrator.injectCompletionPlaceholder(
				new ASTNodeVisitor(), tracker, TEST_URI, new Position(0, 0));
		Assertions.assertNull(result);
	}

	@Test
	void testInjectCompletionPlaceholderOffsetBeyondSource() {
		tracker.setContents(TEST_URI, "hi");

		String result = orchestrator.injectCompletionPlaceholder(
				new ASTNodeVisitor(), tracker, TEST_URI, new Position(99, 0));
		// Should return null for invalid offset
		Assertions.assertNull(result);
	}

	// ------------------------------------------------------------------
	// injectSignatureHelpPlaceholder
	// ------------------------------------------------------------------

	@Test
	void testInjectSignatureHelpPlaceholder() {
		String source = "class Foo {\n  void bar() {\n    method(\n  }\n}";
		tracker.setContents(TEST_URI, source);

		String original = orchestrator.injectSignatureHelpPlaceholder(
				tracker, TEST_URI, new Position(2, 11));

		Assertions.assertNotNull(original);
		Assertions.assertEquals(source, original);

		String modified = tracker.getContents(TEST_URI);
		Assertions.assertNotNull(modified);
		// Should inject ')' at the cursor position
		Assertions.assertTrue(modified.length() > source.length());
	}

	@Test
	void testInjectSignatureHelpPlaceholderNullSource() {
		String result = orchestrator.injectSignatureHelpPlaceholder(
				tracker, TEST_URI, new Position(0, 0));
		Assertions.assertNull(result);
	}

	@Test
	void testInjectSignatureHelpPlaceholderInvalidOffset() {
		tracker.setContents(TEST_URI, "hi");
		String result = orchestrator.injectSignatureHelpPlaceholder(
				tracker, TEST_URI, new Position(99, 0));
		Assertions.assertNull(result);
	}

	// ------------------------------------------------------------------
	// restoreDocumentSource
	// ------------------------------------------------------------------

	@Test
	void testRestoreDocumentSource() {
		String original = "class Foo {}";
		tracker.setContents(TEST_URI, "class Foo { modified }");

		orchestrator.restoreDocumentSource(tracker, TEST_URI, original);

		Assertions.assertEquals(original, tracker.getContents(TEST_URI));
	}

	// ------------------------------------------------------------------
	// visitAST
	// ------------------------------------------------------------------

	@Test
	void testVisitASTNullCompilationUnit() {
		ASTNodeVisitor result = orchestrator.visitAST(null);
		Assertions.assertNull(result);
	}

	@Test
	void testVisitASTIncrementalWithNullExistingVisitor() {
		// Falls back to full visit when existing visitor is null
		ASTNodeVisitor result = orchestrator.visitAST(null, null, java.util.Collections.emptySet());
		Assertions.assertNull(result, "Should return null when compilation unit is null");
	}

	@Test
	void testVisitASTIncrementalWithNullCompilationUnit() {
		ASTNodeVisitor existing = new ASTNodeVisitor();
		ASTNodeVisitor result = orchestrator.visitAST(null, existing, java.util.Collections.emptySet());
		Assertions.assertSame(existing, result, "Should return existing visitor when CU is null");
	}

	// ------------------------------------------------------------------
	// compile
	// ------------------------------------------------------------------

	@Test
	void testCompileNullCompilationUnit() {
		var result = orchestrator.compile(null, java.nio.file.Paths.get("/test"));
		Assertions.assertNull(result);
	}
}
