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

    private static final String BOOLEAN_TYPE = "boolean";
    private static final String BOOLEAN_WRAPPER_TYPE = "Boolean";
    private static final String DOLLAR_PREFIX = "$";
    private static final String DOUBLE_UNDERSCORE_PREFIX = "__";
    private static final String GET_PREFIX = "get";
    private static final String IS_PREFIX = "is";
    private static final String SET_PREFIX = "set";

    private ASTNodeVisitor ast;

    public GenerateGetterSetterAction(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public List<CodeAction> provideCodeActions(CodeActionParams params) {
        ActionContext context = resolveActionContext(params);
        if (context == null) {
            return Collections.emptyList();
        }

        if (context.classNode.isInterface()) {
            return Collections.emptyList();
        }

        Set<String> existingMethods = collectExistingMethods(context.classNode);
        List<MissingAccessor> missingAccessors = collectMissingAccessors(context.classNode, existingMethods);

        if (missingAccessors.isEmpty()) {
            return Collections.emptyList();
        }

        List<CodeAction> actions = new ArrayList<>();

        CodeAction allAction = createGenerateAllAccessorsAction(context.uri, context.classNode, missingAccessors);
        if (allAction != null) {
            actions.add(allAction);
        }

        addSingleAccessorActions(actions, context, missingAccessors);

        return actions;
    }

    private static class ActionContext {
        final URI uri;
        final ASTNode offsetNode;
        final ClassNode classNode;

        ActionContext(URI uri, ASTNode offsetNode, ClassNode classNode) {
            this.uri = uri;
            this.offsetNode = offsetNode;
            this.classNode = classNode;
        }
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

    private ActionContext resolveActionContext(CodeActionParams params) {
        if (ast == null) {
            return null;
        }

        URI uri = URI.create(params.getTextDocument().getUri());
        Position position = params.getRange().getStart();
        ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if (offsetNode == null) {
            return null;
        }

        ASTNode enclosingClass = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ClassNode.class, ast);
        if (!(enclosingClass instanceof ClassNode)) {
            return null;
        }

        return new ActionContext(uri, offsetNode, (ClassNode) enclosingClass);
    }

    private Set<String> collectExistingMethods(ClassNode classNode) {
        Set<String> existingMethods = new HashSet<>();
        for (MethodNode method : classNode.getMethods()) {
            existingMethods.add(method.getName());
        }
        return existingMethods;
    }

    private List<MissingAccessor> collectMissingAccessors(ClassNode classNode, Set<String> existingMethods) {
        List<MissingAccessor> missingAccessors = new ArrayList<>();
        for (FieldNode field : classNode.getFields()) {
            if (isEligibleField(classNode, field)) {
                addMissingAccessorsForField(missingAccessors, existingMethods, field);
            }
        }
        return missingAccessors;
    }

    private boolean isEligibleField(ClassNode classNode, FieldNode field) {
        return !field.isStatic()
                && !field.isSynthetic()
                && !field.getName().startsWith(DOLLAR_PREFIX)
                && !field.getName().startsWith(DOUBLE_UNDERSCORE_PREFIX)
                && classNode.getProperty(field.getName()) == null;
    }

    private void addMissingAccessorsForField(List<MissingAccessor> missingAccessors, Set<String> existingMethods,
            FieldNode field) {
        String capitalName = capitalize(field.getName());
        String type = field.getType().getNameWithoutPackage();
        String getterName = (isBooleanType(type) ? IS_PREFIX : GET_PREFIX) + capitalName;
        String setterName = SET_PREFIX + capitalName;

        if (!existingMethods.contains(getterName)) {
            missingAccessors.add(new MissingAccessor(field.getName(), type, getterName, true));
        }
        if (!existingMethods.contains(setterName) && !field.isFinal()) {
            missingAccessors.add(new MissingAccessor(field.getName(), type, setterName, false));
        }
    }

    private boolean isBooleanType(String type) {
        return BOOLEAN_TYPE.equals(type) || BOOLEAN_WRAPPER_TYPE.equals(type);
    }

    private void addSingleAccessorActions(List<CodeAction> actions, ActionContext context,
            List<MissingAccessor> missingAccessors) {
        String fieldName = getFieldNameAtCursor(context.offsetNode);
        if (fieldName == null) {
            return;
        }

        for (MissingAccessor accessor : missingAccessors) {
            if (accessor.fieldName.equals(fieldName)) {
                CodeAction action = createSingleAccessorAction(context.uri, context.classNode, accessor);
                if (action != null) {
                    actions.add(action);
                }
            }
        }
    }

    private String getFieldNameAtCursor(ASTNode offsetNode) {
        if (offsetNode instanceof FieldNode) {
            return ((FieldNode) offsetNode).getName();
        }
        if (offsetNode instanceof PropertyNode) {
            return ((PropertyNode) offsetNode).getName();
        }
        return null;
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
