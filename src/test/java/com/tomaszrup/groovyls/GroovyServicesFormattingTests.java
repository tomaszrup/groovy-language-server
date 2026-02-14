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
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesFormattingTests {
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

	/**
	 * Apply a list of TextEdits to the original text and return the result.
	 * Edits are applied in reverse order (bottom-to-top) to preserve positions.
	 */
	private String applyEdits(String original, List<? extends TextEdit> edits) {
		// Normalize to \n for offset calculations (matches what FormattingProvider does)
		String text = original.replace("\r\n", "\n").replace("\r", "\n");
		// Apply edits in reverse document order to preserve earlier positions
		List<? extends TextEdit> sorted = new java.util.ArrayList<>(edits);
		sorted.sort((a, b) -> {
			int lineCmp = Integer.compare(b.getRange().getStart().getLine(),
					a.getRange().getStart().getLine());
			if (lineCmp != 0) return lineCmp;
			return Integer.compare(b.getRange().getStart().getCharacter(),
					a.getRange().getStart().getCharacter());
		});
		for (TextEdit edit : sorted) {
			Range range = edit.getRange();
			int startOffset = positionToOffset(text, range.getStart());
			int endOffset = positionToOffset(text, range.getEnd());
			text = text.substring(0, startOffset) + edit.getNewText() + text.substring(endOffset);
		}
		return text;
	}

	private int positionToOffset(String text, Position pos) {
		int line = 0;
		int offset = 0;
		while (line < pos.getLine() && offset < text.length()) {
			if (text.charAt(offset) == '\n') {
				line++;
			}
			offset++;
		}
		return offset + pos.getCharacter();
	}

	// --- Trailing whitespace ---

	@Test
	void testRemovesTrailingWhitespace() throws Exception {
		Path filePath = srcRoot.resolve("Formatting.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class Formatting {   \n    void method() {  \n    }  \n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		for (String line : formatted.split("\\n", -1)) {
			if (!line.isEmpty()) {
				Assertions.assertFalse(line.endsWith(" "), "Line should not end with trailing space: [" + line + "]");
				Assertions.assertFalse(line.endsWith("\t"),
						"Line should not end with trailing tab: [" + line + "]");
			}
		}
	}

	// --- Indentation with spaces ---

	@Test
	void testNormalizesTabsToSpaces() throws Exception {
		Path filePath = srcRoot.resolve("TabToSpaces.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class TabToSpaces {\n\tvoid method() {\n\t\tprintln 'hello'\n\t}\n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		Assertions.assertFalse(formatted.contains("\t"), "Should not contain tabs when insertSpaces=true");
		Assertions.assertTrue(formatted.contains("    void method()"),
				"Should indent with 4 spaces");
	}

	// --- Indentation with tabs ---

	@Test
	void testNormalizesSpacesToTabs() throws Exception {
		Path filePath = srcRoot.resolve("SpacesToTab.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class SpacesToTab {\n    void method() {\n        println 'hello'\n    }\n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, false));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		Assertions.assertTrue(formatted.contains("\tvoid method()"),
				"Should indent with tab");
		Assertions.assertTrue(formatted.contains("\t\tprintln"),
				"Should indent nested with 2 tabs");
	}

	// --- Final newline ---

	@Test
	void testEnsuresFinalNewline() throws Exception {
		Path filePath = srcRoot.resolve("NoNewline.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class NoNewline {\n}";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = applyEdits(contents, edits);
		Assertions.assertTrue(formatted.endsWith("\n"), "File should end with a newline");
		Assertions.assertFalse(formatted.endsWith("\n\n"), "File should not end with multiple newlines");
	}

	// --- Blank line collapsing ---

	@Test
	void testCollapsesExcessiveBlankLines() throws Exception {
		Path filePath = srcRoot.resolve("BlankLines.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class BlankLines {\n\n\n\n\n  void a() {}\n\n\n\n\n  void b() {}\n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		// There should be no 3+ consecutive blank lines
		Assertions.assertFalse(formatted.contains("\n\n\n\n"),
				"Should not have 4 consecutive newlines (3+ blank lines)");
	}

	// --- Comma spacing ---

	@Test
	void testAddsSpaceAfterComma() throws Exception {
		Path filePath = srcRoot.resolve("CommaSpacing.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class CommaSpacing {\n    void method(int a,int b,int c) {\n        def list = [1,2,3]\n    }\n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		Assertions.assertTrue(formatted.contains("int a, int b, int c"),
				"Should have space after commas in parameter list");
		Assertions.assertTrue(formatted.contains("[1, 2, 3]"),
				"Should have space after commas in list literal");
	}

	// --- Keyword spacing ---

	@Test
	void testAddsSpaceAfterControlKeywords() throws Exception {
		Path filePath = srcRoot.resolve("KeywordSpacing.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class KeywordSpacing {\n");
		contents.append("    void method() {\n");
		contents.append("        if(true) {}\n");
		contents.append("        for(int i = 0; i < 10; i++) {}\n");
		contents.append("        while(false) {}\n");
		contents.append("    }\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		Assertions.assertTrue(formatted.contains("if (true)"),
				"Should have space after 'if'");
		Assertions.assertTrue(formatted.contains("for (int"),
				"Should have space after 'for'");
		Assertions.assertTrue(formatted.contains("while (false)"),
				"Should have space after 'while'");
	}

	// --- Already formatted ---

	@Test
	void testNoEditsForAlreadyFormattedFile() throws Exception {
		Path filePath = srcRoot.resolve("AlreadyFormatted.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class AlreadyFormatted {\n    void method() {\n        println 'hello'\n    }\n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertTrue(edits.isEmpty(),
				"Should produce no edits for already-formatted file");
	}

	// --- Brace spacing ---

	@Test
	void testAddsSpaceBeforeOpeningBrace() throws Exception {
		Path filePath = srcRoot.resolve("BraceSpacing.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class BraceSpacing{\n    void method(){\n        if (true){\n        }\n    }\n}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();
		Assertions.assertTrue(formatted.contains("BraceSpacing {"),
				"Should have space before opening brace after class name");
		Assertions.assertTrue(formatted.contains("method() {"),
				"Should have space before opening brace after method");
	}

	// --- Empty file ---

	@Test
	void testEmptyFileReturnsNoEdits() throws Exception {
		Path filePath = srcRoot.resolve("Empty.groovy");
		String uri = filePath.toUri().toString();
		String contents = "";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertTrue(edits.isEmpty(), "Should produce no edits for empty file");
	}

	// --- Windows line endings ---

	@Test
	void testHandlesWindowsLineEndings() throws Exception {
		Path filePath = srcRoot.resolve("WindowsCRLF.groovy");
		String uri = filePath.toUri().toString();
		// Source with \r\n line endings and trailing whitespace
		String contents = "class WindowsCRLF {   \r\n  void method() {  \r\n  }\r\n}\r\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(2, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits for CRLF content");
		String formatted = edits.get(0).getNewText();
		// Should not contain \r
		Assertions.assertFalse(formatted.contains("\r"), "Formatted text should not contain \\r");
		// Should have removed trailing whitespace
		Assertions.assertTrue(formatted.contains("class WindowsCRLF {"),
				"Should remove trailing whitespace after class declaration");
	}

	@Test
	void testNoEditsForAlreadyFormattedCRLF() throws Exception {
		Path filePath = srcRoot.resolve("AlreadyCRLF.groovy");
		String uri = filePath.toUri().toString();
		// Already formatted content that just happens to use \r\n
		String contents = "class AlreadyCRLF {\r\n    void method() {\r\n        println 'hello'\r\n    }\r\n}\r\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertTrue(edits.isEmpty(),
				"Should produce no edits when content is already formatted (ignoring line endings)");
	}

	// --- Spock test formatting ---

	@Test
	void testFormatsSpockTestFile() throws Exception {
		Path filePath = srcRoot.resolve("SampleSpec.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SampleSpec {\r\n");
		contents.append("  def \"should do something\"(){\r\n");
		contents.append("    given:   \r\n");
		contents.append("    def x = 1\r\n");
		contents.append("\r\n");
		contents.append("    when:\r\n");
		contents.append("    def result = x + 1\r\n");
		contents.append("\r\n");
		contents.append("    then:\r\n");
		contents.append("    result == 2\r\n");
		contents.append("  }\r\n");
		contents.append("}\r\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits for Spock test");
		String formatted = edits.get(0).getNewText();
		// Should have fixed missing space before brace
		Assertions.assertTrue(formatted.contains("\"should do something\"() {"),
				"Should have space before opening brace in test method");
		// Should have removed trailing whitespace from "given:   "
		Assertions.assertTrue(formatted.contains("        given:\n"),
				"Should remove trailing whitespace from 'given:' label");
	}

	// --- Structural reindentation ---

	@Test
	void testFixesBadlyIndentedSpockTest() throws Exception {
		Path filePath = srcRoot.resolve("BowlingSpec.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class BowlingSpec {\n");
		contents.append("    def \"should handle strikes correctly\"(int pins,\n");
		contents.append("    int expectedScore) {\n");
		contents.append("        given:\n");
		contents.append("        BowlingGame game = new BowlingGame()\n");
		contents.append("\n");
		contents.append("        when:\n");
		contents.append("        game.roll(pins)\n");
		contents.append("\n");
		contents.append("    then:\n");
		contents.append("        game.score() == expectedScore\n");
		contents.append("\n");
		contents.append("        where:\n");
		contents.append("     pins || expectedScore\n");
		contents.append("        10   || 10\n");
		contents.append("        5    || 5\n");
		contents.append("        0    || 0\n");
		contents.append("    }\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = edits.get(0).getNewText();

		// All lines inside the method body should be at depth 2 (8 spaces)
		String[] lines = formatted.split("\\n");
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) continue;
			// Class declaration and closing brace at depth 0
			if (trimmed.startsWith("class ") || trimmed.equals("}")) continue;
			// Method signature lines at depth 1
			if (trimmed.startsWith("def \"") || trimmed.startsWith("int expectedScore")) {
				Assertions.assertTrue(line.startsWith("    ") && !line.startsWith("        "),
						"Method signature should be at depth 1: [" + line + "]");
				continue;
			}
			// Closing brace of method at depth 1
			if (line.trim().equals("}") && line.startsWith("    ")) continue;
			// Everything else inside method body at depth 2
			if (!trimmed.equals("}")) {
				Assertions.assertTrue(line.startsWith("        "),
						"Method body content should be at depth 2 (8 spaces): [" + line + "]");
			}
		}
	}

	@Test
	void testFormatsSpockLabelsAtMethodBodyDepth() throws Exception {
		Path filePath = srcRoot.resolve("SpockLabelsIndentationSpec.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SpockLabelsIndentationSpec {\n");
		contents.append("    def \"formats labels\"(){\n");
		contents.append("            given:\n");
		contents.append("        def x=1\n");
		contents.append("      when:\n");
		contents.append("        def y = x + 1\n");
		contents.append("   then:\n");
		contents.append("        y == 2\n");
		contents.append("     where:\n");
		contents.append("        x || y\n");
		contents.append("        1 || 2\n");
		contents.append("    }\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = applyEdits(contents.toString(), edits);
		String expected = "class SpockLabelsIndentationSpec {\n"
				+ "    def \"formats labels\"() {\n"
				+ "        given:\n"
				+ "        def x=1\n"
				+ "        when:\n"
				+ "        def y = x + 1\n"
				+ "        then:\n"
				+ "        y == 2\n"
				+ "        where:\n"
				+ "        x || y\n"
				+ "        1 || 2\n"
				+ "    }\n"
				+ "}\n";
		Assertions.assertEquals(expected, formatted);
		Assertions.assertTrue(formatted.contains("\n        given:\n"),
				"'given:' label should be at method-body depth (8 spaces)");
		Assertions.assertTrue(formatted.contains("\n        when:\n"),
				"'when:' label should be at method-body depth (8 spaces)");
		Assertions.assertTrue(formatted.contains("\n        then:\n"),
				"'then:' label should be at method-body depth (8 spaces)");
		Assertions.assertTrue(formatted.contains("\n        where:\n"),
				"'where:' label should be at method-body depth (8 spaces)");
	}

	@Test
	void testFixesWrongIndentation() throws Exception {
		Path filePath = srcRoot.resolve("WrongIndent.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class WrongIndent {\n");
		contents.append("void method() {\n");
		contents.append("println 'hello'\n");
		contents.append("if (true) {\n");
		contents.append("println 'nested'\n");
		contents.append("}\n");
		contents.append("}\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits");
		String formatted = applyEdits(contents.toString(), edits);

		// Verify structural indentation
		String expected = "class WrongIndent {\n"
				+ "    void method() {\n"
				+ "        println 'hello'\n"
				+ "        if (true) {\n"
				+ "            println 'nested'\n"
				+ "        }\n"
				+ "    }\n"
				+ "}\n";
		Assertions.assertEquals(expected, formatted);
	}

	@Test
	void testFormattingOrganizesImportsByDefault() throws Exception {
		Path filePath = srcRoot.resolve("FormatOrganizeImports.groovy");
		String uri = filePath.toUri().toString();
		String contents = "import java.util.List\n"
				+ "import java.util.ArrayList\n"
				+ "class FormatOrganizeImports {\n"
				+ "    ArrayList<String> list = new ArrayList<>()\n"
				+ "}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce edits for import organization");
		String formatted = applyEdits(contents, edits);
		String expected = "import java.util.ArrayList\n"
				+ "\n"
				+ "class FormatOrganizeImports {\n"
				+ "    ArrayList<String> list = new ArrayList<>()\n"
				+ "}\n";
		Assertions.assertEquals(expected, formatted);
	}

	@Test
	void testFormattingDoesNotOrganizeImportsWhenDisabled() throws Exception {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject formatting = new JsonObject();
		formatting.addProperty("organizeImports", false);
		groovy.add("formatting", formatting);
		settings.add("groovy", groovy);
		services.didChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationParams(settings));

		Path filePath = srcRoot.resolve("FormatOrganizeImportsDisabled.groovy");
		String uri = filePath.toUri().toString();
		String contents = "import java.util.List\n"
				+ "import java.util.ArrayList\n"
				+ "class FormatOrganizeImportsDisabled {\n"
				+ "    ArrayList<String> list = new ArrayList<>()\n"
				+ "}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertTrue(edits.isEmpty(), "Should not organize imports when disabled");
	}

	@Test
	void testFormattingDoesNotAddExtraBlankLineAfterImportsWhenAlreadyPresent() throws Exception {
		Path filePath = srcRoot.resolve("FormatNoExtraImportBlankLine.groovy");
		String uri = filePath.toUri().toString();
		String contents = "package com.example\n"
				+ "\n"
				+ "import spock.lang.Specification\n"
				+ "\n"
				+ "class CalculatorSpec extends Specification {\n"
				+ "}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		String formatted = applyEdits(contents, edits);
		String expected = "package com.example\n"
				+ "\n"
				+ "import spock.lang.Specification\n"
				+ "\n"
				+ "class CalculatorSpec extends Specification {\n"
				+ "}\n";
		Assertions.assertEquals(expected, formatted);
	}

	@Test
	void testFormattingCollapsesMultipleBlankLinesAfterImportsToOne() throws Exception {
		Path filePath = srcRoot.resolve("FormatCollapseImportBlankLines.groovy");
		String uri = filePath.toUri().toString();
		String contents = "package com.example\n"
				+ "\n"
				+ "import spock.lang.Specification\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "class CalculatorSpec extends Specification {\n"
				+ "}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		String formatted = applyEdits(contents, edits);
		String expected = "package com.example\n"
				+ "\n"
				+ "import spock.lang.Specification\n"
				+ "\n"
				+ "class CalculatorSpec extends Specification {\n"
				+ "}\n";
		Assertions.assertEquals(expected, formatted);
	}

	@Test
	void testIndentsWrappedMethodChainLineInSpockWhenBlock() throws Exception {
		Path filePath = srcRoot.resolve("WrappedMethodChainSpec.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class WrappedMethodChainSpec extends Specification {\n"
				+ "    def \"should divide two numbers correctly\"() {\n"
				+ "        given:\n"
				+ "        Calculator calculator = new Calculator()\n"
				+ "\n"
				+ "        when:\n"
				+ "        double result = calculator\n"
				+ "        .divide(a, b)\n"
				+ "\n"
				+ "        then:\n"
				+ "        result == expected\n"
				+ "\n"
				+ "        where:\n"
				+ "        a | b || expected\n"
				+ "        10 | 2 || 5.0\n"
				+ "    }\n"
				+ "}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits for wrapped method chain");
		String formatted = applyEdits(contents, edits);
		Assertions.assertTrue(formatted.contains("\n            .divide(a, b)\n"),
				"Wrapped method chain line should be indented one level deeper than assignment line");
	}

	@Test
	void testIndentsMultilineWhereListBlock() throws Exception {
		Path filePath = srcRoot.resolve("WhereListIndentSpec.groovy");
		String uri = filePath.toUri().toString();
		String contents = "class WhereListIndentSpec extends Specification {\n"
				+ "    def \"should calculate area\"(String shape, int width, int height, int expectedArea) {\n"
				+ "        expect:\n"
				+ "        calculateArea(shape, width, height) == expectedArea\n"
				+ "\n"
				+ "        where:\n"
				+ "        [shape, width, height, expectedArea] << [\n"
				+ "        [\"rectangle\", 5, 3, 15],\n"
				+ "        [\"square\", 4, 4, 16],\n"
				+ "        [\"rectangle\", 10, 2, 20]\n"
				+ "        ]\n"
				+ "    }\n"
				+ "}\n";
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents);
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		DocumentFormattingParams params = new DocumentFormattingParams(
				new TextDocumentIdentifier(uri), new FormattingOptions(4, true));
		List<? extends TextEdit> edits = services.formatting(params).get();

		Assertions.assertFalse(edits.isEmpty(), "Should produce formatting edits for multiline where list");
		String formatted = applyEdits(contents, edits);
		Assertions.assertTrue(formatted.contains("\n            [\"rectangle\", 5, 3, 15],\n"),
				"First list row should be indented one level deeper than the opening '[' line");
		Assertions.assertTrue(formatted.contains("\n            [\"square\", 4, 4, 16],\n"),
				"Second list row should be indented one level deeper than the opening '[' line");
		Assertions.assertTrue(formatted.contains("\n            [\"rectangle\", 10, 2, 20]\n"),
				"Third list row should be indented one level deeper than the opening '[' line");
		Assertions.assertTrue(formatted.contains("\n        ]\n"),
				"Closing ']' should align with the opening '[' line indentation");
	}
}
