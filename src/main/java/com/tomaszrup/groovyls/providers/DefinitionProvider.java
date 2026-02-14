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
package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.ClassNodeDecompiler;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

public class DefinitionProvider {
	private static final Logger logger = LoggerFactory.getLogger(DefinitionProvider.class);

	private ASTNodeVisitor ast;
	private JavaSourceLocator javaSourceLocator;

	public DefinitionProvider(ASTNodeVisitor ast) {
		this(ast, null, Collections.emptyList());
	}

	public DefinitionProvider(ASTNodeVisitor ast, JavaSourceLocator javaSourceLocator) {
		this.ast = ast;
		this.javaSourceLocator = javaSourceLocator;
	}

	/**
	 * @param ast              the AST visitor for the current scope
	 * @param javaSourceLocator the primary locator for the current scope
	 * @param siblingLocators  deprecated compatibility parameter; sibling locators
	 *                         are intentionally ignored to keep resolution scope-local
	 */
	public DefinitionProvider(ASTNodeVisitor ast, JavaSourceLocator javaSourceLocator,
			java.util.List<JavaSourceLocator> siblingLocators) {
		this.ast = ast;
		this.javaSourceLocator = javaSourceLocator;
	}

	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> provideDefinition(
			TextDocumentIdentifier textDocument, Position position) {
		if (ast == null) {
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}
		URI uri = URI.create(textDocument.getUri());
		ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		ASTNode definitionNode = GroovyASTUtils.getDefinition(offsetNode, true, ast);
		if (definitionNode == null || definitionNode.getLineNumber() == -1 || definitionNode.getColumnNumber() == -1) {
			// The definition node has no source location in the Groovy AST.
			// Try to resolve it as a Java source file via the locator.
			Location javaLocation = resolveJavaSource(offsetNode, definitionNode);
			if (javaLocation != null) {
				return CompletableFuture.completedFuture(Either.forLeft(Collections.singletonList(javaLocation)));
			}
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		URI definitionURI = ast.getURI(definitionNode);
		if (definitionURI == null) {
			definitionURI = uri;
		}

		Location location = GroovyLanguageServerUtils.astNodeToLocation(definitionNode, definitionURI);
		if (location == null) {
			return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
		}

		return CompletableFuture.completedFuture(Either.forLeft(Collections.singletonList(location)));
	}

	private Location resolveJavaSource(ASTNode offsetNode, ASTNode definitionNode) {
		if (javaSourceLocator == null) {
			return null;
		}
		// If strict definition returned null, try non-strict to get the node
		if (definitionNode == null) {
			definitionNode = GroovyASTUtils.getDefinition(offsetNode, false, ast);
		}
		if (definitionNode == null) {
			logger.debug("resolveJavaSource: no definition node found for {}",
					offsetNode.getClass().getSimpleName());
			return null;
		}
		if (definitionNode instanceof ClassNode) {
			ClassNode classNode = (ClassNode) definitionNode;
			String className = classNode.getName();
			logger.debug("resolveJavaSource: resolving ClassNode '{}'", className);
			Location loc = javaSourceLocator.findLocationForClass(className);
			if (loc != null) {
				logger.debug("resolveJavaSource: found source for '{}' at {}",
						className, loc.getUri());
				return loc;
			}
			logger.debug("resolveJavaSource: no source for '{}', falling back to decompilation",
					className);
			return decompileAndLocateClass(classNode);
		}
		if (definitionNode instanceof ConstructorNode) {
			ConstructorNode ctorNode = (ConstructorNode) definitionNode;
			ClassNode declaringClass = ctorNode.getDeclaringClass();
			if (declaringClass != null) {
				String className = declaringClass.getName();
				int paramCount = ctorNode.getParameters().length;
				Location loc = javaSourceLocator.findLocationForConstructor(className, paramCount);
				if (loc != null) return loc;
				return decompileAndLocateConstructor(declaringClass, paramCount);
			}
		}
		if (definitionNode instanceof MethodNode) {
			MethodNode methodNode = (MethodNode) definitionNode;
			ClassNode declaringClass = methodNode.getDeclaringClass();
			if (declaringClass != null) {
				String className = declaringClass.getName();
				int paramCount = methodNode.getParameters().length;
				if ("<init>".equals(methodNode.getName())) {
					Location loc = javaSourceLocator.findLocationForConstructor(className, paramCount);
					if (loc != null) return loc;
					return decompileAndLocateConstructor(declaringClass, paramCount);
				}
				String methodName = methodNode.getName();
				Location loc = javaSourceLocator.findLocationForMethod(className, methodName, paramCount);
				if (loc != null) return loc;
				return decompileAndLocateMethod(declaringClass, methodName, paramCount);
			}
		}
		if (definitionNode instanceof PropertyNode) {
			PropertyNode propNode = (PropertyNode) definitionNode;
			ClassNode declaringClass = propNode.getDeclaringClass();
			if (declaringClass != null) {
				String className = declaringClass.getName();
				String fieldName = propNode.getName();
				Location loc = javaSourceLocator.findLocationForField(className, fieldName);
				if (loc != null) return loc;
				return decompileAndLocateField(declaringClass, fieldName);
			}
		}
		if (definitionNode instanceof FieldNode) {
			FieldNode fieldNode = (FieldNode) definitionNode;
			ClassNode declaringClass = fieldNode.getDeclaringClass();
			if (declaringClass != null) {
				String className = declaringClass.getName();
				String fieldName = fieldNode.getName();
				Location loc = javaSourceLocator.findLocationForField(className, fieldName);
				if (loc != null) return loc;
				return decompileAndLocateField(declaringClass, fieldName);
			}
		}
		if (definitionNode instanceof Variable) {
			Variable variable = (Variable) definitionNode;
			ClassNode originType = variable.getOriginType();
			if (originType != null) {
				String className = originType.getName();
				Location loc = javaSourceLocator.findLocationForClass(className);
				if (loc != null) return loc;
				return decompileAndLocateClass(originType);
			}
		}
		return null;
	}

	// --- Decompilation fallback methods ---

	private Location decompileAndLocateClass(ClassNode classNode) {
		URI uri = ensureDecompiled(classNode);
		if (uri == null) return null;
		int line = ClassNodeDecompiler.getClassDeclarationLine(classNode);
		return new Location(uri.toString(),
				new Range(new Position(line, 0), new Position(line, 0)));
	}

	private Location decompileAndLocateMethod(ClassNode classNode, String methodName, int paramCount) {
		URI uri = ensureDecompiled(classNode);
		if (uri == null) return null;
		int line = ClassNodeDecompiler.getMethodLine(classNode, methodName, paramCount);
		return new Location(uri.toString(),
				new Range(new Position(line, 0), new Position(line, 0)));
	}

	private Location decompileAndLocateConstructor(ClassNode classNode, int paramCount) {
		URI uri = ensureDecompiled(classNode);
		if (uri == null) return null;
		int line = ClassNodeDecompiler.getMethodLine(classNode, classNode.getNameWithoutPackage(), paramCount);
		return new Location(uri.toString(),
				new Range(new Position(line, 0), new Position(line, 0)));
	}

	private Location decompileAndLocateField(ClassNode classNode, String fieldName) {
		URI uri = ensureDecompiled(classNode);
		if (uri == null) return null;
		int line = ClassNodeDecompiler.getFieldLine(classNode, fieldName);
		return new Location(uri.toString(),
				new Range(new Position(line, 0), new Position(line, 0)));
	}

	private URI ensureDecompiled(ClassNode classNode) {
		if (javaSourceLocator == null || classNode == null) return null;
		String className = classNode.getName();
		// If real source is now available (e.g. source JARs indexed after
		// lazy classpath resolution), skip decompilation entirely.
		if (javaSourceLocator.hasSource(className)) return null;
		List<String> lines = ClassNodeDecompiler.decompile(classNode);
		return javaSourceLocator.registerDecompiledContent(className, lines);
	}
}