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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.importers.ProjectImporter;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Unit tests for {@link ClasspathResolutionCoordinator}: lazy resolution
 * requests, duplicate suppression, shutdown, and backfill scheduling.
 */
class ClasspathResolutionCoordinatorTests {

	private ProjectScopeManager scopeManager;
	private CompilationService compilationService;
	private FileContentsTracker fileContentsTracker;
	private ClasspathResolutionCoordinator coordinator;
	private Map<Path, ProjectImporter> importerMap;

	private static final Path ROOT = Paths.get("/workspace").toAbsolutePath();
	private static final Path PROJECT_A = Paths.get("/workspace/projectA").toAbsolutePath();
	private static final Path PROJECT_B = Paths.get("/workspace/projectB").toAbsolutePath();

	@BeforeEach
	void setup() {
		fileContentsTracker = new FileContentsTracker();
		CompilationUnitFactory defaultFactory = new CompilationUnitFactory();
		scopeManager = new ProjectScopeManager(defaultFactory, fileContentsTracker);
		compilationService = new CompilationService(fileContentsTracker);
		importerMap = new HashMap<>();
		coordinator = new ClasspathResolutionCoordinator(scopeManager, compilationService, importerMap, new ExecutorPools());
	}

	@AfterEach
	void tearDown() {
		coordinator.shutdown();
	}

	// --- requestResolution: null / already-resolved guards ---

	@Test
	void testRequestResolutionNullScope() {
		Assertions.assertDoesNotThrow(() -> coordinator.requestResolution(null, URI.create("file:///test.groovy")));
	}

	@Test
	void testRequestResolutionAlreadyResolved() {
		scopeManager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));
		ProjectScope scope = scopeManager.findProjectScopeByRoot(PROJECT_A);
		scope.setClasspathResolved(true);

		// Should be a no-op for an already-resolved scope
		coordinator.requestResolution(scope, URI.create("file:///test.groovy"));
		// markResolutionStarted should NOT have been called
		Assertions.assertFalse(scopeManager.isResolutionInFlight(PROJECT_A));
	}

	@Test
	void testRequestResolutionNullProjectRoot() {
		ProjectScope scope = scopeManager.getDefaultScope();
		Assertions.assertDoesNotThrow(
				() -> coordinator.requestResolution(scope, URI.create("file:///test.groovy")));
	}

	// --- requestResolution: duplicate suppression ---

	@Test
	void testRequestResolutionDuplicateSuppressed() {
		scopeManager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));
		ProjectScope scope = scopeManager.findProjectScopeByRoot(PROJECT_A);

		// Manually mark as in-flight to simulate a prior request
		scopeManager.markResolutionStarted(PROJECT_A);

		// This second request should be suppressed
		coordinator.requestResolution(scope, PROJECT_A.resolve("src/Foo.groovy").toUri());

		// Still in-flight from the first mark
		Assertions.assertTrue(scopeManager.isResolutionInFlight(PROJECT_A));
	}

	// --- requestResolution: async resolution with stub importer ---

	@Test
	void testRequestResolutionTriggersAsyncResolve() throws Exception {
		CountDownLatch resolveCalled = new CountDownLatch(1);
		List<String> resolvedClasspath = Arrays.asList("/lib/resolved.jar");

		// Stub importer that signals when resolve is called
		ProjectImporter stubImporter = new ProjectImporter() {
			@Override public String getName() { return "StubImporter"; }
			@Override public List<Path> discoverProjects(Path root) { return Collections.emptyList(); }
			@Override public List<String> importProject(Path root) { return resolvedClasspath; }
			@Override public List<String> resolveClasspath(Path root) {
				resolveCalled.countDown();
				return resolvedClasspath;
			}
			@Override public void recompile(Path root) {
				// no-op for test stub
			}
			@Override public boolean isProjectFile(String path) { return false; }
			@Override public boolean claimsProject(Path root) { return true; }
		};
		importerMap.put(PROJECT_A, stubImporter);

		scopeManager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));
		ProjectScope scope = scopeManager.findProjectScopeByRoot(PROJECT_A);
		Assertions.assertFalse(scope.isClasspathResolved());

		URI triggerUri = PROJECT_A.resolve("src/Main.groovy").toUri();
		coordinator.requestResolution(scope, triggerUri);

		// Wait for the resolve call to happen
		boolean called = resolveCalled.await(10, TimeUnit.SECONDS);
		Assertions.assertTrue(called, "resolveClasspath should be called within timeout");

		Assertions.assertTrue(waitUntil(scope::isClasspathResolved, 10_000),
				"Scope classpath should be resolved after async task completes");
		Assertions.assertTrue(waitUntil(
				() -> coordinator.getResolutionState(PROJECT_A) == ClasspathResolutionCoordinator.ResolutionState.RESOLVED,
				10_000));
		Assertions.assertEquals(ClasspathResolutionCoordinator.ResolutionState.RESOLVED,
				coordinator.getResolutionState(PROJECT_A));

		Assertions.assertTrue(waitUntil(() -> !scopeManager.isResolutionInFlight(PROJECT_A), 5_000));
		Assertions.assertFalse(scopeManager.isResolutionInFlight(PROJECT_A));
	}

	@Test
	void testRequestResolutionPropagatesImporterDetectedGroovyVersion() throws Exception {
		CountDownLatch resolveCalled = new CountDownLatch(1);
		List<String> resolvedClasspath = Arrays.asList("/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar");

		ProjectImporter stubImporter = new ProjectImporter() {
			@Override public String getName() { return "StubImporter"; }
			@Override public List<Path> discoverProjects(Path root) { return Collections.emptyList(); }
			@Override public List<String> importProject(Path root) { return resolvedClasspath; }
			@Override public List<String> resolveClasspath(Path root) {
				resolveCalled.countDown();
				return resolvedClasspath;
			}
			@Override public Optional<String> detectProjectGroovyVersion(Path projectRoot, List<String> classpathEntries) {
				return Optional.of("4.0.30");
			}
			@Override public void recompile(Path root) {
				// no-op for test stub
			}
			@Override public boolean isProjectFile(String path) { return false; }
			@Override public boolean claimsProject(Path root) { return true; }
		};
		importerMap.put(PROJECT_A, stubImporter);

		scopeManager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));
		ProjectScope scope = scopeManager.findProjectScopeByRoot(PROJECT_A);

		coordinator.requestResolution(scope, PROJECT_A.resolve("src/Main.groovy").toUri());

		boolean called = resolveCalled.await(10, TimeUnit.SECONDS);
		Assertions.assertTrue(called, "resolveClasspath should be called within timeout");

		Assertions.assertTrue(waitUntil(scope::isClasspathResolved, 10_000));
		Assertions.assertEquals("4.0.30", scope.getDetectedGroovyVersion(),
				"Scope should use importer-provided Groovy version");
	}

	@Test
	void testRequestResolutionKeepsScopeUnresolvedWhenImporterMarksClasspathIncomplete() throws Exception {
		CountDownLatch resolveCalled = new CountDownLatch(1);
		List<String> targetOnlyClasspath = Arrays.asList(
				PROJECT_A.resolve("target/classes").toString(),
				PROJECT_A.resolve("target/test-classes").toString());

		ProjectImporter stubImporter = new ProjectImporter() {
			@Override public String getName() { return "StubImporter"; }
			@Override public List<Path> discoverProjects(Path root) { return Collections.emptyList(); }
			@Override public List<String> importProject(Path root) { return targetOnlyClasspath; }
			@Override public List<String> resolveClasspath(Path root) {
				resolveCalled.countDown();
				return targetOnlyClasspath;
			}
			@Override public boolean shouldMarkClasspathResolved(Path root, List<String> classpathEntries) {
				return false;
			}
			@Override public void recompile(Path root) {
				// no-op for test stub
			}
			@Override public boolean isProjectFile(String path) { return false; }
			@Override public boolean claimsProject(Path root) { return true; }
		};
		importerMap.put(PROJECT_A, stubImporter);

		scopeManager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));
		ProjectScope scope = scopeManager.findProjectScopeByRoot(PROJECT_A);

		coordinator.requestResolution(scope, PROJECT_A.resolve("src/Main.groovy").toUri());

		boolean called = resolveCalled.await(10, TimeUnit.SECONDS);
		Assertions.assertTrue(called, "resolveClasspath should be called within timeout");

		Assertions.assertTrue(waitUntil(() -> !scopeManager.isResolutionInFlight(PROJECT_A), 10_000));

		Assertions.assertFalse(scope.isClasspathResolved(),
				"Scope should remain unresolved when importer marks classpath incomplete");
		Assertions.assertEquals(ClasspathResolutionCoordinator.ResolutionState.FAILED,
				coordinator.getResolutionState(PROJECT_A));
	}

	// --- shutdown ---

	@Test
	void testShutdown() {
		Assertions.assertDoesNotThrow(() -> {
			coordinator.shutdown();
			coordinator.shutdown();
		});
	}

	// --- setters ---

	@Test
	void testSetLanguageClient() {
		Assertions.assertDoesNotThrow(() -> coordinator.setLanguageClient(null));
	}

	@Test
	void testSetWorkspaceRoot() {
		Assertions.assertDoesNotThrow(() -> coordinator.setWorkspaceRoot(ROOT));
	}

	@Test
	void testSetAllDiscoveredRoots() {
		Assertions.assertDoesNotThrow(() -> coordinator.setAllDiscoveredRoots(Arrays.asList(PROJECT_A, PROJECT_B)));
	}

	@Test
	void testSetClasspathCacheEnabled() {
		Assertions.assertDoesNotThrow(() -> {
			coordinator.setClasspathCacheEnabled(false);
			coordinator.setClasspathCacheEnabled(true);
		});
	}

	// --- requestResolution: no importer found ---

	@Test
	void testRequestResolutionNoImporter() {
		// No importer registered for PROJECT_A
		scopeManager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));
		ProjectScope scope = scopeManager.findProjectScopeByRoot(PROJECT_A);

		coordinator.requestResolution(scope, PROJECT_A.resolve("src/Foo.groovy").toUri());

		Assertions.assertTrue(waitUntil(() -> !scopeManager.isResolutionInFlight(PROJECT_A), 5_000));

		// Resolution completes (fails gracefully) but scope stays unresolved
		Assertions.assertFalse(scope.isClasspathResolved());
		Assertions.assertFalse(scopeManager.isResolutionInFlight(PROJECT_A));
		Assertions.assertEquals(ClasspathResolutionCoordinator.ResolutionState.FAILED,
				coordinator.getResolutionState(PROJECT_A));
	}

	private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.onSpinWait();
		}
		return condition.getAsBoolean();
	}

}
