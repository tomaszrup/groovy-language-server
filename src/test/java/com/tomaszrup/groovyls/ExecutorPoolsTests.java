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
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.tomaszrup.groovyls.util.MdcProjectContext;

/**
 * Tests for {@link ExecutorPools}: verifies pool creation, MDC propagation
 * across thread boundaries, compilation semaphore behaviour, and shutdown.
 */
class ExecutorPoolsTests {

	private ExecutorPools pools;

	@BeforeEach
	void setup() {
		pools = new ExecutorPools();
		MDC.clear();
	}

	@AfterEach
	void tearDown() {
		pools.shutdownAll();
		MDC.clear();
	}

	// ------------------------------------------------------------------
	// Pool creation
	// ------------------------------------------------------------------

	@Test
	void testPoolsAreNotNull() {
		Assertions.assertNotNull(pools.getSchedulingPool(), "Scheduling pool should be non-null");
		Assertions.assertNotNull(pools.getImportPool(), "Import pool should be non-null");
		Assertions.assertNotNull(pools.getBackgroundCompilationPool(), "Background compilation pool should be non-null");
	}

	@Test
	void testCompilationPermitsAreNotNull() {
		Semaphore permits = pools.getCompilationPermits();
		Assertions.assertNotNull(permits, "Compilation permits semaphore should be non-null");
		Assertions.assertTrue(permits.availablePermits() > 0,
				"Should have at least 1 compilation permit");
	}

	// ------------------------------------------------------------------
	// MDC propagation — execute(Runnable)
	// ------------------------------------------------------------------

	@Test
	void testImportPoolPropagatesMdcViaExecute() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "test-project");
		AtomicReference<String> captured = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		pools.getImportPool().execute(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
			latch.countDown();
		});

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete");
		Assertions.assertEquals("test-project", captured.get(),
				"MDC context should propagate to import pool thread");
	}

	@Test
	void testBackgroundCompilationPoolPropagatesMdcViaExecute() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "bg-project");
		AtomicReference<String> captured = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		pools.getBackgroundCompilationPool().execute(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
			latch.countDown();
		});

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete");
		Assertions.assertEquals("bg-project", captured.get(),
				"MDC context should propagate to background compilation pool thread");
	}

	// ------------------------------------------------------------------
	// MDC propagation — submit(Runnable)
	// ------------------------------------------------------------------

	@Test
	void testImportPoolPropagatesMdcViaSubmitRunnable() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "submit-project");
		AtomicReference<String> captured = new AtomicReference<>();

		Future<?> future = pools.getImportPool().submit(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
		});

		future.get(5, TimeUnit.SECONDS);
		Assertions.assertEquals("submit-project", captured.get(),
				"MDC context should propagate via submit(Runnable)");
	}

	// ------------------------------------------------------------------
	// MDC propagation — submit(Callable)
	// ------------------------------------------------------------------

	@Test
	void testImportPoolPropagatesMdcViaSubmitCallable() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "callable-project");

		Future<String> future = pools.getImportPool().submit(
				() -> MDC.get(MdcProjectContext.MDC_KEY));

		String result = future.get(5, TimeUnit.SECONDS);
		Assertions.assertEquals("callable-project", result,
				"MDC context should propagate via submit(Callable)");
	}

	// ------------------------------------------------------------------
	// MDC propagation — scheduled pool
	// ------------------------------------------------------------------

	@Test
	void testSchedulingPoolPropagatesMdcViaScheduleRunnable() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "scheduled-project");
		AtomicReference<String> captured = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		pools.getSchedulingPool().schedule(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
			latch.countDown();
		}, 10, TimeUnit.MILLISECONDS);

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Scheduled task should complete");
		Assertions.assertEquals("scheduled-project", captured.get(),
				"MDC context should propagate via schedule(Runnable)");
	}

	@Test
	void testSchedulingPoolPropagatesMdcViaScheduleCallable() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "callable-scheduled");

		ScheduledFuture<String> future = pools.getSchedulingPool().schedule(
				() -> MDC.get(MdcProjectContext.MDC_KEY),
				10, TimeUnit.MILLISECONDS);

		String result = future.get(5, TimeUnit.SECONDS);
		Assertions.assertEquals("callable-scheduled", result,
				"MDC context should propagate via schedule(Callable)");
	}

	@Test
	void testSchedulingPoolPropagatesMdcViaScheduleAtFixedRate() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "fixed-rate-project");
		AtomicReference<String> captured = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		ScheduledFuture<?> future = pools.getSchedulingPool().scheduleAtFixedRate(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
			latch.countDown();
		}, 10, 1000, TimeUnit.MILLISECONDS);

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Fixed-rate task should execute");
		future.cancel(true);
		Assertions.assertEquals("fixed-rate-project", captured.get(),
				"MDC context should propagate via scheduleAtFixedRate");
	}

	@Test
	void testSchedulingPoolPropagatesMdcViaScheduleWithFixedDelay() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "fixed-delay-project");
		AtomicReference<String> captured = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		ScheduledFuture<?> future = pools.getSchedulingPool().scheduleWithFixedDelay(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
			latch.countDown();
		}, 10, 1000, TimeUnit.MILLISECONDS);

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Fixed-delay task should execute");
		future.cancel(true);
		Assertions.assertEquals("fixed-delay-project", captured.get(),
				"MDC context should propagate via scheduleWithFixedDelay");
	}

	// ------------------------------------------------------------------
	// MDC restoration after task completes
	// ------------------------------------------------------------------

	@Test
	void testMdcIsRestoredInWorkerThreadAfterTask() throws Exception {
		// The worker thread should not retain the caller's MDC after the
		// task finishes. We verify by submitting two tasks with different
		// MDC contexts and checking the second one sees its own context.
		MDC.put(MdcProjectContext.MDC_KEY, "first-project");
		Future<?> f1 = pools.getImportPool().submit(() -> {
			// Just propagate — do nothing
		});
		f1.get(5, TimeUnit.SECONDS);

		MDC.put(MdcProjectContext.MDC_KEY, "second-project");
		Future<String> f2 = pools.getImportPool().submit(
				() -> MDC.get(MdcProjectContext.MDC_KEY));

		String result = f2.get(5, TimeUnit.SECONDS);
		Assertions.assertEquals("second-project", result,
				"Second task should see its own MDC context, not the first task's");
	}

	// ------------------------------------------------------------------
	// MDC — no caller context
	// ------------------------------------------------------------------

	@Test
	void testNoMdcContextPropagatesAsNull() throws Exception {
		// Caller has no MDC set — task should see null
		MDC.clear();

		Future<String> future = pools.getImportPool().submit(
				() -> MDC.get(MdcProjectContext.MDC_KEY));

		String result = future.get(5, TimeUnit.SECONDS);
		Assertions.assertNull(result, "Task should see null when caller has no MDC");
	}

	// ------------------------------------------------------------------
	// Compilation semaphore
	// ------------------------------------------------------------------

	@Test
	void testCompilationPermitsAcquireAndRelease() throws Exception {
		Semaphore permits = pools.getCompilationPermits();
		int initial = permits.availablePermits();

		permits.acquire();
		Assertions.assertEquals(initial - 1, permits.availablePermits(),
				"Available permits should decrease by 1 after acquire");

		permits.release();
		Assertions.assertEquals(initial, permits.availablePermits(),
				"Available permits should restore after release");
	}

	// ------------------------------------------------------------------
	// Shutdown
	// ------------------------------------------------------------------

	@Test
	void testShutdownAllTerminatesPools() {
		ExecutorService importPool = pools.getImportPool();
		ScheduledExecutorService schedulingPool = pools.getSchedulingPool();
		ExecutorService bgPool = pools.getBackgroundCompilationPool();

		pools.shutdownAll();

		Assertions.assertTrue(importPool.isShutdown(), "Import pool should be shut down");
		Assertions.assertTrue(schedulingPool.isShutdown(), "Scheduling pool should be shut down");
		Assertions.assertTrue(bgPool.isShutdown(), "Background compilation pool should be shut down");
	}

	@Test
	void testShutdownAllRejectsNewTasks() {
		pools.shutdownAll();

		Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> {
			pools.getImportPool().submit(() -> {});
		}, "Import pool should reject tasks after shutdown");
	}

	@Test
	void testShutdownAllIsIdempotent() {
		// Calling shutdownAll() twice should not throw
		pools.shutdownAll();
		Assertions.assertDoesNotThrow(() -> pools.shutdownAll(),
				"Calling shutdownAll() twice should not throw");
	}

	// ------------------------------------------------------------------
	// Pool threading configuration
	// ------------------------------------------------------------------

	@Test
	void testPoolThreadsAreDaemons() throws Exception {
		AtomicReference<Boolean> isDaemon = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		pools.getImportPool().execute(() -> {
			isDaemon.set(Thread.currentThread().isDaemon());
			latch.countDown();
		});

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete");
		Assertions.assertTrue(isDaemon.get(), "Import pool threads should be daemon threads");
	}

	@Test
	void testSchedulingPoolThreadsAreDaemons() throws Exception {
		AtomicReference<Boolean> isDaemon = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		pools.getSchedulingPool().schedule(() -> {
			isDaemon.set(Thread.currentThread().isDaemon());
			latch.countDown();
		}, 1, TimeUnit.MILLISECONDS);

		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete");
		Assertions.assertTrue(isDaemon.get(), "Scheduling pool threads should be daemon threads");
	}
}
