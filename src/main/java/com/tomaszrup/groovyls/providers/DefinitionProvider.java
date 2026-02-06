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
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

public class DefinitionProvider {
	private ASTNodeVisitor ast;
	private JavaSourceLocator javaSourceLocator;

	public DefinitionProvider(ASTNodeVisitor ast) {
		this(ast, null);
	}

	public DefinitionProvider(ASTNodeVisitor ast, JavaSourceLocator javaSourceLocator) {
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
			return null;
		}
		if (definitionNode instanceof ClassNode) {
			ClassNode classNode = (ClassNode) definitionNode;
			return javaSourceLocator.findLocationForClass(classNode.getName());
		}
		if (definitionNode instanceof ConstructorNode) {
			ConstructorNode ctorNode = (ConstructorNode) definitionNode;
			ClassNode declaringClass = ctorNode.getDeclaringClass();
			if (declaringClass != null) {
				return javaSourceLocator.findLocationForConstructor(
						declaringClass.getName(), ctorNode.getParameters().length);
			}
		}
		if (definitionNode instanceof MethodNode) {
			MethodNode methodNode = (MethodNode) definitionNode;
			ClassNode declaringClass = methodNode.getDeclaringClass();
			if (declaringClass != null) {
				// Check if this is a constructor call (ConstructorCallExpression
				// resolves to a MethodNode named "<init>")
				if ("<init>".equals(methodNode.getName())) {
					return javaSourceLocator.findLocationForConstructor(
							declaringClass.getName(), methodNode.getParameters().length);
				}
				return javaSourceLocator.findLocationForMethod(
						declaringClass.getName(), methodNode.getName(),
						methodNode.getParameters().length);
			}
		}
		if (definitionNode instanceof PropertyNode) {
			PropertyNode propNode = (PropertyNode) definitionNode;
			ClassNode declaringClass = propNode.getDeclaringClass();
			if (declaringClass != null) {
				return javaSourceLocator.findLocationForField(
						declaringClass.getName(), propNode.getName());
			}
		}
		if (definitionNode instanceof FieldNode) {
			FieldNode fieldNode = (FieldNode) definitionNode;
			ClassNode declaringClass = fieldNode.getDeclaringClass();
			if (declaringClass != null) {
				return javaSourceLocator.findLocationForField(
						declaringClass.getName(), fieldNode.getName());
			}
		}
		if (definitionNode instanceof Variable) {
			Variable variable = (Variable) definitionNode;
			ClassNode originType = variable.getOriginType();
			if (originType != null) {
				return javaSourceLocator.findLocationForClass(originType.getName());
			}
		}
		return null;
	}
}