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
 * Provides "Generate getter/setter" code actions for fields and properties
 * that are missing explicit getters or setters.
 */
public class GenerateGetterSetterAction {

    private ASTNodeVisitor ast;

    public GenerateGetterSetterAction(ASTNodeVisitor ast) {
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
        if (classNode.isInterface()) {
            return Collections.emptyList();
        }

        // Collect existing method names
        Set<String> existingMethods = new HashSet<>();
        for (MethodNode method : classNode.getMethods()) {
            existingMethods.add(method.getName());
        }

        List<MissingAccessor> missingAccessors = new ArrayList<>();

        // Check fields (non-property fields need explicit getters/setters)
        for (FieldNode field : classNode.getFields()) {
            if (field.isStatic() || field.isSynthetic()
                    || field.getName().startsWith("$")
                    || field.getName().startsWith("__")) {
                continue;
            }
            // Skip if this field is a property (Groovy auto-generates accessors for properties)
            if (classNode.getProperty(field.getName()) != null) {
                continue;
            }

            String capitalName = capitalize(field.getName());
            String type = field.getType().getNameWithoutPackage();
            boolean isBoolean = type.equals("boolean") || type.equals("Boolean");

            String getterName = (isBoolean ? "is" : "get") + capitalName;
            String setterName = "set" + capitalName;

            if (!existingMethods.contains(getterName)) {
                missingAccessors.add(new MissingAccessor(field.getName(), type, getterName, true));
            }
            if (!existingMethods.contains(setterName) && !field.isFinal()) {
                missingAccessors.add(new MissingAccessor(field.getName(), type, setterName, false));
            }
        }

        if (missingAccessors.isEmpty()) {
            return Collections.emptyList();
        }

        List<CodeAction> actions = new ArrayList<>();

        // Generate all getters and setters at once
        CodeAction allAction = createGenerateAllAccessorsAction(uri, classNode, missingAccessors);
        if (allAction != null) {
            actions.add(allAction);
        }

        // Individual getter/setter actions for the field at cursor
        if (offsetNode instanceof FieldNode || offsetNode instanceof PropertyNode) {
            String fieldName;
            if (offsetNode instanceof FieldNode) {
                fieldName = ((FieldNode) offsetNode).getName();
            } else {
                fieldName = ((PropertyNode) offsetNode).getName();
            }

            for (MissingAccessor accessor : missingAccessors) {
                if (accessor.fieldName.equals(fieldName)) {
                    CodeAction action = createSingleAccessorAction(uri, classNode, accessor);
                    if (action != null) {
                        actions.add(action);
                    }
                }
            }
        }

        return actions;
    }

    private static class MissingAccessor {
        final String fieldName;
        final String fieldType;
        final String methodName;
        final boolean isGetter;

        MissingAccessor(String fieldName, String fieldType, String methodName, boolean isGetter) {
            this.fieldName = fieldName;
            this.fieldType = fieldType;
            this.methodName = methodName;
            this.isGetter = isGetter;
        }
    }

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private CodeAction createGenerateAllAccessorsAction(URI uri, ClassNode classNode,
            List<MissingAccessor> accessors) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        StringBuilder sb = new StringBuilder();
        sb.append("\n");

        for (MissingAccessor accessor : accessors) {
            sb.append(generateAccessorText(accessor));
        }

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                sb.toString());

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction("Generate all getters/setters");
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        return action;
    }

    private CodeAction createSingleAccessorAction(URI uri, ClassNode classNode, MissingAccessor accessor) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        String text = "\n" + generateAccessorText(accessor);

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                text);

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        String title = (accessor.isGetter ? "Generate getter" : "Generate setter")
                + " for '" + accessor.fieldName + "'";
        CodeAction action = new CodeAction(title);
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        return action;
    }

    private String generateAccessorText(MissingAccessor accessor) {
        StringBuilder sb = new StringBuilder();
        if (accessor.isGetter) {
            sb.append("    ").append(accessor.fieldType).append(" ").append(accessor.methodName).append("() {\n");
            sb.append("        return this.").append(accessor.fieldName).append("\n");
            sb.append("    }\n\n");
        } else {
            sb.append("    void ").append(accessor.methodName).append("(")
                    .append(accessor.fieldType).append(" ").append(accessor.fieldName).append(") {\n");
            sb.append("        this.").append(accessor.fieldName).append(" = ").append(accessor.fieldName).append("\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }
}
