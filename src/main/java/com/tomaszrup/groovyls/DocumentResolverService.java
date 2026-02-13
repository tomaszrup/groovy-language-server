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
package com.tomaszrup.groovyls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovydocUtils;

/**
 * Resolves documentation for completion items by searching AST nodes
 * (Groovydoc) and classpath source JARs (Javadoc).
 */
public class DocumentResolverService {
	private final ProjectScopeManager scopeManager;

	public DocumentResolverService(ProjectScopeManager scopeManager) {
		this.scopeManager = scopeManager;
	}

	/**
	 * Resolves documentation for a completion item by searching across all
	 * project scopes' AST visitors and classpath source JARs.
	 */
	public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
		if (unresolved.getDocumentation() != null) {
			return unresolved;
		}
		String label = unresolved.getLabel();
		CompletionItemKind kind = unresolved.getKind();
		if (label == null || kind == null) {
			return unresolved;
		}

		// Extract signature info from CompletionItem.data if available
		String signature = null;
		String declaringClassName = null;
		Object data = unresolved.getData();
		if (data instanceof com.google.gson.JsonObject) {
			com.google.gson.JsonObject jsonData = (com.google.gson.JsonObject) data;
			if (jsonData.has("signature")) {
				signature = jsonData.get("signature").getAsString();
			}
			if (jsonData.has("declaringClass")) {
				declaringClassName = jsonData.get("declaringClass").getAsString();
			}
		} else if (data instanceof com.google.gson.JsonElement) {
			try {
				com.google.gson.JsonObject jsonData = ((com.google.gson.JsonElement) data).getAsJsonObject();
				if (jsonData.has("signature")) {
					signature = jsonData.get("signature").getAsString();
				}
				if (jsonData.has("declaringClass")) {
					declaringClassName = jsonData.get("declaringClass").getAsString();
				}
			} catch (Exception e) {
				// not a JSON object, ignore
			}
		}

		// Search all scopes for matching AST node to retrieve documentation
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			ASTNodeVisitor visitor = scope.getAstVisitor();
			if (visitor == null) {
				continue;
			}
			String docs = resolveDocumentation(visitor, label, kind, signature, declaringClassName);
			if (docs != null) {
				unresolved.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, docs));
				break;
			}
		}

		// If still no documentation, try to load Javadoc from classpath source JARs
		if (unresolved.getDocumentation() == null && declaringClassName != null) {
			for (ProjectScope scope : scopeManager.getAllScopes()) {
				String javadoc = resolveJavadocFromClasspath(scope, label, kind, signature, declaringClassName);
				if (javadoc != null) {
					unresolved.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, javadoc));
					break;
				}
			}
		}

		return unresolved;
	}

	/**
	 * Build a parameter signature string from a MethodNode for comparison.
	 */
	private static String buildMethodSignature(MethodNode method) {
		Parameter[] params = method.getParameters();
		StringBuilder sig = new StringBuilder();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) {
				sig.append(",");
			}
			sig.append(params[i].getType().getName());
		}
		return sig.toString();
	}

	String resolveDocumentation(ASTNodeVisitor visitor, String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		if (kind == CompletionItemKind.Method) {
			if (declaringClassName != null) {
				ClassNode classNode = visitor.getClassNodeByName(declaringClassName);
				if (classNode != null) {
					String docs = findMethodDocs(classNode, label, signature);
					if (docs != null) {
						return docs;
					}
				}
			}
			for (ClassNode classNode : visitor.getClassNodes()) {
				String docs = findMethodDocs(classNode, label, signature);
				if (docs != null) {
					return docs;
				}
			}
		} else if (kind == CompletionItemKind.Property || kind == CompletionItemKind.Field) {
			if (declaringClassName != null) {
				ClassNode classNode = visitor.getClassNodeByName(declaringClassName);
				if (classNode != null) {
					String docs = findFieldOrPropertyDocs(classNode, label);
					if (docs != null) {
						return docs;
					}
				}
			}
			for (ClassNode classNode : visitor.getClassNodes()) {
				String docs = findFieldOrPropertyDocs(classNode, label);
				if (docs != null) {
					return docs;
				}
			}
		} else if (kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
				|| kind == CompletionItemKind.Enum) {
			ClassNode classNode = visitor.getClassNodeByName(label);
			if (classNode != null) {
				String docs = GroovydocUtils.groovydocToMarkdownDescription(classNode.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
			for (ClassNode cn : visitor.getClassNodes()) {
				if (cn.getNameWithoutPackage().equals(label) || cn.getName().equals(label)) {
					String docs = GroovydocUtils.groovydocToMarkdownDescription(cn.getGroovydoc());
					if (docs != null) {
						return docs;
					}
				}
			}
		}
		return null;
	}

	private static String findMethodDocs(ClassNode classNode, String label, String signature) {
		for (MethodNode method : classNode.getMethods()) {
			if (!method.getName().equals(label)) {
				continue;
			}
			if (signature != null) {
				String methodSig = buildMethodSignature(method);
				if (!methodSig.equals(signature)) {
					continue;
				}
			}
			String docs = GroovydocUtils.groovydocToMarkdownDescription(method.getGroovydoc());
			if (docs != null) {
				return docs;
			}
		}
		return null;
	}

	private static String findFieldOrPropertyDocs(ClassNode classNode, String label) {
		for (PropertyNode prop : classNode.getProperties()) {
			if (prop.getName().equals(label)) {
				String docs = GroovydocUtils.groovydocToMarkdownDescription(prop.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
		}
		for (FieldNode field : classNode.getFields()) {
			if (field.getName().equals(label)) {
				String docs = GroovydocUtils.groovydocToMarkdownDescription(field.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
		}
		return null;
	}

	/**
	 * Attempt to resolve Javadoc documentation from classpath source JARs.
	 */
	private String resolveJavadocFromClasspath(ProjectScope scope, String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		if (scope.getCompilationUnit() == null || declaringClassName == null) {
			return null;
		}
		List<String> classpathList = scope.getCompilationUnit().getConfiguration().getClasspath();
		if (classpathList == null) {
			return null;
		}
		for (String cpEntry : classpathList) {
			if (!cpEntry.endsWith(".jar")) {
				continue;
			}
			String sourcesJarPath = cpEntry.replaceAll("\\.jar$", "-sources.jar");
			Path sourcesJar = Paths.get(sourcesJarPath);
			if (!Files.exists(sourcesJar)) {
				continue;
			}
			String javadoc = JavadocResolver.resolveFromSourcesJar(sourcesJar, declaringClassName, label, kind,
					signature);
			if (javadoc != null) {
				return javadoc;
			}
		}
		return null;
	}
}
