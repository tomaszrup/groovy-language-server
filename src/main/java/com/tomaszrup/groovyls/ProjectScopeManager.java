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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.JavadocResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the creation, lookup, and lifecycle of {@link ProjectScope}s.
 * Handles multi-project registration, classpath updates, configuration
 * changes, and feature toggle settings.
 */
public class ProjectScopeManager {
	private static final Logger logger = LoggerFactory.getLogger(ProjectScopeManager.class);

	private volatile Path workspaceRoot;

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

	private volatile boolean semanticHighlightingEnabled = true;
	private volatile boolean formattingEnabled = true;

	/**
	 * Guards mutations to the {@link #projectScopes} list and
	 * {@link #defaultScope} reference. Held very briefly—only while
	 * swapping the list reference, not during compilation.
	 */
	private final Object scopesMutationLock = new Object();

	private final FileContentsTracker fileContentsTracker;

	/** Supplier of language client — set after server connects. */
	private volatile LanguageClient languageClient;

	public ProjectScopeManager(ICompilationUnitFactory defaultFactory, FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
		this.defaultScope = new ProjectScope(null, defaultFactory);
		// The default scope is not managed by a build tool, so its classpath
		// is always "resolved" (user-configured or empty).
		this.defaultScope.classpathResolved = true;
	}

	// --- Accessors ---

	public Path getWorkspaceRoot() {
		return workspaceRoot;
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		synchronized (scopesMutationLock) {
			this.workspaceRoot = workspaceRoot;
			ProjectScope ds = new ProjectScope(workspaceRoot, defaultScope.compilationUnitFactory);
			ds.compiled = false;
			ds.classpathResolved = true;
			this.defaultScope = ds;
		}
	}

	public boolean isSemanticHighlightingEnabled() {
		return semanticHighlightingEnabled;
	}

	public boolean isFormattingEnabled() {
		return formattingEnabled;
	}

	public boolean isImportInProgress() {
		return importInProgress;
	}

	public void setImportInProgress(boolean inProgress) {
		this.importInProgress = inProgress;
	}

	public void setLanguageClient(LanguageClient client) {
		this.languageClient = client;
	}

	public ProjectScope getDefaultScope() {
		return defaultScope;
	}

	public List<ProjectScope> getProjectScopes() {
		return projectScopes;
	}

	// --- Scope lookup ---

	/**
	 * Find the project scope that owns the given URI.
	 * Returns the default scope when no project scopes are registered (backward
	 * compat for tests). Returns null if projects are registered but the file
	 * doesn't belong to any of them.
	 */
	public ProjectScope findProjectScope(URI uri) {
		if (!projectScopes.isEmpty() && uri != null) {
			Path filePath = Paths.get(uri);
			for (ProjectScope scope : projectScopes) {
				if (scope.projectRoot != null && filePath.startsWith(scope.projectRoot)) {
					logger.debug("findProjectScope({}) -> {}", uri, scope.projectRoot);
					return scope;
				}
			}
			logger.warn("findProjectScope({}) -> no matching project scope found", uri);
			return null;
		}
		return defaultScope;
	}

	/**
	 * Finds a project scope by its exact root path.
	 */
	public ProjectScope findProjectScopeByRoot(Path root) {
		for (ProjectScope scope : getAllScopes()) {
			if (root.equals(scope.projectRoot)) {
				return scope;
			}
		}
		return null;
	}

	/**
	 * Returns all active scopes: project scopes if any are registered,
	 * otherwise just the default scope.
	 */
	public List<ProjectScope> getAllScopes() {
		if (!projectScopes.isEmpty()) {
			return projectScopes;
		}
		return Collections.singletonList(defaultScope);
	}

	/**
	 * Returns true if a background import is in progress and the given scope
	 * should not yet be compiled:
	 * <ul>
	 *   <li>The default scope before any project scopes are registered.</li>
	 *   <li>Any project scope whose classpath has not been resolved yet
	 *       (scopes are registered early with empty classpaths).</li>
	 * </ul>
	 */
	public boolean isImportPendingFor(ProjectScope scope) {
		if (!importInProgress) {
			return false;
		}
		// Pre-discovery phase: only the default scope exists
		if (scope == defaultScope && projectScopes.isEmpty()) {
			return true;
		}
		// Post-discovery, pre-classpath-resolution: scope exists but has no classpath
		if (scope != null && scope != defaultScope && !scope.classpathResolved) {
			return true;
		}
		return false;
	}

	// --- Project registration ---

	/**
	 * Register discovered project roots immediately, <b>before</b> classpath
	 * resolution.  Each scope is created with an empty classpath and
	 * {@code classpathResolved = false}.
	 */
	public void registerDiscoveredProjects(List<Path> projectRoots) {
		synchronized (scopesMutationLock) {
			logger.info("registerDiscoveredProjects called with {} projects", projectRoots.size());

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();

				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				factory.setExcludedSubRoots(excludedRoots);

				newScopes.add(new ProjectScope(projectRoot, factory));
			}

			newScopes.sort((a, b) -> b.projectRoot.toString().length() - a.projectRoot.toString().length());
			projectScopes = Collections.unmodifiableList(newScopes);
		}

		clearDefaultScopeDiagnostics();
	}

	/**
	 * Update existing project scopes with their resolved classpaths.
	 * Called after background classpath resolution completes.
	 * Compilation is NOT triggered here &mdash; scopes compile lazily on
	 * first user interaction.
	 */
	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths) {
		logger.info("updateProjectClasspaths called with {} projects", projectClasspaths.size());
		List<ProjectScope> scopes = projectScopes;

		for (ProjectScope scope : scopes) {
			List<String> classpath = projectClasspaths.get(scope.projectRoot);
			if (classpath != null) {
				scope.lock.writeLock().lock();
				try {
					scope.compilationUnitFactory
							.setAdditionalClasspathList(classpath);
					scope.classpathResolved = true;
					// Classpath changed — evict stale Javadoc cache entries
					JavadocResolver.clearCache();

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
	}

	/**
	 * Register all build-tool projects at once with their resolved classpaths.
	 *
	 * @return set of open URIs that need compilation
	 */
	public Set<URI> addProjects(Map<Path, List<String>> projectClasspaths) {
		synchronized (scopesMutationLock) {
			logger.info("addProjects called with {} projects", projectClasspaths.size());
			List<Path> projectRoots = new ArrayList<>(projectClasspaths.keySet());
			for (Path p : projectRoots) {
				logger.info("  project root: {}, classpath entries: {}", p, projectClasspaths.get(p).size());
			}

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();
				factory.setAdditionalClasspathList(projectClasspaths.get(projectRoot));

				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				logger.info("  Project {}: excluding {} subproject root(s): {}", projectRoot, excludedRoots.size(),
						excludedRoots);
				factory.setExcludedSubRoots(excludedRoots);

				ProjectScope scope = new ProjectScope(projectRoot, factory);
				scope.classpathResolved = true;
				newScopes.add(scope);
			}

			newScopes.sort((a, b) -> b.projectRoot.toString().length() - a.projectRoot.toString().length());
			projectScopes = Collections.unmodifiableList(newScopes);
		}

		clearDefaultScopeDiagnostics();
		return fileContentsTracker.getOpenURIs();
	}

	// --- Configuration ---

	public void updateFeatureToggles(JsonObject settings) {
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

	/**
	 * Parse classpath entries from the JSON settings object and delegate
	 * to {@link #updateClasspath(List)}.
	 */
	public void updateClasspathFromSettings(JsonObject settings) {
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

	/**
	 * Update the default scope's classpath (used when no build-tool projects
	 * are registered).
	 */
	public void updateClasspath(List<String> classpathList) {
		if (!projectScopes.isEmpty()) {
			logger.debug("updateClasspath() ignored — {} project scope(s) are active", projectScopes.size());
			return;
		}
		if (importInProgress) {
			logger.info("updateClasspath() deferred — project import in progress");
			defaultScope.compilationUnitFactory.setAdditionalClasspathList(classpathList);
			return;
		}
		// Store the classpath but don't compile — the caller (CompilationService)
		// handles compilation when needed.
		ProjectScope ds = defaultScope;
		if (!classpathList.equals(ds.compilationUnitFactory.getAdditionalClasspathList())) {
			ds.compilationUnitFactory.setAdditionalClasspathList(classpathList);
			// Mark as needing recompilation
			ds.compiled = false;
		}
	}

	/**
	 * Returns true if the default scope classpath actually changed.
	 */
	public boolean hasClasspathChanged(List<String> classpathList) {
		return !classpathList.equals(defaultScope.compilationUnitFactory.getAdditionalClasspathList());
	}

	// --- Diagnostics helpers ---

	/**
	 * Publishes empty diagnostics for every file that defaultScope previously
	 * reported errors on, so the client clears them.
	 */
	public void clearDefaultScopeDiagnostics() {
		if (defaultScope.prevDiagnosticsByFile != null && languageClient != null) {
			for (URI uri : defaultScope.prevDiagnosticsByFile.keySet()) {
				languageClient.publishDiagnostics(
						new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
			}
			defaultScope.prevDiagnosticsByFile = null;
		}
	}
}
