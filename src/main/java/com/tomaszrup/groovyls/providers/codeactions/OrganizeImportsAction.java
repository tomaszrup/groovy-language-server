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

    private ASTNodeVisitor ast;

    public OrganizeImportsAction(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public List<CodeAction> provideCodeActions(CodeActionParams params) {
        if (ast == null) {
            return Collections.emptyList();
        }

        URI uri = URI.create(params.getTextDocument().getUri());

        // Find the ModuleNode for this file
        ModuleNode moduleNode = findModuleNode(uri);
        if (moduleNode == null) {
            return Collections.emptyList();
        }

        List<ImportNode> imports = moduleNode.getImports();
        if (imports == null || imports.isEmpty()) {
            return Collections.emptyList();
        }

        // Collect all type names referenced in the file's AST
        Set<String> referencedTypes = collectReferencedTypes(uri, moduleNode);

        // Find unused imports
        List<ImportNode> unusedImports = new ArrayList<>();
        List<ImportNode> usedImports = new ArrayList<>();
        for (ImportNode importNode : imports) {
            String className = importNode.getClassName();
            String simpleName = className;
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) {
                simpleName = className.substring(lastDot + 1);
            }
            // An import with an alias uses the alias name
            String alias = importNode.getAlias();
            String nameToCheck = (alias != null && !alias.equals(simpleName)) ? alias : simpleName;

            if (referencedTypes.contains(nameToCheck) || referencedTypes.contains(className)) {
                usedImports.add(importNode);
            } else {
                unusedImports.add(importNode);
            }
        }

        List<CodeAction> actions = new ArrayList<>();

        // "Remove Unused Imports" action
        if (!unusedImports.isEmpty()) {
            CodeAction removeAction = createRemoveUnusedImportsAction(uri, moduleNode, imports, usedImports,
                    unusedImports);
            if (removeAction != null) {
                actions.add(removeAction);
            }
        }

        // "Organize Imports" action (sort + remove unused)
        CodeAction organizeAction = createOrganizeImportsAction(uri, moduleNode, imports, usedImports);
        if (organizeAction != null) {
            actions.add(organizeAction);
        }

        return actions;
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
    private Set<String> collectReferencedTypes(URI uri, ModuleNode moduleNode) {
        Set<String> types = new HashSet<>();

        List<ASTNode> nodes = ast.getNodes(uri);
        for (ASTNode node : nodes) {
            // Skip the import nodes themselves
            if (node instanceof ImportNode) {
                continue;
            }

            if (node instanceof ClassNode) {
                ClassNode classNode = (ClassNode) node;
                // Add superclass and interfaces
                addClassNodeType(types, classNode.getUnresolvedSuperClass());
                for (ClassNode iface : classNode.getUnresolvedInterfaces()) {
                    addClassNodeType(types, iface);
                }
            } else if (node instanceof ClassExpression) {
                ClassExpression expr = (ClassExpression) node;
                addClassNodeType(types, expr.getType());
            } else if (node instanceof ConstructorCallExpression) {
                ConstructorCallExpression expr = (ConstructorCallExpression) node;
                addClassNodeType(types, expr.getType());
            } else if (node instanceof VariableExpression) {
                VariableExpression expr = (VariableExpression) node;
                if (expr.getOriginType() != null && !expr.isDynamicTyped()) {
                    addClassNodeType(types, expr.getOriginType());
                }
            }
        }

        // Also scan class nodes for field/property/method return/parameter types
        for (ASTNode node : nodes) {
            if (node instanceof org.codehaus.groovy.ast.FieldNode) {
                org.codehaus.groovy.ast.FieldNode field = (org.codehaus.groovy.ast.FieldNode) node;
                addClassNodeType(types, field.getOriginType());
            } else if (node instanceof org.codehaus.groovy.ast.PropertyNode) {
                org.codehaus.groovy.ast.PropertyNode prop = (org.codehaus.groovy.ast.PropertyNode) node;
                addClassNodeType(types, prop.getOriginType());
            } else if (node instanceof org.codehaus.groovy.ast.MethodNode) {
                org.codehaus.groovy.ast.MethodNode method = (org.codehaus.groovy.ast.MethodNode) node;
                addClassNodeType(types, method.getReturnType());
                for (org.codehaus.groovy.ast.Parameter param : method.getParameters()) {
                    addClassNodeType(types, param.getOriginType());
                }
            }
        }

        return types;
    }

    private void addClassNodeType(Set<String> types, ClassNode classNode) {
        if (classNode == null) {
            return;
        }
        String name = classNode.getName();
        if (name == null || name.equals("java.lang.Object") || name.startsWith("java.lang.")
                || name.equals("void") || name.equals("boolean") || name.equals("int")
                || name.equals("long") || name.equals("float") || name.equals("double")
                || name.equals("byte") || name.equals("short") || name.equals("char")) {
            // Skip primitives and auto-imported java.lang types
        } else {
            types.add(name);
            String simpleName = classNode.getNameWithoutPackage();
            if (simpleName != null) {
                types.add(simpleName);
            }
        }
        // Handle generics
        if (classNode.getGenericsTypes() != null) {
            for (org.codehaus.groovy.ast.GenericsType gt : classNode.getGenericsTypes()) {
                addClassNodeType(types, gt.getType());
                if (gt.getUpperBounds() != null) {
                    for (ClassNode bound : gt.getUpperBounds()) {
                        addClassNodeType(types, bound);
                    }
                }
                if (gt.getLowerBound() != null) {
                    addClassNodeType(types, gt.getLowerBound());
                }
            }
        }
    }

    private CodeAction createRemoveUnusedImportsAction(URI uri, ModuleNode moduleNode,
            List<ImportNode> allImports, List<ImportNode> usedImports, List<ImportNode> unusedImports) {
        String newImportBlock = buildImportBlock(usedImports, false);
        TextEdit edit = createImportBlockEdit(moduleNode, allImports, newImportBlock);
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

    private CodeAction createOrganizeImportsAction(URI uri, ModuleNode moduleNode,
            List<ImportNode> allImports, List<ImportNode> usedImports) {
        // Sort used imports
        String sortedBlock = buildImportBlock(usedImports, true);
        String currentBlock = buildImportBlock(allImports, false);

        // Only offer if there's something to change
        if (sortedBlock.equals(currentBlock)) {
            return null;
        }

        TextEdit edit = createImportBlockEdit(moduleNode, allImports, sortedBlock);
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

        List<String> importLines = new ArrayList<>();
        for (ImportNode importNode : importNodes) {
            String alias = importNode.getAlias();
            String className = importNode.getClassName();
            String simpleName = className;
            int lastDot = className.lastIndexOf('.');
            if (lastDot >= 0) {
                simpleName = className.substring(lastDot + 1);
            }
            if (alias != null && !alias.equals(simpleName)) {
                importLines.add("import " + className + " as " + alias);
            } else {
                importLines.add("import " + className);
            }
        }

        if (sorted) {
            List<String> javaImports = importLines.stream()
                    .filter(l -> l.startsWith("import java.") || l.startsWith("import javax."))
                    .sorted()
                    .collect(Collectors.toList());
            List<String> otherImports = importLines.stream()
                    .filter(l -> !l.startsWith("import java.") && !l.startsWith("import javax."))
                    .sorted()
                    .collect(Collectors.toList());

            StringBuilder sb = new StringBuilder();
            for (String line : javaImports) {
                sb.append(line).append("\n");
            }
            if (!javaImports.isEmpty() && !otherImports.isEmpty()) {
                sb.append("\n");
            }
            for (String line : otherImports) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : importLines) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Creates a TextEdit that replaces the entire import block (from first import
     * to last import) with the given new text.
     */
    private TextEdit createImportBlockEdit(ModuleNode moduleNode, List<ImportNode> allImports,
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
