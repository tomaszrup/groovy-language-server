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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.control.ErrorCollector;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.CompilationOrchestrator;
import com.tomaszrup.groovyls.compiler.DiagnosticHandler;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GroovyServices implements TextDocumentService, WorkspaceService, LanguageClientAware {
	private static final Logger logger = LoggerFactory.getLogger(GroovyServices.class);

	/** Debounce delay for didChange recompilation (milliseconds). */
	private static final long DEBOUNCE_DELAY_MS = 300;

	/**
	 * Holds all per-project state: compilation unit, AST visitor, classpath, etc.
	 * Each build-tool project (Gradle/Maven) in the workspace gets its own scope
	 * so that classpaths don't leak between independent projects.
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

	private final CompilationOrchestrator compilationOrchestrator = new CompilationOrchestrator();
	private final DiagnosticHandler diagnosticHandler = new DiagnosticHandler();

	// Debounce scheduler for didChange recompilation
	private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "groovyls-debounce");
		t.setDaemon(true);
		return t;
	});
	private volatile ScheduledFuture<?> pendingDebounce;

	// Default scope (used when no build-tool projects are registered, e.g. in tests)
	private ProjectScope defaultScope;
	// Per-project scopes, sorted by path length desc for longest-prefix match
	private List<ProjectScope> projectScopes = new ArrayList<>();
	// True while a background project import (Gradle/Maven) is in progress.
	// When true, didOpen/didChange/didClose skip compilation on the defaultScope
	// to avoid wrong diagnostics (no classpath, entire workspace scanned).
	private volatile boolean importInProgress;

	/**
	 * Guards all mutable state: projectScopes, defaultScope, fileContentsTracker,
	 * astVisitor, compilationUnit, etc.
	 *
	 * <p><b>Write-lock</b> is acquired for operations that mutate state
	 * (didOpen/didChange/didClose, compilation, AST visits, and the
	 * placeholder-injection paths in {@code completion}/{@code signatureHelp}).
	 * Placeholder injection uses direct {@link FileContentsTracker#setContents}
	 * manipulation rather than the {@code didChangeInternal} pipeline, which
	 * ensures the document is always restored even if recompilation fails.</p>
	 *
	 * <p><b>Read-lock</b> is acquired for LSP request handlers that only
	 * query the AST (hover, definition, references, etc.).  These handlers
	 * call {@link #ensureCompiledForContext(URI)} first, which briefly
	 * upgrades to a write-lock <em>only</em> when the edit context has
	 * actually changed and a recompilation is needed.  The upgrade
	 * re-validates the scope after acquiring the write-lock to guard
	 * against races in the lock-release window.</p>
	 *
	 * <p>This design allows multiple read-only requests to execute
	 * concurrently while still serialising mutations.</p>
	 */
	private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

	public GroovyServices(ICompilationUnitFactory factory) {
		defaultScope = new ProjectScope(null, factory);
	}

	/**
	 * Called by the language server to indicate that a background project
	 * import (Gradle/Maven) is starting or has finished.
	 */
	public void setImportInProgress(boolean inProgress) {
		this.importInProgress = inProgress;
	}

	/**
	 * Called after the background project import completes (whether or not
	 * any projects were found). If no build-tool projects were discovered,
	 * compiles all open files with the default scope so the user gets
	 * diagnostics.
	 */
	public void onImportComplete() {
		stateLock.writeLock().lock();
		try {
			importInProgress = false;
			if (projectScopes.isEmpty()) {
				// No build-tool projects found — compile open files with defaultScope
				Set<URI> openURIs = fileContentsTracker.getOpenURIs();
				for (URI uri : openURIs) {
					compileAndVisitAST(defaultScope, uri);
				}
				if (languageClient != null) {
					languageClient.refreshSemanticTokens();
				}
			}
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		stateLock.writeLock().lock();
		try {
			this.workspaceRoot = workspaceRoot;
			defaultScope.projectRoot = workspaceRoot;
			defaultScope.javaSourceLocator = new JavaSourceLocator();
			if (workspaceRoot != null) {
				defaultScope.javaSourceLocator.addProjectRoot(workspaceRoot);
			}
			createOrUpdateCompilationUnit(defaultScope);
			fileContentsTracker.resetChangedFiles();
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	/**
	 * Register all build-tool projects at once with their resolved classpaths.
	 * This allows computing proper subproject exclusions so that parent projects
	 * don't scan source files belonging to nested subprojects.
	 *
	 * <p>Groovy compilation for each scope is performed in parallel to reduce
	 * startup time in large workspaces with many projects.</p>
	 */
	public void addProjects(Map<Path, List<String>> projectClasspaths) {
		stateLock.writeLock().lock();
		try {
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

			// Clear any stale diagnostics from the default scope that may have
			// been published before the import completed (e.g. from an early
			// didChangeConfiguration/updateClasspath call).
			clearDefaultScopeDiagnostics();

			// Reset changed files once before compiling all scopes
			fileContentsTracker.resetChangedFiles();

			// Create compilation units for every scope first (sequential —
			// each factory walks its own project root for Groovy files)
			for (ProjectScope scope : projectScopes) {
				createOrUpdateCompilationUnit(scope);
			}

			// Compile and visit AST for each scope in parallel —
			// each scope has its own CompilationUnit and ASTVisitor
			if (projectScopes.size() > 1) {
				logger.info("Compiling {} project scopes in parallel", projectScopes.size());
				int parallelism = Math.min(projectScopes.size(),
						Math.max(2, Runtime.getRuntime().availableProcessors()));
				java.util.concurrent.ExecutorService compilePool =
						java.util.concurrent.Executors.newFixedThreadPool(parallelism, r -> {
							Thread t = new Thread(r, "groovyls-compile");
							t.setDaemon(true);
							return t;
						});
				try {
					List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
					for (ProjectScope scope : projectScopes) {
						futures.add(compilePool.submit(() -> {
							try {
								compile(scope);
								visitAST(scope);
							} catch (Exception e) {
								logger.error("Error compiling scope {}: {}",
										scope.projectRoot, e.getMessage(), e);
							}
						}));
					}
					for (java.util.concurrent.Future<?> f : futures) {
						try {
							f.get();
						} catch (java.util.concurrent.ExecutionException e) {
							logger.error("Compile task failed: {}", e.getCause().getMessage());
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							break;
						}
					}
				} finally {
					compilePool.shutdownNow();
				}
			} else {
				// Single scope — compile directly, no thread overhead
				for (ProjectScope scope : projectScopes) {
					compile(scope);
					visitAST(scope);
				}
			}

			// Replay deferred didOpen compilations for files that were opened
			// while the import was in progress.  The project-wide compile above
			// already built the AST, but an individual compileAndVisitAST per
			// open file ensures previousContext is set and diagnostics are
			// published from the file's buffer (which may differ from disk).
			Set<URI> openURIs = fileContentsTracker.getOpenURIs();
			for (URI uri : openURIs) {
				ProjectScope scope = findProjectScope(uri);
				if (scope != null) {
					compileAndVisitAST(scope, uri);
				}
			}

			// Ask the client to re-request semantic tokens for open editors.
			// Semantic tokens are pull-based so the client won't refresh them
			// unless told to, and the initial request during import returned
			// empty tokens because the AST wasn't ready yet.
			if (languageClient != null) {
				languageClient.refreshSemanticTokens();
			}
		} finally {
			stateLock.writeLock().unlock();
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
		stateLock.writeLock().lock();
		try {
			fileContentsTracker.didOpen(params);
			URI uri = URI.create(params.getTextDocument().getUri());
			ProjectScope scope = findProjectScope(uri);
			if (scope != null && !isImportPendingFor(scope)) {
				compileAndVisitAST(scope, uri);
			}
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		// Always update the file contents immediately so the tracker is current
		stateLock.writeLock().lock();
		try {
			fileContentsTracker.didChange(params);
		} finally {
			stateLock.writeLock().unlock();
		}

		// Cancel any pending debounce and schedule a new recompilation
		ScheduledFuture<?> prev = pendingDebounce;
		if (prev != null) {
			prev.cancel(false);
		}
		pendingDebounce = debounceExecutor.schedule(() -> {
			stateLock.writeLock().lock();
			try {
				URI uri = URI.create(params.getTextDocument().getUri());
				ProjectScope scope = findProjectScope(uri);
				if (scope != null && !isImportPendingFor(scope)) {
					compileAndVisitAST(scope, uri);
				}
			} finally {
				stateLock.writeLock().unlock();
			}
		}, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		stateLock.writeLock().lock();
		try {
			fileContentsTracker.didClose(params);
			URI uri = URI.create(params.getTextDocument().getUri());
			ProjectScope scope = findProjectScope(uri);
			if (scope != null && !isImportPendingFor(scope)) {
				compileAndVisitAST(scope, uri);
			}
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		// nothing to handle on save at this time
	}

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

	private JavaChangeListener javaChangeListener;
	private SettingsChangeListener settingsChangeListener;

	public void setJavaChangeListener(JavaChangeListener listener) {
		this.javaChangeListener = listener;
	}

	public void setSettingsChangeListener(SettingsChangeListener listener) {
		this.settingsChangeListener = listener;
	}

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		stateLock.writeLock().lock();
		try {
			Set<URI> allChangedUris = params.getChanges().stream()
					.map(fileEvent -> URI.create(fileEvent.getUri()))
					.collect(Collectors.toSet());

			// Detect Java/build-tool file changes that require recompilation
			Set<Path> projectsNeedingRecompile = new LinkedHashSet<>();
			for (URI changedUri : allChangedUris) {
				String path = changedUri.getPath();
				if (path != null && (path.endsWith(".java")
						|| path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")
						|| path.endsWith("pom.xml"))) {
					Path filePath = Paths.get(changedUri);
					for (ProjectScope scope : projectScopes) {
						if (scope.projectRoot != null && filePath.startsWith(scope.projectRoot)) {
							projectsNeedingRecompile.add(scope.projectRoot);
							break;
						}
					}
				}
			}

			// Trigger recompile for projects with Java/build-tool file changes
			if (!projectsNeedingRecompile.isEmpty() && javaChangeListener != null) {
				for (Path projectRoot : projectsNeedingRecompile) {
					logger.info("Java/build files changed in {}, triggering recompile", projectRoot);
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
					// Invalidate file cache so that new/deleted .groovy files are picked up
					scope.compilationUnitFactory.invalidateFileCache();

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
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		stateLock.writeLock().lock();
		try {
			if (!(params.getSettings() instanceof JsonObject)) {
				return;
			}
			JsonObject settings = (JsonObject) params.getSettings();
			this.updateClasspath(settings);
			this.updateFeatureToggles(settings);
			if (settingsChangeListener != null) {
				settingsChangeListener.onSettingsChanged(settings);
			}
		} finally {
			stateLock.writeLock().unlock();
		}
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
		stateLock.writeLock().lock();
		try {
			// When project scopes are registered (via Gradle import), they handle
			// their own classpaths. The default scope should not override them.
			if (!projectScopes.isEmpty()) {
				logger.info("updateClasspath() ignored — {} project scope(s) are active", projectScopes.size());
				return;
			}
			// Don't compile the default scope while a background import is in
			// progress — it would scan the entire workspace with no classpath,
			// causing duplicate-class and unresolved-import errors.
			// Still store the classpath so it's available when the import
			// finishes and onImportComplete() compiles the default scope.
			if (importInProgress) {
				logger.info("updateClasspath() deferred — project import in progress");
				defaultScope.compilationUnitFactory.setAdditionalClasspathList(classpathList);
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
		} finally {
			stateLock.writeLock().unlock();
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
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(null);
			}

			HoverProvider provider = new HoverProvider(scope.astVisitor);
			return provider.provideHover(params.getTextDocument(), params.getPosition());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		stateLock.writeLock().lock();
		try {
			TextDocumentIdentifier textDocument = params.getTextDocument();
			Position position = params.getPosition();
			URI uri = URI.create(textDocument.getUri());

			ProjectScope scope = findProjectScope(uri);
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
			}
			// Flush any pending debounced changes before computing completions
			if (!fileContentsTracker.getChangedURIs().isEmpty()) {
				compileAndVisitAST(scope, uri);
			} else {
				recompileIfContextChanged(scope, uri);
			}

			String originalSource = null;
			ASTNode offsetNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
					position.getCharacter());
			if (offsetNode == null) {
				originalSource = injectCompletionPlaceholder(scope, uri, position);
			}

			CompletableFuture<Either<List<CompletionItem>, CompletionList>> result;
			try {
				CompletionProvider provider = new CompletionProvider(scope.astVisitor, scope.classGraphScanResult);
				result = provider.provideCompletion(params.getTextDocument(), params.getPosition(),
						params.getContext());

				// Add Spock-specific completions
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
					restoreDocumentSource(scope, uri, originalSource);
				}
			}

			return result;
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			DefinitionProvider provider = new DefinitionProvider(scope.astVisitor, scope.javaSourceLocator);
			return provider.provideDefinition(params.getTextDocument(), params.getPosition());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
		stateLock.writeLock().lock();
		try {
			TextDocumentIdentifier textDocument = params.getTextDocument();
			Position position = params.getPosition();
			URI uri = URI.create(textDocument.getUri());

			ProjectScope scope = findProjectScope(uri);
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(new SignatureHelp());
			}
			// Flush any pending debounced changes before computing signature help
			if (!fileContentsTracker.getChangedURIs().isEmpty()) {
				compileAndVisitAST(scope, uri);
			} else {
				recompileIfContextChanged(scope, uri);
			}

			String originalSource = null;
			ASTNode offsetNode = scope.astVisitor.getNodeAtLineAndColumn(uri, position.getLine(),
					position.getCharacter());
			if (offsetNode == null) {
				originalSource = injectSignatureHelpPlaceholder(scope, uri, position);
			}

			try {
				SignatureHelpProvider provider = new SignatureHelpProvider(scope.astVisitor);
				return provider.provideSignatureHelp(params.getTextDocument(), params.getPosition());
			} finally {
				if (originalSource != null) {
					restoreDocumentSource(scope, uri, originalSource);
				}
			}
		} finally {
			stateLock.writeLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			TypeDefinitionProvider provider = new TypeDefinitionProvider(scope.astVisitor, scope.javaSourceLocator);
			return provider.provideTypeDefinition(params.getTextDocument(), params.getPosition());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
			ImplementationParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
			}

			ImplementationProvider provider = new ImplementationProvider(scope.astVisitor);
			return provider.provideImplementation(params.getTextDocument(), params.getPosition());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			DocumentHighlightProvider provider = new DocumentHighlightProvider(scope.astVisitor);
			return provider.provideDocumentHighlights(params.getTextDocument(), params.getPosition());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			ReferenceProvider provider = new ReferenceProvider(scope.astVisitor);
			return provider.provideReferences(params.getTextDocument(), params.getPosition());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
			DocumentSymbolParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			DocumentSymbolProvider provider = new DocumentSymbolProvider(scope.astVisitor);
			return provider.provideDocumentSymbols(params.getTextDocument());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
			WorkspaceSymbolParams params) {
		stateLock.readLock().lock();
		try {
			// Workspace symbols aggregate across all project scopes
			List<SymbolInformation> allSymbols = new ArrayList<>();
			for (ProjectScope scope : getAllScopes()) {
				if (scope.astVisitor != null) {
					WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(scope.astVisitor);
					List<? extends SymbolInformation> symbols = provider.provideWorkspaceSymbols(params.getQuery())
							.join();
					allSymbols.addAll(symbols);
				}
			}
			return CompletableFuture.completedFuture(Either.forLeft(allSymbols));
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
			PrepareRenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(null);
			}

			RenameProvider provider = new RenameProvider(scope.astVisitor, fileContentsTracker);
			return provider.providePrepareRename(params);
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(new WorkspaceEdit());
			}

			RenameProvider provider = new RenameProvider(scope.astVisitor, fileContentsTracker);
			return provider.provideRename(params);
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

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
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);

		stateLock.readLock().lock();
		try {
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}

			InlayHintProvider provider = new InlayHintProvider(scope.astVisitor);
			return provider.provideInlayHints(params);
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		stateLock.readLock().lock();
		try {
			if (!semanticHighlightingEnabled) {
				return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
			}
			URI uri = URI.create(params.getTextDocument().getUri());
			ProjectScope scope = findProjectScope(uri);
			if (scope == null || scope.astVisitor == null) {
				return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
			}

			SemanticTokensProvider provider = new SemanticTokensProvider(scope.astVisitor, fileContentsTracker);
			return provider.provideSemanticTokensFull(params.getTextDocument());
		} finally {
			stateLock.readLock().unlock();
		}
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
		stateLock.readLock().lock();
		try {
			if (unresolved.getDocumentation() != null) {
				// Already resolved
				return CompletableFuture.completedFuture(unresolved);
			}
			String label = unresolved.getLabel();
			CompletionItemKind kind = unresolved.getKind();
			if (label == null || kind == null) {
				return CompletableFuture.completedFuture(unresolved);
			}

			// Extract signature info from CompletionItem.data if available
			String signature = null;
			String declaringClassName = null;
			Object data = unresolved.getData();
			if (data instanceof com.google.gson.JsonObject) {
				com.google.gson.JsonObject jsonData = (com.google.gson.JsonObject) data;
				if (jsonData.has("signature")) {
					signature = jsonData.get("signature").getAsString();
				}
				if (jsonData.has("declaringClass")) {
					declaringClassName = jsonData.get("declaringClass").getAsString();
				}
			} else if (data instanceof com.google.gson.JsonElement) {
				try {
					com.google.gson.JsonObject jsonData = ((com.google.gson.JsonElement) data).getAsJsonObject();
					if (jsonData.has("signature")) {
						signature = jsonData.get("signature").getAsString();
					}
					if (jsonData.has("declaringClass")) {
						declaringClassName = jsonData.get("declaringClass").getAsString();
					}
				} catch (Exception e) {
					// not a JSON object, ignore
				}
			}

			// Search all scopes for matching AST node to retrieve documentation
			for (ProjectScope scope : getAllScopes()) {
				if (scope.astVisitor == null) {
					continue;
				}
				String docs = resolveDocumentation(scope, label, kind, signature, declaringClassName);
				if (docs != null) {
					unresolved.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, docs));
					break;
				}
			}

			// If still no documentation, try to load Javadoc from classpath source JARs
			if (unresolved.getDocumentation() == null && declaringClassName != null) {
				for (ProjectScope scope : getAllScopes()) {
					String javadoc = resolveJavadocFromClasspath(scope, label, kind, signature, declaringClassName);
					if (javadoc != null) {
						unresolved.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, javadoc));
						break;
					}
				}
			}

			return CompletableFuture.completedFuture(unresolved);
		} finally {
			stateLock.readLock().unlock();
		}
	}

	/**
	 * Build a parameter signature string from a MethodNode for comparison.
	 * Format: comma-separated fully qualified type names, e.g. "java.lang.String,int"
	 */
	private static String buildMethodSignature(org.codehaus.groovy.ast.MethodNode method) {
		org.codehaus.groovy.ast.Parameter[] params = method.getParameters();
		StringBuilder sig = new StringBuilder();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) {
				sig.append(",");
			}
			sig.append(params[i].getType().getName());
		}
		return sig.toString();
	}

	private String resolveDocumentation(ProjectScope scope, String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		if (kind == CompletionItemKind.Method) {
			// When declaringClassName is available, look up only that class (O(1) lookup
			// via the AST visitor's class node map) instead of scanning all classes.
			if (declaringClassName != null) {
				org.codehaus.groovy.ast.ClassNode classNode = scope.astVisitor.getClassNodeByName(declaringClassName);
				if (classNode != null) {
					String docs = findMethodDocs(classNode, label, signature);
					if (docs != null) {
						return docs;
					}
				}
			}
			// Fallback: scan all class nodes
			for (org.codehaus.groovy.ast.ClassNode classNode : scope.astVisitor.getClassNodes()) {
				String docs = findMethodDocs(classNode, label, signature);
				if (docs != null) {
					return docs;
				}
			}
		} else if (kind == CompletionItemKind.Property || kind == CompletionItemKind.Field) {
			if (declaringClassName != null) {
				org.codehaus.groovy.ast.ClassNode classNode = scope.astVisitor.getClassNodeByName(declaringClassName);
				if (classNode != null) {
					String docs = findFieldOrPropertyDocs(classNode, label);
					if (docs != null) {
						return docs;
					}
				}
			}
			for (org.codehaus.groovy.ast.ClassNode classNode : scope.astVisitor.getClassNodes()) {
				String docs = findFieldOrPropertyDocs(classNode, label);
				if (docs != null) {
					return docs;
				}
			}
		} else if (kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
				|| kind == CompletionItemKind.Enum) {
			// Direct name lookup first
			org.codehaus.groovy.ast.ClassNode classNode = scope.astVisitor.getClassNodeByName(label);
			if (classNode != null) {
				String docs = com.tomaszrup.groovyls.compiler.util.GroovydocUtils
						.groovydocToMarkdownDescription(classNode.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
			// Fallback: scan by simple name
			for (org.codehaus.groovy.ast.ClassNode cn : scope.astVisitor.getClassNodes()) {
				if (cn.getNameWithoutPackage().equals(label) || cn.getName().equals(label)) {
					String docs = com.tomaszrup.groovyls.compiler.util.GroovydocUtils
							.groovydocToMarkdownDescription(cn.getGroovydoc());
					if (docs != null) {
						return docs;
					}
				}
			}
		}
		return null;
	}

	private static String findMethodDocs(org.codehaus.groovy.ast.ClassNode classNode,
			String label, String signature) {
		for (org.codehaus.groovy.ast.MethodNode method : classNode.getMethods()) {
			if (!method.getName().equals(label)) {
				continue;
			}
			if (signature != null) {
				String methodSig = buildMethodSignature(method);
				if (!methodSig.equals(signature)) {
					continue;
				}
			}
			String docs = com.tomaszrup.groovyls.compiler.util.GroovydocUtils
					.groovydocToMarkdownDescription(method.getGroovydoc());
			if (docs != null) {
				return docs;
			}
		}
		return null;
	}

	private static String findFieldOrPropertyDocs(org.codehaus.groovy.ast.ClassNode classNode, String label) {
		for (org.codehaus.groovy.ast.PropertyNode prop : classNode.getProperties()) {
			if (prop.getName().equals(label)) {
				String docs = com.tomaszrup.groovyls.compiler.util.GroovydocUtils
						.groovydocToMarkdownDescription(prop.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
		}
		for (org.codehaus.groovy.ast.FieldNode field : classNode.getFields()) {
			if (field.getName().equals(label)) {
				String docs = com.tomaszrup.groovyls.compiler.util.GroovydocUtils
						.groovydocToMarkdownDescription(field.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
		}
		return null;
	}

	/**
	 * Attempt to resolve Javadoc documentation from classpath source JARs.
	 * For each JAR on the classpath, looks for a corresponding *-sources.jar
	 * in the same directory and extracts Javadoc for the specified class/method.
	 */
	private String resolveJavadocFromClasspath(ProjectScope scope, String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		if (scope.compilationUnit == null || declaringClassName == null) {
			return null;
		}
		List<String> classpathList = scope.compilationUnit.getConfiguration().getClasspath();
		if (classpathList == null) {
			return null;
		}
		for (String cpEntry : classpathList) {
			if (!cpEntry.endsWith(".jar")) {
				continue;
			}
			// Try to find a corresponding -sources.jar
			String sourcesJarPath = cpEntry.replaceAll("\\.jar$", "-sources.jar");
			Path sourcesJar = Paths.get(sourcesJarPath);
			if (!Files.exists(sourcesJar)) {
				continue;
			}
			String javadoc = JavadocResolver.resolveFromSourcesJar(sourcesJar, declaringClassName, label, kind,
					signature);
			if (javadoc != null) {
				return javadoc;
			}
		}
		return null;
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		stateLock.readLock().lock();
		try {
			if (!formattingEnabled) {
				return CompletableFuture.completedFuture(Collections.emptyList());
			}
			URI uri = URI.create(params.getTextDocument().getUri());
			String sourceText = fileContentsTracker.getContents(uri);
			FormattingProvider provider = new FormattingProvider();
			return provider.provideFormatting(params, sourceText);
		} finally {
			stateLock.readLock().unlock();
		}
	}

	// --- INTERNAL

	/**
	 * Returns true if a background import is in progress and the given scope
	 * is the default (workspace-wide) scope. Compilation should be skipped in
	 * this situation because the default scope has no classpath and scans the
	 * entire workspace, producing incorrect diagnostics.
	 */
	private boolean isImportPendingFor(ProjectScope scope) {
		return importInProgress && scope == defaultScope && projectScopes.isEmpty();
	}

	/**
	 * Publishes empty diagnostics for every file that defaultScope previously
	 * reported errors on, so the client clears them.
	 */
	private void clearDefaultScopeDiagnostics() {
		if (defaultScope.prevDiagnosticsByFile != null && languageClient != null) {
			for (URI uri : defaultScope.prevDiagnosticsByFile.keySet()) {
				languageClient.publishDiagnostics(
						new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
			}
			defaultScope.prevDiagnosticsByFile = null;
		}
	}

	private void visitAST(ProjectScope scope) {
		ASTNodeVisitor visitor = compilationOrchestrator.visitAST(scope.compilationUnit);
		if (visitor != null) {
			scope.astVisitor = visitor;
		}
	}

	private void visitAST(ProjectScope scope, Set<URI> uris) {
		scope.astVisitor = compilationOrchestrator.visitAST(
				scope.compilationUnit, scope.astVisitor, uris);
	}

	private boolean createOrUpdateCompilationUnit(ProjectScope scope) {
		GroovyLSCompilationUnit[] cuHolder = { scope.compilationUnit };
		ASTNodeVisitor[] avHolder = { scope.astVisitor };
		ScanResult[] srHolder = { scope.classGraphScanResult };
		GroovyClassLoader[] clHolder = { scope.classLoader };

		boolean result = compilationOrchestrator.createOrUpdateCompilationUnit(
				cuHolder, avHolder, scope.projectRoot,
				scope.compilationUnitFactory, fileContentsTracker,
				srHolder, clHolder);

		scope.compilationUnit = cuHolder[0];
		scope.classGraphScanResult = srHolder[0];
		scope.classLoader = clHolder[0];
		return result;
	}

	/**
	 * Ensures the project scope that owns {@code uri} has an up-to-date
	 * compilation for that context.  A brief <em>read-lock</em> check is
	 * performed first; the write-lock is only acquired when the context has
	 * actually changed and a recompilation is needed.
	 *
	 * <p>After upgrading to the write-lock, the scope is re-validated via
	 * {@link #findProjectScope(URI)} to guard against races during the
	 * window between releasing the read-lock and acquiring the write-lock
	 * (another thread may have invalidated the scope or already performed
	 * the recompilation).</p>
	 *
	 * <p><b>Important:</b> the caller must <em>not</em> hold any lock when
	 * calling this method.</p>
	 *
	 * @return the {@link ProjectScope} that owns the URI, or {@code null}
	 */
	private ProjectScope ensureCompiledForContext(URI uri) {
		stateLock.readLock().lock();
		ProjectScope scope;
		boolean needsRecompile;
		try {
			scope = findProjectScope(uri);
			if (scope == null || scope.astVisitor == null) {
				return scope;
			}
			// Recompile if the context changed OR there are pending changes
			// (e.g. from a debounced didChange that hasn't compiled yet)
			needsRecompile = (scope.previousContext != null && !scope.previousContext.equals(uri))
					|| !fileContentsTracker.getChangedURIs().isEmpty();
		} finally {
			stateLock.readLock().unlock();
		}

		if (needsRecompile) {
			stateLock.writeLock().lock();
			try {
				scope = findProjectScope(uri);
				if (scope == null || scope.astVisitor == null) {
					return scope;
				}
				// Re-check for pending changes under write lock
				if (!fileContentsTracker.getChangedURIs().isEmpty()) {
					compileAndVisitAST(scope, uri);
				} else if (scope.previousContext != null && !scope.previousContext.equals(uri)) {
					recompileIfContextChanged(scope, uri);
				}
			} finally {
				stateLock.writeLock().unlock();
			}
		}

		return scope;
	}

	protected void recompileIfContextChanged(ProjectScope scope, URI newContext) {
		if (scope.previousContext == null || scope.previousContext.equals(newContext)) {
			return;
		}
		// Only recompile if there are actual pending changes. Simply switching
		// to a different file should not trigger a full recompilation — the AST
		// is still valid from the previous compilation.
		if (!fileContentsTracker.getChangedURIs().isEmpty()) {
			compileAndVisitAST(scope, newContext);
		} else {
			// No changes pending — just update the context pointer
			scope.previousContext = newContext;
		}
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

	// --- PLACEHOLDER INJECTION HELPERS

	/**
	 * Injects a placeholder into the document source to force AST node creation
	 * at the cursor position for completion.
	 *
	 * <p><b>Caller must hold the write lock.</b></p>
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	private String injectCompletionPlaceholder(ProjectScope scope, URI uri, Position position) {
		String originalSource = compilationOrchestrator.injectCompletionPlaceholder(
				scope.astVisitor, fileContentsTracker, uri, position);
		if (originalSource != null) {
			compileAndVisitAST(scope, uri);
		}
		return originalSource;
	}

	/**
	 * Injects a closing parenthesis placeholder into the document source to force
	 * {@code ArgumentListExpression} creation in the AST for signature help.
	 *
	 * <p><b>Caller must hold the write lock.</b></p>
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	private String injectSignatureHelpPlaceholder(ProjectScope scope, URI uri, Position position) {
		String originalSource = compilationOrchestrator.injectSignatureHelpPlaceholder(
				fileContentsTracker, uri, position);
		if (originalSource != null) {
			compileAndVisitAST(scope, uri);
		}
		return originalSource;
	}

	/**
	 * Restores the original document source after placeholder injection.
	 *
	 * <p><b>Caller must hold the write lock.</b></p>
	 */
	private void restoreDocumentSource(ProjectScope scope, URI uri, String originalSource) {
		compilationOrchestrator.restoreDocumentSource(fileContentsTracker, uri, originalSource);
		try {
			compileAndVisitAST(scope, uri);
		} catch (Exception e) {
			logger.error("Failed to recompile after restoring document {}: {}", uri, e.getMessage());
		}
	}

	private void compile(ProjectScope scope) {
		ErrorCollector collector = compilationOrchestrator.compile(
				scope.compilationUnit, scope.projectRoot);
		if (collector != null) {
			DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
					scope.compilationUnit, collector, scope.projectRoot, scope.prevDiagnosticsByFile);
			scope.prevDiagnosticsByFile = result.getDiagnosticsByFile();
			result.getDiagnosticsToPublish().forEach(languageClient::publishDiagnostics);
		}
	}
}
