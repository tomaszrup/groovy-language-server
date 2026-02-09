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
package com.tomaszrup.groovyls.util;

import java.net.URI;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for GroovyLanguageServerUtils — verifies position/range conversion,
 * AST-node-to-LSP mappings (CompletionItemKind, SymbolKind, Location),
 * and SymbolInformation creation.
 */
class GroovyLanguageServerUtilsTests {

	// ------------------------------------------------------------------
	// createGroovyPosition
	// ------------------------------------------------------------------

	@Test
	void testCreateGroovyPositionNormal() {
		// Groovy uses 1-based lines and columns, LSP uses 0-based
		Position pos = GroovyLanguageServerUtils.createGroovyPosition(5, 10);
		Assertions.assertNotNull(pos);
		Assertions.assertEquals(4, pos.getLine(), "Groovy line 5 → LSP line 4");
		Assertions.assertEquals(9, pos.getCharacter(), "Groovy column 10 → LSP column 9");
	}

	@Test
	void testCreateGroovyPositionLineMinusOneReturnsNull() {
		Position pos = GroovyLanguageServerUtils.createGroovyPosition(-1, 5);
		Assertions.assertNull(pos, "Line -1 should return null");
	}

	@Test
	void testCreateGroovyPositionColumnMinusOneDefaultsToZero() {
		Position pos = GroovyLanguageServerUtils.createGroovyPosition(1, -1);
		Assertions.assertNotNull(pos);
		Assertions.assertEquals(0, pos.getCharacter(), "Column -1 should default to 0");
	}

	@Test
	void testCreateGroovyPositionLineZero() {
		Position pos = GroovyLanguageServerUtils.createGroovyPosition(0, 0);
		Assertions.assertNotNull(pos);
		Assertions.assertEquals(0, pos.getLine(), "Groovy line 0 → LSP line 0");
		Assertions.assertEquals(0, pos.getCharacter(), "Groovy column 0 → LSP column 0");
	}

	@Test
	void testCreateGroovyPositionLineOneColumnOne() {
		Position pos = GroovyLanguageServerUtils.createGroovyPosition(1, 1);
		Assertions.assertNotNull(pos);
		Assertions.assertEquals(0, pos.getLine(), "Groovy line 1 → LSP line 0");
		Assertions.assertEquals(0, pos.getCharacter(), "Groovy column 1 → LSP column 0");
	}

	// ------------------------------------------------------------------
	// astNodeToRange
	// ------------------------------------------------------------------

	@Test
	void testAstNodeToRangeNormal() {
		ClassNode node = new ClassNode("Test", 0, null);
		node.setLineNumber(3);
		node.setColumnNumber(5);
		node.setLastLineNumber(10);
		node.setLastColumnNumber(20);

		Range range = GroovyLanguageServerUtils.astNodeToRange(node);
		Assertions.assertNotNull(range);
		Assertions.assertEquals(2, range.getStart().getLine());
		Assertions.assertEquals(4, range.getStart().getCharacter());
		Assertions.assertEquals(9, range.getEnd().getLine());
		Assertions.assertEquals(19, range.getEnd().getCharacter());
	}

	@Test
	void testAstNodeToRangeStartLineMinusOneReturnsNull() {
		ClassNode node = new ClassNode("Test", 0, null);
		node.setLineNumber(-1);
		node.setColumnNumber(1);

		Range range = GroovyLanguageServerUtils.astNodeToRange(node);
		Assertions.assertNull(range, "Node with line -1 should return null range");
	}

	@Test
	void testAstNodeToRangeEndLineMinusOneFallsBackToStart() {
		ClassNode node = new ClassNode("Test", 0, null);
		node.setLineNumber(5);
		node.setColumnNumber(1);
		node.setLastLineNumber(-1);
		node.setLastColumnNumber(1);

		Range range = GroovyLanguageServerUtils.astNodeToRange(node);
		Assertions.assertNotNull(range);
		// End should fall back to start
		Assertions.assertEquals(range.getStart(), range.getEnd());
	}

	// ------------------------------------------------------------------
	// astNodeToCompletionItemKind
	// ------------------------------------------------------------------

	@Test
	void testCompletionItemKindForClass() {
		ClassNode node = new ClassNode("MyClass", 0, null);
		Assertions.assertEquals(CompletionItemKind.Class,
				GroovyLanguageServerUtils.astNodeToCompletionItemKind(node));
	}

	@Test
	void testCompletionItemKindForInterface() {
		ClassNode node = new ClassNode("MyInterface", 0x0200, null); // ACC_INTERFACE
		Assertions.assertEquals(CompletionItemKind.Interface,
				GroovyLanguageServerUtils.astNodeToCompletionItemKind(node));
	}

	@Test
	void testCompletionItemKindForEnum() {
		ClassNode node = new ClassNode("MyEnum", 0x4000, null); // ACC_ENUM
		Assertions.assertEquals(CompletionItemKind.Enum,
				GroovyLanguageServerUtils.astNodeToCompletionItemKind(node));
	}

	@Test
	void testCompletionItemKindForMethod() {
		MethodNode node = new MethodNode("doWork", 0, ClassHelper_OBJECT(),
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
		Assertions.assertEquals(CompletionItemKind.Method,
				GroovyLanguageServerUtils.astNodeToCompletionItemKind(node));
	}

	@Test
	void testCompletionItemKindForField() {
		FieldNode node = new FieldNode("myField", 0, ClassHelper_OBJECT(), ClassHelper_OBJECT(), null);
		Assertions.assertEquals(CompletionItemKind.Field,
				GroovyLanguageServerUtils.astNodeToCompletionItemKind(node));
	}

	// ------------------------------------------------------------------
	// astNodeToSymbolKind
	// ------------------------------------------------------------------

	@Test
	void testSymbolKindForClass() {
		ClassNode node = new ClassNode("MyClass", 0, null);
		Assertions.assertEquals(SymbolKind.Class,
				GroovyLanguageServerUtils.astNodeToSymbolKind(node));
	}

	@Test
	void testSymbolKindForInterface() {
		ClassNode node = new ClassNode("MyIface", 0x0200, null);
		Assertions.assertEquals(SymbolKind.Interface,
				GroovyLanguageServerUtils.astNodeToSymbolKind(node));
	}

	@Test
	void testSymbolKindForEnum() {
		ClassNode node = new ClassNode("MyEnum", 0x4000, null);
		Assertions.assertEquals(SymbolKind.Enum,
				GroovyLanguageServerUtils.astNodeToSymbolKind(node));
	}

	@Test
	void testSymbolKindForMethod() {
		MethodNode node = new MethodNode("run", 0, ClassHelper_OBJECT(),
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
		Assertions.assertEquals(SymbolKind.Method,
				GroovyLanguageServerUtils.astNodeToSymbolKind(node));
	}

	@Test
	void testSymbolKindForField() {
		FieldNode node = new FieldNode("count", 0, ClassHelper_OBJECT(), ClassHelper_OBJECT(), null);
		Assertions.assertEquals(SymbolKind.Field,
				GroovyLanguageServerUtils.astNodeToSymbolKind(node));
	}

	// ------------------------------------------------------------------
	// astNodeToLocation
	// ------------------------------------------------------------------

	@Test
	void testAstNodeToLocationNormal() {
		ClassNode node = new ClassNode("Test", 0, null);
		node.setLineNumber(1);
		node.setColumnNumber(1);
		node.setLastLineNumber(5);
		node.setLastColumnNumber(1);

		URI uri = URI.create("file:///test/Foo.groovy");
		Location loc = GroovyLanguageServerUtils.astNodeToLocation(node, uri);
		Assertions.assertNotNull(loc);
		Assertions.assertEquals(uri.toString(), loc.getUri());
	}

	@Test
	void testAstNodeToLocationReturnsNullForInvalidNode() {
		ClassNode node = new ClassNode("Test", 0, null);
		node.setLineNumber(-1);

		URI uri = URI.create("file:///test/Foo.groovy");
		Location loc = GroovyLanguageServerUtils.astNodeToLocation(node, uri);
		Assertions.assertNull(loc,
				"Location should be null when node's line is -1");
	}

	// ------------------------------------------------------------------
	// astNodeToSymbolInformation
	// ------------------------------------------------------------------

	@Test
	void testSymbolInformationForClassNode() {
		ClassNode node = new ClassNode("MyWidget", 0, null);
		node.setLineNumber(3);
		node.setColumnNumber(1);
		node.setLastLineNumber(10);
		node.setLastColumnNumber(1);

		URI uri = URI.create("file:///test/MyWidget.groovy");
		SymbolInformation info = GroovyLanguageServerUtils.astNodeToSymbolInformation(node, uri, "pkg");
		Assertions.assertNotNull(info);
		Assertions.assertEquals("MyWidget", info.getName());
		Assertions.assertEquals(SymbolKind.Class, info.getKind());
		Assertions.assertEquals("pkg", info.getContainerName());
	}

	@Test
	void testSymbolInformationForMethodNode() {
		MethodNode node = new MethodNode("process", 0, ClassHelper_OBJECT(),
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
		node.setLineNumber(5);
		node.setColumnNumber(3);
		node.setLastLineNumber(8);
		node.setLastColumnNumber(3);

		URI uri = URI.create("file:///test/Service.groovy");
		SymbolInformation info = GroovyLanguageServerUtils.astNodeToSymbolInformation(node, uri, "Service");
		Assertions.assertNotNull(info);
		Assertions.assertEquals("process", info.getName());
		Assertions.assertEquals(SymbolKind.Method, info.getKind());
	}

	@Test
	void testSymbolInformationForClassWithInvalidLineReturnsNull() {
		ClassNode node = new ClassNode("Bad", 0, null);
		node.setLineNumber(-1);

		URI uri = URI.create("file:///test/Bad.groovy");
		SymbolInformation info = GroovyLanguageServerUtils.astNodeToSymbolInformation(node, uri, null);
		Assertions.assertNull(info,
				"SymbolInformation should be null when location cannot be created");
	}

	// ------------------------------------------------------------------
	// syntaxExceptionToRange
	// ------------------------------------------------------------------

	@Test
	void testSyntaxExceptionToRangeNormal() {
		SyntaxException ex = new SyntaxException("test error", 3, 5, 3, 15);
		Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(ex);
		Assertions.assertNotNull(range);
		// Groovy 1-based → LSP 0-based
		Assertions.assertEquals(2, range.getStart().getLine());
		Assertions.assertEquals(4, range.getStart().getCharacter());
		Assertions.assertEquals(2, range.getEnd().getLine());
		Assertions.assertEquals(14, range.getEnd().getCharacter());
	}

	@Test
	void testSyntaxExceptionToRangeInvalidStartLine() {
		SyntaxException ex = new SyntaxException("test error", -1, 5, 3, 15);
		Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(ex);
		Assertions.assertNull(range, "Should return null when start line is -1");
	}

	@Test
	void testSyntaxExceptionToRangeInvalidEndLine() {
		SyntaxException ex = new SyntaxException("test error", 3, 5, -1, 15);
		Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(ex);
		Assertions.assertNull(range, "Should return null when end line is -1");
	}

	@Test
	void testSyntaxExceptionToRangeMultiLine() {
		SyntaxException ex = new SyntaxException("test error", 1, 1, 5, 10);
		Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(ex);
		Assertions.assertNotNull(range);
		Assertions.assertEquals(0, range.getStart().getLine());
		Assertions.assertEquals(0, range.getStart().getCharacter());
		Assertions.assertEquals(4, range.getEnd().getLine());
		Assertions.assertEquals(9, range.getEnd().getCharacter());
	}

	// ------------------------------------------------------------------
	// Helper to get ClassNode for Object type
	// ------------------------------------------------------------------

	private static ClassNode ClassHelper_OBJECT() {
		return new ClassNode("java.lang.Object", 0, null);
	}
}
