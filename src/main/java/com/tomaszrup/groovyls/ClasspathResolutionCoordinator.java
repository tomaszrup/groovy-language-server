////////////////////////////////////////////////////////////////////////////////
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
// Author: Tomasz Rup
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

import com.tomaszrup.groovyls.config.ClasspathCache;
import com.tomaszrup.groovyls.importers.GradleProjectImporter;
import com.tomaszrup.groovyls.importers.ProjectImporter;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Coordinates lazy on-demand classpath resolution for project scopes.
 *
 * <p>When a user opens a file in a project whose classpath hasn't been resolved
 * yet, this coordinator:</p>
 * <ol>
 *   <li>Resolves the classpath for that specific project immediately</li>
 *   <li>Applies the classpath to the scope and triggers compilation</li>
 *   <li>Schedules a low-priority backfill to resolve sibling subprojects
 *       under the same Gradle root</li>
 * </ol>
 *
 * <p>The coordinator prevents duplicate resolution requests via the
 * {@link ProjectScopeManager#markResolutionStarted(Path)} guard and supports
 * coalescing: if multiple files from different subprojects are opened quickly,
 * the backfill covers them all in a single Gradle daemon interaction.</p>
 */
public class ClasspathResolutionCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathResolutionCoordinator.class);

    /** Delay before backfill kicks in, to coalesce multiple rapid file opens. */
    private static final long BACKFILL_DELAY_MS = 2000;

    private final ProjectScopeManager scopeManager;
    private final CompilationService compilationService;
    private final Map<Path, ProjectImporter> projectImporterMap;

    /** Shared import pool for classpath resolution and backfill work. */
    private final ExecutorService importPool;

    /** Shared scheduling pool for delayed backfill tasks. */
    private final ScheduledExecutorService schedulingPool;

    /** Shared background compilation pool for post-resolve compilation. */
    private final ExecutorService backgroundCompilationPool;

    /** Tracks pending backfill per Gradle root to avoid duplicate scheduling. */
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> pendingBackfills = new ConcurrentHashMap<>();

    private volatile GroovyLanguageClient languageClient;
    private volatile Path workspaceRoot;
    private volatile List<Path> allDiscoveredRoots;
    private volatile boolean classpathCacheEnabled = true;

    public ClasspathResolutionCoordinator(ProjectScopeManager scopeManager,
                                          CompilationService compilationService,
                                          Map<Path, ProjectImporter> projectImporterMap,
                                          ExecutorPools executorPools) {
        this.scopeManager = scopeManager;
        this.compilationService = compilationService;
        this.projectImporterMap = projectImporterMap;
        this.importPool = executorPools.getImportPool();
        this.schedulingPool = executorPools.getSchedulingPool();
        this.backgroundCompilationPool = executorPools.getBackgroundCompilationPool();
    }

    public void setLanguageClient(GroovyLanguageClient client) {
        this.languageClient = client;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public void setAllDiscoveredRoots(List<Path> roots) {
        this.allDiscoveredRoots = roots;
    }

    public void setClasspathCacheEnabled(boolean enabled) {
        this.classpathCacheEnabled = enabled;
    }

    /**
     * Request lazy classpath resolution for the given scope. If the scope's
     * classpath is already resolved or resolution is already in-flight,
     * this is a no-op.
     *
     * @param scope the project scope that needs classpath resolution
     * @param triggerURI the URI that triggered the resolution (for compilation after)
     */
    public void requestResolution(ProjectScope scope, URI triggerURI) {
        if (scope == null || scope.isClasspathResolved() || scope.getProjectRoot() == null) {
            return;
        }
        if (!scopeManager.markResolutionStarted(scope.getProjectRoot())) {
            logger.debug("Resolution already in-flight for {}, skipping", scope.getProjectRoot());
            return;
        }

        logger.info("Scheduling lazy classpath resolution for {}", scope.getProjectRoot());
        importPool.submit(() -> {
            try {
                doResolve(scope, triggerURI);
            } catch (Exception e) {
                logger.error("Lazy classpath resolution failed for {}: {}",
                        scope.getProjectRoot(), e.getMessage(), e);
            } finally {
                scopeManager.markResolutionComplete(scope.getProjectRoot());
            }
        });
    }

    private void doResolve(ProjectScope scope, URI triggerURI) {
        Path projectRoot = scope.getProjectRoot();
        ProjectImporter importer = projectImporterMap.get(projectRoot);
        if (importer == null) {
            logger.warn("No importer found for {}", projectRoot);
            return;
        }

        logProgress("Resolving classpath for " + projectRoot.getFileName() + "...");
        sendStatusUpdate("importing", "Resolving classpath for " + projectRoot.getFileName() + "...");
        long start = System.currentTimeMillis();

        // Resolve classpath for this single project
        List<String> classpath = importer.resolveClasspath(projectRoot);

        long elapsed = System.currentTimeMillis() - start;
        String resolvedMsg = "Classpath resolved for " + projectRoot.getFileName()
                + " (" + classpath.size() + " entries, " + elapsed + "ms)";
        logProgress(resolvedMsg);
        sendStatusUpdate("ready", resolvedMsg);

        // Apply to scope
        ProjectScope updatedScope = scopeManager.updateProjectClasspath(projectRoot, classpath);

        // Save to cache
        if (classpathCacheEnabled && workspaceRoot != null) {
            ClasspathCache.mergeProject(workspaceRoot, projectRoot, classpath, allDiscoveredRoots);
        }

        // Compile if the scope has open files
        if (updatedScope != null) {
            updatedScope.getLock().writeLock().lock();
            try {
                compilationService.ensureScopeCompiled(updatedScope);
            } finally {
                updatedScope.getLock().writeLock().unlock();
            }
            if (languageClient != null) {
                languageClient.refreshSemanticTokens();
            }
        }

        // Schedule backfill for sibling subprojects
        scheduleBackfill(importer, projectRoot);
    }

    /**
     * Schedule a delayed backfill for unresolved sibling subprojects under the
     * same Gradle root. Uses a coalescing delay so rapid file opens across
     * multiple subprojects are batched into one Gradle daemon interaction.
     */
    private void scheduleBackfill(ProjectImporter importer, Path resolvedProjectRoot) {
        if (!(importer instanceof GradleProjectImporter)) {
            // Maven projects are independent — no sibling batching benefit
            return;
        }

        GradleProjectImporter gradleImporter = (GradleProjectImporter) importer;
        Path gradleRoot = gradleImporter.getGradleRoot(resolvedProjectRoot);

        // Cancel any pending backfill for this root (coalescing)
        ScheduledFuture<?> existing = pendingBackfills.get(gradleRoot);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = schedulingPool.schedule(() -> {
            importPool.submit(() -> doBackfill(gradleImporter, gradleRoot));
        }, BACKFILL_DELAY_MS, TimeUnit.MILLISECONDS);

        pendingBackfills.put(gradleRoot, future);
    }

    private void doBackfill(GradleProjectImporter gradleImporter, Path gradleRoot) {
        // Find all unresolved sibling scopes under this Gradle root
        List<ProjectScope> unresolvedSiblings = new ArrayList<>();
        for (ProjectScope scope : scopeManager.getProjectScopes()) {
            if (scope.getProjectRoot() != null
                    && !scope.isClasspathResolved()
                    && !scopeManager.isResolutionInFlight(scope.getProjectRoot())) {
                Path scopeGradleRoot = gradleImporter.getGradleRoot(scope.getProjectRoot());
                if (gradleRoot.equals(scopeGradleRoot)) {
                    unresolvedSiblings.add(scope);
                }
            }
        }

        if (unresolvedSiblings.isEmpty()) {
            logger.debug("Backfill: no unresolved siblings under {}", gradleRoot);
            pendingBackfills.remove(gradleRoot);
            return;
        }

        // Mark all as in-flight
        List<Path> toResolve = new ArrayList<>();
        for (ProjectScope scope : unresolvedSiblings) {
            if (scopeManager.markResolutionStarted(scope.getProjectRoot())) {
                toResolve.add(scope.getProjectRoot());
            }
        }

        if (toResolve.isEmpty()) {
            pendingBackfills.remove(gradleRoot);
            return;
        }

        logProgress("Backfill: resolving " + toResolve.size()
                + " sibling project(s) under " + gradleRoot.getFileName() + "...");
        sendStatusUpdate("importing", "Backfill: resolving " + toResolve.size()
                + " sibling project(s) under " + gradleRoot.getFileName() + "...");
        long start = System.currentTimeMillis();

        try {
            Map<Path, List<String>> batchResult =
                    gradleImporter.resolveClasspathsForRoot(gradleRoot, toResolve);

            for (Map.Entry<Path, List<String>> entry : batchResult.entrySet()) {
                Path root = entry.getKey();
                List<String> classpath = entry.getValue();

                scopeManager.updateProjectClasspath(root, classpath);

                if (classpathCacheEnabled && workspaceRoot != null) {
                    ClasspathCache.mergeProject(workspaceRoot, root, classpath, allDiscoveredRoots);
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            String backfillMsg = "Backfill complete: " + batchResult.size()
                    + " project(s) resolved in " + elapsed + "ms";
            logProgress(backfillMsg);
            sendStatusUpdate("ready", backfillMsg);
        } catch (Exception e) {
            logger.error("Backfill failed for Gradle root {}: {}", gradleRoot, e.getMessage(), e);
        } finally {
            for (Path root : toResolve) {
                scopeManager.markResolutionComplete(root);
            }
            pendingBackfills.remove(gradleRoot);
        }
    }

    private void logProgress(String message) {
        logger.info(message);
        if (languageClient != null) {
            languageClient.logMessage(new MessageParams(MessageType.Info, message));
        }
    }

    private void sendStatusUpdate(String state, String message) {
        if (languageClient != null) {
            try {
                languageClient.statusUpdate(new StatusUpdateParams(state, message));
            } catch (Exception e) {
                logger.debug("Failed to send statusUpdate: {}", e.getMessage());
            }
        }
    }

    public void shutdown() {
        // Pool shutdown is handled centrally by ExecutorPools.shutdownAll()
        // called from GroovyLanguageServer.shutdown().
        // Cancel any pending backfills.
        for (ScheduledFuture<?> future : pendingBackfills.values()) {
            future.cancel(false);
        }
        pendingBackfills.clear();
    }
}
