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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

import org.codehaus.groovy.ast.ASTNode;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import io.github.classgraph.ScanResult;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.providers.CodeActionProvider;
import com.tomaszrup.groovyls.providers.CompletionProvider;
import com.tomaszrup.groovyls.providers.DefinitionProvider;
import com.tomaszrup.groovyls.providers.DocumentHighlightProvider;
import com.tomaszrup.groovyls.providers.DocumentSymbolProvider;
import com.tomaszrup.groovyls.providers.FormattingProvider;
import com.tomaszrup.groovyls.providers.HoverProvider;
import com.tomaszrup.groovyls.providers.ImplementationProvider;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin facade implementing the LSP {@link TextDocumentService},
 * {@link WorkspaceService}, and {@link LanguageClientAware} interfaces.
 * Delegates heavy lifting to:
 * <ul>
 *   <li>{@link ProjectScopeManager} — project scope lifecycle and lookup</li>
 *   <li>{@link CompilationService} — compilation orchestration and AST management</li>
 *   <li>{@link FileChangeHandler} — watched-file change processing</li>
 *   <li>{@link DocumentResolverService} — completion item documentation resolution</li>
 * </ul>
 */
public class GroovyServices implements TextDocumentService, WorkspaceService, LanguageClientAware {
	private static final Logger logger = LoggerFactory.getLogger(GroovyServices.class);

	/** Debounce delay for didChange recompilation (milliseconds). */
	private static final long DEBOUNCE_DELAY_MS = 300;

	// --- Callback interfaces (kept here for backward compatibility) ---

	/**
	 * Callback interface for notifying about Java or build-tool file changes
	 * that require recompilation of a project.
	 */
	public interface JavaChangeListener {
		void onJavaFilesChanged(Path projectRoot);
	}

	/**
	 * Callback interface for forwarding raw configuration settings to the
	 * server layer (e.g. so {@code GroovyLanguageServer} can push
	 * importer-specific settings like {@code groovy.maven.home}).
	 */
	public interface SettingsChangeListener {
		void onSettingsChanged(JsonObject settings);
	}

	// --- Delegate services ---

	private final ProjectScopeManager scopeManager;
	private final CompilationService compilationService;
	private final FileChangeHandler fileChangeHandler;
	private final DocumentResolverService documentResolverService;
	private final FileContentsTracker fileContentsTracker = new FileContentsTracker();

	private volatile LanguageClient languageClient;
	private SettingsChangeListener settingsChangeListener;

	// Debounce scheduler for didChange recompilation
	private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "groovyls-debounce");
		t.setDaemon(true);
		return t;
	});
	private volatile ScheduledFuture<?> pendingDebounce;

	/**
	 * Executor for debounced Java/build-file recompile scheduling.
	 */
	private final ScheduledExecutorService javaRecompileExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "groovyls-java-recompile");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Single-threaded executor for background compilations triggered by
	 * {@code didOpen} and {@code didClose}.
	 */
	private final ExecutorService backgroundCompiler = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "groovyls-bg-compile");
		t.setDaemon(true);
		return t;
	});

	public GroovyServices(ICompilationUnitFactory factory) {
		this.scopeManager = new ProjectScopeManager(factory, fileContentsTracker);
		this.compilationService = new CompilationService(fileContentsTracker);
		this.fileChangeHandler = new FileChangeHandler(scopeManager, compilationService, javaRecompileExecutor);
		this.documentResolverService = new DocumentResolverService(scopeManager);
	}

	// --- Lifecycle / wiring ---

	@Override
	public void connect(LanguageClient client) {
		this.languageClient = client;
		scopeManager.setLanguageClient(client);
		compilationService.setLanguageClient(client);
	}

	public void shutdown() {
		debounceExecutor.shutdownNow();
		javaRecompileExecutor.shutdownNow();
		backgroundCompiler.shutdownNow();
		try {
			debounceExecutor.awaitTermination(5, TimeUnit.SECONDS);
			javaRecompileExecutor.awaitTermination(5, TimeUnit.SECONDS);
			backgroundCompiler.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		scopeManager.setWorkspaceRoot(workspaceRoot);
	}

	public Path getWorkspaceRoot() {
		return scopeManager.getWorkspaceRoot();
	}

	public void setImportInProgress(boolean inProgress) {
		scopeManager.setImportInProgress(inProgress);
	}

	public void onImportComplete() {
		scopeManager.setImportInProgress(false);
		List<ProjectScope> scopes = scopeManager.getProjectScopes();
		if (scopes.isEmpty()) {
			// No build-tool projects found — compile the default scope
			ProjectScope ds = scopeManager.getDefaultScope();
			ds.lock.writeLock().lock();
			try {
				Set<URI> openURIs = fileContentsTracker.getOpenURIs();
				for (URI uri : openURIs) {
					compilationService.compileAndVisitAST(ds, uri);
				}
			} finally {
				ds.lock.writeLock().unlock();
			}
			if (languageClient != null) {
				languageClient.refreshSemanticTokens();
			}
			return;
		}

		// Build-tool projects exist — schedule background compilation for
		// scopes that have open files.  This provides diagnostics for files
		// the user is looking at without blocking the import thread or
		// compiling ALL scopes eagerly (which caused log spam in large
		// workspaces).
		Set<URI> openURIs = fileContentsTracker.getOpenURIs();
		if (!openURIs.isEmpty()) {
			// Deduplicate: only compile each scope once, even if multiple
			// files are open in the same project.
			Map<ProjectScope, URI> scopeToURI = new java.util.LinkedHashMap<>();
			for (URI uri : openURIs) {
				ProjectScope scope = scopeManager.findProjectScope(uri);
				if (scope != null && scope.classpathResolved) {
					scopeToURI.putIfAbsent(scope, uri);
				}
			}

			if (!scopeToURI.isEmpty()) {
				logger.info("Scheduling background compilation for {} scope(s) with open files",
						scopeToURI.size());
				backgroundCompiler.submit(() -> {
					for (Map.Entry<ProjectScope, URI> entry : scopeToURI.entrySet()) {
						ProjectScope scope = entry.getKey();
						scope.lock.writeLock().lock();
						try {
							compilationService.ensureScopeCompiled(scope);
						} finally {
							scope.lock.writeLock().unlock();
						}
					}
					if (languageClient != null) {
						languageClient.refreshSemanticTokens();
					}
				});
			}
		}
	}

	public void setJavaChangeListener(JavaChangeListener listener) {
		fileChangeHandler.setJavaChangeListener(listener::onJavaFilesChanged);
	}

	public void setSettingsChangeListener(SettingsChangeListener listener) {
		this.settingsChangeListener = listener;
	}

	// --- Project registration (delegated to scopeManager + compilationService) ---

	public void registerDiscoveredProjects(List<Path> projectRoots) {
		scopeManager.registerDiscoveredProjects(projectRoots);
	}

	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths) {
		scopeManager.updateProjectClasspaths(projectClasspaths);
		// Compilation is NOT done here — scopes compile lazily on first
		// interaction (didOpen, didChange, hover, etc.) or via background
		// compilation submitted by onImportComplete().  This avoids the
		// expensive O(open-tabs × projects) synchronous compilation that
		// caused log spam and long import times in large workspaces.
	}

	public void addProjects(Map<Path, List<String>> projectClasspaths) {
		Set<URI> openURIs = scopeManager.addProjects(projectClasspaths);

		// Deduplicate by scope to avoid compiling the same project multiple times
		Map<ProjectScope, URI> scopeToRepresentativeURI = new java.util.LinkedHashMap<>();
		for (URI uri : openURIs) {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			if (scope != null) {
				scopeToRepresentativeURI.putIfAbsent(scope, uri);
			}
		}

		for (Map.Entry<ProjectScope, URI> entry : scopeToRepresentativeURI.entrySet()) {
			ProjectScope scope = entry.getKey();
			URI uri = entry.getValue();
			scope.lock.writeLock().lock();
			try {
				boolean didFullCompile = compilationService.ensureScopeCompiled(scope);
				if (!didFullCompile) {
					compilationService.compileAndVisitAST(scope, uri);
				}
			} finally {
				scope.lock.writeLock().unlock();
			}
		}

		if (languageClient != null) {
			languageClient.refreshSemanticTokens();
		}
	}

	// --- Utility (kept for backward compat with tests) ---

	static boolean isBuildOutputFile(Path filePath, Path projectRoot) {
		return FileChangeHandler.isBuildOutputFile(filePath, projectRoot);
	}

	// --- TextDocumentService notifications ---

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		fileContentsTracker.didOpen(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = scopeManager.findProjectScope(uri);
		if (scope != null && !scopeManager.isImportPendingFor(scope)) {
			scope.lock.writeLock().lock();
			try {
				// Ensure the scope is compiled at least once (lazy first compile).
				// Do NOT trigger compileAndVisitAST here — opening a file is not
				// a content change. The AST from initial compilation (disk-based)
				// is correct. Actual edits trigger recompilation via didChange().
				// Code-intelligence requests (hover, completion) also lazily
				// recompile if changes are pending via ensureCompiledForContext().
				compilationService.ensureScopeCompiled(scope);
			} finally {
				scope.lock.writeLock().unlock();
			}
		} else if (scopeManager.isImportPendingFor(scope != null ? scope : scopeManager.getDefaultScope())) {
			backgroundCompiler.submit(() -> compilationService.syntaxCheckSingleFile(uri));
		}
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		fileContentsTracker.didChange(params);

		ScheduledFuture<?> prev = pendingDebounce;
		if (prev != null) {
			prev.cancel(false);
		}
		pendingDebounce = debounceExecutor.schedule(() -> {
			URI uri = URI.create(params.getTextDocument().getUri());
			ProjectScope scope = scopeManager.findProjectScope(uri);
			if (scope != null && !scopeManager.isImportPendingFor(scope)) {
				scope.lock.writeLock().lock();
				try {
					boolean didFullCompile = compilationService.ensureScopeCompiled(scope);
					if (!didFullCompile) {
						compilationService.compileAndVisitAST(scope, uri);
					}
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		fileContentsTracker.didClose(params);
		// Closing a file does not change its content — no recompilation needed.
		// The AST remains valid. The next interaction (hover, edit, etc.) will
		// lazily recompile if actual changes are pending.
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		// nothing to handle on save at this time
	}

	// --- WorkspaceService notifications ---

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		fileChangeHandler.handleDidChangeWatchedFiles(params);
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		if (!(params.getSettings() instanceof JsonObject)) {
			return;
		}
		JsonObject settings = (JsonObject) params.getSettings();
		updateClasspath(settings);
		scopeManager.updateFeatureToggles(settings);
		if (settingsChangeListener != null) {
			settingsChangeListener.onSettingsChanged(settings);
		}
	}

	// --- Configuration / classpath ---

	void updateClasspath(List<String> classpathList) {
		if (!scopeManager.getProjectScopes().isEmpty()) {
			logger.debug("updateClasspath() ignored — {} project scope(s) are active",
					scopeManager.getProjectScopes().size());
			return;
		}
		if (scopeManager.isImportInProgress()) {
			logger.info("updateClasspath() deferred — project import in progress");
			scopeManager.getDefaultScope().compilationUnitFactory.setAdditionalClasspathList(classpathList);
			return;
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		ds.lock.writeLock().lock();
		try {
			if (!classpathList.equals(ds.compilationUnitFactory.getAdditionalClasspathList())) {
				ds.compilationUnitFactory.setAdditionalClasspathList(classpathList);
				compilationService.recompileForClasspathChange(ds);
			}
		} finally {
			ds.lock.writeLock().unlock();
		}
	}

	private void updateClasspath(JsonObject settings) {
		List<String> classpathList = new ArrayList<>();

		if (settings.has("groovy") && settings.get("groovy").isJsonObject()) {
			JsonObject groovy = settings.get("groovy").getAsJsonObject();
			if (groovy.has("classpath") && groovy.get("classpath").isJsonArray()) {
				com.google.gson.JsonArray classpath = groovy.get("classpath").getAsJsonArray();
				classpath.forEach(element -> {
					classpathList.add(element.getAsString());
				});
			}
		}

		updateClasspath(classpathList);
	}

	// --- TextDocumentService requests ---

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(null);
		}

		HoverProvider provider = new HoverProvider(visitor);
		return provider.provideHover(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		ProjectScope scope = scopeManager.findProjectScope(uri);
		if (scope == null) {
			return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
		}

		scope.lock.writeLock().lock();
		try {
			compilationService.ensureScopeCompiled(scope);
			if (scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
			}
			if (!fileContentsTracker.hasChangedURIsUnder(scope.projectRoot)) {
				compilationService.recompileIfContextChanged(scope, uri);
			} else {
				compilationService.compileAndVisitAST(scope, uri);
			}

			String originalSource = null;
			ASTNode offsetNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
					position.getCharacter());
			if (offsetNode == null) {
				originalSource = compilationService.injectCompletionPlaceholder(scope, uri, position);
			}

			CompletableFuture<Either<List<CompletionItem>, CompletionList>> result;
			try {
				CompletionProvider provider = new CompletionProvider(scope.astVisitor, scope.classGraphScanResult);
				result = provider.provideCompletion(params.getTextDocument(), params.getPosition(),
						params.getContext());

				SpockCompletionProvider spockProvider = new SpockCompletionProvider(scope.astVisitor);
				ASTNode currentNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
						position.getCharacter());
				if (currentNode != null) {
					List<CompletionItem> spockItems = spockProvider.provideSpockCompletions(uri, position,
							currentNode);
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
					compilationService.restoreDocumentSource(scope, uri, originalSource);
				}
			}

			return result;
		} finally {
			scope.lock.writeLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		DefinitionProvider provider = new DefinitionProvider(visitor, scope.javaSourceLocator);
		return provider.provideDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		ProjectScope scope = scopeManager.findProjectScope(uri);
		if (scope == null) {
			return CompletableFuture.completedFuture(new SignatureHelp());
		}

		scope.lock.writeLock().lock();
		try {
			compilationService.ensureScopeCompiled(scope);
			if (scope.astVisitor == null) {
				return CompletableFuture.completedFuture(new SignatureHelp());
			}
			if (!fileContentsTracker.hasChangedURIsUnder(scope.projectRoot)) {
				compilationService.recompileIfContextChanged(scope, uri);
			} else {
				compilationService.compileAndVisitAST(scope, uri);
			}

			String originalSource = null;
			ASTNode offsetNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
					position.getCharacter());
			if (offsetNode == null) {
				originalSource = compilationService.injectSignatureHelpPlaceholder(scope, uri, position);
			}

			try {
				SignatureHelpProvider provider = new SignatureHelpProvider(scope.astVisitor);
				return provider.provideSignatureHelp(params.getTextDocument(), params.getPosition());
			} finally {
				if (originalSource != null) {
					compilationService.restoreDocumentSource(scope, uri, originalSource);
				}
			}
		} finally {
			scope.lock.writeLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		TypeDefinitionProvider provider = new TypeDefinitionProvider(visitor, scope.javaSourceLocator);
		return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
			ImplementationParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		ImplementationProvider provider = new ImplementationProvider(visitor);
		return provider.provideImplementation(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		DocumentHighlightProvider provider = new DocumentHighlightProvider(visitor);
		return provider.provideDocumentHighlights(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		ReferenceProvider provider = new ReferenceProvider(visitor);
		return provider.provideReferences(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
			DocumentSymbolParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		DocumentSymbolProvider provider = new DocumentSymbolProvider(visitor);
		return provider.provideDocumentSymbols(params.getTextDocument());
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			WorkspaceSymbolParams params) {
		// Ensure all scopes are compiled and up-to-date before querying —
		// workspace symbol is a cross-scope request with no single context URI.
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			if (!scope.compiled || fileContentsTracker.hasChangedURIsUnder(scope.projectRoot)) {
				scope.lock.writeLock().lock();
				try {
					boolean didFull = compilationService.ensureScopeCompiled(scope);
					if (!didFull && fileContentsTracker.hasChangedURIsUnder(scope.projectRoot)) {
						Set<URI> pending = fileContentsTracker.getChangedURIs();
						URI representative = pending.isEmpty() ? null : pending.iterator().next();
						if (representative != null) {
							compilationService.compileAndVisitAST(scope, representative);
						}
					}
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}

		List<CompletableFuture<List<? extends SymbolInformation>>> futures = new ArrayList<>();
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			ASTNodeVisitor visitor = scope.astVisitor;
			if (visitor != null) {
				WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(visitor);
				futures.add(provider.provideWorkspaceSymbols(params.getQuery()));
			}
		}
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
				.thenApply(v -> {
					List<SymbolInformation> allSymbols = new ArrayList<>();
					for (CompletableFuture<List<? extends SymbolInformation>> f : futures) {
						allSymbols.addAll(f.join());
					}
					return Either.forLeft(allSymbols);
				});
	}

	@Override
	public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
			PrepareRenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(null);
		}

		RenameProvider provider = new RenameProvider(visitor, fileContentsTracker);
		return provider.providePrepareRename(params);
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(new WorkspaceEdit());
		}

		RenameProvider provider = new RenameProvider(visitor, fileContentsTracker);
		return provider.provideRename(params);
	}

	@Override
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		ScanResult scanResult = scope != null ? scope.classGraphScanResult : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		CodeActionProvider provider = new CodeActionProvider(visitor, scanResult, fileContentsTracker);
		CompletableFuture<List<Either<Command, CodeAction>>> result = provider.provideCodeActions(params);

		SpockCodeActionProvider spockProvider = new SpockCodeActionProvider(visitor);
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
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		InlayHintProvider provider = new InlayHintProvider(visitor);
		return provider.provideInlayHints(params);
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		if (!scopeManager.isSemanticHighlightingEnabled()) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = scopeManager.findProjectScope(uri);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}

		SemanticTokensProvider provider = new SemanticTokensProvider(visitor, fileContentsTracker);
		return provider.provideSemanticTokensFull(params.getTextDocument());
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
		return CompletableFuture.completedFuture(documentResolverService.resolveCompletionItem(unresolved));
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		if (!scopeManager.isFormattingEnabled()) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		String sourceText = fileContentsTracker.getContents(uri);
		FormattingProvider provider = new FormattingProvider();
		return provider.provideFormatting(params, sourceText);
	}
}
