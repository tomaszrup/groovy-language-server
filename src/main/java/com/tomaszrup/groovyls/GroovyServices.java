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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.MdcProjectContext;
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
	private static final String IMPORT_KEYWORD = "import ";
	private static final String PACKAGE_KEYWORD = "package ";

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

	// --- Delegate services ---

	private final ProjectScopeManager scopeManager;
	private final CompilationService compilationService;
	private final FileChangeHandler fileChangeHandler;
	private final DocumentResolverService documentResolverService;
	private final LspProviderFacade providerFacade;
	private final FileContentsTracker fileContentsTracker = new FileContentsTracker();

	private final AtomicReference<LanguageClient> languageClient = new AtomicReference<>();
	private final AtomicReference<ClasspathResolutionCoordinator> resolutionCoordinator = new AtomicReference<>();

	// --- Shared executor pools (owned by GroovyLanguageServer) ---

	/** Shared scheduling pool used for debounce timers. */
	private final ScheduledExecutorService schedulingPool;

	/** Shared background compilation pool. */
	private final ExecutorService backgroundCompiler;

	/**
	 * Atomic holder for the pending debounce future. Uses
	 * {@link AtomicReference#compareAndSet} to prevent the TOCTOU race
	 * where two concurrent {@code didChange} calls could both cancel the
	 * same future and both schedule new ones, causing redundant compilation.
	 */
	private final AtomicReference<ScheduledFuture<?>> pendingDebounce = new AtomicReference<>();

	private final CompletionHandler completionHandler;
	private final DefinitionHandler definitionHandler;
	private final FormattingHandler formattingHandler;
	private final WorkspaceSymbolHandler workspaceSymbolHandler;
	private final SemanticTokensHandler semanticTokensHandler;
	private final LspRequestGuard requestGuard;
	private final ConfigurationChangeHandler configChangeHandler;
	private final CodeActionHandler codeActionHandler;

	public GroovyServices(ICompilationUnitFactory factory, ExecutorPools executorPools) {
		this.schedulingPool = executorPools.getSchedulingPool();
		this.backgroundCompiler = executorPools.getBackgroundCompilationPool();
		this.scopeManager = new ProjectScopeManager(factory, fileContentsTracker);
		this.compilationService = new CompilationService(fileContentsTracker);
		this.compilationService.setCompilationPermits(executorPools.getCompilationPermits());
		this.fileChangeHandler = new FileChangeHandler(scopeManager, compilationService, schedulingPool);
		var importRewriter = new GroovyImportRewriter(fileContentsTracker);
		this.fileChangeHandler.setJavaImportMoveListener(
				(projectRoot, movedImports) -> importRewriter.applyGroovyImportUpdatesForJavaMoves(
						projectRoot, movedImports, languageClient.get()));
		this.documentResolverService = new DocumentResolverService(scopeManager);
		this.providerFacade = new LspProviderFacade(fileContentsTracker);
		this.completionHandler = new CompletionHandler(scopeManager, compilationService, providerFacade, fileContentsTracker);
		this.definitionHandler = new DefinitionHandler(fileContentsTracker);
		this.formattingHandler = new FormattingHandler(providerFacade, fileContentsTracker);
		this.workspaceSymbolHandler = new WorkspaceSymbolHandler(scopeManager, compilationService, providerFacade, fileContentsTracker);
		this.semanticTokensHandler = new SemanticTokensHandler(scopeManager, providerFacade,
				this::ensureCompiledForContext, fileContentsTracker);
		this.requestGuard = new LspRequestGuard(scopeManager);
		this.configChangeHandler = new ConfigurationChangeHandler(scopeManager, compilationService);
		this.codeActionHandler = new CodeActionHandler(compilationService, scopeManager, backgroundCompiler, providerFacade);
	}

	/**
	 * Convenience constructor that creates its own {@link ExecutorPools}.
	 * Used by tests and standalone scenarios where shared pools are not needed.
	 */
	public GroovyServices(ICompilationUnitFactory factory) {
		this(factory, new ExecutorPools());
	}

	// --- Lifecycle / wiring ---

	@Override
	public void connect(LanguageClient client) {
		this.languageClient.set(client);
		scopeManager.setLanguageClient(client);
		compilationService.setLanguageClient(client);
	}

	public void shutdown() {
		// Pool shutdown is handled centrally by ExecutorPools.shutdownAll()
		// called from GroovyLanguageServer.shutdown().
		// Cancel any pending debounce to avoid stale tasks.
		ScheduledFuture<?> pending = pendingDebounce.getAndSet(null);
		if (pending != null) {
			pending.cancel(false);
		}

		// Dispose all project scopes to release classloaders, shared caches, etc.
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			try {
				scope.dispose();
			} catch (Exception e) {
				logger.debug("Error disposing scope {}: {}", scope.getProjectRoot(), e.getMessage());
			}
		}
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		scopeManager.setWorkspaceRoot(workspaceRoot);
		MdcProjectContext.setWorkspaceRoot(workspaceRoot);
	}

	public Path getWorkspaceRoot() {
		return scopeManager.getWorkspaceRoot();
	}

	public void setImportInProgress(boolean inProgress) {
		scopeManager.setImportInProgress(inProgress);
	}

	protected ProjectScope ensureCompiledForContext(URI uri) {
		return compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
	}

	/**
	 * Delegates to {@link SemanticTokensHandler#createSemanticTokensProvider(ASTNodeVisitor, ProjectScope)}.
	 * Kept for backward compatibility with tests.
	 */
	protected Object createSemanticTokensProvider(ASTNodeVisitor visitor, ProjectScope scope) {
		return semanticTokensHandler.createSemanticTokensProvider(visitor, scope);
	}

	private <T> CompletableFuture<T> failSoftRequest(String requestName, URI uri,
			Supplier<CompletableFuture<T>> requestCall, T fallbackValue) {
		return requestGuard.failSoftRequest(requestName, uri, requestCall, fallbackValue);
	}

	public String getDecompiledContent(String className) {
		return documentResolverService.getDecompiledContent(className);
	}

	public String getDecompiledContentByURI(String uri) {
		return documentResolverService.getDecompiledContentByURI(uri);
	}

	public String getJavaNavigationURI(String uri) {
		return documentResolverService.getJavaNavigationURI(uri);
	}

	public void onImportComplete() {
		scopeManager.setImportInProgress(false);
		List<ProjectScope> scopes = scopeManager.getProjectScopes();
		if (scopes.isEmpty()) {
			compileDefaultScopeOpenFiles();
			return;
		}
		Set<URI> openURIs = fileContentsTracker.getOpenURIs();
		if (openURIs.isEmpty()) {
			return;
		}

		Map<ProjectScope, URI> scopeToResolvedURI = new java.util.LinkedHashMap<>();
		Map<ProjectScope, URI> scopeToUnresolvedURI = new java.util.LinkedHashMap<>();
		partitionOpenUrisByClasspathState(openURIs, scopeToResolvedURI, scopeToUnresolvedURI);

		scheduleResolvedScopeCompilations(scopeToResolvedURI);
		requestLazyResolutionForUnresolvedScopes(scopeToUnresolvedURI);
	}

	private void compileDefaultScopeOpenFiles() {
		ProjectScope defaultScope = scopeManager.getDefaultScope();
		defaultScope.getLock().writeLock().lock();
		try {
			Set<URI> openUris = fileContentsTracker.getOpenURIs();
			for (URI uri : openUris) {
				compilationService.compileAndVisitAST(defaultScope, uri);
			}
		} finally {
			defaultScope.getLock().writeLock().unlock();
		}
		refreshSemanticTokensIfConnected();
	}

	private void partitionOpenUrisByClasspathState(
			Set<URI> openURIs,
			Map<ProjectScope, URI> scopeToResolvedURI,
			Map<ProjectScope, URI> scopeToUnresolvedURI) {
		for (URI uri : openURIs) {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			if (scope == null) {
				continue;
			}
			if (scope.isClasspathResolved()) {
				scopeToResolvedURI.putIfAbsent(scope, uri);
			} else {
				scopeToUnresolvedURI.putIfAbsent(scope, uri);
			}
		}
	}

	private void scheduleResolvedScopeCompilations(Map<ProjectScope, URI> scopeToResolvedURI) {
		if (scopeToResolvedURI.isEmpty()) {
			return;
		}
		logger.info("Scheduling background compilation for {} scope(s) with open files",
				scopeToResolvedURI.size());

		ProjectScope activeScope = resolveActiveScope();
		if (activeScope != null && scopeToResolvedURI.containsKey(activeScope)) {
			submitScopeCompilation(activeScope);
		}

		for (Map.Entry<ProjectScope, URI> entry : scopeToResolvedURI.entrySet()) {
			ProjectScope scope = entry.getKey();
			if (scope != activeScope) {
				submitScopeCompilation(scope);
			}
		}
		MdcProjectContext.clear();
	}

	private ProjectScope resolveActiveScope() {
		URI lastOpened = fileContentsTracker.getLastOpenedURI();
		return lastOpened != null ? scopeManager.findProjectScope(lastOpened) : null;
	}

	private void submitScopeCompilation(ProjectScope scope) {
		MdcProjectContext.setProject(scope.getProjectRoot());
		backgroundCompiler.submit(() -> {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			scope.getLock().writeLock().lock();
			try {
				compilationService.ensureScopeCompiled(scope);
			} finally {
				scope.getLock().writeLock().unlock();
			}
			refreshSemanticTokensIfConnected();
		});
	}

	private void requestLazyResolutionForUnresolvedScopes(Map<ProjectScope, URI> scopeToUnresolvedURI) {
		ClasspathResolutionCoordinator coordinator = resolutionCoordinator.get();
		if (scopeToUnresolvedURI.isEmpty() || coordinator == null) {
			return;
		}
		logger.info("Triggering lazy classpath resolution for {} scope(s) with open files",
				scopeToUnresolvedURI.size());
		for (Map.Entry<ProjectScope, URI> entry : scopeToUnresolvedURI.entrySet()) {
			coordinator.requestResolution(entry.getKey(), entry.getValue());
		}
	}

	private void refreshSemanticTokensIfConnected() {
		LanguageClient client = languageClient.get();
		if (client != null) {
			client.refreshSemanticTokens();
		}
	}

	public void setJavaChangeListener(JavaChangeListener listener) {
		fileChangeHandler.setJavaChangeListener(listener::onJavaFilesChanged);
	}

	public void setSettingsChangeListener(ConfigurationChangeHandler.SettingsChangeListener listener) {
		configChangeHandler.setSettingsChangeListener(listener);
	}

	public void setResolutionCoordinator(ClasspathResolutionCoordinator coordinator) {
		this.resolutionCoordinator.set(coordinator);
	}

	public ProjectScopeManager getScopeManager() {
		return scopeManager;
	}

	public CompilationService getCompilationService() {
		return compilationService;
	}

	// --- Project registration (delegated to scopeManager + compilationService) ---

	public void registerDiscoveredProjects(List<Path> projectRoots) {
		scopeManager.registerDiscoveredProjects(projectRoots);
	}

	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths) {
		updateProjectClasspaths(projectClasspaths, java.util.Collections.emptyMap());
	}

	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths,
									 Map<Path, String> projectGroovyVersions) {
		updateProjectClasspaths(projectClasspaths, projectGroovyVersions, java.util.Collections.emptyMap());
		// Compilation is NOT done here — scopes compile lazily on first
		// interaction (didOpen, didChange, hover, etc.) or via background
		// compilation submitted by onImportComplete().  This avoids the
		// expensive O(open-tabs × projects) synchronous compilation that
		// caused log spam and long import times in large workspaces.
	}

	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths,
								 Map<Path, String> projectGroovyVersions,
								 Map<Path, Boolean> projectResolvedStates) {
		scopeManager.updateProjectClasspaths(projectClasspaths, projectGroovyVersions, projectResolvedStates);
		// Compilation is NOT done here — scopes compile lazily on first
		// interaction (didOpen, didChange, hover, etc.) or via background
		// compilation submitted by onImportComplete().  This avoids the
		// expensive O(open-tabs × projects) synchronous compilation that
		// caused log spam and long import times in large workspaces.
	}

	public void addProjects(Map<Path, List<String>> projectClasspaths) {
		addProjects(projectClasspaths, java.util.Collections.emptyMap());
	}

	public void addProjects(Map<Path, List<String>> projectClasspaths,
							 Map<Path, String> projectGroovyVersions) {
		Set<URI> openURIs = scopeManager.addProjects(projectClasspaths, projectGroovyVersions);

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
			scope.getLock().writeLock().lock();
			try {
				boolean didFullCompile = compilationService.ensureScopeCompiled(scope);
				if (!didFullCompile) {
					compilationService.compileAndVisitAST(scope, uri);
				}
			} finally {
				scope.getLock().writeLock().unlock();
			}
		}

		refreshSemanticTokensIfConnected();
	}

	// --- Utility (kept for backward compat with tests) ---

	static boolean isBuildOutputFile(Path filePath, Path projectRoot) {
		return FileChangeHandler.isBuildOutputFile(filePath, projectRoot);
	}

	// --- TextDocumentService notifications ---

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		fileContentsTracker.didOpen(params);
		URI openedUri = null;
		try {
			openedUri = URI.create(params.getTextDocument().getUri());
			ProjectScope scope = scopeManager.findProjectScope(openedUri);
			if (scope != null) {
				MdcProjectContext.setProject(scope.getProjectRoot());
			}
			doDidOpen(openedUri, scope);
		} catch (LinkageError e) {
			// NoClassDefFoundError or similar — a project class could not be
			// loaded during compilation.  Catch here so the error does NOT
			// propagate to LSP4J's listener thread (which only catches
			// Exception, not Error), which would kill the connection and
			// cause the EPIPE seen by the VS Code client.
			logger.warn("Classpath linkage error during didOpen for {}: {}", openedUri, e.toString());
			logger.debug("didOpen LinkageError details", e);
		} catch (VirtualMachineError e) {
			logger.error("VirtualMachineError during didOpen for {}: {}", openedUri, e.toString());
			// Swallow to keep the LSP connection alive
		} catch (Exception e) {
			logger.warn("Unexpected exception during didOpen for {}: {}", openedUri, e.getMessage());
			logger.debug("didOpen exception details", e);
		} finally {
			MdcProjectContext.clear();
		}
	}

	private void doDidOpen(URI uri, ProjectScope scope) {
		if (scope != null && !scopeManager.isImportPendingFor(scope)) {
			ClasspathResolutionCoordinator coordinator = resolutionCoordinator.get();
			if (!scope.isClasspathResolved() && coordinator != null) {
				// Classpath not yet resolved — fire a quick syntax-only check
				// so the user sees parse errors immediately, then trigger lazy
				// classpath resolution (which will compile fully once resolved).
				backgroundCompiler.submit(() -> compilationService.syntaxCheckSingleFile(uri));
				coordinator.requestResolution(scope, uri);
			} else {
				// Classpath is resolved. Run staged compilation:
				// Phase A (single-file) runs synchronously so that diagnostics
				// (including unused-import hints) are published before didOpen
				// returns. Phase B (full project) is submitted to the
				// backgroundCompiler internally by ensureScopeCompiled.
				scope.getLock().writeLock().lock();
				try {
					compilationService.ensureScopeCompiled(scope, uri, backgroundCompiler);
				} finally {
					scope.getLock().writeLock().unlock();
				}
			}
		} else {
			// No matching scope or import is pending — submit a syntax-only
			// check so the user still gets parse-error feedback.
			backgroundCompiler.submit(() -> compilationService.syntaxCheckSingleFile(uri));
		}
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		fileContentsTracker.didChange(params);
		traceDidChangeIfEnabled(params);

		URI changeUri = URI.create(params.getTextDocument().getUri());
		setDidChangeMdcProject(changeUri);

		ScheduledFuture<?> newFuture = schedulingPool.schedule(
				() -> runDidChangeCompilation(changeUri),
				DEBOUNCE_DELAY_MS,
				TimeUnit.MILLISECONDS);
		ScheduledFuture<?> prev = pendingDebounce.getAndSet(newFuture);
		if (prev != null) {
			prev.cancel(false);
		}
		MdcProjectContext.clear();
	}

	private void traceDidChangeIfEnabled(DidChangeTextDocumentParams params) {
		if (!logger.isDebugEnabled() && !logger.isTraceEnabled()) {
			return;
		}
		try {
			URI traceUri = URI.create(params.getTextDocument().getUri());
			VersionedTextDocumentIdentifier doc = params.getTextDocument();
			List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
			boolean importOrPackageEdit = hasImportOrPackageEdit(changes);

			if (logger.isDebugEnabled()) {
				logger.debug("didChangeTrace uri={} version={} changeCount={} importOrPackageEdit={}",
						traceUri,
						doc != null ? doc.getVersion() : null,
						changes != null ? changes.size() : 0,
						importOrPackageEdit);
			}
			if (importOrPackageEdit && logger.isTraceEnabled()) {
				logDidChangeImports(traceUri);
			}
		} catch (Exception e) {
			logger.trace("didChangeTrace logging failed", e);
		}
	}

	private boolean hasImportOrPackageEdit(List<TextDocumentContentChangeEvent> changes) {
		if (changes == null) {
			return false;
		}
		for (TextDocumentContentChangeEvent change : changes) {
			String text = change.getText();
			if (text != null && (text.contains(IMPORT_KEYWORD)
					|| text.contains(PACKAGE_KEYWORD)
					|| text.contains("com."))) {
				return true;
			}
		}
		return false;
	}

	private void logDidChangeImports(URI traceUri) {
		String trackedContents = fileContentsTracker.getContents(traceUri);
		if (trackedContents != null) {
			List<String> imports = FormattingHandler.collectImportLines(trackedContents, 12);
			logger.trace("didChangeImports uri={} imports={}", traceUri, imports);
		}
	}

	private void setDidChangeMdcProject(URI changeUri) {
		ProjectScope changeScope = scopeManager.findProjectScope(changeUri);
		if (changeScope != null) {
			MdcProjectContext.setProject(changeScope.getProjectRoot());
		}
	}

	private void runDidChangeCompilation(URI uri) {
		try {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			if (scope != null && !scopeManager.isImportPendingFor(scope)) {
				handleDidChangeCompilationForScope(scope, uri);
			}
		} catch (LinkageError e) {
			logger.warn("Classpath linkage error during didChange: {}", e.toString());
			logger.debug("didChange LinkageError details", e);
		} catch (VirtualMachineError e) {
			logger.error("VirtualMachineError during didChange: {}", e.toString());
		} catch (Exception e) {
			logger.warn("Unexpected exception during didChange: {}", e.getMessage());
			logger.debug("didChange exception details", e);
		}
	}

	private void handleDidChangeCompilationForScope(ProjectScope scope, URI uri) {
		ClasspathResolutionCoordinator coordinator = resolutionCoordinator.get();
		if (!scope.isClasspathResolved() && coordinator != null) {
			coordinator.requestResolution(scope, uri);
			return;
		}
		scope.getLock().writeLock().lock();
		try {
			boolean didFullCompile = compilationService.ensureScopeCompiled(scope);
			if (!didFullCompile) {
				compilationService.compileAndVisitAST(scope, uri);
			}
		} finally {
			scope.getLock().writeLock().unlock();
		}
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		fileContentsTracker.didClose(params);
		try {
			URI uri = URI.create(params.getTextDocument().getUri());
			semanticTokensHandler.clearCache(uri);
		} catch (Exception ignored) {
			// best effort cache cleanup
		}
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
		configChangeHandler.handleConfigurationChange(params.getSettings());
	}

	// --- Configuration / classpath ---

	void updateClasspath(List<String> classpathList) {
		ProjectScopeManager.ClasspathUpdateResult result = scopeManager.updateClasspath(classpathList);
		if (result != ProjectScopeManager.ClasspathUpdateResult.UPDATED) {
			return;
		}

		ProjectScope ds = scopeManager.getDefaultScope();
		ds.getLock().writeLock().lock();
		try {
			compilationService.recompileForClasspathChange(ds);
		} finally {
			ds.getLock().writeLock().unlock();
		}
	}

	// --- TextDocumentService requests ---

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		return failSoftRequest("hover", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(null);
			}

			return providerFacade.provideHover(visitor, params.getTextDocument(), params.getPosition());
		}, null);
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		return failSoftRequest("completion", uri,
				() -> completionHandler.provideCompletionForUri(params, uri, position),
				Either.forRight(new CompletionList()));
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		return failSoftRequest("definition", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			return providerFacade.provideDefinition(visitor, scope.getJavaSourceLocator(),
					params.getTextDocument(), params.getPosition())
					.thenApply(definitionHandler::toLspDefinitionResult)
					.thenApply(result -> {
						if (!definitionHandler.isEmptyDefinitionResult(result)) {
							return result;
						}
						Either<List<? extends Location>, List<? extends LocationLink>> fallback =
								definitionHandler.resolveDefinitionFromJavaVirtualSource(uri, params.getPosition(),
										scope.getJavaSourceLocator());
						if (!definitionHandler.isEmptyDefinitionResult(fallback)) {
							logger.debug("Java virtual-source fallback resolved definition for {}", uri);
							return fallback;
						}
						return result;
					});
		}, Either.forLeft(Collections.emptyList()));
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		return failSoftRequest("signatureHelp", uri, () -> {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			if (scope == null) {
				return CompletableFuture.completedFuture(new SignatureHelp());
			}

			// Capture AST snapshot under write-lock, then run provider lock-free.
			ASTNodeVisitor visitor;
			scope.getLock().writeLock().lock();
			try {
				compilationService.ensureScopeCompiled(scope);
				if (scope.getAstVisitor() == null) {
					return CompletableFuture.completedFuture(new SignatureHelp());
				}
				if (!fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
					compilationService.recompileIfContextChanged(scope, uri);
				} else {
					compilationService.compileAndVisitAST(scope, uri);
				}

				String originalSource = null;
				var offsetNode = scope.getAstVisitor().getNodeAtLineAndColumn(uri, position.getLine(),
						position.getCharacter());
				if (offsetNode == null) {
					originalSource = compilationService.injectSignatureHelpPlaceholder(scope, uri, position);
				}

				// Capture snapshot reference before releasing the lock
				visitor = scope.getAstVisitor();

				if (originalSource != null) {
					compilationService.restoreDocumentSource(scope, uri, originalSource);
				}
			} finally {
				scope.getLock().writeLock().unlock();
			}

			// Provider logic runs lock-free on the captured AST snapshot
			return providerFacade.provideSignatureHelp(visitor, params.getTextDocument(), params.getPosition());
		}, new SignatureHelp());
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		return failSoftRequest("typeDefinition", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			return providerFacade.provideTypeDefinition(visitor, scope.getJavaSourceLocator(),
					params.getTextDocument(), params.getPosition())
					.thenApply(definitionHandler::toLspDefinitionResult);
		}, Either.forLeft(Collections.emptyList()));
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
			ImplementationParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		return failSoftRequest("implementation", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			return providerFacade.provideImplementation(visitor, params.getTextDocument(), params.getPosition())
					.thenApply(definitionHandler::toLspDefinitionResult);
		}, Either.forLeft(Collections.emptyList()));
	}

	@Override
	public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		return providerFacade.provideDocumentHighlights(visitor, params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		return failSoftRequest("references", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			return providerFacade.provideReferences(visitor, params.getTextDocument(), params.getPosition());
		}, Collections.emptyList());
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
			DocumentSymbolParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		return failSoftRequest("documentSymbol", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			return providerFacade.provideDocumentSymbols(visitor, params.getTextDocument());
		}, Collections.emptyList());
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			WorkspaceSymbolParams params) {
		return workspaceSymbolHandler.symbol(params);
	}

	@Override
	public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
			PrepareRenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(null);
		}

		return providerFacade.providePrepareRename(visitor, params);
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(new WorkspaceEdit());
		}

		return providerFacade.provideRename(visitor, params);
	}

	@Override
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		return codeActionHandler.codeAction(params);
	}

	@Override
	public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		try {
			return failSoftRequest("inlayHint", uri, () -> {
				ProjectScope scope = ensureCompiledForContext(uri);
				ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
				if (visitor == null) {
					return CompletableFuture.completedFuture(Collections.emptyList());
				}

				return providerFacade.provideInlayHints(visitor, params);
			}, Collections.emptyList());
		} catch (Exception | LinkageError throwable) {
			Throwable root = LspRequestGuard.unwrapRequestThrowable(throwable);
			if (LspRequestGuard.isFatalRequestThrowable(root)) {
				LspRequestGuard.throwAsUnchecked(root);
			}
			requestGuard.logRequestFailure("inlayHint", uri, root, false);
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		return semanticTokensHandler.semanticTokensFull(params);
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
		return semanticTokensHandler.semanticTokensRange(params);
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
		return formattingHandler.formatting(params,
				scopeManager.isFormattingOrganizeImportsEnabled(),
				this::ensureCompiledForContext);
	}
}
