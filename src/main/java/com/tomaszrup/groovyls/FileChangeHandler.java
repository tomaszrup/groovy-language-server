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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private JavaChangeListener javaChangeListener;

	public FileChangeHandler(ProjectScopeManager scopeManager, CompilationService compilationService,
			ScheduledExecutorService javaRecompileExecutor) {
		this.scopeManager = scopeManager;
		this.compilationService = compilationService;
		this.javaRecompileExecutor = javaRecompileExecutor;
	}

	public void setJavaChangeListener(JavaChangeListener listener) {
		this.javaChangeListener = listener;
	}

	/**
	 * Handles didChangeWatchedFiles notifications: detects Java/build-file
	 * changes, schedules recompiles, refreshes Java source indices, and
	 * processes Groovy file changes per-scope.
	 */
	public void handleDidChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		Set<URI> allChangedUris = params.getChanges().stream()
				.map(fileEvent -> URI.create(fileEvent.getUri()))
				.collect(Collectors.toSet());

		// Invalidate closed-file cache entries for all changed URIs so that
		// subsequent getContents() calls read fresh content from disk.
		compilationService.getFileContentsTracker().invalidateClosedFileCache(allChangedUris);

		// Detect Java/build-tool file changes that require recompilation.
		Set<Path> projectsNeedingRecompile = new LinkedHashSet<>();
		List<ProjectScope> scopes = scopeManager.getProjectScopes(); // volatile read once
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
			for (Path projectRoot : projectsNeedingRecompile) {
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
