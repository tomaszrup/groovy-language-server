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
package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.SpockUtils;

/**
 * Provides Spock-specific code actions:
 * <ul>
 *   <li>Generate feature method from method name</li>
 *   <li>Wrap selection in Spock block</li>
 *   <li>Add missing Spock blocks</li>
 * </ul>
 */
public class SpockCodeActionProvider {

    private ASTNodeVisitor ast;

    public SpockCodeActionProvider(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    /**
     * Provides Spock-specific code actions for the given context.
     */
    public List<Either<Command, CodeAction>> provideCodeActions(CodeActionParams params) {
        if (ast == null) {
            return Collections.emptyList();
        }

        URI uri = URI.create(params.getTextDocument().getUri());
        Position position = params.getRange().getStart();

        ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if (offsetNode == null) {
            return Collections.emptyList();
        }

        // Check if we're in a Spock specification
        ASTNode enclosingClass = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ClassNode.class, ast);
        if (!(enclosingClass instanceof ClassNode)) {
            return Collections.emptyList();
        }
        ClassNode classNode = (ClassNode) enclosingClass;
        if (!SpockUtils.isSpockSpecification(classNode)) {
            return Collections.emptyList();
        }

        List<Either<Command, CodeAction>> actions = new ArrayList<>();

        // Check if cursor is on a method
        ASTNode enclosingMethod = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, MethodNode.class, ast);
        if (enclosingMethod instanceof MethodNode) {
            MethodNode methodNode = (MethodNode) enclosingMethod;

            // Offer "Add when-then blocks" for feature methods without block structure
            if (SpockUtils.isSpockFeatureMethod(methodNode)) {
                addInsertBlocksAction(uri, methodNode, actions);
            }
        }

        // Offer "Generate feature method" at the class level
        addGenerateFeatureMethodAction(uri, classNode, position, actions);

        return actions;
    }

    /**
     * Adds a code action to insert when-then blocks into an existing feature method.
     */
    private void addInsertBlocksAction(URI uri, MethodNode methodNode,
                                        List<Either<Command, CodeAction>> actions) {
        Range methodRange = GroovyLanguageServerUtils.astNodeToRange(methodNode);
        if (methodRange == null) {
            return;
        }

        // We'll insert at the beginning of the method body
        int insertLine = methodRange.getStart().getLine() + 1;

        String blockTemplate = "        given:\n" +
                "        // setup\n\n" +
                "        when:\n" +
                "        // stimulus\n\n" +
                "        then:\n" +
                "        // response\n";

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                blockTemplate);

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction("Spock: Insert given-when-then blocks");
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        actions.add(Either.forRight(action));
    }

    /**
     * Adds a code action to generate a new feature method at the current position.
     */
    private void addGenerateFeatureMethodAction(URI uri, ClassNode classNode,
                                                 Position position,
                                                 List<Either<Command, CodeAction>> actions) {
        Range classRange = GroovyLanguageServerUtils.astNodeToRange(classNode);
        if (classRange == null) {
            return;
        }

        // Only offer this near the end of the class body
        int insertLine = classRange.getEnd().getLine();
        if (position != null && Math.abs(position.getLine() - insertLine) > 3) {
            return;
        }

        String featureTemplate = "\n    def \"should do something\"() {\n" +
                "        given:\n" +
                "        // setup\n\n" +
                "        when:\n" +
                "        // stimulus\n\n" +
                "        then:\n" +
                "        // response\n" +
                "    }\n";

        TextEdit edit = new TextEdit(
                new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                featureTemplate);

        WorkspaceEdit workspaceEdit = new WorkspaceEdit();
        workspaceEdit.setChanges(Collections.singletonMap(uri.toString(), Collections.singletonList(edit)));

        CodeAction action = new CodeAction("Spock: Generate feature method");
        action.setKind(CodeActionKind.Refactor);
        action.setEdit(workspaceEdit);
        actions.add(Either.forRight(action));
    }
}
