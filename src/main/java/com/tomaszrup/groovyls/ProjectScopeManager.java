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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;

import com.tomaszrup.groovyls.compiler.SharedClassGraphCache;
import com.tomaszrup.groovyls.compiler.SharedClasspathIndexCache;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.GroovyVersionDetector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the creation, lookup, and lifecycle of {@link ProjectScope}s.
 * Handles multi-project registration, classpath updates, configuration
 * changes, and feature toggle settings.
 */
public class ProjectScopeManager {
	private static final Logger logger = LoggerFactory.getLogger(ProjectScopeManager.class);
	private static final String KEY_GROOVY = "groovy";
	private static final String KEY_CLASSPATH = "classpath";
	private static final String KEY_ENABLED = "enabled";
	private static final String KEY_FORMATTING = "formatting";
	private static final String KEY_SEMANTIC_HIGHLIGHTING = "semanticHighlighting";
	private static final String KEY_ORGANIZE_IMPORTS = "organizeImports";

	public enum ClasspathUpdateResult {
		IGNORED_PROJECT_SCOPES,
		DEFERRED_IMPORT_IN_PROGRESS,
		UNCHANGED,
		UPDATED
	}

	private final AtomicReference<Path> workspaceRoot = new AtomicReference<>();

	// Default scope (used when no build-tool projects are registered, e.g. in tests)
	private final AtomicReference<ProjectScope> defaultScope = new AtomicReference<>();
	// Per-project scopes, sorted by path length desc for longest-prefix match.
	// Volatile reference to an effectively-immutable list; swapped atomically
	// in addProjects(). Reads are lock-free.
	private final AtomicReference<List<ProjectScope>> projectScopes =
			new AtomicReference<>(Collections.emptyList());
	// True while a background project import (Gradle/Maven) is in progress.
	// When true, didOpen/didChange/didClose skip compilation on the defaultScope
	// to avoid wrong diagnostics (no classpath, entire workspace scanned).
	private volatile boolean importInProgress;

	private volatile boolean semanticHighlightingEnabled = true;
	private volatile boolean formattingEnabled = true;
	private volatile boolean formattingOrganizeImportsEnabled = true;

	/**
	 * Tracks project roots whose classpath resolution is currently in-flight
	 * to prevent duplicate concurrent resolution requests for the same scope.
	 */
	private final Set<Path> resolutionInFlight = ConcurrentHashMap.newKeySet();

	/**
	 * Guards mutations to the {@link #projectScopes} list and
	 * {@link #defaultScope} reference. Held very briefly—only while
	 * swapping the list reference, not during compilation.
	 */
	private final Object scopesMutationLock = new Object();

	/**
	 * Cache for {@link #findProjectScope(URI)} results.  The URI → scope
	 * mapping is stable while the scope list is unchanged, so this avoids
	 * repeated linear scans on every LSP request (hover, completion,
	 * semantic tokens, inlay hints, etc.).
	 * <p>Invalidated whenever the scope list is mutated.</p>
	 */
	private final ConcurrentHashMap<URI, ProjectScope> scopeCache = new ConcurrentHashMap<>();

	private final FileContentsTracker fileContentsTracker;

	/** Supplier of language client — set after server connects. */
	private final AtomicReference<LanguageClient> languageClient = new AtomicReference<>();

	private final ScopeEvictionManager evictionManager;

	public ProjectScopeManager(ICompilationUnitFactory defaultFactory, FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
		this.evictionManager = new ScopeEvictionManager(projectScopes::get, fileContentsTracker);
		this.defaultScope.set(new ProjectScope(null, defaultFactory));
		// The default scope is not managed by a build tool, so its classpath
		// is always "resolved" (user-configured or empty).
		this.defaultScope.get().setClasspathResolved(true);
	}

	// --- Accessors ---

	public Path getWorkspaceRoot() {
		return workspaceRoot.get();
	}

	public void setWorkspaceRoot(Path workspaceRoot) {
		synchronized (scopesMutationLock) {
			this.workspaceRoot.set(workspaceRoot);
			ProjectScope ds = new ProjectScope(workspaceRoot, defaultScope.get().getCompilationUnitFactory());
			ds.setCompiled(false);
			ds.setFullyCompiled(false);
			ds.setClasspathResolved(true);
			this.defaultScope.set(ds);
			scopeCache.clear();
		}
	}

	public boolean isSemanticHighlightingEnabled() {
		return semanticHighlightingEnabled;
	}

	public boolean isFormattingEnabled() {
		return formattingEnabled;
	}

	public boolean isFormattingOrganizeImportsEnabled() {
		return formattingOrganizeImportsEnabled;
	}

	public boolean isImportInProgress() {
		return importInProgress;
	}

	public void setImportInProgress(boolean inProgress) {
		this.importInProgress = inProgress;
	}

	public void setLanguageClient(LanguageClient client) {
		this.languageClient.set(client);
	}

	public ProjectScope getDefaultScope() {
		return defaultScope.get();
	}

	public List<ProjectScope> getProjectScopes() {
		return projectScopes.get();
	}

	/**
	 * Returns a 3-element array: [activeScopes, evictedScopes, totalScopes].
	 * Active = compiled and not evicted. Thread-safe snapshot.
	 */
	public int[] getScopeCounts() {
		List<ProjectScope> scopes = projectScopes.get();
		int total = scopes.size();
		int active = 0;
		int evicted = 0;
		for (ProjectScope s : scopes) {
			if (s.isEvicted()) {
				evicted++;
			} else if (s.isCompiled()) {
				active++;
			}
		}
		return new int[] { active, evicted, total };
	}

	// --- Scope lookup ---

	/**
	 * Find the project scope that owns the given URI.
	 * Returns the default scope when no project scopes are registered (backward
	 * compat for tests). Returns null if projects are registered but the file
	 * doesn't belong to any of them.
	 */
	public ProjectScope findProjectScope(URI uri) {
		List<ProjectScope> scopes = projectScopes.get();
		if (scopes.isEmpty() || uri == null) {
			return defaultScope.get();
		}

		ProjectScope cached = scopeCache.get(uri);
		if (cached != null) {
			return cacheAndTouch(uri, cached, "cache-hit");
		}

		Path filePath = toFilePath(uri);
		ProjectScope resolved = resolveScopeForUri(uri, filePath, scopes);
		if (resolved != null) {
			return resolved;
		}

		logNoMatchingProjectScope(uri, scopes);
		return null;
	}

	private ProjectScope resolveScopeForUri(URI uri, Path filePath, List<ProjectScope> scopes) {
		if (filePath != null) {
			ProjectScope matched = findScopeByFilePath(filePath);
			if (matched != null) {
				return cacheAndTouch(uri, matched, "path-match");
			}
			return null;
		}

		ProjectScope virtualMatched = findScopeForVirtualUri(uri);
		if (virtualMatched != null) {
			return cacheAndTouch(uri, virtualMatched, "virtual-uri match");
		}

		if (scopes.size() == 1) {
			return cacheAndTouch(uri, scopes.get(0), "non-file fallback");
		}
		return null;
	}

	private ProjectScope cacheAndTouch(URI uri, ProjectScope scope, String reason) {
		scopeCache.put(uri, scope);
		scope.touchAccess();
		logger.debug("findProjectScope({}) {} -> {}", uri, reason, scope.getProjectRoot());
		return scope;
	}

	private void logNoMatchingProjectScope(URI uri, List<ProjectScope> scopes) {
		StringBuilder candidates = new StringBuilder();
		int limit = Math.min(scopes.size(), 5);
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				candidates.append(", ");
			}
			candidates.append(scopes.get(i).getProjectRoot());
		}
		logger.warn("findProjectScope({}) -> no matching project scope found. candidateRoots=[{}] totalRoots={}",
				uri, candidates, scopes.size());
	}

	private ProjectScope findScopeByFilePath(Path filePath) {
		for (ProjectScope scope : projectScopes.get()) {
			if (scope.getProjectRoot() != null && filePath.startsWith(scope.getProjectRoot())) {
				return scope;
			}
		}
		return null;
	}

	private ProjectScope findScopeForVirtualUri(URI uri) {
		if (!isJarUri(uri)) {
			return null;
		}

		String sourceJarName = extractSourceJarName(uri);
		if (sourceJarName == null || sourceJarName.isEmpty()) {
			return null;
		}
		String binaryJarName = sourceJarName.endsWith("-sources.jar")
				? sourceJarName.substring(0, sourceJarName.length() - "-sources.jar".length()) + ".jar"
				: sourceJarName;

		List<ProjectScope> candidates = new ArrayList<>();
		for (ProjectScope scope : projectScopes.get()) {
			if (hasMatchingClasspathJar(scope, sourceJarName, binaryJarName)) {
				candidates.add(scope);
			}
		}

		if (candidates.isEmpty()) {
			return null;
		}
		return candidates.stream()
				.max(Comparator.comparingLong(ProjectScope::getLastAccessedAt))
				.orElse(null);
	}

	private boolean isJarUri(URI uri) {
		return uri != null && "jar".equalsIgnoreCase(uri.getScheme());
	}

	private boolean hasMatchingClasspathJar(ProjectScope scope, String sourceJarName, String binaryJarName) {
		List<String> classpath = scope.getCompilationUnitFactory().getAdditionalClasspathList();
		if (classpath == null || classpath.isEmpty()) {
			return false;
		}
		for (String entry : classpath) {
			if (matchesJarName(entry, sourceJarName, binaryJarName)) {
				return true;
			}
		}
		return false;
	}

	private boolean matchesJarName(String classpathEntry, String sourceJarName, String binaryJarName) {
		String fileName = fileNameOfClasspathEntry(classpathEntry);
		return fileName != null
				&& (binaryJarName.equalsIgnoreCase(fileName) || sourceJarName.equalsIgnoreCase(fileName));
	}

	private String extractSourceJarName(URI uri) {
		String ssp = uri.getSchemeSpecificPart();
		if (ssp == null || ssp.isEmpty()) {
			return null;
		}
		String normalized = ssp;
		if (normalized.startsWith("//")) {
			normalized = normalized.substring(2);
		}
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		int slash = normalized.indexOf('/');
		if (slash <= 0) {
			return null;
		}
		return normalized.substring(0, slash);
	}

	private String fileNameOfClasspathEntry(String entry) {
		if (entry == null || entry.isEmpty()) {
			return null;
		}
		String normalized = entry.replace('\\', '/');
		int slash = normalized.lastIndexOf('/');
		if (slash >= 0 && slash + 1 < normalized.length()) {
			return normalized.substring(slash + 1);
		}
		return normalized;
	}

	private Path toFilePath(URI uri) {
		if (uri == null) {
			return null;
		}
		String scheme = uri.getScheme();
		if (isFileScheme(scheme)) {
			return pathFromUri(uri);
		}
		if (!"jar".equalsIgnoreCase(scheme)) {
			return null;
		}
		URI outerUri = extractOuterJarUri(uri);
		if (outerUri == null || !"file".equalsIgnoreCase(outerUri.getScheme())) {
			return null;
		}
		return pathFromUri(outerUri);
	}

	private boolean isFileScheme(String scheme) {
		return scheme == null || "file".equalsIgnoreCase(scheme);
	}

	private Path pathFromUri(URI uri) {
		try {
			return Paths.get(uri);
		} catch (Exception ignored) {
			return null;
		}
	}

	private URI extractOuterJarUri(URI jarUri) {
		String ssp = jarUri.getSchemeSpecificPart();
		if (ssp == null) {
			return null;
		}
		int separator = ssp.indexOf("!/");
		if (separator <= 0) {
			return null;
		}
		try {
			return URI.create(ssp.substring(0, separator));
		} catch (Exception ignored) {
			return null;
		}
	}

	/**
	 * Invalidate the {@link #findProjectScope(URI)} cache.  Must be called
	 * whenever the scope list is replaced or the workspace root changes.
	 */
	public void clearScopeCache() {
		scopeCache.clear();
	}

	/**
	 * Finds a project scope by its exact root path.
	 */
	public ProjectScope findProjectScopeByRoot(Path root) {
		for (ProjectScope scope : getAllScopes()) {
			if (root.equals(scope.getProjectRoot())) {
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
		List<ProjectScope> scopes = projectScopes.get();
		if (!scopes.isEmpty()) {
			return scopes;
		}
		return Collections.singletonList(defaultScope.get());
	}

	/**
	 * Returns true if the given scope should not yet be compiled because
	 * project discovery itself is still running.
	 * <p>
	 * This only gates during the <em>pre-discovery</em> phase (the default
	 * scope before any project scopes are registered and the background
	 * import thread is still running).  After discovery completes, scopes
	 * whose classpath has not been resolved yet are handled by the lazy
	 * resolution path in {@code didOpen()}/{@code didChange()} — they must
	 * <b>not</b> be blocked here, otherwise the coordinator's
	 * {@code requestResolution()} call is never reached.
	 */
	public boolean isImportPendingFor(ProjectScope scope) {
		return importInProgress && scope == defaultScope.get() && projectScopes.get().isEmpty();
	}

	/**
	 * Mark a project root as having classpath resolution in-flight.
	 * Returns {@code true} if the root was not already in-flight (i.e.
	 * the caller should proceed with resolution). Returns {@code false}
	 * if resolution is already in progress for this root.
	 */
	public boolean markResolutionStarted(Path projectRoot) {
		return resolutionInFlight.add(projectRoot);
	}

	/**
	 * Mark a project root's classpath resolution as complete.
	 */
	public void markResolutionComplete(Path projectRoot) {
		resolutionInFlight.remove(projectRoot);
	}

	/**
	 * Returns true if classpath resolution is currently in-flight for the
	 * given project root.
	 */
	public boolean isResolutionInFlight(Path projectRoot) {
		return resolutionInFlight.contains(projectRoot);
	}

	// --- Project registration ---

	/**
	 * Register discovered project roots immediately, <b>before</b> classpath
	 * resolution.  Each scope is created with an empty classpath and
	 * {@code classpathResolved = false}.
	 */
	public void registerDiscoveredProjects(List<Path> projectRoots) {
		invalidateWorkspaceLocalClasspathCaches(projectRoots);

		synchronized (scopesMutationLock) {
			logger.info("registerDiscoveredProjects called with {} projects", projectRoots.size());

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();
				factory.setProjectRoot(projectRoot);

				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				factory.setExcludedSubRoots(excludedRoots);

				newScopes.add(new ProjectScope(projectRoot, factory));
			}

			newScopes.sort((a, b) -> b.getProjectRoot().toString().length() - a.getProjectRoot().toString().length());
			projectScopes.set(Collections.unmodifiableList(newScopes));
			scopeCache.clear();
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
		updateProjectClasspaths(projectClasspaths, Collections.emptyMap());
	}

	/**
	 * Update existing project scopes with their resolved classpaths and
	 * optional Groovy version metadata.
	 */
	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths,
									 Map<Path, String> projectGroovyVersions) {
		updateProjectClasspaths(projectClasspaths, projectGroovyVersions, Collections.emptyMap());
	}

	/**
	 * Update existing project scopes with their resolved classpaths,
	 * optional Groovy version metadata and optional per-project resolved-state flags.
	 */
	public void updateProjectClasspaths(Map<Path, List<String>> projectClasspaths,
									 Map<Path, String> projectGroovyVersions,
									 Map<Path, Boolean> projectResolvedStates) {
		logger.info("updateProjectClasspaths called with {} projects", projectClasspaths.size());
		List<ProjectScope> scopes = projectScopes.get();

		for (ProjectScope scope : scopes) {
			List<String> classpath = projectClasspaths.get(scope.getProjectRoot());
			if (classpath == null) {
				continue;
			}
			applyProjectClasspathUpdate(scope, classpath,
					projectGroovyVersions.get(scope.getProjectRoot()),
					projectResolvedStates.getOrDefault(scope.getProjectRoot(), true));
		}
	}

	private void applyProjectClasspathUpdate(ProjectScope scope,
									List<String> classpath,
									String groovyVersion,
									boolean markResolved) {
		String detectedGroovyVersion = groovyVersion;
		if (detectedGroovyVersion == null || detectedGroovyVersion.isEmpty()) {
			detectedGroovyVersion = GroovyVersionDetector.detect(classpath).orElse(null);
		}
		if (scope.getProjectRoot() != null) {
			com.tomaszrup.groovyls.util.StaleClassFileCleaner
					.cleanClasspathEntries(scope.getProjectRoot(), classpath);
		}
		scope.getLock().writeLock().lock();
		try {
			scope.getCompilationUnitFactory().setAdditionalClasspathList(classpath);
			scope.setClasspathResolved(markResolved);
			scope.setDetectedGroovyVersion(detectedGroovyVersion);
			scope.updateSourceLocatorClasspath(classpath);
			JavadocResolver.clearCache();
			if (scope.isCompiled()) {
				logger.info("Forcing recompilation of {} with resolved classpath", scope.getProjectRoot());
				scope.setCompiled(false);
				scope.setFullyCompiled(false);
				scope.setCompilationUnit(null);
				scope.setAstVisitor(null);
			}
		} finally {
			scope.getLock().writeLock().unlock();
		}
		if (!markResolved) {
			logger.info("Applied classpath for {} but keeping scope unresolved", scope.getProjectRoot());
		}
	}

	/**
	 * Update a single project scope with its resolved classpath.
	 * Called by the lazy on-demand resolution path when a user opens a file
	 * in a project whose classpath wasn't yet resolved.
	 *
	 * @param projectRoot the project root
	 * @param classpath   the resolved classpath entries
	 * @return the updated scope, or {@code null} if no matching scope was found
	 */
	public ProjectScope updateProjectClasspath(Path projectRoot, List<String> classpath) {
		return updateProjectClasspath(projectRoot, classpath, null);
	}

	/**
	 * Update a single project scope with its resolved classpath and optional
	 * Groovy version metadata.
	 */
	public ProjectScope updateProjectClasspath(Path projectRoot, List<String> classpath, String groovyVersion) {
		return updateProjectClasspath(projectRoot, classpath, groovyVersion, true);
	}

	/**
	 * Update a single project scope with its resolved classpath and optional
	 * Groovy version metadata, with explicit control of classpath-resolved state.
	 */
	public ProjectScope updateProjectClasspath(Path projectRoot,
									 List<String> classpath,
									 String groovyVersion,
									 boolean markResolved) {
		ProjectScope scope = findProjectScopeByRoot(projectRoot);
		if (scope == null) {
			logger.warn("updateProjectClasspath: no scope found for {}", projectRoot);
			return null;
		}
		String detectedGroovyVersion = groovyVersion;
		if (detectedGroovyVersion == null || detectedGroovyVersion.isEmpty()) {
			detectedGroovyVersion = GroovyVersionDetector.detect(classpath).orElse(null);
		}
		// Clean stale .class files before applying the classpath
		if (projectRoot != null) {
			com.tomaszrup.groovyls.util.StaleClassFileCleaner
					.cleanClasspathEntries(projectRoot, classpath);
		}
		scope.getLock().writeLock().lock();
		try {
			scope.getCompilationUnitFactory().setAdditionalClasspathList(classpath);
			scope.setClasspathResolved(markResolved);
			scope.setDetectedGroovyVersion(detectedGroovyVersion);
			// Reset any previous OOM failure — the classpath change may
			// have reduced memory requirements (fewer deps) or the user
			// may have increased -Xmx.
			scope.resetCompilationFailed();
			// Index dependency source JARs for Go to Definition
			scope.updateSourceLocatorClasspath(classpath);
			JavadocResolver.clearCache();

			if (scope.isCompiled()) {
				logger.info("Forcing recompilation of {} with newly resolved classpath", projectRoot);
				scope.setCompiled(false);
				scope.setFullyCompiled(false);
				scope.setCompilationUnit(null);
				scope.setAstVisitor(null);
			}
		} finally {
			scope.getLock().writeLock().unlock();
		}
		logger.info("Updated classpath for project {} ({} entries, resolved={})",
				projectRoot, classpath.size(), markResolved);
		return scope;
	}

	/**
	 * Register all build-tool projects at once with their resolved classpaths.
	 *
	 * @return set of open URIs that need compilation
	 */
	public Set<URI> addProjects(Map<Path, List<String>> projectClasspaths) {
		return addProjects(projectClasspaths, Collections.emptyMap());
	}

	/**
	 * Register all build-tool projects at once with their resolved classpaths
	 * and optional Groovy version metadata.
	 */
	public Set<URI> addProjects(Map<Path, List<String>> projectClasspaths,
								 Map<Path, String> projectGroovyVersions) {
		invalidateWorkspaceLocalClasspathCaches(new ArrayList<>(projectClasspaths.keySet()));

		synchronized (scopesMutationLock) {
			logger.debug("addProjects called with {} projects", projectClasspaths.size());
			List<Path> projectRoots = new ArrayList<>(projectClasspaths.keySet());
			for (Path p : projectRoots) {
				logger.debug("  project root: {}, classpath entries: {}", p, projectClasspaths.get(p).size());
			}

			List<ProjectScope> newScopes = new ArrayList<>();
			for (Path projectRoot : projectRoots) {
				CompilationUnitFactory factory = new CompilationUnitFactory();
				factory.setProjectRoot(projectRoot);
				List<String> classpath = projectClasspaths.get(projectRoot);
				factory.setAdditionalClasspathList(classpath);

				List<Path> excludedRoots = new ArrayList<>();
				for (Path other : projectRoots) {
					if (!other.equals(projectRoot) && other.startsWith(projectRoot)) {
						excludedRoots.add(other);
					}
				}
				logger.debug("  Project {}: excluding {} subproject root(s): {}", projectRoot, excludedRoots.size(),
						excludedRoots);
				factory.setExcludedSubRoots(excludedRoots);

				ProjectScope scope = new ProjectScope(projectRoot, factory);
				scope.setClasspathResolved(true);
				String detectedGroovyVersion = projectGroovyVersions.get(projectRoot);
				if (detectedGroovyVersion == null || detectedGroovyVersion.isEmpty()) {
					detectedGroovyVersion = GroovyVersionDetector.detect(classpath).orElse(null);
				}
				scope.setDetectedGroovyVersion(detectedGroovyVersion);
				// Index dependency source JARs for Go to Definition
				scope.updateSourceLocatorClasspath(classpath);
				newScopes.add(scope);
			}

			newScopes.sort((a, b) -> b.getProjectRoot().toString().length() - a.getProjectRoot().toString().length());
			projectScopes.set(Collections.unmodifiableList(newScopes));
			scopeCache.clear();
			logger.info("Registered {} project scope(s)", newScopes.size());
		}

		clearDefaultScopeDiagnostics();
		return fileContentsTracker.getOpenURIs();
	}

	/**
	 * Workspace-local classes (project build outputs) may be stale across server
	 * restarts if shared caches are reused. Always evict cache entries rooted
	 * under discovered project paths so those classes are re-scanned on project
	 * open while still preserving cache hits for external dependency jars.
	 */
	private void invalidateWorkspaceLocalClasspathCaches(List<Path> projectRoots) {
		if (projectRoots == null || projectRoots.isEmpty()) {
			return;
		}
		for (Path projectRoot : projectRoots) {
			if (projectRoot == null) {
				continue;
			}
			SharedClasspathIndexCache.getInstance().invalidateEntriesUnderProject(projectRoot);
			SharedClassGraphCache.getInstance().invalidateEntriesUnderProject(projectRoot);
		}
	}

	// --- Configuration ---

	public void updateFeatureToggles(JsonObject settings) {
		if (!settings.has(KEY_GROOVY) || !settings.get(KEY_GROOVY).isJsonObject()) {
			return;
		}
		JsonObject groovy = settings.get(KEY_GROOVY).getAsJsonObject();
		if (groovy.has(KEY_SEMANTIC_HIGHLIGHTING) && groovy.get(KEY_SEMANTIC_HIGHLIGHTING).isJsonObject()) {
			JsonObject sh = groovy.get(KEY_SEMANTIC_HIGHLIGHTING).getAsJsonObject();
			if (sh.has(KEY_ENABLED) && sh.get(KEY_ENABLED).isJsonPrimitive()) {
				this.semanticHighlightingEnabled = sh.get(KEY_ENABLED).getAsBoolean();
			}
		}
		if (groovy.has(KEY_FORMATTING) && groovy.get(KEY_FORMATTING).isJsonObject()) {
			JsonObject fmt = groovy.get(KEY_FORMATTING).getAsJsonObject();
			if (fmt.has(KEY_ENABLED) && fmt.get(KEY_ENABLED).isJsonPrimitive()) {
				this.formattingEnabled = fmt.get(KEY_ENABLED).getAsBoolean();
			}
			if (fmt.has(KEY_ORGANIZE_IMPORTS) && fmt.get(KEY_ORGANIZE_IMPORTS).isJsonPrimitive()) {
				this.formattingOrganizeImportsEnabled = fmt.get(KEY_ORGANIZE_IMPORTS).getAsBoolean();
			}
		}
	}

	/**
	 * Parse classpath entries from the JSON settings object and delegate
	 * to {@link #updateClasspath(List)}.
	 */
	public ClasspathUpdateResult updateClasspathFromSettings(JsonObject settings) {
		List<String> classpathList = new ArrayList<>();

		if (settings.has(KEY_GROOVY) && settings.get(KEY_GROOVY).isJsonObject()) {
			JsonObject groovy = settings.get(KEY_GROOVY).getAsJsonObject();
			if (groovy.has(KEY_CLASSPATH) && groovy.get(KEY_CLASSPATH).isJsonArray()) {
				JsonArray classpath = groovy.get(KEY_CLASSPATH).getAsJsonArray();
				classpath.forEach(element -> classpathList.add(element.getAsString()));
			}
		}

		return updateClasspath(classpathList);
	}

	/**
	 * Update the default scope's classpath (used when no build-tool projects
	 * are registered).
	 */
	public ClasspathUpdateResult updateClasspath(List<String> classpathList) {
		List<ProjectScope> scopes = projectScopes.get();
		if (!scopes.isEmpty()) {
			logger.debug("updateClasspath() ignored — {} project scope(s) are active", scopes.size());
			return ClasspathUpdateResult.IGNORED_PROJECT_SCOPES;
		}
		if (importInProgress) {
			logger.info("updateClasspath() deferred — project import in progress");
			defaultScope.get().getCompilationUnitFactory().setAdditionalClasspathList(classpathList);
			return ClasspathUpdateResult.DEFERRED_IMPORT_IN_PROGRESS;
		}
		// Store the classpath but don't compile — the caller (CompilationService)
		// handles compilation when needed.
		ProjectScope ds = defaultScope.get();
		if (classpathList.equals(ds.getCompilationUnitFactory().getAdditionalClasspathList())) {
			return ClasspathUpdateResult.UNCHANGED;
		}
		ds.getCompilationUnitFactory().setAdditionalClasspathList(classpathList);
		// Mark as needing recompilation
		ds.setCompiled(false);
		ds.setFullyCompiled(false);
		return ClasspathUpdateResult.UPDATED;
	}

	/**
	 * Returns true if the default scope classpath actually changed.
	 */
	public boolean hasClasspathChanged(List<String> classpathList) {
		return !classpathList.equals(defaultScope.get().getCompilationUnitFactory().getAdditionalClasspathList());
	}

	// --- Diagnostics helpers ---

	// --- Eviction (delegated to ScopeEvictionManager) ---

	public void setScopeEvictionTTLSeconds(long ttlSeconds) {
		evictionManager.setScopeEvictionTTLSeconds(ttlSeconds);
	}

	public long getScopeEvictionTTLSeconds() {
		return evictionManager.getScopeEvictionTTLSeconds();
	}

	public void setMemoryPressureThreshold(double threshold) {
		evictionManager.setMemoryPressureThreshold(threshold);
	}

	public double getMemoryPressureThreshold() {
		return evictionManager.getMemoryPressureThreshold();
	}

	public void startEvictionScheduler(ScheduledExecutorService schedulingPool) {
		evictionManager.startEvictionScheduler(schedulingPool);
	}

	public void stopEvictionScheduler() {
		evictionManager.stopEvictionScheduler();
	}

	// --- Diagnostics helpers ---

	/**
	 * Publishes empty diagnostics for every file that defaultScope previously
	 * reported errors on, so the client clears them.
	 */
	public void clearDefaultScopeDiagnostics() {
		ProjectScope ds = defaultScope.get();
		LanguageClient client = languageClient.get();
		if (ds.getPrevDiagnosticsByFile() != null && client != null) {
			for (URI uri : ds.getPrevDiagnosticsByFile().keySet()) {
				client.publishDiagnostics(
						new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
			}
			ds.setPrevDiagnosticsByFile(null);
		}
	}
}
