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
package com.tomaszrup.groovyls.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SharedSourceJarIndex}: verifies reference counting,
 * cache hit/miss behaviour, eviction, key computation, and thread safety.
 *
 * <p>These tests use a fresh {@code SharedSourceJarIndex} instance per test
 * and avoid relying on real classpath entries (which would require actual
 * JAR files on disk). Instead we use empty/synthetic classpath lists that
 * exercise the caching and ref-counting logic without triggering real
 * source-JAR indexing.</p>
 */
class SharedSourceJarIndexTests {

	private SharedSourceJarIndex index;

	@BeforeEach
	void setup() {
		index = new SharedSourceJarIndex();
	}

	@AfterEach
	void tearDown() {
		index.clear();
	}

	// ------------------------------------------------------------------
	// Basic acquire / release
	// ------------------------------------------------------------------

	@Test
	void testAcquireReturnsNonNullEntry() {
		SharedSourceJarIndex.IndexEntry entry = index.acquire(Collections.emptyList());
		Assertions.assertNotNull(entry, "acquire() should return a non-null IndexEntry");
		Assertions.assertEquals(1, index.size(), "Cache should have one entry");
	}

	@Test
	void testAcquireReturnsMapWithGetClassNameToSourceJar() {
		SharedSourceJarIndex.IndexEntry entry = index.acquire(Collections.emptyList());
		Assertions.assertNotNull(entry.getClassNameToSourceJar(),
				"classNameToSourceJar should be non-null (possibly empty)");
	}

	@Test
	void testAcquireSameClasspathSharesEntry() {
		List<String> cp = Arrays.asList("a.jar", "b.jar");
		SharedSourceJarIndex.IndexEntry first = index.acquire(cp);
		SharedSourceJarIndex.IndexEntry second = index.acquire(cp);

		Assertions.assertSame(first, second,
				"Same classpath should return the same IndexEntry instance");
		Assertions.assertEquals(1, index.size(), "Cache should still have one entry");
	}

	@Test
	void testAcquireIncrementsRefCount() {
		List<String> cp = Arrays.asList("a.jar", "b.jar");
		SharedSourceJarIndex.IndexEntry entry = index.acquire(cp);
		Assertions.assertEquals(1, entry.refCount, "First acquire → refCount 1");

		index.acquire(cp);
		Assertions.assertEquals(2, entry.refCount, "Second acquire → refCount 2");
	}

	@Test
	void testAcquireDifferentClasspathsCreatesSeparateEntries() {
		SharedSourceJarIndex.IndexEntry e1 = index.acquire(Arrays.asList("a.jar"));
		SharedSourceJarIndex.IndexEntry e2 = index.acquire(Arrays.asList("b.jar"));

		Assertions.assertNotSame(e1, e2,
				"Different classpaths should produce different entries");
		Assertions.assertEquals(2, index.size(), "Cache should have two entries");
	}

	// ------------------------------------------------------------------
	// Order independence — sorted keys
	// ------------------------------------------------------------------

	@Test
	void testAcquireOrderIndependentClasspath() {
		List<String> cp1 = Arrays.asList("b.jar", "a.jar");
		List<String> cp2 = Arrays.asList("a.jar", "b.jar");

		SharedSourceJarIndex.IndexEntry e1 = index.acquire(cp1);
		SharedSourceJarIndex.IndexEntry e2 = index.acquire(cp2);

		Assertions.assertSame(e1, e2,
				"Classpath order should not matter — entries are sorted before hashing");
	}

	// ------------------------------------------------------------------
	// Release
	// ------------------------------------------------------------------

	@Test
	void testReleaseSingleRefEvicts() {
		SharedSourceJarIndex.IndexEntry entry = index.acquire(Collections.emptyList());
		Assertions.assertEquals(1, index.size());

		index.release(entry);
		Assertions.assertEquals(0, index.size(),
				"Entry should be evicted when refCount drops to 0");
	}

	@Test
	void testReleaseDecrementsBeforeEviction() {
		List<String> cp = Collections.singletonList("x.jar");
		SharedSourceJarIndex.IndexEntry entry = index.acquire(cp);
		index.acquire(cp); // refCount = 2

		index.release(entry);
		Assertions.assertEquals(1, index.size(), "Entry should NOT be evicted — refCount = 1");
		Assertions.assertEquals(1, entry.refCount);

		index.release(entry);
		Assertions.assertEquals(0, index.size(), "Now evicted — refCount = 0");
	}

	@Test
	void testReleaseNullIsNoOp() {
		// Should not throw
		Assertions.assertDoesNotThrow(() -> index.release(null),
				"release(null) should be a safe no-op");
		Assertions.assertEquals(0, index.size());
	}

	@Test
	void testReleaseUnknownEntryIsNoOp() {
		// Create and immediately clear to make entry "unknown"
		SharedSourceJarIndex.IndexEntry entry = index.acquire(Collections.emptyList());
		index.clear();
		// Now release it — the reverse-index lookup will return null
		Assertions.assertDoesNotThrow(() -> index.release(entry),
				"Releasing an already-evicted entry should be a safe no-op");
	}

	// ------------------------------------------------------------------
	// clear()
	// ------------------------------------------------------------------

	@Test
	void testClearRemovesAllEntries() {
		index.acquire(Arrays.asList("a.jar"));
		index.acquire(Arrays.asList("b.jar"));
		Assertions.assertTrue(index.size() >= 1);

		index.clear();
		Assertions.assertEquals(0, index.size(), "Cache should be empty after clear()");
	}

	@Test
	void testClearOnEmptyCacheIsNoOp() {
		Assertions.assertDoesNotThrow(() -> index.clear(),
				"Clearing an empty cache should not throw");
	}

	// ------------------------------------------------------------------
	// size()
	// ------------------------------------------------------------------

	@Test
	void testSizeReflectsEntryCount() {
		Assertions.assertEquals(0, index.size());

		index.acquire(Arrays.asList("a.jar"));
		Assertions.assertEquals(1, index.size());

		index.acquire(Arrays.asList("b.jar"));
		Assertions.assertEquals(2, index.size());
	}

	// ------------------------------------------------------------------
	// Key determinism / consistency
	// ------------------------------------------------------------------

	@Test
	void testSameClasspathProducesSameKey() {
		// We test this indirectly: two acquires with the same list should
		// return the same entry (assertSame already covers it). Here we
		// additionally verify that the cache size stays at 1.
		List<String> cp = Arrays.asList("x.jar", "y.jar", "z.jar");
		index.acquire(cp);
		index.acquire(cp);
		index.acquire(cp);
		Assertions.assertEquals(1, index.size(),
				"Repeated acquires of the same classpath should not create new entries");
	}

	// ------------------------------------------------------------------
	// Thread safety
	// ------------------------------------------------------------------

	@Test
	void testConcurrentAcquireAndRelease() throws Exception {
		int threadCount = 16;
		int iterations = 50;
		List<String> cp = Arrays.asList("shared.jar");
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		AtomicInteger errors = new AtomicInteger(0);

		for (int t = 0; t < threadCount; t++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int i = 0; i < iterations; i++) {
						SharedSourceJarIndex.IndexEntry entry = index.acquire(cp);
						Assertions.assertNotNull(entry);
						// Small delay to increase contention
						Thread.yield();
						index.release(entry);
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		Assertions.assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
				"All threads should complete within 30 seconds");
		Assertions.assertEquals(0, errors.get(), "No errors during concurrent access");

		executor.shutdownNow();
	}
}
