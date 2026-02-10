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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import com.tomaszrup.groovyls.util.JavaSourceLocator;
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
	private volatile ClasspathResolutionCoordinator resolutionCoordinator;

	// --- Shared executor pools (owned by GroovyLanguageServer) ---

	private final ExecutorPools executorPools;

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

	public GroovyServices(ICompilationUnitFactory factory, ExecutorPools executorPools) {
		this.executorPools = executorPools;
		this.schedulingPool = executorPools.getSchedulingPool();
		this.backgroundCompiler = executorPools.getBackgroundCompilationPool();
		this.scopeManager = new ProjectScopeManager(factory, fileContentsTracker);
		this.compilationService = new CompilationService(fileContentsTracker);
		this.compilationService.setCompilationPermits(executorPools.getCompilationPermits());
		this.fileChangeHandler = new FileChangeHandler(scopeManager, compilationService, schedulingPool);
		this.documentResolverService = new DocumentResolverService(scopeManager);
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
		this.languageClient = client;
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

	/**
	 * Look up decompiled content across all project scopes.
	 * Returns the content for the first matching scope, or {@code null}
	 * if no scope has decompiled content for the given class.
	 *
	 * @param className fully-qualified class name
	 * @return decompiled source text, or {@code null}
	 */
	public String getDecompiledContent(String className) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String content = locator.getDecompiledContent(className);
				if (content != null) {
					return content;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getDecompiledContent(className);
		}
		return null;
	}

	/**
	 * Look up decompiled content by URI string across all project scopes.
	 * Supports {@code decompiled:}, {@code jar:}, and {@code jrt:} URIs.
	 *
	 * @param uri the URI string
	 * @return decompiled source text, or {@code null}
	 */
	public String getDecompiledContentByURI(String uri) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String content = locator.getDecompiledContentByURI(uri);
				if (content != null) {
					return content;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getDecompiledContentByURI(uri);
		}
		return null;
	}

	public void onImportComplete() {
		scopeManager.setImportInProgress(false);
		List<ProjectScope> scopes = scopeManager.getProjectScopes();
		if (scopes.isEmpty()) {
			// No build-tool projects found — compile the default scope
			ProjectScope ds = scopeManager.getDefaultScope();
			ds.getLock().writeLock().lock();
			try {
				Set<URI> openURIs = fileContentsTracker.getOpenURIs();
				for (URI uri : openURIs) {
					compilationService.compileAndVisitAST(ds, uri);
				}
			} finally {
				ds.getLock().writeLock().unlock();
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
			Map<ProjectScope, URI> scopeToResolvedURI = new java.util.LinkedHashMap<>();
			Map<ProjectScope, URI> scopeToUnresolvedURI = new java.util.LinkedHashMap<>();
			for (URI uri : openURIs) {
				ProjectScope scope = scopeManager.findProjectScope(uri);
				if (scope != null && scope.isClasspathResolved()) {
					scopeToResolvedURI.putIfAbsent(scope, uri);
				} else if (scope != null && !scope.isClasspathResolved()) {
					scopeToUnresolvedURI.putIfAbsent(scope, uri);
				}
			}

			if (!scopeToResolvedURI.isEmpty()) {
				logger.info("Scheduling background compilation for {} scope(s) with open files",
						scopeToResolvedURI.size());

				// Prioritise the scope containing the most recently opened
				// file so the user sees diagnostics for it first.
				URI lastOpened = fileContentsTracker.getLastOpenedURI();
				ProjectScope activeScope = lastOpened != null
						? scopeManager.findProjectScope(lastOpened) : null;

				// Submit separate tasks per scope so diagnostics are published
				// incrementally (not blocked until all scopes finish).
				// Active scope is submitted first.
				if (activeScope != null && scopeToResolvedURI.containsKey(activeScope)) {
					ProjectScope first = activeScope;
					MdcProjectContext.setProject(first.getProjectRoot());
					backgroundCompiler.submit(() -> {
						if (Thread.currentThread().isInterrupted()) return;
						first.getLock().writeLock().lock();
						try {
							compilationService.ensureScopeCompiled(first);
						} finally {
							first.getLock().writeLock().unlock();
						}
						if (languageClient != null) {
							languageClient.refreshSemanticTokens();
						}
					});
				}

				for (Map.Entry<ProjectScope, URI> entry : scopeToResolvedURI.entrySet()) {
					ProjectScope scope = entry.getKey();
					if (scope == activeScope) {
						continue; // already submitted above
					}
					MdcProjectContext.setProject(scope.getProjectRoot());
					backgroundCompiler.submit(() -> {
						if (Thread.currentThread().isInterrupted()) return;
						scope.getLock().writeLock().lock();
						try {
							compilationService.ensureScopeCompiled(scope);
						} finally {
							scope.getLock().writeLock().unlock();
						}
						if (languageClient != null) {
							languageClient.refreshSemanticTokens();
						}
					});
				}
				MdcProjectContext.clear();
			}

			// For open files in scopes whose classpath has NOT been resolved
			// yet, trigger lazy resolution now so the user gets diagnostics.
			if (!scopeToUnresolvedURI.isEmpty() && resolutionCoordinator != null) {
				logger.info("Triggering lazy classpath resolution for {} scope(s) with open files",
						scopeToUnresolvedURI.size());
				for (Map.Entry<ProjectScope, URI> entry : scopeToUnresolvedURI.entrySet()) {
					resolutionCoordinator.requestResolution(entry.getKey(), entry.getValue());
				}
			}
		}
	}

	public void setJavaChangeListener(JavaChangeListener listener) {
		fileChangeHandler.setJavaChangeListener(listener::onJavaFilesChanged);
	}

	public void setSettingsChangeListener(SettingsChangeListener listener) {
		this.settingsChangeListener = listener;
	}

	public void setResolutionCoordinator(ClasspathResolutionCoordinator coordinator) {
		this.resolutionCoordinator = coordinator;
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
		if (scope != null) {
			MdcProjectContext.setProject(scope.getProjectRoot());
		}
		try {
			doDidOpen(uri, scope);
		} catch (LinkageError e) {
			// NoClassDefFoundError or similar — a project class could not be
			// loaded during compilation.  Catch here so the error does NOT
			// propagate to LSP4J's listener thread (which only catches
			// Exception, not Error), which would kill the connection and
			// cause the EPIPE seen by the VS Code client.
			logger.warn("Classpath linkage error during didOpen for {}: {}", uri, e.toString());
			logger.debug("didOpen LinkageError details", e);
		} catch (VirtualMachineError e) {
			logger.error("VirtualMachineError during didOpen for {}: {}", uri, e.toString());
			// Swallow to keep the LSP connection alive
		} catch (Exception e) {
			logger.warn("Unexpected exception during didOpen for {}: {}", uri, e.getMessage());
			logger.debug("didOpen exception details", e);
		} finally {
			MdcProjectContext.clear();
		}
	}

	private void doDidOpen(URI uri, ProjectScope scope) {
		if (scope != null && !scopeManager.isImportPendingFor(scope)) {
			if (!scope.isClasspathResolved() && resolutionCoordinator != null) {
				// Classpath not yet resolved — fire a quick syntax-only check
				// so the user sees parse errors immediately, then trigger lazy
				// classpath resolution (which will compile fully once resolved).
				backgroundCompiler.submit(() -> compilationService.syntaxCheckSingleFile(uri));
				resolutionCoordinator.requestResolution(scope, uri);
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

		// Set MDC for the current thread so that the MDC-propagating
		// scheduled executor captures the project context.
		URI changeUri = URI.create(params.getTextDocument().getUri());
		ProjectScope changeScope = scopeManager.findProjectScope(changeUri);
		if (changeScope != null) {
			MdcProjectContext.setProject(changeScope.getProjectRoot());
		}

		// Atomically cancel-and-reschedule to prevent the TOCTOU race where
		// two concurrent didChange calls could both cancel the same future
		// and both schedule new ones, causing redundant compilation.
		ScheduledFuture<?> newFuture = schedulingPool.schedule(() -> {
			try {
				URI uri = URI.create(params.getTextDocument().getUri());
				ProjectScope scope = scopeManager.findProjectScope(uri);
				if (scope != null && !scopeManager.isImportPendingFor(scope)) {
					if (!scope.isClasspathResolved() && resolutionCoordinator != null) {
						// Classpath not yet resolved — request lazy resolution.
						// Don't attempt full compilation without classpath.
						resolutionCoordinator.requestResolution(scope, uri);
					} else {
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
				}
			} catch (LinkageError e) {
				logger.warn("Classpath linkage error during didChange: {}", e.toString());
				logger.debug("didChange LinkageError details", e);
			} catch (VirtualMachineError e) {
				logger.error("VirtualMachineError during didChange: {}", e.toString());
				// Swallow to keep the LSP connection alive
			} catch (Exception e) {
				logger.warn("Unexpected exception during didChange: {}", e.getMessage());
				logger.debug("didChange exception details", e);
			}
		}, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
		ScheduledFuture<?> prev = pendingDebounce.getAndSet(newFuture);
		if (prev != null) {
			prev.cancel(false);
		}
		MdcProjectContext.clear();
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
			scopeManager.getDefaultScope().getCompilationUnitFactory().setAdditionalClasspathList(classpathList);
			return;
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		ds.getLock().writeLock().lock();
		try {
			if (!classpathList.equals(ds.getCompilationUnitFactory().getAdditionalClasspathList())) {
				ds.getCompilationUnitFactory().setAdditionalClasspathList(classpathList);
				compilationService.recompileForClasspathChange(ds);
			}
		} finally {
			ds.getLock().writeLock().unlock();
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

	/**
	 * Collect JavaSourceLocators from all project scopes except the given one.
	 * Used to enable cross-project "Go to Definition" in multi-module workspaces.
	 */
	private List<JavaSourceLocator> collectSiblingLocators(ProjectScope currentScope) {
		List<ProjectScope> allScopes = scopeManager.getAllScopes();
		if (allScopes.size() <= 1) {
			return Collections.emptyList();
		}
		List<JavaSourceLocator> siblings = new ArrayList<>();
		for (ProjectScope other : allScopes) {
			if (other != currentScope && other.getJavaSourceLocator() != null) {
				siblings.add(other.getJavaSourceLocator());
			}
		}
		return siblings;
	}

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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

		// Capture AST snapshot under write-lock, then run providers lock-free.
		ASTNodeVisitor visitor;
		ScanResult scanResult;
		scope.getLock().writeLock().lock();
		try {
			compilationService.ensureScopeCompiled(scope);
			if (scope.getAstVisitor() == null) {
				return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
			}
			if (!fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
				compilationService.recompileIfContextChanged(scope, uri);
			} else {
				compilationService.compileAndVisitAST(scope, uri);
			}

			String originalSource = null;
			ASTNode offsetNode = scope.getAstVisitor().getNodeAtLineAndColumn(uri, position.getLine(),
					position.getCharacter());
			if (offsetNode == null) {
				originalSource = compilationService.injectCompletionPlaceholder(scope, uri, position);
			}

			// Capture snapshot references before releasing the lock
			visitor = scope.getAstVisitor();
			// Lazily trigger ClassGraph scan on first completion request
			scanResult = scope.ensureClassGraphScannedUnsafe();

			if (originalSource != null) {
				compilationService.restoreDocumentSource(scope, uri, originalSource);
			}
		} finally {
			scope.getLock().writeLock().unlock();
		}

		// Provider logic runs lock-free on the captured AST snapshot
		CompletionProvider provider = new CompletionProvider(visitor, scanResult);
		CompletableFuture<Either<List<CompletionItem>, CompletionList>> result =
				provider.provideCompletion(params.getTextDocument(), params.getPosition(),
						params.getContext());

		SpockCompletionProvider spockProvider = new SpockCompletionProvider(visitor);
		ASTNode currentNode = visitor.getNodeAtLineAndColumn(uri, position.getLine(),
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

		return result;
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		DefinitionProvider provider = new DefinitionProvider(visitor, scope.getJavaSourceLocator(),
				collectSiblingLocators(scope));
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
			ASTNode offsetNode = scope.getAstVisitor().getNodeAtLineAndColumn(uri, position.getLine(),
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
		SignatureHelpProvider provider = new SignatureHelpProvider(visitor);
		return provider.provideSignatureHelp(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		TypeDefinitionProvider provider = new TypeDefinitionProvider(visitor, scope.getJavaSourceLocator(),
				collectSiblingLocators(scope));
		return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
			ImplementationParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
			if (!scope.isCompiled() || fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
				scope.getLock().writeLock().lock();
				try {
					boolean didFull = compilationService.ensureScopeCompiled(scope);
					if (!didFull && fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
						Set<URI> pending = fileContentsTracker.getChangedURIs();
						URI representative = pending.isEmpty() ? null : pending.iterator().next();
						if (representative != null) {
							compilationService.compileAndVisitAST(scope, representative);
						}
					}
				} finally {
					scope.getLock().writeLock().unlock();
				}
			}
		}

		List<CompletableFuture<List<? extends SymbolInformation>>> futures = new ArrayList<>();
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			ASTNodeVisitor visitor = scope.getAstVisitor();
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		// Lazily trigger ClassGraph scan on first code action request
		ScanResult scanResult = scope != null ? scope.ensureClassGraphScanned() : null;
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
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
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
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}

		SemanticTokensProvider provider = new SemanticTokensProvider(visitor, fileContentsTracker);
		return provider.provideSemanticTokensFull(params.getTextDocument());
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
		if (!scopeManager.isSemanticHighlightingEnabled()) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}

		SemanticTokensProvider provider = new SemanticTokensProvider(visitor, fileContentsTracker);
		return provider.provideSemanticTokensRange(params.getTextDocument(), params.getRange());
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
