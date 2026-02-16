package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final String PACKAGE_PREFIX = "package ";
    private static final int IMPORT_PRIORITY_LOCATOR_PROJECT = 0;
    private static final int IMPORT_PRIORITY_AST = 1;
    private static final int IMPORT_PRIORITY_LOCATOR_OTHER = 2;
    private static final int IMPORT_PRIORITY_CLASSPATH = 3;

    private ASTNodeVisitor ast;
    private ClasspathSymbolIndex classpathSymbolIndex;
    private Set<String> classpathSymbolClasspathElements;
    private FileContentsTracker fileContentsTracker;
    private JavaSourceLocator javaSourceLocator;

    public CodeActionProvider(ASTNodeVisitor ast, ClasspathSymbolIndex classpathSymbolIndex,
            FileContentsTracker fileContentsTracker) {
        this(ast, classpathSymbolIndex, null, fileContentsTracker, null);
    }

    public CodeActionProvider(ASTNodeVisitor ast, ClasspathSymbolIndex classpathSymbolIndex,
            Set<String> classpathSymbolClasspathElements,
            FileContentsTracker fileContentsTracker) {
        this(ast, classpathSymbolIndex, classpathSymbolClasspathElements, fileContentsTracker, null);
    }

    public CodeActionProvider(ASTNodeVisitor ast, ClasspathSymbolIndex classpathSymbolIndex,
            Set<String> classpathSymbolClasspathElements,
            FileContentsTracker fileContentsTracker,
            JavaSourceLocator javaSourceLocator) {
        this.ast = ast;
        this.classpathSymbolIndex = classpathSymbolIndex;
        this.classpathSymbolClasspathElements = classpathSymbolClasspathElements;
        this.fileContentsTracker = fileContentsTracker;
        this.javaSourceLocator = javaSourceLocator;
    }

    private List<ClasspathSymbolIndex.Symbol> getFilteredClasses() {
        if (classpathSymbolIndex == null) {
            return Collections.emptyList();
        }
        return classpathSymbolIndex.getSymbols(classpathSymbolClasspathElements);
    }

    public CompletableFuture<List<Either<Command, CodeAction>>> provideCodeActions(CodeActionParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        List<Either<Command, CodeAction>> codeActions = new ArrayList<>();

        addDiagnosticBasedActions(params, uri, codeActions);
        addGeneratedAndRefactorActions(params, codeActions);

        return CompletableFuture.completedFuture(codeActions);
    }

    private void addDiagnosticBasedActions(CodeActionParams params, URI uri,
            List<Either<Command, CodeAction>> codeActions) {
        if (params.getContext() == null || params.getContext().getDiagnostics() == null) {
            return;
        }

        List<Diagnostic> unusedImportDiagnostics = new ArrayList<>();
        for (Diagnostic diagnostic : params.getContext().getDiagnostics()) {
            processDiagnostic(uri, codeActions, unusedImportDiagnostics, diagnostic);
        }

        if (unusedImportDiagnostics.size() > 1) {
            CodeAction removeAll = createRemoveAllUnusedImportsAction(uri, unusedImportDiagnostics);
            if (removeAll != null) {
                codeActions.add(Either.forRight(removeAll));
            }
        }
    }

    private void processDiagnostic(URI uri, List<Either<Command, CodeAction>> codeActions,
            List<Diagnostic> unusedImportDiagnostics, Diagnostic diagnostic) {
        String message = diagnostic.getMessage();
        if (message == null) {
            return;
        }

        addMissingImportActions(uri, codeActions, diagnostic, message);
        if (UNUSED_IMPORT_MESSAGE.equals(message)) {
            unusedImportDiagnostics.add(diagnostic);
            CodeAction removeOne = createRemoveUnusedImportAction(uri, diagnostic);
            if (removeOne != null) {
                codeActions.add(Either.forRight(removeOne));
            }
        }
    }

    private void addMissingImportActions(URI uri, List<Either<Command, CodeAction>> codeActions,
            Diagnostic diagnostic, String message) {
        Matcher matcher = PATTERN_UNABLE_TO_RESOLVE_CLASS.matcher(message);
        if (!matcher.find()) {
            return;
        }

        String unresolvedClassName = matcher.group(1);
        for (CodeAction action : createImportActions(uri, unresolvedClassName, diagnostic)) {
            codeActions.add(Either.forRight(action));
        }
    }

    private void addGeneratedAndRefactorActions(CodeActionParams params,
            List<Either<Command, CodeAction>> codeActions) {
        addActions(codeActions, new OrganizeImportsAction(ast).provideCodeActions(params));
        addActions(codeActions, new ImplementInterfaceMethodsAction(ast).provideCodeActions(params));
        addActions(codeActions, new GenerateConstructorAction(ast).provideCodeActions(params));
        addActions(codeActions, new GenerateGetterSetterAction(ast).provideCodeActions(params));
        addActions(codeActions, new GenerateMethodsAction(ast).provideCodeActions(params));
        addActions(codeActions, new AddOverrideAction(ast, fileContentsTracker).provideCodeActions(params));
    }

    private void addActions(List<Either<Command, CodeAction>> codeActions, List<CodeAction> actions) {
        for (CodeAction action : actions) {
            codeActions.add(Either.forRight(action));
        }
    }

    private List<CodeAction> createImportActions(URI uri, String unresolvedClassName, Diagnostic diagnostic) {
        List<CodeAction> actions = new ArrayList<>();
        Set<String> seenFqcns = new LinkedHashSet<>();
        Map<String, Integer> candidatePriorities = new HashMap<>();
        String currentPackage = extractCurrentPackage(uri);

        addLocatorCandidates(unresolvedClassName, currentPackage, seenFqcns, candidatePriorities);
        addClasspathCandidates(unresolvedClassName, currentPackage, seenFqcns, candidatePriorities);
        addAstCandidates(unresolvedClassName, currentPackage, seenFqcns, candidatePriorities);

        candidatePriorities.entrySet().stream()
                .sorted(Comparator
                        .comparingInt(Map.Entry<String, Integer>::getValue)
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> {
                    CodeAction action = createAddImportAction(uri, entry.getKey(), diagnostic);
                    if (action != null) {
                        actions.add(action);
                    }
                });

        return actions;
    }

    private void addLocatorCandidates(String unresolvedClassName, String currentPackage, Set<String> seenFqcns,
            Map<String, Integer> candidatePriorities) {
        if (javaSourceLocator == null) {
            return;
        }

        for (String fullyQualifiedName : javaSourceLocator.findClassNamesBySimpleName(unresolvedClassName)) {
            if (!shouldAddImportCandidate(fullyQualifiedName, currentPackage, seenFqcns)) {
                continue;
            }
            int priority = javaSourceLocator.hasProjectSource(fullyQualifiedName)
                    ? IMPORT_PRIORITY_LOCATOR_PROJECT
                    : IMPORT_PRIORITY_LOCATOR_OTHER;
            candidatePriorities.put(fullyQualifiedName, priority);
        }
    }

    private void addClasspathCandidates(String unresolvedClassName, String currentPackage, Set<String> seenFqcns,
            Map<String, Integer> candidatePriorities) {
        if (classpathSymbolIndex == null) {
            return;
        }

        for (ClasspathSymbolIndex.Symbol classSymbol : getFilteredClasses()) {
            if (classSymbol.getSimpleName().equals(unresolvedClassName)) {
                String fullyQualifiedName = classSymbol.getName();
                if (shouldAddImportCandidate(fullyQualifiedName, currentPackage, seenFqcns)) {
                    candidatePriorities.put(fullyQualifiedName, IMPORT_PRIORITY_CLASSPATH);
                }
            }
        }
    }

    private void addAstCandidates(String unresolvedClassName, String currentPackage, Set<String> seenFqcns,
            Map<String, Integer> candidatePriorities) {
        if (ast == null) {
            return;
        }

        ast.getClassNodes().stream()
                .filter(classNode -> classNode.getNameWithoutPackage().equals(unresolvedClassName))
                .filter(classNode -> classNode.getPackageName() != null && !classNode.getPackageName().isEmpty())
                .forEach(classNode -> {
                    String fullyQualifiedName = classNode.getName();
                    if (shouldAddImportCandidate(fullyQualifiedName, currentPackage, seenFqcns)) {
                        candidatePriorities.put(fullyQualifiedName, IMPORT_PRIORITY_AST);
                    }
                });
    }

    private boolean shouldAddImportCandidate(String fullyQualifiedName, String currentPackage,
            Set<String> seenFqcns) {
        return shouldOfferImport(fullyQualifiedName, currentPackage) && seenFqcns.add(fullyQualifiedName);
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
            if (!isSkippableLeadingLine(line)) {
                if (line.startsWith(PACKAGE_PREFIX)) {
                    return sanitizePackageName(line.substring(PACKAGE_PREFIX.length()).trim());
                }
                return null;
            }
        }

        return null;
    }

    private boolean isSkippableLeadingLine(String line) {
        return line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*");
    }

    private String sanitizePackageName(String packageDeclaration) {
        String pkg = packageDeclaration;
        if (pkg.endsWith(";")) {
            pkg = pkg.substring(0, pkg.length() - 1).trim();
        }
        return pkg;
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

    private CodeAction createRemoveUnusedImportAction(URI uri, Diagnostic diagnostic) {
        Range range = diagnostic.getRange();
        if (range == null) {
            return null;
        }

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

    private CodeAction createRemoveAllUnusedImportsAction(URI uri, List<Diagnostic> diagnostics) {
        List<int[]> ranges = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            Range range = diagnostic.getRange();
            if (range != null) {
                ranges.add(new int[] { range.getStart().getLine(), range.getEnd().getLine() + 1 });
            }
        }

        if (ranges.isEmpty()) {
            return null;
        }

        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        merged.add(ranges.get(0));
        for (int i = 1; i < ranges.size(); i++) {
            int[] last = merged.get(merged.size() - 1);
            int[] current = ranges.get(i);
            if (current[0] <= last[1]) {
                last[1] = Math.max(last[1], current[1]);
            } else {
                merged.add(current);
            }
        }

        List<TextEdit> edits = new ArrayList<>();
        for (int[] range : merged) {
            edits.add(new TextEdit(
                    new Range(new Position(range[0], 0), new Position(range[1], 0)),
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
            } else if (trimmed.startsWith(PACKAGE_PREFIX)) {
                packageLine = i;
            }
        }

        if (lastImportLine >= 0) {
            return lastImportLine + 1;
        }
        if (packageLine >= 0) {
            return packageLine + 1;
        }
        return 0;
    }
}
