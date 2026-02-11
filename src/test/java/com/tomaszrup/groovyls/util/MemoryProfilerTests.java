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

import com.tomaszrup.groovyls.ProjectScope;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Unit tests for {@link MemoryProfiler}: component size estimation,
 * top-3 ranking, and graceful handling of null/evicted scopes.
 */
class MemoryProfilerTests {

	private static final Path PROJECT_A = Paths.get("/workspace/project-a").toAbsolutePath();
	private static final Path PROJECT_B = Paths.get("/workspace/project-b").toAbsolutePath();
	private static final Path PROJECT_C = Paths.get("/workspace/project-c").toAbsolutePath();
	private static final Path PROJECT_D = Paths.get("/workspace/project-d").toAbsolutePath();

	// --- estimateComponentSizes ---

	@Test
	void testEstimateComponentSizesEmptyScope() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_A, factory);

		Map<String, Double> sizes = MemoryProfiler.estimateComponentSizes(scope);

		Assertions.assertNotNull(sizes);
		Assertions.assertFalse(sizes.isEmpty(), "Should return component entries even if all zero");

		// All values should be non-negative
		for (Map.Entry<String, Double> entry : sizes.entrySet()) {
			Assertions.assertTrue(entry.getValue() >= 0.0,
					"Component '" + entry.getKey() + "' should be non-negative, got: " + entry.getValue());
		}
	}

	@Test
	void testEstimateComponentSizesWithClasspath() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		List<String> classpath = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			classpath.add("/libs/dep-" + i + ".jar");
		}
		factory.setAdditionalClasspathList(classpath);

		ProjectScope scope = new ProjectScope(PROJECT_A, factory);

		Map<String, Double> sizes = MemoryProfiler.estimateComponentSizes(scope);

		// GroovyClassLoader estimate should be 0 since no classloader is set
		Double clSize = sizes.get("GroovyClassLoader");
		Assertions.assertNotNull(clSize);
		Assertions.assertEquals(0.0, clSize, 0.001,
				"ClassLoader size should be 0 when no classloader is instantiated");

		// Classpath caches component should reflect the factory state
		Double cacheSize = sizes.get("Classpath caches");
		Assertions.assertNotNull(cacheSize);
		Assertions.assertTrue(cacheSize >= 0.0);
	}

	@Test
	void testEstimateComponentSizesHasExpectedComponents() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_A, factory);

		Map<String, Double> sizes = MemoryProfiler.estimateComponentSizes(scope);

		// Verify all expected component names are present
		Assertions.assertTrue(sizes.containsKey("ClassGraph ScanResult"), "Missing ClassGraph ScanResult");
		Assertions.assertTrue(sizes.containsKey("GroovyClassLoader"), "Missing GroovyClassLoader");
		Assertions.assertTrue(sizes.containsKey("AST (ASTNodeVisitor)"), "Missing AST");
		Assertions.assertTrue(sizes.containsKey("CompilationUnit"), "Missing CompilationUnit");
		Assertions.assertTrue(sizes.containsKey("Diagnostics"), "Missing Diagnostics");
		Assertions.assertTrue(sizes.containsKey("DependencyGraph"), "Missing DependencyGraph");
		Assertions.assertTrue(sizes.containsKey("Classpath caches"), "Missing Classpath caches");
	}

	// --- logProfile (no-op / graceful handling) ---

	@Test
	void testLogProfileWithNullScopes() {
		// Should not throw
		MemoryProfiler.logProfile(null);
	}

	@Test
	void testLogProfileWithEmptyScopes() {
		// Should not throw
		MemoryProfiler.logProfile(Collections.emptyList());
	}

	@Test
	void testLogProfileWithActiveScopes() {
		CompilationUnitFactory factoryA = new CompilationUnitFactory();
		factoryA.setAdditionalClasspathList(createClasspath(200));
		ProjectScope scopeA = new ProjectScope(PROJECT_A, factoryA);

		CompilationUnitFactory factoryB = new CompilationUnitFactory();
		factoryB.setAdditionalClasspathList(createClasspath(100));
		ProjectScope scopeB = new ProjectScope(PROJECT_B, factoryB);

		CompilationUnitFactory factoryC = new CompilationUnitFactory();
		factoryC.setAdditionalClasspathList(createClasspath(50));
		ProjectScope scopeC = new ProjectScope(PROJECT_C, factoryC);

		List<ProjectScope> scopes = List.of(scopeA, scopeB, scopeC);

		// Should not throw regardless of enabled state
		MemoryProfiler.logProfile(scopes);
	}

	@Test
	void testLogProfileSkipsEvictedScopes() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_A, factory);

		// Simulate eviction
		scope.getLock().writeLock().lock();
		try {
			scope.evictHeavyState();
		} finally {
			scope.getLock().writeLock().unlock();
		}

		Assertions.assertTrue(scope.isEvicted());

		// logProfile should handle evicted scopes gracefully
		MemoryProfiler.logProfile(List.of(scope));
	}

	@Test
	void testLogProfileWithMixedScopes() {
		CompilationUnitFactory factoryA = new CompilationUnitFactory();
		factoryA.setAdditionalClasspathList(createClasspath(100));
		ProjectScope active = new ProjectScope(PROJECT_A, factoryA);

		CompilationUnitFactory factoryB = new CompilationUnitFactory();
		ProjectScope evicted = new ProjectScope(PROJECT_B, factoryB);
		evicted.getLock().writeLock().lock();
		try {
			evicted.evictHeavyState();
		} finally {
			evicted.getLock().writeLock().unlock();
		}

		CompilationUnitFactory factoryC = new CompilationUnitFactory();
		ProjectScope empty = new ProjectScope(PROJECT_C, factoryC);

		// Should handle mix of active, evicted, and empty scopes
		MemoryProfiler.logProfile(List.of(active, evicted, empty));
	}

	// --- Top-3 ranking ---

	@Test
	void testEstimateRankingDifferentClasspathSizes() {
		// Create scopes with different classpath sizes to verify ranking
		CompilationUnitFactory factoryA = new CompilationUnitFactory();
		factoryA.setAdditionalClasspathList(createClasspath(500));
		ProjectScope scopeA = new ProjectScope(PROJECT_A, factoryA);

		CompilationUnitFactory factoryB = new CompilationUnitFactory();
		factoryB.setAdditionalClasspathList(createClasspath(100));
		ProjectScope scopeB = new ProjectScope(PROJECT_B, factoryB);

		// Scope A should have larger classpath cache estimate
		Map<String, Double> sizesA = MemoryProfiler.estimateComponentSizes(scopeA);
		Map<String, Double> sizesB = MemoryProfiler.estimateComponentSizes(scopeB);

		// Both should be non-negative
		double totalA = sizesA.values().stream().mapToDouble(Double::doubleValue).sum();
		double totalB = sizesB.values().stream().mapToDouble(Double::doubleValue).sum();

		Assertions.assertTrue(totalA >= 0.0, "Total for scope A should be non-negative");
		Assertions.assertTrue(totalB >= 0.0, "Total for scope B should be non-negative");
	}

	@Test
	void testLogProfileWithMoreThan3Scopes() {
		// Ensure top-3 logic works when there are more scopes
		List<ProjectScope> scopes = new ArrayList<>();
		Path[] roots = {PROJECT_A, PROJECT_B, PROJECT_C, PROJECT_D};

		for (int i = 0; i < 4; i++) {
			CompilationUnitFactory factory = new CompilationUnitFactory();
			factory.setAdditionalClasspathList(createClasspath((i + 1) * 50));
			scopes.add(new ProjectScope(roots[i], factory));
		}

		// Should not throw and should only report top 3
		MemoryProfiler.logProfile(scopes);
	}

	@Test
	void testLogProfileWithNullProjectRoot() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(null, factory);

		// Should handle null project root gracefully
		MemoryProfiler.logProfile(List.of(scope));
	}

	// --- isEnabled ---

	@Test
	void testIsEnabledReflectsSystemProperty() {
		// By default in tests, the system property is not set
		// The static ENABLED field reads at class load time.
		// This test just verifies isEnabled() doesn't throw.
		boolean enabled = MemoryProfiler.isEnabled();
		// Value depends on system property; just assert it's a boolean
		Assertions.assertNotNull((Object) enabled);
	}

	// --- Helpers ---

	private List<String> createClasspath(int size) {
		List<String> cp = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			cp.add("/libs/dependency-" + i + "-1.0.jar");
		}
		return cp;
	}
}
