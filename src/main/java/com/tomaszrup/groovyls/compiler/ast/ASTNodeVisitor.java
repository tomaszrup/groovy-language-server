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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ArrayExpression;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ClosureListExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PostfixExpression;
import org.codehaus.groovy.ast.expr.PrefixExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.SpreadMapExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.AssertStatement;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.BreakStatement;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ContinueStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.classgen.BytecodeExpression;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.lsp.utils.Positions;
import com.tomaszrup.lsp.utils.Ranges;

public class ASTNodeVisitor extends ClassCodeVisitorSupport {
	private class ASTLookupKey {
		public ASTLookupKey(ASTNode node) {
			this.node = node;
		}

		private ASTNode node;

		@Override
		public boolean equals(Object o) {
			// some ASTNode subclasses, like ClassNode, override equals() with
			// comparisons that are not strict. we need strict identity.
			if (this == o) return true;
			if (!(o instanceof ASTLookupKey)) return false;
			ASTLookupKey other = (ASTLookupKey) o;
			return node == other.node;
		}

		@Override
		public int hashCode() {
			// Use identity hash to match the identity semantics of equals()
			return System.identityHashCode(node);
		}
	}

	private class ASTNodeLookupData {
		public ASTNode parent;
		public URI uri;
	}

	private SourceUnit sourceUnit;

	@Override
	protected SourceUnit getSourceUnit() {
		return sourceUnit;
	}

	private Deque<ASTNode> stack = new ArrayDeque<>();
	private Map<URI, List<ASTNode>> nodesByURI = new HashMap<>();
	private Map<URI, List<ClassNode>> classNodesByURI = new HashMap<>();
	private Map<String, ClassNode> classNodesByName = new HashMap<>();
	private Map<ASTLookupKey, ASTNodeLookupData> lookup = new HashMap<>();

	/**
	 * Tracks fully-qualified class names referenced by each source file
	 * (via imports, superclass, and interface declarations). Used to build
	 * the inter-file dependency graph for incremental compilation.
	 */
	private Map<URI, Set<String>> dependenciesByURI = new HashMap<>();

	/**
	 * Lazily-built reverse index: definition node → list of referencing nodes.
	 * Built on first {@link #getReferenceIndex()} call after each compilation,
	 * automatically invalidated when the visitor is replaced (copy-on-write).
	 */
	private volatile Map<ASTNode, List<ASTNode>> referenceIndex;

	/**
	 * Returns the lazily-built reference index, or {@code null} if it hasn't
	 * been built yet. Callers should use
	 * {@link #setReferenceIndex(Map)} to store a freshly built index.
	 */
	public Map<ASTNode, List<ASTNode>> getReferenceIndex() {
		return referenceIndex;
	}

	/**
	 * Stores a pre-built reference index. The index maps each definition
	 * node to the list of AST nodes that reference it.
	 */
	public void setReferenceIndex(Map<ASTNode, List<ASTNode>> index) {
		this.referenceIndex = index;
	}

	private void pushASTNode(ASTNode node) {
		boolean isSynthetic = false;
		if (node instanceof AnnotatedNode) {
			AnnotatedNode annotatedNode = (AnnotatedNode) node;
			isSynthetic = annotatedNode.isSynthetic();
		}
		if (!isSynthetic) {
			URI uri = sourceUnit.getSource().getURI();
			nodesByURI.get(uri).add(node);

			ASTNodeLookupData data = new ASTNodeLookupData();
			data.uri = uri;
			if (!stack.isEmpty()) {
				data.parent = stack.peekLast();
			}
			lookup.put(new ASTLookupKey(node), data);
		}

		stack.addLast(node);
	}

	private void popASTNode() {
		stack.removeLast();
	}

	public List<ClassNode> getClassNodes() {
		List<ClassNode> result = new ArrayList<>();
		for (List<ClassNode> nodes : classNodesByURI.values()) {
			result.addAll(nodes);
		}
		return result;
	}

	/**
	 * Returns the class nodes defined in the given source file.
	 *
	 * @param uri the source file URI
	 * @return the list of class nodes, or an empty list if none
	 */
	public List<ClassNode> getClassNodes(URI uri) {
		List<ClassNode> nodes = classNodesByURI.get(uri);
		return nodes != null ? nodes : Collections.emptyList();
	}

	/**
	 * Looks up a class node by its fully-qualified name in O(1) time.
	 * Returns {@code null} if no class with that name is in the AST.
	 */
	public ClassNode getClassNodeByName(String name) {
		return classNodesByName.get(name);
	}

	public List<ASTNode> getNodes() {
		List<ASTNode> result = new ArrayList<>();
		for (List<ASTNode> nodes : nodesByURI.values()) {
			result.addAll(nodes);
		}
		return result;
	}

	public List<ASTNode> getNodes(URI uri) {
		List<ASTNode> nodes = nodesByURI.get(uri);
		if (nodes == null) {
			return Collections.emptyList();
		}
		return nodes;
	}

	public ASTNode getNodeAtLineAndColumn(URI uri, int line, int column) {
		Position position = new Position(line, column);
		List<ASTNode> nodes = nodesByURI.get(uri);
		if (nodes == null) {
			return null;
		}

		ASTNode best = null;
		Range bestRange = null;

		for (ASTNode node : nodes) {
			if (node.getLineNumber() == -1) {
				continue;
			}
			Range range = GroovyLanguageServerUtils.astNodeToRange(node);
			if (range == null || !Ranges.contains(range, position)) {
				continue;
			}

			if (best == null) {
				best = node;
				bestRange = range;
				continue;
			}

			// Prefer later start (more specific/inner node)
			int startCmp = Positions.COMPARATOR.compare(range.getStart(), bestRange.getStart());
			if (startCmp > 0) {
				best = node;
				bestRange = range;
			} else if (startCmp == 0) {
				// Same start — prefer earlier end (tighter range)
				int endCmp = Positions.COMPARATOR.compare(range.getEnd(), bestRange.getEnd());
				if (endCmp < 0) {
					best = node;
					bestRange = range;
				} else if (endCmp == 0) {
					// Identical range — prefer child over parent
					// Exception: ClassNode vs ConstructorNode — keep ClassNode
					if (contains(best, node)
							&& !(best instanceof ClassNode && node instanceof ConstructorNode)) {
						best = node;
						bestRange = range;
					}
				}
			}
		}

		return best;
	}

	public ASTNode getParent(ASTNode child) {
		if (child == null) {
			return null;
		}
		ASTNodeLookupData data = lookup.get(new ASTLookupKey(child));
		if (data == null) {
			return null;
		}
		return data.parent;
	}

	public boolean contains(ASTNode ancestor, ASTNode descendant) {
		ASTNode current = getParent(descendant);
		while (current != null) {
			if (current.equals(ancestor)) {
				return true;
			}
			current = getParent(current);
		}
		return false;
	}

	public URI getURI(ASTNode node) {
		ASTNodeLookupData data = lookup.get(new ASTLookupKey(node));
		if (data == null) {
			return null;
		}
		return data.uri;
	}

	public void visitCompilationUnit(CompilationUnit unit) {
		nodesByURI.clear();
		classNodesByURI.clear();
		classNodesByName.clear();
		lookup.clear();
		dependenciesByURI.clear();
		unit.iterator().forEachRemaining(sourceUnit -> {
			visitSourceUnit(sourceUnit);
		});
	}

	public void visitCompilationUnit(CompilationUnit unit, Collection<URI> uris) {
		uris.forEach(uri -> {
			// clear all old nodes so that they may be replaced
			List<ASTNode> nodes = nodesByURI.remove(uri);
			if (nodes != null) {
				nodes.forEach(node -> {
					lookup.remove(new ASTLookupKey(node));
				});
			}
			List<ClassNode> oldClassNodes = classNodesByURI.remove(uri);
			if (oldClassNodes != null) {
				oldClassNodes.forEach(cn -> classNodesByName.remove(cn.getName()));
			}
			dependenciesByURI.remove(uri);
		});
		unit.iterator().forEachRemaining(sourceUnit -> {
			URI uri = sourceUnit.getSource().getURI();
			if (!uris.contains(uri)) {
				return;
			}
			visitSourceUnit(sourceUnit);
		});
	}

	/**
	 * Creates a new {@code ASTNodeVisitor} that is a copy-on-write snapshot
	 * of this visitor. Data for URIs in {@code excludedURIs} is omitted from
	 * the copy (those URIs are about to be re-visited from fresh compilation
	 * output). The original visitor is <em>not</em> mutated, so concurrent
	 * readers can safely use it while the new visitor is being populated.
	 *
	 * <p>The returned visitor shares the same AST node object references as
	 * the original, but the container maps are independent copies.</p>
	 *
	 * @param excludedURIs URIs whose data should be excluded from the snapshot
	 * @return a new {@code ASTNodeVisitor} pre-populated with data for all
	 *         URIs <em>except</em> those in {@code excludedURIs}
	 */
	public ASTNodeVisitor createSnapshotExcluding(Collection<URI> excludedURIs) {
		ASTNodeVisitor copy = new ASTNodeVisitor();
		Set<URI> excluded = excludedURIs instanceof Set
				? (Set<URI>) excludedURIs
				: new HashSet<>(excludedURIs);

		// Share list/set references for non-excluded URIs (shallow copy).
		// Wrapped in unmodifiable views to prevent accidental mutation — the
		// original visitor may still be held by other threads.
		for (Map.Entry<URI, List<ASTNode>> entry : nodesByURI.entrySet()) {
			if (!excluded.contains(entry.getKey())) {
				copy.nodesByURI.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
			}
		}
		for (Map.Entry<URI, List<ClassNode>> entry : classNodesByURI.entrySet()) {
			if (!excluded.contains(entry.getKey())) {
				copy.classNodesByURI.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
			}
		}
		for (Map.Entry<URI, Set<String>> entry : dependenciesByURI.entrySet()) {
			if (!excluded.contains(entry.getKey())) {
				copy.dependenciesByURI.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
			}
		}

		// Copy classNodesByName — skip names belonging to excluded URIs
		Set<String> excludedClassNames = new HashSet<>();
		for (URI uri : excluded) {
			List<ClassNode> classNodes = classNodesByURI.get(uri);
			if (classNodes != null) {
				for (ClassNode cn : classNodes) {
					excludedClassNames.add(cn.getName());
				}
			}
		}
		for (Map.Entry<String, ClassNode> entry : classNodesByName.entrySet()) {
			if (!excludedClassNames.contains(entry.getKey())) {
				copy.classNodesByName.put(entry.getKey(), entry.getValue());
			}
		}

		// Copy lookup — skip entries belonging to excluded URIs
		for (Map.Entry<ASTLookupKey, ASTNodeLookupData> entry : lookup.entrySet()) {
			if (!excluded.contains(entry.getValue().uri)) {
				copy.lookup.put(entry.getKey(), entry.getValue());
			}
		}

		return copy;
	}

	public void visitSourceUnit(SourceUnit unit) {
		sourceUnit = unit;
		URI uri = sourceUnit.getSource().getURI();
		nodesByURI.put(uri, new ArrayList<>());
		classNodesByURI.put(uri, new ArrayList<>());
		dependenciesByURI.put(uri, new HashSet<>());
		stack.clear();
		ModuleNode moduleNode = unit.getAST();
		if (moduleNode != null) {
			visitModule(moduleNode);
		}
		sourceUnit = null;
		stack.clear();
	}

	public void visitModule(ModuleNode node) {
		pushASTNode(node);
		try {
			node.getClasses().forEach(classInUnit -> {
				visitClass(classInUnit);
			});
		} finally {
			popASTNode();
		}
	}

	// GroovyClassVisitor

	public void visitClass(ClassNode node) {
		URI uri = sourceUnit.getSource().getURI();
		classNodesByURI.get(uri).add(node);
		classNodesByName.put(node.getName(), node);
		pushASTNode(node);
		try {
			ClassNode unresolvedSuperClass = node.getUnresolvedSuperClass();
			if (unresolvedSuperClass != null && unresolvedSuperClass.getLineNumber() != -1) {
				pushASTNode(unresolvedSuperClass);
				// Track superclass as a dependency
				String superName = unresolvedSuperClass.getName();
				if (superName != null && !superName.startsWith("java.") && !superName.startsWith("groovy.")) {
					Set<String> deps = dependenciesByURI.get(uri);
					if (deps != null) {
						deps.add(superName);
					}
				}
				popASTNode();
			}
			for (ClassNode unresolvedInterface : node.getUnresolvedInterfaces()) {
				if (unresolvedInterface.getLineNumber() == -1) {
					continue;
				}
				pushASTNode(unresolvedInterface);
				// Track interface as a dependency
				String ifaceName = unresolvedInterface.getName();
				if (ifaceName != null && !ifaceName.startsWith("java.") && !ifaceName.startsWith("groovy.")) {
					Set<String> deps = dependenciesByURI.get(uri);
					if (deps != null) {
						deps.add(ifaceName);
					}
				}
				popASTNode();
			}
			super.visitClass(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitAnnotations(AnnotatedNode node) {
		for (AnnotationNode annotation : node.getAnnotations()) {
			pushASTNode(annotation);
			try {
				for (java.util.Map.Entry<String, org.codehaus.groovy.ast.expr.Expression> entry : annotation.getMembers().entrySet()) {
					entry.getValue().visit(this);
				}
			} finally {
				popASTNode();
			}
		}
	}

	@Override
	public void visitImports(ModuleNode node) {
		if (node != null) {
			URI uri = sourceUnit.getSource().getURI();
			Set<String> deps = dependenciesByURI.get(uri);

			for (ImportNode importNode : node.getImports()) {
				pushASTNode(importNode);
				visitAnnotations(importNode);
				importNode.visit(this);
				// Track regular import as a dependency
				if (deps != null && importNode.getClassName() != null) {
					String className = importNode.getClassName();
					if (!className.startsWith("java.") && !className.startsWith("groovy.")) {
						deps.add(className);
					}
				}
				popASTNode();
			}
			for (ImportNode importStarNode : node.getStarImports()) {
				pushASTNode(importStarNode);
				visitAnnotations(importStarNode);
				importStarNode.visit(this);
				// Track star import — resolve conservatively against all known classes
				if (deps != null) {
					String packageName = importStarNode.getPackageName();
					if (packageName != null && !packageName.startsWith("java.") && !packageName.startsWith("groovy.")) {
						// Mark as depending on all classes in this package
						for (Map.Entry<String, ClassNode> entry : classNodesByName.entrySet()) {
							String fqn = entry.getKey();
							if (fqn.startsWith(packageName)) {
								deps.add(fqn);
							}
						}
					}
				}
				popASTNode();
			}
			for (ImportNode importStaticNode : node.getStaticImports().values()) {
				pushASTNode(importStaticNode);
				visitAnnotations(importStaticNode);
				importStaticNode.visit(this);
				// Track static import as a dependency
				if (deps != null && importStaticNode.getClassName() != null) {
					String className = importStaticNode.getClassName();
					if (!className.startsWith("java.") && !className.startsWith("groovy.")) {
						deps.add(className);
					}
				}
				popASTNode();
			}
			for (ImportNode importStaticStarNode : node.getStaticStarImports().values()) {
				pushASTNode(importStaticStarNode);
				visitAnnotations(importStaticStarNode);
				importStaticStarNode.visit(this);
				// Track static star import as a dependency
				if (deps != null && importStaticStarNode.getClassName() != null) {
					String className = importStaticStarNode.getClassName();
					if (!className.startsWith("java.") && !className.startsWith("groovy.")) {
						deps.add(className);
					}
				}
				popASTNode();
			}
		}
	}

	public void visitConstructor(ConstructorNode node) {
		pushASTNode(node);
		try {
			super.visitConstructor(node);
			for (Parameter parameter : node.getParameters()) {
				visitParameter(parameter);
			}
		} finally {
			popASTNode();
		}
	}

	public void visitMethod(MethodNode node) {
		pushASTNode(node);
		try {
			super.visitMethod(node);
			for (Parameter parameter : node.getParameters()) {
				visitParameter(parameter);
			}
		} finally {
			popASTNode();
		}
	}

	protected void visitParameter(Parameter node) {
		pushASTNode(node);
		try {
		} finally {
			popASTNode();
		}
	}

	public void visitField(FieldNode node) {
		pushASTNode(node);
		try {
			super.visitField(node);
		} finally {
			popASTNode();
		}
	}

	public void visitProperty(PropertyNode node) {
		pushASTNode(node);
		try {
			super.visitProperty(node);
		} finally {
			popASTNode();
		}
	}

	// GroovyCodeVisitor

	public void visitBlockStatement(BlockStatement node) {
		pushASTNode(node);
		try {
			super.visitBlockStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitForLoop(ForStatement node) {
		pushASTNode(node);
		try {
			super.visitForLoop(node);
		} finally {
			popASTNode();
		}
	}

	public void visitWhileLoop(WhileStatement node) {
		pushASTNode(node);
		try {
			super.visitWhileLoop(node);
		} finally {
			popASTNode();
		}
	}

	public void visitDoWhileLoop(DoWhileStatement node) {
		pushASTNode(node);
		try {
			super.visitDoWhileLoop(node);
		} finally {
			popASTNode();
		}
	}

	public void visitIfElse(IfStatement node) {
		pushASTNode(node);
		try {
			super.visitIfElse(node);
		} finally {
			popASTNode();
		}
	}

	public void visitExpressionStatement(ExpressionStatement node) {
		pushASTNode(node);
		try {
			super.visitExpressionStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitReturnStatement(ReturnStatement node) {
		pushASTNode(node);
		try {
			super.visitReturnStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitAssertStatement(AssertStatement node) {
		pushASTNode(node);
		try {
			super.visitAssertStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitTryCatchFinally(TryCatchStatement node) {
		pushASTNode(node);
		try {
			super.visitTryCatchFinally(node);
		} finally {
			popASTNode();
		}
	}

	public void visitEmptyStatement(EmptyStatement node) {
		pushASTNode(node);
		try {
			super.visitEmptyStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitSwitch(SwitchStatement node) {
		pushASTNode(node);
		try {
			super.visitSwitch(node);
		} finally {
			popASTNode();
		}
	}

	public void visitCaseStatement(CaseStatement node) {
		pushASTNode(node);
		try {
			super.visitCaseStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitBreakStatement(BreakStatement node) {
		pushASTNode(node);
		try {
			super.visitBreakStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitContinueStatement(ContinueStatement node) {
		pushASTNode(node);
		try {
			super.visitContinueStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitSynchronizedStatement(SynchronizedStatement node) {
		pushASTNode(node);
		try {
			super.visitSynchronizedStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitThrowStatement(ThrowStatement node) {
		pushASTNode(node);
		try {
			super.visitThrowStatement(node);
		} finally {
			popASTNode();
		}
	}

	public void visitMethodCallExpression(MethodCallExpression node) {
		pushASTNode(node);
		try {
			super.visitMethodCallExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
		pushASTNode(node);
		try {
			super.visitStaticMethodCallExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitConstructorCallExpression(ConstructorCallExpression node) {
		pushASTNode(node);
		try {
			super.visitConstructorCallExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitBinaryExpression(BinaryExpression node) {
		pushASTNode(node);
		try {
			super.visitBinaryExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitTernaryExpression(TernaryExpression node) {
		pushASTNode(node);
		try {
			super.visitTernaryExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitShortTernaryExpression(ElvisOperatorExpression node) {
		pushASTNode(node);
		try {
			super.visitShortTernaryExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitPostfixExpression(PostfixExpression node) {
		pushASTNode(node);
		try {
			super.visitPostfixExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitPrefixExpression(PrefixExpression node) {
		pushASTNode(node);
		try {
			super.visitPrefixExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitBooleanExpression(BooleanExpression node) {
		pushASTNode(node);
		try {
			super.visitBooleanExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitNotExpression(NotExpression node) {
		pushASTNode(node);
		try {
			super.visitNotExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitClosureExpression(ClosureExpression node) {
		pushASTNode(node);
		try {
			super.visitClosureExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitTupleExpression(TupleExpression node) {
		pushASTNode(node);
		try {
			super.visitTupleExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitListExpression(ListExpression node) {
		pushASTNode(node);
		try {
			super.visitListExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitArrayExpression(ArrayExpression node) {
		pushASTNode(node);
		try {
			super.visitArrayExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitMapExpression(MapExpression node) {
		pushASTNode(node);
		try {
			super.visitMapExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitMapEntryExpression(MapEntryExpression node) {
		pushASTNode(node);
		try {
			super.visitMapEntryExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitRangeExpression(RangeExpression node) {
		pushASTNode(node);
		try {
			super.visitRangeExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitSpreadExpression(SpreadExpression node) {
		pushASTNode(node);
		try {
			super.visitSpreadExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitSpreadMapExpression(SpreadMapExpression node) {
		pushASTNode(node);
		try {
			super.visitSpreadMapExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitMethodPointerExpression(MethodPointerExpression node) {
		pushASTNode(node);
		try {
			super.visitMethodPointerExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitUnaryMinusExpression(UnaryMinusExpression node) {
		pushASTNode(node);
		try {
			super.visitUnaryMinusExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitUnaryPlusExpression(UnaryPlusExpression node) {
		pushASTNode(node);
		try {
			super.visitUnaryPlusExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
		pushASTNode(node);
		try {
			super.visitBitwiseNegationExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitCastExpression(CastExpression node) {
		pushASTNode(node);
		try {
			super.visitCastExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitConstantExpression(ConstantExpression node) {
		pushASTNode(node);
		try {
			super.visitConstantExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitClassExpression(ClassExpression node) {
		pushASTNode(node);
		try {
			super.visitClassExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitVariableExpression(VariableExpression node) {
		pushASTNode(node);
		try {
			super.visitVariableExpression(node);
		} finally {
			popASTNode();
		}
	}

	// this calls visitBinaryExpression()
	// public void visitDeclarationExpression(DeclarationExpression node) {
	// pushASTNode(node);
	// try {
	// super.visitDeclarationExpression(node);
	// } finally {
	// popASTNode();
	// }
	// }

	public void visitPropertyExpression(PropertyExpression node) {
		pushASTNode(node);
		try {
			super.visitPropertyExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitAttributeExpression(AttributeExpression node) {
		pushASTNode(node);
		try {
			super.visitAttributeExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitFieldExpression(FieldExpression node) {
		pushASTNode(node);
		try {
			super.visitFieldExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitGStringExpression(GStringExpression node) {
		pushASTNode(node);
		try {
			super.visitGStringExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitCatchStatement(CatchStatement node) {
		pushASTNode(node);
		try {
			super.visitCatchStatement(node);
		} finally {
			popASTNode();
		}
	}

	// this calls visitTupleListExpression()
	// public void visitArgumentlistExpression(ArgumentListExpression node) {
	// pushASTNode(node);
	// try {
	// super.visitArgumentlistExpression(node);
	// } finally {
	// popASTNode();
	// }
	// }

	public void visitClosureListExpression(ClosureListExpression node) {
		pushASTNode(node);
		try {
			super.visitClosureListExpression(node);
		} finally {
			popASTNode();
		}
	}

	public void visitBytecodeExpression(BytecodeExpression node) {
		pushASTNode(node);
		try {
			super.visitBytecodeExpression(node);
		} finally {
			popASTNode();
		}
	}

	// --- Dependency tracking ---

	/**
	 * Returns the raw dependency map: source URI → set of fully-qualified
	 * class names that the source references (via imports, superclass, or
	 * interface declarations).
	 */
	public Map<URI, Set<String>> getDependenciesByURI() {
		return dependenciesByURI;
	}

	/**
	 * Resolves class-name dependencies to source URIs using the current
	 * {@link #classNodesByName} mapping.  Only dependencies on classes
	 * that exist in the current compilation are resolved — references to
	 * external (classpath) classes are silently dropped since they don't
	 * belong to the source dependency graph.
	 *
	 * @param fileURI the source file whose dependencies to resolve
	 * @return the set of source URIs that {@code fileURI} depends on,
	 *         or an empty set if no dependencies were recorded
	 */
	public Set<URI> resolveSourceDependencies(URI fileURI) {
		Set<String> classNames = dependenciesByURI.get(fileURI);
		if (classNames == null || classNames.isEmpty()) {
			return Collections.emptySet();
		}
		Set<URI> result = new HashSet<>();
		for (String className : classNames) {
			ClassNode classNode = classNodesByName.get(className);
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