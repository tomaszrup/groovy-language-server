////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.compiler.ast;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.lsp.utils.Positions;
import com.tomaszrup.lsp.utils.Ranges;

/**
 * Manages AST node indices and provides lookup, snapshot, and dependency
 * resolution operations for {@link ASTNodeVisitor}.
 *
 * <p>Extracted from {@code ASTNodeVisitor} to keep class sizes manageable.
 * Accesses the visitor's package-private maps directly.</p>
 */
class ASTNodeIndex {
	private static final String JAVA_PACKAGE_PREFIX = "java.";
	private static final String GROOVY_PACKAGE_PREFIX = "groovy.";

	private final ASTNodeVisitor visitor;

	ASTNodeIndex(ASTNodeVisitor visitor) {
		this.visitor = visitor;
	}

	// ── Node query methods ───────────────────────────────────────────────────

	List<ClassNode> getClassNodes() {
		List<ClassNode> result = new ArrayList<>();
		for (List<ClassNode> nodes : visitor.classNodesByURI.values()) {
			result.addAll(nodes);
		}
		return result;
	}

	List<ClassNode> getClassNodes(URI uri) {
		List<ClassNode> nodes = visitor.classNodesByURI.get(uri);
		return nodes != null ? nodes : Collections.emptyList();
	}

	ClassNode getClassNodeByName(String name) {
		return visitor.classNodesByName.get(name);
	}

	List<ASTNode> getNodes() {
		List<ASTNode> result = new ArrayList<>();
		for (List<ASTNode> nodes : visitor.nodesByURI.values()) {
			result.addAll(nodes);
		}
		return result;
	}

	List<ASTNode> getNodes(URI uri) {
		List<ASTNode> nodes = visitor.nodesByURI.get(uri);
		if (nodes == null) {
			return Collections.emptyList();
		}
		return nodes;
	}

	ASTNode getNodeAtLineAndColumn(URI uri, int line, int column) {
		Position position = new Position(line, column);
		List<ASTNode> nodes = visitor.nodesByURI.get(uri);
		if (nodes == null) {
			return null;
		}

		ASTNode best = null;
		Range bestRange = null;

		for (ASTNode node : nodes) {
			Range range = toContainedRange(node, position);
			if (range != null && isBetterNodeCandidate(best, bestRange, node, range)) {
				best = node;
				bestRange = range;
			}
		}

		return best;
	}

	private Range toContainedRange(ASTNode node, Position position) {
		if (node.getLineNumber() == -1) {
			return null;
		}
		Range range = GroovyLanguageServerUtils.astNodeToRange(node);
		if (range == null || !Ranges.contains(range, position)) {
			return null;
		}
		return range;
	}

	private boolean isBetterNodeCandidate(ASTNode best, Range bestRange, ASTNode candidate, Range candidateRange) {
		if (best == null || bestRange == null) {
			return true;
		}
		int startCmp = Positions.COMPARATOR.compare(candidateRange.getStart(), bestRange.getStart());
		if (startCmp > 0) {
			return true;
		}
		if (startCmp < 0) {
			return false;
		}
		int endCmp = Positions.COMPARATOR.compare(candidateRange.getEnd(), bestRange.getEnd());
		if (endCmp < 0) {
			return true;
		}
		return endCmp == 0
				&& contains(best, candidate)
				&& !(best instanceof ClassNode && candidate instanceof ConstructorNode);
	}

	ASTNode getParent(ASTNode child) {
		if (child == null) {
			return null;
		}
		ASTNodeVisitor.ASTNodeLookupData data = visitor.lookup.get(
				new ASTNodeVisitor.ASTLookupKey(child));
		if (data == null) {
			return null;
		}
		return data.getParent();
	}

	boolean contains(ASTNode ancestor, ASTNode descendant) {
		ASTNode current = getParent(descendant);
		while (current != null) {
			if (current.equals(ancestor)) {
				return true;
			}
			current = getParent(current);
		}
		return false;
	}

	URI getURI(ASTNode node) {
		ASTNodeVisitor.ASTNodeLookupData data = visitor.lookup.get(
				new ASTNodeVisitor.ASTLookupKey(node));
		if (data == null) {
			return null;
		}
		return data.getUri();
	}

	int getNodeCount(URI uri) {
		List<ASTNode> nodes = visitor.nodesByURI.get(uri);
		return nodes != null ? nodes.size() : 0;
	}

	// ── Snapshot and restore ─────────────────────────────────────────────────

	/**
	 * Restores AST data for a specific URI from a previous (last-known-good)
	 * visitor into this visitor. This is used to preserve semantic token data
	 * when a recompilation produces a degraded AST due to syntax errors.
	 *
	 * <p>Any existing data for the URI in this visitor is replaced.</p>
	 *
	 * @param uri      the source file URI to restore
	 * @param previous the previous visitor containing good data for the URI
	 */
	void restoreFromPrevious(URI uri, ASTNodeVisitor previous) {
		if (previous == null || uri == null) {
			return;
		}

		// Restore nodes
		List<ASTNode> prevNodes = previous.nodesByURI.get(uri);
		if (prevNodes != null) {
			visitor.nodesByURI.put(uri, prevNodes);
		}

		// Restore class nodes
		List<ClassNode> prevClassNodes = previous.classNodesByURI.get(uri);
		if (prevClassNodes != null) {
			// Remove any class names from the new compilation for this URI
			List<ClassNode> currentClassNodes = visitor.classNodesByURI.get(uri);
			if (currentClassNodes != null) {
				for (ClassNode cn : currentClassNodes) {
					visitor.classNodesByName.remove(cn.getName());
				}
			}
			visitor.classNodesByURI.put(uri, prevClassNodes);
			// Restore class names from previous
			for (ClassNode cn : prevClassNodes) {
				visitor.classNodesByName.put(cn.getName(), cn);
			}
		}

		// Restore dependencies
		Set<String> prevDeps = previous.dependenciesByURI.get(uri);
		if (prevDeps != null) {
			visitor.dependenciesByURI.put(uri, prevDeps);
		}

		// Restore lookup entries for the URI
		// First remove any new lookup entries for this URI
		visitor.lookup.entrySet().removeIf(entry -> uri.equals(entry.getValue().getUri()));
		// Then copy from previous
		for (Map.Entry<ASTNodeVisitor.ASTLookupKey, ASTNodeVisitor.ASTNodeLookupData> entry
				: previous.lookup.entrySet()) {
			if (uri.equals(entry.getValue().getUri())) {
				visitor.lookup.put(entry.getKey(), entry.getValue());
			}
		}
	}

	/**
	 * Creates a new {@code ASTNodeVisitor} that is a copy-on-write snapshot
	 * of this visitor's data. Data for URIs in {@code excludedURIs} is omitted
	 * from the copy. The original visitor is not mutated.
	 */
	static ASTNodeVisitor createSnapshotExcluding(ASTNodeVisitor source, Collection<URI> excludedURIs) {
		ASTNodeVisitor copy = new ASTNodeVisitor();
		Set<URI> excluded = asExcludedSet(excludedURIs);
		copyUriScopedState(source, copy, excluded);
		copyClassNameIndex(source, copy, excluded);
		copyLookupIndex(source, copy, excluded);
		return copy;
	}

	private static Set<URI> asExcludedSet(Collection<URI> excludedURIs) {
		return excludedURIs instanceof Set
				? (Set<URI>) excludedURIs
				: new HashSet<>(excludedURIs);
	}

	private static void copyUriScopedState(ASTNodeVisitor source, ASTNodeVisitor copy, Set<URI> excluded) {
		for (Map.Entry<URI, List<ASTNode>> entry : source.nodesByURI.entrySet()) {
			if (!excluded.contains(entry.getKey())) {
				copy.nodesByURI.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
			}
		}
		for (Map.Entry<URI, List<ClassNode>> entry : source.classNodesByURI.entrySet()) {
			if (!excluded.contains(entry.getKey())) {
				copy.classNodesByURI.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
			}
		}
		for (Map.Entry<URI, Set<String>> entry : source.dependenciesByURI.entrySet()) {
			if (!excluded.contains(entry.getKey())) {
				copy.dependenciesByURI.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
			}
		}
	}

	private static void copyClassNameIndex(ASTNodeVisitor source, ASTNodeVisitor copy, Set<URI> excluded) {
		Set<String> excludedClassNames = collectExcludedClassNames(source, excluded);
		for (Map.Entry<String, ClassNode> entry : source.classNodesByName.entrySet()) {
			if (!excludedClassNames.contains(entry.getKey())) {
				copy.classNodesByName.put(entry.getKey(), entry.getValue());
			}
		}
	}

	private static Set<String> collectExcludedClassNames(ASTNodeVisitor source, Set<URI> excluded) {
		Set<String> excludedClassNames = new HashSet<>();
		for (URI uri : excluded) {
			List<ClassNode> classNodes = source.classNodesByURI.get(uri);
			if (classNodes != null) {
				for (ClassNode cn : classNodes) {
					excludedClassNames.add(cn.getName());
				}
			}
		}
		return excludedClassNames;
	}

	private static void copyLookupIndex(ASTNodeVisitor source, ASTNodeVisitor copy, Set<URI> excluded) {
		for (Map.Entry<ASTNodeVisitor.ASTLookupKey, ASTNodeVisitor.ASTNodeLookupData> entry
				: source.lookup.entrySet()) {
			if (!excluded.contains(entry.getValue().getUri())) {
				copy.lookup.put(entry.getKey(), entry.getValue());
			}
		}
	}

	// ── Dependency tracking ──────────────────────────────────────────────────

	void addDependency(Set<String> deps, String className) {
		if (deps != null && isProjectDependency(className)) {
			deps.add(className);
		}
	}

	boolean isProjectDependency(String className) {
		return className != null
				&& !className.startsWith(JAVA_PACKAGE_PREFIX)
				&& !className.startsWith(GROOVY_PACKAGE_PREFIX);
	}

	void addStarImportDependencies(Set<String> deps, String packageName) {
		if (deps == null || !isProjectDependency(packageName)) {
			return;
		}
		for (Map.Entry<String, ClassNode> entry : visitor.classNodesByName.entrySet()) {
			String fqn = entry.getKey();
			if (fqn.startsWith(packageName)) {
				deps.add(fqn);
			}
		}
	}

	Map<URI, Set<String>> getDependenciesByURI() {
		return visitor.dependenciesByURI;
	}

	/**
	 * Resolves class-name dependencies to source URIs using the current
	 * class name mapping. Only dependencies on classes that exist in the
	 * current compilation are resolved.
	 */
	Set<URI> resolveSourceDependencies(URI fileURI) {
		Set<String> classNames = visitor.dependenciesByURI.get(fileURI);
		if (classNames == null || classNames.isEmpty()) {
			return Collections.emptySet();
		}
		Set<URI> result = new HashSet<>();
		for (String className : classNames) {
			ClassNode classNode = visitor.classNodesByName.get(className);
			if (classNode != null && classNode.getModule() != null
					&& classNode.getModule().getContext() != null
					&& classNode.getModule().getContext().getSource() != null) {
				URI depURI = classNode.getModule().getContext().getSource().getURI();
				// Don't add self-dependency
				if (!depURI.equals(fileURI)) {
					result.add(depURI);
				}
			}
		}
		return result;
	}
}
