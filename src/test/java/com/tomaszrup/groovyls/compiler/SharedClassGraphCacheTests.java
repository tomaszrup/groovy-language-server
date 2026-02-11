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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

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
		String key1 = cache.computeClasspathKey(cl);
		String key2 = cache.computeClasspathKey(cl);
		Assertions.assertEquals(key1, key2, "Same classloader → same key");
	}

	@Test
	void testComputeClasspathKeySameForIdenticalClasspaths() {
		CompilerConfiguration config = new CompilerConfiguration();
		GroovyClassLoader cl1 = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyClassLoader cl2 = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);

		String key1 = cache.computeClasspathKey(cl1);
		String key2 = cache.computeClasspathKey(cl2);
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
	// acquireWithResult — exact match
	// ------------------------------------------------------------------

	@Test
	void testAcquireWithResultReturnsNonNull() {
		GroovyClassLoader cl = createClassLoader();
		SharedClassGraphCache.AcquireResult ar = cache.acquireWithResult(cl);
		Assertions.assertNotNull(ar, "acquireWithResult should return non-null");
		Assertions.assertNotNull(ar.getScanResult(), "ScanResult should be non-null");
		Assertions.assertNull(ar.getOwnClasspathFiles(),
				"Exact match should have null classGraphClasspathFiles");
		Assertions.assertFalse(ar.isShared(), "Exact match should not be shared");
	}

	@Test
	void testAcquireWithResultExactHitSharesResult() {
		GroovyClassLoader cl = createClassLoader();
		SharedClassGraphCache.AcquireResult ar1 = cache.acquireWithResult(cl);
		SharedClassGraphCache.AcquireResult ar2 = cache.acquireWithResult(cl);

		Assertions.assertSame(ar1.getScanResult(), ar2.getScanResult(),
				"Same classloader should return same ScanResult");
		Assertions.assertEquals(1, cache.size(), "Cache should still have one entry");
	}

	// ------------------------------------------------------------------
	// acquireWithResult — superset sharing
	// ------------------------------------------------------------------

	@Test
	void testSupersetSharingReusesCachedEntry() throws Exception {
		// Create 3 temp JARs: 2 shared + 1 extra for the superset
		Path sharedJar1 = createTempJar("shared1");
		Path sharedJar2 = createTempJar("shared2");
		Path extraJar = createTempJar("extra");

		try {
			// clA = superset (shared + extra)
			GroovyClassLoader clA = createClassLoader();
			clA.addURL(sharedJar1.toUri().toURL());
			clA.addURL(sharedJar2.toUri().toURL());
			clA.addURL(extraJar.toUri().toURL());

			// Scan clA first (superset)
			SharedClassGraphCache.AcquireResult arA = cache.acquireWithResult(clA);
			Assertions.assertNotNull(arA);
			Assertions.assertEquals(1, cache.size());

			// clB = subset of clA (2 of 3 URLs)
			// overlap = 2/2 = 100% → superset hit
			GroovyClassLoader clB = createClassLoader();
			clB.addURL(sharedJar1.toUri().toURL());
			clB.addURL(sharedJar2.toUri().toURL());

			SharedClassGraphCache.AcquireResult arB = cache.acquireWithResult(clB);
			Assertions.assertNotNull(arB);
			Assertions.assertSame(arA.getScanResult(), arB.getScanResult(),
					"Subset classloader should reuse superset's ScanResult");
			Assertions.assertTrue(arB.isShared(), "Subset result should be marked as shared");
			Assertions.assertNotNull(arB.getOwnClasspathFiles(),
					"Shared result should carry ownClasspathFiles");

			// Still only one cache entry (the superset)
			Assertions.assertEquals(1, cache.size(),
					"Superset sharing should not create a new cache entry");

			// Ref count should be 2 (A + B)
			Assertions.assertEquals(2, cache.getRefCount(arA.getScanResult()));

			// Close classloaders before clearing cache to release file handles
			clA.close();
			clB.close();
		} finally {
			cache.clear();
			// Give GC a chance to release file handles
			System.gc();
			Thread.sleep(100);
			Files.deleteIfExists(sharedJar1);
			Files.deleteIfExists(sharedJar2);
			Files.deleteIfExists(extraJar);
		}
	}

	@Test
	void testSupersetSharingFilterFilesMatchSubsetClasspath() throws Exception {
		Path sharedJar1 = createTempJar("shared1");
		Path sharedJar2 = createTempJar("shared2");
		Path extraJar = createTempJar("extra2");

		try {
			// Superset classloader
			GroovyClassLoader clSuperset = createClassLoader();
			clSuperset.addURL(sharedJar1.toUri().toURL());
			clSuperset.addURL(sharedJar2.toUri().toURL());
			clSuperset.addURL(extraJar.toUri().toURL());
			cache.acquireWithResult(clSuperset);

			// Subset classloader (only the shared JARs)
			GroovyClassLoader clSubset = createClassLoader();
			clSubset.addURL(sharedJar1.toUri().toURL());
			clSubset.addURL(sharedJar2.toUri().toURL());

			SharedClassGraphCache.AcquireResult arSubset = cache.acquireWithResult(clSubset);

			Set<File> ownFiles = arSubset.getOwnClasspathFiles();
			Assertions.assertNotNull(ownFiles);
			// The extra JAR should NOT be in the subset's own files
			Assertions.assertFalse(ownFiles.contains(extraJar.toFile().getCanonicalFile()),
					"Subset's ownClasspathFiles should not include superset-only JARs");
			// The shared JARs should be in the subset's own files
			Assertions.assertTrue(ownFiles.contains(sharedJar1.toFile().getCanonicalFile()),
					"Subset's ownClasspathFiles should include its own JARs");
			Assertions.assertTrue(ownFiles.contains(sharedJar2.toFile().getCanonicalFile()),
					"Subset's ownClasspathFiles should include its own JARs");

			// Close classloaders to release file handles
			clSuperset.close();
			clSubset.close();
		} finally {
			cache.clear();
			System.gc();
			Thread.sleep(100);
			Files.deleteIfExists(sharedJar1);
			Files.deleteIfExists(sharedJar2);
			Files.deleteIfExists(extraJar);
		}
	}

	// ------------------------------------------------------------------
	// acquireWithResult — overlap sharing (not strict superset)
	// ------------------------------------------------------------------

	@Test
	void testOverlapSharingReusesExistingEntry() throws Exception {
		// Create 10 shared JARs + 1 unique per scope
		// overlap = 10/11 = 91% → above 90% threshold, reuses directly
		int sharedCount = 10;
		Path[] sharedJars = new Path[sharedCount];
		for (int i = 0; i < sharedCount; i++) {
			sharedJars[i] = createTempJar("shared" + i);
		}
		Path extraJarA = createTempJar("extraA");
		Path extraJarB = createTempJar("extraB");

		try {
			// clA: 10 shared + extraA → 11 URLs
			GroovyClassLoader clA = createClassLoader();
			for (Path jar : sharedJars) {
				clA.addURL(jar.toUri().toURL());
			}
			clA.addURL(extraJarA.toUri().toURL());

			SharedClassGraphCache.AcquireResult arA = cache.acquireWithResult(clA);
			Assertions.assertNotNull(arA);
			Assertions.assertEquals(1, cache.size(), "First scan should create one entry");
			Assertions.assertFalse(arA.isShared(), "First scan is not shared");

			// clB: 10 shared + extraB → 11 URLs
			// overlap with clA: 10/11 = 91% → above 90% → direct reuse
			GroovyClassLoader clB = createClassLoader();
			for (Path jar : sharedJars) {
				clB.addURL(jar.toUri().toURL());
			}
			clB.addURL(extraJarB.toUri().toURL());

			SharedClassGraphCache.AcquireResult arB = cache.acquireWithResult(clB);
			Assertions.assertNotNull(arB, "Overlap reuse should succeed");
			Assertions.assertTrue(arB.isShared(), "Overlap result should be marked as shared");
			Assertions.assertNotNull(arB.getOwnClasspathFiles(),
					"Overlap result should carry ownClasspathFiles");

			// Direct reuse should NOT create a new entry
			Assertions.assertEquals(1, cache.size(),
					"Overlap should reuse existing entry — no new entry");

			// Should reuse the SAME ScanResult
			Assertions.assertSame(arA.getScanResult(), arB.getScanResult(),
					"Overlap should share the same ScanResult");

			// Ref count should be 2 (A + B)
			Assertions.assertEquals(2, cache.getRefCount(arA.getScanResult()),
					"Ref count should be 2 after overlap sharing");

			clA.close();
			clB.close();
		} finally {
			cache.clear();
			System.gc();
			Thread.sleep(100);
			for (Path jar : sharedJars) {
				Files.deleteIfExists(jar);
			}
			Files.deleteIfExists(extraJarA);
			Files.deleteIfExists(extraJarB);
		}
	}

	@Test
	void testOverlapSharingFilterContainsOnlyRequestedFiles() throws Exception {
		int sharedCount = 10;
		Path[] sharedJars = new Path[sharedCount];
		for (int i = 0; i < sharedCount; i++) {
			sharedJars[i] = createTempJar("shared" + i);
		}
		Path extraJarA = createTempJar("extraA");
		Path extraJarB = createTempJar("extraB");

		try {
			GroovyClassLoader clA = createClassLoader();
			for (Path jar : sharedJars) {
				clA.addURL(jar.toUri().toURL());
			}
			clA.addURL(extraJarA.toUri().toURL());
			cache.acquireWithResult(clA);

			GroovyClassLoader clB = createClassLoader();
			for (Path jar : sharedJars) {
				clB.addURL(jar.toUri().toURL());
			}
			clB.addURL(extraJarB.toUri().toURL());
			SharedClassGraphCache.AcquireResult arB = cache.acquireWithResult(clB);

			Set<File> ownFiles = arB.getOwnClasspathFiles();
			Assertions.assertNotNull(ownFiles);

			// clB's filter should contain shared JARs and extraB, but NOT extraA
			Assertions.assertTrue(ownFiles.contains(extraJarB.toFile().getCanonicalFile()),
					"Filter should include clB's unique JAR");
			Assertions.assertFalse(ownFiles.contains(extraJarA.toFile().getCanonicalFile()),
					"Filter should NOT include clA's unique JAR");
			for (Path jar : sharedJars) {
				Assertions.assertTrue(ownFiles.contains(jar.toFile().getCanonicalFile()),
						"Filter should include shared JARs");
			}

			clA.close();
			clB.close();
		} finally {
			cache.clear();
			System.gc();
			Thread.sleep(100);
			for (Path jar : sharedJars) {
				Files.deleteIfExists(jar);
			}
			Files.deleteIfExists(extraJarA);
			Files.deleteIfExists(extraJarB);
		}
	}

	// ------------------------------------------------------------------
	// extractUrlSet
	// ------------------------------------------------------------------

	@Test
	void testExtractUrlSetContainsAllClassLoaderUrls() throws Exception {
		GroovyClassLoader cl = createClassLoader();
		Path tempJar = createTempJar("test");
		cl.addURL(tempJar.toUri().toURL());

		Set<String> urls = SharedClassGraphCache.extractUrlSet(cl);
		Assertions.assertTrue(urls.contains(tempJar.toUri().toURL().toExternalForm()),
				"URL set should contain the added temp JAR");
		Assertions.assertTrue(urls.size() >= 1);

		Files.deleteIfExists(tempJar);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private GroovyClassLoader createClassLoader() {
		CompilerConfiguration config = new CompilerConfiguration();
		return new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
	}

	/**
	 * Create a minimal temp JAR file to differentiate classpaths in tests.
	 */
	private Path createTempJar(String prefix) throws IOException {
		Path tempFile = Files.createTempFile(prefix, ".jar");
		// Write a minimal valid JAR (empty ZIP)
		try (java.util.jar.JarOutputStream jos =
				new java.util.jar.JarOutputStream(Files.newOutputStream(tempFile))) {
			// empty JAR
		}
		return tempFile;
	}
}
