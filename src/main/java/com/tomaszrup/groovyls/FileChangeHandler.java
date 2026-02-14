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
		if (logger.isDebugEnabled()) {
			for (org.eclipse.lsp4j.FileEvent event : params.getChanges()) {
				logger.debug("watcherTrace eventType={} uri={}", event.getType(), event.getUri());
			}
		}

		Set<URI> allChangedUris = params.getChanges().stream()
				.map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());

		// Invalidate closed-file cache entries for all changed URIs so that
		// subsequent getContents() calls read fresh content from disk.
		compilationService.getFileContentsTracker().invalidateClosedFileCache(allChangedUris);

		// Detect Java/build-tool file changes that require recompilation.
		Set<Path> projectsNeedingRecompile = new LinkedHashSet<>();
		List<ProjectScope> scopes = scopeManager.getProjectScopes(); // volatile read once
		Map<Path, Map<String, String>> javaImportMovesByProject =
				detectJavaImportMoves(params.getChanges(), scopes);
		if (logger.isDebugEnabled() && !javaImportMovesByProject.isEmpty()) {
			for (Map.Entry<Path, Map<String, String>> e : javaImportMovesByProject.entrySet()) {
				logger.debug("watcherTrace javaMoves projectRoot={} moves={}", e.getKey(), e.getValue());
			}
		}
		for (URI changedUri : allChangedUris) {
			String path = changedUri.getPath();
			if (path != null && (path.endsWith(".java")
					|| path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")
					|| path.endsWith("pom.xml"))) {
				Path filePath = Paths.get(changedUri);
				for (ProjectScope scope : scopes) {
					if (scope.getProjectRoot() != null && filePath.startsWith(scope.getProjectRoot())) {
						if (path.endsWith(".java") && isBuildOutputFile(filePath, scope.getProjectRoot())) {
							logger.debug("Ignoring build-output Java file: {}", filePath);
							break;
						}
						projectsNeedingRecompile.add(scope.getProjectRoot());
						break;
					}
				}
			}
		}

		// Schedule debounced async recompile for affected projects.
		if (!projectsNeedingRecompile.isEmpty()) {
			if (javaImportMoveListener != null) {
				for (Map.Entry<Path, Map<String, String>> entry : javaImportMovesByProject.entrySet()) {
					if (!entry.getValue().isEmpty()) {
						javaImportMoveListener.onJavaImportsMoved(entry.getKey(), entry.getValue());
					}
				}
			}
			for (Path projectRoot : projectsNeedingRecompile) {
				invalidateProjectClasspathCaches(projectRoot);

				// Immediately invalidate classloader + compilation unit to ensure
				// any stale compiled classes are dropped before the next compile.
				ProjectScope scope = scopeManager.findProjectScopeByRoot(projectRoot);
				if (scope != null) {
					scope.getLock().writeLock().lock();
					try {
						scope.getCompilationUnitFactory().invalidateCompilationUnitFull();
					} finally {
						scope.getLock().writeLock().unlock();
					}
				}

				scheduleJavaRecompile(projectRoot);
			}
		}

		// Refresh Java source index for projects with Java file changes
		for (URI changedUri : allChangedUris) {
			String uriPath = changedUri.getPath();
			if (uriPath != null && uriPath.endsWith(".java")) {
				for (ProjectScope scope : scopeManager.getAllScopes()) {
					if (scope.getProjectRoot() != null && scope.getJavaSourceLocator() != null) {
						Path filePath = Paths.get(changedUri);
						if (filePath.startsWith(scope.getProjectRoot())
								&& !isBuildOutputFile(filePath, scope.getProjectRoot())) {
							scope.getJavaSourceLocator().refresh();
							break;
						}
					}
				}
			}
		}

		// Process each non-recompile scope independently under its own lock.
		// Use the SAME scope list captured above to avoid a race where
		// registerDiscoveredProjects() populates projectScopes between the
		// isEmpty() check and the iteration, causing all changed URIs to be
		// assigned to every newly-registered scope.
		List<ProjectScope> scopesToProcess = scopes.isEmpty()
				? scopeManager.getAllScopes()
				: scopes;
		boolean noProjectScopes = scopes.isEmpty();

		for (ProjectScope scope : scopesToProcess) {
			if (projectsNeedingRecompile.contains(scope.getProjectRoot())) {
				continue; // handled by scheduleJavaRecompile
			}
			// Skip scopes whose classpath hasn't been resolved yet — compiling
			// them would produce thousands of false-positive diagnostics.
			if (!scope.isClasspathResolved() && scope.getProjectRoot() != null) {
				logger.debug("Skipping watcher-triggered compile for {} — classpath not yet resolved",
						scope.getProjectRoot());
				continue;
			}

			Set<URI> scopeUris;
			if (noProjectScopes) {
				scopeUris = allChangedUris;
			} else {
				scopeUris = allChangedUris.stream()
						.filter(uri -> {
							if (scope.getProjectRoot() == null) return false;
							Path fp = Paths.get(uri);
							if (!fp.startsWith(scope.getProjectRoot())) return false;
							String p = uri.getPath();
							if (p != null && p.endsWith(".java")
									&& isBuildOutputFile(fp, scope.getProjectRoot())) {
								logger.debug("Ignoring build-output file in watched-files processing: {}", fp);
								return false;
							}
							return true;
						})
						.collect(Collectors.toSet());
			}
			if (!scopeUris.isEmpty()) {
				MdcProjectContext.setProject(scope.getProjectRoot());
				scope.getLock().writeLock().lock();
				try {
					scope.getCompilationUnitFactory().invalidateFileCache();

					// Handle .groovy file deletions: remove from dependency graph
					for (URI changedUri : scopeUris) {
						String uriPath = changedUri.getPath();
						if (uriPath != null && uriPath.endsWith(".groovy")) {
							try {
								if (!Files.exists(Paths.get(changedUri))) {
									scope.getDependencyGraph().removeFile(changedUri);
								}
							} catch (Exception e) {
								// ignore URIs that can't be converted to Path
							}
						}
					}
					// If the scope hasn't been compiled yet, do a full compile.
					// Otherwise, update the compilation unit and recompile.
					boolean didFullCompile = compilationService.ensureScopeCompiled(scope);
					if (!didFullCompile) {
						boolean isSameUnit = compilationService.createOrUpdateCompilationUnit(scope, scopeUris);
						compilationService.resetChangedFilesForScope(scope);
						Set<URI> errorURIs = compilationService.compile(scope);
						if (isSameUnit) {
							compilationService.visitAST(scope, scopeUris, errorURIs);
						} else {
							compilationService.visitAST(scope, java.util.Collections.emptySet(), errorURIs);
						}
						compilationService.updateDependencyGraph(scope, scopeUris);
					}
				} finally {
					scope.getLock().writeLock().unlock();
				}
			}
		}
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
			URI uri = URI.create(event.getUri());
			String uriPath = uri.getPath();
			if (uriPath == null || !uriPath.endsWith(".java")) {
				continue;
			}
			Path filePath;
			try {
				filePath = Paths.get(uri);
			} catch (Exception e) {
				continue;
			}
			Path projectRoot = findProjectRootForJavaFile(filePath, scopes);
			if (projectRoot == null || isBuildOutputFile(filePath, projectRoot)) {
				continue;
			}

			if (event.getType() == org.eclipse.lsp4j.FileChangeType.Deleted) {
				deletedByProject.computeIfAbsent(projectRoot, pr -> new ArrayList<>()).add(filePath);
			} else if (event.getType() == org.eclipse.lsp4j.FileChangeType.Created) {
				createdByProject.computeIfAbsent(projectRoot, pr -> new ArrayList<>()).add(filePath);
			}
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
			int startIndex = -1;
			for (int i = 0; i < relative.getNameCount(); i++) {
				if ("java".equals(relative.getName(i).toString())) {
					startIndex = i + 1;
					break;
				}
			}
			if (startIndex < 0 || startIndex >= relative.getNameCount()) {
				return null;
			}
			StringBuilder fqcn = new StringBuilder();
			for (int i = startIndex; i < relative.getNameCount(); i++) {
				String segment = relative.getName(i).toString();
				if (i == relative.getNameCount() - 1) {
					if (!segment.endsWith(".java")) {
						return null;
					}
					segment = segment.substring(0, segment.length() - ".java".length());
				}
				if (segment.isEmpty()) {
					return null;
				}
				if (fqcn.length() > 0) {
					fqcn.append('.');
				}
				fqcn.append(segment);
			}
			return fqcn.toString();
		} catch (Exception e) {
			return null;
		}
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
