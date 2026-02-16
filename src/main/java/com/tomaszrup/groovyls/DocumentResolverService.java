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
import com.tomaszrup.groovyls.util.JavaSourceLocator;

/**
 * Resolves documentation for completion items by searching AST nodes
 * (Groovydoc) and classpath source JARs (Javadoc).
 */
public class DocumentResolverService {
	private static final String DATA_KEY_SIGNATURE = "signature";
	private static final String DATA_KEY_DECLARING_CLASS = "declaringClass";

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

		CompletionItemData completionData = extractCompletionItemData(unresolved.getData());

		String docs = resolveDocumentationAcrossScopes(
				label, kind, completionData.signature, completionData.declaringClassName);
		if (docs != null) {
			unresolved.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, docs));
		}

		if (unresolved.getDocumentation() == null && completionData.declaringClassName != null) {
			String javadoc = resolveJavadocAcrossScopes(
					label, kind, completionData.signature, completionData.declaringClassName);
			if (javadoc != null) {
				unresolved.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, javadoc));
			}
		}

		return unresolved;
	}

	private CompletionItemData extractCompletionItemData(Object data) {
		if (data instanceof com.google.gson.JsonObject) {
			return extractFromJsonObject((com.google.gson.JsonObject) data);
		}
		if (data instanceof com.google.gson.JsonElement) {
			try {
				return extractFromJsonObject(((com.google.gson.JsonElement) data).getAsJsonObject());
			} catch (Exception e) {
				return CompletionItemData.EMPTY;
			}
		}
		return CompletionItemData.EMPTY;
	}

	private CompletionItemData extractFromJsonObject(com.google.gson.JsonObject jsonData) {
		String signature = readJsonString(jsonData, DATA_KEY_SIGNATURE);
		String declaringClassName = readJsonString(jsonData, DATA_KEY_DECLARING_CLASS);
		return new CompletionItemData(signature, declaringClassName);
	}

	private String readJsonString(com.google.gson.JsonObject jsonData, String key) {
		return jsonData.has(key) ? jsonData.get(key).getAsString() : null;
	}

	private String resolveDocumentationAcrossScopes(String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			ASTNodeVisitor visitor = scope.getAstVisitor();
			if (visitor != null) {
				String docs = resolveDocumentation(visitor, label, kind, signature, declaringClassName);
				if (docs != null) {
					return docs;
				}
			}
		}
		return null;
	}

	private String resolveJavadocAcrossScopes(String label, CompletionItemKind kind,
			String signature, String declaringClassName) {
		for (ProjectScope scope : scopeManager.getAllScopes()) {
			String javadoc = resolveJavadocFromClasspath(scope, label, kind, signature, declaringClassName);
			if (javadoc != null) {
				return javadoc;
			}
		}
		return null;
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
			return resolveMethodDocumentation(visitor, label, signature, declaringClassName);
		}
		if (kind == CompletionItemKind.Property || kind == CompletionItemKind.Field) {
			return resolveFieldOrPropertyDocumentation(visitor, label, declaringClassName);
		}
		if (kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
				|| kind == CompletionItemKind.Enum) {
			return resolveTypeDocumentation(visitor, label);
		}
		return null;
	}

	private String resolveMethodDocumentation(ASTNodeVisitor visitor, String label,
			String signature, String declaringClassName) {
		String docsFromDeclaringClass = findMethodDocsForDeclaringClass(visitor, declaringClassName, label, signature);
		if (docsFromDeclaringClass != null) {
			return docsFromDeclaringClass;
		}
		for (ClassNode classNode : visitor.getClassNodes()) {
			String docs = findMethodDocs(classNode, label, signature);
			if (docs != null) {
				return docs;
			}
		}
		return null;
	}

	private String findMethodDocsForDeclaringClass(ASTNodeVisitor visitor, String declaringClassName,
			String label, String signature) {
		if (declaringClassName == null) {
			return null;
		}
		ClassNode classNode = visitor.getClassNodeByName(declaringClassName);
		return classNode != null ? findMethodDocs(classNode, label, signature) : null;
	}

	private String resolveFieldOrPropertyDocumentation(ASTNodeVisitor visitor, String label, String declaringClassName) {
		String docsFromDeclaringClass = findFieldDocsForDeclaringClass(visitor, declaringClassName, label);
		if (docsFromDeclaringClass != null) {
			return docsFromDeclaringClass;
		}
		for (ClassNode classNode : visitor.getClassNodes()) {
			String docs = findFieldOrPropertyDocs(classNode, label);
			if (docs != null) {
				return docs;
			}
		}
		return null;
	}

	private String findFieldDocsForDeclaringClass(ASTNodeVisitor visitor, String declaringClassName, String label) {
		if (declaringClassName == null) {
			return null;
		}
		ClassNode classNode = visitor.getClassNodeByName(declaringClassName);
		return classNode != null ? findFieldOrPropertyDocs(classNode, label) : null;
	}

	private String resolveTypeDocumentation(ASTNodeVisitor visitor, String label) {
		ClassNode exactClassNode = visitor.getClassNodeByName(label);
		String exactDocs = exactClassNode != null
				? GroovydocUtils.groovydocToMarkdownDescription(exactClassNode.getGroovydoc())
				: null;
		if (exactDocs != null) {
			return exactDocs;
		}

		for (ClassNode classNode : visitor.getClassNodes()) {
			boolean nameMatches = classNode.getNameWithoutPackage().equals(label) || classNode.getName().equals(label);
			if (nameMatches) {
				String docs = GroovydocUtils.groovydocToMarkdownDescription(classNode.getGroovydoc());
				if (docs != null) {
					return docs;
				}
			}
		}
		return null;
	}

	private static String findMethodDocs(ClassNode classNode, String label, String signature) {
		for (MethodNode method : classNode.getMethods()) {
			boolean nameMatches = method.getName().equals(label);
			boolean signatureMatches = signature == null || buildMethodSignature(method).equals(signature);
			if (nameMatches && signatureMatches) {
				String docs = GroovydocUtils.groovydocToMarkdownDescription(method.getGroovydoc());
				if (docs != null) {
					return docs;
				}
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
			Path sourcesJar = toSourcesJar(cpEntry);
			if (sourcesJar != null && Files.exists(sourcesJar)) {
				String javadoc = JavadocResolver.resolveFromSourcesJar(sourcesJar, declaringClassName, label, kind,
						signature);
				if (javadoc != null) {
					return javadoc;
				}
			}
		}
		return null;
	}

	private Path toSourcesJar(String classpathEntry) {
		if (classpathEntry == null || !classpathEntry.endsWith(".jar")) {
			return null;
		}
		String sourcesJarPath = classpathEntry.substring(0, classpathEntry.length() - 4) + "-sources.jar";
		return Paths.get(sourcesJarPath);
	}

	// --- Decompiled content resolution (merged from DecompiledContentResolver) ---

	/**
	 * Look up decompiled content across all project scopes.
	 * Returns the content for the first matching scope, or {@code null}
	 * if no scope has decompiled content for the given class.
	 *
	 * @param className fully-qualified class name
	 * @return decompiled source text, or {@code null}
	 */
	public String getDecompiledContent(String className) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String content = locator.getDecompiledContent(className);
				if (content != null) {
					return content;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getDecompiledContent(className);
		}
		return null;
	}

	/**
	 * Look up decompiled content by URI string across all project scopes.
	 * Supports {@code decompiled:}, {@code jar:}, and {@code jrt:} URIs.
	 *
	 * @param uri the URI string
	 * @return decompiled source text, or {@code null}
	 */
	public String getDecompiledContentByURI(String uri) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String content = locator.getDecompiledContentByURI(uri);
				if (content != null) {
					return content;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getDecompiledContentByURI(uri);
		}
		return null;
	}

	/**
	 * Resolve a URI for Java definition providers.
	 * Converts virtual dependency URIs to provider-compatible URI forms
	 * (for example, source-jar entries as {@code jar:file:///...!/entry.java}).
	 *
	 * @param uri the current document URI
	 * @return Java-provider-compatible URI string, or {@code null} if unavailable
	 */
	public String getJavaNavigationURI(String uri) {
		for (ProjectScope scope : scopeManager.getProjectScopes()) {
			JavaSourceLocator locator = scope.getJavaSourceLocator();
			if (locator != null) {
				String resolvedUri = locator.getJavaNavigationURI(uri);
				if (resolvedUri != null) {
					return resolvedUri;
				}
			}
		}
		ProjectScope ds = scopeManager.getDefaultScope();
		if (ds != null && ds.getJavaSourceLocator() != null) {
			return ds.getJavaSourceLocator().getJavaNavigationURI(uri);
		}
		return null;
	}

	private static final class CompletionItemData {
		private static final CompletionItemData EMPTY = new CompletionItemData(null, null);
		private final String signature;
		private final String declaringClassName;

		private CompletionItemData(String signature, String declaringClassName) {
			this.signature = signature;
			this.declaringClassName = declaringClassName;
		}
	}
}
