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
import com.tomaszrup.groovyls.importers.ProjectImporter;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tomaszrup.groovyls.util.MdcProjectContext;
import com.tomaszrup.groovyls.util.MemoryProfiler;

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

    /** Tracks pending backfill per Gradle root to avoid duplicate scheduling. */
    private final ConcurrentHashMap<Path, ScheduledFuture<?>> pendingBackfills = new ConcurrentHashMap<>();

    /** Explicit per-project resolution lifecycle state. */
    private final ConcurrentHashMap<Path, ResolutionState> resolutionStates = new ConcurrentHashMap<>();

    private volatile GroovyLanguageClient languageClient;
    private volatile Path workspaceRoot;
    private volatile List<Path> allDiscoveredRoots;
    private volatile boolean classpathCacheEnabled = true;
    private volatile boolean backfillEnabled = false;

    enum ResolutionState {
        REQUESTED,
        RESOLVING,
        RESOLVED,
        FAILED
    }

    public ClasspathResolutionCoordinator(ProjectScopeManager scopeManager,
                                          CompilationService compilationService,
                                          Map<Path, ProjectImporter> projectImporterMap,
                                          ExecutorPools executorPools) {
        this.scopeManager = scopeManager;
        this.compilationService = compilationService;
        this.projectImporterMap = projectImporterMap;
        this.importPool = executorPools.getImportPool();
        this.schedulingPool = executorPools.getSchedulingPool();
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
     * Enable or disable automatic backfill of sibling subprojects.
     * When disabled (default), each project's classpath is resolved on
     * demand when a file is first opened in it.
     */
    public void setBackfillEnabled(boolean enabled) {
        this.backfillEnabled = enabled;
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
        Path projectRoot = scope.getProjectRoot();
        if (!scopeManager.markResolutionStarted(projectRoot)) {
            logger.debug("Resolution already in-flight for {}, skipping", scope.getProjectRoot());
            return;
        }
        transitionState(projectRoot, ResolutionState.REQUESTED);
        transitionState(projectRoot, ResolutionState.RESOLVING);

        // Set MDC so the MDC-propagating executor captures project context
        MdcProjectContext.setProject(projectRoot);
        logger.info("Scheduling lazy classpath resolution for {}", projectRoot);
        importPool.submit(() -> {
            try {
                doResolve(scope, triggerURI);
            } catch (Exception e) {
                logger.error("Lazy classpath resolution failed for {}: {}",
                        scope.getProjectRoot(), e.getMessage(), e);
                transitionState(projectRoot, ResolutionState.FAILED);
            } finally {
                scopeManager.markResolutionComplete(projectRoot);
            }
        });
        MdcProjectContext.clear();
    }

    private void doResolve(ProjectScope scope, URI triggerURI) {
        Path projectRoot = scope.getProjectRoot();
        MdcProjectContext.setProject(projectRoot);
        ProjectImporter importer = projectImporterMap.get(projectRoot);
        if (importer == null) {
            logger.warn("No importer found for {}", projectRoot);
            transitionState(projectRoot, ResolutionState.FAILED);
            return;
        }

        logProgress("Resolving classpath for " + projectRoot.getFileName() + "...");
        sendStatusUpdate("importing", "Resolving classpath for " + projectRoot.getFileName() + "...");
        long start = System.currentTimeMillis();

        // Resolve classpath for this single project
        List<String> classpath = importer.resolveClasspath(projectRoot);
        boolean markResolved = importer.shouldMarkClasspathResolved(projectRoot, classpath);
        String detectedGroovyVersion = importer
            .detectProjectGroovyVersion(projectRoot, classpath)
            .orElse(null);

        long elapsed = System.currentTimeMillis() - start;
        String resolvedMsg = "Classpath resolved for " + projectRoot.getFileName()
                + " (" + classpath.size() + " entries, " + elapsed + "ms)";
        logProgress(resolvedMsg);
        sendStatusUpdate("ready", resolvedMsg);

        // Apply to scope
        ProjectScope updatedScope = scopeManager.updateProjectClasspath(
            projectRoot, classpath, detectedGroovyVersion, markResolved);
        if (markResolved) {
            transitionState(projectRoot, ResolutionState.RESOLVED);
        } else {
            logger.warn("Classpath for {} applied but scope remains unresolved", projectRoot);
            transitionState(projectRoot, ResolutionState.FAILED);
            String retryMsg = "Classpath for " + projectRoot.getFileName()
                    + " is incomplete (target-only). Dependencies will be retried on next file open.";
            logProgress(retryMsg);
            sendStatusUpdate("importing", retryMsg);
        }

        // Compile Java/Kotlin sources so that .class files are up to date
        // before Groovy compilation.  The lazy resolve only resolves
        // dependency JARs — it does NOT compile source code.
        try {
            logProgress("Compiling " + importer.getName() + " sources for "
                    + projectRoot.getFileName() + "...");
            importer.recompile(projectRoot);
        } catch (Exception e) {
            logger.warn("Post-resolve source compilation failed for {}: {}",
                    projectRoot, e.getMessage());
        }

        // Save to cache
        if (classpathCacheEnabled && workspaceRoot != null && markResolved) {
            String cachedGroovyVersion = updatedScope != null
                ? updatedScope.getDetectedGroovyVersion()
                : null;
            ClasspathCache.mergeProject(workspaceRoot, projectRoot, classpath,
                cachedGroovyVersion, allDiscoveredRoots);
        }

        // Compile if the scope has open files
        if (updatedScope != null) {
            updatedScope.getLock().writeLock().lock();
            try {
                compilationService.ensureScopeCompiled(updatedScope);
            } catch (VirtualMachineError e) {
                logger.error("VirtualMachineError during post-resolve compilation for {}: {}",
                        projectRoot, e.toString());
                // Swallow — the scope is already marked as failed by
                // CompilationService.handleCompilationOOM if it got that far.
            } finally {
                updatedScope.getLock().writeLock().unlock();
            }
            if (languageClient != null) {
                languageClient.refreshSemanticTokens();
            }
        }

        // Schedule backfill for sibling subprojects (if enabled)
        if (backfillEnabled) {
            scheduleBackfill(importer, projectRoot);
        } else {
            logger.debug("Backfill disabled — sibling projects will resolve on demand");
        }

        // Schedule low-priority background source JAR download so that
        // Go-to-Definition can show real source.  This is NOT on the
        // critical path to first diagnostic.
        importPool.submit(() -> {
            try {
                importer.downloadSourceJarsAsync(projectRoot);
            } catch (Exception e) {
                logger.debug("Background source JAR download failed: {}", e.getMessage());
            }
        });
    }

    /**
     * Schedule a delayed backfill for unresolved sibling subprojects under the
     * same build-tool root. Uses a coalescing delay so rapid file opens across
     * multiple subprojects are batched into one build-tool interaction.
     */
    private void scheduleBackfill(ProjectImporter importer, Path resolvedProjectRoot) {
        if (!importer.supportsSiblingBatching()) {
            // This build tool doesn't support batching siblings
            return;
        }

        Path buildToolRoot = importer.getBuildToolRoot(resolvedProjectRoot);

        // Cancel any pending backfill for this root (coalescing)
        ScheduledFuture<?> existing = pendingBackfills.get(buildToolRoot);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = schedulingPool.schedule(() -> {
            importPool.submit(() -> doBackfill(importer, buildToolRoot));
        }, BACKFILL_DELAY_MS, TimeUnit.MILLISECONDS);

        pendingBackfills.put(buildToolRoot, future);
    }

    private void doBackfill(ProjectImporter importer, Path buildToolRoot) {
        MdcProjectContext.setProject(buildToolRoot);
        // Find all unresolved sibling scopes under this build-tool root
        List<ProjectScope> unresolvedSiblings = new ArrayList<>();
        for (ProjectScope scope : scopeManager.getProjectScopes()) {
            if (scope.getProjectRoot() != null
                    && !scope.isClasspathResolved()
                    && !scopeManager.isResolutionInFlight(scope.getProjectRoot())) {
                Path scopeBuildToolRoot = importer.getBuildToolRoot(scope.getProjectRoot());
                if (buildToolRoot.equals(scopeBuildToolRoot)) {
                    unresolvedSiblings.add(scope);
                }
            }
        }

        if (unresolvedSiblings.isEmpty()) {
            logger.debug("Backfill: no unresolved siblings under {}", buildToolRoot);
            pendingBackfills.remove(buildToolRoot);
            return;
        }

        // Mark all as in-flight
        List<Path> toResolve = new ArrayList<>();
        for (ProjectScope scope : unresolvedSiblings) {
            if (scopeManager.markResolutionStarted(scope.getProjectRoot())) {
                toResolve.add(scope.getProjectRoot());
                transitionState(scope.getProjectRoot(), ResolutionState.RESOLVING);
            }
        }

        if (toResolve.isEmpty()) {
            pendingBackfills.remove(buildToolRoot);
            return;
        }

        logProgress("Backfill: resolving " + toResolve.size()
                + " sibling project(s) under " + buildToolRoot.getFileName() + "...");
        sendStatusUpdate("importing", "Backfill: resolving " + toResolve.size()
                + " sibling project(s) under " + buildToolRoot.getFileName() + "...");
        long start = System.currentTimeMillis();

        try {
            Map<Path, List<String>> batchResult =
                    importer.resolveClasspathsForRoot(buildToolRoot, toResolve);

            for (Map.Entry<Path, List<String>> entry : batchResult.entrySet()) {
                Path root = entry.getKey();
                List<String> classpath = entry.getValue();
                boolean markResolved = importer.shouldMarkClasspathResolved(root, classpath);
                String detectedGroovyVersion = importer
                    .detectProjectGroovyVersion(root, classpath)
                    .orElse(null);

                ProjectScope updatedScope = scopeManager.updateProjectClasspath(
                    root, classpath, detectedGroovyVersion, markResolved);
                if (markResolved) {
                    transitionState(root, ResolutionState.RESOLVED);
                } else {
                    transitionState(root, ResolutionState.FAILED);
                    logger.warn("Backfill classpath for {} is incomplete (target-only); scope remains unresolved", root);
                }

                if (classpathCacheEnabled && workspaceRoot != null && markResolved) {
                    String cachedGroovyVersion = updatedScope != null
                            ? updatedScope.getDetectedGroovyVersion()
                            : null;
                    ClasspathCache.mergeProject(workspaceRoot, root, classpath,
                        cachedGroovyVersion, allDiscoveredRoots);
                }
            }

            for (Path root : toResolve) {
                if (!batchResult.containsKey(root)) {
                    transitionState(root, ResolutionState.FAILED);
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            String backfillMsg = "Backfill complete: " + batchResult.size()
                    + " project(s) resolved in " + elapsed + "ms";
            logProgress(backfillMsg);
            sendStatusUpdate("ready", backfillMsg);

            // Log memory stats after backfill so the user (and us) can
            // diagnose memory pressure in large workspaces early.
            logMemoryStats();
        } catch (Exception e) {
            logger.error("Backfill failed for root {}: {}", buildToolRoot, e.getMessage(), e);
            for (Path root : toResolve) {
                transitionState(root, ResolutionState.FAILED);
            }
        } finally {
            for (Path root : toResolve) {
                scopeManager.markResolutionComplete(root);
            }
            pendingBackfills.remove(buildToolRoot);
        }
    }

    private void transitionState(Path projectRoot, ResolutionState newState) {
        if (projectRoot == null) {
            return;
        }
        ResolutionState prev = resolutionStates.put(projectRoot, newState);
        if (prev != newState) {
            logger.debug("Resolution state {} -> {} for {}", prev, newState, projectRoot);
        }
    }

    ResolutionState getResolutionState(Path projectRoot) {
        return resolutionStates.get(projectRoot);
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

    /**
     * Log JVM memory statistics and warn if usage exceeds 80% of max heap.
     * When the memory profiler is enabled, also logs a per-project breakdown.
     */
    private void logMemoryStats() {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        int scopeCount = scopeManager.getProjectScopes().size();
        logger.info("JVM memory after backfill: used={}MB, max={}MB, scopes={}",
                usedMB, maxMB, scopeCount);
        if (usedMB > maxMB * 0.8) {
            String warning = String.format(
                    "Memory usage is high (%dMB / %dMB, %d project scopes). "
                    + "Consider increasing heap via groovy.java.vmargs setting, "
                    + "e.g. \"-Xmx%dm\".",
                    usedMB, maxMB, scopeCount,
                    Math.min(4096, (int) (maxMB * 2)));
            logger.warn(warning);
            if (languageClient != null) {
                languageClient.logMessage(new MessageParams(MessageType.Warning, warning));
            }
        }
        MemoryProfiler.logProfile(scopeManager.getProjectScopes());
    }

    public void shutdown() {
        // Pool shutdown is handled centrally by ExecutorPools.shutdownAll()
        // called from GroovyLanguageServer.shutdown().
        // Cancel any pending backfills.
        for (ScheduledFuture<?> future : pendingBackfills.values()) {
            future.cancel(false);
        }
        pendingBackfills.clear();
        resolutionStates.clear();
    }
}
