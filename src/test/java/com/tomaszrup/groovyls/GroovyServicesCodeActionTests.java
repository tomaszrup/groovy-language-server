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

	// =========================================================================
	// Generate Constructor tests
	// =========================================================================

	@Test
	void testCodeActionGenerateConstructorWithFields() throws Exception {
		Path filePath = srcRoot.resolve("ConstructorTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ConstructorTest {\n");
		contents.append("  String name\n");
		contents.append("  int age\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		// Position cursor on the class name (line 0, within "ConstructorTest")
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasConstructorWithFields = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate constructor with all fields"));

		Assertions.assertTrue(hasConstructorWithFields,
				"Should suggest generating a constructor with all fields");
	}

	@Test
	void testCodeActionGenerateNoArgConstructor() throws Exception {
		Path filePath = srcRoot.resolve("NoArgCtorTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class NoArgCtorTest {\n");
		contents.append("  String name\n");
		contents.append("  int age\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasNoArgConstructor = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate no-arg constructor"));

		Assertions.assertTrue(hasNoArgConstructor,
				"Should suggest generating a no-arg constructor when class has fields");
	}

	@Test
	void testCodeActionNoConstructorForEmptyClass() throws Exception {
		Path filePath = srcRoot.resolve("EmptyClassCtorTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class EmptyClassCtorTest {\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasConstructorAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate constructor"));

		Assertions.assertFalse(hasConstructorAction,
				"Should not suggest constructor generation for a class with no fields");
	}

	@Test
	void testCodeActionConstructorEditContainsFieldAssignments() throws Exception {
		Path filePath = srcRoot.resolve("CtorEditTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class CtorEditTest {\n");
		contents.append("  String name\n");
		contents.append("  int age\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		CodeAction ctorAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("Generate constructor with all fields"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(ctorAction, "Should have a constructor action");
		Assertions.assertNotNull(ctorAction.getEdit(), "Constructor action should have an edit");

		List<TextEdit> edits = ctorAction.getEdit().getChanges().get(uri);
		Assertions.assertNotNull(edits, "Should have edits for the file");
		Assertions.assertFalse(edits.isEmpty(), "Should have at least one edit");

		String editText = edits.get(0).getNewText();
		Assertions.assertTrue(editText.contains("this.name = name"),
				"Constructor body should assign 'name' field");
		Assertions.assertTrue(editText.contains("this.age = age"),
				"Constructor body should assign 'age' field");
	}

	// =========================================================================
	// Generate toString/equals/hashCode tests
	// =========================================================================

	@Test
	void testCodeActionGenerateToString() throws Exception {
		Path filePath = srcRoot.resolve("ToStringTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ToStringTest {\n");
		contents.append("  String name\n");
		contents.append("  int value\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasToString = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().equals("Generate toString()"));

		Assertions.assertTrue(hasToString, "Should suggest generating toString()");
	}

	@Test
	void testCodeActionGenerateEquals() throws Exception {
		Path filePath = srcRoot.resolve("EqualsTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class EqualsTest {\n");
		contents.append("  String name\n");
		contents.append("  int value\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasEquals = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().equals("Generate equals()"));

		Assertions.assertTrue(hasEquals, "Should suggest generating equals()");
	}

	@Test
	void testCodeActionGenerateHashCode() throws Exception {
		Path filePath = srcRoot.resolve("HashCodeTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class HashCodeTest {\n");
		contents.append("  String name\n");
		contents.append("  int value\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasHashCode = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().equals("Generate hashCode()"));

		Assertions.assertTrue(hasHashCode, "Should suggest generating hashCode()");
	}

	@Test
	void testCodeActionGenerateAllThreeMethods() throws Exception {
		Path filePath = srcRoot.resolve("AllThreeTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class AllThreeTest {\n");
		contents.append("  String name\n");
		contents.append("  int value\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasAllThree = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate toString(), equals(), and hashCode()"));

		Assertions.assertTrue(hasAllThree,
				"Should suggest generating all three methods together");
	}

	@Test
	void testCodeActionNoToStringIfAlreadyExists() throws Exception {
		Path filePath = srcRoot.resolve("ExistingToStringTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ExistingToStringTest {\n");
		contents.append("  String name\n");
		contents.append("  String toString() {\n");
		contents.append("    return name\n");
		contents.append("  }\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasToString = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().equals("Generate toString()"));

		Assertions.assertFalse(hasToString,
				"Should not suggest generating toString() when it already exists");
	}

	@Test
	void testCodeActionToStringEditContainsFieldNames() throws Exception {
		Path filePath = srcRoot.resolve("ToStringEditTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ToStringEditTest {\n");
		contents.append("  String firstName\n");
		contents.append("  String lastName\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		CodeAction toStringAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().equals("Generate toString()"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(toStringAction, "Should have a toString action");
		List<TextEdit> edits = toStringAction.getEdit().getChanges().get(uri);
		Assertions.assertNotNull(edits);
		String editText = edits.get(0).getNewText();
		Assertions.assertTrue(editText.contains("firstName"),
				"toString body should reference firstName");
		Assertions.assertTrue(editText.contains("lastName"),
				"toString body should reference lastName");
		Assertions.assertTrue(editText.contains("@Override"),
				"toString should have @Override annotation");
	}

	// =========================================================================
	// Generate Getter/Setter tests
	// =========================================================================

	@Test
	void testCodeActionGenerateGetterSetterForPrivateFields() throws Exception {
		Path filePath = srcRoot.resolve("GetterSetterTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class GetterSetterTest {\n");
		contents.append("  private String name\n");
		contents.append("  private int age\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasGenerateAll = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate all getters/setters"));

		Assertions.assertTrue(hasGenerateAll,
				"Should suggest generating all getters/setters for private fields");
	}

	@Test
	void testCodeActionGetterSetterEditContent() throws Exception {
		Path filePath = srcRoot.resolve("GetterSetterEditTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class GetterSetterEditTest {\n");
		contents.append("  private String name\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		CodeAction generateAll = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("Generate all getters/setters"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(generateAll, "Should have generate all getters/setters action");
		List<TextEdit> edits = generateAll.getEdit().getChanges().get(uri);
		Assertions.assertNotNull(edits);
		String editText = edits.get(0).getNewText();

		Assertions.assertTrue(editText.contains("getName"),
				"Should generate getName getter");
		Assertions.assertTrue(editText.contains("setName"),
				"Should generate setName setter");
		Assertions.assertTrue(editText.contains("return this.name"),
				"Getter should return this.name");
	}

	@Test
	void testCodeActionNoGetterSetterForGroovyProperties() throws Exception {
		// Groovy auto-generates getters/setters for properties (no access modifier)
		Path filePath = srcRoot.resolve("PropertyTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class PropertyTest {\n");
		contents.append("  String name\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasGetterSetter = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate all getters/setters"));

		// Groovy properties auto-generate accessors, so we should not see this action
		Assertions.assertFalse(hasGetterSetter,
				"Should not suggest getter/setter for Groovy properties (auto-generated)");
	}

	// =========================================================================
	// Implement Interface Methods tests
	// =========================================================================

	@Test
	void testCodeActionImplementInterfaceMethods() throws Exception {
		Path ifacePath = srcRoot.resolve("Greeter.groovy");
		String ifaceUri = ifacePath.toUri().toString();
		StringBuilder ifaceContents = new StringBuilder();
		ifaceContents.append("interface Greeter {\n");
		ifaceContents.append("  String greet(String name)\n");
		ifaceContents.append("}\n");
		TextDocumentItem ifaceDoc = new TextDocumentItem(ifaceUri, LANGUAGE_GROOVY, 1, ifaceContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(ifaceDoc));

		Path implPath = srcRoot.resolve("GreeterImpl.groovy");
		String implUri = implPath.toUri().toString();
		StringBuilder implContents = new StringBuilder();
		implContents.append("class GreeterImpl implements Greeter {\n");
		implContents.append("}\n");
		TextDocumentItem implDoc = new TextDocumentItem(implUri, LANGUAGE_GROOVY, 1, implContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(implDoc));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(implUri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasImplementMethods = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Implement methods"));

		Assertions.assertTrue(hasImplementMethods,
				"Should suggest implementing interface methods for a class missing implementations");
	}

	@Test
	void testCodeActionImplementMethodsEditContainsOverride() throws Exception {
		Path ifacePath = srcRoot.resolve("Describable.groovy");
		String ifaceUri = ifacePath.toUri().toString();
		StringBuilder ifaceContents = new StringBuilder();
		ifaceContents.append("interface Describable {\n");
		ifaceContents.append("  String describe()\n");
		ifaceContents.append("}\n");
		TextDocumentItem ifaceDoc = new TextDocumentItem(ifaceUri, LANGUAGE_GROOVY, 1, ifaceContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(ifaceDoc));

		Path implPath = srcRoot.resolve("DescribableImpl.groovy");
		String implUri = implPath.toUri().toString();
		StringBuilder implContents = new StringBuilder();
		implContents.append("class DescribableImpl implements Describable {\n");
		implContents.append("}\n");
		TextDocumentItem implDoc = new TextDocumentItem(implUri, LANGUAGE_GROOVY, 1, implContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(implDoc));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(implUri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		CodeAction implementAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("Implement methods"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(implementAction, "Should have implement methods action");
		List<TextEdit> edits = implementAction.getEdit().getChanges().get(implUri);
		Assertions.assertNotNull(edits, "Should have edits");
		String editText = edits.get(0).getNewText();
		Assertions.assertTrue(editText.contains("@Override"),
				"Generated methods should have @Override annotation");
		Assertions.assertTrue(editText.contains("describe"),
				"Should generate describe method");
	}

	@Test
	void testCodeActionNoImplementMethodsWhenAllImplemented() throws Exception {
		Path ifacePath = srcRoot.resolve("Closeable2.groovy");
		String ifaceUri = ifacePath.toUri().toString();
		StringBuilder ifaceContents = new StringBuilder();
		ifaceContents.append("interface Closeable2 {\n");
		ifaceContents.append("  void close()\n");
		ifaceContents.append("}\n");
		TextDocumentItem ifaceDoc = new TextDocumentItem(ifaceUri, LANGUAGE_GROOVY, 1, ifaceContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(ifaceDoc));

		Path implPath = srcRoot.resolve("Closeable2Impl.groovy");
		String implUri = implPath.toUri().toString();
		StringBuilder implContents = new StringBuilder();
		implContents.append("class Closeable2Impl implements Closeable2 {\n");
		implContents.append("  void close() {\n");
		implContents.append("    // already implemented\n");
		implContents.append("  }\n");
		implContents.append("}\n");
		TextDocumentItem implDoc = new TextDocumentItem(implUri, LANGUAGE_GROOVY, 1, implContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(implDoc));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(implUri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasImplementMethods = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Implement methods"));

		Assertions.assertFalse(hasImplementMethods,
				"Should not suggest implementing methods when all are already implemented");
	}

	// =========================================================================
	// Add @Override tests
	// =========================================================================

	@Test
	void testCodeActionAddOverrideForOverridingMethod() throws Exception {
		Path basePath = srcRoot.resolve("Animal.groovy");
		String baseUri = basePath.toUri().toString();
		StringBuilder baseContents = new StringBuilder();
		baseContents.append("class Animal {\n");
		baseContents.append("  String speak() {\n");
		baseContents.append("    return 'generic'\n");
		baseContents.append("  }\n");
		baseContents.append("}\n");
		TextDocumentItem baseDoc = new TextDocumentItem(baseUri, LANGUAGE_GROOVY, 1, baseContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(baseDoc));

		Path childPath = srcRoot.resolve("Dog.groovy");
		String childUri = childPath.toUri().toString();
		StringBuilder childContents = new StringBuilder();
		childContents.append("class Dog extends Animal {\n");
		childContents.append("  String speak() {\n");
		childContents.append("    return 'woof'\n");
		childContents.append("  }\n");
		childContents.append("}\n");
		TextDocumentItem childDoc = new TextDocumentItem(childUri, LANGUAGE_GROOVY, 1, childContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(childDoc));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(childUri);
		// Position cursor on the method "speak" in child class (line 1)
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(1, 10), new Position(1, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasAddOverride = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Add @Override"));

		Assertions.assertTrue(hasAddOverride,
				"Should suggest adding @Override to a method that overrides a parent method");
	}

	@Test
	void testCodeActionAddOverrideEditContent() throws Exception {
		Path basePath = srcRoot.resolve("Vehicle.groovy");
		String baseUri = basePath.toUri().toString();
		StringBuilder baseContents = new StringBuilder();
		baseContents.append("class Vehicle {\n");
		baseContents.append("  String describe() {\n");
		baseContents.append("    return 'vehicle'\n");
		baseContents.append("  }\n");
		baseContents.append("}\n");
		TextDocumentItem baseDoc = new TextDocumentItem(baseUri, LANGUAGE_GROOVY, 1, baseContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(baseDoc));

		Path childPath = srcRoot.resolve("Car.groovy");
		String childUri = childPath.toUri().toString();
		StringBuilder childContents = new StringBuilder();
		childContents.append("class Car extends Vehicle {\n");
		childContents.append("  String describe() {\n");
		childContents.append("    return 'car'\n");
		childContents.append("  }\n");
		childContents.append("}\n");
		TextDocumentItem childDoc = new TextDocumentItem(childUri, LANGUAGE_GROOVY, 1, childContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(childDoc));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(childUri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(1, 10), new Position(1, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		CodeAction overrideAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("Add @Override"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(overrideAction, "Should have Add @Override action");
		List<TextEdit> edits = overrideAction.getEdit().getChanges().get(childUri);
		Assertions.assertNotNull(edits, "Should have edits");
		String editText = edits.get(0).getNewText();
		Assertions.assertTrue(editText.contains("@Override"),
				"Edit should insert @Override annotation");
	}

	@Test
	void testCodeActionNoOverrideWhenAlreadyAnnotated() throws Exception {
		Path basePath = srcRoot.resolve("Base.groovy");
		String baseUri = basePath.toUri().toString();
		StringBuilder baseContents = new StringBuilder();
		baseContents.append("class Base {\n");
		baseContents.append("  String getValue() {\n");
		baseContents.append("    return 'base'\n");
		baseContents.append("  }\n");
		baseContents.append("}\n");
		TextDocumentItem baseDoc = new TextDocumentItem(baseUri, LANGUAGE_GROOVY, 1, baseContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(baseDoc));

		Path childPath = srcRoot.resolve("Child.groovy");
		String childUri = childPath.toUri().toString();
		StringBuilder childContents = new StringBuilder();
		childContents.append("class Child extends Base {\n");
		childContents.append("  @Override\n");
		childContents.append("  String getValue() {\n");
		childContents.append("    return 'child'\n");
		childContents.append("  }\n");
		childContents.append("}\n");
		TextDocumentItem childDoc = new TextDocumentItem(childUri, LANGUAGE_GROOVY, 1, childContents.toString());
		services.didOpen(new DidOpenTextDocumentParams(childDoc));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(childUri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(2, 10), new Position(2, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasAddOverride = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Add @Override"));

		Assertions.assertFalse(hasAddOverride,
				"Should not suggest @Override when method already has it");
	}

	@Test
	void testCodeActionNoOverrideForNonOverridingMethod() throws Exception {
		Path filePath = srcRoot.resolve("StandaloneClass.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class StandaloneClass {\n");
		contents.append("  String myCustomMethod() {\n");
		contents.append("    return 'hello'\n");
		contents.append("  }\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(1, 10), new Position(1, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasAddOverride = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Add @Override"));

		Assertions.assertFalse(hasAddOverride,
				"Should not suggest @Override for a method that does not override anything");
	}

	// =========================================================================
	// Organize Imports tests
	// =========================================================================

	@Test
	void testCodeActionOrganizeImportsOffered() throws Exception {
		Path filePath = srcRoot.resolve("OrganizeImportsTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.Map\n");
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class OrganizeImportsTest {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("  Map<String, String> map = new HashMap<>()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 0), new Position(0, 0)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasOrganize = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Organize imports"));

		// Imports are unsorted (Map before ArrayList alphabetically), so organize should be offered
		Assertions.assertTrue(hasOrganize,
				"Should suggest organizing imports when they are not sorted");
	}

	@Test
	void testCodeActionRemoveUnusedImports() throws Exception {
		Path filePath = srcRoot.resolve("UnusedImportTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("import java.util.HashMap\n");
		contents.append("\n");
		contents.append("class UnusedImportTest {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 0), new Position(0, 0)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasRemoveUnused = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Remove unused imports"));

		Assertions.assertTrue(hasRemoveUnused,
				"Should suggest removing unused imports when HashMap is not used");
	}

	// =========================================================================
	// No code actions for interfaces/enums (negative boundary tests)
	// =========================================================================

	@Test
	void testCodeActionNoConstructorForInterface() throws Exception {
		Path filePath = srcRoot.resolve("InterfaceCtorTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface InterfaceCtorTest {\n");
		contents.append("  String getName()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 10), new Position(0, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasConstructor = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate constructor"));

		Assertions.assertFalse(hasConstructor,
				"Should not suggest constructor generation for interfaces");
	}

	@Test
	void testCodeActionNoMethodGenerationForInterface() throws Exception {
		Path filePath = srcRoot.resolve("InterfaceMethodsTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface InterfaceMethodsTest {\n");
		contents.append("  String getName()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 10), new Position(0, 10)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasToString = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("Generate toString()"));

		Assertions.assertFalse(hasToString,
				"Should not suggest toString/equals/hashCode generation for interfaces");
	}

	// =========================================================================
	// Multiple diagnostics test
	// =========================================================================

	@Test
	void testCodeActionMultipleDiagnosticsMultipleImportSuggestions() throws Exception {
		Path filePath = srcRoot.resolve("MultiDiagTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class MultiDiagTest {\n");
		contents.append("  public void test() {\n");
		contents.append("    List<String> list = new ArrayList<>()\n");
		contents.append("    Map<String, String> map = new HashMap<>()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Diagnostic diag1 = new Diagnostic();
		diag1.setRange(new Range(new Position(2, 27), new Position(2, 36)));
		diag1.setSeverity(DiagnosticSeverity.Error);
		diag1.setMessage("unable to resolve class ArrayList");

		Diagnostic diag2 = new Diagnostic();
		diag2.setRange(new Range(new Position(3, 31), new Position(3, 38)));
		diag2.setSeverity(DiagnosticSeverity.Error);
		diag2.setMessage("unable to resolve class HashMap");

		List<Diagnostic> diagnostics = new ArrayList<>();
		diagnostics.add(diag1);
		diagnostics.add(diag2);

		CodeActionContext context = new CodeActionContext(diagnostics);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(2, 27), new Position(3, 38)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasArrayListImport = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("java.util.ArrayList"));

		boolean hasHashMapImport = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> action.getTitle().contains("java.util.HashMap"));

		Assertions.assertTrue(hasArrayListImport,
				"Should suggest import for ArrayList");
		Assertions.assertTrue(hasHashMapImport,
				"Should suggest import for HashMap");
	}

	// =========================================================================
	// Code action kind tests
	// =========================================================================

	@Test
	void testCodeActionImportHasQuickFixKind() throws Exception {
		Path filePath = srcRoot.resolve("KindTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class KindTest {\n");
		contents.append("  public void test() {\n");
		contents.append("    List<String> list = new ArrayList<>()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

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

		CodeAction importAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("java.util.ArrayList"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(importAction, "Should have import action");
		Assertions.assertEquals("quickfix", importAction.getKind(),
				"Import code action should have quickfix kind");
	}

	@Test
	void testCodeActionConstructorHasRefactorKind() throws Exception {
		Path filePath = srcRoot.resolve("RefactorKindTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class RefactorKindTest {\n");
		contents.append("  String name\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		CodeAction ctorAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> action.getTitle().contains("Generate constructor"))
				.findFirst()
				.orElse(null);

		Assertions.assertNotNull(ctorAction, "Should have constructor action");
		Assertions.assertEquals("refactor", ctorAction.getKind(),
				"Constructor code action should have refactor kind");
	}

	// =========================================================================
	// Empty/null diagnostic context
	// =========================================================================

	@Test
	void testCodeActionWithEmptyDiagnosticList() throws Exception {
		Path filePath = srcRoot.resolve("EmptyDiagTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class EmptyDiagTest {\n");
		contents.append("  String name\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 6), new Position(0, 6)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();
		Assertions.assertNotNull(result, "Result should not be null with empty diagnostics");
		// Should still return refactoring code actions (constructor, toString, etc.)
		Assertions.assertFalse(result.isEmpty(),
				"Should still return refactoring actions even without diagnostics");
	}

	// =========================================================================
	// Remove Unused Import QuickFix tests
	// =========================================================================

	@Test
	void testQuickFixRemoveUnusedImport() throws Exception {
		Path filePath = srcRoot.resolve("QuickFixUnusedImport.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("import java.util.HashMap\n");
		contents.append("\n");
		contents.append("class QuickFixUnusedImport {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Simulate the "Unused import" diagnostic on line 1 (HashMap)
		Diagnostic unusedDiag = new Diagnostic();
		unusedDiag.setRange(new Range(new Position(1, 0), new Position(1, 25)));
		unusedDiag.setSeverity(DiagnosticSeverity.Hint);
		unusedDiag.setMessage("Unused import");

		List<Diagnostic> diagnostics = new ArrayList<>();
		diagnostics.add(unusedDiag);
		CodeActionContext context = new CodeActionContext(diagnostics);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(1, 0), new Position(1, 25)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasRemoveUnusedQuickFix = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> "Remove unused import".equals(action.getTitle())
						&& "quickfix".equals(action.getKind()));

		Assertions.assertTrue(hasRemoveUnusedQuickFix,
				"Should offer 'Remove unused import' QuickFix for unused import diagnostic");

		// Verify the edit deletes line 1
		CodeAction removeAction = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> "Remove unused import".equals(action.getTitle()))
				.findFirst()
				.orElse(null);
		Assertions.assertNotNull(removeAction);
		Assertions.assertTrue(removeAction.getIsPreferred(), "Remove unused import should be preferred");
		List<TextEdit> edits = removeAction.getEdit().getChanges().get(uri);
		Assertions.assertNotNull(edits);
		Assertions.assertEquals(1, edits.size());
		Assertions.assertEquals(1, edits.get(0).getRange().getStart().getLine());
		Assertions.assertEquals(2, edits.get(0).getRange().getEnd().getLine());
		Assertions.assertEquals("", edits.get(0).getNewText());
	}

	@Test
	void testQuickFixRemoveAllUnusedImports() throws Exception {
		Path filePath = srcRoot.resolve("QuickFixAllUnused.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("import java.util.HashMap\n");
		contents.append("import java.util.LinkedList\n");
		contents.append("\n");
		contents.append("class QuickFixAllUnused {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// Simulate two "Unused import" diagnostics
		Diagnostic unusedDiag1 = new Diagnostic();
		unusedDiag1.setRange(new Range(new Position(1, 0), new Position(1, 25)));
		unusedDiag1.setSeverity(DiagnosticSeverity.Hint);
		unusedDiag1.setMessage("Unused import");

		Diagnostic unusedDiag2 = new Diagnostic();
		unusedDiag2.setRange(new Range(new Position(2, 0), new Position(2, 28)));
		unusedDiag2.setSeverity(DiagnosticSeverity.Hint);
		unusedDiag2.setMessage("Unused import");

		List<Diagnostic> diagnostics = new ArrayList<>();
		diagnostics.add(unusedDiag1);
		diagnostics.add(unusedDiag2);
		CodeActionContext context = new CodeActionContext(diagnostics);
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(1, 0), new Position(2, 28)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasRemoveAll = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> "Remove all unused imports".equals(action.getTitle())
						&& "quickfix".equals(action.getKind()));

		Assertions.assertTrue(hasRemoveAll,
				"Should offer 'Remove all unused imports' QuickFix when multiple unused imports are present");

		// Should also have individual remove actions
		long individualCount = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.filter(action -> "Remove unused import".equals(action.getTitle()))
				.count();
		Assertions.assertEquals(2, individualCount,
				"Should have one 'Remove unused import' QuickFix per unused import diagnostic");
	}

	@Test
	void testQuickFixNoRemoveUnusedImportWithoutDiagnostic() throws Exception {
		Path filePath = srcRoot.resolve("NoQuickFix.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class NoQuickFix {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("}\n");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// No diagnostics passed
		CodeActionContext context = new CodeActionContext(new ArrayList<>());
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		CodeActionParams params = new CodeActionParams(textDocument,
				new Range(new Position(0, 0), new Position(0, 0)), context);

		List<Either<Command, CodeAction>> result = services.codeAction(params).get();

		boolean hasRemoveUnusedQuickFix = result.stream()
				.filter(Either::isRight)
				.map(Either::getRight)
				.anyMatch(action -> "Remove unused import".equals(action.getTitle())
						&& "quickfix".equals(action.getKind()));

		Assertions.assertFalse(hasRemoveUnusedQuickFix,
				"Should NOT offer 'Remove unused import' QuickFix when no unused import diagnostics present");
	}
}
