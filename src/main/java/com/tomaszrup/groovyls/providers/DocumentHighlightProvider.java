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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;

public class DocumentHighlightProvider {
    private ASTNodeVisitor ast;

    public DocumentHighlightProvider(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public CompletableFuture<List<? extends DocumentHighlight>> provideDocumentHighlights(
            TextDocumentIdentifier textDocument, Position position) {
        if (ast == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        URI documentURI = URI.create(textDocument.getUri());
        ASTNode offsetNode = ast.getNodeAtLineAndColumn(documentURI, position.getLine(), position.getCharacter());
        if (offsetNode == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        ASTNode definitionNode = GroovyASTUtils.getDefinition(offsetNode, true, ast);
        if (definitionNode == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<ASTNode> references = GroovyASTUtils.getReferences(offsetNode, ast);
        List<DocumentHighlight> highlights = references.stream()
                .filter(node -> {
                    URI nodeURI = ast.getURI(node);
                    return nodeURI != null && nodeURI.equals(documentURI);
                })
                .map(node -> {
                    Range range = GroovyLanguageServerUtils.astNodeToRange(node);
                    if (range == null) {
                        return null;
                    }
                    DocumentHighlightKind kind = node.equals(definitionNode)
                            ? DocumentHighlightKind.Write
                            : DocumentHighlightKind.Read;
                    return new DocumentHighlight(range, kind);
                })
                .filter(highlight -> highlight != null)
                .collect(Collectors.toList());

        return CompletableFuture.completedFuture(highlights);
    }
}
