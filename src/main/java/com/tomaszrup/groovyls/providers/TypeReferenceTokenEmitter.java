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

import static com.tomaszrup.groovyls.providers.SemanticTokensProvider.TYPE_CLASS;
import static com.tomaszrup.groovyls.providers.SemanticTokensProvider.TYPE_NAMESPACE;
import static com.tomaszrup.groovyls.providers.SemanticTokensProvider.TYPE_TYPE_PARAMETER;
import static com.tomaszrup.groovyls.providers.SemanticTokensProvider.addToken;
import static com.tomaszrup.groovyls.providers.SemanticTokensProvider.classNodeToTokenType;
import static com.tomaszrup.groovyls.providers.SemanticTokensProvider.isPrimitiveType;

import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.SemanticTokensProvider.SemanticToken;

/**
 * Emits semantic tokens for type references, generic type parameters,
 * and namespace segments (import/package declarations).
 *
 * <p>Extracted from {@link SemanticTokensProvider} to keep class sizes manageable.
 * Uses static imports from {@code SemanticTokensProvider} for token constants
 * and shared utilities.</p>
 */
class TypeReferenceTokenEmitter {
	private static final String STATIC_KEYWORD = "static";
	private static final String PACKAGE_KEYWORD = "package";

	private final String[] sourceLines;
	private final ASTNodeVisitor ast;

	TypeReferenceTokenEmitter(String[] sourceLines, ASTNodeVisitor ast) {
		this.sourceLines = sourceLines;
		this.ast = ast;
	}

	// ── Type reference tokens ────────────────────────────────────────────────

	/**
	 * Adds a semantic token for the declared type of a field or property.
	 * For example, in {@code MathHelper helper = new MathHelper()}, this highlights
	 * the type {@code MathHelper} before the field name.
	 *
	 * @param field      the backing FieldNode
	 * @param nameLine   line number of the field/property name
	 * @param nameColumn column number of the field/property name
	 */
	void addDeclaredTypeReferenceToken(FieldNode field, int nameLine, int nameColumn,
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
	 * Adds a semantic token for the return type of a method declaration.
	 * For example, in {@code String getName()}, this highlights {@code String}
	 * as a type reference. Primitive return types are skipped (handled by TextMate).
	 */
	void addMethodReturnTypeToken(MethodNode node, int nameLine, int nameColumn,
			List<SemanticToken> tokens) {
		if (node.isDynamicReturnType()) {
			IdentifierOccurrence inferred = findPreviousIdentifierBefore(nameLine, nameColumn);
			if (inferred == null || "def".equals(inferred.identifier)
					|| isPrimitiveType(inferred.identifier)) {
				return;
			}
			addToken(tokens, nameLine, inferred.column, inferred.identifier.length(), TYPE_CLASS, 0);
			return;
		}
		ClassNode returnType = node.getReturnType();
		if (returnType == null) {
			return;
		}
		String typeName = returnType.getNameWithoutPackage();
		if (isPrimitiveType(typeName)) {
			return;
		}
		int typeLine = returnType.getLineNumber();
		int typeColumn = returnType.getColumnNumber();
		if (typeLine <= 0) {
			typeLine = nameLine;
		}
		boolean validAstPosition = typeLine > 0
				&& (typeLine < nameLine || (typeLine == nameLine && typeColumn > 0 && typeColumn < nameColumn));
		if (!validAstPosition) {
			typeLine = nameLine;
			typeColumn = findIdentifierColumnBefore(nameLine, nameColumn, typeName);
			if (typeColumn <= 0) {
				return;
			}
		}
		int tokenType = classNodeToTokenType(returnType);
		addToken(tokens, typeLine, typeColumn, typeName.length(), tokenType, 0);

		// Also emit tokens for generic type arguments in the return type (e.g. List<String>)
		addGenericTypeParameterTokens(returnType.getGenericsTypes(), tokens);
	}

	/**
	 * Adds a semantic token for the type of a parameter declaration.
	 * For example, in {@code void foo(String name)}, this highlights {@code String}
	 * as a type reference. Primitive parameter types are skipped (handled by TextMate).
	 */
	void addParameterTypeToken(Parameter node, int nameLine, int nameColumn,
			List<SemanticToken> tokens) {
		if (node.isDynamicTyped()) {
			IdentifierOccurrence inferred = findPreviousIdentifierBefore(nameLine, nameColumn);
			if (inferred == null || "def".equals(inferred.identifier)
					|| isPrimitiveType(inferred.identifier)) {
				return;
			}
			addToken(tokens, nameLine, inferred.column, inferred.identifier.length(), TYPE_CLASS, 0);
			return;
		}
		ClassNode paramType = node.getOriginType();
		if (paramType == null) {
			return;
		}
		String typeName = paramType.getNameWithoutPackage();
		if (isPrimitiveType(typeName)) {
			return;
		}
		int typeLine = paramType.getLineNumber();
		int typeColumn = paramType.getColumnNumber();
		if (typeLine <= 0) {
			typeLine = nameLine;
		}
		boolean validAstPosition = typeLine > 0
				&& (typeLine < nameLine || (typeLine == nameLine && typeColumn > 0 && typeColumn < nameColumn));
		if (!validAstPosition) {
			typeLine = nameLine;
			typeColumn = findIdentifierColumnBefore(nameLine, nameColumn, typeName);
			if (typeColumn <= 0) {
				return;
			}
		}
		int tokenType = classNodeToTokenType(paramType);
		addToken(tokens, typeLine, typeColumn, typeName.length(), tokenType, 0);

		// Also emit tokens for generic type arguments (e.g. List<String>)
		addGenericTypeParameterTokens(paramType.getGenericsTypes(), tokens);
	}

	// ── Generics handling ────────────────────────────────────────────────────

	/**
	 * Emits {@code typeParameter} tokens for generic type parameters.
	 * For example, in {@code class Foo<T, E extends Comparable>}, this highlights
	 * {@code T} and {@code E} as type parameters. Also handles generic type arguments
	 * like {@code String} in {@code List<String>}.
	 *
	 * <p>Groovy's AST often does not provide position information for
	 * {@code GenericsType} nodes, so this method first tries the AST positions
	 * and falls back to scanning the source line when positions are missing.</p>
	 */
	void addGenericTypeParameterTokens(GenericsType[] genericsTypes, List<SemanticToken> tokens) {
		if (genericsTypes == null) {
			return;
		}
		for (GenericsType gt : genericsTypes) {
			processGenericTypeToken(gt, tokens);
		}
	}

	private void processGenericTypeToken(GenericsType gt, List<SemanticToken> tokens) {
		ClassNode gtType = gt.getType();
		if (gtType == null) {
			return;
		}
		String name = genericTypeDisplayName(gt, gtType);
		if (name == null || name.isEmpty()) {
			return;
		}
		GenericTokenPosition position = resolveGenericTypePosition(gt, gtType);
		if (!position.isKnown()) {
			return;
		}
		emitGenericTypeToken(gt, gtType, name, position, tokens);
		addGenericTypeParameterTokens(gtType.getGenericsTypes(), tokens);
		emitGenericUpperBounds(gt.getUpperBounds(), tokens);
	}

	private String genericTypeDisplayName(GenericsType gt, ClassNode gtType) {
		if (gt.isPlaceholder() || gt.isWildcard()) {
			return gt.getName();
		}
		return gtType.getNameWithoutPackage();
	}

	private GenericTokenPosition resolveGenericTypePosition(GenericsType gt, ClassNode gtType) {
		int gtLine = gt.getLineNumber();
		int gtColumn = gt.getColumnNumber();
		if (gtLine == -1 && !gt.isPlaceholder() && !gt.isWildcard() && gtType.getLineNumber() > 0) {
			gtLine = gtType.getLineNumber();
			gtColumn = gtType.getColumnNumber();
		}
		return new GenericTokenPosition(gtLine, gtColumn);
	}

	private void emitGenericTypeToken(GenericsType gt,
									ClassNode gtType,
									String name,
									GenericTokenPosition position,
									List<SemanticToken> tokens) {
		if (gt.isPlaceholder() || gt.isWildcard()) {
			addToken(tokens, position.line, position.column, name.length(), TYPE_TYPE_PARAMETER, 0);
			return;
		}
		if (!isPrimitiveType(name)) {
			int tokenType = classNodeToTokenType(gtType);
			addToken(tokens, position.line, position.column, name.length(), tokenType, 0);
		}
	}

	private void emitGenericUpperBounds(ClassNode[] upperBounds, List<SemanticToken> tokens) {
		if (upperBounds == null) {
			return;
		}
		for (ClassNode bound : upperBounds) {
			if (bound.getLineNumber() > 0) {
				emitBoundTypeToken(bound, tokens);
			}
		}
	}

	private void emitBoundTypeToken(ClassNode bound, List<SemanticToken> tokens) {
		String boundName = bound.getNameWithoutPackage();
		if (!isPrimitiveType(boundName)) {
			int boundTokenType = classNodeToTokenType(bound);
			addToken(tokens, bound.getLineNumber(), bound.getColumnNumber(),
					boundName.length(), boundTokenType, 0);
		}
	}

	private static final class GenericTokenPosition {
		private final int line;
		private final int column;

		private GenericTokenPosition(int line, int column) {
			this.line = line;
			this.column = column;
		}

		private boolean isKnown() {
			return line != -1;
		}
	}

	// ── Source line scanning ─────────────────────────────────────────────────

	private int findIdentifierColumnBefore(int groovyLine, int beforeColumn, String identifier) {
		if (sourceLines == null || groovyLine <= 0 || groovyLine > sourceLines.length
				|| identifier == null || identifier.isEmpty()) {
			return -1;
		}
		String line = sourceLines[groovyLine - 1];
		int searchEndExclusive = beforeColumn > 0 ? Math.min(beforeColumn - 1, line.length()) : line.length();
		if (searchEndExclusive <= 0) {
			return -1;
		}
		int idx = line.lastIndexOf(identifier, searchEndExclusive - 1);
		while (idx >= 0) {
			boolean startOk = idx == 0 || !Character.isJavaIdentifierPart(line.charAt(idx - 1));
			int endIndex = idx + identifier.length();
			boolean endOk = endIndex >= line.length() || !Character.isJavaIdentifierPart(line.charAt(endIndex));
			if (startOk && endOk) {
				return idx + 1;
			}
			idx = line.lastIndexOf(identifier, idx - 1);
		}
		return -1;
	}

	private IdentifierOccurrence findPreviousIdentifierBefore(int groovyLine, int beforeColumn) {
		if (sourceLines == null || groovyLine <= 0 || groovyLine > sourceLines.length) {
			return null;
		}
		String line = sourceLines[groovyLine - 1];
		int idx = beforeColumn > 0 ? Math.min(beforeColumn - 2, line.length() - 1) : line.length() - 1;
		while (idx >= 0 && Character.isWhitespace(line.charAt(idx))) {
			idx--;
		}
		if (idx < 0 || !Character.isJavaIdentifierPart(line.charAt(idx))) {
			return null;
		}
		int end = idx;
		while (idx >= 0 && Character.isJavaIdentifierPart(line.charAt(idx))) {
			idx--;
		}
		int start = idx + 1;
		if (start > end) {
			return null;
		}
		return new IdentifierOccurrence(line.substring(start, end + 1), start + 1);
	}

	private static final class IdentifierOccurrence {
		private final String identifier;
		private final int column;

		private IdentifierOccurrence(String identifier, int column) {
			this.identifier = identifier;
			this.column = column;
		}
	}

	// ── Class generics ───────────────────────────────────────────────────────

	/**
	 * Emits {@code typeParameter} tokens for generic type parameters in a class
	 * declaration by scanning the source line. This is used as the primary strategy
	 * because Groovy's AST frequently does not set position information on
	 * {@code GenericsType} nodes for class declarations.
	 *
	 * <p>For {@code class Foo<T, E>}, finds the {@code <...>} portion after the
	 * class name and emits typeParameter tokens for each identifier found inside.</p>
	 */
	void addClassGenericTypeParameterTokens(ClassNode node, List<SemanticToken> tokens) {
		GenericsType[] genericsTypes = node.getGenericsTypes();
		if (genericsTypes == null || genericsTypes.length == 0) {
			return;
		}
		if (hasRealGenericsPositionInfo(genericsTypes)) {
			addGenericTypeParameterTokens(genericsTypes, tokens);
			return;
		}

		ClassGenericsSource genericsSource = locateClassGenericsSource(node);
		if (genericsSource == null) {
			return;
		}
		emitClassGenericIdentifiers(genericsSource, genericsTypes, tokens);
	}

	private boolean hasRealGenericsPositionInfo(GenericsType[] genericsTypes) {
		for (GenericsType gt : genericsTypes) {
			if (gt.getLineNumber() > 0) {
				return true;
			}
		}
		return false;
	}

	private ClassGenericsSource locateClassGenericsSource(ClassNode node) {
		int line = node.getLineNumber();
		if (sourceLines == null || line <= 0 || line > sourceLines.length) {
			return null;
		}
		String sourceLine = sourceLines[line - 1];
		int nameIdx = sourceLine.indexOf(node.getNameWithoutPackage());
		if (nameIdx < 0) {
			return null;
		}
		int angleBracketStart = sourceLine.indexOf('<', nameIdx + node.getNameWithoutPackage().length());
		if (angleBracketStart < 0) {
			return null;
		}
		int angleBracketEnd = sourceLine.indexOf('>', angleBracketStart);
		if (angleBracketEnd < 0) {
			return null;
		}
		String content = sourceLine.substring(angleBracketStart + 1, angleBracketEnd);
		int contentOffset = angleBracketStart + 1;
		return new ClassGenericsSource(line, content, contentOffset);
	}

	private void emitClassGenericIdentifiers(ClassGenericsSource source,
											GenericsType[] genericsTypes,
											List<SemanticToken> tokens) {
		int pos = 0;
		while (pos < source.content.length()) {
			IdentifierSpan span = readIdentifierSpan(source.content, pos);
			if (span == null) {
				pos++;
			} else {
				if (!isGenericsKeyword(span.identifier)) {
					int tokenType = isClassTypeParameter(span.identifier, genericsTypes)
							? TYPE_TYPE_PARAMETER : TYPE_CLASS;
					addToken(tokens, source.line, source.contentOffset + span.start + 1,
							span.identifier.length(), tokenType, 0);
				}
				pos = span.end;
			}
		}
	}

	private IdentifierSpan readIdentifierSpan(String content, int startAt) {
		char c = content.charAt(startAt);
		if (!Character.isJavaIdentifierStart(c)) {
			return null;
		}
		int end = startAt;
		while (end < content.length() && Character.isJavaIdentifierPart(content.charAt(end))) {
			end++;
		}
		return new IdentifierSpan(content.substring(startAt, end), startAt, end);
	}

	private boolean isGenericsKeyword(String identifier) {
		return "extends".equals(identifier) || "super".equals(identifier);
	}

	private boolean isClassTypeParameter(String identifier, GenericsType[] genericsTypes) {
		for (GenericsType gt : genericsTypes) {
			if (gt.getType() != null
					&& identifier.equals(gt.getType().getNameWithoutPackage())
					&& (gt.isPlaceholder() || gt.isWildcard())) {
				return true;
			}
		}
		return false;
	}

	private static final class ClassGenericsSource {
		private final int line;
		private final String content;
		private final int contentOffset;

		private ClassGenericsSource(int line, String content, int contentOffset) {
			this.line = line;
			this.content = content;
			this.contentOffset = contentOffset;
		}
	}

	private static final class IdentifierSpan {
		private final String identifier;
		private final int start;
		private final int end;

		private IdentifierSpan(String identifier, int start, int end) {
			this.identifier = identifier;
			this.start = start;
			this.end = end;
		}
	}

	// ── Namespace tokens (imports / package declarations) ────────────────────

	/**
	 * Emits {@code namespace} tokens for the package segments in an import statement.
	 * For example, in {@code import com.example.Foo}, this highlights {@code com.example}
	 * as namespace. Each dot-separated segment is emitted as its own token.
	 */
	void addImportPackageNamespaceTokens(ImportNode importNode, ClassNode type,
			List<SemanticToken> tokens) {
		if (sourceLines == null) {
			return;
		}
		String packageName = type.getPackageName();
		if (packageName == null || packageName.isEmpty()) {
			return;
		}

		// Find the start of the fully qualified name in the import line
		// The FQN starts after 'import' (and optional 'static') keyword
		int importLine = importNode.getLineNumber();
		if (importLine <= 0 || importLine > sourceLines.length) {
			return;
		}
		String sourceLine = sourceLines[importLine - 1];

		int fqnStart = findImportFqnStart(sourceLine);
		if (fqnStart < 0) {
			return;
		}
		emitNamespaceSegments(tokens, importLine, sourceLine, packageName, fqnStart);
	}

	private int findImportFqnStart(String sourceLine) {
		int importIdx = sourceLine.indexOf("import");
		if (importIdx < 0) {
			return -1;
		}
		int fqnStart = skipWhitespace(sourceLine, importIdx + "import".length());
		if (sourceLine.startsWith(STATIC_KEYWORD, fqnStart)) {
			fqnStart = skipWhitespace(sourceLine, fqnStart + STATIC_KEYWORD.length());
		}
		return fqnStart;
	}

	private int skipWhitespace(String sourceLine, int from) {
		int index = from;
		while (index < sourceLine.length() && Character.isWhitespace(sourceLine.charAt(index))) {
			index++;
		}
		return index;
	}

	private void emitNamespaceSegments(List<SemanticToken> tokens,
									 int line,
									 String sourceLine,
									 String packageName,
									 int startColumn) {
		String[] segments = packageName.split("\\.");
		int currentCol = startColumn;
		for (String segment : segments) {
			if (!segment.isEmpty()) {
				int segIdx = sourceLine.indexOf(segment, currentCol);
				if (segIdx < 0) {
					return;
				}
				addToken(tokens, line, segIdx + 1, segment.length(), TYPE_NAMESPACE, 0);
				currentCol = segIdx + segment.length() + 1;
			}
		}
	}

	/**
	 * Emits {@code namespace} tokens for the package declaration segments.
	 * For example, in {@code package com.example}, this highlights
	 * {@code com} and {@code example} as namespace.
	 */
	void addPackageDeclarationNamespaceTokens(List<SemanticToken> tokens, int fromLine, int toLine) {
		if (sourceLines == null || sourceLines.length == 0) {
			return;
		}

		int startLine = Math.max(1, fromLine);
		int endLine = Math.min(sourceLines.length, toLine);
		for (int line = startLine; line <= endLine; line++) {
			String sourceLine = sourceLines[line - 1];
			if (sourceLine != null && !sourceLine.isEmpty()) {
				addPackageDeclarationNamespaceTokensForLine(tokens, line, sourceLine);
			}
		}
	}

	private void addPackageDeclarationNamespaceTokensForLine(List<SemanticToken> tokens,
										 int line,
										 String sourceLine) {
		int keywordStart = skipWhitespace(sourceLine, 0);
		if (!startsWithPackageKeyword(sourceLine, keywordStart)) {
			return;
		}
		int nameStart = findPackageNameStart(sourceLine, keywordStart);
		if (nameStart < 0) {
			return;
		}
		int nameEnd = findPackageNameEnd(sourceLine, nameStart);
		if (nameEnd <= nameStart) {
			return;
		}
		String packageName = sourceLine.substring(nameStart, nameEnd);
		emitNamespaceSegments(tokens, line, sourceLine, packageName, nameStart);
	}

	private boolean startsWithPackageKeyword(String sourceLine, int index) {
		if (!sourceLine.startsWith(PACKAGE_KEYWORD, index)) {
			return false;
		}
		int afterKeyword = index + PACKAGE_KEYWORD.length();
		return afterKeyword < sourceLine.length() && Character.isWhitespace(sourceLine.charAt(afterKeyword));
	}

	private int findPackageNameStart(String sourceLine, int packageKeywordStart) {
		int afterKeyword = packageKeywordStart + PACKAGE_KEYWORD.length();
		int nameStart = skipWhitespace(sourceLine, afterKeyword);
		return nameStart < sourceLine.length() ? nameStart : -1;
	}

	private int findPackageNameEnd(String sourceLine, int nameStart) {
		int nameEnd = nameStart;
		while (nameEnd < sourceLine.length()) {
			char c = sourceLine.charAt(nameEnd);
			if (Character.isJavaIdentifierPart(c) || c == '.') {
				nameEnd++;
			} else {
				break;
			}
		}
		return nameEnd;
	}

	// ── Enclosing method parameter check ─────────────────────────────────────

	/**
	 * Walks up the AST parent chain to find the enclosing MethodNode and checks
	 * if it has a parameter with the given name. This handles cases where
	 * Spock (or other AST transforms) don't properly link VariableExpressions
	 * to their Parameter declarations (e.g. in where-block data tables).
	 */
	boolean isEnclosingMethodParameter(ASTNode node, String name) {
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
}
