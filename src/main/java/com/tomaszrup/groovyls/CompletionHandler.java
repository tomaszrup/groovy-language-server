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
package com.tomaszrup.groovyls;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Handles LSP completion requests.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class CompletionHandler {

	private final ProjectScopeManager scopeManager;
	private final CompilationService compilationService;
	private final LspProviderFacade providerFacade;
	private final FileContentsTracker fileContentsTracker;

	CompletionHandler(
			ProjectScopeManager scopeManager,
			CompilationService compilationService,
			LspProviderFacade providerFacade,
			FileContentsTracker fileContentsTracker) {
		this.scopeManager = scopeManager;
		this.compilationService = compilationService;
		this.providerFacade = providerFacade;
		this.fileContentsTracker = fileContentsTracker;
	}

	CompletableFuture<Either<List<CompletionItem>, CompletionList>> provideCompletionForUri(
			CompletionParams params,
			URI uri,
			Position position) {
		ProjectScope scope = scopeManager.findProjectScope(uri);
		if (scope == null) {
			return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
		}
		CompletionContext context = captureCompletionContext(scope, uri, position);
		if (context == null) {
			return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
		}
		return buildCompletionResult(params, uri, position, context);
	}

	private CompletionContext captureCompletionContext(ProjectScope scope, URI uri, Position position) {
		scope.getLock().writeLock().lock();
		try {
			compilationService.ensureScopeCompiled(scope);
			if (scope.getAstVisitor() == null) {
				return null;
			}
			recompileScopeForCompletion(scope, uri);

			String originalSource = injectCompletionPlaceholderIfNeeded(scope, uri, position);
			CompletionContext context = new CompletionContext(
					scope.getAstVisitor(),
					scope.ensureClasspathSymbolIndexUnsafe(),
					scope.getClasspathSymbolClasspathElements());
			restoreCompletionSourceIfNeeded(scope, uri, originalSource);
			return context;
		} finally {
			scope.getLock().writeLock().unlock();
		}
	}

	private void recompileScopeForCompletion(ProjectScope scope, URI uri) {
		if (!fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
			compilationService.recompileIfContextChanged(scope, uri);
		} else {
			compilationService.compileAndVisitAST(scope, uri);
		}
	}

	private String injectCompletionPlaceholderIfNeeded(ProjectScope scope, URI uri, Position position) {
		ASTNode offsetNode = scope.getAstVisitor().getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode != null) {
			return null;
		}
		return compilationService.injectCompletionPlaceholder(scope, uri, position);
	}

	private void restoreCompletionSourceIfNeeded(ProjectScope scope, URI uri, String originalSource) {
		if (originalSource != null) {
			compilationService.restoreDocumentSource(scope, uri, originalSource);
		}
	}

	private CompletableFuture<Either<List<CompletionItem>, CompletionList>> buildCompletionResult(
			CompletionParams params,
			URI uri,
			Position position,
			CompletionContext context) {
		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result =
				providerFacade.provideCompletion(
						context.visitor,
						context.classpathSymbolIndex,
						context.classpathSymbolClasspathElements,
						params.getTextDocument(),
						params.getPosition());

		List<CompletionItem> spockItems = resolveSpockCompletionItems(context.visitor, uri, position);
		if (spockItems.isEmpty()) {
			return result;
		}
		return result.thenApply(either -> mergeCompletionItems(either, spockItems));
	}

	private List<CompletionItem> resolveSpockCompletionItems(ASTNodeVisitor visitor, URI uri, Position position) {
		ASTNode currentNode = visitor.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (currentNode == null) {
			return Collections.emptyList();
		}
		return providerFacade.provideSpockCompletions(visitor, uri, position, currentNode);
	}

	private Either<List<CompletionItem>, CompletionList> mergeCompletionItems(
			Either<List<CompletionItem>, CompletionList> base,
			List<CompletionItem> spockItems) {
		if (base.isLeft()) {
			List<CompletionItem> combined = new ArrayList<>(base.getLeft());
			combined.addAll(spockItems);
			return Either.forLeft(combined);
		}
		CompletionList list = base.getRight();
		list.getItems().addAll(spockItems);
		return Either.forRight(list);
	}

	static final class CompletionContext {
		final ASTNodeVisitor visitor;
		final ClasspathSymbolIndex classpathSymbolIndex;
		final Set<String> classpathSymbolClasspathElements;

		CompletionContext(
				ASTNodeVisitor visitor,
				ClasspathSymbolIndex classpathSymbolIndex,
				Set<String> classpathSymbolClasspathElements) {
			this.visitor = visitor;
			this.classpathSymbolIndex = classpathSymbolIndex;
			this.classpathSymbolClasspathElements = classpathSymbolClasspathElements;
		}
	}
}
