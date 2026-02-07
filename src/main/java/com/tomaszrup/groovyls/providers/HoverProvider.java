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
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import groovy.lang.groovydoc.Groovydoc;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.compiler.util.GroovydocUtils;
import com.tomaszrup.groovyls.util.GroovyNodeToStringUtils;
import com.tomaszrup.groovyls.util.SpockUtils;

public class HoverProvider {
	private ASTNodeVisitor ast;

	public HoverProvider(ASTNodeVisitor ast) {
		this.ast = ast;
	}

	public CompletableFuture<Hover> provideHover(TextDocumentIdentifier textDocument, Position position) {
		if (ast == null) {
			// this shouldn't happen, but let's avoid an exception if something
			// goes terribly wrong.
			return CompletableFuture.completedFuture(null);
		}

		URI uri = URI.create(textDocument.getUri());
		ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
		if (offsetNode == null) {
			return CompletableFuture.completedFuture(null);
		}

		// Check for Spock block label hover (labels appear as ConstantExpression
		// nodes that are children of ExpressionStatements in feature methods)
		Hover spockBlockHover = getSpockBlockLabelHover(offsetNode);
		if (spockBlockHover != null) {
			return CompletableFuture.completedFuture(spockBlockHover);
		}

		ASTNode definitionNode = GroovyASTUtils.getDefinition(offsetNode, false, ast);
		if (definitionNode == null) {
			return CompletableFuture.completedFuture(null);
		}

		String content = getContent(definitionNode);
		if (content == null) {
			return CompletableFuture.completedFuture(null);
		}

		String documentation = null;
		if (definitionNode instanceof AnnotatedNode) {
			AnnotatedNode annotatedNode = (AnnotatedNode) definitionNode;
			Groovydoc groovydoc = annotatedNode.getGroovydoc();
			documentation = GroovydocUtils.groovydocToMarkdownDescription(groovydoc);
		}

		// Add Spock-specific documentation
		String spockDoc = getSpockDocumentation(definitionNode);

		StringBuilder contentsBuilder = new StringBuilder();
		contentsBuilder.append("```groovy\n");
		contentsBuilder.append(content);
		contentsBuilder.append("\n```");
		if (spockDoc != null) {
			contentsBuilder.append("\n\n---\n\n");
			contentsBuilder.append(spockDoc);
		}
		if (documentation != null) {
			contentsBuilder.append("\n\n---\n\n");
			contentsBuilder.append(documentation);
		}

		MarkupContent contents = new MarkupContent();
		contents.setKind(MarkupKind.MARKDOWN);
		contents.setValue(contentsBuilder.toString());
		Hover hover = new Hover();
		hover.setContents(contents);
		return CompletableFuture.completedFuture(hover);
	}

	/**
	 * Tries to provide a hover for Spock block labels (given:/when:/then: etc.).
	 */
	private Hover getSpockBlockLabelHover(ASTNode node) {
		if (!(node instanceof ConstantExpression)) {
			return null;
		}
		ConstantExpression constExpr = (ConstantExpression) node;
		Object value = constExpr.getValue();
		if (!(value instanceof String)) {
			return null;
		}
		String label = (String) value;
		if (!SpockUtils.isSpockBlockLabel(label)) {
			return null;
		}
		// Verify this is inside a Spock specification
		ASTNode enclosingMethod = GroovyASTUtils.getEnclosingNodeOfType(node, MethodNode.class, ast);
		if (enclosingMethod == null) {
			return null;
		}
		ASTNode enclosingClass = GroovyASTUtils.getEnclosingNodeOfType(enclosingMethod, ClassNode.class, ast);
		if (!(enclosingClass instanceof ClassNode) || !SpockUtils.isSpockSpecification((ClassNode) enclosingClass)) {
			return null;
		}

		String description = SpockUtils.getBlockDescription(label);
		if (description == null) {
			return null;
		}

		MarkupContent contents = new MarkupContent();
		contents.setKind(MarkupKind.MARKDOWN);
		contents.setValue(description);
		Hover hover = new Hover();
		hover.setContents(contents);
		return hover;
	}

	/**
	 * Returns Spock-specific documentation for a definition node, or null.
	 */
	private String getSpockDocumentation(ASTNode definitionNode) {
		if (definitionNode instanceof ClassNode) {
			return SpockUtils.getSpecificationDescription((ClassNode) definitionNode);
		}
		if (definitionNode instanceof MethodNode) {
			MethodNode methodNode = (MethodNode) definitionNode;
			String lifecycleDoc = SpockUtils.getLifecycleMethodDescription(methodNode.getName());
			if (lifecycleDoc != null && SpockUtils.isSpockSpecification(methodNode.getDeclaringClass())) {
				return lifecycleDoc;
			}
			return SpockUtils.getFeatureMethodDescription(methodNode);
		}
		return null;
	}

	private String getContent(ASTNode hoverNode) {
		if (hoverNode instanceof ClassNode) {
			ClassNode classNode = (ClassNode) hoverNode;
			return GroovyNodeToStringUtils.classToString(classNode, ast);
		} else if (hoverNode instanceof MethodNode) {
			MethodNode methodNode = (MethodNode) hoverNode;
			return GroovyNodeToStringUtils.methodToString(methodNode, ast);
		} else if (hoverNode instanceof Variable) {
			Variable varNode = (Variable) hoverNode;
			return GroovyNodeToStringUtils.variableToString(varNode, ast);
		} else {
			System.err.println("*** hover not available for node: " + hoverNode);
		}
		return null;
	}
}