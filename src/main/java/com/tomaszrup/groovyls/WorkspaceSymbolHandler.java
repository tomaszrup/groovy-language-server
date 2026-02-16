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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Handles LSP workspace symbol requests.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class WorkspaceSymbolHandler {

	private final ProjectScopeManager scopeManager;
	private final CompilationService compilationService;
	private final LspProviderFacade providerFacade;
	private final FileContentsTracker fileContentsTracker;

	WorkspaceSymbolHandler(
			ProjectScopeManager scopeManager,
			CompilationService compilationService,
			LspProviderFacade providerFacade,
			FileContentsTracker fileContentsTracker) {
		this.scopeManager = scopeManager;
		this.compilationService = compilationService;
		this.providerFacade = providerFacade;
		this.fileContentsTracker = fileContentsTracker;
	}

	CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			WorkspaceSymbolParams params) {
		List<ProjectScope> scopesToSearch = resolveScopesToSearchForWorkspaceSymbols();
		if (scopesToSearch.isEmpty()) {
			return CompletableFuture.completedFuture(Either.forRight(Collections.emptyList()));
		}

		synchronizeScopesForWorkspaceSymbols(scopesToSearch);

		List<CompletableFuture<List<WorkspaceSymbol>>> futures =
				createWorkspaceSymbolFutures(scopesToSearch, params.getQuery());
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
				.thenApply(v -> Either.forRight(joinWorkspaceSymbols(futures)));
	}

	private List<ProjectScope> resolveScopesToSearchForWorkspaceSymbols() {
		List<ProjectScope> allScopes = scopeManager.getAllScopes();
		if (allScopes.size() <= 1) {
			return allScopes;
		}
		Set<ProjectScope> openScopes = new LinkedHashSet<>();
		for (URI openUri : fileContentsTracker.getOpenURIs()) {
			ProjectScope openScope = scopeManager.findProjectScope(openUri);
			if (openScope != null) {
				openScopes.add(openScope);
			}
		}
		return openScopes.isEmpty() ? Collections.emptyList() : new ArrayList<>(openScopes);
	}

	private void synchronizeScopesForWorkspaceSymbols(List<ProjectScope> scopesToSearch) {
		for (ProjectScope scope : scopesToSearch) {
			synchronizeScopeForWorkspaceSymbols(scope);
		}
	}

	private void synchronizeScopeForWorkspaceSymbols(ProjectScope scope) {
		if (!shouldSyncScopeForWorkspaceSymbols(scope)) {
			return;
		}
		scope.getLock().writeLock().lock();
		try {
			boolean didFull = compilationService.ensureScopeCompiled(scope);
			if (!didFull && fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
				URI representative = firstChangedUriOrNull();
				if (representative != null) {
					compilationService.compileAndVisitAST(scope, representative);
				}
			}
		} finally {
			scope.getLock().writeLock().unlock();
		}
	}

	private boolean shouldSyncScopeForWorkspaceSymbols(ProjectScope scope) {
		return !scope.isCompiled() || fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot());
	}

	private URI firstChangedUriOrNull() {
		Set<URI> pending = fileContentsTracker.getChangedURIs();
		return pending.isEmpty() ? null : pending.iterator().next();
	}

	private List<CompletableFuture<List<WorkspaceSymbol>>> createWorkspaceSymbolFutures(
			List<ProjectScope> scopesToSearch,
			String query) {
		List<CompletableFuture<List<WorkspaceSymbol>>> futures = new ArrayList<>();
		for (ProjectScope scope : scopesToSearch) {
			ASTNodeVisitor visitor = scope.getAstVisitor();
			if (visitor != null) {
				futures.add(providerFacade.provideWorkspaceSymbols(visitor, query));
			}
		}
		return futures;
	}

	private List<WorkspaceSymbol> joinWorkspaceSymbols(List<CompletableFuture<List<WorkspaceSymbol>>> futures) {
		List<WorkspaceSymbol> allSymbols = new ArrayList<>();
		for (CompletableFuture<List<WorkspaceSymbol>> future : futures) {
			allSymbols.addAll(future.join());
		}
		return allSymbols;
	}
}
