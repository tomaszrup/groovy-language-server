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
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.lsp.utils.Ranges;

/**
 * Provides inlay hints for Groovy source files. Inlay hints are inline
 * annotations displayed in the editor to show inferred types and parameter
 * names.
 *
 * <p>Supported hint kinds:</p>
 * <ul>
 *   <li><b>Type hints</b> — shown after {@code def} variable declarations
 *       where the type can be inferred from the right-hand side</li>
 *   <li><b>Parameter hints</b> — shown before method/constructor call
 *       arguments to indicate the parameter name</li>
 * </ul>
 */
public class InlayHintProvider {

    private static final String OBJECT_TYPE = "Object";
    private static final String VOID_TYPE = "void";

    private final ASTNodeVisitor ast;

    public InlayHintProvider(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public CompletableFuture<List<InlayHint>> provideInlayHints(InlayHintParams params) {
        if (ast == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        URI uri = URI.create(params.getTextDocument().getUri());
        Range visibleRange = params.getRange();
        List<ASTNode> nodes = ast.getNodes(uri);
        if (nodes == null || nodes.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<InlayHint> hints = new ArrayList<>();

        for (ASTNode node : nodes) {
            if (node.getLineNumber() == -1) {
                continue;
            }
            Range nodeRange = GroovyLanguageServerUtils.astNodeToRange(node);
            if (nodeRange == null) {
                continue;
            }
            // Skip nodes outside the visible range
            if (!rangesOverlap(visibleRange, nodeRange)) {
                continue;
            }

            if (node instanceof DeclarationExpression) {
                InlayHint typeHint = createTypeHint((DeclarationExpression) node);
                if (typeHint != null) {
                    hints.add(typeHint);
                }
            } else if (node instanceof MethodCallExpression) {
                hints.addAll(createParameterHints((MethodCallExpression) node));
            } else if (node instanceof ConstructorCallExpression) {
                hints.addAll(createConstructorParameterHints((ConstructorCallExpression) node));
            }
        }

        return CompletableFuture.completedFuture(hints);
    }

    /**
     * Creates a type hint for a {@code def} variable declaration where the type
     * can be inferred.
     * <p>Example: {@code def x = "hello"} → shows {@code : String} after {@code x}</p>
     */
    private InlayHint createTypeHint(DeclarationExpression declExpr) {
        if (declExpr.isMultipleAssignmentDeclaration()) {
            return null;
        }

        VariableExpression varExpr = declExpr.getVariableExpression();
        if (varExpr == null) {
            return null;
        }

        // Only show type hints for dynamically typed (def) variables
        if (!varExpr.isDynamicTyped()) {
            return null;
        }

        // Try to infer the type from the right-hand side
        ClassNode inferredType = GroovyASTUtils.getTypeOfNode(declExpr.getRightExpression(), ast);
        if (inferredType == null) {
            return null;
        }

        String typeName = inferredType.getNameWithoutPackage();
        // Don't show hint if the type is Object (uninformative) or void
        if (OBJECT_TYPE.equals(typeName) || VOID_TYPE.equals(typeName)) {
            return null;
        }

        // Position the hint right after the variable name
        Range varRange = GroovyLanguageServerUtils.astNodeToRange(varExpr);
        if (varRange == null) {
            return null;
        }

        Position hintPosition = varRange.getEnd();
        InlayHint hint = new InlayHint();
        hint.setPosition(hintPosition);
        hint.setLabel(Either.forLeft(": " + typeName));
        hint.setKind(InlayHintKind.Type);
        hint.setPaddingLeft(false);
        hint.setPaddingRight(true);
        return hint;
    }

    /**
     * Creates parameter name hints for method call arguments.
     * <p>Example: {@code greet("world", 3)} → shows {@code name:} before
     * {@code "world"} and {@code count:} before {@code 3}</p>
     */
    private List<InlayHint> createParameterHints(MethodCallExpression methodCall) {
        return createParameterHintsForCall(methodCall);
    }

    /**
     * Creates parameter name hints for constructor call arguments.
     */
    private List<InlayHint> createConstructorParameterHints(ConstructorCallExpression constructorCall) {
        return createParameterHintsForCall(constructorCall);
    }

    private List<InlayHint> createParameterHintsForCall(MethodCall call) {
        if (!(call.getArguments() instanceof ArgumentListExpression)) {
            return Collections.emptyList();
        }

        ArgumentListExpression argList = (ArgumentListExpression) call.getArguments();
        List<Expression> args = argList.getExpressions();
        if (args.isEmpty()) {
            return Collections.emptyList();
        }

        MethodNode method = GroovyASTUtils.getMethodFromCallExpression(call, ast);
        if (method == null) {
            return Collections.emptyList();
        }

        Parameter[] params = method.getParameters();
        if (params.length == 0) {
            return Collections.emptyList();
        }

        List<InlayHint> hints = new ArrayList<>();
        int count = Math.min(args.size(), params.length);
        for (int i = 0; i < count; i++) {
            Expression arg = args.get(i);
            Parameter param = params[i];

            // Skip if the argument is a closure (too noisy)
            if (arg instanceof ClosureExpression) {
                continue;
            }

            String paramName = param.getName();

            // Skip if the argument text already matches the parameter name
            // (e.g., passing a variable with the same name as the parameter)
            if (arg instanceof VariableExpression) {
                VariableExpression varExpr = (VariableExpression) arg;
                if (varExpr.getName().equals(paramName)) {
                    continue;
                }
            }

            // Skip single-parameter calls with obvious semantics
            if (params.length == 1 && isObviousSingleParam(paramName)) {
                continue;
            }

            Range argRange = GroovyLanguageServerUtils.astNodeToRange(arg);
            if (argRange == null) {
                continue;
            }

            Position hintPosition = argRange.getStart();
            InlayHint hint = new InlayHint();
            hint.setPosition(hintPosition);
            hint.setLabel(Either.forLeft(paramName + ":"));
            hint.setKind(InlayHintKind.Parameter);
            hint.setPaddingLeft(false);
            hint.setPaddingRight(true);
            hints.add(hint);
        }

        return hints;
    }

    /**
     * Returns true if the parameter name is too obvious for a single-parameter
     * method and should not get a hint.
     */
    private boolean isObviousSingleParam(String paramName) {
        switch (paramName) {
            case "value":
            case "arg":
            case "obj":
            case "it":
            case "self":
            case "input":
            case "param":
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks whether two ranges overlap (share at least one position).
     */
    private boolean rangesOverlap(Range a, Range b) {
        if (a == null || b == null) {
            return true; // if no range constraint, include everything
        }
        // a ends before b starts, or b ends before a starts → no overlap
        if (comparePositions(a.getEnd(), b.getStart()) < 0) {
            return false;
        }
        if (comparePositions(b.getEnd(), a.getStart()) < 0) {
            return false;
        }
        return true;
    }

    private int comparePositions(Position a, Position b) {
        if (a.getLine() != b.getLine()) {
            return Integer.compare(a.getLine(), b.getLine());
        }
        return Integer.compare(a.getCharacter(), b.getCharacter());
    }
}
