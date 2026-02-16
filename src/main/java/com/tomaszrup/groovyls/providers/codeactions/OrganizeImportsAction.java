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
package com.tomaszrup.groovyls.providers.codeactions;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;

/**
 * Provides "Organize Imports" and "Remove Unused Imports" code actions.
 * <ul>
 *   <li>Detects unused imports by comparing import statements against types actually
 *       referenced in the AST.</li>
 *   <li>Sorts remaining imports alphabetically.</li>
 * </ul>
 */
public class OrganizeImportsAction {

    private static final String IMPORT_JAVA_PREFIX = "import java.";
    private static final String IMPORT_JAVAX_PREFIX = "import javax.";
    private static final Set<String> PRIMITIVE_TYPE_NAMES = Set.of(
            "void", "boolean", "int", "long", "float", "double", "byte", "short", "char");

    private ASTNodeVisitor ast;

    public OrganizeImportsAction(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public List<CodeAction> provideCodeActions(CodeActionParams params) {
        ModuleContext context = resolveModuleContext(params);
        if (context == null || context.imports == null || context.imports.isEmpty()) {
            return Collections.emptyList();
        }

        ImportUsage importUsage = splitImportUsage(context.imports,
            collectReferencedTypes(context.uri));

        List<CodeAction> actions = new ArrayList<>();
        if (!importUsage.unusedImports.isEmpty()) {
                CodeAction removeAction = createRemoveUnusedImportsAction(context.uri,
                    context.imports, importUsage.usedImports, importUsage.unusedImports);
            if (removeAction != null) {
                actions.add(removeAction);
            }
        }

        CodeAction organizeAction = createOrganizeImportsAction(context.uri,
                context.imports, importUsage.usedImports);
        if (organizeAction != null) {
            actions.add(organizeAction);
        }

        return actions;
    }

    /**
     * Creates a single TextEdit for organize-imports (remove unused + sort),
     * or null if there is nothing to change.
     */
    public TextEdit createOrganizeImportsTextEdit(URI uri) {
        if (ast == null) {
            return null;
        }

        ModuleNode moduleNode = findModuleNode(uri);
        if (moduleNode == null) {
            return null;
        }

        List<ImportNode> imports = moduleNode.getImports();
        if (imports == null || imports.isEmpty()) {
            return null;
        }

        ImportUsage importUsage = splitImportUsage(imports, collectReferencedTypes(uri));
        String sortedBlock = buildImportBlock(importUsage.usedImports, true);
        return createImportBlockEdit(imports, sortedBlock);
    }

    private static class ModuleContext {
        final URI uri;
        final List<ImportNode> imports;

        ModuleContext(URI uri, List<ImportNode> imports) {
            this.uri = uri;
            this.imports = imports;
        }
    }

    private static class ImportUsage {
        final List<ImportNode> usedImports;
        final List<ImportNode> unusedImports;

        ImportUsage(List<ImportNode> usedImports, List<ImportNode> unusedImports) {
            this.usedImports = usedImports;
            this.unusedImports = unusedImports;
        }
    }

    private ModuleContext resolveModuleContext(CodeActionParams params) {
        if (ast == null) {
            return null;
        }

        URI uri = URI.create(params.getTextDocument().getUri());
        ModuleNode moduleNode = findModuleNode(uri);
        if (moduleNode == null) {
            return null;
        }

        return new ModuleContext(uri, moduleNode.getImports());
    }

    private ImportUsage splitImportUsage(List<ImportNode> imports, Set<String> referencedTypes) {
        List<ImportNode> usedImports = new ArrayList<>();
        List<ImportNode> unusedImports = new ArrayList<>();
        for (ImportNode importNode : imports) {
            if (isImportUsed(importNode, referencedTypes)) {
                usedImports.add(importNode);
            } else {
                unusedImports.add(importNode);
            }
        }
        return new ImportUsage(usedImports, unusedImports);
    }

    private boolean isImportUsed(ImportNode importNode, Set<String> referencedTypes) {
        String className = importNode.getClassName();
        String simpleName = className;
        int lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = className.substring(lastDot + 1);
        }

        String alias = importNode.getAlias();
        String nameToCheck = (alias != null && !alias.equals(simpleName)) ? alias : simpleName;
        return referencedTypes.contains(nameToCheck) || referencedTypes.contains(className);
    }

    private ModuleNode findModuleNode(URI uri) {
        List<ASTNode> nodes = ast.getNodes(uri);
        for (ASTNode node : nodes) {
            if (node instanceof ModuleNode) {
                return (ModuleNode) node;
            }
        }
        return null;
    }

    /**
     * Collects simple names and fully-qualified names of all types referenced
     * in the file. Includes class declarations, superclass/interface references,
     * field types, variable types, constructor calls, class expressions, etc.
     */
    private Set<String> collectReferencedTypes(URI uri) {
        Set<String> types = new HashSet<>();

        List<ASTNode> nodes = ast.getNodes(uri);
        for (ASTNode node : nodes) {
            addDirectTypeReferences(types, node);
        }

        for (ASTNode node : nodes) {
            addMemberTypeReferences(types, node);
        }

        return types;
    }

    private void addDirectTypeReferences(Set<String> types, ASTNode node) {
        if (node instanceof ImportNode) {
            return;
        }

        if (node instanceof ClassNode) {
            ClassNode classNode = (ClassNode) node;
            addClassNodeType(types, classNode.getUnresolvedSuperClass());
            for (ClassNode iface : classNode.getUnresolvedInterfaces()) {
                addClassNodeType(types, iface);
            }
            return;
        }

        if (node instanceof AnnotationNode) {
            addClassNodeType(types, ((AnnotationNode) node).getClassNode());
            return;
        }

        if (node instanceof ClassExpression) {
            addClassNodeType(types, ((ClassExpression) node).getType());
            return;
        }

        if (node instanceof ConstructorCallExpression) {
            addClassNodeType(types, ((ConstructorCallExpression) node).getType());
            return;
        }

        if (node instanceof VariableExpression) {
            VariableExpression expr = (VariableExpression) node;
            if (expr.getOriginType() != null && !expr.isDynamicTyped()) {
                addClassNodeType(types, expr.getOriginType());
            }
        }
    }

    private void addMemberTypeReferences(Set<String> types, ASTNode node) {
        if (node instanceof org.codehaus.groovy.ast.FieldNode) {
            addClassNodeType(types, ((org.codehaus.groovy.ast.FieldNode) node).getOriginType());
            return;
        }

        if (node instanceof org.codehaus.groovy.ast.PropertyNode) {
            addClassNodeType(types, ((org.codehaus.groovy.ast.PropertyNode) node).getOriginType());
            return;
        }

        if (node instanceof org.codehaus.groovy.ast.MethodNode) {
            org.codehaus.groovy.ast.MethodNode method = (org.codehaus.groovy.ast.MethodNode) node;
            addClassNodeType(types, method.getReturnType());
            for (org.codehaus.groovy.ast.Parameter param : method.getParameters()) {
                addClassNodeType(types, param.getOriginType());
            }
        }
    }

    private void addClassNodeType(Set<String> types, ClassNode classNode) {
        if (classNode == null) {
            return;
        }

        String name = classNode.getName();
        if (!isSkippableTypeName(name)) {
            types.add(name);
            String simpleName = classNode.getNameWithoutPackage();
            if (simpleName != null) {
                types.add(simpleName);
            }
        }

        addGenericTypeReferences(types, classNode);
    }

    private boolean isSkippableTypeName(String name) {
        return name == null
                || "java.lang.Object".equals(name)
                || name.startsWith("java.lang.")
                || PRIMITIVE_TYPE_NAMES.contains(name);
    }

    private void addGenericTypeReferences(Set<String> types, ClassNode classNode) {
        if (classNode.getGenericsTypes() == null) {
            return;
        }

        for (org.codehaus.groovy.ast.GenericsType gt : classNode.getGenericsTypes()) {
            addClassNodeType(types, gt.getType());
            addBounds(types, gt.getUpperBounds());
            addClassNodeType(types, gt.getLowerBound());
        }
    }

    private void addBounds(Set<String> types, ClassNode[] bounds) {
        if (bounds == null) {
            return;
        }
        for (ClassNode bound : bounds) {
            addClassNodeType(types, bound);
        }
    }

    private CodeAction createRemoveUnusedImportsAction(URI uri,
            List<ImportNode> allImports, List<ImportNode> usedImports, List<ImportNode> unusedImports) {
        String newImportBlock = buildImportBlock(usedImports, false);
        TextEdit edit = createImportBlockEdit(allImports, newImportBlock);
        if (edit == null) {
            return null;
        }

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        String unusedNames = unusedImports.stream()
                .map(i -> i.getClassName())
                .collect(Collectors.joining(", "));

        CodeAction action = new CodeAction("Remove unused imports (" + unusedNames + ")");
        action.setKind(CodeActionKind.SourceOrganizeImports);
        action.setEdit(workspaceEdit);
        return action;
    }

    private CodeAction createOrganizeImportsAction(URI uri,
            List<ImportNode> allImports, List<ImportNode> usedImports) {
        String sortedBlock = buildImportBlock(usedImports, true);
        String currentBlock = buildImportBlock(allImports, false);
        if (sortedBlock.equals(currentBlock)) {
            return null;
        }

        TextEdit edit = createImportBlockEdit(allImports, sortedBlock);
        if (edit == null) {
            return null;
        }

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction("Organize imports");
        action.setKind(CodeActionKind.SourceOrganizeImports);
        action.setEdit(workspaceEdit);
        return action;
    }

    /**
     * Builds the text for an import block from the given list of imports.
     * If sorted is true, imports are sorted alphabetically by fully-qualified name,
     * with java.* and javax.* grouped first, then a blank line, then the rest.
     */
    private String buildImportBlock(List<ImportNode> importNodes, boolean sorted) {
        if (importNodes.isEmpty()) {
            return "";
        }

        List<String> importLines = toImportLines(importNodes);
        return sorted ? buildSortedImportBlock(importLines) : buildUnsortedImportBlock(importLines);
    }

    private List<String> toImportLines(List<ImportNode> importNodes) {
        List<String> importLines = new ArrayList<>();
        for (ImportNode importNode : importNodes) {
            importLines.add(toImportLine(importNode));
        }
        return importLines;
    }

    private String toImportLine(ImportNode importNode) {
        String alias = importNode.getAlias();
        String className = importNode.getClassName();
        String simpleName = className;
        int lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = className.substring(lastDot + 1);
        }

        if (alias != null && !alias.equals(simpleName)) {
            return "import " + className + " as " + alias;
        }
        return "import " + className;
    }

    private String buildSortedImportBlock(List<String> importLines) {
        List<String> javaImports = importLines.stream()
                .filter(this::isJavaImportLine)
                .sorted()
                .collect(Collectors.toList());
        List<String> otherImports = importLines.stream()
                .filter(line -> !isJavaImportLine(line))
                .sorted()
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        appendLines(sb, javaImports);
        if (!javaImports.isEmpty() && !otherImports.isEmpty()) {
            sb.append("\n");
        }
        appendLines(sb, otherImports);
        sb.append("\n");
        return sb.toString();
    }

    private String buildUnsortedImportBlock(List<String> importLines) {
        StringBuilder sb = new StringBuilder();
        appendLines(sb, importLines);
        sb.append("\n");
        return sb.toString();
    }

    private void appendLines(StringBuilder sb, List<String> lines) {
        for (String line : lines) {
            sb.append(line).append("\n");
        }
    }

    private boolean isJavaImportLine(String line) {
        return line.startsWith(IMPORT_JAVA_PREFIX) || line.startsWith(IMPORT_JAVAX_PREFIX);
    }

    /**
     * Creates a TextEdit that replaces the entire import block (from first import
     * to last import) with the given new text.
     */
    private TextEdit createImportBlockEdit(List<ImportNode> allImports,
            String newImportBlock) {
        if (allImports.isEmpty()) {
            return null;
        }

        // Find the range spanning all imports
        int firstLine = Integer.MAX_VALUE;
        int lastLine = -1;

        for (ImportNode importNode : allImports) {
            Range range = GroovyLanguageServerUtils.astNodeToRange(importNode);
            if (range != null) {
                if (range.getStart().getLine() < firstLine) {
                    firstLine = range.getStart().getLine();
                }
                if (range.getEnd().getLine() > lastLine) {
                    lastLine = range.getEnd().getLine();
                }
            }
        }

        if (firstLine == Integer.MAX_VALUE || lastLine == -1) {
            return null;
        }

        // Replace from start of first import line to end of last import line
        Range replaceRange = new Range(
                new Position(firstLine, 0),
                new Position(lastLine + 1, 0));

        return new TextEdit(replaceRange, newImportBlock);
    }
}
