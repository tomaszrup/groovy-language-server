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
 * Provides "Implement interface methods" code action.
 * When the cursor is on a class that implements interfaces or extends an abstract class,
 * this action generates stub implementations for all unimplemented abstract methods.
 */
public class ImplementInterfaceMethodsAction {

    private ASTNodeVisitor ast;

    public ImplementInterfaceMethodsAction(ASTNodeVisitor ast) {
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

        // Skip interfaces and abstract classes (they don't need to implement)
        if (classNode.isInterface() || classNode.isAbstract()) {
            return Collections.emptyList();
        }

        // Collect all abstract methods from interfaces and superclasses
        List<MethodNode> abstractMethods = collectUnimplementedMethods(classNode);
        if (abstractMethods.isEmpty()) {
            return Collections.emptyList();
        }

        // Build the code action
        CodeAction action = createImplementMethodsAction(uri, classNode, abstractMethods);
        if (action == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(action);
    }

    /**
     * Collects all abstract methods from interfaces and abstract superclasses
     * that are not already implemented by the given class.
     */
    private List<MethodNode> collectUnimplementedMethods(ClassNode classNode) {
        Set<String> implementedSignatures = new HashSet<>();

        // Collect signatures of methods already implemented
        for (MethodNode method : classNode.getMethods()) {
            if (!method.isAbstract()) {
                implementedSignatures.add(getMethodSignature(method));
            }
        }

        List<MethodNode> unimplemented = new ArrayList<>();
        Set<String> addedSignatures = new HashSet<>();

        // Walk interfaces
        for (ClassNode iface : classNode.getAllInterfaces()) {
            for (MethodNode method : iface.getMethods()) {
                if (method.isAbstract() || iface.isInterface()) {
                    String sig = getMethodSignature(method);
                    if (!implementedSignatures.contains(sig) && addedSignatures.add(sig)) {
                        unimplemented.add(method);
                    }
                }
            }
        }

        // Walk abstract superclass chain
        ClassNode superClass = null;
        try {
            superClass = classNode.getSuperClass();
        } catch (NoClassDefFoundError e) {
            // ignore
        }
        while (superClass != null && !superClass.getName().equals("java.lang.Object")) {
            for (MethodNode method : superClass.getMethods()) {
                if (method.isAbstract()) {
                    String sig = getMethodSignature(method);
                    if (!implementedSignatures.contains(sig) && addedSignatures.add(sig)) {
                        unimplemented.add(method);
                    }
                }
            }
            try {
                superClass = superClass.getSuperClass();
            } catch (NoClassDefFoundError e) {
                break;
            }
        }

        return unimplemented;
    }

    private String getMethodSignature(MethodNode method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName());
        sb.append("(");
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(params[i].getType().getName());
        }
        sb.append(")");
        return sb.toString();
    }

    private CodeAction createImplementMethodsAction(URI uri, ClassNode classNode,
            List<MethodNode> abstractMethods) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return null;
        }

        // Insert before the closing brace of the class
        int insertLine = classRange.getEnd().getLine();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        for (MethodNode method : abstractMethods) {
            sb.append("    @Override\n");
            sb.append("    ");

            // Return type
            String returnType = method.getReturnType().getNameWithoutPackage();
            sb.append(returnType);
            sb.append(" ");

            // Method name and parameters
            sb.append(method.getName());
            sb.append("(");
            Parameter[] params = method.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i].getType().getNameWithoutPackage());
                sb.append(" ");
                sb.append(params[i].getName());
            }
            sb.append(") {\n");

            // Default body
            sb.append("        throw new UnsupportedOperationException(\"Not yet implemented\")\n");

            sb.append("    }\n\n");
        }

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                sb.toString());

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        String methodNames = abstractMethods.stream()
                .map(MethodNode::getName)
                .collect(Collectors.joining(", "));

        CodeAction action = new CodeAction("Implement methods: " + methodNames);
        action.setKind(CodeActionKind.QuickFix);
        action.setEdit(workspaceEdit);
        return action;
    }
}
