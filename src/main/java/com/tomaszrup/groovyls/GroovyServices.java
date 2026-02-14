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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import org.codehaus.groovy.ast.ASTNode;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
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
import com.tomaszrup.groovyls.providers.codeactions.OrganizeImportsAction;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.JavaSourceLocator;
import com.tomaszrup.groovyls.util.MdcProjectContext;
import com.tomaszrup.groovyls.util.GroovyVersionDetector;
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
	private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;?\\s*$");
	private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;?\\s*$");

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

	/**
	 * Last non-empty semantic tokens per document URI.
	 * Used as a fallback during transient syntax-error states where current
	 * compilation yields no semantic tokens for a file.
	 */
	private final Map<URI, SemanticTokens> lastSemanticTokensByUri = new ConcurrentHashMap<>();

	public GroovyServices(ICompilationUnitFactory factory, ExecutorPools executorPools) {
		this.schedulingPool = executorPools.getSchedulingPool();
		this.backgroundCompiler = executorPools.getBackgroundCompilationPool();
		this.scopeManager = new ProjectScopeManager(factory, fileContentsTracker);
		this.compilationService = new CompilationService(fileContentsTracker);
		this.compilationService.setCompilationPermits(executorPools.getCompilationPermits());
		this.fileChangeHandler = new FileChangeHandler(scopeManager, compilationService, schedulingPool);
		this.fileChangeHandler.setJavaImportMoveListener(this::applyGroovyImportUpdatesForJavaMoves);
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

	protected ProjectScope ensureCompiledForContext(URI uri) {
		return compilationService.ensureCompiledForContext(uri, scopeManager, backgroundCompiler);
	}

	protected SemanticTokensProvider createSemanticTokensProvider(ASTNodeVisitor visitor) {
		return new SemanticTokensProvider(visitor, fileContentsTracker);
	}

	protected SemanticTokensProvider createSemanticTokensProvider(ASTNodeVisitor visitor, ProjectScope scope) {
		boolean groovy4Compatibility = isGroovy4ColumnCompatibilityRequired(scope);
		return new SemanticTokensProvider(visitor, fileContentsTracker, groovy4Compatibility);
	}

	private boolean isGroovy4ColumnCompatibilityRequired(ProjectScope scope) {
		try {
			Integer runtimeMajor = GroovyVersionDetector.major(groovy.lang.GroovySystem.getVersion()).orElse(null);
			if (runtimeMajor != null && runtimeMajor <= 4) {
				return true;
			}
		} catch (Throwable t) {
			logger.debug("Could not detect Groovy runtime version for semantic token compatibility: {}",
					t.toString());
		}

		if (scope == null) {
			return false;
		}

		Integer projectMajor = GroovyVersionDetector.major(scope.getDetectedGroovyVersion()).orElse(null);
		return projectMajor != null && projectMajor <= 4;
	}

	private <T> CompletableFuture<T> failSoftRequest(String requestName, URI uri,
			Supplier<CompletableFuture<T>> requestCall, T fallbackValue) {
		try {
			CompletableFuture<T> future = requestCall.get();
			if (future == null) {
				return CompletableFuture.completedFuture(fallbackValue);
			}
			return future.exceptionally(throwable -> {
				Throwable root = unwrapRequestThrowable(throwable);
				if (isFatalRequestThrowable(root)) {
					throwAsUnchecked(root);
				}
				logRequestFailure(requestName, uri, root, true);
				return fallbackValue;
			});
		} catch (Throwable throwable) {
			Throwable root = unwrapRequestThrowable(throwable);
			if (isFatalRequestThrowable(root)) {
				throwAsUnchecked(root);
			}
			logRequestFailure(requestName, uri, root, false);
			return CompletableFuture.completedFuture(fallbackValue);
		}
	}

	private void logRequestFailure(String requestName, URI uri, Throwable throwable,
			boolean fromAsyncStage) {
		ProjectScope scope = uri != null ? scopeManager.findProjectScope(uri) : null;
		Path projectRoot = scope != null ? scope.getProjectRoot() : null;
		String phase = fromAsyncStage ? "async" : "sync";
		logger.warn("{} request failed ({}), uri={}, projectRoot={}, error={}", requestName, phase, uri,
				projectRoot, throwable.toString());
		logger.debug("{} request failure details", requestName, throwable);
	}

	private static Throwable unwrapRequestThrowable(Throwable throwable) {
		Throwable current = throwable;
		while (current instanceof java.util.concurrent.CompletionException
				|| current instanceof ExecutionException) {
			Throwable cause = current.getCause();
			if (cause == null) {
				break;
			}
			current = cause;
		}
		return current;
	}

	private static boolean isFatalRequestThrowable(Throwable throwable) {
		return throwable instanceof VirtualMachineError;
	}

	private static void throwAsUnchecked(Throwable throwable) {
		if (throwable instanceof RuntimeException) {
			throw (RuntimeException) throwable;
		}
		if (throwable instanceof Error) {
			throw (Error) throwable;
		}
		throw new RuntimeException(throwable);
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

	/**
	 * Resolve a URI for Java definition providers.
	 * Converts virtual dependency URIs to provider-compatible URI forms
	 * (for example, source-jar entries as {@code jar:file:///...!/entry.java}).
	 *
	 * @param uri the current document URI
	 * @return Java-provider-compatible URI string, or {@code null} if unavailable
	 */
	public String getJavaNavigationURI(String uri) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String resolvedUri = locator.getJavaNavigationURI(uri);
				if (resolvedUri != null) {
					return resolvedUri;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getJavaNavigationURI(uri);
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
		if (logger.isDebugEnabled() || logger.isTraceEnabled()) {
			try {
				URI traceUri = URI.create(params.getTextDocument().getUri());
				VersionedTextDocumentIdentifier doc = params.getTextDocument();
				List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
				boolean importOrPackageEdit = false;
				if (changes != null) {
					for (TextDocumentContentChangeEvent ev : changes) {
						String text = ev.getText();
						if (text != null && (text.contains("import ") || text.contains("package ")
								|| text.contains("com."))) {
							importOrPackageEdit = true;
							break;
						}
					}
				}

				if (logger.isDebugEnabled()) {
					logger.debug("didChangeTrace uri={} version={} changeCount={} importOrPackageEdit={}",
							traceUri,
							doc != null ? doc.getVersion() : null,
							changes != null ? changes.size() : 0,
							importOrPackageEdit);
				}

				if (importOrPackageEdit && logger.isTraceEnabled()) {
					String trackedContents = fileContentsTracker.getContents(traceUri);
					if (trackedContents != null) {
						List<String> imports = collectImportLines(trackedContents, 12);
						logger.trace("didChangeImports uri={} imports={}", traceUri, imports);
					}
				}
			} catch (Exception e) {
				logger.trace("didChangeTrace logging failed: {}", e.toString());
			}
		}

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
		try {
			URI uri = URI.create(params.getTextDocument().getUri());
			lastSemanticTokensByUri.remove(uri);
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

	private void applyGroovyImportUpdatesForJavaMoves(Path projectRoot, Map<String, String> movedImports) {
		if (projectRoot == null || movedImports == null || movedImports.isEmpty()) {
			return;
		}
		LanguageClient client = languageClient;
		if (client == null) {
			return;
		}

		Set<URI> candidateUris = collectGroovyUrisForProject(projectRoot);
		if (candidateUris.isEmpty()) {
			return;
		}

		Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
		for (URI uri : candidateUris) {
			String source = fileContentsTracker.getContents(uri);
			if (source == null || source.isEmpty()) {
				continue;
			}
			String updated = rewriteGroovyImports(source, movedImports);
			if (updated.equals(source)) {
				continue;
			}

			int endLine = 0;
			int endChar = 0;
			if (!source.isEmpty()) {
				String[] lines = source.split("\\n", -1);
				endLine = lines.length - 1;
				endChar = lines[endLine].length();
			}
			TextEdit fullReplacement = new TextEdit(
					new Range(new Position(0, 0), new Position(endLine, endChar)),
					updated);
			changes.put(uri.toString(), Collections.singletonList(fullReplacement));

			if (fileContentsTracker.isOpen(uri)) {
				fileContentsTracker.setContents(uri, updated);
				fileContentsTracker.forceChanged(uri);
			}
		}

		if (changes.isEmpty()) {
			return;
		}

		WorkspaceEdit edit = new WorkspaceEdit();
		edit.setChanges(changes);
		ApplyWorkspaceEditParams params = new ApplyWorkspaceEditParams(edit);
		client.applyEdit(params).exceptionally(error -> {
			logger.debug("Failed to apply Groovy import updates after Java move: {}", error.getMessage());
			return null;
		});
	}

	private Set<URI> collectGroovyUrisForProject(Path projectRoot) {
		Set<URI> uris = new LinkedHashSet<>();
		for (URI openUri : fileContentsTracker.getOpenURIs()) {
			try {
				Path openPath = Path.of(openUri);
				if (openPath.startsWith(projectRoot)
						&& openPath.toString().endsWith(".groovy")
						&& !isBuildOutputFile(openPath, projectRoot)) {
					uris.add(openUri);
				}
			} catch (Exception e) {
				// ignore URI that cannot be converted to a path
			}
		}

		try (var stream = Files.walk(projectRoot)) {
			stream.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".groovy"))
					.filter(path -> !isBuildOutputFile(path, projectRoot))
					.forEach(path -> uris.add(path.toUri()));
		} catch (Exception e) {
			logger.debug("Failed to scan Groovy files for import updates under {}: {}",
					projectRoot, e.getMessage());
		}

		return uris;
	}

	private String rewriteGroovyImports(String source, Map<String, String> movedImports) {
		String updated = source;
		for (Map.Entry<String, String> move : movedImports.entrySet()) {
			String oldFqcn = move.getKey();
			String newFqcn = move.getValue();
			updated = updated.replaceAll(
					"(?m)^([\\t ]*import[\\t ]+)" + java.util.regex.Pattern.quote(oldFqcn)
							+ "((?:[\\t ]+as[\\t ]+[A-Za-z_$][\\w$]*)?[\\t ]*;?[\\t ]*)$",
					"$1" + java.util.regex.Matcher.quoteReplacement(newFqcn) + "$2");
			updated = updated.replaceAll(
					"(?m)^([\\t ]*import[\\t ]+static[\\t ]+)" + java.util.regex.Pattern.quote(oldFqcn)
							+ "(\\.[^\\r\\n;]+[\\t ]*;?[\\t ]*)$",
					"$1" + java.util.regex.Matcher.quoteReplacement(newFqcn) + "$2");
		}
		updated = addImportsForSamePackageJavaMoves(updated, movedImports);
		return updated;
	}

	private String addImportsForSamePackageJavaMoves(String source, Map<String, String> movedImports) {
		String updated = source;
		String filePackage = extractGroovyPackage(updated);
		if (filePackage == null || filePackage.isEmpty()) {
			return updated;
		}

		for (Map.Entry<String, String> move : movedImports.entrySet()) {
			String oldFqcn = move.getKey();
			String newFqcn = move.getValue();
			String oldPackage = packageNameOf(oldFqcn);
			String newPackage = packageNameOf(newFqcn);
			String simpleName = simpleNameOf(newFqcn);

			// Only add import when file was previously in the same package as
			// the class and the new package is different.
			if (!filePackage.equals(oldPackage) || filePackage.equals(newPackage)) {
				continue;
			}

			if (hasImport(updated, oldFqcn) || hasImport(updated, newFqcn)) {
				continue;
			}

			if (!containsUnqualifiedTypeReference(updated, simpleName)) {
				continue;
			}

			updated = insertImport(updated, newFqcn);
		}

		return updated;
	}

	private String extractGroovyPackage(String source) {
		for (String line : source.split("\\R", -1)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
				continue;
			}
			if (trimmed.startsWith("package ")) {
				String pkg = trimmed.substring("package ".length()).trim();
				if (pkg.endsWith(";")) {
					pkg = pkg.substring(0, pkg.length() - 1).trim();
				}
				return pkg;
			}
			break;
		}
		return null;
	}

	private String packageNameOf(String fqcn) {
		if (fqcn == null) {
			return "";
		}
		int dot = fqcn.lastIndexOf('.');
		return dot >= 0 ? fqcn.substring(0, dot) : "";
	}

	private String simpleNameOf(String fqcn) {
		if (fqcn == null) {
			return "";
		}
		int dot = fqcn.lastIndexOf('.');
		return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
	}

	private boolean hasImport(String source, String fqcn) {
		if (fqcn == null || fqcn.isEmpty()) {
			return false;
		}
		return source.matches("(?s).*(?m)^[\\t ]*import[\\t ]+" + java.util.regex.Pattern.quote(fqcn)
				+ "(?:[\\t ]+as[\\t ]+[A-Za-z_$][\\w$]*)?[\\t ]*;?[\\t ]*$.*");
	}

	private boolean containsUnqualifiedTypeReference(String source, String simpleName) {
		if (simpleName == null || simpleName.isEmpty()) {
			return false;
		}
		return source.matches("(?s).*\\b" + java.util.regex.Pattern.quote(simpleName) + "\\b.*");
	}

	private String insertImport(String source, String fqcn) {
		String newline = source.contains("\r\n") ? "\r\n" : "\n";
		String importLine = "import " + fqcn + newline;

		String[] lines = source.split("\\R", -1);
		int insertAt = 0;
		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
				insertAt = i + 1;
				continue;
			}
			if (trimmed.isEmpty()) {
				if (insertAt == i) {
					insertAt = i + 1;
				}
				continue;
			}
			break;
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i == insertAt) {
				sb.append(importLine);
			}
			sb.append(lines[i]);
			if (i < lines.length - 1) {
				sb.append(newline);
			}
		}
		if (insertAt >= lines.length) {
			if (!source.endsWith(newline) && !source.isEmpty()) {
				sb.append(newline);
			}
			sb.append(importLine);
		}
		return sb.toString();
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

	private void updateClasspath(JsonObject settings) {
		ProjectScopeManager.ClasspathUpdateResult result = scopeManager.updateClasspathFromSettings(settings);
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

			HoverProvider provider = new HoverProvider(visitor);
			return provider.provideHover(params.getTextDocument(), params.getPosition());
		}, null);
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		TextDocumentIdentifier textDocument = params.getTextDocument();
		Position position = params.getPosition();
		URI uri = URI.create(textDocument.getUri());

		return failSoftRequest("completion", uri, () -> {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			if (scope == null) {
				return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
			}

			// Capture AST snapshot under write-lock, then run providers lock-free.
			ASTNodeVisitor visitor;
			ClasspathSymbolIndex classpathSymbolIndex;
			java.util.Set<String> classpathSymbolClasspathElements;
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
				classpathSymbolIndex = scope.ensureClasspathSymbolIndexUnsafe();
				classpathSymbolClasspathElements = scope.getClasspathSymbolClasspathElements();

				if (originalSource != null) {
					compilationService.restoreDocumentSource(scope, uri, originalSource);
				}
			} finally {
				scope.getLock().writeLock().unlock();
			}

			// Provider logic runs lock-free on the captured AST snapshot
			CompletionProvider provider = new CompletionProvider(visitor, classpathSymbolIndex,
					classpathSymbolClasspathElements);
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
		}, Either.forRight(new CompletionList()));
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

			DefinitionProvider provider = new DefinitionProvider(visitor, scope.getJavaSourceLocator());
			return provider.provideDefinition(params.getTextDocument(), params.getPosition())
					.thenApply(result -> {
						if (!isEmptyDefinitionResult(result)) {
							return result;
						}
						Either<List<? extends Location>, List<? extends LocationLink>> fallback =
								resolveDefinitionFromJavaVirtualSource(uri, params.getPosition(),
										scope.getJavaSourceLocator());
						if (!isEmptyDefinitionResult(fallback)) {
							logger.debug("Java virtual-source fallback resolved definition for {}", uri);
							return fallback;
						}
						return result;
					});
		}, Either.forLeft(Collections.emptyList()));
	}

	private boolean isEmptyDefinitionResult(Either<List<? extends Location>, List<? extends LocationLink>> result) {
		if (result == null) {
			return true;
		}
		if (result.isLeft()) {
			List<? extends Location> left = result.getLeft();
			return left == null || left.isEmpty();
		}
		List<? extends LocationLink> right = result.getRight();
		return right == null || right.isEmpty();
	}

	private Either<List<? extends Location>, List<? extends LocationLink>> resolveDefinitionFromJavaVirtualSource(
			URI uri,
			Position position,
			JavaSourceLocator javaSourceLocator) {
		if (uri == null || position == null || javaSourceLocator == null || !isLikelyJavaVirtualUri(uri)) {
			return Either.forLeft(Collections.emptyList());
		}

		String contents = fileContentsTracker.getContents(uri);
		if (contents == null || contents.isBlank()) {
			return Either.forLeft(Collections.emptyList());
		}

		String symbol = symbolAtPosition(contents, position);
		if (symbol == null || symbol.isBlank()) {
			return Either.forLeft(Collections.emptyList());
		}

		List<String> candidates = javaSourceLocator.findClassNamesBySimpleName(symbol);
		if (candidates == null || candidates.isEmpty()) {
			return Either.forLeft(Collections.emptyList());
		}

		String preferred = choosePreferredCandidate(symbol, contents, candidates);
		if (preferred != null) {
			Location loc = javaSourceLocator.findLocationForClass(preferred);
			if (loc != null) {
				return Either.forLeft(Collections.singletonList(loc));
			}
		}

		for (String candidate : candidates) {
			Location loc = javaSourceLocator.findLocationForClass(candidate);
			if (loc != null) {
				return Either.forLeft(Collections.singletonList(loc));
			}
		}

		return Either.forLeft(Collections.emptyList());
	}

	private boolean isLikelyJavaVirtualUri(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null) {
			return false;
		}
		String normalized = scheme.toLowerCase();
		if (!"jar".equals(normalized) && !"jrt".equals(normalized) && !"decompiled".equals(normalized)) {
			return false;
		}
		String asText = uri.toString().toLowerCase();
		return asText.endsWith(".java") || asText.contains(".java!");
	}

	private String symbolAtPosition(String contents, Position position) {
		String[] lines = contents.split("\\R", -1);
		if (position.getLine() < 0 || position.getLine() >= lines.length) {
			return null;
		}
		String line = lines[position.getLine()];
		if (line.isEmpty()) {
			return null;
		}

		int character = Math.max(0, Math.min(position.getCharacter(), line.length()));
		int index = character;
		if (index >= line.length()) {
			index = line.length() - 1;
		}
		if (index < 0) {
			return null;
		}

		if (!Character.isJavaIdentifierPart(line.charAt(index)) && index > 0
				&& Character.isJavaIdentifierPart(line.charAt(index - 1))) {
			index--;
		}
		if (!Character.isJavaIdentifierPart(line.charAt(index))) {
			return null;
		}

		int start = index;
		while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
			start--;
		}
		int end = index + 1;
		while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
			end++;
		}
		return line.substring(start, end);
	}

	private String choosePreferredCandidate(String simpleName, String contents, List<String> candidates) {
		String imported = importedTypeForSimpleName(simpleName, contents);
		if (imported != null && candidates.contains(imported)) {
			return imported;
		}

		String packageName = packageName(contents);
		if (packageName != null && !packageName.isBlank()) {
			List<String> samePackage = new ArrayList<>();
			for (String candidate : candidates) {
				if (candidate.startsWith(packageName + ".")) {
					samePackage.add(candidate);
				}
			}
			if (samePackage.size() == 1) {
				return samePackage.get(0);
			}
		}

		if (candidates.size() == 1) {
			return candidates.get(0);
		}

		return null;
	}

	private String importedTypeForSimpleName(String simpleName, String contents) {
		for (String line : contents.split("\\R", -1)) {
			Matcher matcher = JAVA_IMPORT_PATTERN.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			String imported = matcher.group(1);
			if (imported == null || imported.endsWith(".*")) {
				continue;
			}
			int lastDot = imported.lastIndexOf('.');
			if (lastDot >= 0 && imported.substring(lastDot + 1).equals(simpleName)) {
				return imported;
			}
		}
		return null;
	}

	private String packageName(String contents) {
		for (String line : contents.split("\\R", -1)) {
			Matcher matcher = JAVA_PACKAGE_PATTERN.matcher(line);
			if (matcher.matches()) {
				return matcher.group(1);
			}
		}
		return null;
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

			TypeDefinitionProvider provider = new TypeDefinitionProvider(visitor, scope.getJavaSourceLocator());
			return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
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

			ImplementationProvider provider = new ImplementationProvider(visitor);
			return provider.provideImplementation(params.getTextDocument(), params.getPosition());
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

		DocumentHighlightProvider provider = new DocumentHighlightProvider(visitor);
		return provider.provideDocumentHighlights(params.getTextDocument(), params.getPosition());
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

			ReferenceProvider provider = new ReferenceProvider(visitor);
			return provider.provideReferences(params.getTextDocument(), params.getPosition());
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

			DocumentSymbolProvider provider = new DocumentSymbolProvider(visitor);
			return provider.provideDocumentSymbols(params.getTextDocument());
		}, Collections.emptyList());
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			WorkspaceSymbolParams params) {
		List<ProjectScope> scopesToSearch;
		List<ProjectScope> allScopes = scopeManager.getAllScopes();
		if (allScopes.size() <= 1) {
			scopesToSearch = allScopes;
		} else {
			Set<ProjectScope> openScopes = new LinkedHashSet<>();
			for (URI openUri : fileContentsTracker.getOpenURIs()) {
				ProjectScope openScope = scopeManager.findProjectScope(openUri);
				if (openScope != null) {
					openScopes.add(openScope);
				}
			}
			if (openScopes.isEmpty()) {
				return CompletableFuture.completedFuture(Either.forRight(Collections.emptyList()));
			}
			scopesToSearch = new ArrayList<>(openScopes);
		}

		for (ProjectScope scope : scopesToSearch) {
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

		List<CompletableFuture<List<? extends WorkspaceSymbol>>> futures = new ArrayList<>();
		for (ProjectScope scope : scopesToSearch) {
			ASTNodeVisitor visitor = scope.getAstVisitor();
			if (visitor != null) {
				WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(visitor);
				futures.add(provider.provideWorkspaceSymbols(params.getQuery()));
			}
		}
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
				.thenApply(v -> {
					List<WorkspaceSymbol> allSymbols = new ArrayList<>();
					for (CompletableFuture<List<? extends WorkspaceSymbol>> f : futures) {
						allSymbols.addAll(f.join());
					}
					return Either.forRight(allSymbols);
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
		ClasspathSymbolIndex classpathSymbolIndex =
				scope != null ? scope.ensureClasspathSymbolIndex() : null;
		java.util.Set<String> classpathSymbolClasspathElements =
				scope != null ? scope.getClasspathSymbolClasspathElements() : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		CodeActionProvider provider = new CodeActionProvider(visitor, classpathSymbolIndex,
				classpathSymbolClasspathElements, fileContentsTracker,
				scope != null ? scope.getJavaSourceLocator() : null);
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
		return failSoftRequest("inlayHint", uri, () -> {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			InlayHintProvider provider = new InlayHintProvider(visitor);
			return provider.provideInlayHints(params);
		}, Collections.emptyList());
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		if (!scopeManager.isSemanticHighlightingEnabled()) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		try {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				logger.debug("semanticTokensFull uri={} projectRoot={} visitorUnavailable=true", uri,
						scope != null ? scope.getProjectRoot() : null);
				SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
				if (fallback != null) {
					return CompletableFuture.completedFuture(fallback);
				}
				return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
			}

			SemanticTokensProvider provider = createSemanticTokensProvider(visitor, scope);
			Path projectRoot = scope.getProjectRoot();
			return provider.provideSemanticTokensFull(params.getTextDocument())
					.handle((tokens, throwable) -> {
						if (throwable != null) {
							logger.warn(
									"semanticTokensFull failed uri={} projectRoot={} error={}",
									uri, projectRoot, throwable.toString());
							logger.debug("semanticTokensFull failure details", throwable);
							SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
							return fallback != null ? fallback : new SemanticTokens(Collections.emptyList());
						}
						if (tokens != null && tokens.getData() != null && !tokens.getData().isEmpty()) {
							lastSemanticTokensByUri.put(uri, tokens);
							return tokens;
						}
						SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
						if (fallback != null) {
							logger.debug("semanticTokensFull uri={} projectRoot={} usingFallback=true", uri,
									projectRoot);
							return fallback;
						}
						return tokens != null ? tokens : new SemanticTokens(Collections.emptyList());
					});
		} catch (LinkageError e) {
			ProjectScope scope = scopeManager.findProjectScope(uri);
			logger.warn("semanticTokensFull linkage error uri={} projectRoot={} error={}", uri,
					scope != null ? scope.getProjectRoot() : null, e.toString());
			logger.debug("semanticTokensFull linkage error details", e);
			SemanticTokens fallback = lastSemanticTokensByUri.get(uri);
			return CompletableFuture.completedFuture(
					fallback != null ? fallback : new SemanticTokens(Collections.emptyList()));
		}
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
		if (!scopeManager.isSemanticHighlightingEnabled()) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		try {
			ProjectScope scope = ensureCompiledForContext(uri);
			ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
			if (visitor == null) {
				logger.debug("semanticTokensRange uri={} projectRoot={} visitorUnavailable=true", uri,
						scope != null ? scope.getProjectRoot() : null);
				return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
			}

			SemanticTokensProvider provider = createSemanticTokensProvider(visitor, scope);
			Path projectRoot = scope.getProjectRoot();
			return provider.provideSemanticTokensRange(params.getTextDocument(), params.getRange())
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
		if (sourceText == null || sourceText.isEmpty()) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		String normalizedSource = normalizeLineEndings(sourceText);
		String textToFormat = normalizedSource;

		if (scopeManager.isFormattingOrganizeImportsEnabled()) {
			try {
				String organizedText = applyOrganizeImportsForFormatting(uri, textToFormat);
				if (organizedText != null) {
					textToFormat = organizedText;
				}
			} catch (Exception e) {
				logger.debug("formatting organize imports skipped uri={} reason={}", uri, e.toString());
			}
		}

		FormattingProvider provider = new FormattingProvider();
		final String textForFormatting = textToFormat;
		return provider.provideFormatting(params, textForFormatting)
				.thenApply(formattingEdits -> {
					String formattedText = applyTextEdits(textForFormatting, formattingEdits);
					formattedText = normalizeBlankLinesAfterLastImport(formattedText);
					if (formattedText.equals(normalizedSource)) {
						return Collections.<TextEdit>emptyList();
					}
					return Collections.singletonList(new TextEdit(
							new Range(new Position(0, 0), documentEndPosition(normalizedSource)),
							formattedText));
				});
	}

	private static Position documentEndPosition(String text) {
		String[] lines = text.split("\\n", -1);
		return new Position(lines.length - 1, lines[lines.length - 1].length());
	}

	private static String normalizeBlankLinesAfterLastImport(String text) {
		String[] lines = text.split("\\n", -1);
		int lastImportLine = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("import ")) {
				lastImportLine = i;
			}
		}
		if (lastImportLine < 0 || lastImportLine + 1 >= lines.length) {
			return text;
		}

		int afterImportStart = lastImportLine + 1;
		int firstNonBlank = afterImportStart;
		while (firstNonBlank < lines.length && lines[firstNonBlank].trim().isEmpty()) {
			firstNonBlank++;
		}

		int blankCount = firstNonBlank - afterImportStart;
		if (blankCount <= 1) {
			return text;
		}

		StringBuilder normalized = new StringBuilder();
		for (int i = 0; i <= lastImportLine; i++) {
			normalized.append(lines[i]).append("\n");
		}
		normalized.append("\n");
		for (int i = firstNonBlank; i < lines.length; i++) {
			normalized.append(lines[i]);
			if (i < lines.length - 1) {
				normalized.append("\n");
			}
		}
		return normalized.toString();
	}

	private String applyOrganizeImportsForFormatting(URI uri, String sourceText) {
		ProjectScope scope = ensureCompiledForContext(uri);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return sourceText;
		}

		OrganizeImportsAction organizeImportsAction = new OrganizeImportsAction(visitor);
		TextEdit importEdit = organizeImportsAction.createOrganizeImportsTextEdit(uri);
		if (importEdit == null) {
			return sourceText;
		}
		importEdit = normalizeImportEditForExistingBlankLine(sourceText, importEdit);

		return applyTextEdits(sourceText, Collections.singletonList(importEdit));
	}

	private TextEdit normalizeImportEditForExistingBlankLine(String sourceText, TextEdit importEdit) {
		String newText = importEdit.getNewText();
		if (newText == null) {
			return importEdit;
		}

		String normalizedNewText = newText;
		if (!normalizedNewText.endsWith("\n\n")) {
			normalizedNewText = normalizedNewText + "\n\n";
		}

		int startOffset = positionToOffset(sourceText, importEdit.getRange().getEnd());
		int endOffset = startOffset;
		while (endOffset < sourceText.length() && sourceText.charAt(endOffset) == '\n') {
			endOffset++;
		}

		if (endOffset == startOffset && normalizedNewText.equals(importEdit.getNewText())) {
			return importEdit;
		}

		Range normalizedRange = new Range(
				importEdit.getRange().getStart(),
				offsetToPosition(sourceText, endOffset));
		return new TextEdit(normalizedRange, normalizedNewText);
	}

	private static Position offsetToPosition(String text, int offset) {
		int clampedOffset = Math.max(0, Math.min(offset, text.length()));
		int line = 0;
		int lineStartOffset = 0;
		for (int i = 0; i < clampedOffset; i++) {
			if (text.charAt(i) == '\n') {
				line++;
				lineStartOffset = i + 1;
			}
		}
		return new Position(line, clampedOffset - lineStartOffset);
	}

	private static String normalizeLineEndings(String text) {
		return text.replace("\r\n", "\n").replace("\r", "\n");
	}

	private static String applyTextEdits(String original, List<? extends TextEdit> edits) {
		if (edits == null || edits.isEmpty()) {
			return original;
		}

		String text = original;
		List<? extends TextEdit> sorted = new ArrayList<>(edits);
		sorted.sort((a, b) -> {
			int lineCmp = Integer.compare(b.getRange().getStart().getLine(), a.getRange().getStart().getLine());
			if (lineCmp != 0) {
				return lineCmp;
			}
			return Integer.compare(b.getRange().getStart().getCharacter(), a.getRange().getStart().getCharacter());
		});

		for (TextEdit edit : sorted) {
			Range range = edit.getRange();
			int startOffset = positionToOffset(text, range.getStart());
			int endOffset = positionToOffset(text, range.getEnd());
			text = text.substring(0, startOffset) + edit.getNewText() + text.substring(endOffset);
		}

		return text;
	}

	private static int positionToOffset(String text, Position pos) {
		int line = 0;
		int offset = 0;
		while (line < pos.getLine() && offset < text.length()) {
			if (text.charAt(offset) == '\n') {
				line++;
			}
			offset++;
		}
		return offset + pos.getCharacter();
	}

	private static String preview(String text, int maxLen) {
		if (text == null) {
			return "<null>";
		}
		String normalized = text
				.replace("\r", "\\r")
				.replace("\n", "\\n")
				.replace("\t", "\\t");
		if (normalized.length() <= maxLen) {
			return normalized;
		}
		return normalized.substring(0, maxLen) + "...";
	}

	private static List<String> collectImportLines(String text, int maxLines) {
		List<String> result = new ArrayList<>();
		String[] lines = text.split("\\R", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.contains("import ")) {
				result.add((i + 1) + ":" + preview(line, 200));
				if (result.size() >= maxLines) {
					break;
				}
			}
		}
		return result;
	}
}
