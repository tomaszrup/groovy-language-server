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
package com.tomaszrup.groovyls;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;

import com.tomaszrup.groovyls.compiler.DiagnosticHandler;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.GroovyVersionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects Groovy 5-only syntax used in projects that resolve an older
 * Groovy version (e.g. 4.x) and produces synthetic diagnostics so the
 * editor shows actionable errors before the compiler itself fails.
 * <p>
 * Extracted from {@link CompilationService} for single-responsibility.
 */
class GroovyVersionSyntaxGuard {
	private static final Logger logger = LoggerFactory.getLogger(GroovyVersionSyntaxGuard.class);
	private static final String VERSION_GUARD_DIAGNOSTIC_SOURCE = "groovy-language-server";
	private static final String INSTANCEOF_KEYWORD = "instanceof";
	private static final String ARROW_TOKEN = "->";

	private static final List<GuardedSyntaxFeature> GROOVY5_SYNTAX_FEATURES = List.of(
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findImplicationOperatorMatches,
					"implication operator (==>)"),
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findInstanceofPatternVariableMatches,
					"instanceof pattern variable"),
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findVarMultiAssignmentMatches,
					"var with multi-assignment"),
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findForLoopIndexVariableMatches,
					"for-loop index variable declaration"),
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findUnderscoreLambdaMatches,
					"underscore placeholder parameters in lambdas"),
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findUnderscoreClosureMatches,
					"underscore placeholder parameters in closures"),
			new GuardedSyntaxFeature(GroovyVersionSyntaxGuard::findMultidimensionalArrayLiteralMatches,
					"Java-style multidimensional array literals"));

	private final FileContentsTracker fileContentsTracker;

	GroovyVersionSyntaxGuard(FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
	}

	/**
	 * Merge Groovy-version-aware syntax diagnostics into base compilation
	 * diagnostics.
	 */
	DiagnosticHandler.DiagnosticResult mergeGroovyVersionSyntaxDiagnostics(
			ProjectScope scope,
			GroovyLSCompilationUnit compilationUnit,
			DiagnosticHandler.DiagnosticResult baseResult) {
		if (scope == null || baseResult == null) {
			return baseResult;
		}

		String detectedVersion = scope.getDetectedGroovyVersion();
		Integer projectMajor = GroovyVersionDetector.major(detectedVersion).orElse(null);
		if (!isVersionGuardActive(scope, detectedVersion, projectMajor)) {
			return baseResult;
		}

		Map<URI, List<Diagnostic>> mergedDiagnosticsByFile = copyDiagnosticsByFile(baseResult.getDiagnosticsByFile());
		appendDiagnostics(mergedDiagnosticsByFile,
				collectGroovy5SyntaxDiagnostics(compilationUnit, projectMajor, detectedVersion));
		deduplicateDiagnostics(mergedDiagnosticsByFile);
		Set<PublishDiagnosticsParams> diagnosticsToPublish = buildDiagnosticsToPublish(scope, mergedDiagnosticsByFile);
		return new DiagnosticHandler.DiagnosticResult(diagnosticsToPublish, mergedDiagnosticsByFile);
	}

	private boolean isVersionGuardActive(ProjectScope scope, String detectedVersion, Integer projectMajor) {
		if (projectMajor == null || projectMajor >= 5) {
			if (logger.isDebugEnabled()) {
				logger.debug("Groovy 5 syntax guard inactive for scope {} (detectedVersion={}, major={})",
						scope.getProjectRoot(), detectedVersion, projectMajor);
			}
			return false;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("Groovy 5 syntax guard active for scope {} (detectedVersion={}, major={}, guardedFeatures={})",
					scope.getProjectRoot(), detectedVersion, projectMajor, GROOVY5_SYNTAX_FEATURES.size());
		}
		return true;
	}

	private Map<URI, List<Diagnostic>> copyDiagnosticsByFile(Map<URI, List<Diagnostic>> diagnosticsByFile) {
		Map<URI, List<Diagnostic>> copy = new HashMap<>();
		if (diagnosticsByFile != null) {
			for (Map.Entry<URI, List<Diagnostic>> entry : diagnosticsByFile.entrySet()) {
				copy.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
			}
		}
		return copy;
	}

	private void appendDiagnostics(Map<URI, List<Diagnostic>> target, Map<URI, List<Diagnostic>> source) {
		for (Map.Entry<URI, List<Diagnostic>> entry : source.entrySet()) {
			target.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>()).addAll(entry.getValue());
		}
	}

	private Set<PublishDiagnosticsParams> buildDiagnosticsToPublish(ProjectScope scope,
			Map<URI, List<Diagnostic>> mergedDiagnosticsByFile) {
		Set<PublishDiagnosticsParams> diagnosticsToPublish = new HashSet<>();
		for (Map.Entry<URI, List<Diagnostic>> entry : mergedDiagnosticsByFile.entrySet()) {
			diagnosticsToPublish.add(new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()));
		}
		Map<URI, List<Diagnostic>> previousDiagnosticsByFile = scope.getPrevDiagnosticsByFile();
		if (previousDiagnosticsByFile != null) {
			for (URI uri : previousDiagnosticsByFile.keySet()) {
				if (!mergedDiagnosticsByFile.containsKey(uri)) {
					diagnosticsToPublish.add(new PublishDiagnosticsParams(uri.toString(), new java.util.ArrayList<>()));
				}
			}
		}
		return diagnosticsToPublish;
	}

	private Map<URI, List<Diagnostic>> collectGroovy5SyntaxDiagnostics(
			GroovyLSCompilationUnit compilationUnit,
			int projectMajor,
			String projectVersion) {
		Map<URI, List<Diagnostic>> diagnosticsByFile = new HashMap<>();
		if (compilationUnit == null || compilationUnit.getAST() == null
				|| compilationUnit.getAST().getModules() == null) {
			return diagnosticsByFile;
		}

		for (org.codehaus.groovy.ast.ModuleNode module : compilationUnit.getAST().getModules()) {
			ModuleDiagnostics moduleDiagnostics = collectModuleGroovy5Diagnostics(module, projectMajor, projectVersion);
			if (moduleDiagnostics != null) {
				diagnosticsByFile.put(moduleDiagnostics.uri, moduleDiagnostics.diagnostics);
			}
		}

		return diagnosticsByFile;
	}

	private ModuleDiagnostics collectModuleGroovy5Diagnostics(org.codehaus.groovy.ast.ModuleNode module,
			int projectMajor, String projectVersion) {
		if (module == null || module.getContext() == null) {
			return null;
		}
		org.codehaus.groovy.control.SourceUnit sourceUnit = module.getContext();
		URI uri = GroovyLanguageServerUtils.sourceLocatorToUri(sourceUnit.getName());
		if (uri == null) {
			return null;
		}
		String source = resolveSourceForUri(uri);
		if (source == null || source.isEmpty()) {
			return null;
		}
		List<Diagnostic> fileDiagnostics = createGroovy5DiagnosticsForSource(source, projectMajor, projectVersion);
		return fileDiagnostics.isEmpty() ? null : new ModuleDiagnostics(uri, fileDiagnostics);
	}

	private String resolveSourceForUri(URI uri) {
		String source = fileContentsTracker.getContents(uri);
		if (source != null || !"file".equalsIgnoreCase(uri.getScheme())) {
			return source;
		}
		try {
			return Files.readString(Paths.get(uri));
		} catch (Exception ignored) {
			return null;
		}
	}

	private List<Diagnostic> createGroovy5DiagnosticsForSource(String source, int projectMajor, String projectVersion) {
		List<Diagnostic> fileDiagnostics = new java.util.ArrayList<>();
		for (GuardedSyntaxFeature feature : GROOVY5_SYNTAX_FEATURES) {
			fileDiagnostics.addAll(createFeatureDiagnostics(
					source,
					feature.detector,
					feature.featureName,
					projectMajor,
					projectVersion));
		}
		return fileDiagnostics;
	}

	// --- Syntax pattern detectors ---

	static List<MatchRange> findImplicationOperatorMatches(String source) {
		return findLiteralMatches(source, "==>");
	}

	static List<MatchRange> findInstanceofPatternVariableMatches(String source) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		int cursor = 0;
		while (cursor < source.length()) {
			int lineEnd = source.indexOf('\n', cursor);
			if (lineEnd < 0) {
				lineEnd = source.length();
			}
			String line = source.substring(cursor, lineEnd);
			int keyword = indexOfWord(line, INSTANCEOF_KEYWORD, 0);
			while (keyword >= 0) {
				int afterKeyword = keyword + INSTANCEOF_KEYWORD.length();
				int varStart = findPatternVariableStart(line, afterKeyword);
				if (varStart >= 0) {
					int varEnd = consumeIdentifier(line, varStart);
					matches.add(new MatchRange(cursor + keyword, cursor + varEnd));
				}
				keyword = indexOfWord(line, INSTANCEOF_KEYWORD, afterKeyword);
			}
			cursor = lineEnd + 1;
		}
		return matches;
	}

	private static int findPatternVariableStart(String line, int from) {
		int i = skipWhitespace(line, from);
		if (i >= line.length()) {
			return -1;
		}
		while (i < line.length() && line.charAt(i) != ';' && line.charAt(i) != ')') {
			if (Character.isJavaIdentifierStart(line.charAt(i))) {
				int idEnd = consumeIdentifier(line, i);
				int probe = skipWhitespace(line, idEnd);
				if (probe < line.length() && Character.isJavaIdentifierStart(line.charAt(probe))) {
					return probe;
				}
				i = idEnd;
				continue;
			}
			i++;
		}
		return -1;
	}

	static List<MatchRange> findVarMultiAssignmentMatches(String source) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		int from = 0;
		while (from < source.length()) {
			int idx = indexOfWord(source, "var", from);
			if (idx < 0) {
				break;
			}
			int openParen = skipWhitespace(source, idx + 3);
			if (openParen < source.length() && source.charAt(openParen) == '(') {
				int closeParen = findClosingParen(source, openParen);
				if (closeParen > openParen) {
					String inside = source.substring(openParen + 1, closeParen);
					if (inside.indexOf(',') >= 0) {
						matches.add(new MatchRange(idx, closeParen + 1));
					}
				}
			}
			from = idx + 3;
		}
		return matches;
	}

	static List<MatchRange> findForLoopIndexVariableMatches(String source) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		int from = 0;
		while (from < source.length()) {
			int idx = indexOfWord(source, "for", from);
			if (idx < 0) {
				break;
			}
			int openParen = skipWhitespace(source, idx + 3);
			if (openParen < source.length() && source.charAt(openParen) == '(') {
				int closeParen = findClosingParen(source, openParen);
				if (closeParen > openParen) {
					String inside = source.substring(openParen + 1, closeParen);
					if (inside.indexOf(',') >= 0 && containsWord(inside, "in")) {
						matches.add(new MatchRange(idx, closeParen + 1));
					}
				}
			}
			from = idx + 3;
		}
		return matches;
	}

	static List<MatchRange> findUnderscoreLambdaMatches(String source) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		for (MatchRange arrow : findLiteralMatches(source, ARROW_TOKEN)) {
			int beforeArrow = skipWhitespaceBackward(source, arrow.start - 1);
			if (beforeArrow >= 0 && source.charAt(beforeArrow) == ')') {
				int openParen = findMatchingOpenParen(source, beforeArrow);
				if (openParen >= 0) {
					String params = source.substring(openParen + 1, beforeArrow);
					if (containsWord(params, "_")) {
						matches.add(new MatchRange(openParen, arrow.end));
					}
				}
			}
		}
		return matches;
	}

	static List<MatchRange> findUnderscoreClosureMatches(String source) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		for (MatchRange arrow : findLiteralMatches(source, ARROW_TOKEN)) {
			int lineStart = source.lastIndexOf('\n', Math.max(0, arrow.start - 1));
			lineStart = lineStart < 0 ? 0 : lineStart + 1;
			int brace = source.lastIndexOf('{', arrow.start);
			boolean braceInCurrentLine = brace >= lineStart;
			boolean braceNotClosedBeforeArrow = source.indexOf('}', Math.max(0, brace)) < 0
					|| source.indexOf('}', Math.max(0, brace)) >= arrow.start;
			if (braceInCurrentLine && braceNotClosedBeforeArrow) {
				String closureArgs = source.substring(brace + 1, arrow.start);
				if (containsWord(closureArgs, "_")) {
					matches.add(new MatchRange(brace, arrow.end));
				}
			}
		}
		return matches;
	}

	static List<MatchRange> findMultidimensionalArrayLiteralMatches(String source) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		int from = 0;
		while (from < source.length()) {
			int idx = indexOfWord(source, "new", from);
			if (idx < 0) {
				break;
			}
			int i = skipWhitespace(source, idx + 3);
			while (i < source.length() && isTypeNamePart(source.charAt(i))) {
				i++;
			}
			int probe = skipWhitespace(source, i);
			if (probe + 5 < source.length()
					&& source.startsWith("[]", probe)
					&& source.startsWith("[]", skipWhitespace(source, probe + 2))) {
				int afterSecond = skipWhitespace(source, skipWhitespace(source, probe + 2) + 2);
				if (afterSecond + 1 < source.length() && source.startsWith("{{", afterSecond)) {
					matches.add(new MatchRange(idx, afterSecond + 2));
				}
			}
			from = idx + 3;
		}
		return matches;
	}

	// --- Text scanning utilities ---

	private static boolean isTypeNamePart(char ch) {
		return Character.isJavaIdentifierPart(ch) || ch == '.' || ch == '$';
	}

	static List<MatchRange> findLiteralMatches(String source, String literal) {
		List<MatchRange> matches = new java.util.ArrayList<>();
		int from = 0;
		while (true) {
			int at = source.indexOf(literal, from);
			if (at < 0) {
				break;
			}
			matches.add(new MatchRange(at, at + literal.length()));
			from = at + literal.length();
		}
		return matches;
	}

	private static int findClosingParen(String source, int openParen) {
		int depth = 0;
		for (int i = openParen; i < source.length(); i++) {
			char ch = source.charAt(i);
			if (ch == '(') {
				depth++;
			} else if (ch == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static int findMatchingOpenParen(String source, int closeParen) {
		int depth = 0;
		for (int i = closeParen; i >= 0; i--) {
			char ch = source.charAt(i);
			if (ch == ')') {
				depth++;
			} else if (ch == '(') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	static int indexOfWord(String text, String word, int from) {
		int idx = text.indexOf(word, from);
		while (idx >= 0) {
			int before = idx - 1;
			int after = idx + word.length();
			boolean leftBoundary = before < 0 || !Character.isJavaIdentifierPart(text.charAt(before));
			boolean rightBoundary = after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
			if (leftBoundary && rightBoundary) {
				return idx;
			}
			idx = text.indexOf(word, idx + word.length());
		}
		return -1;
	}

	static boolean containsWord(String text, String word) {
		return indexOfWord(text, word, 0) >= 0;
	}

	private static int skipWhitespace(String text, int from) {
		int i = Math.max(0, from);
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}

	private static int skipWhitespaceBackward(String text, int from) {
		int i = Math.min(from, text.length() - 1);
		while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
			i--;
		}
		return i;
	}

	private static int consumeIdentifier(String text, int from) {
		int i = from;
		while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
			i++;
		}
		return i;
	}

	// --- Diagnostics creation helpers ---

	private List<Diagnostic> createFeatureDiagnostics(
			String source,
			SyntaxDetector detector,
			String featureName,
			int projectMajor,
			String projectVersion) {
		List<Diagnostic> diagnostics = new java.util.ArrayList<>();
		int[] lineStartOffsets = computeLineStartOffsets(source);
		for (MatchRange match : detector.findMatches(source)) {
			int startOffset = match.start;
			int endOffset = match.end;
			Range range = new Range(
					offsetToPosition(startOffset, source, lineStartOffsets),
					offsetToPosition(endOffset, source, lineStartOffsets));

			Diagnostic diagnostic = new Diagnostic();
			diagnostic.setRange(range);
			diagnostic.setSeverity(DiagnosticSeverity.Error);
			diagnostic.setSource(VERSION_GUARD_DIAGNOSTIC_SOURCE);
			diagnostic.setMessage(String.format(
					"%s require Groovy 5+, but this project resolves Groovy %s (major %d).",
					featureName,
					projectVersion != null ? projectVersion : Integer.toString(projectMajor),
					projectMajor));
			diagnostics.add(diagnostic);
		}
		return diagnostics;
	}

	private int[] computeLineStartOffsets(String source) {
		java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
		starts.add(0);
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		int[] result = new int[starts.size()];
		for (int i = 0; i < starts.size(); i++) {
			result[i] = starts.get(i);
		}
		return result;
	}

	private Position offsetToPosition(int offset, String source, int[] lineStartOffsets) {
		int safeOffset = Math.max(0, Math.min(offset, source.length()));
		int idx = java.util.Arrays.binarySearch(lineStartOffsets, safeOffset);
		if (idx < 0) {
			idx = -idx - 2;
		}
		if (idx < 0) {
			idx = 0;
		}
		int lineStart = lineStartOffsets[idx];
		int character = Math.max(0, safeOffset - lineStart);
		return new Position(idx, character);
	}

	private void deduplicateDiagnostics(Map<URI, List<Diagnostic>> diagnosticsByFile) {
		for (Map.Entry<URI, List<Diagnostic>> entry : diagnosticsByFile.entrySet()) {
			List<Diagnostic> unique = new java.util.ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (Diagnostic diagnostic : entry.getValue()) {
				String key = diagnostic.getRange() + "|" + diagnostic.getMessage() + "|" + diagnostic.getSeverity();
				if (seen.add(key)) {
					unique.add(diagnostic);
				}
			}
			entry.setValue(unique);
		}
	}

	// --- Inner types ---

	@FunctionalInterface
	interface SyntaxDetector {
		List<MatchRange> findMatches(String source);
	}

	static final class MatchRange {
		final int start;
		final int end;

		MatchRange(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	private static final class ModuleDiagnostics {
		private final URI uri;
		private final List<Diagnostic> diagnostics;

		private ModuleDiagnostics(URI uri, List<Diagnostic> diagnostics) {
			this.uri = uri;
			this.diagnostics = diagnostics;
		}
	}

	static final class GuardedSyntaxFeature {
		final SyntaxDetector detector;
		final String featureName;

		GuardedSyntaxFeature(SyntaxDetector detector, String featureName) {
			this.detector = detector;
			this.featureName = featureName;
		}
	}
}
