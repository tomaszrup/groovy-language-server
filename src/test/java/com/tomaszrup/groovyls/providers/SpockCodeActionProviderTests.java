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

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
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
 * Tests for SpockCodeActionProvider — verifies that Spock-specific code actions
 * (insert given-when-then blocks, generate feature method) are offered in Spock
 * specification classes.
 */
class SpockCodeActionProviderTests {
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
	// Insert given-when-then blocks for a feature method
	// ------------------------------------------------------------------

	@Test
	void testInsertBlocksActionOnFeatureMethod() throws Exception {
		Path filePath = srcRoot.resolve("SpockActionSpec1.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockActionSpec1 extends Specification {\n");
		contents.append("  def \"should test something\"() {\n"); // line 2
		contents.append("    println 'hello'\n"); // line 3
		contents.append("  }\n"); // line 4
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		// Request code actions inside the feature method
		CodeActionParams params = new CodeActionParams(
				new TextDocumentIdentifier(uri),
				new Range(new Position(3, 4), new Position(3, 4)),
				new CodeActionContext(java.util.Collections.emptyList()));

		List<Either<Command, CodeAction>> actions = services.codeAction(params).get();
		Assertions.assertNotNull(actions);

		List<String> actionTitles = actions.stream()
				.filter(Either::isRight)
				.map(e -> e.getRight().getTitle())
				.collect(Collectors.toList());

		Assertions.assertTrue(
				actionTitles.stream().anyMatch(t -> t.contains("Spock") && t.contains("given-when-then")),
				"Should offer 'Insert given-when-then blocks' action on feature method, got: " + actionTitles);
	}

	// ------------------------------------------------------------------
	// Generate feature method at class level
	// ------------------------------------------------------------------

	@Test
	void testGenerateFeatureMethodAction() throws Exception {
		Path filePath = srcRoot.resolve("SpockActionSpec2.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockActionSpec2 extends Specification {\n");
		contents.append("  def \"existing feature\"() {\n");
		contents.append("    expect:\n");
		contents.append("    true\n");
		contents.append("  }\n"); // line 5
		contents.append("}\n"); // line 6

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		// Request code actions at class scope
		CodeActionParams params = new CodeActionParams(
				new TextDocumentIdentifier(uri),
				new Range(new Position(5, 0), new Position(5, 0)),
				new CodeActionContext(java.util.Collections.emptyList()));

		List<Either<Command, CodeAction>> actions = services.codeAction(params).get();
		Assertions.assertNotNull(actions);

		List<String> actionTitles = actions.stream()
				.filter(Either::isRight)
				.map(e -> e.getRight().getTitle())
				.collect(Collectors.toList());

		Assertions.assertTrue(
				actionTitles.stream().anyMatch(t -> t.contains("Spock") && t.contains("Generate feature")),
				"Should offer 'Generate feature method' action, got: " + actionTitles);
	}

	// ------------------------------------------------------------------
	// Code action edit content verification
	// ------------------------------------------------------------------

	@Test
	void testInsertBlocksEditContainsGivenWhenThen() throws Exception {
		Path filePath = srcRoot.resolve("SpockActionSpec3.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockActionSpec3 extends Specification {\n");
		contents.append("  def \"check edit\"() {\n");
		contents.append("    println 'stub'\n");
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CodeActionParams params = new CodeActionParams(
				new TextDocumentIdentifier(uri),
				new Range(new Position(3, 4), new Position(3, 4)),
				new CodeActionContext(java.util.Collections.emptyList()));

		List<Either<Command, CodeAction>> actions = services.codeAction(params).get();

		CodeAction insertAction = actions.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(a -> a.getTitle().contains("given-when-then"))
				.findFirst().orElse(null);

		if (insertAction != null && insertAction.getEdit() != null) {
			String editText = insertAction.getEdit().getChanges().values().stream()
					.flatMap(List::stream)
					.map(org.eclipse.lsp4j.TextEdit::getNewText)
					.collect(Collectors.joining());
			Assertions.assertTrue(editText.contains("given:"), "Edit should contain 'given:'");
			Assertions.assertTrue(editText.contains("when:"), "Edit should contain 'when:'");
			Assertions.assertTrue(editText.contains("then:"), "Edit should contain 'then:'");
		}
	}

	// ------------------------------------------------------------------
	// No Spock code actions in non-Spock class
	// ------------------------------------------------------------------

	@Test
	void testNoSpockActionsInNonSpockClass() throws Exception {
		Path filePath = srcRoot.resolve("SpockActionSpec4.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class SpockActionSpec4 {\n");
		contents.append("  void regularMethod() {\n");
		contents.append("    println 'hello'\n");
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CodeActionParams params = new CodeActionParams(
				new TextDocumentIdentifier(uri),
				new Range(new Position(2, 4), new Position(2, 4)),
				new CodeActionContext(java.util.Collections.emptyList()));

		List<Either<Command, CodeAction>> actions = services.codeAction(params).get();

		List<String> spockActions = actions.stream()
				.filter(Either::isRight)
				.map(e -> e.getRight().getTitle())
				.filter(t -> t.contains("Spock"))
				.collect(Collectors.toList());

		Assertions.assertTrue(spockActions.isEmpty(),
				"Non-Spock class should not receive Spock code actions, got: " + spockActions);
	}

	// ------------------------------------------------------------------
	// Generate feature method edit content
	// ------------------------------------------------------------------

	@Test
	void testGenerateFeatureMethodEditContent() throws Exception {
		Path filePath = srcRoot.resolve("SpockActionSpec5.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import spock.lang.Specification\n");
		contents.append("class SpockActionSpec5 extends Specification {\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		CodeActionParams params = new CodeActionParams(
				new TextDocumentIdentifier(uri),
				new Range(new Position(1, 0), new Position(1, 0)),
				new CodeActionContext(java.util.Collections.emptyList()));

		List<Either<Command, CodeAction>> actions = services.codeAction(params).get();

		CodeAction generateAction = actions.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(a -> a.getTitle().contains("Generate feature"))
				.findFirst().orElse(null);

		if (generateAction != null && generateAction.getEdit() != null) {
			String editText = generateAction.getEdit().getChanges().values().stream()
					.flatMap(List::stream)
					.map(org.eclipse.lsp4j.TextEdit::getNewText)
					.collect(Collectors.joining());
			Assertions.assertTrue(editText.contains("def \""),
					"Generated feature method should contain 'def \"...'");
			Assertions.assertTrue(editText.contains("given:"),
					"Generated feature method should contain 'given:'");
		}
	}
}
