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

import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.DefinitionProvider;
import com.tomaszrup.groovyls.providers.DocumentHighlightProvider;
import com.tomaszrup.groovyls.providers.ImplementationProvider;
import com.tomaszrup.groovyls.providers.ReferenceProvider;
import com.tomaszrup.groovyls.providers.TypeDefinitionProvider;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

/**
 * Sub-facade for navigation-related LSP providers: definition,
 * type-definition, implementation, document highlights, and references.
 */
final class NavigationProviderFacade {

    CompletableFuture<Either<List<Location>, List<LocationLink>>> provideDefinition(
            ASTNodeVisitor visitor,
            JavaSourceLocator javaSourceLocator,
            TextDocumentIdentifier textDocument,
            Position position) {
        DefinitionProvider provider = new DefinitionProvider(visitor, javaSourceLocator);
        return provider.provideDefinition(textDocument, position);
    }

    CompletableFuture<Either<List<Location>, List<LocationLink>>> provideTypeDefinition(
            ASTNodeVisitor visitor,
            JavaSourceLocator javaSourceLocator,
            TextDocumentIdentifier textDocument,
            Position position) {
        TypeDefinitionProvider provider = new TypeDefinitionProvider(visitor, javaSourceLocator);
        return provider.provideTypeDefinition(textDocument, position);
    }

    CompletableFuture<Either<List<Location>, List<LocationLink>>> provideImplementation(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Position position) {
        ImplementationProvider provider = new ImplementationProvider(visitor);
        return provider.provideImplementation(textDocument, position);
    }

    @SuppressWarnings("java:S1452")
    CompletableFuture<List<? extends DocumentHighlight>> provideDocumentHighlights(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Position position) {
        DocumentHighlightProvider provider = new DocumentHighlightProvider(visitor);
        return provider.provideDocumentHighlights(textDocument, position).thenApply(highlights -> highlights);
    }

    @SuppressWarnings("java:S1452")
    CompletableFuture<List<? extends Location>> provideReferences(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Position position) {
        ReferenceProvider provider = new ReferenceProvider(visitor);
        return provider.provideReferences(textDocument, position).thenApply(locations -> locations);
    }
}
