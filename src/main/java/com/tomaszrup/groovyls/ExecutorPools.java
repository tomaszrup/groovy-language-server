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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tomaszrup.groovyls.util.MdcProjectContext;

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
     * Small fixed-size pool for background compilation work.
     * Independent project scopes can compile in parallel since each
     * scope has its own CompilationUnit, ClassLoader, and ASTNodeVisitor.
     * Thread safety is ensured by per-scope write locks.
     * Using 2 threads balances parallelism with memory pressure from
     * concurrent Groovy compilations.
     */
    private final ExecutorService backgroundCompilationPool;

    /**
     * Global semaphore that caps the total number of concurrent compilations
     * across ALL thread pools (import pool, background pool, LSP threads).
     * This prevents memory spikes when many projects resolve their classpaths
     * simultaneously and each triggers compilation on the import pool thread.
     */
    private final Semaphore compilationPermits;

    public ExecutorPools() {
        ScheduledExecutorService rawSchedulingPool = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "groovyls-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.schedulingPool = new MdcScheduledExecutorService(rawSchedulingPool);

        // Cap the import pool at 4 threads to limit Gradle daemon parallelism
        // and reduce CPU pressure during classpath resolution.
        ExecutorService rawImportPool = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), r -> {
                    Thread t = new Thread(r, "groovyls-import");
                    t.setDaemon(true);
                    return t;
                });
        this.importPool = new MdcExecutorService(rawImportPool);

        int bgCompileThreads = Math.min(2, Runtime.getRuntime().availableProcessors());
        ExecutorService rawBgPool = Executors.newFixedThreadPool(bgCompileThreads, r -> {
            Thread t = new Thread(r, "groovyls-bg-compile");
            t.setDaemon(true);
            return t;
        });
        this.backgroundCompilationPool = new MdcExecutorService(rawBgPool);

        // Allow at most N concurrent compilations regardless of which pool
        // they originate from.  When heap is small (< 1 GB), restrict to 1
        // concurrent compilation to prevent overlapping peak AST memory from
        // causing OOM.  Otherwise allow up to 2 concurrent compilations.
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int permits = maxHeapMB < 1024 ? 1 : Math.min(2, Runtime.getRuntime().availableProcessors());
        this.compilationPermits = new Semaphore(permits);
        logger.info("Compilation permits: {} (maxHeap={}MB)", permits, maxHeapMB);
    }

    /** Scheduled executor for debounce timers and delayed task scheduling. */
    public ScheduledExecutorService getSchedulingPool() {
        return schedulingPool;
    }

    /** Pool for project import and classpath resolution work. */
    public ExecutorService getImportPool() {
        return importPool;
    }

    /** Fixed-size pool for background AST compilation (parallel for independent scopes). */
    public ExecutorService getBackgroundCompilationPool() {
        return backgroundCompilationPool;
    }

    /**
     * Global semaphore limiting concurrent compilations across all pools.
     * Callers should {@code acquire()} before starting compilation work
     * and {@code release()} in a {@code finally} block afterwards.
     */
    public Semaphore getCompilationPermits() {
        return compilationPermits;
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

    // -----------------------------------------------------------------------
    // MDC-propagating executor wrappers
    // -----------------------------------------------------------------------

    /**
     * Wraps an {@link ExecutorService} so that every submitted task
     * automatically inherits the caller thread's SLF4J MDC context.
     */
    private static class MdcExecutorService implements ExecutorService {
        protected final ExecutorService delegate;

        MdcExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override public void execute(Runnable command) {
            delegate.execute(MdcProjectContext.wrap(command));
        }

        @Override public Future<?> submit(Runnable task) {
            return delegate.submit(MdcProjectContext.wrap(task));
        }

        @Override public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(MdcProjectContext.wrap(task), result);
        }

        @Override public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(wrapCallable(task));
        }

        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            return delegate.invokeAll(wrapCallables(tasks));
        }

        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.invokeAll(wrapCallables(tasks), timeout, unit);
        }

        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return delegate.invokeAny(wrapCallables(tasks));
        }

        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(wrapCallables(tasks), timeout, unit);
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        private static <T> Callable<T> wrapCallable(Callable<T> task) {
            java.util.Map<String, String> ctx = MdcProjectContext.snapshot();
            return () -> {
                java.util.Map<String, String> prev = MdcProjectContext.snapshot();
                MdcProjectContext.restore(ctx);
                try {
                    return task.call();
                } finally {
                    MdcProjectContext.restore(prev);
                }
            };
        }

        private static <T> Collection<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
            List<Callable<T>> wrapped = new java.util.ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                wrapped.add(wrapCallable(task));
            }
            return wrapped;
        }
    }

    /**
     * Wraps a {@link ScheduledExecutorService} so that every scheduled task
     * automatically inherits the caller thread's SLF4J MDC context.
     */
    private static class MdcScheduledExecutorService extends MdcExecutorService
            implements ScheduledExecutorService {
        private final ScheduledExecutorService scheduledDelegate;

        MdcScheduledExecutorService(ScheduledExecutorService delegate) {
            super(delegate);
            this.scheduledDelegate = delegate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return scheduledDelegate.schedule(MdcProjectContext.wrap(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            java.util.Map<String, String> ctx = MdcProjectContext.snapshot();
            Callable<V> wrapped = () -> {
                java.util.Map<String, String> prev = MdcProjectContext.snapshot();
                MdcProjectContext.restore(ctx);
                try {
                    return callable.call();
                } finally {
                    MdcProjectContext.restore(prev);
                }
            };
            return scheduledDelegate.schedule(wrapped, delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                long period, TimeUnit unit) {
            return scheduledDelegate.scheduleAtFixedRate(
                    MdcProjectContext.wrap(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                long delay, TimeUnit unit) {
            return scheduledDelegate.scheduleWithFixedDelay(
                    MdcProjectContext.wrap(command), initialDelay, delay, unit);
        }
    }
}
