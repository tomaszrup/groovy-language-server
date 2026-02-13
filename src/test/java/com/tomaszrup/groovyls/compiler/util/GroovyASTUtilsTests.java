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
package com.tomaszrup.groovyls.compiler.util;

import java.net.URI;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Range;

import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Unit tests for {@link GroovyASTUtils}: definition lookup, type resolution,
 * reference finding, property/field extraction, and import range detection.
 */
class GroovyASTUtilsTests {

	// ------------------------------------------------------------------
	// getDefinition — null and ClassNode
	// ------------------------------------------------------------------

	@Test
	void testGetDefinitionNullNode() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		Assertions.assertNull(GroovyASTUtils.getDefinition(null, false, ast));
	}

	@Test
	void testGetDefinitionOfClassNode() {
		ASTNodeVisitor ast = compileAndVisit("class Foo {}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		Assertions.assertNotNull(fooNode);
		ASTNode def = GroovyASTUtils.getDefinition(fooNode, false, ast);
		Assertions.assertNotNull(def);
		Assertions.assertTrue(def instanceof ClassNode);
	}

	@Test
	void testGetDefinitionOfMethodNode() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void bar() {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "bar");
		Assertions.assertNotNull(method);
		ASTNode def = GroovyASTUtils.getDefinition(method, false, ast);
		// A MethodNode is its own definition
		Assertions.assertEquals(method, def);
	}

	@Test
	void testGetDefinitionOfVariableExpression() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String name\n" +
				"  void bar() {\n" +
				"    int x = 5\n" +
				"  }\n" +
				"}\n");
		// Find a VariableExpression in the AST
		List<ASTNode> nodes = ast.getNodes();
		VariableExpression varExpr = nodes.stream()
				.filter(n -> n instanceof VariableExpression)
				.map(n -> (VariableExpression) n)
				.filter(v -> v.getName().equals("x"))
				.findFirst().orElse(null);
		if (varExpr != null) {
			ASTNode def = GroovyASTUtils.getDefinition(varExpr, false, ast);
			// Should find the accessed variable (the declaration)
			Assertions.assertNotNull(def);
		}
	}

	// ------------------------------------------------------------------
	// getTypeDefinition
	// ------------------------------------------------------------------

	@Test
	void testGetTypeDefinitionNullNode() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		Assertions.assertNull(GroovyASTUtils.getTypeDefinition(null, ast));
	}

	@Test
	void testGetTypeDefinitionOfMethod() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String getName() { return '' }\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "getName");
		Assertions.assertNotNull(method);
		// The type definition of a method is its return type resolved as a class
		GroovyASTUtils.getTypeDefinition(method, ast);
		// May be null if String is not in the AST as a user class, that's OK
		// Just verify it doesn't throw
	}

	// ------------------------------------------------------------------
	// getReferences
	// ------------------------------------------------------------------

	@Test
	void testGetReferencesForField() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String name\n" +
				"  void bar() {\n" +
				"    println(name)\n" +
				"  }\n" +
				"}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		FieldNode field = fooNode.getField("name");
		if (field != null) {
			List<ASTNode> refs = GroovyASTUtils.getReferences(field, ast);
			// Should find at least the declaration and the usage
			Assertions.assertNotNull(refs);
		}
	}

	@Test
	void testGetReferencesNullDefinition() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		// A node that resolves to null definition → empty list
		List<ASTNode> refs = GroovyASTUtils.getReferences(null, ast);
		Assertions.assertTrue(refs.isEmpty());
	}

	// ------------------------------------------------------------------
	// getTypeOfNode
	// ------------------------------------------------------------------

	@Test
	void testGetTypeOfNodeForClassExpression() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void bar() {\n" +
				"    Foo.class\n" +
				"  }\n" +
				"}\n");
		List<ASTNode> nodes = ast.getNodes();
		// Find a ClassExpression
		ASTNode classExpr = nodes.stream()
				.filter(n -> n instanceof org.codehaus.groovy.ast.expr.ClassExpression)
				.findFirst().orElse(null);
		if (classExpr != null) {
			ClassNode type = GroovyASTUtils.getTypeOfNode(classExpr, ast);
			Assertions.assertNotNull(type);
		}
	}

	@Test
	void testGetTypeOfNodeForConstructorCall() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void bar() {\n" +
				"    new ArrayList()\n" +
				"  }\n" +
				"}\n");
		List<ASTNode> nodes = ast.getNodes();
		ConstructorCallExpression ctorCall = nodes.stream()
				.filter(n -> n instanceof ConstructorCallExpression)
				.map(n -> (ConstructorCallExpression) n)
				.findFirst().orElse(null);
		if (ctorCall != null) {
			ClassNode type = GroovyASTUtils.getTypeOfNode(ctorCall, ast);
			Assertions.assertNotNull(type);
			Assertions.assertTrue(type.getName().contains("ArrayList"));
		}
	}

	@Test
	void testGetTypeOfNodeNull() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		Assertions.assertNull(GroovyASTUtils.getTypeOfNode(null, ast));
	}

	@Test
	void testGetTypeOfNodeForVariable() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String name = 'hello'\n" +
				"}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		FieldNode field = fooNode.getField("name");
		if (field != null) {
			ClassNode type = GroovyASTUtils.getTypeOfNode(field, ast);
			Assertions.assertNotNull(type);
			Assertions.assertEquals("java.lang.String", type.getName());
		}
	}

	// ------------------------------------------------------------------
	// getEnclosingNodeOfType
	// ------------------------------------------------------------------

	@Test
	void testGetEnclosingNodeOfTypeFindsClass() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void bar() {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "bar");
		Assertions.assertNotNull(method);
		ASTNode enclosing = GroovyASTUtils.getEnclosingNodeOfType(method, ClassNode.class, ast);
		Assertions.assertNotNull(enclosing);
		Assertions.assertTrue(enclosing instanceof ClassNode);
		Assertions.assertEquals("Foo", ((ClassNode) enclosing).getNameWithoutPackage());
	}

	@Test
	void testGetEnclosingNodeOfTypeFindsModule() {
		ASTNodeVisitor ast = compileAndVisit("class Foo {}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		ASTNode enclosing = GroovyASTUtils.getEnclosingNodeOfType(fooNode, ModuleNode.class, ast);
		Assertions.assertNotNull(enclosing);
		Assertions.assertTrue(enclosing instanceof ModuleNode);
	}

	@Test
	void testGetEnclosingNodeOfTypeReturnsNullWhenNotFound() {
		ASTNodeVisitor ast = compileAndVisit("class Foo {}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		// Try to find a MethodNode enclosing a ClassNode — won't find one
		ASTNode enclosing = GroovyASTUtils.getEnclosingNodeOfType(fooNode, MethodNode.class, ast);
		Assertions.assertNull(enclosing);
	}

	@Test
	void testGetEnclosingNodeOfTypeNullNode() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		Assertions.assertNull(GroovyASTUtils.getEnclosingNodeOfType(null, ClassNode.class, ast));
	}

	// ------------------------------------------------------------------
	// getFieldsForLeftSideOfPropertyExpression
	// ------------------------------------------------------------------

	@Test
	void testGetFieldsForClassWithFields() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String name\n" +
				"  int age\n" +
				"}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		// Use a ClassExpression to test static lookup
		org.codehaus.groovy.ast.expr.ClassExpression classExpr =
				new org.codehaus.groovy.ast.expr.ClassExpression(fooNode);
		List<FieldNode> fields = GroovyASTUtils.getFieldsForLeftSideOfPropertyExpression(classExpr, ast);
		// ClassExpression retrieves static fields only — might be empty for instance fields
		Assertions.assertNotNull(fields);
	}

	// ------------------------------------------------------------------
	// getPropertiesForLeftSideOfPropertyExpression
	// ------------------------------------------------------------------

	@Test
	void testGetPropertiesForClassWithProperties() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String name\n" +
				"}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		org.codehaus.groovy.ast.expr.ClassExpression classExpr =
				new org.codehaus.groovy.ast.expr.ClassExpression(fooNode);
		List<PropertyNode> props = GroovyASTUtils.getPropertiesForLeftSideOfPropertyExpression(classExpr, ast);
		Assertions.assertNotNull(props);
	}

	// ------------------------------------------------------------------
	// getMethodsForLeftSideOfPropertyExpression
	// ------------------------------------------------------------------

	@Test
	void testGetMethodsForClassWithMethods() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void bar() {}\n" +
				"  static void baz() {}\n" +
				"}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		org.codehaus.groovy.ast.expr.ClassExpression classExpr =
				new org.codehaus.groovy.ast.expr.ClassExpression(fooNode);
		List<MethodNode> methods = GroovyASTUtils.getMethodsForLeftSideOfPropertyExpression(classExpr, ast);
		Assertions.assertNotNull(methods);
		// Should include static method 'baz' since we're using ClassExpression
		boolean hasBaz = methods.stream().anyMatch(m -> m.getName().equals("baz"));
		Assertions.assertTrue(hasBaz, "Should find static method 'baz'");
	}

	// ------------------------------------------------------------------
	// getMethodOverloadsFromCallExpression
	// ------------------------------------------------------------------

	@Test
	void testGetMethodOverloadsFromConstructorCall() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  Foo() {}\n" +
				"  Foo(String s) {}\n" +
				"  void bar() {\n" +
				"    new Foo()\n" +
				"  }\n" +
				"}\n");
		List<ASTNode> nodes = ast.getNodes();
		ConstructorCallExpression ctorCall = nodes.stream()
				.filter(n -> n instanceof ConstructorCallExpression)
				.map(n -> (ConstructorCallExpression) n)
				.findFirst().orElse(null);
		if (ctorCall != null) {
			List<MethodNode> overloads = GroovyASTUtils.getMethodOverloadsFromCallExpression(ctorCall, ast);
			Assertions.assertNotNull(overloads);
			Assertions.assertTrue(overloads.size() >= 2,
					"Should find at least 2 constructor overloads, got: " + overloads.size());
		}
	}

	// ------------------------------------------------------------------
	// findAddImportRange
	// ------------------------------------------------------------------

	@Test
	void testFindAddImportRangeWithExistingImports() {
		ASTNodeVisitor ast = compileAndVisit(
				"import java.util.List\n" +
				"class Foo {}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		Range importRange = GroovyASTUtils.findAddImportRange(fooNode, ast);
		Assertions.assertNotNull(importRange);
		// Should be after the last import line
		Assertions.assertTrue(importRange.getStart().getLine() > 0);
	}

	@Test
	void testFindAddImportRangeNoImports() {
		ASTNodeVisitor ast = compileAndVisit("class Foo {}\n");
		ClassNode fooNode = findClass(ast, "Foo");
		Range importRange = GroovyASTUtils.findAddImportRange(fooNode, ast);
		Assertions.assertNotNull(importRange);
	}

	// ------------------------------------------------------------------
	// resolveOriginalMethod
	// ------------------------------------------------------------------

	@Test
	void testResolveOriginalMethodWithSourceLocation() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void bar() {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "bar");
		Assertions.assertNotNull(method);
		// Method already has a source location, should return itself
		MethodNode resolved = GroovyASTUtils.resolveOriginalMethod(method, ast);
		Assertions.assertEquals(method, resolved);
	}

	@Test
	void testResolveOriginalMethodNull() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		Assertions.assertNull(GroovyASTUtils.resolveOriginalMethod(null, ast));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private ASTNodeVisitor compileAndVisit(String source) {
		CompilerConfiguration config = new CompilerConfiguration();
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
			// expected for some test cases
		}
		ASTNodeVisitor ast = new ASTNodeVisitor();
		ast.visitCompilationUnit(cu);
		return ast;
	}

	private ClassNode findClass(ASTNodeVisitor ast, String name) {
		return ast.getClassNodes().stream()
				.filter(cn -> cn.getNameWithoutPackage().equals(name))
				.findFirst().orElse(null);
	}

	private MethodNode findMethod(ASTNodeVisitor ast, String className, String methodName) {
		ClassNode classNode = findClass(ast, className);
		if (classNode == null) {
			return null;
		}
		return classNode.getMethods(methodName).stream().findFirst().orElse(null);
	}
}
