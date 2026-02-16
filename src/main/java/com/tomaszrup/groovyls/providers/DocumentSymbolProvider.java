////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.SpockUtils;

public class DocumentSymbolProvider {
	private ASTNodeVisitor ast;

	public DocumentSymbolProvider(ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> provideDocumentSymbols(
			TextDocumentIdentifier textDocument) {
		if (ast == null) {
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		URI uri = URI.create(textDocument.getUri());
		List<ASTNode> nodes = ast.getNodes(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = nodes.stream()
				.filter(this::isSymbolNode)
				.map(this::toDocumentSymbol)
				.filter(Objects::nonNull)
				.map(Either::<SymbolInformation, DocumentSymbol>forRight)
				.collect(Collectors.toList());
		return CompletableFuture.completedFuture(symbols);
	}

	private boolean isSymbolNode(ASTNode node) {
		return node instanceof ClassNode
				|| node instanceof MethodNode
				|| node instanceof FieldNode
				|| node instanceof PropertyNode;
	}

	private DocumentSymbol toDocumentSymbol(ASTNode node) {
		if (node instanceof ClassNode) {
			return toClassSymbol((ClassNode) node);
		}

		ClassNode classNode = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class, ast);
		if (node instanceof MethodNode) {
			return toMethodSymbol((MethodNode) node, classNode);
		}
		if (node instanceof PropertyNode) {
			return toPropertySymbol((PropertyNode) node, classNode);
		}
		if (node instanceof FieldNode) {
			return toFieldSymbol((FieldNode) node, classNode);
		}
		return null;
	}

	private DocumentSymbol toClassSymbol(ClassNode classNode) {
		Range range = GroovyLanguageServerUtils.astNodeToRange(classNode);
		if (range == null) {
			return null;
		}

		String symbolName = classNode.getName();
		if (SpockUtils.isSpockSpecification(classNode)) {
			symbolName = "\u2731 " + symbolName;
		}
		SymbolKind symbolKind = GroovyLanguageServerUtils.astNodeToSymbolKind(classNode);
		return new DocumentSymbol(symbolName, symbolKind, range, range);
	}

	private DocumentSymbol toMethodSymbol(MethodNode methodNode, ClassNode classNode) {
		Range range = GroovyLanguageServerUtils.astNodeToRange(methodNode);
		if (range == null) {
			return null;
		}

		String symbolName = methodNode.getName();
		if (SpockUtils.isSpockFeatureMethod(methodNode)) {
			symbolName = "\u25b6 " + symbolName;
		} else if (SpockUtils.isSpockLifecycleMethod(methodNode.getName())
				&& SpockUtils.isSpockSpecification(classNode)) {
			symbolName = "\u2699 " + symbolName;
		}

		DocumentSymbol symbol = new DocumentSymbol(symbolName,
				GroovyLanguageServerUtils.astNodeToSymbolKind(methodNode), range, range);
		if (classNode != null) {
			symbol.setDetail(classNode.getName());
		}
		return symbol;
	}

	private DocumentSymbol toPropertySymbol(PropertyNode propNode, ClassNode classNode) {
		Range range = GroovyLanguageServerUtils.astNodeToRange(propNode);
		if (range == null) {
			return null;
		}

		DocumentSymbol symbol = new DocumentSymbol(propNode.getName(),
				GroovyLanguageServerUtils.astNodeToSymbolKind(propNode), range, range);
		if (classNode != null) {
			symbol.setDetail(classNode.getName());
		}
		return symbol;
	}

	private DocumentSymbol toFieldSymbol(FieldNode fieldNode, ClassNode classNode) {
		Range range = GroovyLanguageServerUtils.astNodeToRange(fieldNode);
		if (range == null) {
			return null;
		}

		DocumentSymbol symbol = new DocumentSymbol(fieldNode.getName(),
				GroovyLanguageServerUtils.astNodeToSymbolKind(fieldNode), range, range);
		if (classNode != null) {
			symbol.setDetail(classNode.getName());
		}
		return symbol;
	}
}