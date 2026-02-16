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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
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
			if (!isSkippableRegularImport(importNode)
					&& !isRegularImportUsed(importNode, usedClassNames)) {
				unusedImports.add(importNode);
			}
		}

		// Check static imports (import static com.example.Foo.bar)
		for (ImportNode importNode : moduleNode.getStaticImports().values()) {
			if (importNode.getLineNumber() >= 0 && !isStaticImportUsed(importNode, usedClassNames)) {
				unusedImports.add(importNode);
			}
		}

		return unusedImports;
	}

	private boolean isSkippableRegularImport(ImportNode importNode) {
		return importNode.isStar() || importNode.getLineNumber() < 0 || importNode.getType() == null;
	}

	private boolean isRegularImportUsed(ImportNode importNode, Set<String> usedClassNames) {
		ClassNode importedType = importNode.getType();
		String simpleName = importedType.getNameWithoutPackage();
		if (usedClassNames.contains(simpleName)) {
			return true;
		}
		String alias = importNode.getAlias();
		return alias != null && !alias.equals(simpleName) && usedClassNames.contains(alias);
	}

	private boolean isStaticImportUsed(ImportNode importNode, Set<String> usedClassNames) {
		String fieldName = importNode.getFieldName();
		if (usedClassNames.contains(fieldName)) {
			return true;
		}
		String alias = importNode.getAlias();
		if (alias != null && !alias.equals(fieldName) && usedClassNames.contains(alias)) {
			return true;
		}
		ClassNode importedType = importNode.getType();
		return importedType != null && usedClassNames.contains(importedType.getNameWithoutPackage());
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

		// Also visit the module's statement block directly — script-level code
		// is wrapped in a synthetic run() method which we skip in collectFromClassNode.
		if (moduleNode.getStatementBlock() != null) {
			UsedTypeCollectorVisitor visitor = new UsedTypeCollectorVisitor(usedNames);
			moduleNode.getStatementBlock().visit(visitor);
		}

		return usedNames;
	}

	private void collectFromClassNode(ClassNode classNode, Set<String> usedNames) {
		collectClassHierarchy(classNode, usedNames);
		collectClassFields(classNode, usedNames);
		collectClassProperties(classNode, usedNames);
		collectClassMethods(classNode, usedNames);
		collectClassConstructors(classNode, usedNames);
	}

	private void collectClassHierarchy(ClassNode classNode, Set<String> usedNames) {
		ClassNode superClass = classNode.getUnresolvedSuperClass();
		if (superClass != null) {
			addClassName(superClass, usedNames);
		}
		for (ClassNode iface : classNode.getUnresolvedInterfaces()) {
			addClassName(iface, usedNames);
		}
		classNode.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));
	}

	private void collectClassFields(ClassNode classNode, Set<String> usedNames) {
		for (FieldNode field : classNode.getFields()) {
			if (!field.isSynthetic()) {
				addClassName(field.getType(), usedNames);
				addGenericsTypes(field.getType(), usedNames);
				field.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));
				if (field.getInitialValueExpression() != null) {
					UsedTypeCollectorVisitor visitor = new UsedTypeCollectorVisitor(usedNames);
					field.getInitialValueExpression().visit(visitor);
				}
			}
		}
	}

	private void collectClassProperties(ClassNode classNode, Set<String> usedNames) {
		for (PropertyNode prop : classNode.getProperties()) {
			addClassName(prop.getType(), usedNames);
			addGenericsTypes(prop.getType(), usedNames);
			if (prop.getInitialExpression() != null) {
				UsedTypeCollectorVisitor visitor = new UsedTypeCollectorVisitor(usedNames);
				prop.getInitialExpression().visit(visitor);
			}
		}
	}

	private void collectClassMethods(ClassNode classNode, Set<String> usedNames) {
		for (MethodNode method : classNode.getMethods()) {
			if (!method.isSynthetic()) {
				collectFromMethodNode(method, usedNames);
			}
		}
	}

	private void collectClassConstructors(ClassNode classNode, Set<String> usedNames) {
		for (ConstructorNode ctor : classNode.getDeclaredConstructors()) {
			if (!ctor.isSynthetic()) {
				collectFromMethodNode(ctor, usedNames);
			}
		}
	}

	private void collectFromMethodNode(MethodNode method, Set<String> usedNames) {
		// Return type
		addClassName(method.getReturnType(), usedNames);
		addGenericsTypes(method.getReturnType(), usedNames);

		// Parameters — may be null for methods in incompletely-compiled ASTs
		Parameter[] params = method.getParameters();
		if (params != null) {
			for (Parameter param : params) {
				addClassName(param.getType(), usedNames);
				addGenericsTypes(param.getType(), usedNames);
				param.getAnnotations().forEach(ann -> addClassName(ann.getClassNode(), usedNames));
			}
		}

		// Exceptions — may be null for methods in incompletely-compiled ASTs
		ClassNode[] exceptions = method.getExceptions();
		if (exceptions != null) {
			for (ClassNode exception : exceptions) {
				addClassName(exception, usedNames);
			}
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
