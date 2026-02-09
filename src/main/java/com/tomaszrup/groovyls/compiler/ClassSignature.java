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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Captures the "public API" signature of a {@link ClassNode} for change
 * detection during incremental compilation. Two {@code ClassSignature}
 * instances are equal if and only if the class's public-facing API
 * (methods, fields, properties, superclass, interfaces) is identical.
 *
 * <p>Used to determine whether dependent files need recompilation after
 * an incremental compile of a single file. If the changed file's class
 * signatures haven't changed, dependents don't need recompilation.</p>
 */
public class ClassSignature {
	private final String name;
	private final String superClassName;
	private final Set<String> interfaceNames;
	private final Set<String> methodSignatures;
	private final Set<String> fieldSignatures;
	private final Set<String> propertySignatures;

	private ClassSignature(String name, String superClassName,
			Set<String> interfaceNames,
			Set<String> methodSignatures,
			Set<String> fieldSignatures,
			Set<String> propertySignatures) {
		this.name = name;
		this.superClassName = superClassName;
		this.interfaceNames = interfaceNames;
		this.methodSignatures = methodSignatures;
		this.fieldSignatures = fieldSignatures;
		this.propertySignatures = propertySignatures;
	}

	/**
	 * Captures the public API signature of a {@link ClassNode}.
	 *
	 * @param classNode the class to capture
	 * @return a signature representing the class's public API
	 */
	public static ClassSignature of(ClassNode classNode) {
		String name = classNode.getName();
		String superClassName = classNode.getUnresolvedSuperClass() != null
				? classNode.getUnresolvedSuperClass().getName()
				: null;

		Set<String> interfaceNames = new TreeSet<>();
		for (ClassNode iface : classNode.getUnresolvedInterfaces()) {
			interfaceNames.add(iface.getName());
		}

		Set<String> methodSignatures = new TreeSet<>();
		for (MethodNode method : classNode.getMethods()) {
			if (method.isSynthetic()) {
				continue;
			}
			StringBuilder sb = new StringBuilder();
			if (method.isStatic()) {
				sb.append("static ");
			}
			sb.append(method.getReturnType().getName()).append(' ');
			sb.append(method.getName()).append('(');
			Parameter[] params = method.getParameters();
			for (int i = 0; i < params.length; i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(params[i].getType().getName());
			}
			sb.append(')');
			methodSignatures.add(sb.toString());
		}

		Set<String> fieldSignatures = new TreeSet<>();
		for (FieldNode field : classNode.getFields()) {
			if (field.isSynthetic()) {
				continue;
			}
			fieldSignatures.add(field.getType().getName() + " " + field.getName());
		}

		Set<String> propertySignatures = new TreeSet<>();
		for (PropertyNode prop : classNode.getProperties()) {
			propertySignatures.add(prop.getType().getName() + " " + prop.getName());
		}

		return new ClassSignature(name, superClassName, interfaceNames,
				methodSignatures, fieldSignatures, propertySignatures);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ClassSignature)) {
			return false;
		}
		ClassSignature that = (ClassSignature) o;
		return Objects.equals(name, that.name)
				&& Objects.equals(superClassName, that.superClassName)
				&& Objects.equals(interfaceNames, that.interfaceNames)
				&& Objects.equals(methodSignatures, that.methodSignatures)
				&& Objects.equals(fieldSignatures, that.fieldSignatures)
				&& Objects.equals(propertySignatures, that.propertySignatures);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, superClassName, interfaceNames,
				methodSignatures, fieldSignatures, propertySignatures);
	}

	@Override
	public String toString() {
		return "ClassSignature{" + name
				+ ", super=" + superClassName
				+ ", ifaces=" + interfaceNames.size()
				+ ", methods=" + methodSignatures.size()
				+ ", fields=" + fieldSignatures.size()
				+ ", props=" + propertySignatures.size() + "}";
	}
}
