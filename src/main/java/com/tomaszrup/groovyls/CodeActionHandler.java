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
package com.tomaszrup.groovyls;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;

/**
 * Handles LSP code-action requests, encapsulating the
 * {@link ClasspathSymbolIndex} dependency so that
 * {@link GroovyServices} no longer references it directly.
 */
final class CodeActionHandler {
    private final CompilationService compilationService;
    private final ProjectScopeManager scopeManager;
    private final ExecutorService backgroundCompiler;
    private final LspProviderFacade providerFacade;

    CodeActionHandler(CompilationService compilationService, ProjectScopeManager scopeManager,
                      ExecutorService backgroundCompiler, LspProviderFacade providerFacade) {
        this.compilationService = compilationService;
        this.scopeManager = scopeManager;
        this.backgroundCompiler = backgroundCompiler;
        this.providerFacade = providerFacade;
    }

    CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
        ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
        ClasspathSymbolIndex classpathSymbolIndex =
                scope != null ? scope.ensureClasspathSymbolIndex() : null;
        Set<String> classpathSymbolClasspathElements =
                scope != null ? scope.getClasspathSymbolClasspathElements() : null;
        if (visitor == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CompletableFuture<List<Either<Command, CodeAction>>> result = providerFacade.provideCodeActions(
                visitor,
                classpathSymbolIndex,
                classpathSymbolClasspathElements,
                scope.getJavaSourceLocator(),
                params);

        List<Either<Command, CodeAction>> spockActions = providerFacade.provideSpockCodeActions(visitor, params);
        if (!spockActions.isEmpty()) {
            result = result.thenApply(actions -> {
                List<Either<Command, CodeAction>> combined = new ArrayList<>(actions);
                combined.addAll(spockActions);
                return combined;
            });
        }

        return result;
    }
}
