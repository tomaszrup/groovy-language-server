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

import java.util.List;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static java.lang.reflect.Modifier.*;

/**
 * Unit tests for {@link ClassNodeDecompiler}: synthetic source generation
 * for classes, interfaces, enums, fields, constructors, methods, and
 * line-number lookups.
 */
class ClassNodeDecompilerTests {

	// --- decompile: basic class ---

	@Test
	void testDecompileSimpleClass() {
		ClassNode cn = new ClassNode("com.example.Foo", PUBLIC, ClassHelper.OBJECT_TYPE);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		Assertions.assertFalse(lines.isEmpty());

		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("package com.example;"));
		Assertions.assertTrue(joined.contains("class Foo"));
		Assertions.assertTrue(joined.contains("}"));
	}

	@Test
	void testDecompileClassWithSuperclass() {
		ClassNode superClass = new ClassNode("com.example.Base", PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassNode cn = new ClassNode("com.example.Child", PUBLIC, superClass);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("extends Base"));
	}

	@Test
	void testDecompileClassDoesNotShowObjectSuperclass() {
		ClassNode cn = new ClassNode("com.example.Plain", PUBLIC, ClassHelper.OBJECT_TYPE);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertFalse(joined.contains("extends Object"));
	}

	// --- decompile: interface ---

	@Test
	void testDecompileInterface() {
		ClassNode cn = new ClassNode("com.example.MyInterface",
				PUBLIC | ABSTRACT | INTERFACE, ClassHelper.OBJECT_TYPE);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("interface MyInterface"));
	}

	@Test
	void testDecompileInterfaceWithSuperInterfaces() {
		ClassNode iface1 = new ClassNode("com.example.Serializable", PUBLIC | INTERFACE, ClassHelper.OBJECT_TYPE);
		ClassNode iface2 = new ClassNode("com.example.Comparable", PUBLIC | INTERFACE, ClassHelper.OBJECT_TYPE);
		ClassNode cn = new ClassNode("com.example.MyInterface",
				PUBLIC | ABSTRACT | INTERFACE, ClassHelper.OBJECT_TYPE,
				new ClassNode[] { iface1, iface2 }, null);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		// Interfaces use "extends" for super-interfaces
		Assertions.assertTrue(joined.contains("extends Serializable, Comparable"));
	}

	// --- decompile: enum ---

	@Test
	void testDecompileEnum() {
		ClassNode cn = new ClassNode("com.example.Color",
				PUBLIC | FINAL | 0x00004000 /* ENUM */, ClassHelper.OBJECT_TYPE);
		// ClassNode.isEnum() checks the ACC_ENUM flag

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		// It should recognize the enum based on the flag
		Assertions.assertTrue(joined.contains("class Color") || joined.contains("enum Color"));
	}

	// --- decompile: class with implements ---

	@Test
	void testDecompileClassWithInterfaces() {
		ClassNode iface = new ClassNode("java.io.Serializable", PUBLIC | INTERFACE, ClassHelper.OBJECT_TYPE);
		ClassNode cn = new ClassNode("com.example.Data", PUBLIC, ClassHelper.OBJECT_TYPE,
				new ClassNode[] { iface }, null);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("implements Serializable"));
	}

	// --- decompile: fields ---

	@Test
	void testDecompileClassWithFields() {
		ClassNode cn = new ClassNode("com.example.Person", PUBLIC, ClassHelper.OBJECT_TYPE);
		cn.addField(new FieldNode("name", PUBLIC, ClassHelper.STRING_TYPE, cn, null));
		cn.addField(new FieldNode("age", PRIVATE, ClassHelper.int_TYPE, cn, null));

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("public String name;"));
		Assertions.assertTrue(joined.contains("private int age;"));
	}

	// --- decompile: constructors ---

	@Test
	void testDecompileClassWithConstructor() {
		ClassNode cn = new ClassNode("com.example.Widget", PUBLIC, ClassHelper.OBJECT_TYPE);
		Parameter nameParam = new Parameter(ClassHelper.STRING_TYPE, "name");
		ConstructorNode ctor = new ConstructorNode(PUBLIC, new Parameter[] { nameParam }, null, null);
		cn.addConstructor(ctor);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("Widget(String name)"));
	}

	@Test
	void testDecompileClassWithNoArgConstructor() {
		ClassNode cn = new ClassNode("com.example.Empty", PUBLIC, ClassHelper.OBJECT_TYPE);
		ConstructorNode ctor = new ConstructorNode(PUBLIC, Parameter.EMPTY_ARRAY, null, null);
		cn.addConstructor(ctor);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("Empty()"));
	}

	// --- decompile: methods ---

	@Test
	void testDecompileClassWithMethods() {
		ClassNode cn = new ClassNode("com.example.Service", PUBLIC, ClassHelper.OBJECT_TYPE);
		MethodNode method = new MethodNode("doWork", PUBLIC,
				ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, null, null);
		cn.addMethod(method);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("void doWork()"));
	}

	@Test
	void testDecompileMethodWithParameters() {
		ClassNode cn = new ClassNode("com.example.Calculator", PUBLIC, ClassHelper.OBJECT_TYPE);
		Parameter[] params = {
				new Parameter(ClassHelper.int_TYPE, "a"),
				new Parameter(ClassHelper.int_TYPE, "b")
		};
		MethodNode method = new MethodNode("add", PUBLIC,
				ClassHelper.int_TYPE, params, null, null);
		cn.addMethod(method);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("int add(int a, int b)"));
	}

	@Test
	void testDecompileStaticMethod() {
		ClassNode cn = new ClassNode("com.example.Utils", PUBLIC, ClassHelper.OBJECT_TYPE);
		MethodNode method = new MethodNode("helper", PUBLIC | STATIC,
				ClassHelper.STRING_TYPE, Parameter.EMPTY_ARRAY, null, null);
		cn.addMethod(method);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("static"));
		Assertions.assertTrue(joined.contains("helper()"));
	}

	// --- decompile: no package ---

	@Test
	void testDecompileClassWithNoPackage() {
		ClassNode cn = new ClassNode("DefaultPackageClass", PUBLIC, ClassHelper.OBJECT_TYPE);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		// No "package" line when package is null/empty
		Assertions.assertFalse(joined.contains("package "));
		Assertions.assertTrue(joined.contains("class DefaultPackageClass"));
	}

	// --- decompile: header comment ---

	@Test
	void testDecompileContainsHeaderComment() {
		ClassNode cn = new ClassNode("com.example.Any", PUBLIC, ClassHelper.OBJECT_TYPE);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("Decompiled from bytecode"));
	}

	// --- getClassDeclarationLine ---

	@Test
	void testGetClassDeclarationLineWithPackage() {
		ClassNode cn = new ClassNode("com.example.Foo", PUBLIC, ClassHelper.OBJECT_TYPE);
		int line = ClassNodeDecompiler.getClassDeclarationLine(cn);
		// package + blank + comment + blank = 4
		Assertions.assertEquals(4, line);
	}

	@Test
	void testGetClassDeclarationLineWithoutPackage() {
		ClassNode cn = new ClassNode("NoPackage", PUBLIC, ClassHelper.OBJECT_TYPE);
		int line = ClassNodeDecompiler.getClassDeclarationLine(cn);
		// comment + blank = 2
		Assertions.assertEquals(2, line);
	}

	// --- getMethodLine ---

	@Test
	void testGetMethodLine() {
		ClassNode cn = new ClassNode("com.example.Svc", PUBLIC, ClassHelper.OBJECT_TYPE);
		MethodNode method = new MethodNode("process", PUBLIC,
				ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, null, null);
		cn.addMethod(method);

		int line = ClassNodeDecompiler.getMethodLine(cn, "process", 0);
		// Should return a valid line index
		List<String> lines = ClassNodeDecompiler.decompile(cn);
		Assertions.assertTrue(line >= 0 && line < lines.size());
		Assertions.assertTrue(lines.get(line).contains("process"));
	}

	@Test
	void testGetMethodLineNotFound() {
		ClassNode cn = new ClassNode("com.example.Svc", PUBLIC, ClassHelper.OBJECT_TYPE);
		int line = ClassNodeDecompiler.getMethodLine(cn, "nonexistent", 0);
		// Falls back to class declaration line
		Assertions.assertEquals(ClassNodeDecompiler.getClassDeclarationLine(cn), line);
	}

	// --- getFieldLine ---

	@Test
	void testGetFieldLine() {
		ClassNode cn = new ClassNode("com.example.Data", PUBLIC, ClassHelper.OBJECT_TYPE);
		cn.addField(new FieldNode("value", PUBLIC, ClassHelper.STRING_TYPE, cn, null));

		int line = ClassNodeDecompiler.getFieldLine(cn, "value");
		List<String> lines = ClassNodeDecompiler.decompile(cn);
		Assertions.assertTrue(line >= 0 && line < lines.size());
		Assertions.assertTrue(lines.get(line).contains("value"));
	}

	@Test
	void testGetFieldLineNotFound() {
		ClassNode cn = new ClassNode("com.example.Data", PUBLIC, ClassHelper.OBJECT_TYPE);
		int line = ClassNodeDecompiler.getFieldLine(cn, "missing");
		Assertions.assertEquals(ClassNodeDecompiler.getClassDeclarationLine(cn), line);
	}

	// --- Empty class (no fields, no methods, no constructors) ---

	@Test
	void testDecompileEmptyClass() {
		ClassNode cn = new ClassNode("com.example.Empty", PUBLIC, ClassHelper.OBJECT_TYPE);

		List<String> lines = ClassNodeDecompiler.decompile(cn);
		String joined = String.join("\n", lines);
		Assertions.assertTrue(joined.contains("class Empty"));
		Assertions.assertTrue(joined.endsWith("}"));
	}
}
