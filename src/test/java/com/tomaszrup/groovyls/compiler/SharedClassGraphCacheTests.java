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
package com.tomaszrup.groovyls.compiler;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SharedClassGraphCache}: verifies that scan results are
 * shared across classloaders with identical classpaths, reference-counted
 * correctly, and evicted when all references are released.
 */
class SharedClassGraphCacheTests {

	private SharedClassGraphCache cache;

	@BeforeEach
	void setup() {
		// Use a fresh instance per test to avoid interference
		cache = new SharedClassGraphCache();
	}

	@AfterEach
	void tearDown() {
		cache.clear();
	}

	// ------------------------------------------------------------------
	// Basic acquire / release
	// ------------------------------------------------------------------

	@Test
	void testAcquireReturnsScanResult() {
		GroovyClassLoader cl = createClassLoader();
		ScanResult result = cache.acquire(cl);
		Assertions.assertNotNull(result, "acquire() should return a non-null ScanResult");
		Assertions.assertEquals(1, cache.size(), "Cache should have one entry");
		Assertions.assertEquals(1, cache.getRefCount(result), "Ref count should be 1 after first acquire");
	}

	@Test
	void testAcquireSameClassloaderTwiceSharesResult() {
		GroovyClassLoader cl = createClassLoader();
		ScanResult first = cache.acquire(cl);
		ScanResult second = cache.acquire(cl);

		Assertions.assertSame(first, second, "Same classloader should return same ScanResult");
		Assertions.assertEquals(1, cache.size(), "Cache should still have one entry");
		Assertions.assertEquals(2, cache.getRefCount(first), "Ref count should be 2");
	}

	@Test
	void testAcquireDifferentClassloadersWithSameClasspathSharesResult() {
		CompilerConfiguration config = new CompilerConfiguration();
		// Both classloaders have the exact same (empty) classpath
		GroovyClassLoader cl1 = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyClassLoader cl2 = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);

		// They should be different object instances
		Assertions.assertNotSame(cl1, cl2);

		ScanResult result1 = cache.acquire(cl1);
		ScanResult result2 = cache.acquire(cl2);

		Assertions.assertSame(result1, result2, "Same classpath → same ScanResult");
		Assertions.assertEquals(1, cache.size(), "Should have one cache entry");
		Assertions.assertEquals(2, cache.getRefCount(result1), "Ref count should be 2");
	}

	@Test
	void testReleaseSingleRef() {
		GroovyClassLoader cl = createClassLoader();
		ScanResult result = cache.acquire(cl);
		Assertions.assertEquals(1, cache.size());

		cache.release(result);
		Assertions.assertEquals(0, cache.size(), "Entry should be evicted when ref count drops to 0");
		Assertions.assertEquals(-1, cache.getRefCount(result), "Untracked result should return -1");
	}

	@Test
	void testReleaseDecrementsBeforeEvict() {
		GroovyClassLoader cl = createClassLoader();
		ScanResult result = cache.acquire(cl);
		cache.acquire(cl); // refCount = 2

		cache.release(result);
		Assertions.assertEquals(1, cache.size(), "Entry should NOT be evicted — still 1 ref");
		Assertions.assertEquals(1, cache.getRefCount(result));

		cache.release(result);
		Assertions.assertEquals(0, cache.size(), "Now evicted");
	}

	@Test
	void testReleaseNullIsNoOp() {
		// Should not throw
		cache.release(null);
		Assertions.assertEquals(0, cache.size());
	}

	// ------------------------------------------------------------------
	// Classpath key computation
	// ------------------------------------------------------------------

	@Test
	void testComputeClasspathKeyDeterministic() {
		GroovyClassLoader cl = createClassLoader();
		String key1 = SharedClassGraphCache.computeClasspathKey(cl);
		String key2 = SharedClassGraphCache.computeClasspathKey(cl);
		Assertions.assertEquals(key1, key2, "Same classloader → same key");
	}

	@Test
	void testComputeClasspathKeySameForIdenticalClasspaths() {
		CompilerConfiguration config = new CompilerConfiguration();
		GroovyClassLoader cl1 = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyClassLoader cl2 = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);

		String key1 = SharedClassGraphCache.computeClasspathKey(cl1);
		String key2 = SharedClassGraphCache.computeClasspathKey(cl2);
		Assertions.assertEquals(key1, key2, "Identical classpaths → same key");
	}

	// ------------------------------------------------------------------
	// clear()
	// ------------------------------------------------------------------

	@Test
	void testClearEvictsAll() {
		GroovyClassLoader cl1 = createClassLoader();
		GroovyClassLoader cl2 = createClassLoader();
		cache.acquire(cl1);
		cache.acquire(cl2);
		// Both may share the same entry (same classpath) — that's fine

		cache.clear();
		Assertions.assertEquals(0, cache.size(), "Cache should be empty after clear()");
	}

	// ------------------------------------------------------------------
	// getRefCount for untracked results
	// ------------------------------------------------------------------

	@Test
	void testGetRefCountUntracked() {
		Assertions.assertEquals(-1, cache.getRefCount(null));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private GroovyClassLoader createClassLoader() {
		CompilerConfiguration config = new CompilerConfiguration();
		return new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
	}
}
