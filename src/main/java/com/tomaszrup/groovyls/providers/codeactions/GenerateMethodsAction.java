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
import java.util.List;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;

/**
 * Provides code actions to generate {@code toString()}, {@code equals()},
 * and {@code hashCode()} methods for a class based on its fields/properties.
 */
public class GenerateMethodsAction {

    private ASTNodeVisitor ast;

    public GenerateMethodsAction(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public List<CodeAction> provideCodeActions(CodeActionParams params) {
        if (ast == null) {
            return Collections.emptyList();
        }

        URI uri = URI.create(params.getTextDocument().getUri());
        Position position = params.getRange().getStart();

        ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if (offsetNode == null) {
            return Collections.emptyList();
        }

        // Find the enclosing class
        ASTNode enclosingClass = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ClassNode.class, ast);
        if (!(enclosingClass instanceof ClassNode)) {
            return Collections.emptyList();
        }

        ClassNode classNode = (ClassNode) enclosingClass;
        if (classNode.isInterface() || classNode.isEnum()) {
            return Collections.emptyList();
        }

        // Collect field info
        List<FieldInfo> fields = collectFields(classNode);

        // Check which methods already exist
        boolean hasToString = hasMethod(classNode, "toString", 0);
        boolean hasEquals = hasMethod(classNode, "equals", 1);
        boolean hasHashCode = hasMethod(classNode, "hashCode", 0);

        List<CodeAction> actions = new ArrayList<>();

        if (!hasToString && !fields.isEmpty()) {
            CodeAction action = createToStringAction(uri, classNode, fields);
            if (action != null) {
                actions.add(action);
            }
        }

        if (!hasEquals && !fields.isEmpty()) {
            CodeAction action = createEqualsAction(uri, classNode, fields);
            if (action != null) {
                actions.add(action);
            }
        }

        if (!hasHashCode && !fields.isEmpty()) {
            CodeAction action = createHashCodeAction(uri, classNode, fields);
            if (action != null) {
                actions.add(action);
            }
        }

        // Combined action for all three
        if (!hasToString && !hasEquals && !hasHashCode && !fields.isEmpty()) {
            CodeAction action = createAllThreeAction(uri, classNode, fields);
            if (action != null) {
                actions.add(action);
            }
        }

        return actions;
    }

    private static class FieldInfo {
        final String name;
        final String type;

        FieldInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private List<FieldInfo> collectFields(ClassNode classNode) {
        List<FieldInfo> fields = new ArrayList<>();

        for (PropertyNode prop : classNode.getProperties()) {
            if (!prop.isStatic() && !prop.isSynthetic()) {
                fields.add(new FieldInfo(prop.getName(), prop.getType().getNameWithoutPackage()));
            }
        }

        for (FieldNode field : classNode.getFields()) {
            if (!field.isStatic() && !field.isSynthetic()
                    && !field.getName().startsWith("$")
                    && !field.getName().startsWith("__")
                    && classNode.getProperty(field.getName()) == null) {
                fields.add(new FieldInfo(field.getName(), field.getType().getNameWithoutPackage()));
            }
        }

        return fields;
    }

    private boolean hasMethod(ClassNode classNode, String name, int paramCount) {
        for (MethodNode method : classNode.getMethods()) {
            if (method.getName().equals(name) && method.getParameters().length == paramCount
                    && !method.isSynthetic()) {
                return true;
            }
        }
        return false;
    }

    private CodeAction createToStringAction(URI uri, ClassNode classNode, List<FieldInfo> fields) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        String className = classNode.getNameWithoutPackage();

        StringBuilder sb = new StringBuilder();
        sb.append("\n    @Override\n");
        sb.append("    String toString() {\n");
        sb.append("        return \"").append(className).append("(\" +\n");
        for (int i = 0; i < fields.size(); i++) {
            FieldInfo field = fields.get(i);
            sb.append("            \"");
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(field.name).append("=\" + ").append(field.name);
            if (i < fields.size() - 1) {
                sb.append(" +\n");
            } else {
                sb.append(" +\n            \")\"\n");
            }
        }
        sb.append("    }\n");

        return createAction(uri, insertLine, sb.toString(), "Generate toString()");
    }

    private CodeAction createEqualsAction(URI uri, ClassNode classNode, List<FieldInfo> fields) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        String className = classNode.getNameWithoutPackage();

        StringBuilder sb = new StringBuilder();
        sb.append("\n    @Override\n");
        sb.append("    boolean equals(Object o) {\n");
        sb.append("        if (this.is(o)) return true\n");
        sb.append("        if (!(o instanceof ").append(className).append(")) return false\n");
        sb.append("        ").append(className).append(" that = (").append(className).append(") o\n");

        for (FieldInfo field : fields) {
            if (isPrimitive(field.type)) {
                sb.append("        if (").append(field.name).append(" != that.").append(field.name)
                        .append(") return false\n");
            } else {
                sb.append("        if (").append(field.name).append(" != that.").append(field.name)
                        .append(") return false\n");
            }
        }

        sb.append("        return true\n");
        sb.append("    }\n");

        return createAction(uri, insertLine, sb.toString(), "Generate equals()");
    }

    private CodeAction createHashCodeAction(URI uri, ClassNode classNode, List<FieldInfo> fields) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();

        StringBuilder sb = new StringBuilder();
        sb.append("\n    @Override\n");
        sb.append("    int hashCode() {\n");
        sb.append("        int result = 17\n");
        for (FieldInfo field : fields) {
            if (isPrimitive(field.type)) {
                sb.append("        result = 31 * result + (int) ").append(field.name).append("\n");
            } else {
                sb.append("        result = 31 * result + (").append(field.name)
                        .append(" != null ? ").append(field.name).append(".hashCode() : 0)\n");
            }
        }
        sb.append("        return result\n");
        sb.append("    }\n");

        return createAction(uri, insertLine, sb.toString(), "Generate hashCode()");
    }

    private CodeAction createAllThreeAction(URI uri, ClassNode classNode, List<FieldInfo> fields) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        String className = classNode.getNameWithoutPackage();

        StringBuilder sb = new StringBuilder();

        // toString
        sb.append("\n    @Override\n");
        sb.append("    String toString() {\n");
        sb.append("        return \"").append(className).append("(\" +\n");
        for (int i = 0; i < fields.size(); i++) {
            FieldInfo field = fields.get(i);
            sb.append("            \"");
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(field.name).append("=\" + ").append(field.name);
            if (i < fields.size() - 1) {
                sb.append(" +\n");
            } else {
                sb.append(" +\n            \")\"\n");
            }
        }
        sb.append("    }\n");

        // equals
        sb.append("\n    @Override\n");
        sb.append("    boolean equals(Object o) {\n");
        sb.append("        if (this.is(o)) return true\n");
        sb.append("        if (!(o instanceof ").append(className).append(")) return false\n");
        sb.append("        ").append(className).append(" that = (").append(className).append(") o\n");
        for (FieldInfo field : fields) {
            sb.append("        if (").append(field.name).append(" != that.").append(field.name)
                    .append(") return false\n");
        }
        sb.append("        return true\n");
        sb.append("    }\n");

        // hashCode
        sb.append("\n    @Override\n");
        sb.append("    int hashCode() {\n");
        sb.append("        int result = 17\n");
        for (FieldInfo field : fields) {
            if (isPrimitive(field.type)) {
                sb.append("        result = 31 * result + (int) ").append(field.name).append("\n");
            } else {
                sb.append("        result = 31 * result + (").append(field.name)
                        .append(" != null ? ").append(field.name).append(".hashCode() : 0)\n");
            }
        }
        sb.append("        return result\n");
        sb.append("    }\n");

        return createAction(uri, insertLine, sb.toString(), "Generate toString(), equals(), and hashCode()");
    }

    private boolean isPrimitive(String type) {
        return type.equals("int") || type.equals("long") || type.equals("float")
                || type.equals("double") || type.equals("boolean") || type.equals("byte")
                || type.equals("short") || type.equals("char");
    }

    private CodeAction createAction(URI uri, int insertLine, String text, String title) {
        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                text);

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction(title);
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        return action;
    }
}
