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
package com.tomaszrup.groovyls.providers;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.transform.trait.Traits;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.TextDocumentIdentifier;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Provides semantic tokens for Groovy source files, enabling semantic-aware
 * syntax highlighting in editors that support the LSP semantic tokens protocol.
 */
public class SemanticTokensProvider {
	private static final String STATIC_KEYWORD = "static";

	// Token types — order matters, indices are used in the encoding
	public static final List<String> TOKEN_TYPES = Collections.unmodifiableList(Arrays.asList(
			"namespace",      // 0
			"type",           // 1
			"class",          // 2
			"interface",      // 3
			"enum",           // 4
			"parameter",      // 5
			"variable",       // 6
			"property",       // 7
			"function",       // 8
			"method",         // 9
			"decorator",      // 10
			"enumMember",     // 11
			"keyword",        // 12
			"typeParameter"   // 13
	));

	// Token modifiers — bit flags
	public static final List<String> TOKEN_MODIFIERS = Collections.unmodifiableList(Arrays.asList(
			"declaration",   // bit 0
			STATIC_KEYWORD,   // bit 1
			"readonly",      // bit 2
			"deprecated",    // bit 3
			"abstract",      // bit 4
			"defaultLibrary" // bit 5
	));

	static final int TYPE_NAMESPACE = 0;
	static final int TYPE_TYPE = 1;
	static final int TYPE_CLASS = 2;
	static final int TYPE_INTERFACE = 3;
	static final int TYPE_ENUM = 4;
	static final int TYPE_PARAMETER = 5;
	static final int TYPE_VARIABLE = 6;
	static final int TYPE_PROPERTY = 7;
	static final int TYPE_FUNCTION = 8;
	static final int TYPE_METHOD = 9;
	static final int TYPE_DECORATOR = 10;
	static final int TYPE_ENUM_MEMBER = 11;
	static final int TYPE_KEYWORD = 12;
	static final int TYPE_TYPE_PARAMETER = 13;

	static final int MOD_DECLARATION = 1;       // bit 0
	static final int MOD_STATIC = 1 << 1;       // bit 1
	static final int MOD_READONLY = 1 << 2;     // bit 2
	static final int MOD_ABSTRACT = 1 << 4;     // bit 4

	private ASTNodeVisitor ast;
	private FileContentsTracker fileContentsTracker;
	private final boolean groovy4ColumnCompatibility;
	private String[] sourceLines;
	private Set<ClassNode> classNodeSet;
	private TypeReferenceTokenEmitter typeRefEmitter;

	public SemanticTokensProvider(ASTNodeVisitor ast, FileContentsTracker fileContentsTracker) {
		this(ast, fileContentsTracker, true);
	}

	public SemanticTokensProvider(ASTNodeVisitor ast, FileContentsTracker fileContentsTracker,
								 boolean groovy4ColumnCompatibility) {
		this.ast = ast;
		this.fileContentsTracker = fileContentsTracker;
		this.groovy4ColumnCompatibility = groovy4ColumnCompatibility;
	}

	public static SemanticTokensLegend getLegend() {
		return new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);
	}

	public CompletableFuture<SemanticTokens> provideSemanticTokensFull(TextDocumentIdentifier textDocument) {
		if (ast == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}

		URI uri = URI.create(textDocument.getUri());

		// Load source lines for accurate name position lookup
		String source = fileContentsTracker != null ? fileContentsTracker.getContents(uri) : null;
		this.sourceLines = source != null ? source.split("\n", -1) : new String[0];

		// Precompute set of declared classes for O(1) lookup (scoped to this file)
		this.classNodeSet = new HashSet<>(ast.getClassNodes(uri));

		this.typeRefEmitter = new TypeReferenceTokenEmitter(sourceLines, ast);

		List<ASTNode> nodes = ast.getNodes(uri);
		List<SemanticToken> tokens = new ArrayList<>();

		for (ASTNode node : nodes) {
			addTokensForNode(node, tokens);
		}

		// Emit namespace tokens for package declaration segments
		typeRefEmitter.addPackageDeclarationNamespaceTokens(tokens, 1, sourceLines.length);

		// Sort tokens by position (line, then column)
		tokens.sort(Comparator.comparingInt((SemanticToken t) -> t.line)
				.thenComparingInt(t -> t.column));

		// Deduplicate overlapping tokens
		tokens = deduplicateTokens(tokens);

		// Encode into the LSP relative format
		List<Integer> data = encodeTokens(tokens);
		return CompletableFuture.completedFuture(new SemanticTokens(data));
	}

	/**
	 * Provides semantic tokens for a specific range of a Groovy source file.
	 * Only AST nodes whose line range intersects the requested range are processed,
	 * which is significantly faster for large files when only the viewport is needed.
	 */
	public CompletableFuture<SemanticTokens> provideSemanticTokensRange(TextDocumentIdentifier textDocument, Range range) {
		if (ast == null) {
			return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
		}

		URI uri = URI.create(textDocument.getUri());

		// Load source lines for accurate name position lookup
		String source = fileContentsTracker != null ? fileContentsTracker.getContents(uri) : null;
		this.sourceLines = source != null ? source.split("\n", -1) : new String[0];

		// Precompute set of declared classes for O(1) lookup (scoped to this file)
		this.classNodeSet = new HashSet<>(ast.getClassNodes(uri));

		this.typeRefEmitter = new TypeReferenceTokenEmitter(sourceLines, ast);

		// LSP Range uses 0-based lines; Groovy AST uses 1-based lines
		int rangeStartLine = range.getStart().getLine();
		int rangeEndLine = range.getEnd().getLine();

		List<ASTNode> nodes = ast.getNodes(uri);
		List<SemanticToken> tokens = new ArrayList<>();

		for (ASTNode node : nodes) {
			int nodeStartLine = node.getLineNumber() - 1;     // convert to 0-based
			int nodeEndLine = node.getLastLineNumber() - 1;   // convert to 0-based

			// Skip nodes entirely outside the requested range
			if (nodeStartLine > rangeEndLine || nodeEndLine < rangeStartLine) {
				continue;
			}

			addTokensForNode(node, tokens);
		}

		// Emit namespace tokens for package declaration segments in the requested range
		typeRefEmitter.addPackageDeclarationNamespaceTokens(tokens, rangeStartLine + 1, rangeEndLine + 1);

		// Sort tokens by position (line, then column)
		tokens.sort(Comparator.comparingInt((SemanticToken t) -> t.line)
				.thenComparingInt(t -> t.column));

		// Deduplicate overlapping tokens
		tokens = deduplicateTokens(tokens);

		// Encode into the LSP relative format
		List<Integer> data = encodeTokens(tokens);
		return CompletableFuture.completedFuture(new SemanticTokens(data));
	}

	private void addTokensForNode(ASTNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}
		if (!addDeclarationLikeNodeToken(node, tokens)) {
			addExpressionLikeNodeToken(node, tokens);
		}
	}

	private boolean addDeclarationLikeNodeToken(ASTNode node, List<SemanticToken> tokens) {
		if (node instanceof AnnotationNode) {
			addAnnotationToken((AnnotationNode) node, tokens);
			return true;
		}
		if (node instanceof ClassNode) {
			addClassNodeToken((ClassNode) node, tokens);
			return true;
		}
		if (node instanceof MethodNode) {
			addMethodNodeToken((MethodNode) node, tokens);
			return true;
		}
		if (node instanceof FieldNode) {
			addFieldNodeToken((FieldNode) node, tokens);
			return true;
		}
		if (node instanceof PropertyNode) {
			addPropertyNodeToken((PropertyNode) node, tokens);
			return true;
		}
		if (node instanceof Parameter) {
			addParameterToken((Parameter) node, tokens);
			return true;
		}
		return false;
	}

	private void addExpressionLikeNodeToken(ASTNode node, List<SemanticToken> tokens) {
		if (node instanceof DeclarationExpression) {
			addDeclarationTypeToken((DeclarationExpression) node, tokens);
		} else if (node instanceof VariableExpression) {
			addVariableExpressionToken((VariableExpression) node, tokens);
		} else if (node instanceof MethodCallExpression) {
			addMethodCallToken((MethodCallExpression) node, tokens);
		} else if (node instanceof StaticMethodCallExpression) {
			addStaticMethodCallToken((StaticMethodCallExpression) node, tokens);
		} else if (node instanceof ConstructorCallExpression) {
			addConstructorCallToken((ConstructorCallExpression) node, tokens);
		} else if (node instanceof ClassExpression) {
			addClassExpressionToken((ClassExpression) node, tokens);
		} else if (node instanceof PropertyExpression) {
			addPropertyExpressionToken((PropertyExpression) node, tokens);
		} else if (node instanceof ImportNode) {
			addImportNodeToken((ImportNode) node, tokens);
		} else if (node instanceof MapEntryExpression) {
			addMapEntryExpressionToken((MapEntryExpression) node, tokens);
		}
	}

	private void addAnnotationToken(AnnotationNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}
		ClassNode classNode = node.getClassNode();
		String name = classNode.getNameWithoutPackage();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		// Include the '@' in the decorator token length so that '@Ignore'
		// is colored uniformly as a single decorator token.
		addToken(tokens, line, column, name.length() + 1, TYPE_DECORATOR, 0);
	}

	private void addClassNodeToken(ClassNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		boolean declaration = isClassDeclaration(node);
		int tokenType = classNodeToTokenType(node);
		int modifiers = classNodeModifiers(node, declaration);

		String name = node.getNameWithoutPackage();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		int nameCol = resolveClassNameColumn(line, column, name);
		emitDeclarationKeywordToken(tokens, declaration, line, column, nameCol);

		int effectiveColumn = nameCol > 0 ? nameCol : column;
		addToken(tokens, line, effectiveColumn, name.length(), tokenType, modifiers);
		emitClassDeclarationExtras(node, tokens, declaration);
	}

	private int classNodeModifiers(ClassNode node, boolean declaration) {
		int modifiers = 0;
		if (declaration) {
			modifiers |= MOD_DECLARATION;
		}
		if (Modifier.isAbstract(node.getModifiers())) {
			modifiers |= MOD_ABSTRACT;
		}
		if (Modifier.isStatic(node.getModifiers())) {
			modifiers |= MOD_STATIC;
		}
		return modifiers;
	}

	private int resolveClassNameColumn(int line, int column, String name) {
		if (!groovy4ColumnCompatibility) {
			return -1;
		}
		return findNameColumn(line, column, name);
	}

	private void emitDeclarationKeywordToken(List<SemanticToken> tokens,
									 boolean declaration,
									 int line,
									 int column,
									 int nameCol) {
		if (!declaration || nameCol <= 0 || nameCol <= column) {
			return;
		}
		String sourceLine = (sourceLines != null && line > 0 && line <= sourceLines.length)
				? sourceLines[line - 1] : null;
		if (sourceLine == null) {
			return;
		}
		int kwStart = column - 1;
		int kwEnd = nameCol - 1;
		String between = sourceLine.substring(kwStart, Math.min(kwEnd, sourceLine.length()));
		String keyword = between.trim();
		if (!keyword.isEmpty()) {
			addToken(tokens, line, column, keyword.length(), TYPE_KEYWORD, 0);
		}
	}

	private void emitClassDeclarationExtras(ClassNode node, List<SemanticToken> tokens, boolean declaration) {
		if (!declaration) {
			return;
		}
		typeRefEmitter.addClassGenericTypeParameterTokens(node, tokens);
		addExtendsImplementsKeywords(node, tokens);
	}

	/**
	 * Emits TYPE_KEYWORD semantic tokens for the {@code extends} and
	 * {@code implements} keywords that appear in a class/trait/interface
	 * declaration, so they are colored consistently with the declaration keyword.
	 */
	private void addExtendsImplementsKeywords(ClassNode node, List<SemanticToken> tokens) {
		if (sourceLines == null) {
			return;
		}

		// Find 'extends' keyword before the superclass name
		ClassNode superClass = node.getUnresolvedSuperClass();
		if (superClass != null && superClass.getLineNumber() > 0 && superClass.getColumnNumber() > 0) {
			addKeywordTokenBefore(superClass.getLineNumber(), superClass.getColumnNumber(), "extends", tokens);
		}

		// Find 'implements' keyword before the first interface name
		ClassNode[] interfaces = node.getUnresolvedInterfaces();
		if (interfaces != null && interfaces.length > 0) {
			ClassNode firstInterface = null;
			for (ClassNode iface : interfaces) {
				if (iface.getLineNumber() > 0 && iface.getColumnNumber() > 0
						&& (firstInterface == null
								|| iface.getLineNumber() < firstInterface.getLineNumber()
								|| (iface.getLineNumber() == firstInterface.getLineNumber()
										&& iface.getColumnNumber() < firstInterface.getColumnNumber()))) {
					firstInterface = iface;
				}
			}
			if (firstInterface != null) {
				addKeywordTokenBefore(firstInterface.getLineNumber(), firstInterface.getColumnNumber(),
						"implements", tokens);
			}
		}
	}

	/**
	 * Searches backward on the given line for a whole-word occurrence of
	 * {@code keyword} before the specified column, and emits a TYPE_KEYWORD
	 * semantic token if found.
	 */
	private void addKeywordTokenBefore(int groovyLine, int beforeColumn, String keyword,
			List<SemanticToken> tokens) {
		if (sourceLines == null || groovyLine <= 0 || groovyLine > sourceLines.length) {
			return;
		}
		String line = sourceLines[groovyLine - 1];
		int searchEnd = beforeColumn > 0 ? beforeColumn - 1 : line.length(); // 0-based
		int idx = line.lastIndexOf(keyword, searchEnd - 1);
		if (idx >= 0) {
			boolean startOk = idx == 0 || !Character.isJavaIdentifierPart(line.charAt(idx - 1));
			boolean endOk = (idx + keyword.length() >= line.length())
					|| !Character.isJavaIdentifierPart(line.charAt(idx + keyword.length()));
			if (startOk && endOk) {
				addToken(tokens, groovyLine, idx + 1, keyword.length(), TYPE_KEYWORD, 0);
			}
		}
	}

	private void addMethodNodeToken(MethodNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		// Skip Spock-style string method names (contain spaces or non-identifier chars)
		// These are highlighted as string literals by TextMate grammar
		String name = node.getName();
		if (!isValidIdentifier(name)) {
			return;
		}

		int tokenType = (node instanceof ConstructorNode) ? TYPE_FUNCTION : TYPE_METHOD;
		int modifiers = MOD_DECLARATION;
		if (Modifier.isStatic(node.getModifiers())) {
			modifiers |= MOD_STATIC;
		}
		if (Modifier.isAbstract(node.getModifiers())) {
			modifiers |= MOD_ABSTRACT;
		}

		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		if (groovy4ColumnCompatibility) {
			// In Groovy 4, MethodNode.getColumnNumber() points to the first modifier
			// or return type keyword, not the method name.
			int nameCol = findNameColumn(line, column, name);
			if (nameCol > 0) {
				column = nameCol;
			}
		}

		// Emit a type token for the method's return type (e.g. 'String' in 'String getName()')
		if (!(node instanceof ConstructorNode)) {
			typeRefEmitter.addMethodReturnTypeToken(node, line, column, tokens);
		}

		// Emit type parameter tokens for generic method declarations (e.g. <T> in '<T> T find()')
		typeRefEmitter.addGenericTypeParameterTokens(node.getGenericsTypes(), tokens);

		addToken(tokens, line, column, name.length(), tokenType, modifiers);
	}

	private void addFieldNodeToken(FieldNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		String name = node.getName();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		if (groovy4ColumnCompatibility) {
			// In Groovy 4, FieldNode.getColumnNumber() may point to the TYPE name,
			// not the field NAME.
			int nameCol = findNameColumn(line, column, name);
			if (nameCol > 0 && nameCol > column) {
				column = nameCol;
			}
		}

		// Add type reference token for explicitly-typed field declarations
		typeRefEmitter.addDeclaredTypeReferenceToken(node, line, column, tokens);

		int tokenType = TYPE_PROPERTY;
		int modifiers = MOD_DECLARATION;
		if (Modifier.isStatic(node.getModifiers())) {
			modifiers |= MOD_STATIC;
		}
		if (Modifier.isFinal(node.getModifiers())) {
			modifiers |= MOD_READONLY;
		}

		// Check if this is an enum constant
		if (node.getDeclaringClass() != null && node.getDeclaringClass().isEnum()
				&& node.isEnum()) {
			tokenType = TYPE_ENUM_MEMBER;
		}

		addToken(tokens, line, column, name.length(), tokenType, modifiers);
	}

	private void addPropertyNodeToken(PropertyNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		String name = node.getName();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		if (groovy4ColumnCompatibility) {
			// In Groovy 4, PropertyNode.getColumnNumber() may point to the TYPE name,
			// not the property NAME.
			int nameCol = findNameColumn(line, column, name);
			if (nameCol > 0 && nameCol > column) {
				column = nameCol;
			}
		}

		// Add type reference token for explicitly-typed property declarations
		if (node.getField() != null) {
			typeRefEmitter.addDeclaredTypeReferenceToken(node.getField(), line, column, tokens);
		}

		int modifiers = MOD_DECLARATION;
		if (Modifier.isStatic(node.getModifiers())) {
			modifiers |= MOD_STATIC;
		}
		if (Modifier.isFinal(node.getModifiers())) {
			modifiers |= MOD_READONLY;
		}

		addToken(tokens, line, column, name.length(), TYPE_PROPERTY, modifiers);
	}

	private void addParameterToken(Parameter node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		String name = node.getName();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		if (groovy4ColumnCompatibility && node.getLastLineNumber() == line
				&& node.getLastColumnNumber() > 0 && !node.hasInitialExpression()) {
			// In Groovy 4's Parrot parser, Parameter.getColumnNumber() points to the
			// start of the TYPE, not the parameter NAME.
			int nameStart = node.getLastColumnNumber() - name.length();
			if (nameStart > column) {
				column = nameStart;
			}
		}

		addToken(tokens, line, column, name.length(), TYPE_PARAMETER, MOD_DECLARATION);

		// Emit a type token for the parameter's declared type (e.g. 'String' in 'String name')
		typeRefEmitter.addParameterTypeToken(node, line, column, tokens);
	}

	private void addVariableExpressionToken(VariableExpression node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		String name = node.getName();
		// Skip 'this' and 'super' — they're keywords
		if ("this".equals(name) || "super".equals(name)) {
			return;
		}

		// Determine what the variable resolves to
		if (node.getAccessedVariable() instanceof Parameter) {
			addToken(tokens, node.getLineNumber(), node.getColumnNumber(), name.length(), TYPE_PARAMETER, 0);
		} else if (node.getAccessedVariable() instanceof FieldNode) {
			FieldNode field = (FieldNode) node.getAccessedVariable();
			int modifiers = 0;
			if (Modifier.isStatic(field.getModifiers())) {
				modifiers |= MOD_STATIC;
			}
			if (Modifier.isFinal(field.getModifiers())) {
				modifiers |= MOD_READONLY;
			}
			addToken(tokens, node.getLineNumber(), node.getColumnNumber(), name.length(), TYPE_PROPERTY, modifiers);
		} else {
			// Variable not resolved (e.g. Spock where-block data table variables).
			// Check if this name matches a parameter of the enclosing method —
			// this ensures consistent coloring for where-block variables.
			if (typeRefEmitter.isEnclosingMethodParameter(node, name)) {
				addToken(tokens, node.getLineNumber(), node.getColumnNumber(), name.length(), TYPE_PARAMETER, 0);
			} else {
				addToken(tokens, node.getLineNumber(), node.getColumnNumber(), name.length(), TYPE_VARIABLE, 0);
			}
		}
	}

	private void addMethodCallToken(MethodCallExpression node, List<SemanticToken> tokens) {
		// Highlight just the method name portion, not the entire call expression
		ASTNode methodNode = node.getMethod();
		if (methodNode == null || methodNode.getLineNumber() == -1) {
			return;
		}
		String methodName = node.getMethodAsString();
		if (methodName == null) {
			return;
		}

		int modifiers = 0;
		if (node.getObjectExpression() instanceof ClassExpression) {
			// Static call like ClassName.method()
			modifiers |= MOD_STATIC;
		}

		addToken(tokens, methodNode.getLineNumber(), methodNode.getColumnNumber(), methodName.length(), TYPE_METHOD,
				modifiers);
	}

	private void addStaticMethodCallToken(StaticMethodCallExpression node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}
		addToken(tokens, node.getLineNumber(), node.getColumnNumber(), node.getMethod().length(), TYPE_METHOD,
				MOD_STATIC);
	}

	private void addConstructorCallToken(ConstructorCallExpression node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		ClassNode typeNode = node.getType();
		if (typeNode == null) {
			return;
		}

		String name = typeNode.getNameWithoutPackage();
		// The 'new' keyword is already highlighted by TextMate; highlight the type name
		int tokenType = classNodeToTokenType(typeNode);

		if (typeNode.getLineNumber() != -1) {
			int line = typeNode.getLineNumber();
			int column = typeNode.getColumnNumber();

			if (groovy4ColumnCompatibility) {
				// In Groovy 4, the type node's getColumnNumber() in a constructor call
				// may point to the 'new' keyword.
				int nameCol = findNameColumn(line, column, name);
				if (nameCol > 0) {
					column = nameCol;
				}
			}

			addToken(tokens, line, column, name.length(), tokenType, 0);
		}
	}

	private void addClassExpressionToken(ClassExpression node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}
		ClassNode type = node.getType();
		String name = type.getNameWithoutPackage();
		int tokenType = classNodeToTokenType(type);
		addToken(tokens, node.getLineNumber(), node.getColumnNumber(), name.length(), tokenType, 0);
	}

	private void addPropertyExpressionToken(PropertyExpression node, List<SemanticToken> tokens) {
		// Highlight the property part (right side) of property expressions
		ASTNode property = node.getProperty();
		if (property == null || property.getLineNumber() == -1) {
			return;
		}
		if (!(property instanceof ConstantExpression)) {
			return;
		}
		String propName = ((ConstantExpression) property).getText();
		if (propName == null) {
			return;
		}
		// We can't easily determine if this is a field or method without resolution,
		// so we mark it as a property
		addToken(tokens, property.getLineNumber(), property.getColumnNumber(), propName.length(), TYPE_PROPERTY, 0);
	}

	private void addImportNodeToken(ImportNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}
		ClassNode type = node.getType();
		if (type != null) {
			String name = type.getNameWithoutPackage();
			// Always compute the column from the ImportNode's end position.
			// The ClassNode's getColumnNumber() points to the start of the
			// fully qualified name (e.g. "spock" in "spock.lang.Specification"),
			// but we only want to highlight the short class name at the end.
			// getLastColumnNumber() is 1-based and exclusive, so subtracting
			// the name length gives the 1-based start of the class name.
			int line = node.getLastLineNumber();
			int column = node.getLastColumnNumber() - name.length();

			// Emit namespace tokens for the package segments before the class name.
			// For 'import com.example.Foo', highlight 'com.example' as namespace.
			typeRefEmitter.addImportPackageNamespaceTokens(node, type, tokens);

			// Use generic "type" for all imports so they have consistent coloring
			addToken(tokens, line, column, name.length(), TYPE_TYPE, 0);
		}
	}

	/**
	 * Adds a semantic token for the type name in a local variable declaration.
	 * For example, in {@code MathHelper helper = new MathHelper()}, this highlights
	 * the first {@code MathHelper} as a type reference.
	 */
	private void addDeclarationTypeToken(DeclarationExpression node, List<SemanticToken> tokens) {
		if (!(node.getLeftExpression() instanceof VariableExpression)) {
			return;
		}
		VariableExpression varExpr = (VariableExpression) node.getLeftExpression();
		if (varExpr.isDynamicTyped()) {
			return; // 'def' declarations have no explicit type to highlight
		}
		ClassNode originType = varExpr.getOriginType();
		if (originType == null || originType.getLineNumber() == -1) {
			return;
		}
		String typeName = originType.getNameWithoutPackage();
		if (isPrimitiveType(typeName)) {
			return; // Primitive types are handled by TextMate grammar
		}
		int tokenType = classNodeToTokenType(originType);
		addToken(tokens, originType.getLineNumber(), originType.getColumnNumber(), typeName.length(), tokenType, 0);
	}

	/**
	 * Adds a semantic token for the key in a named argument expression.
	 * For example, in {@code method(paramName: value)}, this highlights
	 * {@code paramName} as a parameter.
	 */
	private void addMapEntryExpressionToken(MapEntryExpression node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}
		if (!(node.getKeyExpression() instanceof ConstantExpression)) {
			return;
		}
		ConstantExpression keyExpr = (ConstantExpression) node.getKeyExpression();
		if (keyExpr.getLineNumber() == -1) {
			return;
		}
		String keyName = keyExpr.getText();
		if (keyName != null && isValidIdentifier(keyName)) {
			addToken(tokens, keyExpr.getLineNumber(), keyExpr.getColumnNumber(), keyName.length(), TYPE_PARAMETER, 0);
		}
	}

	/**
	 * Checks whether a ClassNode represents an actual class/interface/enum
	 * declaration, as opposed to a type reference (e.g. in extends/implements).
	 */
	private boolean isClassDeclaration(ClassNode node) {
		return classNodeSet != null && classNodeSet.contains(node);
	}

	/**
	 * Returns true if the type name is a primitive type that is already
	 * handled by the TextMate grammar.
	 */
	static boolean isPrimitiveType(String typeName) {
		switch (typeName) {
			case "int": case "long": case "short": case "byte":
			case "float": case "double": case "boolean": case "char":
			case "void":
				return true;
			default:
				return false;
		}
	}

	/**
	 * Checks if a name is a valid Java/Groovy identifier.
	 * Non-identifier method names (e.g. Spock test names with spaces) should not
	 * get semantic tokens since they are string literals in source.
	 */
	private static boolean isValidIdentifier(String name) {
		if (name == null || name.isEmpty()) {
			return false;
		}
		if (!Character.isJavaIdentifierStart(name.charAt(0))) {
			return false;
		}
		for (int i = 1; i < name.length(); i++) {
			if (!Character.isJavaIdentifierPart(name.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Removes duplicate and overlapping tokens. When two tokens share the same
	 * position, the more specific (higher priority) one is kept. When a token is
	 * contained within a longer token's range, the shorter one is preferred.
	 */
	private List<SemanticToken> deduplicateTokens(List<SemanticToken> sorted) {
		if (sorted.size() <= 1) {
			return sorted;
		}

		List<SemanticToken> result = new ArrayList<>();

		for (SemanticToken token : sorted) {
			if (result.isEmpty() || (!handleSamePositionToken(result, token) && !handleOverlappingToken(result, token))) {
				result.add(token);
			}
		}

		return result;
	}

	private boolean handleSamePositionToken(List<SemanticToken> result, SemanticToken token) {
		SemanticToken last = result.get(result.size() - 1);
		if (token.line == last.line && token.column == last.column) {
			if (shouldReplace(last, token)) {
				result.set(result.size() - 1, token);
			}
			return true;
		}
		return false;
	}

	private boolean handleOverlappingToken(List<SemanticToken> result, SemanticToken token) {
		SemanticToken last = result.get(result.size() - 1);
		if (token.line == last.line && token.column < last.column + last.length) {
			if (token.length <= last.length) {
				result.set(result.size() - 1, token);
			}
			return true;
		}
		return false;
	}

	/**
	 * Returns true if the candidate token should replace the existing token
	 * at the same position.
	 */
	private boolean shouldReplace(SemanticToken existing, SemanticToken candidate) {
		// Prefer shorter tokens (more specific/precise)
		if (candidate.length < existing.length) {
			return true;
		}
		if (candidate.length > existing.length) {
			return false;
		}
		// Same length: prefer higher type priority
		return tokenPriority(candidate.tokenType) > tokenPriority(existing.tokenType);
	}

	/**
	 * Returns a priority value for a token type. Higher = preferred when
	 * resolving overlaps between tokens at the same position.
	 */
	private int tokenPriority(int tokenType) {
		switch (tokenType) {
			case TYPE_METHOD: return 12;
			case TYPE_FUNCTION: return 11;
			case TYPE_ENUM_MEMBER: return 10;
			case TYPE_PARAMETER: return 9;
			case TYPE_PROPERTY: return 8;
			case TYPE_VARIABLE: return 7;
			case TYPE_CLASS: return 6;
			case TYPE_INTERFACE: return 6;
			case TYPE_ENUM: return 6;
			case TYPE_TYPE_PARAMETER: return 5;
			case TYPE_NAMESPACE: return 1;
			default: return 0;
		}
	}

	static int classNodeToTokenType(ClassNode node) {
		if (node.isAnnotationDefinition()) {
			return TYPE_DECORATOR;
		} else if (node.isInterface()) {
			return TYPE_INTERFACE;
		} else if (node.isEnum()) {
			return TYPE_ENUM;
		} else if (Traits.isTrait(node)) {
			return TYPE_INTERFACE;
		}
		return TYPE_CLASS;
	}

	static void addToken(List<SemanticToken> tokens, int groovyLine, int groovyColumn, int length, int tokenType,
			int tokenModifiers) {
		if (groovyLine <= 0 || length <= 0) {
			return;
		}
		// Groovy lines are 1-based, LSP lines are 0-based
		int lspLine = groovyLine - 1;
		// Groovy columns are 1-based, LSP columns are 0-based
		int lspColumn = groovyColumn > 0 ? groovyColumn - 1 : 0;

		SemanticToken token = new SemanticToken();
		token.line = lspLine;
		token.column = lspColumn;
		token.length = length;
		token.tokenType = tokenType;
		token.tokenModifiers = tokenModifiers;
		tokens.add(token);
	}

	/**
	 * Encodes tokens into the LSP relative format:
	 * [deltaLine, deltaStartChar, length, tokenType, tokenModifiers, ...]
	 */
	private List<Integer> encodeTokens(List<SemanticToken> tokens) {
		List<Integer> data = new ArrayList<>(tokens.size() * 5);
		int prevLine = 0;
		int prevColumn = 0;

		for (SemanticToken token : tokens) {
			int deltaLine = token.line - prevLine;
			int deltaColumn = (deltaLine == 0) ? token.column - prevColumn : token.column;

			data.add(deltaLine);
			data.add(deltaColumn);
			data.add(token.length);
			data.add(token.tokenType);
			data.add(token.tokenModifiers);

			prevLine = token.line;
			prevColumn = token.column;
		}

		return data;
	}

	static class SemanticToken {
		int line;
		int column;
		int length;
		int tokenType;
		int tokenModifiers;
	}

	/**
	 * Finds the 1-based column of identifier {@code name} in the source line,
	 * searching from {@code startColumn} (1-based) onwards. This is necessary
	 * because Groovy 4's Parrot parser stores the column of the preceding
	 * keyword/type in many AST nodes, not the identifier itself.
	 *
	 * @return the 1-based column of the name, or -1 if not found
	 */
	private int findNameColumn(int groovyLine, int startColumn, String name) {
		if (sourceLines == null || groovyLine <= 0 || groovyLine > sourceLines.length) {
			return -1;
		}
		String line = sourceLines[groovyLine - 1]; // 0-based array index
		int searchFrom = startColumn > 0 ? startColumn - 1 : 0; // convert to 0-based
		int idx = line.indexOf(name, searchFrom);
		if (idx < 0) {
			return -1;
		}
		if (!isWholeWordMatch(line, idx, name)) {
			idx = findNextWholeWordMatch(line, name, idx + 1);
			if (idx < 0) {
				return -1;
			}
		}
		return idx + 1; // convert back to 1-based
	}

	private int findNextWholeWordMatch(String line, String name, int searchFrom) {
		int idx = line.indexOf(name, searchFrom);
		while (idx >= 0) {
			if (isWholeWordMatch(line, idx, name)) {
				return idx;
			}
			idx = line.indexOf(name, idx + 1);
		}
		return -1;
	}

	private boolean isWholeWordMatch(String line, int startIndex, String name) {
		boolean startOk = startIndex == 0 || !Character.isJavaIdentifierPart(line.charAt(startIndex - 1));
		int afterEnd = startIndex + name.length();
		boolean endOk = afterEnd >= line.length() || !Character.isJavaIdentifierPart(line.charAt(afterEnd));
		return startOk && endOk;
	}
}
