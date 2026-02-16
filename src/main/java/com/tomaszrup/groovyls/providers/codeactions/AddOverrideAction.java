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
import java.util.Collections;
import java.util.List;

import com.tomaszrup.groovyls.util.FileContentsTracker;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
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
 * Provides "Add @Override" code action for methods that override a parent method
 * but are missing the {@code @Override} annotation.
 */
public class AddOverrideAction {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final String JAVA_LANG_OVERRIDE = "java.lang.Override";
    private static final String OVERRIDE_SIMPLE_NAME = "Override";

    private ASTNodeVisitor ast;
    private FileContentsTracker fileContentsTracker;

    public AddOverrideAction(ASTNodeVisitor ast, FileContentsTracker fileContentsTracker) {
        this.ast = ast;
        this.fileContentsTracker = fileContentsTracker;
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

        // Check if cursor is on a method
        ASTNode enclosingMethod = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, MethodNode.class, ast);
        if (!(enclosingMethod instanceof MethodNode)) {
            return Collections.emptyList();
        }

        MethodNode methodNode = (MethodNode) enclosingMethod;

        // Skip if already has @Override
        if (hasOverrideAnnotation(methodNode)) {
            return Collections.emptyList();
        }

        // Find the enclosing class
        ASTNode enclosingClass = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ClassNode.class, ast);
        if (!(enclosingClass instanceof ClassNode)) {
            return Collections.emptyList();
        }

        ClassNode classNode = (ClassNode) enclosingClass;

        // Check if this method overrides a parent method
        if (!overridesParentMethod(classNode, methodNode)) {
            return Collections.emptyList();
        }

        // Create the code action
        CodeAction action = createAddOverrideAction(uri, methodNode);
        if (action == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(action);
    }

    private boolean hasOverrideAnnotation(MethodNode method) {
        for (AnnotationNode annotation : method.getAnnotations()) {
            if (annotation.getClassNode().getName().equals(JAVA_LANG_OVERRIDE)
                    || annotation.getClassNode().getNameWithoutPackage().equals(OVERRIDE_SIMPLE_NAME)) {
                return true;
            }
        }
        return false;
    }

    private boolean overridesParentMethod(ClassNode classNode, MethodNode method) {
        String methodName = method.getName();
        Parameter[] params = method.getParameters();
        if (overridesInSuperClasses(classNode, methodName, params)) {
            return true;
        }
        return overridesInInterfaces(classNode, methodName, params);
    }

    private boolean overridesInSuperClasses(ClassNode classNode, String methodName, Parameter[] params) {
        ClassNode superClass = safeGetSuperClass(classNode);
        while (superClass != null && !JAVA_LANG_OBJECT.equals(superClass.getName())) {
            if (hasMatchingMethod(superClass.getMethods(), methodName, params)) {
                return true;
            }
            superClass = safeGetSuperClass(superClass);
        }
        return false;
    }

    private boolean overridesInInterfaces(ClassNode classNode, String methodName, Parameter[] params) {
        for (ClassNode iface : classNode.getAllInterfaces()) {
            if (hasMatchingMethod(iface.getMethods(), methodName, params)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMatchingMethod(List<MethodNode> methods, String methodName, Parameter[] params) {
        for (MethodNode candidate : methods) {
            if (candidate.getName().equals(methodName)
                    && parametersMatch(candidate.getParameters(), params)) {
                return true;
            }
        }
        return false;
    }

    private ClassNode safeGetSuperClass(ClassNode classNode) {
        try {
            return classNode.getSuperClass();
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    private boolean parametersMatch(Parameter[] a, Parameter[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].getType().getName().equals(b[i].getType().getName())) {
                return false;
            }
        }
        return true;
    }

    private CodeAction createAddOverrideAction(URI uri, MethodNode method) {
        Range methodRange = GroovyLanguageServerUtils.astNodeToRange(method);
        if (methodRange == null) {
            return null;
        }

        int insertLine = methodRange.getStart().getLine();
        int insertCol = methodRange.getStart().getCharacter();
        char indentChar = detectIndentChar(uri, insertLine);
        String annotationText = buildIndentation(insertCol, indentChar) + "@Override\n";

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                annotationText);

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction("Add @Override to '" + method.getName() + "'");
        action.setKind(CodeActionKind.QuickFix);
        action.setEdit(workspaceEdit);
        return action;
    }

    private char detectIndentChar(URI uri, int insertLine) {
        if (fileContentsTracker == null) {
            return ' ';
        }

        String contents = fileContentsTracker.getContents(uri);
        if (contents == null) {
            return ' ';
        }

        String[] lines = contents.split("\n", -1);
        if (insertLine >= lines.length) {
            return ' ';
        }

        String methodLine = lines[insertLine];
        for (int i = 0; i < methodLine.length(); i++) {
            char current = methodLine.charAt(i);
            if (current == '\t') {
                return '\t';
            }
            if (current != ' ') {
                return ' ';
            }
        }
        return ' ';
    }

    private String buildIndentation(int indentWidth, char indentChar) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < indentWidth; i++) {
            indent.append(indentChar);
        }
        return indent.toString();
    }
}
