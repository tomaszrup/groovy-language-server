package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.codeactions.AddOverrideAction;
import com.tomaszrup.groovyls.providers.codeactions.GenerateConstructorAction;
import com.tomaszrup.groovyls.providers.codeactions.GenerateGetterSetterAction;
import com.tomaszrup.groovyls.providers.codeactions.GenerateMethodsAction;
import com.tomaszrup.groovyls.providers.codeactions.ImplementInterfaceMethodsAction;
import com.tomaszrup.groovyls.providers.codeactions.OrganizeImportsAction;
import com.tomaszrup.groovyls.util.FileContentsTracker;

public class CodeActionProvider {
	private static final Pattern PATTERN_UNABLE_TO_RESOLVE_CLASS = Pattern
			.compile("unable to resolve class (\\w+)");
	private static final String UNUSED_IMPORT_MESSAGE = "Unused import";

	private ASTNodeVisitor ast;
	private ScanResult classGraphScanResult;
	private FileContentsTracker fileContentsTracker;

	public CodeActionProvider(ASTNodeVisitor ast, ScanResult classGraphScanResult,
			FileContentsTracker fileContentsTracker) {
		this.ast = ast;
		this.classGraphScanResult = classGraphScanResult;
		this.fileContentsTracker = fileContentsTracker;
	}

	public CompletableFuture<List<Either<Command, CodeAction>>> provideCodeActions(CodeActionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		List<Either<Command, CodeAction>> codeActions = new ArrayList<>();

		// Diagnostic-based quick fixes
		if (params.getContext() != null && params.getContext().getDiagnostics() != null) {
			List<Diagnostic> unusedImportDiagnostics = new ArrayList<>();

			for (Diagnostic diagnostic : params.getContext().getDiagnostics()) {
				String message = diagnostic.getMessage();
				if (message == null) {
					continue;
				}

				// Add Import quick fix
				Matcher matcher = PATTERN_UNABLE_TO_RESOLVE_CLASS.matcher(message);
				if (matcher.find()) {
					String unresolvedClassName = matcher.group(1);
					List<CodeAction> importActions = createImportActions(uri, unresolvedClassName, diagnostic);
					for (CodeAction action : importActions) {
						codeActions.add(Either.forRight(action));
					}
				}

				// Remove Unused Import quick fix
				if (UNUSED_IMPORT_MESSAGE.equals(message)) {
					unusedImportDiagnostics.add(diagnostic);
					CodeAction removeOne = createRemoveUnusedImportAction(uri, diagnostic);
					if (removeOne != null) {
						codeActions.add(Either.forRight(removeOne));
					}
				}
			}

			// "Remove all unused imports" when there are multiple
			if (unusedImportDiagnostics.size() > 1) {
				CodeAction removeAll = createRemoveAllUnusedImportsAction(uri, unusedImportDiagnostics);
				if (removeAll != null) {
					codeActions.add(Either.forRight(removeAll));
				}
			}
		}

		// Organize / Remove Unused Imports
		OrganizeImportsAction organizeImportsAction = new OrganizeImportsAction(ast);
		for (CodeAction action : organizeImportsAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		// Implement Interface Methods
		ImplementInterfaceMethodsAction implementMethodsAction = new ImplementInterfaceMethodsAction(ast);
		for (CodeAction action : implementMethodsAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		// Generate Constructor
		GenerateConstructorAction generateConstructorAction = new GenerateConstructorAction(ast);
		for (CodeAction action : generateConstructorAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		// Generate Getter/Setter
		GenerateGetterSetterAction generateGetterSetterAction = new GenerateGetterSetterAction(ast);
		for (CodeAction action : generateGetterSetterAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		// Generate toString(), equals(), hashCode()
		GenerateMethodsAction generateMethodsAction = new GenerateMethodsAction(ast);
		for (CodeAction action : generateMethodsAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		// Add @Override
		AddOverrideAction addOverrideAction = new AddOverrideAction(ast);
		for (CodeAction action : addOverrideAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		return CompletableFuture.completedFuture(codeActions);
	}

	private List<CodeAction> createImportActions(URI uri, String unresolvedClassName, Diagnostic diagnostic) {
		List<CodeAction> actions = new ArrayList<>();

		// Search in classpath via ClassGraph
		if (classGraphScanResult != null) {
			List<ClassInfo> allClasses = classGraphScanResult.getAllClasses();
			for (ClassInfo classInfo : allClasses) {
				if (classInfo.getSimpleName().equals(unresolvedClassName)) {
					String fullyQualifiedName = classInfo.getName();
					CodeAction action = createAddImportAction(uri, fullyQualifiedName, diagnostic);
					if (action != null) {
						actions.add(action);
					}
				}
			}
		}

		// Also search in AST (project's own classes)
		if (ast != null) {
			ast.getClassNodes().stream()
					.filter(classNode -> classNode.getNameWithoutPackage().equals(unresolvedClassName))
					.filter(classNode -> classNode.getPackageName() != null
							&& classNode.getPackageName().length() > 0)
					.forEach(classNode -> {
						String fullyQualifiedName = classNode.getName();
						// Avoid duplicates from ClassGraph
						boolean alreadyAdded = actions.stream()
								.anyMatch(a -> a.getTitle().contains(fullyQualifiedName));
						if (!alreadyAdded) {
							CodeAction action = createAddImportAction(uri, fullyQualifiedName, diagnostic);
							if (action != null) {
								actions.add(action);
							}
						}
					});
		}

		return actions;
	}

	private CodeAction createAddImportAction(URI uri, String fullyQualifiedName, Diagnostic diagnostic) {
		String importStatement = "import " + fullyQualifiedName + "\n";
		int insertLine = findImportInsertionLine(uri);

		TextEdit textEdit = new TextEdit(
				new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
				importStatement);

		WorkspaceEdit workspaceEdit = new WorkspaceEdit();
		workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(textEdit)));

		CodeAction codeAction = new CodeAction("Add import: " + fullyQualifiedName);
		codeAction.setKind(CodeActionKind.QuickFix);
		codeAction.setEdit(workspaceEdit);
		codeAction.setDiagnostics(Collections.singletonList(diagnostic));

		return codeAction;
	}

	/**
	 * Creates a QuickFix to remove a single unused import line.
	 */
	private CodeAction createRemoveUnusedImportAction(URI uri, Diagnostic diagnostic) {
		Range range = diagnostic.getRange();
		if (range == null) {
			return null;
		}

		// Delete the entire import line (from start of line to start of next line)
		TextEdit textEdit = new TextEdit(
				new Range(new Position(range.getStart().getLine(), 0),
						new Position(range.getEnd().getLine() + 1, 0)),
				"");

		WorkspaceEdit workspaceEdit = new WorkspaceEdit();
		workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(textEdit)));

		CodeAction action = new CodeAction("Remove unused import");
		action.setKind(CodeActionKind.QuickFix);
		action.setEdit(workspaceEdit);
		action.setDiagnostics(Collections.singletonList(diagnostic));
		action.setIsPreferred(true);
		return action;
	}

	/**
	 * Creates a QuickFix to remove all unused imports at once.
	 */
	private CodeAction createRemoveAllUnusedImportsAction(URI uri, List<Diagnostic> diagnostics) {
		List<TextEdit> edits = new ArrayList<>();
		for (Diagnostic diagnostic : diagnostics) {
			Range range = diagnostic.getRange();
			if (range == null) {
				continue;
			}
			edits.add(new TextEdit(
					new Range(new Position(range.getStart().getLine(), 0),
							new Position(range.getEnd().getLine() + 1, 0)),
					""));
		}

		if (edits.isEmpty()) {
			return null;
		}

		WorkspaceEdit workspaceEdit = new WorkspaceEdit();
		workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), edits));

		CodeAction action = new CodeAction("Remove all unused imports");
		action.setKind(CodeActionKind.QuickFix);
		action.setEdit(workspaceEdit);
		action.setDiagnostics(diagnostics);
		return action;
	}

	/**
	 * Finds the line where a new import statement should be inserted.
	 * It inserts after existing import statements, or after the package statement,
	 * or at the top of the file.
	 */
	private int findImportInsertionLine(URI uri) {
		String contents = fileContentsTracker.getContents(uri);
		if (contents == null) {
			return 0;
		}

		String[] lines = contents.split("\n", -1);
		int lastImportLine = -1;
		int packageLine = -1;

		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("import ")) {
				lastImportLine = i;
			} else if (trimmed.startsWith("package ")) {
				packageLine = i;
			}
		}

		if (lastImportLine >= 0) {
			// Insert after the last import
			return lastImportLine + 1;
		} else if (packageLine >= 0) {
			// Insert after the package statement (with a blank line)
			return packageLine + 1;
		}

		// Insert at the top of the file
		return 0;
	}
}
