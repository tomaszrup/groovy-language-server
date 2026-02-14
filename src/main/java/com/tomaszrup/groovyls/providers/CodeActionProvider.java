package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
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

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.codeactions.AddOverrideAction;
import com.tomaszrup.groovyls.providers.codeactions.GenerateConstructorAction;
import com.tomaszrup.groovyls.providers.codeactions.GenerateGetterSetterAction;
import com.tomaszrup.groovyls.providers.codeactions.GenerateMethodsAction;
import com.tomaszrup.groovyls.providers.codeactions.ImplementInterfaceMethodsAction;
import com.tomaszrup.groovyls.providers.codeactions.OrganizeImportsAction;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

public class CodeActionProvider {
	private static final Pattern PATTERN_UNABLE_TO_RESOLVE_CLASS = Pattern
			.compile("unable to resolve class (\\w+)");
	private static final String UNUSED_IMPORT_MESSAGE = "Unused import";
	private static final int IMPORT_PRIORITY_LOCATOR_PROJECT = 0;
	private static final int IMPORT_PRIORITY_AST = 1;
	private static final int IMPORT_PRIORITY_LOCATOR_OTHER = 2;
	private static final int IMPORT_PRIORITY_CLASSPATH = 3;

	private ASTNodeVisitor ast;
	private ClasspathSymbolIndex classpathSymbolIndex;
	/**
	 * When non-null, the scan result is a shared superset and should be
	 * filtered to only include classes from these classpath files.
	 */
	private java.util.Set<String> classpathSymbolClasspathElements;
	private FileContentsTracker fileContentsTracker;
	private JavaSourceLocator javaSourceLocator;

	public CodeActionProvider(ASTNodeVisitor ast, ClasspathSymbolIndex classpathSymbolIndex,
			FileContentsTracker fileContentsTracker) {
		this(ast, classpathSymbolIndex, null, fileContentsTracker, null);
	}

	public CodeActionProvider(ASTNodeVisitor ast, ClasspathSymbolIndex classpathSymbolIndex,
			java.util.Set<String> classpathSymbolClasspathElements,
			FileContentsTracker fileContentsTracker) {
		this(ast, classpathSymbolIndex, classpathSymbolClasspathElements, fileContentsTracker, null);
	}

	public CodeActionProvider(ASTNodeVisitor ast, ClasspathSymbolIndex classpathSymbolIndex,
			java.util.Set<String> classpathSymbolClasspathElements,
			FileContentsTracker fileContentsTracker,
			JavaSourceLocator javaSourceLocator) {
		this.ast = ast;
		this.classpathSymbolIndex = classpathSymbolIndex;
		this.classpathSymbolClasspathElements = classpathSymbolClasspathElements;
		this.fileContentsTracker = fileContentsTracker;
		this.javaSourceLocator = javaSourceLocator;
	}

	/**
	 * Returns classes from the scan result, filtered by the scope's
	 * classpath files if this is a shared superset scan.
	 */
	private List<ClasspathSymbolIndex.Symbol> getFilteredClasses() {
		if (classpathSymbolIndex == null) {
			return Collections.emptyList();
		}
		return classpathSymbolIndex.getSymbols(classpathSymbolClasspathElements);
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
		AddOverrideAction addOverrideAction = new AddOverrideAction(ast, fileContentsTracker);
		for (CodeAction action : addOverrideAction.provideCodeActions(params)) {
			codeActions.add(Either.forRight(action));
		}

		return CompletableFuture.completedFuture(codeActions);
	}

	private List<CodeAction> createImportActions(URI uri, String unresolvedClassName, Diagnostic diagnostic) {
		List<CodeAction> actions = new ArrayList<>();
		Set<String> seenFqcns = new LinkedHashSet<>();
		Map<String, Integer> candidatePriorities = new HashMap<>();
		String currentPackage = extractCurrentPackage(uri);

		// Prefer live source-locator results first; these reflect source moves
		// even when classpath indexes are stale.
		if (javaSourceLocator != null) {
			for (String fullyQualifiedName : javaSourceLocator.findClassNamesBySimpleName(unresolvedClassName)) {
				if (!shouldOfferImport(fullyQualifiedName, currentPackage) || !seenFqcns.add(fullyQualifiedName)) {
					continue;
				}
				int priority = javaSourceLocator.hasProjectSource(fullyQualifiedName)
						? IMPORT_PRIORITY_LOCATOR_PROJECT
						: IMPORT_PRIORITY_LOCATOR_OTHER;
				candidatePriorities.put(fullyQualifiedName, priority);
			}
		}

		// Search in classpath via ClassGraph
		if (classpathSymbolIndex != null) {
			List<ClasspathSymbolIndex.Symbol> allClasses = getFilteredClasses();
			for (ClasspathSymbolIndex.Symbol classSymbol : allClasses) {
				if (classSymbol.getSimpleName().equals(unresolvedClassName)) {
					String fullyQualifiedName = classSymbol.getName();
					if (!shouldOfferImport(fullyQualifiedName, currentPackage)
							|| !seenFqcns.add(fullyQualifiedName)) {
						continue;
					}
					candidatePriorities.put(fullyQualifiedName, IMPORT_PRIORITY_CLASSPATH);
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
						if (shouldOfferImport(fullyQualifiedName, currentPackage)
								&& seenFqcns.add(fullyQualifiedName)) {
							candidatePriorities.put(fullyQualifiedName, IMPORT_PRIORITY_AST);
						}
					});
		}

		candidatePriorities.entrySet().stream()
				.sorted(Comparator
						.comparingInt((Map.Entry<String, Integer> e) -> e.getValue())
						.thenComparing(Map.Entry::getKey))
				.forEach(entry -> {
					CodeAction action = createAddImportAction(uri, entry.getKey(), diagnostic);
					if (action != null) {
						actions.add(action);
					}
				});

		return actions;
	}

	private boolean shouldOfferImport(String fullyQualifiedName, String currentPackage) {
		if (fullyQualifiedName == null || fullyQualifiedName.isEmpty()) {
			return false;
		}
		if (currentPackage == null || currentPackage.isEmpty()) {
			return true;
		}
		int lastDot = fullyQualifiedName.lastIndexOf('.');
		if (lastDot < 0) {
			return true;
		}
		String candidatePackage = fullyQualifiedName.substring(0, lastDot);
		return !currentPackage.equals(candidatePackage);
	}

	private String extractCurrentPackage(URI uri) {
		if (fileContentsTracker == null || uri == null) {
			return null;
		}
		String content = fileContentsTracker.getContents(uri);
		if (content == null || content.isEmpty()) {
			return null;
		}
		for (String rawLine : content.split("\\R", -1)) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
				continue;
			}
			if (line.startsWith("package ")) {
				String pkg = line.substring("package ".length()).trim();
				if (pkg.endsWith(";")) {
					pkg = pkg.substring(0, pkg.length() - 1).trim();
				}
				return pkg;
			}
			break;
		}
		return null;
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
		List<int[]> ranges = new ArrayList<>();
		for (Diagnostic diagnostic : diagnostics) {
			Range range = diagnostic.getRange();
			if (range == null) {
				continue;
			}
			ranges.add(new int[] { range.getStart().getLine(), range.getEnd().getLine() + 1 });
		}

		if (ranges.isEmpty()) {
			return null;
		}

		// Sort by start line and merge overlapping/adjacent ranges
		ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
		List<int[]> merged = new ArrayList<>();
		merged.add(ranges.get(0));
		for (int i = 1; i < ranges.size(); i++) {
			int[] last = merged.get(merged.size() - 1);
			int[] cur = ranges.get(i);
			if (cur[0] <= last[1]) {
				last[1] = Math.max(last[1], cur[1]);
			} else {
				merged.add(cur);
			}
		}

		List<TextEdit> edits = new ArrayList<>();
		for (int[] r : merged) {
			edits.add(new TextEdit(
					new Range(new Position(r[0], 0), new Position(r[1], 0)),
					""));
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
