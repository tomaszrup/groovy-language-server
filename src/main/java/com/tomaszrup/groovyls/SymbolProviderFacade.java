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
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.DocumentSymbolProvider;
import com.tomaszrup.groovyls.providers.InlayHintProvider;
import com.tomaszrup.groovyls.providers.SemanticTokensProvider;
import com.tomaszrup.groovyls.providers.WorkspaceSymbolProvider;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Sub-facade for symbol-related LSP providers: document symbols,
 * workspace symbols, inlay hints, and semantic tokens.
 */
final class SymbolProviderFacade {
    private final FileContentsTracker fileContentsTracker;

    SymbolProviderFacade(FileContentsTracker fileContentsTracker) {
        this.fileContentsTracker = fileContentsTracker;
    }

    CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> provideDocumentSymbols(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument) {
        DocumentSymbolProvider provider = new DocumentSymbolProvider(visitor);
        return provider.provideDocumentSymbols(textDocument);
    }

    CompletableFuture<List<WorkspaceSymbol>> provideWorkspaceSymbols(ASTNodeVisitor visitor, String query) {
        WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(visitor);
        return provider.provideWorkspaceSymbols(query);
    }

    CompletableFuture<List<InlayHint>> provideInlayHints(ASTNodeVisitor visitor, InlayHintParams params) {
        InlayHintProvider provider = new InlayHintProvider(visitor);
        return provider.provideInlayHints(params);
    }

    CompletableFuture<SemanticTokens> provideSemanticTokensFull(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            boolean groovy4Compatibility) {
        SemanticTokensProvider provider = new SemanticTokensProvider(visitor, fileContentsTracker, groovy4Compatibility);
        return provider.provideSemanticTokensFull(textDocument);
    }

    CompletableFuture<SemanticTokens> provideSemanticTokensRange(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Range range,
            boolean groovy4Compatibility) {
        SemanticTokensProvider provider = new SemanticTokensProvider(visitor, fileContentsTracker, groovy4Compatibility);
        return provider.provideSemanticTokensRange(textDocument, range);
    }
}
