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
import java.util.List;
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

	// Token types — order matters, indices are used in the encoding
	public static final List<String> TOKEN_TYPES = Collections.unmodifiableList(Arrays.asList(
			"namespace",   // 0
			"type",        // 1
			"class",       // 2
			"interface",   // 3
			"enum",        // 4
			"parameter",   // 5
			"variable",    // 6
			"property",    // 7
			"function",    // 8
			"method",      // 9
			"decorator",   // 10
			"enumMember"   // 11
	));

	// Token modifiers — bit flags
	public static final List<String> TOKEN_MODIFIERS = Collections.unmodifiableList(Arrays.asList(
			"declaration",   // bit 0
			"static",        // bit 1
			"readonly",      // bit 2
			"deprecated",    // bit 3
			"abstract",      // bit 4
			"defaultLibrary" // bit 5
	));

	private static final int TYPE_NAMESPACE = 0;
	private static final int TYPE_TYPE = 1;
	private static final int TYPE_CLASS = 2;
	private static final int TYPE_INTERFACE = 3;
	private static final int TYPE_ENUM = 4;
	private static final int TYPE_PARAMETER = 5;
	private static final int TYPE_VARIABLE = 6;
	private static final int TYPE_PROPERTY = 7;
	private static final int TYPE_FUNCTION = 8;
	private static final int TYPE_METHOD = 9;
	private static final int TYPE_DECORATOR = 10;
	private static final int TYPE_ENUM_MEMBER = 11;

	private static final int MOD_DECLARATION = 1;       // bit 0
	private static final int MOD_STATIC = 1 << 1;       // bit 1
	private static final int MOD_READONLY = 1 << 2;     // bit 2
	private static final int MOD_DEPRECATED = 1 << 3;   // bit 3
	private static final int MOD_ABSTRACT = 1 << 4;     // bit 4
	private static final int MOD_DEFAULT_LIBRARY = 1 << 5; // bit 5

	private ASTNodeVisitor ast;
	private FileContentsTracker fileContentsTracker;
	private String[] sourceLines;

	public SemanticTokensProvider(ASTNodeVisitor ast, FileContentsTracker fileContentsTracker) {
		this.ast = ast;
		this.fileContentsTracker = fileContentsTracker;
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

		List<ASTNode> nodes = ast.getNodes(uri);
		List<SemanticToken> tokens = new ArrayList<>();

		for (ASTNode node : nodes) {
			addTokensForNode(node, uri, tokens);
		}

		// Sort tokens by position (line, then column)
		tokens.sort(Comparator.comparingInt((SemanticToken t) -> t.line)
				.thenComparingInt(t -> t.column));

		// Deduplicate overlapping tokens
		tokens = deduplicateTokens(tokens);

		// Encode into the LSP relative format
		List<Integer> data = encodeTokens(tokens);
		return CompletableFuture.completedFuture(new SemanticTokens(data));
	}

	private void addTokensForNode(ASTNode node, URI uri, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		if (node instanceof AnnotationNode) {
			addAnnotationToken((AnnotationNode) node, tokens);
		} else if (node instanceof ClassNode) {
			addClassNodeToken((ClassNode) node, tokens);
		} else if (node instanceof MethodNode) {
			addMethodNodeToken((MethodNode) node, tokens);
		} else if (node instanceof FieldNode) {
			addFieldNodeToken((FieldNode) node, tokens);
		} else if (node instanceof PropertyNode) {
			addPropertyNodeToken((PropertyNode) node, tokens);
		} else if (node instanceof Parameter) {
			addParameterToken((Parameter) node, tokens);
		} else if (node instanceof DeclarationExpression) {
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

		int tokenType = classNodeToTokenType(node);
		int modifiers = 0;
		// Only mark as declaration for actual class declarations,
		// not for superclass/interface type references
		if (isClassDeclaration(node)) {
			modifiers |= MOD_DECLARATION;
		}
		if (Modifier.isAbstract(node.getModifiers())) {
			modifiers |= MOD_ABSTRACT;
		}
		if (Modifier.isStatic(node.getModifiers())) {
			modifiers |= MOD_STATIC;
		}

		String name = node.getNameWithoutPackage();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		// In Groovy 4, ClassNode.getColumnNumber() points to the 'class'/'interface'/'enum'
		// keyword, not the class name. Find the actual name in the source.
		int nameCol = findNameColumn(line, column, name);
		if (nameCol > 0) {
			column = nameCol;
		}

		addToken(tokens, line, column, name.length(), tokenType, modifiers);
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

		addToken(tokens, node.getLineNumber(), node.getColumnNumber(), name.length(), tokenType, modifiers);
	}

	private void addFieldNodeToken(FieldNode node, List<SemanticToken> tokens) {
		if (node.getLineNumber() == -1) {
			return;
		}

		String name = node.getName();
		int line = node.getLineNumber();
		int column = node.getColumnNumber();

		// In Groovy 4, FieldNode.getColumnNumber() may point to the TYPE name,
		// not the field NAME. Find the actual name in the source.
		int nameCol = findNameColumn(line, column, name);
		if (nameCol > 0 && nameCol > column) {
			column = nameCol;
		}

		// Add type reference token for explicitly-typed field declarations
		addDeclaredTypeReferenceToken(node, line, column, tokens);

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

		// In Groovy 4, PropertyNode.getColumnNumber() may point to the TYPE name,
		// not the property NAME. Find the actual name in the source.
		int nameCol = findNameColumn(line, column, name);
		if (nameCol > 0 && nameCol > column) {
			column = nameCol;
		}

		// Add type reference token for explicitly-typed property declarations
		if (node.getField() != null) {
			addDeclaredTypeReferenceToken(node.getField(), line, column, tokens);
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

		// In Groovy 4's Parrot parser, Parameter.getColumnNumber() points to the
		// start of the TYPE (e.g. "int"), not the parameter NAME (e.g. "currentFrame").
		// For single-line parameters without default values, getLastColumnNumber()
		// is the exclusive end of the name, so we can compute the name's start.
		if (node.getLastLineNumber() == line
				&& node.getLastColumnNumber() > 0
				&& !node.hasInitialExpression()) {
			int nameStart = node.getLastColumnNumber() - name.length();
			if (nameStart > column) {
				// The name must come after the type — use the computed position
				column = nameStart;
			}
		}

		addToken(tokens, line, column, name.length(), TYPE_PARAMETER, MOD_DECLARATION);
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
			if (isEnclosingMethodParameter(node, name)) {
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

			// In Groovy 4, the type node's getColumnNumber() in a constructor call
			// may point to the 'new' keyword. Find the actual type name in the source.
			int nameCol = findNameColumn(line, column, name);
			if (nameCol > 0) {
				column = nameCol;
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
	 * Adds a semantic token for the declared type of a field or property.
	 * For example, in {@code MathHelper helper = new MathHelper()}, this highlights
	 * the type {@code MathHelper} before the field name.
	 *
	 * @param field      the backing FieldNode
	 * @param nameLine   line number of the field/property name
	 * @param nameColumn column number of the field/property name
	 */
	private void addDeclaredTypeReferenceToken(FieldNode field, int nameLine, int nameColumn,
			List<SemanticToken> tokens) {
		if (field.isDynamicTyped() || field.isEnum()) {
			return;
		}
		ClassNode originType = field.getOriginType();
		if (originType == null || originType.getLineNumber() == -1) {
			return;
		}
		String typeName = originType.getNameWithoutPackage();
		if (isPrimitiveType(typeName)) {
			return;
		}
		// Verify the type position is on the same line and before the field name
		if (originType.getLineNumber() != nameLine || originType.getColumnNumber() >= nameColumn) {
			return;
		}
		int tokenType = classNodeToTokenType(originType);
		addToken(tokens, originType.getLineNumber(), originType.getColumnNumber(), typeName.length(), tokenType, 0);
	}

	/**
	 * Checks whether a ClassNode represents an actual class/interface/enum
	 * declaration, as opposed to a type reference (e.g. in extends/implements).
	 */
	private boolean isClassDeclaration(ClassNode node) {
		for (ClassNode cn : ast.getClassNodes()) {
			if (cn == node) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the type name is a primitive type that is already
	 * handled by the TextMate grammar.
	 */
	private static boolean isPrimitiveType(String typeName) {
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
	 * Walks up the AST parent chain to find the enclosing MethodNode and checks
	 * if it has a parameter with the given name. This handles cases where
	 * Spock (or other AST transforms) don't properly link VariableExpressions
	 * to their Parameter declarations (e.g. in where-block data tables).
	 */
	private boolean isEnclosingMethodParameter(ASTNode node, String name) {
		ASTNode current = ast.getParent(node);
		while (current != null) {
			if (current instanceof MethodNode) {
				MethodNode method = (MethodNode) current;
				for (Parameter param : method.getParameters()) {
					if (name.equals(param.getName())) {
						return true;
					}
				}
				return false;
			}
			current = ast.getParent(current);
		}
		return false;
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
			if (result.isEmpty()) {
				result.add(token);
				continue;
			}

			SemanticToken last = result.get(result.size() - 1);

			// Exact same position: keep the better token
			if (token.line == last.line && token.column == last.column) {
				if (shouldReplace(last, token)) {
					result.set(result.size() - 1, token);
				}
				continue;
			}

			// Overlap on same line: current starts within previous token's range
			if (token.line == last.line && token.column < last.column + last.length) {
				// Prefer the shorter/more specific token
				if (token.length <= last.length) {
					result.set(result.size() - 1, token);
				}
				// Otherwise skip the current (longer) overlapping token
				continue;
			}

			// No overlap
			result.add(token);
		}

		return result;
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
			default: return 0;
		}
	}

	private int classNodeToTokenType(ClassNode node) {
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

	private void addToken(List<SemanticToken> tokens, int groovyLine, int groovyColumn, int length, int tokenType,
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

	private static class SemanticToken {
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
		// Verify it's a whole-word match: the character before must not be an identifier part,
		// and the character after must not be an identifier part
		if (idx > 0 && Character.isJavaIdentifierPart(line.charAt(idx - 1))) {
			// Not a whole word — try searching further
			while (idx >= 0) {
				idx = line.indexOf(name, idx + 1);
				if (idx < 0) {
					return -1;
				}
				boolean startOk = idx == 0 || !Character.isJavaIdentifierPart(line.charAt(idx - 1));
				boolean endOk = (idx + name.length() >= line.length())
						|| !Character.isJavaIdentifierPart(line.charAt(idx + name.length()));
				if (startOk && endOk) {
					break;
				}
			}
			if (idx < 0) {
				return -1;
			}
		}
		return idx + 1; // convert back to 1-based
	}
}
