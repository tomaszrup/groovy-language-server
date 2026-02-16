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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.CodeActionProvider;
import com.tomaszrup.groovyls.providers.FormattingProvider;
import com.tomaszrup.groovyls.providers.RenameProvider;
import com.tomaszrup.groovyls.providers.SpockCodeActionProvider;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

/**
 * Sub-facade for refactoring-related LSP providers: rename,
 * code actions, Spock code actions, and formatting.
 */
final class RefactoringProviderFacade {
    private final FileContentsTracker fileContentsTracker;

    RefactoringProviderFacade(FileContentsTracker fileContentsTracker) {
        this.fileContentsTracker = fileContentsTracker;
    }

    CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> providePrepareRename(
            ASTNodeVisitor visitor,
            PrepareRenameParams params) {
        RenameProvider provider = new RenameProvider(visitor, fileContentsTracker);
        return provider.providePrepareRename(params);
    }

    CompletableFuture<WorkspaceEdit> provideRename(ASTNodeVisitor visitor, RenameParams params) {
        RenameProvider provider = new RenameProvider(visitor, fileContentsTracker);
        return provider.provideRename(params);
    }

    CompletableFuture<List<Either<Command, CodeAction>>> provideCodeActions(
            ASTNodeVisitor visitor,
            ClasspathSymbolIndex classpathSymbolIndex,
            Set<String> classpathSymbolClasspathElements,
            JavaSourceLocator javaSourceLocator,
            CodeActionParams params) {
        CodeActionProvider provider = new CodeActionProvider(
                visitor,
                classpathSymbolIndex,
                classpathSymbolClasspathElements,
                fileContentsTracker,
                javaSourceLocator);
        return provider.provideCodeActions(params);
    }

    List<Either<Command, CodeAction>> provideSpockCodeActions(ASTNodeVisitor visitor, CodeActionParams params) {
        SpockCodeActionProvider spockProvider = new SpockCodeActionProvider(visitor);
        return spockProvider.provideCodeActions(params);
    }

    CompletableFuture<List<TextEdit>> provideFormatting(DocumentFormattingParams params, String textForFormatting) {
        FormattingProvider provider = new FormattingProvider();
        return provider.provideFormatting(params, textForFormatting);
    }
}
