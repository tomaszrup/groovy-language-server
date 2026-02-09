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

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

/**
 * Unit tests for {@link ClassSignature}: factory method, equals/hashCode
 * contract, and synthetic member filtering.
 */
class ClassSignatureTests {

	// ------------------------------------------------------------------
	// of() — basic class
	// ------------------------------------------------------------------

	@Test
	void testOfSimpleClass() {
		ClassNode classNode = new ClassNode("com.example.Foo", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotNull(sig);
		Assertions.assertTrue(sig.toString().contains("com.example.Foo"));
	}

	@Test
	void testOfClassWithSuperclass() {
		ClassNode superNode = new ClassNode("com.example.Base", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassNode classNode = new ClassNode("com.example.Child", Modifier.PUBLIC, superNode);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotNull(sig);
		Assertions.assertTrue(sig.toString().contains("com.example.Child"));
		Assertions.assertTrue(sig.toString().contains("super=com.example.Base"));
	}

	@Test
	void testOfClassWithNoSuperclass() {
		// A class with null unresolvedSuperClass
		ClassNode classNode = new ClassNode("com.example.Orphan", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotNull(sig);
		// Should not throw even when super is java.lang.Object (treated as non-null)
	}

	// ------------------------------------------------------------------
	// of() — interfaces
	// ------------------------------------------------------------------

	@Test
	void testOfClassWithInterfaces() {
		ClassNode iface1 = new ClassNode("com.example.Readable", Modifier.PUBLIC | Modifier.INTERFACE,
				ClassHelper.OBJECT_TYPE);
		ClassNode iface2 = new ClassNode("com.example.Writable", Modifier.PUBLIC | Modifier.INTERFACE,
				ClassHelper.OBJECT_TYPE);

		ClassNode classNode = new ClassNode("com.example.ReadWrite", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		classNode.setInterfaces(new ClassNode[] { iface1, iface2 });

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotNull(sig);
		Assertions.assertTrue(sig.toString().contains("ifaces=2"));
	}

	@Test
	void testOfClassWithNoInterfaces() {
		ClassNode classNode = new ClassNode("com.example.Plain", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertTrue(sig.toString().contains("ifaces=0"));
	}

	// ------------------------------------------------------------------
	// of() — methods
	// ------------------------------------------------------------------

	@Test
	void testOfClassWithMethods() {
		ClassNode classNode = new ClassNode("com.example.WithMethods", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		MethodNode method = new MethodNode(
				"greet", Modifier.PUBLIC,
				ClassHelper.STRING_TYPE,
				new Parameter[] { new Parameter(ClassHelper.STRING_TYPE, "name") },
				ClassNode.EMPTY_ARRAY,
				new BlockStatement());
		classNode.addMethod(method);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertTrue(sig.toString().contains("methods=1"));
	}

	@Test
	void testSyntheticMethodsAreIgnored() {
		ClassNode classNode = new ClassNode("com.example.WithSynthetic", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		MethodNode realMethod = new MethodNode(
				"doWork", Modifier.PUBLIC,
				ClassHelper.VOID_TYPE,
				Parameter.EMPTY_ARRAY,
				ClassNode.EMPTY_ARRAY,
				new BlockStatement());
		classNode.addMethod(realMethod);

		MethodNode syntheticMethod = new MethodNode(
				"$getStaticMetaClass", Modifier.PUBLIC,
				ClassHelper.OBJECT_TYPE,
				Parameter.EMPTY_ARRAY,
				ClassNode.EMPTY_ARRAY,
				new BlockStatement());
		syntheticMethod.setSynthetic(true);
		classNode.addMethod(syntheticMethod);

		ClassSignature sig = ClassSignature.of(classNode);

		// Only the real method should be counted
		Assertions.assertTrue(sig.toString().contains("methods=1"));
	}

	@Test
	void testStaticMethodIncludesStaticPrefix() {
		ClassNode classNode = new ClassNode("com.example.WithStatic", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		MethodNode staticMethod = new MethodNode(
				"create", Modifier.PUBLIC | Modifier.STATIC,
				ClassHelper.STRING_TYPE,
				Parameter.EMPTY_ARRAY,
				ClassNode.EMPTY_ARRAY,
				new BlockStatement());
		classNode.addMethod(staticMethod);

		ClassSignature sig1 = ClassSignature.of(classNode);

		// Create same class but non-static method
		ClassNode classNode2 = new ClassNode("com.example.WithStatic", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		MethodNode instanceMethod = new MethodNode(
				"create", Modifier.PUBLIC,
				ClassHelper.STRING_TYPE,
				Parameter.EMPTY_ARRAY,
				ClassNode.EMPTY_ARRAY,
				new BlockStatement());
		classNode2.addMethod(instanceMethod);

		ClassSignature sig2 = ClassSignature.of(classNode2);

		// Static vs instance should produce different signatures
		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testMethodWithMultipleParameters() {
		ClassNode classNode = new ClassNode("com.example.MultiParam", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		MethodNode method = new MethodNode(
				"compute", Modifier.PUBLIC,
				ClassHelper.int_TYPE,
				new Parameter[] {
						new Parameter(ClassHelper.STRING_TYPE, "input"),
						new Parameter(ClassHelper.int_TYPE, "count"),
						new Parameter(ClassHelper.boolean_TYPE, "flag")
				},
				ClassNode.EMPTY_ARRAY,
				new BlockStatement());
		classNode.addMethod(method);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotNull(sig);
		Assertions.assertTrue(sig.toString().contains("methods=1"));
	}

	// ------------------------------------------------------------------
	// of() — fields
	// ------------------------------------------------------------------

	@Test
	void testOfClassWithFields() {
		ClassNode classNode = new ClassNode("com.example.WithFields", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		FieldNode field = new FieldNode("name", Modifier.PRIVATE, ClassHelper.STRING_TYPE, classNode, null);
		classNode.addField(field);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertTrue(sig.toString().contains("fields=1"));
	}

	@Test
	void testSyntheticFieldsAreIgnored() {
		ClassNode classNode = new ClassNode("com.example.SynFields", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		FieldNode realField = new FieldNode("name", Modifier.PRIVATE, ClassHelper.STRING_TYPE, classNode, null);
		classNode.addField(realField);

		FieldNode syntheticField = new FieldNode("$staticClassInfo", Modifier.PRIVATE,
				ClassHelper.OBJECT_TYPE, classNode, null);
		syntheticField.setSynthetic(true);
		classNode.addField(syntheticField);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertTrue(sig.toString().contains("fields=1"));
	}

	// ------------------------------------------------------------------
	// of() — properties
	// ------------------------------------------------------------------

	@Test
	void testOfClassWithProperties() {
		ClassNode classNode = new ClassNode("com.example.WithProps", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		FieldNode fieldNode = new FieldNode("value", Modifier.PUBLIC, ClassHelper.int_TYPE, classNode, null);
		PropertyNode prop = new PropertyNode(fieldNode, Modifier.PUBLIC, null, null);
		classNode.addProperty(prop);

		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertTrue(sig.toString().contains("props=1"));
	}

	// ------------------------------------------------------------------
	// equals() / hashCode() contract
	// ------------------------------------------------------------------

	@Test
	void testEqualsIdenticalClassNodes() {
		ClassNode node1 = new ClassNode("com.example.Same", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		addSampleMembers(node1);

		ClassNode node2 = new ClassNode("com.example.Same", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		addSampleMembers(node2);

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertEquals(sig1, sig2);
		Assertions.assertEquals(sig1.hashCode(), sig2.hashCode());
	}

	@Test
	void testEqualsReflexive() {
		ClassNode classNode = new ClassNode("com.example.Ref", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertEquals(sig, sig);
	}

	@Test
	void testEqualsSymmetric() {
		ClassNode node1 = new ClassNode("com.example.Sym", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassNode node2 = new ClassNode("com.example.Sym", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertEquals(sig1, sig2);
		Assertions.assertEquals(sig2, sig1);
	}

	@Test
	void testNotEqualsNull() {
		ClassNode classNode = new ClassNode("com.example.NotNull", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotEquals(null, sig);
	}

	@Test
	void testNotEqualsDifferentType() {
		ClassNode classNode = new ClassNode("com.example.DiffType", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassSignature sig = ClassSignature.of(classNode);

		Assertions.assertNotEquals("not a ClassSignature", sig);
	}

	@Test
	void testNotEqualsDifferentName() {
		ClassNode node1 = new ClassNode("com.example.Alpha", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassNode node2 = new ClassNode("com.example.Beta", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentSuperclass() {
		ClassNode super1 = new ClassNode("com.example.Base1", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		ClassNode super2 = new ClassNode("com.example.Base2", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);

		ClassNode node1 = new ClassNode("com.example.Child", Modifier.PUBLIC, super1);
		ClassNode node2 = new ClassNode("com.example.Child", Modifier.PUBLIC, super2);

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentInterfaces() {
		ClassNode iface1 = new ClassNode("com.example.Iface1", Modifier.PUBLIC | Modifier.INTERFACE,
				ClassHelper.OBJECT_TYPE);
		ClassNode iface2 = new ClassNode("com.example.Iface2", Modifier.PUBLIC | Modifier.INTERFACE,
				ClassHelper.OBJECT_TYPE);

		ClassNode node1 = new ClassNode("com.example.Impl", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node1.setInterfaces(new ClassNode[] { iface1 });

		ClassNode node2 = new ClassNode("com.example.Impl", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node2.setInterfaces(new ClassNode[] { iface2 });

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentMethods() {
		ClassNode node1 = new ClassNode("com.example.DiffMethods", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node1.addMethod(new MethodNode("foo", Modifier.PUBLIC, ClassHelper.VOID_TYPE,
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement()));

		ClassNode node2 = new ClassNode("com.example.DiffMethods", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node2.addMethod(new MethodNode("bar", Modifier.PUBLIC, ClassHelper.VOID_TYPE,
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement()));

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentFields() {
		ClassNode node1 = new ClassNode("com.example.DiffFields", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node1.addField(new FieldNode("alpha", Modifier.PRIVATE, ClassHelper.STRING_TYPE, node1, null));

		ClassNode node2 = new ClassNode("com.example.DiffFields", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node2.addField(new FieldNode("beta", Modifier.PRIVATE, ClassHelper.STRING_TYPE, node2, null));

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentFieldTypes() {
		ClassNode node1 = new ClassNode("com.example.DiffFieldTypes", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node1.addField(new FieldNode("value", Modifier.PRIVATE, ClassHelper.STRING_TYPE, node1, null));

		ClassNode node2 = new ClassNode("com.example.DiffFieldTypes", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node2.addField(new FieldNode("value", Modifier.PRIVATE, ClassHelper.int_TYPE, node2, null));

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentMethodReturnType() {
		ClassNode node1 = new ClassNode("com.example.DiffReturn", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node1.addMethod(new MethodNode("getData", Modifier.PUBLIC, ClassHelper.STRING_TYPE,
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement()));

		ClassNode node2 = new ClassNode("com.example.DiffReturn", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node2.addMethod(new MethodNode("getData", Modifier.PUBLIC, ClassHelper.int_TYPE,
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement()));

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	@Test
	void testNotEqualsDifferentMethodParameters() {
		ClassNode node1 = new ClassNode("com.example.DiffParams", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node1.addMethod(new MethodNode("process", Modifier.PUBLIC, ClassHelper.VOID_TYPE,
				new Parameter[] { new Parameter(ClassHelper.STRING_TYPE, "input") },
				ClassNode.EMPTY_ARRAY, new BlockStatement()));

		ClassNode node2 = new ClassNode("com.example.DiffParams", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		node2.addMethod(new MethodNode("process", Modifier.PUBLIC, ClassHelper.VOID_TYPE,
				new Parameter[] { new Parameter(ClassHelper.int_TYPE, "input") },
				ClassNode.EMPTY_ARRAY, new BlockStatement()));

		ClassSignature sig1 = ClassSignature.of(node1);
		ClassSignature sig2 = ClassSignature.of(node2);

		Assertions.assertNotEquals(sig1, sig2);
	}

	// ------------------------------------------------------------------
	// toString()
	// ------------------------------------------------------------------

	@Test
	void testToStringFormat() {
		ClassNode classNode = new ClassNode("com.example.ToStr", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
		classNode.addField(new FieldNode("x", Modifier.PRIVATE, ClassHelper.int_TYPE, classNode, null));

		ClassSignature sig = ClassSignature.of(classNode);
		String str = sig.toString();

		Assertions.assertTrue(str.startsWith("ClassSignature{"));
		Assertions.assertTrue(str.contains("com.example.ToStr"));
		Assertions.assertTrue(str.contains("super="));
		Assertions.assertTrue(str.contains("ifaces="));
		Assertions.assertTrue(str.contains("methods="));
		Assertions.assertTrue(str.contains("fields="));
		Assertions.assertTrue(str.contains("props="));
	}

	// ------------------------------------------------------------------
	// Helper
	// ------------------------------------------------------------------

	private void addSampleMembers(ClassNode classNode) {
		classNode.addMethod(new MethodNode("getValue", Modifier.PUBLIC, ClassHelper.STRING_TYPE,
				Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, new BlockStatement()));
		classNode.addField(new FieldNode("id", Modifier.PRIVATE, ClassHelper.int_TYPE, classNode, null));
	}
}
