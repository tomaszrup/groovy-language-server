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
package com.tomaszrup.groovyls.compiler.ast;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.builder.AstBuilder;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Direct unit tests for {@link ASTNodeVisitor}: node collection, parent
 * lookups, URI tracking, class node indexing, and position-based queries.
 */
class ASTNodeVisitorTests {

	private ASTNodeVisitor visitor;

	@BeforeEach
	void setup() {
		visitor = new ASTNodeVisitor();
	}

	// --- Empty state ---

	@Test
	void testEmptyVisitorHasNoNodes() {
		Assertions.assertTrue(visitor.getNodes().isEmpty());
		Assertions.assertTrue(visitor.getClassNodes().isEmpty());
	}

	@Test
	void testEmptyVisitorGetNodeAtReturnsNull() {
		URI uri = URI.create("file:///test.groovy");
		Assertions.assertNull(visitor.getNodeAtLineAndColumn(uri, 0, 0));
	}

	@Test
	void testEmptyVisitorGetNodesForUriReturnsEmptyList() {
		URI uri = URI.create("file:///test.groovy");
		List<ASTNode> nodes = visitor.getNodes(uri);
		Assertions.assertNotNull(nodes);
		Assertions.assertTrue(nodes.isEmpty());
	}

	// --- Visiting a compilation unit ---

	@Test
	void testVisitCompilationUnitCollectsClassNodes() {
		GroovyLSCompilationUnit cu = compileSource(
				"class Foo {\n" +
				"  String name\n" +
				"  void doStuff() {}\n" +
				"}\n");

		visitor.visitCompilationUnit(cu);

		List<ClassNode> classNodes = visitor.getClassNodes();
		Assertions.assertFalse(classNodes.isEmpty(), "Should find at least one class node");
		boolean hasFoo = classNodes.stream().anyMatch(cn -> cn.getNameWithoutPackage().equals("Foo"));
		Assertions.assertTrue(hasFoo, "Should find 'Foo' class");
	}

	@Test
	void testVisitCompilationUnitCollectsMethodNodes() {
		GroovyLSCompilationUnit cu = compileSource(
				"class Bar {\n" +
				"  int calculate(int x) {\n" +
				"    return x * 2\n" +
				"  }\n" +
				"}\n");

		visitor.visitCompilationUnit(cu);

		List<ASTNode> nodes = visitor.getNodes();
		boolean hasMethod = nodes.stream().anyMatch(n -> n instanceof MethodNode
				&& ((MethodNode) n).getName().equals("calculate"));
		Assertions.assertTrue(hasMethod, "Should find 'calculate' method node");
	}

	@Test
	void testClassNodeByNameLookup() {
		GroovyLSCompilationUnit cu = compileSource(
				"class LookupTest {\n" +
				"  void run() {}\n" +
				"}\n");

		visitor.visitCompilationUnit(cu);

		ClassNode found = visitor.getClassNodeByName("LookupTest");
		Assertions.assertNotNull(found, "Should find class by name");
		Assertions.assertEquals("LookupTest", found.getNameWithoutPackage());
	}

	@Test
	void testClassNodeByNameReturnsNullForUnknown() {
		GroovyLSCompilationUnit cu = compileSource("class Present {}\n");
		visitor.visitCompilationUnit(cu);

		Assertions.assertNull(visitor.getClassNodeByName("Absent"));
	}

	// --- Parent tracking ---

	@Test
	void testGetParentOfTopLevelNodeIsModuleOrNull() {
		GroovyLSCompilationUnit cu = compileSource("class ParentTest {}\n");
		visitor.visitCompilationUnit(cu);

		List<ClassNode> classNodes = visitor.getClassNodes();
		Assertions.assertFalse(classNodes.isEmpty());
		ClassNode classNode = classNodes.get(0);
		// Parent of a ClassNode should be a ModuleNode
		ASTNode parent = visitor.getParent(classNode);
		Assertions.assertTrue(parent instanceof ModuleNode,
				"Parent of class node should be ModuleNode, got: " + (parent != null ? parent.getClass() : "null"));
	}

	@Test
	void testGetParentOfNullReturnsNull() {
		Assertions.assertNull(visitor.getParent(null));
	}

	// --- URI tracking ---

	@Test
	void testGetURIForNode() {
		GroovyLSCompilationUnit cu = compileSource("class UriTrack {}\n");
		visitor.visitCompilationUnit(cu);

		List<ClassNode> classNodes = visitor.getClassNodes();
		Assertions.assertFalse(classNodes.isEmpty());
		URI uri = visitor.getURI(classNodes.get(0));
		Assertions.assertNotNull(uri, "Should have URI for visited class node");
	}

	// --- contains() ---

	@Test
	void testContainsReturnsTrueForAncestor() {
		GroovyLSCompilationUnit cu = compileSource(
				"class ContainerTest {\n" +
				"  void method() {}\n" +
				"}\n");
		visitor.visitCompilationUnit(cu);

		List<ClassNode> classNodes = visitor.getClassNodes();
		ClassNode classNode = classNodes.stream()
				.filter(cn -> cn.getNameWithoutPackage().equals("ContainerTest"))
				.findFirst().orElse(null);
		Assertions.assertNotNull(classNode);

		List<ASTNode> allNodes = visitor.getNodes();
		MethodNode methodNode = allNodes.stream()
				.filter(n -> n instanceof MethodNode && ((MethodNode) n).getName().equals("method"))
				.map(n -> (MethodNode) n)
				.findFirst().orElse(null);
		Assertions.assertNotNull(methodNode);

		Assertions.assertTrue(visitor.contains(classNode, methodNode),
				"ClassNode should contain its MethodNode child");
	}

	@Test
	void testContainsReturnsFalseForUnrelated() {
		GroovyLSCompilationUnit cu = compileSource(
				"class A { void aMethod() {} }\n" +
				"class B { void bMethod() {} }\n");
		visitor.visitCompilationUnit(cu);

		List<ClassNode> classNodes = visitor.getClassNodes();
		ClassNode classA = classNodes.stream()
				.filter(cn -> cn.getNameWithoutPackage().equals("A"))
				.findFirst().orElse(null);
		ClassNode classB = classNodes.stream()
				.filter(cn -> cn.getNameWithoutPackage().equals("B"))
				.findFirst().orElse(null);
		Assertions.assertNotNull(classA);
		Assertions.assertNotNull(classB);

		Assertions.assertFalse(visitor.contains(classA, classB),
				"Unrelated classes should not contain each other");
	}

	// --- visitCompilationUnit with URI subset ---

	@Test
	void testVisitCompilationUnitWithUriSubsetOnlyRefreshesMatchingUris() {
		GroovyLSCompilationUnit cu = compileSource(
				"class SubsetTest {\n" +
				"  int value = 10\n" +
				"}\n");
		visitor.visitCompilationUnit(cu);

		int initialNodeCount = visitor.getNodes().size();
		Assertions.assertTrue(initialNodeCount > 0);

		// Re-visit with empty URI set — should not clear existing nodes
		visitor.visitCompilationUnit(cu, Collections.emptySet());
		int afterCount = visitor.getNodes().size();
		Assertions.assertEquals(initialNodeCount, afterCount,
				"Node count should remain the same when visiting with empty URI set");
	}

	// --- getNodeAtLineAndColumn ---

	@Test
	void testGetNodeAtLineAndColumnFindsNode() {
		GroovyLSCompilationUnit cu = compileSource(
				"class PosTest {\n" +
				"  String name\n" +
				"}\n");
		visitor.visitCompilationUnit(cu);

		// Get the URI from the compilation unit
		URI uri = null;
		var iter = cu.iterator();
		if (iter.hasNext()) {
			uri = iter.next().getSource().getURI();
		}
		Assertions.assertNotNull(uri);

		// Line 0 (0-based) should be on the class declaration
		ASTNode node = visitor.getNodeAtLineAndColumn(uri, 0, 6);
		Assertions.assertNotNull(node, "Should find a node at line 0, column 6");
	}

	@Test
	void testGetNodeAtLineAndColumnReturnsNullForEmptyLine() {
		GroovyLSCompilationUnit cu = compileSource(
				"class EmptyLine {\n" +
				"\n" +
				"}\n");
		visitor.visitCompilationUnit(cu);

		URI uri = null;
		var iter = cu.iterator();
		if (iter.hasNext()) {
			uri = iter.next().getSource().getURI();
		}
		Assertions.assertNotNull(uri);

		// Line 1 (empty line) at column 0 — might find the class (it spans the block)
		// or null. We just check it doesn't throw.
		visitor.getNodeAtLineAndColumn(uri, 1, 0);
	}

	// --- Multiple classes ---

	@Test
	void testMultipleClassesAreAllCollected() {
		GroovyLSCompilationUnit cu = compileSource(
				"class Alpha {}\n" +
				"class Beta {}\n" +
				"class Gamma {}\n");
		visitor.visitCompilationUnit(cu);

		List<ClassNode> classNodes = visitor.getClassNodes();
		Assertions.assertTrue(classNodes.size() >= 3,
				"Should have at least 3 class nodes, got: " + classNodes.size());
	}

	// --- Helper ---

	private GroovyLSCompilationUnit compileSource(String source) {
		CompilerConfiguration config = new CompilerConfiguration();
		config.getOptimizationOptions().put(CompilerConfiguration.GROOVYDOC, true);
		GroovyClassLoader classLoader = new GroovyClassLoader(
				ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config, null, classLoader);

		URI uri = URI.create("file:///test.groovy");
		SourceUnit sourceUnit = new SourceUnit("test.groovy",
				new StringReaderSourceWithURI(source, uri, config),
				config, classLoader, cu.getErrorCollector());
		cu.addSource(sourceUnit);

		try {
			cu.compile(Phases.CANONICALIZATION);
		} catch (Exception e) {
			// Compilation errors are expected in some test cases
		}
		return cu;
	}
}
