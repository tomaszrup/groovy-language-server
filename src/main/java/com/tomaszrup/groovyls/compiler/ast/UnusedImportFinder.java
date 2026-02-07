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
package com.tomaszrup.groovyls.compiler.ast;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Finds unused imports in Groovy source files by analyzing the AST.
 * An import is considered "used" if the imported class name appears
 * as a type reference somewhere in the module's classes (field types,
 * method return types, parameter types, variable types, cast targets,
 * constructor calls, super classes, interfaces, etc.).
 */
public class UnusedImportFinder {

	/**
	 * Returns a map from source URI to the list of unused ImportNodes
	 * found in that source file.
	 */
	public Map<URI, List<ImportNode>> findUnusedImports(CompilationUnit compilationUnit) {
		if (compilationUnit == null) {
			return Collections.emptyMap();
		}

		Map<URI, List<ImportNode>> result = new HashMap<>();

		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			List<ImportNode> unused = findUnusedImportsInSource(sourceUnit);
			if (!unused.isEmpty()) {
				result.put(sourceUnit.getSource().getURI(), unused);
			}
		});

		return result;
	}

	/**
	 * Finds unused imports in a single source unit.
	 */
	public List<ImportNode> findUnusedImportsInSource(SourceUnit sourceUnit) {
		ModuleNode moduleNode = sourceUnit.getAST();
		if (moduleNode == null) {
			return Collections.emptyList();
		}

		// Collect all class names that are actually referenced in the code
		Set<String> usedClassNames = collectUsedClassNames(moduleNode);

		List<ImportNode> unusedImports = new ArrayList<>();

		// Check regular imports (import com.example.Foo)
		for (ImportNode importNode : moduleNode.getImports()) {
			if (importNode.isStar()) {
				continue; // star imports are not checked
			}
			if (importNode.getLineNumber() < 0) {
				continue; // synthetic/implicit imports
			}
			ClassNode importedType = importNode.getType();
			if (importedType == null) {
				continue;
			}

			String simpleName = importedType.getNameWithoutPackage();
			String alias = importNode.getAlias();

			// The import is used if either its simple name or alias appears in usedClassNames
			boolean used = usedClassNames.contains(simpleName);
			if (!used && alias != null && !alias.equals(simpleName)) {
				used = usedClassNames.contains(alias);
			}
			if (!used) {
				unusedImports.add(importNode);
			}
		}

		// Check static imports (import static com.example.Foo.bar)
		for (ImportNode importNode : moduleNode.getStaticImports().values()) {
			if (importNode.getLineNumber() < 0) {
				continue; // synthetic/implicit imports
			}
			String fieldName = importNode.getFieldName();
			String alias = importNode.getAlias();
			boolean used = usedClassNames.contains(fieldName);
			if (!used && alias != null && !alias.equals(fieldName)) {
				used = usedClassNames.contains(alias);
			}
			// Also check if the class itself is used
			if (!used) {
				ClassNode importedType = importNode.getType();
				if (importedType != null) {
					String simpleName = importedType.getNameWithoutPackage();
					used = usedClassNames.contains(simpleName);
				}
			}
			if (!used) {
				unusedImports.add(importNode);
			}
		}

		return unusedImports;
	}

	/**
	 * Collects all class/type names that are actually referenced in the module's code.
	 * This includes field types, method return types, parameter types, super classes,
	 * implemented interfaces, constructor calls, casts, class expressions, annotations, etc.
	 */
	private Set<String> collectUsedClassNames(ModuleNode moduleNode) {
		Set<String> usedNames = new HashSet<>();

		for (ClassNode classNode : moduleNode.getClasses()) {
			// Don't count the class declaration itself, but do count its supers/interfaces
			collectFromClassNode(classNode, usedNames);
		}

		// Also check script-level statements — the Groovy compiler wraps them
		// in a run() method of a generated class, which we already iterate above.

		return usedNames;
	}

	private void collectFromClassNode(ClassNode classNode, Set<String> usedNames) {
		// Superclass
		ClassNode superClass = classNode.getUnresolvedSuperClass();
		if (superClass != null) {
			addClassName(superClass, usedNames);
		}

		// Interfaces
		for (ClassNode iface : classNode.getUnresolvedInterfaces()) {
			addClassName(iface, usedNames);
		}

		// Annotations on the class
		classNode.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));

		// Fields
		for (FieldNode field : classNode.getFields()) {
			if (field.isSynthetic()) {
				continue;
			}
			addClassName(field.getType(), usedNames);
			addGenericsTypes(field.getType(), usedNames);
			field.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));
			// Visit field initializer expressions (e.g., def list = new ArrayList())
			if (field.getInitialValueExpression() != null) {
				UsedTypeCollectorVisitor visitor = new UsedTypeCollectorVisitor(usedNames);
				field.getInitialValueExpression().visit(visitor);
			}
		}

		// Properties
		for (PropertyNode prop : classNode.getProperties()) {
			addClassName(prop.getType(), usedNames);
			addGenericsTypes(prop.getType(), usedNames);
			// Visit property initializer expressions
			if (prop.getInitialExpression() != null) {
				UsedTypeCollectorVisitor visitor = new UsedTypeCollectorVisitor(usedNames);
				prop.getInitialExpression().visit(visitor);
			}
		}

		// Methods
		for (MethodNode method : classNode.getMethods()) {
			if (method.isSynthetic()) {
				continue;
			}
			collectFromMethodNode(method, usedNames);
		}

		// Constructors
		for (ConstructorNode ctor : classNode.getDeclaredConstructors()) {
			if (ctor.isSynthetic()) {
				continue;
			}
			collectFromMethodNode(ctor, usedNames);
		}
	}

	private void collectFromMethodNode(MethodNode method, Set<String> usedNames) {
		// Return type
		addClassName(method.getReturnType(), usedNames);
		addGenericsTypes(method.getReturnType(), usedNames);

		// Parameters
		for (Parameter param : method.getParameters()) {
			addClassName(param.getType(), usedNames);
			addGenericsTypes(param.getType(), usedNames);
			param.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));
		}

		// Exceptions
		for (ClassNode exception : method.getExceptions()) {
			addClassName(exception, usedNames);
		}

		// Annotations
		method.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));

		// Walk the method body to find class references in code
		if (method.getCode() != null) {
			UsedTypeCollectorVisitor visitor = new UsedTypeCollectorVisitor(usedNames);
			method.getCode().visit(visitor);
		}
	}

	private void addClassName(ClassNode classNode, Set<String> usedNames) {
		if (classNode == null) {
			return;
		}
		String name = classNode.getNameWithoutPackage();
		// Handle inner classes: Outer.Inner -> Outer
		int dotIndex = name.indexOf('.');
		if (dotIndex > 0) {
			name = name.substring(0, dotIndex);
		}
		// Skip primitive and built-in types
		if (isPrimitiveOrBuiltin(name)) {
			return;
		}
		usedNames.add(name);
		addGenericsTypes(classNode, usedNames);
	}

	private void addGenericsTypes(ClassNode classNode, Set<String> usedNames) {
		if (classNode == null || classNode.getGenericsTypes() == null) {
			return;
		}
		for (org.codehaus.groovy.ast.GenericsType gt : classNode.getGenericsTypes()) {
			if (gt.getType() != null) {
				addClassName(gt.getType(), usedNames);
			}
			if (gt.getUpperBounds() != null) {
				for (ClassNode bound : gt.getUpperBounds()) {
					addClassName(bound, usedNames);
				}
			}
			if (gt.getLowerBound() != null) {
				addClassName(gt.getLowerBound(), usedNames);
			}
		}
	}

	private boolean isPrimitiveOrBuiltin(String name) {
		switch (name) {
			case "int":
			case "long":
			case "short":
			case "byte":
			case "char":
			case "float":
			case "double":
			case "boolean":
			case "void":
			case "def":
			case "Object":
			case "String":
				return true;
			default:
				return false;
		}
	}
}
