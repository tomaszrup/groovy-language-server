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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.ClassSignature;
import com.tomaszrup.groovyls.compiler.CompilationOrchestrator;
import com.tomaszrup.groovyls.compiler.DependencyGraph;
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

	/** Debounce delay for Java/build-file recompile triggers (milliseconds). */
	private static final long JAVA_RECOMPILE_DEBOUNCE_MS = 2000;

	/**
	 * Common build output directory names.  {@code .java} files under these
	 * directories (relative to a project root) are ignored when deciding
	 * whether to trigger a Java recompile, preventing feedback loops with
	 * annotation processors and source generators.
	 */
	private static final Set<String> BUILD_OUTPUT_DIRS = Set.of(
			"build", "target", ".gradle", "out", "bin");

	/**
	 * Maximum number of simultaneously changed files for which incremental
	 * compilation is attempted instead of falling back to full compilation.
	 */
	private static final int INCREMENTAL_MAX_CHANGED = 3;

	/**
	 * Maximum total source files (changed + dependencies) to include in an
	 * incremental compilation unit. If exceeded, falls back to full compile.
	 */
	private static final int INCREMENTAL_MAX_FILES = 50;

	/**
	 * Holds all per-project state: compilation unit, AST visitor, classpath, etc.
	 * Each build-tool project (Gradle/Maven) in the workspace gets its own scope
	 * so that classpaths don't leak between independent projects.
	 */
	static class ProjectScope {
		Path projectRoot;
		final ICompilationUnitFactory compilationUnitFactory;
		GroovyLSCompilationUnit compilationUnit;

		/**
		 * The latest AST visitor snapshot.  Published via volatile write after
		 * every successful compilation so that lock-free readers (hover,
		 * definition, etc.) always see a consistent, immutable snapshot.
		 * Writers produce a <em>new</em> visitor via copy-on-write rather than
		 * mutating this reference in place.
		 */
		volatile ASTNodeVisitor astVisitor;

		Map<URI, List<Diagnostic>> prevDiagnosticsByFile;

		/** Published via volatile write when the classloader changes. */
		volatile ScanResult classGraphScanResult;

		GroovyClassLoader classLoader;
		volatile URI previousContext;
		volatile JavaSourceLocator javaSourceLocator;
		final DependencyGraph dependencyGraph = new DependencyGraph();

		/**
		 * Per-project lock. Write-lock is acquired for compilation and AST
		 * mutation. Read-only LSP handlers no longer acquire a read-lock —
		 * they read the volatile {@link #astVisitor} reference directly
		 * (stale-AST reads). The read-lock is still used in a few places
		 * that need to coordinate with the write-lock (e.g. workspace
		 * symbols aggregation).
		 */
		final ReadWriteLock lock = new ReentrantReadWriteLock();

		/**
		 * Whether this scope has been compiled at least once. Used for
		 * deferred/lazy compilation: scopes registered via {@code addProjects}
		 * are not compiled eagerly — compilation is deferred until the first
		 * request that actually needs the AST.
		 */
		volatile boolean compiled = false;

		/**
		 * Whether this scope's classpath has been resolved (dependency JARs
		 * are known). Scopes registered via {@code registerDiscoveredProjects}
		 * start with empty classpaths ({@code classpathResolved = false}) and
		 * are upgraded later via {@code updateProjectClasspaths}.
		 */
		volatile boolean classpathResolved = false;

		ProjectScope(Path projectRoot, ICompilationUnitFactory factory) {
			this.projectRoot = projectRoot;
			this.compilationUnitFactory = factory;
			this.javaSourceLocator = new JavaSourceLocator();
			if (projectRoot != null) {
				this.javaSourceLocator.addProjectRoot(projectRoot);
			}
		}
	}

	private volatile LanguageClient languageClient;
	private volatile Path workspaceRoot;
	private final FileContentsTracker fileContentsTracker = new FileContentsTracker();
	private volatile boolean semanticHighlightingEnabled = true;
	private volatile boolean formattingEnabled = true;

	private final CompilationOrchestrator compilationOrchestrator = new CompilationOrchestrator();
	private final DiagnosticHandler diagnosticHandler = new DiagnosticHandler();

	// Debounce scheduler for didChange recompilation
	private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "groovyls-debounce");
		t.setDaemon(true);
		return t;
	});
	private volatile ScheduledFuture<?> pendingDebounce;

	/**
	 * Executor for debounced Java/build-file recompile scheduling.
	 * Uses a single thread so that per-project recompiles are serialised.
	 */
	private final ScheduledExecutorService javaRecompileExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "groovyls-java-recompile");
		t.setDaemon(true);
		return t;
	});

	/** Per-project debounce futures for Java/build-file recompiles. */
	private final ConcurrentHashMap<Path, ScheduledFuture<?>> pendingJavaRecompiles = new ConcurrentHashMap<>();

	/**
	 * Single-threaded executor for background compilations triggered by
	 * {@code didOpen} and {@code didClose}.  Using a single thread ensures
	 * that compilations are serialised and the latest AST snapshot is
	 * published via a volatile write visible to lock-free readers.
	 */
	private final ExecutorService backgroundCompiler = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "groovyls-bg-compile");
		t.setDaemon(true);
		return t;
	});

	// Default scope (used when no build-tool projects are registered, e.g. in tests)
	private volatile ProjectScope defaultScope;
	// Per-project scopes, sorted by path length desc for longest-prefix match.
	// Volatile reference to an effectively-immutable list; swapped atomically
	// in addProjects(). Reads are lock-free.
	private volatile List<ProjectScope> projectScopes = Collections.emptyList();
	// True while a background project import (Gradle/Maven) is in progress.
	// When true, didOpen/didChange/didClose skip compilation on the defaultScope
	// to avoid wrong diagnostics (no classpath, entire workspace scanned).
	private volatile boolean importInProgress;

	/**
	 * Guards mutations to the {@link #projectScopes} list and
	 * {@link #defaultScope} reference. Held very briefly—only while
	 * swapping the list reference, not during compilation.
	 *
	 * <p>Per-project mutable state (compilationUnit, astVisitor, etc.) is
	 * guarded by each {@link ProjectScope#lock} individually, so operations
	 * on independent projects proceed concurrently.</p>
	 */
	private final Object scopesMutationLock = new Object();

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
		importInProgress = false;
		List<ProjectScope> scopes = projectScopes;
		if (scopes.isEmpty()) {
			ProjectScope ds = defaultScope;
			ds.lock.writeLock().lock();
			try {
				// No build-tool projects found — compile open files with defaultScope
				Set<URI> openURIs = fileContentsTracker.getOpenURIs();
				for (URI uri : openURIs) {
					compileAndVisitAST(ds, uri);
				}
			} finally {
				ds.lock.writeLock().unlock();
			}
			if (languageClient != null) {
				languageClient.refreshSemanticTokens();
			}
		}
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		synchronized (scopesMutationLock) {
			this.workspaceRoot = workspaceRoot;
			ProjectScope ds = new ProjectScope(workspaceRoot, defaultScope.compilationUnitFactory);
			// Defer compilation — the default scope is compiled lazily via
			// ensureScopeCompiled() when an LSP request targets it.  This
			// avoids wasting time compiling with an empty classpath when
			// registerDiscoveredProjects() will replace the scope moments later.
			ds.compiled = false;
			this.defaultScope = ds;
		}
	}

	public Path getWorkspaceRoot() {
		return workspaceRoot;
	}

	/**
	 * Register discovered project roots immediately, <b>before</b> classpath
	 * resolution.  Each scope is created with an empty classpath and
	 * {@code classpathResolved = false}.  This allows
	 * {@link #findProjectScope(URI)} to work right away so that didOpen can
	 * compile open files with basic syntax while the heavy classpath
	 * resolution runs in the background.
	 *
	 * <p>Call {@link #updateProjectClasspaths(Map)} once classpath resolution
	 * completes to upgrade scopes with their full classpath.</p>
	 *
	 * @param projectRoots all discovered project root paths
	 */
	public void registerDiscoveredProjects(List<Path> projectRoots) {
		synchronized (scopesMutationLock) {
			logger.info("registerDiscoveredProjects called with {} projects", projectRoots.size());

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();

				// Compute nested project roots that this project should NOT scan
				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				factory.setExcludedSubRoots(excludedRoots);
				// Note: no classpath set yet — classpathResolved stays false

				newScopes.add(new ProjectScope(projectRoot, factory));
			}

			// Sort scopes by path length descending for longest-prefix-first matching
			newScopes.sort((a, b) -> b.projectRoot.toString().length() - a.projectRoot.toString().length());

			// Publish the immutable list atomically (volatile write)
			projectScopes = Collections.unmodifiableList(newScopes);
		}

		// Clear stale diagnostics from the default scope.
		clearDefaultScopeDiagnostics();
	}

	/**
	 * Update existing project scopes with their resolved classpaths.
	 * Called after background classpath resolution completes.  Scopes that
	 * were already compiled (with an empty classpath) are recompiled with
	 * the full classpath so that diagnostics and completions reflect all
	 * dependency types.
	 *
	 * <p><b>Lazy optimisation:</b> only scopes that have open files are
	 * recompiled eagerly.  All other scopes stay uncompiled and will be
	 * compiled on-demand via {@link #ensureScopeCompiled} when a file
	 * belonging to them is first opened.</p>
	 *
	 * @param projectClasspaths map from project root to classpath entries
	 */
	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths) {
		logger.info("updateProjectClasspaths called with {} projects", projectClasspaths.size());
		List<ProjectScope> scopes = projectScopes;

		// Collect open URIs once so we can check which scopes have open files.
		Set<URI> openURIs = fileContentsTracker.getOpenURIs();

		// Build a set of scopes that own at least one open file.
		Set<ProjectScope> scopesWithOpenFiles = new HashSet<>();
		for (URI uri : openURIs) {
			ProjectScope owning = findProjectScope(uri);
			if (owning != null) {
				scopesWithOpenFiles.add(owning);
			}
		}

		for (ProjectScope scope : scopes) {
			List<String> classpath = projectClasspaths.get(scope.projectRoot);
			if (classpath != null) {
				scope.lock.writeLock().lock();
				try {
					((CompilationUnitFactory) scope.compilationUnitFactory)
							.setAdditionalClasspathList(classpath);
					scope.classpathResolved = true;

					// If the scope was already compiled with an empty classpath,
					// force a fresh compilation with the real classpath.
					if (scope.compiled) {
						logger.info("Forcing recompilation of {} with resolved classpath",
								scope.projectRoot);
						scope.compiled = false;
						scope.compilationUnit = null;
						scope.astVisitor = null;
					}
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}

		// Only recompile scopes that have open files — all others stay lazy.
		for (URI uri : openURIs) {
			ProjectScope scope = findProjectScope(uri);
			if (scope != null && scope.classpathResolved) {
				scope.lock.writeLock().lock();
				try {
					ensureScopeCompiled(scope);
					compileAndVisitAST(scope, uri);
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}

		// Ask the client to re-request semantic tokens for open editors.
		if (languageClient != null) {
			languageClient.refreshSemanticTokens();
		}
	}

	/**
	 * Register all build-tool projects at once with their resolved classpaths.
	 * This allows computing proper subproject exclusions so that parent projects
	 * don't scan source files belonging to nested subprojects.
	 *
	 * <p>Compilation is <b>deferred</b>: project scopes are registered with
	 * their classpaths and factories, but the actual Groovy compilation is
	 * not performed here.  Each scope is compiled lazily on first access
	 * (e.g. when a file belonging to it is opened or an LSP request targets
	 * it).  This reduces startup time in large workspaces with many projects
	 * because only the projects the user actively works on pay the
	 * compilation cost.</p>
	 *
	 * <p>Files that were already opened before the import completed are
	 * compiled immediately so that diagnostics and semantic tokens are
	 * available right away for visible editors.</p>
	 */
	public void addProjects(Map<Path, List<String>> projectClasspaths) {
		List<ProjectScope> newScopes;
		synchronized (scopesMutationLock) {
			logger.info("addProjects called with {} projects", projectClasspaths.size());
			List<Path> projectRoots = new ArrayList<>(projectClasspaths.keySet());
			for (Path p : projectRoots) {
				logger.info("  project root: {}, classpath entries: {}", p, projectClasspaths.get(p).size());
			}

			newScopes = new ArrayList<>();
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
				scope.classpathResolved = true;
				newScopes.add(scope);
			}

			// Sort scopes by path length descending for longest-prefix-first matching
			newScopes.sort((a, b) -> b.projectRoot.toString().length() - a.projectRoot.toString().length());

			// Publish the immutable list atomically (volatile write)
			projectScopes = Collections.unmodifiableList(newScopes);
		}

		// Clear any stale diagnostics from the default scope that may have
		// been published before the import completed.
		clearDefaultScopeDiagnostics();

		// Replay deferred didOpen compilations for files that were opened
		// while the import was in progress.  Each scope is compiled lazily
		// on first access; open files trigger that first compilation here.
		Set<URI> openURIs = fileContentsTracker.getOpenURIs();
		for (URI uri : openURIs) {
			ProjectScope scope = findProjectScope(uri);
			if (scope != null) {
				scope.lock.writeLock().lock();
				try {
					ensureScopeCompiled(scope);
					compileAndVisitAST(scope, uri);
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}

		// Ask the client to re-request semantic tokens for open editors.
		if (languageClient != null) {
			languageClient.refreshSemanticTokens();
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

	/**
	 * Shuts down background executors.  Called from
	 * {@link GroovyLanguageServer#shutdown()}.
	 */
	public void shutdown() {
		debounceExecutor.shutdownNow();
		javaRecompileExecutor.shutdownNow();
		backgroundCompiler.shutdownNow();
	}

	// --- NOTIFICATIONS

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		fileContentsTracker.didOpen(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope != null && !isImportPendingFor(scope)) {
			scope.lock.writeLock().lock();
			try {
				ensureScopeCompiled(scope);
				compileAndVisitAST(scope, uri);
			} finally {
				scope.lock.writeLock().unlock();
			}
		} else if (isImportPendingFor(scope != null ? scope : defaultScope)) {
			// Import is still in progress — provide immediate syntax-only
			// diagnostics so the user sees parse errors without waiting for
			// the full classpath resolution to complete.
			backgroundCompiler.submit(() -> syntaxCheckSingleFile(uri));
		}
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		// Always update the file contents immediately (tracker is thread-safe)
		fileContentsTracker.didChange(params);

		// Cancel any pending debounce and schedule a new recompilation
		ScheduledFuture<?> prev = pendingDebounce;
		if (prev != null) {
			prev.cancel(false);
		}
		pendingDebounce = debounceExecutor.schedule(() -> {
			URI uri = URI.create(params.getTextDocument().getUri());
			ProjectScope scope = findProjectScope(uri);
			if (scope != null && !isImportPendingFor(scope)) {
				scope.lock.writeLock().lock();
				try {
					ensureScopeCompiled(scope);
					compileAndVisitAST(scope, uri);
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		fileContentsTracker.didClose(params);
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		if (scope != null && !isImportPendingFor(scope)) {
			backgroundCompiler.submit(() -> {
				scope.lock.writeLock().lock();
				try {
					ensureScopeCompiled(scope);
					compileAndVisitAST(scope, uri);
				} finally {
					scope.lock.writeLock().unlock();
				}
			});
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
		Set<URI> allChangedUris = params.getChanges().stream()
				.map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());

		// Detect Java/build-tool file changes that require recompilation.
		// Generated .java files under build output directories (build/,
		// target/, etc.) are excluded to prevent feedback loops with
		// annotation processors and source generators.
		Set<Path> projectsNeedingRecompile = new LinkedHashSet<>();
		List<ProjectScope> scopes = projectScopes; // volatile read once
		for (URI changedUri : allChangedUris) {
			String path = changedUri.getPath();
			if (path != null && (path.endsWith(".java")
					|| path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")
					|| path.endsWith("pom.xml"))) {
				Path filePath = Paths.get(changedUri);
				for (ProjectScope scope : scopes) {
					if (scope.projectRoot != null && filePath.startsWith(scope.projectRoot)) {
						// Skip generated/build-output .java files to prevent
						// feedback loops (annotation processors, source generators)
						if (path.endsWith(".java") && isBuildOutputFile(filePath, scope.projectRoot)) {
							logger.debug("Ignoring build-output Java file: {}", filePath);
							break;
						}
						projectsNeedingRecompile.add(scope.projectRoot);
						break;
					}
				}
			}
		}

		// Schedule debounced async recompile for affected projects.
		// The Gradle/Maven build runs on a background thread so the
		// notification handler returns immediately, allowing other LSP
		// messages (diagnostics, hover, etc.) to be processed.
		if (!projectsNeedingRecompile.isEmpty()) {
			for (Path projectRoot : projectsNeedingRecompile) {
				scheduleJavaRecompile(projectRoot);
			}
		}

		// Refresh Java source index for projects with Java file changes
		for (URI changedUri : allChangedUris) {
			String uriPath = changedUri.getPath();
			if (uriPath != null && uriPath.endsWith(".java")) {
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

		// Process each non-recompile scope independently under its own lock.
		// Scopes that need a Java recompile are handled by the debounced
		// async handler (scheduleJavaRecompile) instead.
		for (ProjectScope scope : getAllScopes()) {
			if (projectsNeedingRecompile.contains(scope.projectRoot)) {
				continue; // handled by scheduleJavaRecompile
			}

			Set<URI> scopeUris;
			if (scopes.isEmpty()) {
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
				scope.lock.writeLock().lock();
				try {
					// Invalidate file cache so that new/deleted .groovy files are picked up
					scope.compilationUnitFactory.invalidateFileCache();

					// Handle .groovy file deletions: remove from dependency graph
					for (URI changedUri : scopeUris) {
						String uriPath = changedUri.getPath();
						if (uriPath != null && uriPath.endsWith(".groovy")) {
							// Check if file was deleted (no longer exists)
							try {
								if (!Files.exists(Paths.get(changedUri))) {
									scope.dependencyGraph.removeFile(changedUri);
								}
							} catch (Exception e) {
								// ignore URIs that can't be converted to Path
							}
						}
					}
					ensureScopeCompiled(scope);
					boolean isSameUnit = createOrUpdateCompilationUnit(scope);
					resetChangedFilesForScope(scope);
					compile(scope);
					if (isSameUnit) {
						visitAST(scope, scopeUris);
					} else {
						visitAST(scope);
					}
					updateDependencyGraph(scope, scopeUris);
				} finally {
					scope.lock.writeLock().unlock();
				}
			}
		}
	}

	/**
	 * Returns {@code true} if the given file path is inside a build output
	 * directory (e.g. {@code build/}, {@code target/}, {@code .gradle/},
	 * {@code out/}, {@code bin/}) relative to the project root.
	 *
	 * <p>Generated {@code .java} files produced by annotation processors or
	 * source generators live in these directories. Treating them as
	 * &ldquo;source changes&rdquo; would create an infinite recompile loop:
	 * a user edit triggers a Gradle build, which generates {@code .java}
	 * files, which trigger another build, and so on.</p>
	 */
	static boolean isBuildOutputFile(Path filePath, Path projectRoot) {
		Path relativePath = projectRoot.relativize(filePath);
		if (relativePath.getNameCount() == 0) {
			return false;
		}
		String firstSegment = relativePath.getName(0).toString();
		return BUILD_OUTPUT_DIRS.contains(firstSegment);
	}

	/**
	 * Schedules a debounced, asynchronous Java/build-tool recompile for the
	 * given project root.  Multiple rapid-fire file changes are coalesced
	 * into a single recompile that fires {@value #JAVA_RECOMPILE_DEBOUNCE_MS}
	 * ms after the last change.
	 *
	 * <p>The recompile runs on a dedicated background thread so that the
	 * calling notification handler returns immediately and doesn't block
	 * other LSP message processing (hover, completion, diagnostics for
	 * other projects, etc.).</p>
	 */
	private void scheduleJavaRecompile(Path projectRoot) {
		ScheduledFuture<?> prev = pendingJavaRecompiles.get(projectRoot);
		if (prev != null) {
			prev.cancel(false);
		}
		logger.info("Scheduling debounced Java recompile for {} ({}ms delay)",
				projectRoot, JAVA_RECOMPILE_DEBOUNCE_MS);
		pendingJavaRecompiles.put(projectRoot, javaRecompileExecutor.schedule(() -> {
			try {
				executeJavaRecompile(projectRoot);
			} catch (Exception e) {
				logger.error("Error during debounced Java recompile for {}: {}",
						projectRoot, e.getMessage(), e);
			} finally {
				pendingJavaRecompiles.remove(projectRoot);
			}
		}, JAVA_RECOMPILE_DEBOUNCE_MS, TimeUnit.MILLISECONDS));
	}

	/**
	 * Executes the full Java recompile flow for a project: delegates to the
	 * {@link JavaChangeListener} (which runs the Gradle/Maven build), then
	 * invalidates the Groovy compilation unit and recompiles the scope so
	 * that diagnostics and AST data reflect the updated Java classes.
	 *
	 * <p>Called on the {@link #javaRecompileExecutor} thread.</p>
	 */
	private void executeJavaRecompile(Path projectRoot) {
		logger.info("Java/build files changed in {}, triggering recompile", projectRoot);
		if (javaChangeListener != null) {
			javaChangeListener.onJavaFilesChanged(projectRoot);
		}

		// Find the scope for this project and recompile the Groovy AST
		ProjectScope scope = findProjectScopeByRoot(projectRoot);
		if (scope == null) {
			logger.warn("No scope found for project root after Java recompile: {}", projectRoot);
			return;
		}

		scope.lock.writeLock().lock();
		try {
			scope.compilationUnitFactory.invalidateFileCache();
			scope.compilationUnitFactory.invalidateCompilationUnit();
			scope.dependencyGraph.clear();
			ensureScopeCompiled(scope);
			createOrUpdateCompilationUnit(scope);
			resetChangedFilesForScope(scope);
			compile(scope);
			visitAST(scope);
			// Full rebuild — update all dependencies
			if (scope.astVisitor != null) {
				for (URI uri : scope.astVisitor.getDependenciesByURI().keySet()) {
					Set<URI> deps = scope.astVisitor.resolveSourceDependencies(uri);
					scope.dependencyGraph.updateDependencies(uri, deps);
				}
			}
		} finally {
			scope.lock.writeLock().unlock();
		}
	}

	/**
	 * Finds a project scope by its exact root path.
	 */
	private ProjectScope findProjectScopeByRoot(Path root) {
		for (ProjectScope scope : getAllScopes()) {
			if (root.equals(scope.projectRoot)) {
				return scope;
			}
		}
		return null;
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		if (!(params.getSettings() instanceof JsonObject)) {
			return;
		}
		JsonObject settings = (JsonObject) params.getSettings();
		this.updateClasspath(settings);
		this.updateFeatureToggles(settings);
		if (settingsChangeListener != null) {
			settingsChangeListener.onSettingsChanged(settings);
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
		// When project scopes are registered (via Gradle import), they handle
		// their own classpaths. The default scope should not override them.
		if (!projectScopes.isEmpty()) {
			logger.info("updateClasspath() ignored — {} project scope(s) are active", projectScopes.size());
			return;
		}
		// Don't compile the default scope while a background import is in
		// progress — it would scan the entire workspace with no classpath,
		// causing duplicate-class and unresolved-import errors.
		if (importInProgress) {
			logger.info("updateClasspath() deferred — project import in progress");
			defaultScope.compilationUnitFactory.setAdditionalClasspathList(classpathList);
			return;
		}
		ProjectScope ds = defaultScope;
		ds.lock.writeLock().lock();
		try {
			if (!classpathList.equals(ds.compilationUnitFactory.getAdditionalClasspathList())) {
				ds.compilationUnitFactory.setAdditionalClasspathList(classpathList);
				// Classpath changed — full rebuild, clear dependency graph
				ds.dependencyGraph.clear();
				createOrUpdateCompilationUnit(ds);
				resetChangedFilesForScope(ds);
				compile(ds);
				visitAST(ds);
				// Rebuild dependency graph from scratch
				if (ds.astVisitor != null) {
					for (URI uri : ds.astVisitor.getDependenciesByURI().keySet()) {
						Set<URI> deps = ds.astVisitor.resolveSourceDependencies(uri);
						ds.dependencyGraph.updateDependencies(uri, deps);
					}
				}
				ds.previousContext = null;
				ds.compiled = true;
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

		ProjectScope scope = findProjectScope(uri);
		if (scope == null) {
			return CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
		}

		scope.lock.writeLock().lock();
		try {
			ensureScopeCompiled(scope);
			if (scope.astVisitor == null) {
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
			scope.lock.writeLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);
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

		ProjectScope scope = findProjectScope(uri);
		if (scope == null) {
			return CompletableFuture.completedFuture(new SignatureHelp());
		}

		scope.lock.writeLock().lock();
		try {
			ensureScopeCompiled(scope);
			if (scope.astVisitor == null) {
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
			scope.lock.writeLock().unlock();
		}
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
			TypeDefinitionParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);
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
		ProjectScope scope = ensureCompiledForContext(uri);
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
		ProjectScope scope = ensureCompiledForContext(uri);
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
		ProjectScope scope = ensureCompiledForContext(uri);
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
		ProjectScope scope = ensureCompiledForContext(uri);
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
		// Workspace symbols aggregate across all project scopes.
		// Uses volatile astVisitor reads — no lock needed.
		List<SymbolInformation> allSymbols = new ArrayList<>();
		for (ProjectScope scope : getAllScopes()) {
			ASTNodeVisitor visitor = scope.astVisitor;
			if (visitor != null) {
				WorkspaceSymbolProvider provider = new WorkspaceSymbolProvider(visitor);
				List<? extends SymbolInformation> symbols = provider.provideWorkspaceSymbols(params.getQuery())
						.join();
				allSymbols.addAll(symbols);
			}
		}
		return CompletableFuture.completedFuture(Either.forLeft(allSymbols));
	}

	@Override
	public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
			PrepareRenameParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = ensureCompiledForContext(uri);
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
		ProjectScope scope = ensureCompiledForContext(uri);
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
		ProjectScope scope = ensureCompiledForContext(uri);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		ScanResult scanResult = scope != null ? scope.classGraphScanResult : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		CodeActionProvider provider = new CodeActionProvider(visitor, scanResult,
				fileContentsTracker);
		CompletableFuture<List<Either<Command, CodeAction>>> result = provider.provideCodeActions(params);

		// Add Spock-specific code actions
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
		ProjectScope scope = ensureCompiledForContext(uri);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		InlayHintProvider provider = new InlayHintProvider(visitor);
		return provider.provideInlayHints(params);
	}

	@Override
	public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
		if (!semanticHighlightingEnabled) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		ProjectScope scope = findProjectScope(uri);
		ASTNodeVisitor visitor = scope != null ? scope.astVisitor : null;
		if (visitor == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}

		SemanticTokensProvider provider = new SemanticTokensProvider(visitor, fileContentsTracker);
		return provider.provideSemanticTokensFull(params.getTextDocument());
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
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
			ASTNodeVisitor visitor = scope.astVisitor;
			if (visitor == null) {
				continue;
			}
			String docs = resolveDocumentation(visitor, label, kind, signature, declaringClassName);
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

	private String resolveDocumentation(ASTNodeVisitor visitor, String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		if (kind == CompletionItemKind.Method) {
			// When declaringClassName is available, look up only that class (O(1) lookup
			// via the AST visitor's class node map) instead of scanning all classes.
			if (declaringClassName != null) {
				org.codehaus.groovy.ast.ClassNode classNode = visitor.getClassNodeByName(declaringClassName);
				if (classNode != null) {
					String docs = findMethodDocs(classNode, label, signature);
					if (docs != null) {
						return docs;
					}
				}
			}
			// Fallback: scan all class nodes
			for (org.codehaus.groovy.ast.ClassNode classNode : visitor.getClassNodes()) {
				String docs = findMethodDocs(classNode, label, signature);
				if (docs != null) {
					return docs;
				}
			}
		} else if (kind == CompletionItemKind.Property || kind == CompletionItemKind.Field) {
			if (declaringClassName != null) {
				org.codehaus.groovy.ast.ClassNode classNode = visitor.getClassNodeByName(declaringClassName);
				if (classNode != null) {
					String docs = findFieldOrPropertyDocs(classNode, label);
					if (docs != null) {
						return docs;
					}
				}
			}
			for (org.codehaus.groovy.ast.ClassNode classNode : visitor.getClassNodes()) {
				String docs = findFieldOrPropertyDocs(classNode, label);
				if (docs != null) {
					return docs;
				}
			}
		} else if (kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
				|| kind == CompletionItemKind.Enum) {
			// Direct name lookup first
			org.codehaus.groovy.ast.ClassNode classNode = visitor.getClassNodeByName(label);
			if (classNode != null) {
				String docs = com.tomaszrup.groovyls.compiler.util.GroovydocUtils
						.groovydocToMarkdownDescription(classNode.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
			// Fallback: scan by simple name
			for (org.codehaus.groovy.ast.ClassNode cn : visitor.getClassNodes()) {
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
		if (!formattingEnabled) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		URI uri = URI.create(params.getTextDocument().getUri());
		String sourceText = fileContentsTracker.getContents(uri);
		FormattingProvider provider = new FormattingProvider();
		return provider.provideFormatting(params, sourceText);
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
		return createOrUpdateCompilationUnit(scope, Collections.emptySet());
	}

	private boolean createOrUpdateCompilationUnit(ProjectScope scope, Set<URI> additionalInvalidations) {
		GroovyLSCompilationUnit[] cuHolder = { scope.compilationUnit };
		ASTNodeVisitor[] avHolder = { scope.astVisitor };
		ScanResult[] srHolder = { scope.classGraphScanResult };
		GroovyClassLoader[] clHolder = { scope.classLoader };

		boolean result = compilationOrchestrator.createOrUpdateCompilationUnit(
				cuHolder, avHolder, scope.projectRoot,
				scope.compilationUnitFactory, fileContentsTracker,
				srHolder, clHolder, additionalInvalidations);

		scope.compilationUnit = cuHolder[0];
		scope.classGraphScanResult = srHolder[0];
		scope.classLoader = clHolder[0];
		return result;
	}

	/**
	 * Ensures the project scope that owns {@code uri} has been compiled at
	 * least once.  If the scope hasn't been compiled yet, a synchronous
	 * compilation is performed under the write lock.  If the scope has a
	 * valid AST and there are pending changes, a <em>background</em>
	 * compilation is submitted so that the current request can proceed
	 * immediately with the stale (but consistent) AST snapshot.
	 *
	 * <p>For non-mutating read-only handlers, callers should snapshot the
	 * volatile {@code scope.astVisitor} reference <em>after</em> this method
	 * returns and use that snapshot for the duration of the request (stale-AST
	 * reads).</p>
	 *
	 * <p><b>Important:</b> the caller must <em>not</em> hold any lock on
	 * the returned scope when calling this method.</p>
	 *
	 * @return the {@link ProjectScope} that owns the URI, or {@code null}
	 */
	private ProjectScope ensureCompiledForContext(URI uri) {
		ProjectScope scope = findProjectScope(uri);
		if (scope == null) {
			return null;
		}

		// Lock-free quick check using volatile fields
		if (!scope.compiled || scope.astVisitor == null) {
			// First compilation — must be synchronous
			scope.lock.writeLock().lock();
			try {
				ensureScopeCompiled(scope);
				if (scope.astVisitor == null) {
					return scope;
				}
				if (!fileContentsTracker.getChangedURIs().isEmpty()) {
					compileAndVisitAST(scope, uri);
				}
			} finally {
				scope.lock.writeLock().unlock();
			}
		} else if (!fileContentsTracker.getChangedURIs().isEmpty()) {
			// There are pending changes but the scope has a valid AST.
			// Submit a background compilation so the next request sees
			// fresh data; the current request proceeds with the stale AST.
			backgroundCompiler.submit(() -> {
				scope.lock.writeLock().lock();
				try {
					if (!fileContentsTracker.getChangedURIs().isEmpty()) {
						compileAndVisitAST(scope, uri);
					}
				} finally {
					scope.lock.writeLock().unlock();
				}
			});
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
		// Snapshot changed URIs BEFORE factory reads them so that concurrent
		// additions from other threads are not cleared prematurely.
		Set<URI> changedSnapshot = new HashSet<>(fileContentsTracker.getChangedURIs());

		// Try incremental (single-file) compilation for small change sets
		// when we already have a compiled AST and a populated dependency graph.
		if (changedSnapshot.size() <= INCREMENTAL_MAX_CHANGED
				&& scope.astVisitor != null
				&& scope.compiled
				&& !scope.dependencyGraph.isEmpty()) {
			if (tryIncrementalCompile(scope, contextURI, changedSnapshot)) {
				clearProcessedChanges(scope, changedSnapshot);
				scope.previousContext = contextURI;
				return;
			}
			// Incremental failed — fall through to full compilation
			logger.info("Incremental compile failed for scope {}, falling back to full", scope.projectRoot);
		}

		// Full compilation path
		// Compute transitive dependents of the changed files so they are also
		// removed from the compilation unit and recompiled.
		Set<URI> affectedDependents = scope.dependencyGraph.getTransitiveDependents(changedSnapshot);

		// The full set of URIs to visit after compilation: context + changed + dependents
		Set<URI> allAffectedURIs = new HashSet<>(changedSnapshot);
		allAffectedURIs.add(contextURI);
		allAffectedURIs.addAll(affectedDependents);

		boolean isSameUnit = createOrUpdateCompilationUnit(scope, affectedDependents);
		// Only clear URIs that were present at snapshot time and belong to
		// this scope (other scopes' files must remain dirty).
		clearProcessedChanges(scope, changedSnapshot);
		compile(scope);
		if (isSameUnit) {
			visitAST(scope, allAffectedURIs);
		} else {
			visitAST(scope);
		}

		// Update the dependency graph with freshly resolved dependencies
		updateDependencyGraph(scope, allAffectedURIs);

		scope.previousContext = contextURI;
		scope.compiled = true;
	}

	/**
	 * Attempts incremental single-file compilation for a small set of changed
	 * files. Creates a lightweight compilation unit containing only the changed
	 * files and their forward dependencies (for type resolution), compiles it,
	 * and merges the AST results.
	 *
	 * <p>Returns {@code true} if the incremental compile succeeded and the AST
	 * was updated without requiring dependent recompilation. Returns
	 * {@code false} if the caller must fall back to full compilation (e.g. too
	 * many files, compilation failure, or public API change detected).</p>
	 *
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	private boolean tryIncrementalCompile(ProjectScope scope, URI contextURI, Set<URI> changedSnapshot) {
		// Build the set of URIs whose AST we actually need to update
		Set<URI> changedPlusContext = new HashSet<>(changedSnapshot);
		changedPlusContext.add(contextURI);

		// Collect forward transitive dependencies (2 levels deep) so the
		// incremental compilation unit can resolve types from imported files.
		Set<URI> forwardDeps = scope.dependencyGraph.getTransitiveDependencies(changedPlusContext, 2);

		Set<URI> filesToCompile = new HashSet<>(changedPlusContext);
		filesToCompile.addAll(forwardDeps);

		if (filesToCompile.size() > INCREMENTAL_MAX_FILES) {
			logger.info("Incremental compile aborted: {} files exceeds limit of {}",
					filesToCompile.size(), INCREMENTAL_MAX_FILES);
			return false;
		}

		// Capture old class signatures for the changed files (API-change detection)
		Map<String, ClassSignature> oldSignatures = captureClassSignatures(scope.astVisitor, changedPlusContext);

		// Create a lightweight compilation unit with only the needed files
		GroovyLSCompilationUnit incrementalUnit = scope.compilationUnitFactory.createIncremental(
				scope.projectRoot, fileContentsTracker, filesToCompile);
		if (incrementalUnit == null) {
			return false;
		}

		// Compile the incremental unit
		ErrorCollector collector = compilationOrchestrator.compileIncremental(incrementalUnit, scope.projectRoot);

		// Visit AST only for the changed files (dependencies are in the unit
		// for type resolution but their AST data in the base visitor is preserved)
		ASTNodeVisitor newVisitor = compilationOrchestrator.visitAST(
				incrementalUnit, scope.astVisitor, changedPlusContext);

		// Check if the public API changed BEFORE publishing the new visitor
		Map<String, ClassSignature> newSignatures = captureClassSignatures(newVisitor, changedPlusContext);
		if (!oldSignatures.equals(newSignatures)) {
			logger.info("API change detected in incremental compile for scope {}, need full recompile",
					scope.projectRoot);
			return false;
		}

		// No API change - publish the new visitor (volatile write)
		scope.astVisitor = newVisitor;

		// Update dependency graph for the changed files
		updateDependencyGraph(scope, changedPlusContext);

		// Publish diagnostics from the incremental compilation
		if (collector != null) {
			DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
					incrementalUnit, collector, scope.projectRoot, scope.prevDiagnosticsByFile);
			scope.prevDiagnosticsByFile = result.getDiagnosticsByFile();
			LanguageClient client = languageClient;
			if (client != null) {
				result.getDiagnosticsToPublish().forEach(client::publishDiagnostics);
			}
		}

		logger.info("Incremental compilation succeeded for scope {} ({} changed, {} total in unit)",
				scope.projectRoot, changedPlusContext.size(), filesToCompile.size());
		return true;
	}

	/**
	 * Captures class signatures for all classes defined in the given URIs.
	 * Used for API-change detection during incremental compilation.
	 */
	private Map<String, ClassSignature> captureClassSignatures(ASTNodeVisitor visitor, Set<URI> uris) {
		Map<String, ClassSignature> signatures = new HashMap<>();
		if (visitor == null) {
			return signatures;
		}
		for (URI uri : uris) {
			List<org.codehaus.groovy.ast.ClassNode> classNodes = visitor.getClassNodes(uri);
			for (org.codehaus.groovy.ast.ClassNode cn : classNodes) {
				try {
					signatures.put(cn.getName(), ClassSignature.of(cn));
				} catch (Exception e) {
					// ClassNode may be in an incomplete state; skip it
					logger.debug("Failed to capture signature for {}: {}", cn.getName(), e.getMessage());
				}
			}
		}
		return signatures;
	}

	/**
	 * Performs the initial (deferred) compilation of a scope if it hasn’t
	 * been compiled yet.  Called under the scope’s write lock.
	 */
	private void ensureScopeCompiled(ProjectScope scope) {
		if (!scope.compiled) {
			createOrUpdateCompilationUnit(scope);
			resetChangedFilesForScope(scope);
			compile(scope);
			visitAST(scope);
			// Build the full dependency graph after initial compilation
			if (scope.astVisitor != null) {
				scope.dependencyGraph.clear();
				for (URI uri : scope.astVisitor.getDependenciesByURI().keySet()) {
					Set<URI> deps = scope.astVisitor.resolveSourceDependencies(uri);
					scope.dependencyGraph.updateDependencies(uri, deps);
				}
			}
			scope.compiled = true;
		}
	}

	/**
	 * Updates the dependency graph for the given set of URIs using freshly
	 * resolved dependency information from the AST visitor.
	 */
	private void updateDependencyGraph(ProjectScope scope, Set<URI> uris) {
		if (scope.astVisitor == null) {
			return;
		}
		for (URI uri : uris) {
			Set<URI> deps = scope.astVisitor.resolveSourceDependencies(uri);
			scope.dependencyGraph.updateDependencies(uri, deps);
		}
	}


	/**
	 * Clears changed-file tracking for the given scope.  If the scope has
	 * a project root, only URIs under that root are removed; otherwise all
	 * changed URIs are cleared (backward-compat for the default scope).
	 */
	private void resetChangedFilesForScope(ProjectScope scope) {
		if (scope.projectRoot != null) {
			Set<URI> toReset = new HashSet<>();
			for (URI uri : fileContentsTracker.getChangedURIs()) {
				try {
					if (Paths.get(uri).startsWith(scope.projectRoot)) {
						toReset.add(uri);
					}
				} catch (Exception e) {
					// ignore URIs that can't be converted to Path
				}
			}
			if (!toReset.isEmpty()) {
				fileContentsTracker.resetChangedFiles(toReset);
			}
		} else {
			fileContentsTracker.resetChangedFiles();
		}
	}

	/**
	 * Clears only the URIs from the given snapshot set that belong to the
	 * scope.  Any URIs added to {@code changedFiles} after the snapshot
	 * was taken are left intact, ensuring concurrent changes are not lost.
	 */
	private void clearProcessedChanges(ProjectScope scope, Set<URI> snapshot) {
		if (scope.projectRoot != null) {
			Set<URI> toReset = new HashSet<>();
			for (URI uri : snapshot) {
				try {
					if (Paths.get(uri).startsWith(scope.projectRoot)) {
						toReset.add(uri);
					}
				} catch (Exception e) {
					// ignore
				}
			}
			if (!toReset.isEmpty()) {
				fileContentsTracker.resetChangedFiles(toReset);
			}
		} else {
			fileContentsTracker.resetChangedFiles(snapshot);
		}
	}

	// --- PLACEHOLDER INJECTION HELPERS

	/**
	 * Injects a placeholder into the document source to force AST node creation
	 * at the cursor position for completion.
	 *
	 * <p><b>Caller must hold the scope's write lock.</b></p>
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
	 * <p><b>Caller must hold the scope's write lock.</b></p>
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
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	private void restoreDocumentSource(ProjectScope scope, URI uri, String originalSource) {
		compilationOrchestrator.restoreDocumentSource(fileContentsTracker, uri, originalSource);
		try {
			compileAndVisitAST(scope, uri);
		} catch (Exception e) {
			logger.error("Failed to recompile after restoring document {}: {}", uri, e.getMessage());
		}
	}

	/**
	 * Performs a quick, syntax-only parse of a single file and publishes
	 * any syntax errors as diagnostics.  This is used when a file is opened
	 * while the background project import is still in progress — it gives
	 * the user immediate feedback on parse errors without waiting for full
	 * classpath resolution.
	 *
	 * <p>The compilation unit is lightweight: no classpath, no other source
	 * files, compiled only to {@link Phases#CONVERSION} (parse + syntax
	 * check).  The file will be fully recompiled with the real classpath
	 * once import finishes and {@code onImportComplete()} replays open files.</p>
	 */
	private void syntaxCheckSingleFile(URI uri) {
		String source = fileContentsTracker.getContents(uri);
		if (source == null) {
			return;
		}
		try {
			CompilerConfiguration config = new CompilerConfiguration();
			GroovyLSCompilationUnit unit = new GroovyLSCompilationUnit(config);
			unit.addSource(uri.toString(), source);
			try {
				unit.compile(Phases.CONVERSION);
			} catch (CompilationFailedException e) {
				// Expected for code with syntax errors — diagnostics are in the error collector
			} catch (Exception e) {
				logger.debug("Syntax check failed for {}: {}", uri, e.getMessage());
			}

			ErrorCollector collector = unit.getErrorCollector();
			if (collector != null) {
				DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
						unit, collector, null, null);
				LanguageClient client = languageClient;
				if (client != null) {
					result.getDiagnosticsToPublish().forEach(client::publishDiagnostics);
				}
			}
		} catch (Exception e) {
			logger.debug("Syntax-only check failed for {}: {}", uri, e.getMessage());
		}
	}

	private void compile(ProjectScope scope) {
		ErrorCollector collector = compilationOrchestrator.compile(
				scope.compilationUnit, scope.projectRoot);
		if (collector != null) {
			DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
					scope.compilationUnit, collector, scope.projectRoot, scope.prevDiagnosticsByFile);
			scope.prevDiagnosticsByFile = result.getDiagnosticsByFile();
			LanguageClient client = languageClient;
			if (client != null) {
				result.getDiagnosticsToPublish().forEach(client::publishDiagnostics);
			}
		}
	}
}
