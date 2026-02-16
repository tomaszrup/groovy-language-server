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
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensRangeParams;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.SemanticTokensProvider;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.GroovyVersionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles LSP semantic tokens requests (full and range), including fallback
 * caching of the last successful result per URI.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class SemanticTokensHandler {
	private static final Logger logger = LoggerFactory.getLogger(SemanticTokensHandler.class);

	private final Map<URI, SemanticTokens> lastSemanticTokensByUri = new ConcurrentHashMap<>();
	private final ProjectScopeManager scopeManager;
	private final LspProviderFacade providerFacade;
	private final Function<URI, ProjectScope> ensureCompiledForContext;
	private final FileContentsTracker fileContentsTracker;

	SemanticTokensHandler(
			ProjectScopeManager scopeManager,
			LspProviderFacade providerFacade,
			Function<URI, ProjectScope> ensureCompiledForContext,
			FileContentsTracker fileContentsTracker) {
		this.scopeManager = scopeManager;
		this.providerFacade = providerFacade;
		this.ensureCompiledForContext = ensureCompiledForContext;
		this.fileContentsTracker = fileContentsTracker;
	}

	/**
	 * Determines whether Groovy-4 column compatibility mode is required
	 * for semantic token position calculations.
	 */
	boolean isGroovy4ColumnCompatibilityRequired(ProjectScope scope) {
		try {
			Integer runtimeMajor = GroovyVersionDetector.major(groovy.lang.GroovySystem.getVersion()).orElse(null);
			if (runtimeMajor != null && runtimeMajor <= 4) {
				return true;
			}
		} catch (Exception t) {
			logger.debug("Could not detect Groovy runtime version for semantic token compatibility: {}",
					t.toString());
		}

		if (scope == null) {
			return false;
		}

		Integer projectMajor = GroovyVersionDetector.major(scope.getDetectedGroovyVersion()).orElse(null);
		return projectMajor != null && projectMajor <= 4;
	}

	SemanticTokensProvider createSemanticTokensProvider(ASTNodeVisitor visitor) {
		return new SemanticTokensProvider(visitor, fileContentsTracker);
	}

	SemanticTokensProvider createSemanticTokensProvider(ASTNodeVisitor visitor, ProjectScope scope) {
		boolean groovy4Compatibility = isGroovy4ColumnCompatibilityRequired(scope);
		return new SemanticTokensProvider(visitor, fileContentsTracker, groovy4Compatibility);
	}

	void clearCache(URI uri) {
		lastSemanticTokensByUri.remove(uri);
	}

	CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		if (!scopeManager.isSemanticHighlightingEnabled()) {
			return completedEmptySemanticTokens();
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		try {
			ProjectScope scope = ensureCompiledForContext.apply(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return handleSemanticTokensFullVisitorUnavailable(uri, scope);
			}
			return provideSemanticTokensFull(params, uri, scope, visitor);
		} catch (LinkageError e) {
			return handleSemanticTokensFullLinkageError(uri, e);
		}
	}

	private CompletableFuture<SemanticTokens> provideSemanticTokensFull(
			SemanticTokensParams params,
			URI uri,
			ProjectScope scope,
			ASTNodeVisitor visitor) {
		Path projectRoot = scope.getProjectRoot();
		boolean groovy4Compatibility = isGroovy4ColumnCompatibilityRequired(scope);
		return providerFacade.provideSemanticTokensFull(visitor, params.getTextDocument(), groovy4Compatibility)
				.handle((tokens, throwable) -> handleSemanticTokensFullResult(uri, projectRoot, tokens, throwable));
	}

	private CompletableFuture<SemanticTokens> handleSemanticTokensFullVisitorUnavailable(URI uri, ProjectScope scope) {
		logger.debug("semanticTokensFull uri={} projectRoot={} visitorUnavailable=true", uri,
				scope != null ? scope.getProjectRoot() : null);
		SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
		return CompletableFuture.completedFuture(fallback != null ? fallback : emptySemanticTokens());
	}

	private SemanticTokens handleSemanticTokensFullResult(
			URI uri,
			Path projectRoot,
			SemanticTokens tokens,
			Throwable throwable) {
		if (throwable != null) {
			logger.warn("semanticTokensFull failed uri={} projectRoot={} error={}",
					uri, projectRoot, throwable);
			logger.debug("semanticTokensFull failure details", throwable);
			SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
			return fallback != null ? fallback : emptySemanticTokens();
		}
		if (tokens != null && tokens.getData() != null && !tokens.getData().isEmpty()) {
			lastSemanticTokensByUri.put(uri, tokens);
			return tokens;
		}
		SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
		if (fallback != null) {
			logger.debug("semanticTokensFull uri={} projectRoot={} usingFallback=true", uri, projectRoot);
			return fallback;
		}
		return tokens != null ? tokens : emptySemanticTokens();
	}

	private CompletableFuture<SemanticTokens> handleSemanticTokensFullLinkageError(URI uri, LinkageError error) {
		ProjectScope scope = scopeManager.findProjectScope(uri);
		logger.warn("semanticTokensFull linkage error uri={} projectRoot={} error={}", uri,
				scope != null ? scope.getProjectRoot() : null, error);
		logger.debug("semanticTokensFull linkage error details", error);
		SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
		return CompletableFuture.completedFuture(fallback != null ? fallback : emptySemanticTokens());
	}

	CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
		if (!scopeManager.isSemanticHighlightingEnabled()) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		try {
			ProjectScope scope = ensureCompiledForContext.apply(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				logger.debug("semanticTokensRange uri={} projectRoot={} visitorUnavailable=true", uri,
						scope != null ? scope.getProjectRoot() : null);
				return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
			}

			Path projectRoot = scope.getProjectRoot();
			boolean groovy4Compatibility = isGroovy4ColumnCompatibilityRequired(scope);
			return providerFacade.provideSemanticTokensRange(
					visitor,
					params.getTextDocument(),
					params.getRange(),
					groovy4Compatibility)
					.handle((tokens, throwable) -> {
						if (throwable != null) {
							logger.warn(
									"semanticTokensRange failed uri={} projectRoot={} error={}",
									uri, projectRoot, throwable.toString());
							logger.debug("semanticTokensRange failure details", throwable);
							return new SemanticTokens(Collections.emptyList());
						}
						return tokens != null ? tokens : new SemanticTokens(Collections.emptyList());
					});
		} catch (LinkageError e) {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			logger.warn("semanticTokensRange linkage error uri={} projectRoot={} error={}", uri,
					scope != null ? scope.getProjectRoot() : null, e.toString());
			logger.debug("semanticTokensRange linkage error details", e);
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
	}

	private CompletableFuture<SemanticTokens> completedEmptySemanticTokens() {
		return CompletableFuture.completedFuture(emptySemanticTokens());
	}

	private SemanticTokens emptySemanticTokens() {
		return new SemanticTokens(Collections.emptyList());
	}
}
