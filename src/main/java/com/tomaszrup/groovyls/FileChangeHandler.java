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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.SharedClassGraphCache;
import com.tomaszrup.groovyls.compiler.SharedClasspathIndexCache;
import com.tomaszrup.groovyls.util.MdcProjectContext;

/**
 * Handles file-system change events (didChangeWatchedFiles) and coordinates
 * debounced Java/build-file recompilation across project scopes.
 */
public class FileChangeHandler {
	private static final Logger logger = LoggerFactory.getLogger(FileChangeHandler.class);

	/** Debounce delay for Java/build-file recompile triggers (milliseconds). */
	private static final long JAVA_RECOMPILE_DEBOUNCE_MS = 2000;
	private static final String JAVA_EXTENSION = ".java";

	/**
	 * Common build output directory names. {@code .java} files under these
	 * directories (relative to a project root) are ignored when deciding
	 * whether to trigger a Java recompile, preventing feedback loops with
	 * annotation processors and source generators.
	 */
	private static final Set<String> BUILD_OUTPUT_DIRS = Set.of(
			"build", "target", ".gradle", "out", "bin");

	private final ProjectScopeManager scopeManager;
	private final CompilationService compilationService;
	private final ScheduledExecutorService javaRecompileExecutor;

	/** Per-project debounce futures for Java/build-file recompiles. */
	private final ConcurrentHashMap<Path, ScheduledFuture<?>> pendingJavaRecompiles = new ConcurrentHashMap<>();

	/**
	 * Callback interface for notifying about Java or build-tool file changes
	 * that require recompilation of a project.
	 */
	public interface JavaChangeListener {
		void onJavaFilesChanged(Path projectRoot);
	}

	/**
	 * Callback interface for Java class move detection used to rewrite
	 * Groovy imports (old FQCN -> new FQCN).
	 */
	public interface JavaImportMoveListener {
		void onJavaImportsMoved(Path projectRoot, Map<String, String> movedImports);
	}

	private JavaChangeListener javaChangeListener;
	private JavaImportMoveListener javaImportMoveListener;

	public FileChangeHandler(ProjectScopeManager scopeManager, CompilationService compilationService,
			ScheduledExecutorService javaRecompileExecutor) {
		this.scopeManager = scopeManager;
		this.compilationService = compilationService;
		this.javaRecompileExecutor = javaRecompileExecutor;
	}

	public void setJavaChangeListener(JavaChangeListener listener) {
		this.javaChangeListener = listener;
	}

	public void setJavaImportMoveListener(JavaImportMoveListener listener) {
		this.javaImportMoveListener = listener;
	}

	/**
	 * Handles didChangeWatchedFiles notifications: detects Java/build-file
	 * changes, schedules recompiles, refreshes Java source indices, and
	 * processes Groovy file changes per-scope.
	 */
	public void handleDidChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		logWatchedChanges(params);

		Set<URI> allChangedUris = collectChangedUris(params);
		compilationService.getFileContentsTracker().invalidateClosedFileCache(allChangedUris);

		List<ProjectScope> scopes = scopeManager.getProjectScopes();
		Map<Path, Map<String, String>> javaImportMovesByProject = detectJavaImportMoves(params.getChanges(), scopes);
		logDetectedJavaImportMoves(javaImportMovesByProject);

		Set<Path> projectsNeedingRecompile = findProjectsNeedingRecompile(allChangedUris, scopes);
		handleProjectRecompiles(projectsNeedingRecompile, javaImportMovesByProject);

		refreshJavaSourceIndices(allChangedUris);
		processScopeChanges(allChangedUris, scopes, projectsNeedingRecompile);
	}

	private void logWatchedChanges(DidChangeWatchedFilesParams params) {
		if (!logger.isDebugEnabled()) {
			return;
		}
		for (org.eclipse.lsp4j.FileEvent event : params.getChanges()) {
			logger.debug("watcherTrace eventType={} uri={}", event.getType(), event.getUri());
		}
	}

	private Set<URI> collectChangedUris(DidChangeWatchedFilesParams params) {
		return params.getChanges().stream()
				.map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());
	}

	private void logDetectedJavaImportMoves(Map<Path, Map<String, String>> javaImportMovesByProject) {
		if (!logger.isDebugEnabled() || javaImportMovesByProject.isEmpty()) {
			return;
		}
		for (Map.Entry<Path, Map<String, String>> e : javaImportMovesByProject.entrySet()) {
			logger.debug("watcherTrace javaMoves projectRoot={} moves={}", e.getKey(), e.getValue());
		}
	}

	private Set<Path> findProjectsNeedingRecompile(Set<URI> allChangedUris, List<ProjectScope> scopes) {
		Set<Path> projectsNeedingRecompile = new LinkedHashSet<>();
		for (URI changedUri : allChangedUris) {
			if (isRecompileTriggerPath(changedUri.getPath())) {
				Path filePath = Paths.get(changedUri);
				Path projectRoot = findProjectRootForJavaFile(filePath, scopes);
				if (projectRoot != null) {
					if (isBuildOutputJavaFile(changedUri.getPath(), filePath, projectRoot)) {
						logger.debug("Ignoring build-output Java file: {}", filePath);
					} else {
						projectsNeedingRecompile.add(projectRoot);
					}
				}
			}
		}
		return projectsNeedingRecompile;
	}

	private boolean isRecompileTriggerPath(String path) {
		return path != null && (path.endsWith(JAVA_EXTENSION)
				|| path.endsWith("build.gradle")
				|| path.endsWith("build.gradle.kts")
				|| path.endsWith("pom.xml"));
	}

	private boolean isBuildOutputJavaFile(String uriPath, Path filePath, Path projectRoot) {
		return uriPath != null && uriPath.endsWith(JAVA_EXTENSION) && isBuildOutputFile(filePath, projectRoot);
	}

	private void handleProjectRecompiles(Set<Path> projectsNeedingRecompile,
			Map<Path, Map<String, String>> javaImportMovesByProject) {
		if (projectsNeedingRecompile.isEmpty()) {
			return;
		}
		notifyImportMoves(javaImportMovesByProject);
		for (Path projectRoot : projectsNeedingRecompile) {
			invalidateProjectClasspathCaches(projectRoot);
			invalidateScopeCompilationUnit(projectRoot);
			scheduleJavaRecompile(projectRoot);
		}
	}

	private void notifyImportMoves(Map<Path, Map<String, String>> javaImportMovesByProject) {
		if (javaImportMoveListener == null) {
			return;
		}
		for (Map.Entry<Path, Map<String, String>> entry : javaImportMovesByProject.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				javaImportMoveListener.onJavaImportsMoved(entry.getKey(), entry.getValue());
			}
		}
	}

	private void invalidateScopeCompilationUnit(Path projectRoot) {
		ProjectScope scope = scopeManager.findProjectScopeByRoot(projectRoot);
		if (scope == null) {
			return;
		}
		scope.getLock().writeLock().lock();
		try {
			scope.getCompilationUnitFactory().invalidateCompilationUnitFull();
		} finally {
			scope.getLock().writeLock().unlock();
		}
	}

	private void refreshJavaSourceIndices(Set<URI> allChangedUris) {
		for (URI changedUri : allChangedUris) {
			String uriPath = changedUri.getPath();
			if (uriPath == null || !uriPath.endsWith(JAVA_EXTENSION)) {
				continue;
			}
			refreshJavaSourceIndexForUri(changedUri);
		}
	}

	private void refreshJavaSourceIndexForUri(URI changedUri) {
		Path filePath = Paths.get(changedUri);
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			Path root = scope.getProjectRoot();
			if (root == null || scope.getJavaSourceLocator() == null) {
				continue;
			}
			if (filePath.startsWith(root) && !isBuildOutputFile(filePath, root)) {
				scope.getJavaSourceLocator().refresh();
				return;
			}
		}
	}

	private void processScopeChanges(Set<URI> allChangedUris, List<ProjectScope> capturedScopes,
			Set<Path> projectsNeedingRecompile) {
		List<ProjectScope> scopesToProcess = capturedScopes.isEmpty() ? scopeManager.getAllScopes() : capturedScopes;
		boolean noProjectScopes = capturedScopes.isEmpty();

		for (ProjectScope scope : scopesToProcess) {
			if (shouldSkipScopeChangeProcessing(scope, projectsNeedingRecompile)) {
				continue;
			}
			Set<URI> scopeUris = resolveScopeUris(scope, allChangedUris, noProjectScopes);
			if (!scopeUris.isEmpty()) {
				processScopeUris(scope, scopeUris);
			}
		}
	}

	private boolean shouldSkipScopeChangeProcessing(ProjectScope scope, Set<Path> projectsNeedingRecompile) {
		boolean handledByRecompile = projectsNeedingRecompile.contains(scope.getProjectRoot());
		boolean unresolvedClasspath = !scope.isClasspathResolved() && scope.getProjectRoot() != null;
		if (unresolvedClasspath) {
			logger.debug("Skipping watcher-triggered compile for {} — classpath not yet resolved",
					scope.getProjectRoot());
		}
		return handledByRecompile || unresolvedClasspath;
	}

	private Set<URI> resolveScopeUris(ProjectScope scope, Set<URI> allChangedUris, boolean noProjectScopes) {
		if (noProjectScopes) {
			return allChangedUris;
		}
		Path projectRoot = scope.getProjectRoot();
		if (projectRoot == null) {
			return java.util.Collections.emptySet();
		}
		return allChangedUris.stream()
				.filter(uri -> isUriInsideScope(projectRoot, uri))
				.collect(Collectors.toSet());
	}

	private boolean isUriInsideScope(Path projectRoot, URI uri) {
		Path filePath = Paths.get(uri);
		if (!filePath.startsWith(projectRoot)) {
			return false;
		}
		String path = uri.getPath();
		if (path != null && path.endsWith(JAVA_EXTENSION) && isBuildOutputFile(filePath, projectRoot)) {
			logger.debug("Ignoring build-output file in watched-files processing: {}", filePath);
			return false;
		}
		return true;
	}

	private void processScopeUris(ProjectScope scope, Set<URI> scopeUris) {
		MdcProjectContext.setProject(scope.getProjectRoot());
		scope.getLock().writeLock().lock();
		try {
			scope.getCompilationUnitFactory().invalidateFileCache();
			removeDeletedGroovyFiles(scope, scopeUris);
			recompileScopeForUris(scope, scopeUris);
		} finally {
			scope.getLock().writeLock().unlock();
		}
	}

	private void removeDeletedGroovyFiles(ProjectScope scope, Set<URI> scopeUris) {
		for (URI changedUri : scopeUris) {
			String uriPath = changedUri.getPath();
			if (uriPath == null || !uriPath.endsWith(".groovy")) {
				continue;
			}
			try {
				if (!Files.exists(Paths.get(changedUri))) {
					scope.getDependencyGraph().removeFile(changedUri);
				}
			} catch (Exception e) {
				// ignore URIs that can't be converted to Path
			}
		}
	}

	private void recompileScopeForUris(ProjectScope scope, Set<URI> scopeUris) {
		boolean didFullCompile = compilationService.ensureScopeCompiled(scope);
		if (didFullCompile) {
			return;
		}
		boolean isSameUnit = compilationService.createOrUpdateCompilationUnit(scope, scopeUris);
		compilationService.resetChangedFilesForScope(scope);
		Set<URI> errorURIs = compilationService.compile(scope);
		Set<URI> astUris = isSameUnit ? scopeUris : java.util.Collections.emptySet();
		compilationService.visitAST(scope, astUris, errorURIs);
		compilationService.updateDependencyGraph(scope, scopeUris);
	}

	/**
	 * Immediately evict per-scope and shared classpath indexes for a project
	 * when Java/build files change. This prevents stale import suggestions from
	 * old package locations while debounced recompilation is pending.
	 */
	private void invalidateProjectClasspathCaches(Path projectRoot) {
		ProjectScope scope = scopeManager.findProjectScopeByRoot(projectRoot);
		if (scope != null) {
			scope.getLock().writeLock().lock();
			try {
				ScanResult oldScan = scope.getClassGraphScanResult();
				if (oldScan != null) {
					SharedClassGraphCache.getInstance().release(oldScan);
				}
				scope.setClassGraphScanResult(null);
				scope.clearClasspathIndexes();
			} finally {
				scope.getLock().writeLock().unlock();
			}
		}

		SharedClasspathIndexCache.getInstance().invalidateEntriesUnderProject(projectRoot);
		SharedClassGraphCache.getInstance().invalidateEntriesUnderProject(projectRoot);
	}

	private Map<Path, Map<String, String>> detectJavaImportMoves(List<org.eclipse.lsp4j.FileEvent> events,
			List<ProjectScope> scopes) {
		Map<Path, List<Path>> deletedByProject = new HashMap<>();
		Map<Path, List<Path>> createdByProject = new HashMap<>();

		for (org.eclipse.lsp4j.FileEvent event : events) {
			handleJavaMoveEvent(event, scopes, deletedByProject, createdByProject);
		}

		Map<Path, Map<String, String>> movesByProject = new HashMap<>();
		for (Path projectRoot : deletedByProject.keySet()) {
			List<Path> deleted = deletedByProject.getOrDefault(projectRoot, java.util.Collections.emptyList());
			List<Path> created = createdByProject.getOrDefault(projectRoot, java.util.Collections.emptyList());
			Map<String, String> moved = pairMovedClasses(projectRoot, deleted, created);
			if (!moved.isEmpty()) {
				movesByProject.put(projectRoot, moved);
			}
		}
		return movesByProject;
	}

	private void handleJavaMoveEvent(
			org.eclipse.lsp4j.FileEvent event,
			List<ProjectScope> scopes,
			Map<Path, List<Path>> deletedByProject,
			Map<Path, List<Path>> createdByProject) {
		URI uri = URI.create(event.getUri());
		String uriPath = uri.getPath();
		if (uriPath == null || !uriPath.endsWith(JAVA_EXTENSION)) {
			return;
		}

		Path filePath = toPathOrNull(uri);
		if (filePath == null) {
			return;
		}

		Path projectRoot = findProjectRootForJavaFile(filePath, scopes);
		if (projectRoot == null || isBuildOutputFile(filePath, projectRoot)) {
			return;
		}

		if (event.getType() == org.eclipse.lsp4j.FileChangeType.Deleted) {
			deletedByProject.computeIfAbsent(projectRoot, pr -> new ArrayList<>()).add(filePath);
		} else if (event.getType() == org.eclipse.lsp4j.FileChangeType.Created) {
			createdByProject.computeIfAbsent(projectRoot, pr -> new ArrayList<>()).add(filePath);
		}
	}

	private Path toPathOrNull(URI uri) {
		try {
			return Paths.get(uri);
		} catch (Exception e) {
			return null;
		}
	}

	private Path findProjectRootForJavaFile(Path filePath, List<ProjectScope> scopes) {
		for (ProjectScope scope : scopes) {
			Path projectRoot = scope.getProjectRoot();
			if (projectRoot != null && filePath.startsWith(projectRoot)) {
				return projectRoot;
			}
		}
		return null;
	}

	private Map<String, String> pairMovedClasses(Path projectRoot, List<Path> deletedJavaFiles,
			List<Path> createdJavaFiles) {
		Map<String, Deque<Path>> createdBySimpleName = new HashMap<>();
		for (Path created : createdJavaFiles) {
			createdBySimpleName
					.computeIfAbsent(created.getFileName().toString(), key -> new ArrayDeque<>())
					.add(created);
		}

		Map<String, String> moved = new HashMap<>();
		for (Path deleted : deletedJavaFiles) {
			String simpleName = deleted.getFileName().toString();
			Deque<Path> createdCandidates = createdBySimpleName.get(simpleName);
			if (createdCandidates == null || createdCandidates.isEmpty()) {
				continue;
			}
			Path created = createdCandidates.removeFirst();
			String oldFqcn = javaPathToFqcn(projectRoot, deleted);
			String newFqcn = javaPathToFqcn(projectRoot, created);
			if (oldFqcn != null && newFqcn != null && !oldFqcn.equals(newFqcn)) {
				moved.put(oldFqcn, newFqcn);
			}
		}
		return moved;
	}

	private String javaPathToFqcn(Path projectRoot, Path javaFilePath) {
		try {
			Path relative = projectRoot.relativize(javaFilePath);
			int startIndex = findJavaSegmentStart(relative);
			if (startIndex < 0 || startIndex >= relative.getNameCount()) {
				return null;
			}
			StringBuilder fqcn = new StringBuilder();
			for (int i = startIndex; i < relative.getNameCount(); i++) {
				String segment = normalizeFqcnSegment(relative.getName(i).toString(),
						i == relative.getNameCount() - 1);
				if (segment == null || segment.isEmpty()) {
					return null;
				}
				appendFqcnSegment(fqcn, segment);
			}
			return fqcn.toString();
		} catch (Exception e) {
			return null;
		}
	}

	private int findJavaSegmentStart(Path relativePath) {
		for (int i = 0; i < relativePath.getNameCount(); i++) {
			if ("java".equals(relativePath.getName(i).toString())) {
				return i + 1;
			}
		}
		return -1;
	}

	private String normalizeFqcnSegment(String segment, boolean lastSegment) {
		if (!lastSegment) {
			return segment;
		}
		if (!segment.endsWith(JAVA_EXTENSION)) {
			return null;
		}
		return segment.substring(0, segment.length() - JAVA_EXTENSION.length());
	}

	private void appendFqcnSegment(StringBuilder fqcn, String segment) {
		if (fqcn.length() > 0) {
			fqcn.append('.');
		}
		fqcn.append(segment);
	}

	/**
	 * Returns {@code true} if the given file path is inside a build output
	 * directory relative to the project root.
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
	 * given project root.
	 */
	private void scheduleJavaRecompile(Path projectRoot) {
		ScheduledFuture<?> prev = pendingJavaRecompiles.get(projectRoot);
		if (prev != null) {
			prev.cancel(false);
		}
		MdcProjectContext.setProject(projectRoot);
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
	 * Executes the full Java recompile flow for a project.
	 */
	private void executeJavaRecompile(Path projectRoot) {
		MdcProjectContext.setProject(projectRoot);
		logger.info("Java/build files changed in {}, triggering recompile", projectRoot);

		// Clean stale .class files whose source has been deleted, BEFORE
		// notifying listeners or recompiling, so the Groovy classloader
		// won't resolve deleted classes from leftover build output.
		com.tomaszrup.groovyls.util.StaleClassFileCleaner.cleanProject(projectRoot);

		if (javaChangeListener != null) {
			javaChangeListener.onJavaFilesChanged(projectRoot);
		}

		ProjectScope scope = scopeManager.findProjectScopeByRoot(projectRoot);
		if (scope == null) {
			logger.warn("No scope found for project root after Java recompile: {}", projectRoot);
			return;
		}

		scope.getLock().writeLock().lock();
		try {
			compilationService.recompileAfterJavaChange(scope);
		} finally {
			scope.getLock().writeLock().unlock();
		}
	}


}
