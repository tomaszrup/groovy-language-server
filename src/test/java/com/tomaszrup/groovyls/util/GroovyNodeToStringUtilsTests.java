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

import java.net.URI;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Unit tests for {@link GroovyNodeToStringUtils}: verifying string
 * representations of classes, methods, constructors, parameters,
 * and variables.
 */
class GroovyNodeToStringUtilsTests {

	// ------------------------------------------------------------------
	// classToString
	// ------------------------------------------------------------------

	@Test
	void testClassToStringSimple() {
		ASTNodeVisitor ast = compileAndVisit(
				"package com.example\n" +
				"class Foo {}\n");
		ClassNode classNode = findClass(ast, "Foo");
		String result = GroovyNodeToStringUtils.classToString(classNode);
		Assertions.assertTrue(result.contains("package com.example"));
		Assertions.assertTrue(result.contains("class Foo"));
	}

	@Test
	void testClassToStringInterface() {
		ASTNodeVisitor ast = compileAndVisit(
				"interface MyInterface {}\n");
		ClassNode classNode = findClass(ast, "MyInterface");
		String result = GroovyNodeToStringUtils.classToString(classNode);
		Assertions.assertTrue(result.contains("interface MyInterface"));
	}

	@Test
	void testClassToStringEnum() {
		ASTNodeVisitor ast = compileAndVisit(
				"enum Color { RED, GREEN, BLUE }\n");
		ClassNode classNode = findClass(ast, "Color");
		String result = GroovyNodeToStringUtils.classToString(classNode);
		Assertions.assertTrue(result.contains("enum Color"));
	}

	@Test
	void testClassToStringWithSuperclass() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Base {}\n" +
				"class Child extends Base {}\n");
		ClassNode classNode = findClass(ast, "Child");
		String result = GroovyNodeToStringUtils.classToString(classNode);
		Assertions.assertTrue(result.contains("class Child"));
		Assertions.assertTrue(result.contains("extends Base"));
	}

	@Test
	void testClassToStringWithInterfaces() {
		ASTNodeVisitor ast = compileAndVisit(
				"interface Greetable {}\n" +
				"class Hello implements Greetable {}\n");
		ClassNode classNode = findClass(ast, "Hello");
		String result = GroovyNodeToStringUtils.classToString(classNode);
		Assertions.assertTrue(result.contains("implements Greetable"));
	}

	@Test
	void testClassToStringAbstract() {
		ASTNodeVisitor ast = compileAndVisit(
				"abstract class Base {}\n");
		ClassNode classNode = findClass(ast, "Base");
		String result = GroovyNodeToStringUtils.classToString(classNode);
		Assertions.assertTrue(result.contains("abstract"));
		Assertions.assertTrue(result.contains("class Base"));
	}

	// ------------------------------------------------------------------
	// methodToString
	// ------------------------------------------------------------------

	@Test
	void testMethodToStringSimple() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void doSomething() {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "doSomething");
		String result = GroovyNodeToStringUtils.methodToString(method, ast);
		Assertions.assertTrue(result.contains("void"));
		Assertions.assertTrue(result.contains("doSomething"));
		Assertions.assertTrue(result.contains("()"));
	}

	@Test
	void testMethodToStringWithParameters() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  String greet(String name, int count) { return name }\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "greet");
		String result = GroovyNodeToStringUtils.methodToString(method, ast);
		Assertions.assertTrue(result.contains("String greet"));
		Assertions.assertTrue(result.contains("String name"));
		Assertions.assertTrue(result.contains("int count"));
	}

	@Test
	void testMethodToStringStatic() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  static int compute() { return 42 }\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "compute");
		String result = GroovyNodeToStringUtils.methodToString(method, ast);
		Assertions.assertTrue(result.contains("static"));
		Assertions.assertTrue(result.contains("int compute"));
	}

	@Test
	void testMethodToStringPrivate() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  private void secret() {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "secret");
		String result = GroovyNodeToStringUtils.methodToString(method, ast);
		Assertions.assertTrue(result.contains("private"));
	}

	// ------------------------------------------------------------------
	// constructorToString
	// ------------------------------------------------------------------

	@Test
	void testConstructorToString() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  Foo(String name) {}\n" +
				"}\n");
		ClassNode classNode = findClass(ast, "Foo");
		List<ConstructorNode> ctors = classNode.getDeclaredConstructors();
		Assertions.assertFalse(ctors.isEmpty());
		String result = GroovyNodeToStringUtils.constructorToString(ctors.get(0), ast);
		Assertions.assertTrue(result.contains("Foo("));
		Assertions.assertTrue(result.contains("String name"));
	}

	@Test
	void testConstructorToStringNoParams() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Bar {\n" +
				"  Bar() {}\n" +
				"}\n");
		ClassNode classNode = findClass(ast, "Bar");
		List<ConstructorNode> ctors = classNode.getDeclaredConstructors();
		Assertions.assertFalse(ctors.isEmpty());
		String result = GroovyNodeToStringUtils.constructorToString(ctors.get(0), ast);
		Assertions.assertTrue(result.contains("Bar()"));
	}

	// ------------------------------------------------------------------
	// parametersToString
	// ------------------------------------------------------------------

	@Test
	void testParametersToStringEmpty() {
		ASTNodeVisitor ast = compileAndVisit("class X {}\n");
		String result = GroovyNodeToStringUtils.parametersToString(new Parameter[0], ast);
		Assertions.assertEquals("", result);
	}

	@Test
	void testParametersToStringMultiple() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void m(int a, String b) {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "m");
		String result = GroovyNodeToStringUtils.parametersToString(method.getParameters(), ast);
		Assertions.assertTrue(result.contains("int a"));
		Assertions.assertTrue(result.contains("String b"));
		Assertions.assertTrue(result.contains(", "));
	}

	// ------------------------------------------------------------------
	// variableToString
	// ------------------------------------------------------------------

	@Test
	void testVariableToStringField() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  private static final String NAME = 'test'\n" +
				"}\n");
		ClassNode classNode = findClass(ast, "Foo");
		FieldNode field = classNode.getField("NAME");
		Assertions.assertNotNull(field);
		String result = GroovyNodeToStringUtils.variableToString(field, ast);
		Assertions.assertTrue(result.contains("private"));
		Assertions.assertTrue(result.contains("static"));
		Assertions.assertTrue(result.contains("final"));
		Assertions.assertTrue(result.contains("String"));
		Assertions.assertTrue(result.contains("NAME"));
	}

	@Test
	void testVariableToStringParameter() {
		ASTNodeVisitor ast = compileAndVisit(
				"class Foo {\n" +
				"  void m(int x) {}\n" +
				"}\n");
		MethodNode method = findMethod(ast, "Foo", "m");
		Parameter param = method.getParameters()[0];
		String result = GroovyNodeToStringUtils.variableToString(param, ast);
		Assertions.assertTrue(result.contains("int"));
		Assertions.assertTrue(result.contains("x"));
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
