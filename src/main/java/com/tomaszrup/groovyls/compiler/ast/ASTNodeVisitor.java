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

import java.lang.ref.SoftReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

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

public class ASTNodeVisitor extends ClassCodeVisitorSupport {

	static class ASTLookupKey {
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

	static class ASTNodeLookupData {
		private ASTNode parent;
		private URI uri;

		public ASTNode getParent() {
			return parent;
		}

		public URI getUri() {
			return uri;
		}

		public void setParent(ASTNode parent) {
			this.parent = parent;
		}

		public void setUri(URI uri) {
			this.uri = uri;
		}
	}

	private SourceUnit sourceUnit;

	@Override
	protected SourceUnit getSourceUnit() {
		return sourceUnit;
	}

	Deque<ASTNode> stack = new ArrayDeque<>();
	Map<URI, List<ASTNode>> nodesByURI = new HashMap<>();
	Map<URI, List<ClassNode>> classNodesByURI = new HashMap<>();
	Map<String, ClassNode> classNodesByName = new HashMap<>();
	Map<ASTLookupKey, ASTNodeLookupData> lookup = new HashMap<>();

	/**
	 * Tracks fully-qualified class names referenced by each source file
	 * (via imports, superclass, and interface declarations). Used to build
	 * the inter-file dependency graph for incremental compilation.
	 */
	Map<URI, Set<String>> dependenciesByURI = new HashMap<>();

	private final ASTNodeIndex index = new ASTNodeIndex(this);

	/**
	 * Lazily-built reverse index: definition node → list of referencing nodes.
	 * Wrapped in a {@link SoftReference} so the GC can reclaim it under memory
	 * pressure — it will be transparently rebuilt on the next
	 * {@link #getReferenceIndex()} access.  Automatically invalidated when the
	 * visitor is replaced (copy-on-write).
	 */
	private final AtomicReference<SoftReference<Map<ASTNode, List<ASTNode>>>> referenceIndexRef = new AtomicReference<>();

	/**
	 * Returns the lazily-built reference index, or {@code null} if it hasn't
	 * been built yet <em>or</em> the GC reclaimed it.  Callers should use
	 * {@link #setReferenceIndex(Map)} to store a freshly built index when
	 * this returns {@code null}.
	 */
	public Map<ASTNode, List<ASTNode>> getReferenceIndex() {
		SoftReference<Map<ASTNode, List<ASTNode>>> ref = referenceIndexRef.get();
		return ref != null ? ref.get() : null;
	}

	/**
	 * Stores a pre-built reference index. The index maps each definition
	 * node to the list of AST nodes that reference it.  The map is wrapped
	 * in a {@link SoftReference} to allow GC reclamation under memory pressure.
	 */
	public void setReferenceIndex(Map<ASTNode, List<ASTNode>> index) {
		this.referenceIndexRef.set((index != null) ? new SoftReference<>(index) : null);
	}

	/**
	 * Explicitly clears the reference index to free memory.  It will be
	 * lazily rebuilt on the next {@code getReferences()} call.
	 */
	public void clearReferenceIndex() {
		this.referenceIndexRef.set(null);
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
			data.setUri(uri);
			if (!stack.isEmpty()) {
				data.setParent(stack.peekLast());
			}
			lookup.put(new ASTLookupKey(node), data);
		}

		stack.addLast(node);
	}

	private void popASTNode() {
		stack.removeLast();
	}

	public List<ClassNode> getClassNodes() {
		return index.getClassNodes();
	}

	public List<ClassNode> getClassNodes(URI uri) {
		return index.getClassNodes(uri);
	}

	public ClassNode getClassNodeByName(String name) {
		return index.getClassNodeByName(name);
	}

	public List<ASTNode> getNodes() {
		return index.getNodes();
	}

	public List<ASTNode> getNodes(URI uri) {
		return index.getNodes(uri);
	}

	public ASTNode getNodeAtLineAndColumn(URI uri, int line, int column) {
		return index.getNodeAtLineAndColumn(uri, line, column);
	}

	public ASTNode getParent(ASTNode child) {
		return index.getParent(child);
	}

	public boolean contains(ASTNode ancestor, ASTNode descendant) {
		return index.contains(ancestor, descendant);
	}

	public URI getURI(ASTNode node) {
		return index.getURI(node);
	}

	public void visitCompilationUnit(CompilationUnit unit) {
		nodesByURI.clear();
		classNodesByURI.clear();
		classNodesByName.clear();
		lookup.clear();
		dependenciesByURI.clear();
		clearReferenceIndex();
		unit.iterator().forEachRemaining(this::visitSourceUnit);
	}

	public void visitCompilationUnit(CompilationUnit unit, Collection<URI> uris) {
		uris.forEach(uri -> {
			// clear all old nodes so that they may be replaced
			List<ASTNode> nodes = nodesByURI.remove(uri);
			if (nodes != null) {
				nodes.forEach(node -> lookup.remove(new ASTLookupKey(node)));
			}
			List<ClassNode> oldClassNodes = classNodesByURI.remove(uri);
			if (oldClassNodes != null) {
				oldClassNodes.forEach(cn -> classNodesByName.remove(cn.getName()));
			}
			dependenciesByURI.remove(uri);
		});
		unit.iterator().forEachRemaining(currentSourceUnit -> {
			URI uri = currentSourceUnit.getSource().getURI();
			if (!uris.contains(uri)) {
				return;
			}
			visitSourceUnit(currentSourceUnit);
		});
	}

	public int getNodeCount(URI uri) {
		return index.getNodeCount(uri);
	}

	public void restoreFromPrevious(URI uri, ASTNodeVisitor previous) {
		index.restoreFromPrevious(uri, previous);
	}

	public ASTNodeVisitor createSnapshotExcluding(Collection<URI> excludedURIs) {
		return ASTNodeIndex.createSnapshotExcluding(this, excludedURIs);
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
			node.getClasses().forEach(this::visitClass);
		} finally {
			popASTNode();
		}
	}

	// GroovyClassVisitor

	@Override
	public void visitClass(ClassNode node) {
		URI uri = sourceUnit.getSource().getURI();
		classNodesByURI.get(uri).add(node);
		classNodesByName.put(node.getName(), node);
		Set<String> deps = dependenciesByURI.get(uri);
		pushASTNode(node);
		try {
			trackNamedClassNodeDependency(node.getUnresolvedSuperClass(), deps);
			for (ClassNode unresolvedInterface : node.getUnresolvedInterfaces()) {
				trackNamedClassNodeDependency(unresolvedInterface, deps);
			}
			super.visitClass(node);
		} finally {
			popASTNode();
		}
	}

	private void trackNamedClassNodeDependency(ClassNode classNode, Set<String> deps) {
		if (classNode == null || classNode.getLineNumber() == -1) {
			return;
		}
		pushASTNode(classNode);
		try {
			addDependency(deps, classNode.getName());
		} finally {
			popASTNode();
		}
	}

	private void addDependency(Set<String> deps, String className) {
		index.addDependency(deps, className);
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
		if (node == null) {
			return;
		}
		URI uri = sourceUnit.getSource().getURI();
		Set<String> deps = dependenciesByURI.get(uri);

		processDirectImports(node.getImports(), deps);
		processStarImports(node.getStarImports(), deps);
		processDirectImports(node.getStaticImports().values(), deps);
		processDirectImports(node.getStaticStarImports().values(), deps);
	}

	private void processDirectImports(Iterable<ImportNode> imports, Set<String> deps) {
		for (ImportNode importNode : imports) {
			visitImportNode(importNode);
			addDependency(deps, importNode.getClassName());
		}
	}

	private void processStarImports(Iterable<ImportNode> imports, Set<String> deps) {
		for (ImportNode importNode : imports) {
			visitImportNode(importNode);
			addStarImportDependencies(deps, importNode.getPackageName());
		}
	}

	private void visitImportNode(ImportNode importNode) {
		pushASTNode(importNode);
		try {
			visitAnnotations(importNode);
			importNode.visit(this);
		} finally {
			popASTNode();
		}
	}

	private void addStarImportDependencies(Set<String> deps, String packageName) {
		index.addStarImportDependencies(deps, packageName);
	}

	@Override
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

	@Override
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
		popASTNode();
	}

	@Override
	public void visitField(FieldNode node) {
		pushASTNode(node);
		try {
			super.visitField(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitProperty(PropertyNode node) {
		pushASTNode(node);
		try {
			super.visitProperty(node);
		} finally {
			popASTNode();
		}
	}

	// GroovyCodeVisitor

	@Override
	public void visitBlockStatement(BlockStatement node) {
		pushASTNode(node);
		try {
			super.visitBlockStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitForLoop(ForStatement node) {
		pushASTNode(node);
		try {
			super.visitForLoop(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitWhileLoop(WhileStatement node) {
		pushASTNode(node);
		try {
			super.visitWhileLoop(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitDoWhileLoop(DoWhileStatement node) {
		pushASTNode(node);
		try {
			super.visitDoWhileLoop(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitIfElse(IfStatement node) {
		pushASTNode(node);
		try {
			super.visitIfElse(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitExpressionStatement(ExpressionStatement node) {
		pushASTNode(node);
		try {
			super.visitExpressionStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitReturnStatement(ReturnStatement node) {
		pushASTNode(node);
		try {
			super.visitReturnStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitAssertStatement(AssertStatement node) {
		pushASTNode(node);
		try {
			super.visitAssertStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitTryCatchFinally(TryCatchStatement node) {
		pushASTNode(node);
		try {
			super.visitTryCatchFinally(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitEmptyStatement(EmptyStatement node) {
		pushASTNode(node);
		try {
			super.visitEmptyStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitSwitch(SwitchStatement node) {
		pushASTNode(node);
		try {
			super.visitSwitch(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitCaseStatement(CaseStatement node) {
		pushASTNode(node);
		try {
			super.visitCaseStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitBreakStatement(BreakStatement node) {
		pushASTNode(node);
		try {
			super.visitBreakStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitContinueStatement(ContinueStatement node) {
		pushASTNode(node);
		try {
			super.visitContinueStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitSynchronizedStatement(SynchronizedStatement node) {
		pushASTNode(node);
		try {
			super.visitSynchronizedStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitThrowStatement(ThrowStatement node) {
		pushASTNode(node);
		try {
			super.visitThrowStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitMethodCallExpression(MethodCallExpression node) {
		pushASTNode(node);
		try {
			super.visitMethodCallExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
		pushASTNode(node);
		try {
			super.visitStaticMethodCallExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitConstructorCallExpression(ConstructorCallExpression node) {
		pushASTNode(node);
		try {
			super.visitConstructorCallExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitBinaryExpression(BinaryExpression node) {
		pushASTNode(node);
		try {
			super.visitBinaryExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitTernaryExpression(TernaryExpression node) {
		pushASTNode(node);
		try {
			super.visitTernaryExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitShortTernaryExpression(ElvisOperatorExpression node) {
		pushASTNode(node);
		try {
			super.visitShortTernaryExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitPostfixExpression(PostfixExpression node) {
		pushASTNode(node);
		try {
			super.visitPostfixExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitPrefixExpression(PrefixExpression node) {
		pushASTNode(node);
		try {
			super.visitPrefixExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitBooleanExpression(BooleanExpression node) {
		pushASTNode(node);
		try {
			super.visitBooleanExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitNotExpression(NotExpression node) {
		pushASTNode(node);
		try {
			super.visitNotExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitClosureExpression(ClosureExpression node) {
		pushASTNode(node);
		try {
			super.visitClosureExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitTupleExpression(TupleExpression node) {
		pushASTNode(node);
		try {
			super.visitTupleExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitListExpression(ListExpression node) {
		pushASTNode(node);
		try {
			super.visitListExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitArrayExpression(ArrayExpression node) {
		pushASTNode(node);
		try {
			super.visitArrayExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitMapExpression(MapExpression node) {
		pushASTNode(node);
		try {
			super.visitMapExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitMapEntryExpression(MapEntryExpression node) {
		pushASTNode(node);
		try {
			super.visitMapEntryExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitRangeExpression(RangeExpression node) {
		pushASTNode(node);
		try {
			super.visitRangeExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitSpreadExpression(SpreadExpression node) {
		pushASTNode(node);
		try {
			super.visitSpreadExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitSpreadMapExpression(SpreadMapExpression node) {
		pushASTNode(node);
		try {
			super.visitSpreadMapExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitMethodPointerExpression(MethodPointerExpression node) {
		pushASTNode(node);
		try {
			super.visitMethodPointerExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitUnaryMinusExpression(UnaryMinusExpression node) {
		pushASTNode(node);
		try {
			super.visitUnaryMinusExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitUnaryPlusExpression(UnaryPlusExpression node) {
		pushASTNode(node);
		try {
			super.visitUnaryPlusExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
		pushASTNode(node);
		try {
			super.visitBitwiseNegationExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitCastExpression(CastExpression node) {
		pushASTNode(node);
		try {
			super.visitCastExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitConstantExpression(ConstantExpression node) {
		pushASTNode(node);
		try {
			super.visitConstantExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitClassExpression(ClassExpression node) {
		pushASTNode(node);
		try {
			super.visitClassExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitVariableExpression(VariableExpression node) {
		pushASTNode(node);
		try {
			super.visitVariableExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitPropertyExpression(PropertyExpression node) {
		pushASTNode(node);
		try {
			super.visitPropertyExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitAttributeExpression(AttributeExpression node) {
		pushASTNode(node);
		try {
			super.visitAttributeExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitFieldExpression(FieldExpression node) {
		pushASTNode(node);
		try {
			super.visitFieldExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitGStringExpression(GStringExpression node) {
		pushASTNode(node);
		try {
			super.visitGStringExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitCatchStatement(CatchStatement node) {
		pushASTNode(node);
		try {
			super.visitCatchStatement(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitClosureListExpression(ClosureListExpression node) {
		pushASTNode(node);
		try {
			super.visitClosureListExpression(node);
		} finally {
			popASTNode();
		}
	}

	@Override
	public void visitBytecodeExpression(BytecodeExpression node) {
		pushASTNode(node);
		try {
			super.visitBytecodeExpression(node);
		} finally {
			popASTNode();
		}
	}

	// --- Dependency tracking ---

	public Map<URI, Set<String>> getDependenciesByURI() {
		return index.getDependenciesByURI();
	}

	public Set<URI> resolveSourceDependencies(URI fileURI) {
		return index.resolveSourceDependencies(fileURI);
	}
}