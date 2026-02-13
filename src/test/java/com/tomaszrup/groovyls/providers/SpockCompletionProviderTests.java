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
package com.tomaszrup.groovyls.providers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.GroovyServices;
import com.tomaszrup.groovyls.TestLanguageClient;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for SpockCompletionProvider — verifies that Spock-specific completions
 * (block labels, assertion helpers, feature method snippets, lifecycle methods,
 * annotations) are offered in the right contexts inside Spock specifications.
 */
class SpockCompletionProviderTests {
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
		if (services != null) {
			services.setWorkspaceRoot(null);
		}
		services = null;
		workspaceRoot = null;
		srcRoot = null;
	}

	// ------------------------------------------------------------------
	// Block label completions inside a feature method
	// ------------------------------------------------------------------

	@Test
	void testBlockLabelCompletionsInsideFeatureMethod() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec1.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec1 extends Specification {\n");
		contents.append("  def \"should test something\"() {\n");
		contents.append("    \n"); // line 3 — cursor here
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		Assertions.assertNotNull(result);

		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();
		Assertions.assertNotNull(items);

		// Block label completions should include given:, when:, then:, expect:, cleanup:, and:
		List<String> blockLabels = items.stream()
				.filter(i -> i.getDetail() != null && i.getDetail().contains("Spock"))
				.map(CompletionItem::getLabel)
				.collect(Collectors.toList());

		Assertions.assertTrue(blockLabels.stream().anyMatch(l -> l.contains("given")),
				"Should offer 'given:' block label, got: " + blockLabels);
		Assertions.assertTrue(blockLabels.stream().anyMatch(l -> l.contains("when")),
				"Should offer 'when:' related completions, got: " + blockLabels);
		Assertions.assertTrue(blockLabels.stream().anyMatch(l -> l.contains("then")),
				"Should offer 'then:' block label, got: " + blockLabels);
		Assertions.assertTrue(blockLabels.stream().anyMatch(l -> l.contains("expect")),
				"Should offer 'expect:' block label, got: " + blockLabels);
	}

	@Test
	void testBlockLabelCompletionsAreSnippets() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec2.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec2 extends Specification {\n");
		contents.append("  def \"block snippet test\"() {\n");
		contents.append("    \n"); // line 3
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		// Block labels should use Snippet insert text format
		List<CompletionItem> blockItems = items.stream()
				.filter(i -> i.getDetail() != null && i.getDetail().contains("Spock block label"))
				.collect(Collectors.toList());
		for (CompletionItem item : blockItems) {
			Assertions.assertEquals(InsertTextFormat.Snippet, item.getInsertTextFormat(),
					"Block label '" + item.getLabel() + "' should use Snippet format");
		}
	}

	// ------------------------------------------------------------------
	// Where: block completions (data table, data pipes, etc.)
	// ------------------------------------------------------------------

	@Test
	void testWhereBlockCompletionVariants() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec3.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec3 extends Specification {\n");
		contents.append("  def \"where variants\"() {\n");
		contents.append("    \n"); // line 3
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<String> whereLabels = items.stream()
				.filter(i -> i.getLabel() != null && i.getLabel().toLowerCase().contains("where"))
				.map(CompletionItem::getLabel)
				.collect(Collectors.toList());

		// Should offer plain where:, data table, and data pipes variants
		Assertions.assertTrue(whereLabels.stream().anyMatch(l -> l.contains("data table")),
				"Should offer where: (data table), got: " + whereLabels);
		Assertions.assertTrue(whereLabels.stream().anyMatch(l -> l.contains("data pipes")),
				"Should offer where: (data pipes), got: " + whereLabels);
		Assertions.assertTrue(whereLabels.stream().anyMatch(l -> l.equals("where:")),
				"Should offer plain where:, got: " + whereLabels);
	}

	// ------------------------------------------------------------------
	// Assertion helper completions inside feature method
	// ------------------------------------------------------------------

	@Test
	void testSpockAssertionCompletions() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec4.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec4 extends Specification {\n");
		contents.append("  def \"assertion helpers\"() {\n");
		contents.append("    \n"); // line 3
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<String> labels = items.stream()
				.map(CompletionItem::getLabel)
				.collect(Collectors.toList());

		// thrown(), Mock(), Stub(), Spy(), verifyAll should appear
		Assertions.assertTrue(labels.contains("thrown"),
				"Should offer 'thrown' completion, got: " + labels);
		Assertions.assertTrue(labels.contains("Mock"),
				"Should offer 'Mock' completion, got: " + labels);
		Assertions.assertTrue(labels.contains("Stub"),
				"Should offer 'Stub' completion, got: " + labels);
		Assertions.assertTrue(labels.contains("Spy"),
				"Should offer 'Spy' completion, got: " + labels);
		Assertions.assertTrue(labels.contains("verifyAll"),
				"Should offer 'verifyAll' completion, got: " + labels);
	}

	@Test
	void testThrownCompletionIsSnippet() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec5.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec5 extends Specification {\n");
		contents.append("  def \"thrown snippet\"() {\n");
		contents.append("    \n");
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		CompletionItem thrownItem = items.stream()
				.filter(i -> "thrown".equals(i.getLabel()))
				.findFirst().orElse(null);

		if (thrownItem != null) {
			Assertions.assertEquals(InsertTextFormat.Snippet, thrownItem.getInsertTextFormat(),
					"'thrown' should use Snippet format");
			Assertions.assertTrue(thrownItem.getInsertText().contains("ExceptionType"),
					"'thrown' snippet should contain ExceptionType placeholder");
		}
	}

	// ------------------------------------------------------------------
	// Feature method snippets at class level
	// ------------------------------------------------------------------

	@Test
	void testFeatureMethodSnippetsAtClassLevel() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec6.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec6 extends Specification {\n");
		contents.append("  \n"); // line 2 — class level
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(2, 2));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<String> labels = items.stream()
				.map(CompletionItem::getLabel)
				.collect(Collectors.toList());

		// Feature method snippets
		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("given-when-then")),
				"Should offer given-when-then feature snippet at class level, got: " + labels);
		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("expect")),
				"Should offer expect feature snippet at class level, got: " + labels);
		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("data-driven")),
				"Should offer data-driven feature snippet at class level, got: " + labels);
	}

	@Test
	void testLifecycleMethodSnippetsAtClassLevel() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec7.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec7 extends Specification {\n");
		contents.append("  \n"); // class level
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(2, 2));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<String> labels = items.stream()
				.map(CompletionItem::getLabel)
				.collect(Collectors.toList());

		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("setup()")),
				"Should offer setup() lifecycle snippet, got: " + labels);
		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("cleanup()")),
				"Should offer cleanup() lifecycle snippet, got: " + labels);
		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("setupSpec()")),
				"Should offer setupSpec() lifecycle snippet, got: " + labels);
		Assertions.assertTrue(labels.stream().anyMatch(l -> l.contains("cleanupSpec()")),
				"Should offer cleanupSpec() lifecycle snippet, got: " + labels);
	}

	// ------------------------------------------------------------------
	// Annotation completions
	// ------------------------------------------------------------------

	@Test
	void testSpockAnnotationCompletions() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec8.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec8 extends Specification {\n");
		contents.append("  def \"annotation context\"() {\n");
		contents.append("    \n");
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<String> labels = items.stream()
				.map(CompletionItem::getLabel)
				.collect(Collectors.toList());

		Assertions.assertTrue(labels.contains("@Unroll"),
				"Should offer @Unroll annotation, got: " + labels);
		Assertions.assertTrue(labels.contains("@Shared"),
				"Should offer @Shared annotation, got: " + labels);
		Assertions.assertTrue(labels.contains("@Stepwise"),
				"Should offer @Stepwise annotation, got: " + labels);
	}

	// ------------------------------------------------------------------
	// Non-Spock class should NOT get Spock completions
	// ------------------------------------------------------------------

	@Test
	void testNoSpockCompletionsInNonSpockClass() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec9.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SpockCompSpec9 {\n");
		contents.append("  void someMethod() {\n");
		contents.append("    \n"); // line 2
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(2, 4));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<CompletionItem> spockItems = items.stream()
				.filter(i -> i.getDetail() != null && i.getDetail().contains("Spock"))
				.collect(Collectors.toList());

		Assertions.assertTrue(spockItems.isEmpty(),
				"Non-Spock class should not receive Spock-specific completions, but got: "
						+ spockItems.stream().map(CompletionItem::getLabel).collect(Collectors.toList()));
	}

	// ------------------------------------------------------------------
	// No block labels at class level (only feature/lifecycle snippets)
	// ------------------------------------------------------------------

	@Test
	void testNoBlockLabelsAtClassLevel() throws Exception {
		Path filePath = srcRoot.resolve("SpockCompSpec10.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockCompSpec10 extends Specification {\n");
		contents.append("  \n"); // class level
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(2, 2));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get();
		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();

		List<CompletionItem> blockLabelItems = items.stream()
				.filter(i -> i.getDetail() != null && i.getDetail().equals("Spock block label"))
				.collect(Collectors.toList());

		Assertions.assertTrue(blockLabelItems.isEmpty(),
				"At class level, should not offer block labels (given:, when:, etc.), but got: "
						+ blockLabelItems.stream().map(CompletionItem::getLabel).collect(Collectors.toList()));
	}
}
