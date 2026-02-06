package com.tomaszrup.groovyls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesCodeActionTests {
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

	@Test
	void testCodeActionForUnresolvedClassSuggestsImport() throws Exception {
		Path filePath = srcRoot.resolve("CodeActionTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class CodeActionTest {\n");
		contents.append("  public void test() {\n");
		contents.append("    List<String> list = new ArrayList<>()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Simulate a diagnostic for "unable to resolve class ArrayList"
		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setRange(new Range(new Position(2, 27), new Position(2, 36)));
		diagnostic.setSeverity(DiagnosticSeverity.Error);
		diagnostic.setMessage("unable to resolve class ArrayList");

		List<Diagnostic> diagnostics = new ArrayList<>();
		diagnostics.add(diagnostic);

		CodeActionContext context = new CodeActionContext(diagnostics);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(2, 27), new Position(2, 36)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		// ArrayList is in java.util, so we should get at least one import suggestion
		Assertions.assertFalse(result.isEmpty(),
				"Should suggest at least one import for ArrayList");

		boolean hasArrayListImport = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("java.util.ArrayList"));

		Assertions.assertTrue(hasArrayListImport,
				"Should suggest importing java.util.ArrayList");

		// Verify the edit adds an import statement
		CodeAction arrayListAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("java.util.ArrayList"))
				.findFirst()
				.orElseThrow();

		Assertions.assertNotNull(arrayListAction.getEdit());
		List<TextEdit> edits = arrayListAction.getEdit().getChanges().get(uri);
		Assertions.assertNotNull(edits);
		Assertions.assertFalse(edits.isEmpty());
		Assertions.assertTrue(edits.get(0).getNewText().contains("import java.util.ArrayList"));
	}

	@Test
	void testCodeActionReturnsEmptyForNonClassError() throws Exception {
		Path filePath = srcRoot.resolve("CodeActionTest2.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class CodeActionTest2 {\n");
		contents.append("  public void test() {\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Simulate a different kind of diagnostic
		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setRange(new Range(new Position(1, 0), new Position(1, 10)));
		diagnostic.setSeverity(DiagnosticSeverity.Error);
		diagnostic.setMessage("unexpected token: something");

		List<Diagnostic> diagnostics = new ArrayList<>();
		diagnostics.add(diagnostic);

		CodeActionContext context = new CodeActionContext(diagnostics);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(1, 0), new Position(1, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		Assertions.assertTrue(result.isEmpty(),
				"Should not suggest any imports for non-class-resolution errors");
	}

	@Test
	void testCodeActionInsertionAfterExistingImports() throws Exception {
		Path filePath = srcRoot.resolve("CodeActionTest3.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.List\n");
		contents.append("\n");
		contents.append("class CodeActionTest3 {\n");
		contents.append("  public void test() {\n");
		contents.append("    ArrayList<String> list = new ArrayList<>()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setRange(new Range(new Position(4, 4), new Position(4, 13)));
		diagnostic.setSeverity(DiagnosticSeverity.Error);
		diagnostic.setMessage("unable to resolve class ArrayList");

		List<Diagnostic> diagnostics = new ArrayList<>();
		diagnostics.add(diagnostic);

		CodeActionContext context = new CodeActionContext(diagnostics);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(4, 4), new Position(4, 13)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasArrayListImport = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> {
					List<TextEdit> edits = action.getEdit().getChanges().get(uri);
					// Should insert after line 0 (where "import java.util.List" is)
					return edits != null && !edits.isEmpty()
							&& edits.get(0).getRange().getStart().getLine() == 1
							&& action.getTitle().contains("java.util.ArrayList");
				});

		Assertions.assertTrue(hasArrayListImport,
				"Import should be inserted after existing import statements");
	}
}
