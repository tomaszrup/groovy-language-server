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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraphException;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.providers.CodeActionProvider;
import com.tomaszrup.groovyls.providers.CompletionProvider;
import com.tomaszrup.groovyls.providers.DefinitionProvider;
import com.tomaszrup.groovyls.providers.DocumentHighlightProvider;
import com.tomaszrup.groovyls.providers.DocumentSymbolProvider;
import com.tomaszrup.groovyls.providers.HoverProvider;
import com.tomaszrup.groovyls.providers.ImplementationProvider;
import com.tomaszrup.groovyls.providers.FormattingProvider;
import com.tomaszrup.groovyls.providers.InlayHintProvider;
import com.tomaszrup.groovyls.providers.ReferenceProvider;
import com.tomaszrup.groovyls.providers.RenameProvider;
import com.tomaszrup.groovyls.providers.SemanticTokensProvider;
import com.tomaszrup.groovyls.providers.SignatureHelpProvider;
import com.tomaszrup.groovyls.providers.SpockCodeActionProvider;
import com.tomaszrup.groovyls.providers.SpockCompletionProvider;
import com.tomaszrup.groovyls.providers.TypeDefinitionProvider;
import com.tomaszrup.groovyls.providers.WorkspaceSymbolProvider;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.JavaSourceLocator;
import com.tomaszrup.lsp.utils.Positions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroovyServices implements TextDocumentService, WorkspaceService, LanguageClientAware {
	private static final Logger logger = LoggerFactory.getLogger(GroovyServices.class);
	private static final Pattern PATTERN_CONSTRUCTOR_CALL = Pattern.compile(".*new \\w*$");

	/**
	 * Holds all per-project state: compilation unit, AST visitor, classpath, etc.
	 * Each Gradle project in the workspace gets its own scope so that classpaths
	 * don't leak between independent projects.
	 */
	static class ProjectScope {
		Path projectRoot;
		final ICompilationUnitFactory compilationUnitFactory;
		GroovyLSCompilationUnit compilationUnit;
		ASTNodeVisitor astVisitor;
		Map<URI, List<Diagnostic>> prevDiagnosticsByFile;
		ScanResult classGraphScanResult;
		GroovyClassLoader classLoader;
		URI previousContext;
		JavaSourceLocator javaSourceLocator;

		ProjectScope(Path projectRoot, ICompilationUnitFactory factory) {
			this.projectRoot = projectRoot;
			this.compilationUnitFactory = factory;
			this.javaSourceLocator = new JavaSourceLocator();
			if (projectRoot != null) {
				this.javaSourceLocator.addProjectRoot(projectRoot);
			}
		}
	}

	private LanguageClient languageClient;
	private Path workspaceRoot;
	private FileContentsTracker fileContentsTracker = new FileContentsTracker();
	private boolean semanticHighlightingEnabled = true;
	private boolean formattingEnabled = true;

	// Default scope (used when no Gradle projects are registered, e.g. in tests)
	private ProjectScope defaultScope;
	// Per-Gradle-project scopes, sorted by path length desc for longest-prefix match
	private List<ProjectScope> projectScopes = new ArrayList<>();

	public GroovyServices(ICompilationUnitFactory factory) {
		defaultScope = new ProjectScope(null, factory);
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		this.workspaceRoot = workspaceRoot;
		defaultScope.projectRoot = workspaceRoot;
		defaultScope.javaSourceLocator = new JavaSourceLocator();
		if (workspaceRoot != null) {
			defaultScope.javaSourceLocator.addProjectRoot(workspaceRoot);
		}
		createOrUpdateCompilationUnit(defaultScope);
		fileContentsTracker.resetChangedFiles();
	}

	/**
	 * Register all Gradle projects at once with their resolved classpaths.
	 * This allows computing proper subproject exclusions so that parent projects
	 * don't scan source files belonging to nested subprojects.
	 */
	public void addProjects(Map<Path, List<String>> projectClasspaths) {
		logger.info("addProjects called with {} projects", projectClasspaths.size());
		List<Path> projectRoots = new ArrayList<>(projectClasspaths.keySet());
		for (Path p : projectRoots) {
			logger.info("  project root: {}, classpath entries: {}", p, projectClasspaths.get(p).size());
		}

		for (Path projectRoot : projectRoots) {
			CompilationUnitFactory factory = new CompilationUnitFactory();
			factory.setAdditionalClasspathList(projectClasspaths.get(projectRoot));

			// Compute nested project roots that this project should NOT scan
			List<Path> excludedRoots = new ArrayList<>();
			for (Path other : projectRoots) {
				if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
					excludedRoots.add(other);
				}
			}
			logger.info("  Project {}: excluding {} subproject root(s): {}", projectRoot, excludedRoots.size(), excludedRoots);
			factory.setExcludedSubRoots(excludedRoots);

			ProjectScope scope = new ProjectScope(projectRoot, factory);
			projectScopes.add(scope);
		}

		// Sort scopes by path length descending for longest-prefix-first matching
		projectScopes.sort((a, b) -> b.projectRoot.toString().length() - a.projectRoot.toString().length());

		// Create compilation units, compile, and visit AST for each scope
		for (ProjectScope scope : projectScopes) {
			createOrUpdateCompilationUnit(scope);
			fileContentsTracker.resetChangedFiles();
			compile(scope);
			visitAST(scope);
		}
	}

	/**
	 * Find the project scope that owns the given URI.
	 * Returns the default scope when no project scopes are registered (backward
	 * compat for tests). Returns null if projects are registered but the file
	 * doesn't belong to any of them.
	 */
	private ProjectScope findProjectScope(URI uri) {
		if (!projectScopes.isEmpty() && uri != null) {
			Path filePath = Paths.get(uri);
			for (ProjectScope scope : projectScopes) {
				if (scope.projectRoot != null && filePath.startsWith(scope.projectRoot)) {
					logger.debug("findProjectScope({}) -> {}", uri, scope.projectRoot);
					return scope;
				}
			}
			// File not in any registered project
			logger.warn("findProjectScope({}) -> no matching project scope found", uri);
			return null;
		}
		// No project scopes registered - use default (backward compat)
		return defaultScope;
	}

	/**
	 * Returns all active scopes: project scopes if any are registered,
	 * otherwise just the default scope.
	 */
	private List<ProjectScope> getAllScopes() {
		if (!projectScopes.isEmpty()) {
			return projectScopes;
		}
		return Collections.singletonList(defaultScope);
	}

	@Override
	public void connect(LanguageClient client) {
		languageClient = client;
	}

	// --- NOTIFICATIONS

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		fileContentsTracker.didOpen(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope != null) {
			compileAndVisitAST(scope, uri);
		}
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		fileContentsTracker.didChange(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope != null) {
			compileAndVisitAST(scope, uri);
		}
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		fileContentsTracker.didClose(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope != null) {
			compileAndVisitAST(scope, uri);
		}
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		// nothing to handle on save at this time
	}

	/**
	 * Callback interface for notifying about Java/Gradle file changes
	 * that require recompilation of a Gradle project.
	 */
	public interface JavaChangeListener {
		void onJavaFilesChanged(Path projectRoot);
	}

	private JavaChangeListener javaChangeListener;

	public void setJavaChangeListener(JavaChangeListener listener) {
		this.javaChangeListener = listener;
	}

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		Set<URI> allChangedUris = params.getChanges().stream()
				.map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());

		// Detect Java/Gradle file changes that require Gradle recompilation
		Set<Path> projectsNeedingRecompile = new LinkedHashSet<>();
		for (URI changedUri : allChangedUris) {
			String path = changedUri.getPath();
			if (path != null && (path.endsWith(".java") || path.endsWith("build.gradle") || path.endsWith("build.gradle.kts"))) {
				Path filePath = Paths.get(changedUri);
				for (ProjectScope scope : projectScopes) {
					if (scope.projectRoot != null && filePath.startsWith(scope.projectRoot)) {
						projectsNeedingRecompile.add(scope.projectRoot);
						break;
					}
				}
			}
		}

		// Trigger Gradle recompile for projects with Java/Gradle changes
		if (!projectsNeedingRecompile.isEmpty() && javaChangeListener != null) {
			for (Path projectRoot : projectsNeedingRecompile) {
				logger.info("Java/Gradle files changed in {}, triggering recompile", projectRoot);
				javaChangeListener.onJavaFilesChanged(projectRoot);
			}
		}

		// Refresh Java source index for projects with Java file changes
		for (URI changedUri : allChangedUris) {
			String path = changedUri.getPath();
			if (path != null && path.endsWith(".java")) {
				for (ProjectScope scope : getAllScopes()) {
					if (scope.projectRoot != null && scope.javaSourceLocator != null) {
						Path filePath = Paths.get(changedUri);
						if (filePath.startsWith(scope.projectRoot)) {
							scope.javaSourceLocator.refresh();
							break;
						}
					}
				}
			}
		}

		for (ProjectScope scope : getAllScopes()) {
			Set<URI> scopeUris;
			if (projectScopes.isEmpty()) {
				// No project scopes - process all URIs (backward compat)
				scopeUris = allChangedUris;
			} else {
				// Only process URIs that belong to this project
				scopeUris = allChangedUris.stream()
						.filter(uri -> scope.projectRoot != null
								&& Paths.get(uri).startsWith(scope.projectRoot))
						.collect(Collectors.toSet());
			}
			if (!scopeUris.isEmpty()) {
				// After a Java recompile, invalidate + rebuild the compilation unit
				if (projectsNeedingRecompile.contains(scope.projectRoot)) {
					scope.compilationUnitFactory.invalidateCompilationUnit();
				}
				boolean isSameUnit = createOrUpdateCompilationUnit(scope);
				compile(scope);
				if (isSameUnit) {
					visitAST(scope, scopeUris);
				} else {
					visitAST(scope);
				}
			}
		}
		fileContentsTracker.resetChangedFiles();
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		if (!(params.getSettings() instanceof JsonObject)) {
			return;
		}
		JsonObject settings = (JsonObject) params.getSettings();
		this.updateClasspath(settings);
		this.updateFeatureToggles(settings);
	}

	private void updateFeatureToggles(JsonObject settings) {
		if (!settings.has("groovy") || !settings.get("groovy").isJsonObject()) {
			return;
		}
		JsonObject groovy = settings.get("groovy").getAsJsonObject();
		if (groovy.has("semanticHighlighting") && groovy.get("semanticHighlighting").isJsonObject()) {
			JsonObject sh = groovy.get("semanticHighlighting").getAsJsonObject();
			if (sh.has("enabled")) {
				this.semanticHighlightingEnabled = sh.get("enabled").getAsBoolean();
			}
		}
		if (groovy.has("formatting") && groovy.get("formatting").isJsonObject()) {
			JsonObject fmt = groovy.get("formatting").getAsJsonObject();
			if (fmt.has("enabled")) {
				this.formattingEnabled = fmt.get("enabled").getAsBoolean();
			}
		}
	}

	void updateClasspath(List<String> classpathList) {
		// When project scopes are registered (via Gradle import), they handle
		// their own classpaths. The default scope should not override them.
		if (!projectScopes.isEmpty()) {
			logger.info("updateClasspath() ignored — {} project scope(s) are active", projectScopes.size());
			return;
		}
		if (!classpathList.equals(defaultScope.compilationUnitFactory.getAdditionalClasspathList())) {
			defaultScope.compilationUnitFactory.setAdditionalClasspathList(classpathList);
			createOrUpdateCompilationUnit(defaultScope);
			fileContentsTracker.resetChangedFiles();
			compile(defaultScope);
			visitAST(defaultScope);
			defaultScope.previousContext = null;
		}
	}

	private void updateClasspath(JsonObject settings) {
		List<String> classpathList = new ArrayList<>();

		if (settings.has("groovy") && settings.get("groovy").isJsonObject()) {
			JsonObject groovy = settings.get("groovy").getAsJsonObject();
			if (groovy.has("classpath") && groovy.get("classpath").isJsonArray()) {
				JsonArray classpath = groovy.get("classpath").getAsJsonArray();
				classpath.forEach(element -> {
					classpathList.add(element.getAsString());
				});
			}
		}

		updateClasspath(classpathList);
	}

	// --- REQUESTS

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(null);
		}
		recompileIfContextChanged(scope, uri);

		HoverProvider provider = new HoverProvider(scope.astVisitor);
		return provider.provideHover(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
		}
		recompileIfContextChanged(scope, uri);

		String originalSource = null;
		ASTNode offsetNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
				position.getCharacter());
		if (offsetNode == null) {
			originalSource = fileContentsTracker.getContents(uri);
			VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
					textDocument.getUri(), 1);
			int offset = Positions.getOffset(originalSource, position);
			String lineBeforeOffset = originalSource.substring(offset - position.getCharacter(), offset);
			Matcher matcher = PATTERN_CONSTRUCTOR_CALL.matcher(lineBeforeOffset);
			TextDocumentContentChangeEvent changeEvent = null;
			if (matcher.matches()) {
				changeEvent = new TextDocumentContentChangeEvent(new Range(position, position), 0, "a()");
			} else {
				changeEvent = new TextDocumentContentChangeEvent(new Range(position, position), 0, "a");
			}
			DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
					Collections.singletonList(changeEvent));
			// if the offset node is null, there is probably a syntax error.
			// a completion request is usually triggered by the . character, and
			// if there is no property name after the dot, it will cause a syntax
			// error.
			// this hack adds a placeholder property name in the hopes that it
			// will correctly create a PropertyExpression to use for completion.
			// we'll restore the original text after we're done handling the
			// completion request.
			didChange(didChangeParams);
		}

		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result = null;
		try {
			CompletionProvider provider = new CompletionProvider(scope.astVisitor, scope.classGraphScanResult);
			result = provider.provideCompletion(params.getTextDocument(), params.getPosition(), params.getContext());

			// Add Spock-specific completions
			SpockCompletionProvider spockProvider = new SpockCompletionProvider(scope.astVisitor);
			ASTNode currentNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
					position.getCharacter());
			if (currentNode != null) {
				List<CompletionItem> spockItems = spockProvider.provideSpockCompletions(uri, position, currentNode);
				if (!spockItems.isEmpty()) {
					result = result.thenApply(either -> {
						if (either.isLeft()) {
							List<CompletionItem> combined = new ArrayList<>(either.getLeft());
							combined.addAll(spockItems);
							return Either.forLeft(combined);
						} else {
							CompletionList list = either.getRight();
							list.getItems().addAll(spockItems);
							return Either.forRight(list);
						}
					});
				}
			}
		} finally {
			if (originalSource != null) {
				VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
						textDocument.getUri(), 1);
				TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(null, 0,
						originalSource);
				DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
						Collections.singletonList(changeEvent));
				didChange(didChangeParams);
			}
		}

		return result;
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}
		recompileIfContextChanged(scope, uri);

		DefinitionProvider provider = new DefinitionProvider(scope.astVisitor, scope.javaSourceLocator);
		return provider.provideDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(new SignatureHelp());
		}
		recompileIfContextChanged(scope, uri);

		String originalSource = null;
		ASTNode offsetNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
				position.getCharacter());
		if (offsetNode == null) {
			originalSource = fileContentsTracker.getContents(uri);
			VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
					textDocument.getUri(), 1);
			TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(
					new Range(position, position), 0, ")");
			DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
					Collections.singletonList(changeEvent));
			// if the offset node is null, there is probably a syntax error.
			// a signature help request is usually triggered by the ( character,
			// and if there is no matching ), it will cause a syntax error.
			// this hack adds a placeholder ) character in the hopes that it
			// will correctly create a ArgumentListExpression to use for
			// signature help.
			// we'll restore the original text after we're done handling the
			// signature help request.
			didChange(didChangeParams);
		}

		try {
			SignatureHelpProvider provider = new SignatureHelpProvider(scope.astVisitor);
			return provider.provideSignatureHelp(params.getTextDocument(), params.getPosition());
		} finally {
			if (originalSource != null) {
				VersionedTextDocumentIdentifier versionedTextDocument = new VersionedTextDocumentIdentifier(
						textDocument.getUri(), 1);
				TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent(null, 0,
						originalSource);
				DidChangeTextDocumentParams didChangeParams = new DidChangeTextDocumentParams(versionedTextDocument,
						Collections.singletonList(changeEvent));
				didChange(didChangeParams);
			}
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}
		recompileIfContextChanged(scope, uri);

		TypeDefinitionProvider provider = new TypeDefinitionProvider(scope.astVisitor, scope.javaSourceLocator);
		return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
			ImplementationParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}
		recompileIfContextChanged(scope, uri);

		ImplementationProvider provider = new ImplementationProvider(scope.astVisitor);
		return provider.provideImplementation(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		recompileIfContextChanged(scope, uri);

		DocumentHighlightProvider provider = new DocumentHighlightProvider(scope.astVisitor);
		return provider.provideDocumentHighlights(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		recompileIfContextChanged(scope, uri);

		ReferenceProvider provider = new ReferenceProvider(scope.astVisitor);
		return provider.provideReferences(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
			DocumentSymbolParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		recompileIfContextChanged(scope, uri);

		DocumentSymbolProvider provider = new DocumentSymbolProvider(scope.astVisitor);
		return provider.provideDocumentSymbols(params.getTextDocument());
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			WorkspaceSymbolParams params) {
		// Workspace symbols aggregate across all project scopes
		List<SymbolInformation> allSymbols = new ArrayList<>();
		for (ProjectScope scope : getAllScopes()) {
			if (scope.astVisitor != null) {
				WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(scope.astVisitor);
				List<? extends SymbolInformation> symbols = provider.provideWorkspaceSymbols(params.getQuery()).join();
				allSymbols.addAll(symbols);
			}
		}
		return CompletableFuture.completedFuture(Either.forLeft(allSymbols));
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(new WorkspaceEdit());
		}
		recompileIfContextChanged(scope, uri);

		RenameProvider provider = new RenameProvider(scope.astVisitor, fileContentsTracker);
		return provider.provideRename(params);
	}

	@Override
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		recompileIfContextChanged(scope, uri);

		CodeActionProvider provider = new CodeActionProvider(scope.astVisitor, scope.classGraphScanResult,
				fileContentsTracker);
		CompletableFuture<List<Either<Command, CodeAction>>> result = provider.provideCodeActions(params);

		// Add Spock-specific code actions
		SpockCodeActionProvider spockProvider = new SpockCodeActionProvider(scope.astVisitor);
		List<Either<Command, CodeAction>> spockActions = spockProvider.provideCodeActions(params);
		if (!spockActions.isEmpty()) {
			result = result.thenApply(actions -> {
				List<Either<Command, CodeAction>> combined = new ArrayList<>(actions);
				combined.addAll(spockActions);
				return combined;
			});
		}

		return result;
	}

	@Override
	public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		recompileIfContextChanged(scope, uri);

		InlayHintProvider provider = new InlayHintProvider(scope.astVisitor);
		return provider.provideInlayHints(params);
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		if (!semanticHighlightingEnabled) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope == null || scope.astVisitor == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		recompileIfContextChanged(scope, uri);

		SemanticTokensProvider provider = new SemanticTokensProvider(scope.astVisitor);
		return provider.provideSemanticTokensFull(params.getTextDocument());
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		if (!formattingEnabled) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		String sourceText = fileContentsTracker.getContents(uri);
		FormattingProvider provider = new FormattingProvider();
		return provider.provideFormatting(params, sourceText);
	}

	// --- INTERNAL

	private void visitAST(ProjectScope scope) {
		if (scope.compilationUnit == null) {
			return;
		}
		scope.astVisitor = new ASTNodeVisitor();
		scope.astVisitor.visitCompilationUnit(scope.compilationUnit);
	}

	private void visitAST(ProjectScope scope, Set<URI> uris) {
		if (scope.astVisitor == null) {
			visitAST(scope);
			return;
		}
		if (scope.compilationUnit == null) {
			return;
		}
		scope.astVisitor.visitCompilationUnit(scope.compilationUnit, uris);
	}

	private boolean createOrUpdateCompilationUnit(ProjectScope scope) {
		if (scope.compilationUnit != null) {
			File targetDirectory = scope.compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && targetDirectory.exists()) {
				try {
					Files.walk(targetDirectory.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile)
							.forEach(File::delete);
				} catch (IOException e) {
					System.err.println("Failed to delete target directory: " + targetDirectory.getAbsolutePath());
					scope.compilationUnit = null;
					return false;
				}
			}
		}

		GroovyLSCompilationUnit oldCompilationUnit = scope.compilationUnit;
		scope.compilationUnit = scope.compilationUnitFactory.create(scope.projectRoot, fileContentsTracker);

		if (scope.compilationUnit != null) {
			File targetDirectory = scope.compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
				System.err.println("Failed to create target directory: " + targetDirectory.getAbsolutePath());
			}
			GroovyClassLoader newClassLoader = scope.compilationUnit.getClassLoader();
			if (!newClassLoader.equals(scope.classLoader)) {
				scope.classLoader = newClassLoader;

				try {
					scope.classGraphScanResult = new ClassGraph().overrideClassLoaders(scope.classLoader)
							.enableClassInfo()
							.enableSystemJarsAndModules()
							.scan();
				} catch (ClassGraphException e) {
					scope.classGraphScanResult = null;
				}
			}
		} else {
			scope.classGraphScanResult = null;
		}

		return scope.compilationUnit != null && scope.compilationUnit.equals(oldCompilationUnit);
	}

	protected void recompileIfContextChanged(ProjectScope scope, URI newContext) {
		if (scope.previousContext == null || scope.previousContext.equals(newContext)) {
			return;
		}
		fileContentsTracker.forceChanged(newContext);
		compileAndVisitAST(scope, newContext);
	}

	private void compileAndVisitAST(ProjectScope scope, URI contextURI) {
		Set<URI> uris = Collections.singleton(contextURI);
		boolean isSameUnit = createOrUpdateCompilationUnit(scope);
		fileContentsTracker.resetChangedFiles();
		compile(scope);
		if (isSameUnit) {
			visitAST(scope, uris);
		} else {
			visitAST(scope);
		}
		scope.previousContext = contextURI;
	}

	private void compile(ProjectScope scope) {
		if (scope.compilationUnit == null) {
			logger.warn("compile() called but compilationUnit is null for scope {}", scope.projectRoot);
			return;
		}
		logger.info("Compiling scope: {}, classpath entries: {}", scope.projectRoot,
				scope.compilationUnit.getConfiguration().getClasspath().size());
		try {
			// AST is completely built after the canonicalization phase
			// for code intelligence, we shouldn't need to go further
			// http://groovy-lang.org/metaprogramming.html#_compilation_phases_guide
			scope.compilationUnit.compile(Phases.CANONICALIZATION);
		} catch (CompilationFailedException e) {
			logger.info("Compilation failed (expected for incomplete code) for scope {}: {}", scope.projectRoot, e.getMessage());
		} catch (GroovyBugError e) {
			logger.warn("Groovy compiler bug during compilation for scope {} (this is usually harmless for code intelligence): {}",
					scope.projectRoot, e.getMessage());
			logger.debug("GroovyBugError details", e);
		} catch (Exception e) {
			logger.warn("Unexpected exception during compilation for scope {}: {}",
					scope.projectRoot, e.getMessage());
			logger.debug("Compilation exception details", e);
		}
		Set<PublishDiagnosticsParams> diagnostics = handleErrorCollector(scope,
				scope.compilationUnit.getErrorCollector());
		diagnostics.stream().forEach(languageClient::publishDiagnostics);
	}

	private Set<PublishDiagnosticsParams> handleErrorCollector(ProjectScope scope, ErrorCollector collector) {
		Map<URI, List<Diagnostic>> diagnosticsByFile = new HashMap<>();

		List<? extends Message> errors = collector.getErrors();
		if (errors != null && !errors.isEmpty()) {
			logger.info("Scope {} has {} compilation errors", scope.projectRoot, errors.size());
		}
		if (errors != null) {
			errors.stream().filter((Object message) -> message instanceof SyntaxErrorMessage)
					.forEach((Object message) -> {
						SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
						SyntaxException cause = syntaxErrorMessage.getCause();
						Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(cause);
						if (range == null) {
							// range can't be null in a Diagnostic, so we need
							// a fallback
							range = new Range(new Position(0, 0), new Position(0, 0));
						}
						Diagnostic diagnostic = new Diagnostic();
						diagnostic.setRange(range);
						diagnostic.setSeverity(cause.isFatal() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning);
						diagnostic.setMessage(cause.getMessage());
						URI uri = Paths.get(cause.getSourceLocator()).toUri();
						logger.info("  Diagnostic [{}] in {}: {}", scope.projectRoot, uri, cause.getMessage());
						diagnosticsByFile.computeIfAbsent(uri, (key) -> new ArrayList<>()).add(diagnostic);
					});
		}

		Set<PublishDiagnosticsParams> result = diagnosticsByFile.entrySet().stream()
				.map(entry -> new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()))
				.collect(Collectors.toSet());

		if (scope.prevDiagnosticsByFile != null) {
			for (URI key : scope.prevDiagnosticsByFile.keySet()) {
				if (!diagnosticsByFile.containsKey(key)) {
					// send an empty list of diagnostics for files that had
					// diagnostics previously or they won't be cleared
					result.add(new PublishDiagnosticsParams(key.toString(), new ArrayList<>()));
				}
			}
		}
		scope.prevDiagnosticsByFile = diagnosticsByFile;
		return result;
	}
}
