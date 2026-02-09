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

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DependencyGraph}: forward/reverse edge management,
 * transitive dependency resolution, file removal, and circular dependency
 * handling.
 */
class DependencyGraphTests {

	private DependencyGraph graph;

	// Sample URIs representing source files
	private static final URI FILE_A = URI.create("file:///project/src/A.groovy");
	private static final URI FILE_B = URI.create("file:///project/src/B.groovy");
	private static final URI FILE_C = URI.create("file:///project/src/C.groovy");
	private static final URI FILE_D = URI.create("file:///project/src/D.groovy");
	private static final URI FILE_E = URI.create("file:///project/src/E.groovy");

	@BeforeEach
	void setup() {
		graph = new DependencyGraph();
	}

	@Test
	void testEmptyGraph() {
		Assertions.assertTrue(graph.isEmpty());
		Assertions.assertEquals(0, graph.size());
		Assertions.assertTrue(graph.getDirectDependents(FILE_A).isEmpty());
		Assertions.assertTrue(graph.getDirectDependencies(FILE_A).isEmpty());
		Assertions.assertTrue(graph.getTransitiveDependents(Collections.singleton(FILE_A)).isEmpty());
	}

	@Test
	void testSingleDependency() {
		// B depends on A (B imports A)
		graph.updateDependencies(FILE_B, setOf(FILE_A));

		Assertions.assertEquals(1, graph.size());
		Assertions.assertEquals(setOf(FILE_A), graph.getDirectDependencies(FILE_B));
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_A));
		Assertions.assertTrue(graph.getDirectDependents(FILE_B).isEmpty());
	}

	@Test
	void testDirectDependents() {
		// B depends on A, C depends on A
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		graph.updateDependencies(FILE_C, setOf(FILE_A));

		Set<URI> dependents = graph.getDirectDependents(FILE_A);
		Assertions.assertEquals(setOf(FILE_B, FILE_C), dependents);
	}

	@Test
	void testTransitiveDependents() {
		// B depends on A, C depends on B → changing A should affect B and C
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		graph.updateDependencies(FILE_C, setOf(FILE_B));

		Set<URI> affected = graph.getTransitiveDependents(Collections.singleton(FILE_A));
		Assertions.assertEquals(setOf(FILE_B, FILE_C), affected);
	}

	@Test
	void testTransitiveDependentsChain() {
		// A ← B ← C ← D ← E (chain of dependencies)
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		graph.updateDependencies(FILE_C, setOf(FILE_B));
		graph.updateDependencies(FILE_D, setOf(FILE_C));
		graph.updateDependencies(FILE_E, setOf(FILE_D));

		Set<URI> affected = graph.getTransitiveDependents(Collections.singleton(FILE_A));
		Assertions.assertEquals(setOf(FILE_B, FILE_C, FILE_D, FILE_E), affected);
	}

	@Test
	void testTransitiveDependentsDoNotIncludeChanged() {
		// The returned set should NOT include the originally changed file
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		Set<URI> affected = graph.getTransitiveDependents(Collections.singleton(FILE_A));
		Assertions.assertFalse(affected.contains(FILE_A));
		Assertions.assertTrue(affected.contains(FILE_B));
	}

	@Test
	void testCircularDependency() {
		// A depends on B, B depends on A (circular)
		graph.updateDependencies(FILE_A, setOf(FILE_B));
		graph.updateDependencies(FILE_B, setOf(FILE_A));

		// Should not loop infinitely; should return the other file
		Set<URI> affected = graph.getTransitiveDependents(Collections.singleton(FILE_A));
		Assertions.assertTrue(affected.contains(FILE_B));
		// Should terminate without error
	}

	@Test
	void testCircularDependencyThreeFiles() {
		// A → B → C → A (circular chain)
		graph.updateDependencies(FILE_A, setOf(FILE_B));
		graph.updateDependencies(FILE_B, setOf(FILE_C));
		graph.updateDependencies(FILE_C, setOf(FILE_A));

		Set<URI> affected = graph.getTransitiveDependents(Collections.singleton(FILE_A));
		Assertions.assertTrue(affected.contains(FILE_B));
		Assertions.assertTrue(affected.contains(FILE_C));
	}

	@Test
	void testUpdateDependenciesReplacesOld() {
		// B initially depends on A
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_A));

		// B now depends on C instead of A
		graph.updateDependencies(FILE_B, setOf(FILE_C));
		Assertions.assertTrue(graph.getDirectDependents(FILE_A).isEmpty());
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_C));
		Assertions.assertEquals(setOf(FILE_C), graph.getDirectDependencies(FILE_B));
	}

	@Test
	void testUpdateDependenciesAddsAndRemoves() {
		// B depends on A and C
		graph.updateDependencies(FILE_B, setOf(FILE_A, FILE_C));
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_A));
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_C));

		// B now depends on A and D (C removed, D added)
		graph.updateDependencies(FILE_B, setOf(FILE_A, FILE_D));
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_A));
		Assertions.assertTrue(graph.getDirectDependents(FILE_C).isEmpty());
		Assertions.assertEquals(setOf(FILE_B), graph.getDirectDependents(FILE_D));
	}

	@Test
	void testRemoveFile() {
		// B depends on A, C depends on A
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		graph.updateDependencies(FILE_C, setOf(FILE_A));

		// Remove A from the graph
		graph.removeFile(FILE_A);

		// Forward edges from A should be gone
		Assertions.assertTrue(graph.getDirectDependencies(FILE_A).isEmpty());
		// Reverse edges to A should be gone
		Assertions.assertTrue(graph.getDirectDependents(FILE_A).isEmpty());
		// B's forward edge to A should be cleaned up
		Set<URI> bDeps = graph.getDirectDependencies(FILE_B);
		Assertions.assertFalse(bDeps.contains(FILE_A));
	}

	@Test
	void testRemoveFileWithDependencies() {
		// B depends on A, B depends on C
		graph.updateDependencies(FILE_B, setOf(FILE_A, FILE_C));

		// Remove B from the graph
		graph.removeFile(FILE_B);

		// B's forward edges should be gone
		Assertions.assertTrue(graph.getDirectDependencies(FILE_B).isEmpty());
		// Reverse edges from A and C should no longer reference B
		Assertions.assertTrue(graph.getDirectDependents(FILE_A).isEmpty());
		Assertions.assertTrue(graph.getDirectDependents(FILE_C).isEmpty());
	}

	@Test
	void testClear() {
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		graph.updateDependencies(FILE_C, setOf(FILE_A));
		Assertions.assertFalse(graph.isEmpty());

		graph.clear();

		Assertions.assertTrue(graph.isEmpty());
		Assertions.assertEquals(0, graph.size());
		Assertions.assertTrue(graph.getDirectDependents(FILE_A).isEmpty());
	}

	@Test
	void testMultipleChangedFiles() {
		// B depends on A, C depends on A, D depends on C
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		graph.updateDependencies(FILE_C, setOf(FILE_A));
		graph.updateDependencies(FILE_D, setOf(FILE_C));

		// Both A and C changed
		Set<URI> changed = setOf(FILE_A, FILE_C);
		Set<URI> affected = graph.getTransitiveDependents(changed);

		// B depends on A, D depends on C → both should be affected
		Assertions.assertTrue(affected.contains(FILE_B));
		Assertions.assertTrue(affected.contains(FILE_D));
		// A and C themselves should NOT be in the result
		Assertions.assertFalse(affected.contains(FILE_A));
		Assertions.assertFalse(affected.contains(FILE_C));
	}

	@Test
	void testEmptyDependenciesRemovesForwardEdge() {
		graph.updateDependencies(FILE_B, setOf(FILE_A));
		Assertions.assertEquals(1, graph.size());

		// B no longer depends on anything
		graph.updateDependencies(FILE_B, Collections.emptySet());
		Assertions.assertEquals(0, graph.size());
		Assertions.assertTrue(graph.getDirectDependents(FILE_A).isEmpty());
	}

	@Test
	void testRemoveNonexistentFile() {
		// Should be a no-op, not throw
		graph.removeFile(FILE_A);
		Assertions.assertTrue(graph.isEmpty());
	}

	@Test
	void testTransitiveDependentsWithEmptyChangedSet() {
		graph.updateDependencies(FILE_B, setOf(FILE_A));

		Set<URI> affected = graph.getTransitiveDependents(Collections.emptySet());
		Assertions.assertTrue(affected.isEmpty());
	}

	// --- Helpers ---

	@SafeVarargs
	private static <T> Set<T> setOf(T... elements) {
		Set<T> set = new HashSet<>();
		for (T element : elements) {
			set.add(element);
		}
		return set;
	}
}
