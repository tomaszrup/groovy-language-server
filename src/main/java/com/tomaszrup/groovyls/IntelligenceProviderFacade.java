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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.CompletionProvider;
import com.tomaszrup.groovyls.providers.HoverProvider;
import com.tomaszrup.groovyls.providers.SignatureHelpProvider;
import com.tomaszrup.groovyls.providers.SpockCompletionProvider;

/**
 * Sub-facade for intelligence-related LSP providers: hover, completion,
 * Spock completions, and signature help.
 */
final class IntelligenceProviderFacade {

    CompletableFuture<Hover> provideHover(ASTNodeVisitor visitor, TextDocumentIdentifier textDocument, Position position) {
        HoverProvider provider = new HoverProvider(visitor);
        return provider.provideHover(textDocument, position);
    }

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> provideCompletion(
            ASTNodeVisitor visitor,
            ClasspathSymbolIndex classpathSymbolIndex,
            Set<String> classpathSymbolClasspathElements,
            TextDocumentIdentifier textDocument,
            Position position) {
        CompletionProvider provider = new CompletionProvider(visitor, classpathSymbolIndex, classpathSymbolClasspathElements);
        return provider.provideCompletion(textDocument, position);
    }

    List<CompletionItem> provideSpockCompletions(ASTNodeVisitor visitor, URI uri, Position position, ASTNode currentNode) {
        SpockCompletionProvider spockProvider = new SpockCompletionProvider(visitor);
        return spockProvider.provideSpockCompletions(uri, position, currentNode);
    }

    CompletableFuture<SignatureHelp> provideSignatureHelp(ASTNodeVisitor visitor, TextDocumentIdentifier textDocument, Position position) {
        SignatureHelpProvider provider = new SignatureHelpProvider(visitor);
        return provider.provideSignatureHelp(textDocument, position);
    }
}
