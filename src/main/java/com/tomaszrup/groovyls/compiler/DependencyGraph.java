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
package com.tomaszrup.groovyls.compiler;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks inter-file dependencies so that when a source file changes, all files
 * that depend on it (directly or transitively) can be identified and
 * recompiled.
 *
 * <p>The graph maintains two concurrent maps:
 * <ul>
 *   <li>{@code dependsOn} — forward edges: file → set of files it imports/references</li>
 *   <li>{@code dependedOnBy} — reverse edges: file → set of files that reference it</li>
 * </ul>
 *
 * <p>Both maps are kept in sync by {@link #updateDependencies}. All public
 * methods are thread-safe (backed by {@link ConcurrentHashMap}).
 */
public class DependencyGraph {
	private static final Logger logger = LoggerFactory.getLogger(DependencyGraph.class);

	/**
	 * Maximum depth for transitive dependency traversal.  Prevents
	 * pathological performance in deeply interconnected or circular
	 * dependency graphs.
	 */
	private static final int MAX_TRANSITIVE_DEPTH = 5;

	/** Forward edges: file → files it depends on. */
	private final Map<URI, Set<URI>> dependsOn = new ConcurrentHashMap<>();

	/** Reverse edges: file → files that depend on it. */
	private final Map<URI, Set<URI>> dependedOnBy = new ConcurrentHashMap<>();

	/**
	 * Replaces the dependency set for {@code file}.  The old forward edges
	 * are removed from the reverse index and new edges are inserted.
	 *
	 * @param file    the source file whose dependencies were (re-)computed
	 * @param newDeps the set of source URIs that {@code file} depends on
	 *                (imports, superclass references, interface references)
	 */
	public synchronized void updateDependencies(URI file, Set<URI> newDeps) {
		Set<URI> oldDeps = dependsOn.getOrDefault(file, Collections.emptySet());

		// Remove stale reverse edges
		for (URI oldDep : oldDeps) {
			if (!newDeps.contains(oldDep)) {
				Set<URI> reverse = dependedOnBy.get(oldDep);
				if (reverse != null) {
					reverse.remove(file);
					if (reverse.isEmpty()) {
						dependedOnBy.remove(oldDep);
					}
				}
			}
		}

		// Add new reverse edges
		for (URI newDep : newDeps) {
			if (!oldDeps.contains(newDep)) {
				dependedOnBy.computeIfAbsent(newDep, k -> ConcurrentHashMap.newKeySet())
						.add(file);
			}
		}

		// Update forward index
		if (newDeps.isEmpty()) {
			dependsOn.remove(file);
		} else {
			Set<URI> newSet = ConcurrentHashMap.newKeySet();
			newSet.addAll(newDeps);
			dependsOn.put(file, newSet);
		}
	}

	/**
	 * Returns all files that transitively depend on any file in
	 * {@code changedFiles} (i.e. the set of files that need to be
	 * recompiled/revisited when the changed files are modified).
	 *
	 * <p>The returned set does <em>not</em> include the {@code changedFiles}
	 * themselves — only their dependents.
	 *
	 * <p>Traversal is bounded by {@link #MAX_TRANSITIVE_DEPTH} to prevent
	 * runaway expansion in circular dependency graphs.
	 *
	 * @param changedFiles the set of files that were directly modified
	 * @return the set of transitively affected dependent files
	 */
	public synchronized Set<URI> getTransitiveDependents(Set<URI> changedFiles) {
		Set<URI> visited = new HashSet<>(changedFiles);
		Set<URI> result = new HashSet<>();
		Queue<URI> queue = new ArrayDeque<>(changedFiles);

		int depth = 0;
		int currentLevelSize = queue.size();

		while (!queue.isEmpty() && depth < MAX_TRANSITIVE_DEPTH) {
			URI current = queue.poll();
			currentLevelSize--;

			Set<URI> dependents = dependedOnBy.get(current);
			if (dependents != null) {
				for (URI dependent : dependents) {
					if (visited.add(dependent)) {
						result.add(dependent);
						queue.add(dependent);
					}
				}
			}

			if (currentLevelSize == 0) {
				depth++;
				currentLevelSize = queue.size();
			}
		}

		if (!result.isEmpty()) {
			logger.debug("Transitive dependents of {} changed files: {} files (depth {})",
					changedFiles.size(), result.size(), depth);
		}

		return result;
	}

	/**
	 * Returns the transitive forward dependencies of the given seed files —
	 * all files that any of the given files depend on (directly or
	 * transitively), up to the specified depth.
	 *
	 * <p>Used to determine the minimal set of source files that must be
	 * included in an incremental compilation unit for type resolution.
	 * The returned set does <em>not</em> include the seed files themselves.</p>
	 *
	 * @param seedFiles the files whose dependencies to collect
	 * @param maxDepth  maximum traversal depth
	 * @return the set of dependency URIs (excluding the seed files themselves)
	 */
	public synchronized Set<URI> getTransitiveDependencies(Set<URI> seedFiles, int maxDepth) {
		Set<URI> visited = new HashSet<>(seedFiles);
		Set<URI> result = new HashSet<>();
		Queue<URI> queue = new ArrayDeque<>(seedFiles);

		int depth = 0;
		int currentLevelSize = queue.size();

		while (!queue.isEmpty() && depth < maxDepth) {
			URI current = queue.poll();
			currentLevelSize--;

			Set<URI> deps = dependsOn.get(current);
			if (deps != null) {
				for (URI dep : deps) {
					if (visited.add(dep)) {
						result.add(dep);
						queue.add(dep);
					}
				}
			}

			if (currentLevelSize == 0) {
				depth++;
				currentLevelSize = queue.size();
			}
		}

		if (!result.isEmpty()) {
			logger.debug("Forward transitive dependencies of {} seed files: {} files (depth {})",
					seedFiles.size(), result.size(), depth);
		}

		return result;
	}

	/**
	 * Returns the direct dependents of the given file — files that import
	 * or reference types defined in {@code file}.
	 *
	 * @param file the source file URI
	 * @return an unmodifiable view of the direct dependents, or an empty set
	 */
	public Set<URI> getDirectDependents(URI file) {
		Set<URI> deps = dependedOnBy.get(file);
		return deps != null ? new HashSet<>(deps) : Collections.emptySet();
	}

	/**
	 * Returns the direct dependencies of the given file — files that
	 * {@code file} imports or references.
	 *
	 * @param file the source file URI
	 * @return an unmodifiable view of the dependencies, or an empty set
	 */
	public Set<URI> getDirectDependencies(URI file) {
		Set<URI> deps = dependsOn.get(file);
		return deps != null ? new HashSet<>(deps) : Collections.emptySet();
	}

	/**
	 * Removes a file from the graph entirely — clears both its forward
	 * edges and any reverse edges pointing to it.
	 *
	 * @param file the URI of the removed/deleted file
	 */
	public synchronized void removeFile(URI file) {
		// Remove forward edges (file → its dependencies)
		Set<URI> oldDeps = dependsOn.remove(file);
		if (oldDeps != null) {
			for (URI dep : oldDeps) {
				Set<URI> reverse = dependedOnBy.get(dep);
				if (reverse != null) {
					reverse.remove(file);
					if (reverse.isEmpty()) {
						dependedOnBy.remove(dep);
					}
				}
			}
		}

		// Remove reverse entries (other files → this file)
		Set<URI> oldDependents = dependedOnBy.remove(file);
		if (oldDependents != null) {
			for (URI dependent : oldDependents) {
				Set<URI> forwardDeps = dependsOn.get(dependent);
				if (forwardDeps != null) {
					forwardDeps.remove(file);
					if (forwardDeps.isEmpty()) {
						dependsOn.remove(dependent);
					}
				}
			}
		}
	}

	/**
	 * Clears the entire dependency graph.  Called when the compilation unit
	 * is fully invalidated (e.g. classpath change) and will be rebuilt from
	 * scratch.
	 */
	public synchronized void clear() {
		dependsOn.clear();
		dependedOnBy.clear();
	}

	/**
	 * Returns the total number of files tracked in the forward index.
	 * Useful for diagnostics and testing.
	 */
	public int size() {
		return dependsOn.size();
	}

	/**
	 * Returns {@code true} if the graph contains no dependency information.
	 */
	public boolean isEmpty() {
		return dependsOn.isEmpty() && dependedOnBy.isEmpty();
	}
}
