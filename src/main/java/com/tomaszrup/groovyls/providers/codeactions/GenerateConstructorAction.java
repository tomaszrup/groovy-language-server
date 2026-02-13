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

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
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
 * Provides "Generate constructor" code actions:
 * <ul>
 *   <li>Generate constructor with all fields/properties</li>
 *   <li>Generate no-arg constructor</li>
 * </ul>
 */
public class GenerateConstructorAction {

    private ASTNodeVisitor ast;

    public GenerateConstructorAction(ASTNodeVisitor ast) {
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

        // Skip interfaces and enums
        if (classNode.isInterface() || classNode.isEnum()) {
            return Collections.emptyList();
        }

        List<CodeAction> actions = new ArrayList<>();

        // Collect fields and properties (non-static, non-synthetic)
        List<FieldInfo> fields = collectFields(classNode);

        // Generate constructor with all fields
        if (!fields.isEmpty()) {
            CodeAction allFieldsAction = createConstructorAction(uri, classNode, fields,
                    "Generate constructor with all fields");
            if (allFieldsAction != null) {
                actions.add(allFieldsAction);
            }
        }

        // Generate no-arg constructor (if there are fields and no existing no-arg constructor)
        if (!fields.isEmpty() && !hasNoArgConstructor(classNode)) {
            CodeAction noArgAction = createNoArgConstructorAction(uri, classNode);
            if (noArgAction != null) {
                actions.add(noArgAction);
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
                String type = prop.getType().getNameWithoutPackage();
                fields.add(new FieldInfo(prop.getName(), type));
            }
        }

        // Also add fields that are not properties (declared with explicit access modifiers)
        for (FieldNode field : classNode.getFields()) {
            if (!field.isStatic() && !field.isSynthetic()
                    && !field.getName().startsWith("$")
                    && !field.getName().startsWith("__")
                    && classNode.getProperty(field.getName()) == null) {
                String type = field.getType().getNameWithoutPackage();
                fields.add(new FieldInfo(field.getName(), type));
            }
        }

        return fields;
    }

    private boolean hasNoArgConstructor(ClassNode classNode) {
        List<ConstructorNode> constructors = classNode.getDeclaredConstructors();
        for (ConstructorNode ctor : constructors) {
            if (ctor.getParameters().length == 0 && !ctor.isSynthetic()) {
                return true;
            }
        }
        return false;
    }

    private CodeAction createConstructorAction(URI uri, ClassNode classNode, List<FieldInfo> fields,
            String title) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        String className = classNode.getNameWithoutPackage();

        StringBuilder sb = new StringBuilder();
        sb.append("\n    ").append(className).append("(");

        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            FieldInfo field = fields.get(i);
            sb.append(field.type).append(" ").append(field.name);
        }

        sb.append(") {\n");
        for (FieldInfo field : fields) {
            sb.append("        this.").append(field.name).append(" = ").append(field.name).append("\n");
        }
        sb.append("    }\n");

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                sb.toString());

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction(title);
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        return action;
    }

    private CodeAction createNoArgConstructorAction(URI uri, ClassNode classNode) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        int insertLine = classRange.getEnd().getLine();
        String className = classNode.getNameWithoutPackage();

        String constructorText = "\n    " + className + "() {\n    }\n";

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                constructorText);

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction("Generate no-arg constructor");
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        return action;
    }
}
