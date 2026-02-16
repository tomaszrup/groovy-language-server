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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;

public class WorkspaceSymbolProvider {
	private ASTNodeVisitor ast;

	public WorkspaceSymbolProvider(ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<List<WorkspaceSymbol>> provideWorkspaceSymbols(String query) {
		if (ast == null) {
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		String lowerCaseQuery = query == null ? "" : query.toLowerCase();
		List<ASTNode> nodes = ast.getNodes();
		List<WorkspaceSymbol> symbols = nodes.stream()
				.filter(node -> {
					String name = getNodeName(node);
					return name != null && name.toLowerCase().contains(lowerCaseQuery);
				})
				.map(this::toWorkspaceSymbol)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
		return CompletableFuture.completedFuture(symbols);
	}

	private String getNodeName(ASTNode node) {
		if (node instanceof ClassNode) {
			return ((ClassNode) node).getName();
		}
		if (node instanceof MethodNode) {
			return ((MethodNode) node).getName();
		}
		if (node instanceof FieldNode) {
			return ((FieldNode) node).getName();
		}
		if (node instanceof PropertyNode) {
			return ((PropertyNode) node).getName();
		}
		return null;
	}

	private WorkspaceSymbol toWorkspaceSymbol(ASTNode node) {
		URI uri = ast.getURI(node);
		if (uri == null) {
			return null;
		}
		Location location = GroovyLanguageServerUtils.astNodeToLocation(node, uri);
		if (location == null) {
			return null;
		}
		SymbolKind kind = GroovyLanguageServerUtils.astNodeToSymbolKind(node);
		String name = getNodeName(node);
		if (name == null) {
			return null;
		}
		if (node instanceof ClassNode) {
			return new WorkspaceSymbol(name, kind, Either.forLeft(location), null);
		}
		ClassNode classNode = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class, ast);
		String containerName = classNode != null ? classNode.getName() : null;
		return new WorkspaceSymbol(name, kind, Either.forLeft(location), containerName);
	}
}