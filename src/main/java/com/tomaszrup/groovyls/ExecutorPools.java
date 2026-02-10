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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized thread pool management for the Groovy Language Server.
 *
 * <p>Previously, ~9 {@link ExecutorService} instances were scattered across
 * {@link GroovyLanguageServer}, {@link GroovyServices},
 * {@link ClasspathResolutionCoordinator}, and
 * {@link com.tomaszrup.groovyls.importers.MavenProjectImporter}.
 * This class consolidates them into 3 shared pools:</p>
 *
 * <ul>
 *   <li><b>Scheduling pool</b> — low-overhead scheduled executor for
 *       debounce timers, delayed backfill scheduling, and Java/build-file
 *       recompile debouncing. All scheduled tasks are lightweight and
 *       delegate actual work to the other pools.</li>
 *   <li><b>Import pool</b> — fixed-size pool for project import and
 *       classpath resolution work (Gradle/Maven). Sized to
 *       {@code max(2, availableProcessors)} for parallel builds.</li>
 *   <li><b>Background compilation pool</b> — single-threaded executor for
 *       AST compilation work (didOpen background compilation, lazy
 *       classpath resolution compilation, backfill compilation).</li>
 * </ul>
 *
 * <p>Lifecycle: create one instance in {@link GroovyLanguageServer},
 * pass it to all components, and call {@link #shutdownAll()} on server
 * shutdown.</p>
 */
public class ExecutorPools {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorPools.class);

    /**
     * Scheduled executor for debounce timers, delayed backfill scheduling,
     * and Java/build-file recompile debouncing. Uses 2 threads to avoid
     * head-of-line blocking between independent scheduling domains.
     */
    private final ScheduledExecutorService schedulingPool;

    /**
     * Fixed-size pool for project import and classpath resolution work.
     * Used by Gradle/Maven importers and the resolution coordinator.
     */
    private final ExecutorService importPool;

    /**
     * Single-threaded executor for background compilation work.
     * Serializes AST compilation to avoid concurrent mutation.
     */
    private final ExecutorService backgroundCompilationPool;

    public ExecutorPools() {
        this.schedulingPool = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "groovyls-scheduler");
            t.setDaemon(true);
            return t;
        });

        this.importPool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                    Thread t = new Thread(r, "groovyls-import");
                    t.setDaemon(true);
                    return t;
                });

        this.backgroundCompilationPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "groovyls-bg-compile");
            t.setDaemon(true);
            return t;
        });
    }

    /** Scheduled executor for debounce timers and delayed task scheduling. */
    public ScheduledExecutorService getSchedulingPool() {
        return schedulingPool;
    }

    /** Pool for project import and classpath resolution work. */
    public ExecutorService getImportPool() {
        return importPool;
    }

    /** Single-threaded executor for background AST compilation. */
    public ExecutorService getBackgroundCompilationPool() {
        return backgroundCompilationPool;
    }

    /**
     * Shut down all pools. Attempts graceful shutdown first, then forces
     * termination after 5 seconds.
     */
    public void shutdownAll() {
        logger.debug("Shutting down executor pools");
        schedulingPool.shutdownNow();
        importPool.shutdownNow();
        backgroundCompilationPool.shutdownNow();
        try {
            schedulingPool.awaitTermination(5, TimeUnit.SECONDS);
            importPool.awaitTermination(5, TimeUnit.SECONDS);
            backgroundCompilationPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
