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
import com.tomaszrup.groovyls.util.FileContentsTracker;

public class CodeActionProvider {
	private static final Pattern PATTERN_UNABLE_TO_RESOLVE_CLASS = Pattern
			.compile("unable to resolve class (\\w+)");

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
		if (params.getContext() == null || params.getContext().getDiagnostics() == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		URI uri = URI.create(params.getTextDocument().getUri());
		List<Either<Command, CodeAction>> codeActions = new ArrayList<>();

		for (Diagnostic diagnostic : params.getContext().getDiagnostics()) {
			String message = diagnostic.getMessage();
			if (message == null) {
				continue;
			}

			Matcher matcher = PATTERN_UNABLE_TO_RESOLVE_CLASS.matcher(message);
			if (matcher.find()) {
				String unresolvedClassName = matcher.group(1);
				List<CodeAction> importActions = createImportActions(uri, unresolvedClassName, diagnostic);
				for (CodeAction action : importActions) {
					codeActions.add(Either.forRight(action));
				}
			}
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
