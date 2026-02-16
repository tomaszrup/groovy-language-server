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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

/**
 * Provides textDocument/formatting support for Groovy source files.
 *
 * <p>Uses a character-level state-machine lexer to classify every character
 * into one of these states: CODE, SINGLE_QUOTED, DOUBLE_QUOTED,
 * TRIPLE_SINGLE_QUOTED, TRIPLE_DOUBLE_QUOTED, GSTRING_EXPR, LINE_COMMENT,
 * BLOCK_COMMENT, SLASH_STRING, DOLLAR_SLASH_STRING.
 *
 * <p>This approach avoids regex-based pattern matching on raw lines,
 * which is fragile with closures, multi-line strings, and DSLs.
 *
 * <p>Formatting rules applied:
 * <ul>
 *   <li>Re-indent code based on brace nesting depth</li>
 *   <li>Remove trailing whitespace from each line</li>
 *   <li>Ensure the file ends with a single newline</li>
 *   <li>Normalize blank lines (collapse 3+ consecutive blank lines to 2)</li>
 *   <li>Ensure consistent spacing after commas (in code only)</li>
 *   <li>Ensure consistent spacing after control-flow keywords (in code only)</li>
 *   <li>Ensure consistent spacing around opening braces (in code only)</li>
 * </ul>
 */
public class FormattingProvider {

	/**
	 * Lexer state for the character-level scanner.
	 */
	private enum LexState {
		CODE,
		SINGLE_QUOTED,          // 'text'
		DOUBLE_QUOTED,          // "text"
		TRIPLE_SINGLE_QUOTED,   // '''text'''
		TRIPLE_DOUBLE_QUOTED,   // """text"""
		GSTRING_EXPR,           // ${expr} inside double-quoted or triple-double-quoted strings
		LINE_COMMENT,           // // ...
		BLOCK_COMMENT,          // /* ... */
		SLASH_STRING,           // /regex/
		DOLLAR_SLASH_STRING     // $/regex/$
	}

	private static final class LineFormattingResult {
		private final String line;
		private final int consecutiveBlankLines;

		private LineFormattingResult(String line, int consecutiveBlankLines) {
			this.line = line;
			this.consecutiveBlankLines = consecutiveBlankLines;
		}
	}

	private static final class LexStep {
		private final LexState state;
		private final int nextIndex;

		private LexStep(LexState state, int nextIndex) {
			this.state = state;
			this.nextIndex = nextIndex;
		}
	}

	private static final class LineContext {
		private int lineStart;
		private LexState[] charStates;
		private String singleIndent;
		private int braceDepth;
		private int groupingDepth;
		private int consecutiveBlankLines;
		private boolean lineInString;
		private boolean lineInBlockComment;
	}

	public CompletableFuture<List<TextEdit>> provideFormatting(
			DocumentFormattingParams params, String sourceText) {
		if (sourceText == null || sourceText.isEmpty()) {
			return CompletableFuture.completedFuture(new ArrayList<>());
		}

		FormattingOptions options = params.getOptions();
		int tabSize = options.getTabSize();
		boolean insertSpaces = options.isInsertSpaces();

		String formatted = format(sourceText, tabSize, insertSpaces);

		// Normalize the original text the same way as format() does for
		// comparison, so that \r\n vs \n differences don't produce spurious edits.
		String normalizedSource = sourceText.replace("\r\n", "\n").replace("\r", "\n");

		if (formatted.equals(normalizedSource)) {
			return CompletableFuture.completedFuture(new ArrayList<>());
		}

		List<TextEdit> edits = computeMinimalEdits(normalizedSource, formatted);
		return CompletableFuture.completedFuture(edits);
	}

	/**
	 * Compute minimal line-level TextEdits between the original and formatted text.
	 */
	public static List<TextEdit> computeMinimalEdits(String original, String formatted) {
		String[] origLines = original.split("\\n", -1);
		String[] fmtLines = formatted.split("\\n", -1);
		List<TextEdit> edits = new ArrayList<>();

		int origLen = origLines.length;
		int fmtLen = fmtLines.length;
		int top = findFirstDifferentLine(origLines, fmtLines, origLen, fmtLen);

		if (top == origLen && top == fmtLen) {
			return edits;
		}

		int[] bottoms = findLastDifferentLine(origLines, fmtLines, top, origLen, fmtLen);
		int origBottom = bottoms[0];
		int fmtBottom = bottoms[1];

		String replacement = buildReplacementText(fmtLines, top, fmtBottom);
		TextEdit edit = createMinimalEdit(origLines, top, origBottom, fmtBottom, replacement);
		edits.add(edit);
		return edits;
	}

	private static int findFirstDifferentLine(String[] origLines, String[] fmtLines, int origLen, int fmtLen) {
		int top = 0;
		int minLen = Math.min(origLen, fmtLen);
		while (top < minLen && origLines[top].equals(fmtLines[top])) {
			top++;
		}
		return top;
	}

	private static int[] findLastDifferentLine(String[] origLines, String[] fmtLines, int top, int origLen, int fmtLen) {
		int origBottom = origLen - 1;
		int fmtBottom = fmtLen - 1;
		while (origBottom >= top && fmtBottom >= top && origLines[origBottom].equals(fmtLines[fmtBottom])) {
			origBottom--;
			fmtBottom--;
		}
		return new int[] {origBottom, fmtBottom};
	}

	private static String buildReplacementText(String[] fmtLines, int top, int fmtBottom) {
		StringBuilder replacement = new StringBuilder();
		for (int j = top; j <= fmtBottom; j++) {
			if (j > top) {
				replacement.append("\n");
			}
			replacement.append(fmtLines[j]);
		}
		return replacement.toString();
	}

	private static TextEdit createMinimalEdit(String[] origLines, int top, int origBottom, int fmtBottom,
			String replacementText) {
		String adjustedReplacement = replacementText;
		Position start;
		Position end;

		if (top > origBottom) {
			if (top == 0) {
				start = new Position(0, 0);
				end = new Position(0, 0);
				if (fmtBottom >= top) {
					adjustedReplacement = adjustedReplacement + "\n";
				}
			} else {
				start = new Position(top - 1, origLines[top - 1].length());
				end = new Position(top - 1, origLines[top - 1].length());
				if (fmtBottom >= top) {
					adjustedReplacement = "\n" + adjustedReplacement;
				}
			}
		} else {
			start = new Position(top, 0);
			end = new Position(origBottom, origLines[origBottom].length());
		}

		return new TextEdit(new Range(start, end), adjustedReplacement);
	}

	/**
	 * Format the given Groovy source text using a state-machine lexer.
	 */
	String format(String sourceText, int tabSize, boolean insertSpaces) {
		String text = sourceText.replace("\r\n", "\n").replace("\r", "\n");

		// Phase 1: Lex the entire source into character-level state annotations
		LexState[] charStates = lexSource(text);

		// Phase 2: Build formatted output line by line
		String[] lines = text.split("\\n", -1);
		String singleIndent = insertSpaces ? " ".repeat(tabSize) : "\t";

		StringBuilder result = new StringBuilder();
		int consecutiveBlankLines = 0;
		int braceDepth = 0;
		int groupingDepth = 0;
		int charOffset = 0; // tracks position in the original text for state lookup

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int lineStart = charOffset;
			int lineEnd = lineStart + line.length();

			// Determine if this line is entirely inside a multi-line string or block comment
			boolean lineInMultiLineString = isEntirelyInState(charStates, lineStart, lineEnd,
					LexState.TRIPLE_SINGLE_QUOTED, LexState.TRIPLE_DOUBLE_QUOTED, LexState.DOLLAR_SLASH_STRING);
			boolean lineInBlockComment = isEntirelyInState(charStates, lineStart, lineEnd, LexState.BLOCK_COMMENT);
			boolean lineStartsInString = lineStart < charStates.length
					&& isStringState(charStates[lineStart]);

			LineContext lineContext = new LineContext();
			lineContext.lineStart = lineStart;
			lineContext.charStates = charStates;
			lineContext.singleIndent = singleIndent;
			lineContext.braceDepth = braceDepth;
			lineContext.groupingDepth = groupingDepth;
			lineContext.consecutiveBlankLines = consecutiveBlankLines;
			lineContext.lineInString = lineInMultiLineString || lineStartsInString;
			lineContext.lineInBlockComment = lineInBlockComment;
			LineFormattingResult lineResult = processLine(line, lineContext);
			consecutiveBlankLines = lineResult.consecutiveBlankLines;
			if (lineResult.line != null) {
				result.append(lineResult.line);
				if (i < lines.length - 1) {
					result.append("\n");
				}
			}

			if (!lineContext.lineInString && !lineContext.lineInBlockComment && !line.trim().isEmpty()) {
				int firstNonWhitespaceOffset = findFirstNonWhitespace(line, lineStart);
				String trimmedLine = fixSpacing(line.trim(), charStates, firstNonWhitespaceOffset);
				braceDepth = Math.max(0, braceDepth + countNetBraces(trimmedLine, charStates, firstNonWhitespaceOffset));
				groupingDepth = Math.max(0,
						groupingDepth + countNetGroupingDelimiters(trimmedLine, charStates, firstNonWhitespaceOffset));
			}

			charOffset = lineEnd + 1;
		}

		String formatted = result.toString();
		formatted = trimTrailingNewlines(formatted) + "\n";
		return formatted;
	}

	/**
	 * Lex the entire source into per-character state annotations.
	 * This is the core of the state-machine approach.
	 */
	private LexState[] lexSource(String text) {
		LexState[] states = new LexState[text.length()];
		LexState state = LexState.CODE;
		int gstringBraceDepth = 0;

		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			LexStep step = applyLexStep(text, states, state, i, gstringBraceDepth);
			state = step.state;
			if (state == LexState.GSTRING_EXPR && c == '{') {
				gstringBraceDepth++;
			} else if (c == '}' && gstringBraceDepth > 0 && states[i] == LexState.DOUBLE_QUOTED) {
				gstringBraceDepth = 0;
			} else if (state == LexState.GSTRING_EXPR && c == '}') {
				gstringBraceDepth--;
			}
			i = step.nextIndex;
		}
		return states;
	}

	private LineFormattingResult processLine(String line, LineContext context) {
		if (context.lineInString) {
			return new LineFormattingResult(line, 0);
		}

		String trimmedLine = line.trim();
		if (context.lineInBlockComment) {
			String indentedComment = trimmedLine.isEmpty()
					? ""
					: buildIndent(context.singleIndent, context.braceDepth) + " " + trimmedLine;
			return new LineFormattingResult(indentedComment, 0);
		}

		if (trimmedLine.isEmpty()) {
			int updatedBlankLines = context.consecutiveBlankLines + 1;
			if (updatedBlankLines > 2) {
				return new LineFormattingResult(null, updatedBlankLines);
			}
			return new LineFormattingResult("", updatedBlankLines);
		}

		int firstNonWhitespaceOffset = findFirstNonWhitespace(line, context.lineStart);
		String spacedLine = fixSpacing(trimmedLine, context.charStates, firstNonWhitespaceOffset);
		int lineDepth = computeLineDepth(spacedLine, context.charStates, firstNonWhitespaceOffset,
				context.braceDepth, context.groupingDepth);
		String formattedLine = buildIndent(context.singleIndent, lineDepth) + spacedLine;
		return new LineFormattingResult(formattedLine, 0);
	}

	private int computeLineDepth(String trimmedLine, LexState[] charStates, int firstNonWhitespaceOffset,
			int braceDepth, int groupingDepth) {
		int lineDepth = braceDepth + groupingDepth;
		int leadingClosers = countLeadingClosers(trimmedLine, charStates, firstNonWhitespaceOffset);
		lineDepth = Math.max(0, lineDepth - leadingClosers);
		if (isContinuationMemberAccessLine(trimmedLine)) {
			lineDepth++;
		}
		return lineDepth;
	}

	private LexStep applyLexStep(String text, LexState[] states, LexState state, int index, int gstringBraceDepth) {
		char c = text.charAt(index);
		char next = (index + 1 < text.length()) ? text.charAt(index + 1) : 0;
		char next2 = (index + 2 < text.length()) ? text.charAt(index + 2) : 0;
		switch (state) {
			case CODE:
				return applyCodeStateLexing(states, index, c, next, next2);
			case LINE_COMMENT:
				return handleLineComment(states, index, c);
			case BLOCK_COMMENT:
				return handleBlockComment(states, index, c, next);
			case SINGLE_QUOTED:
				return handleSingleQuoted(states, index, c);
			case DOUBLE_QUOTED:
				return applyDoubleQuotedLexing(states, index, c, next);
			case TRIPLE_SINGLE_QUOTED:
				return applyTripleSingleQuotedLexing(states, index, c, next, next2);
			case TRIPLE_DOUBLE_QUOTED:
				return applyTripleDoubleQuotedLexing(states, index, c, next, next2);
			case GSTRING_EXPR:
				return handleGStringExpression(states, index, c, gstringBraceDepth);
			case DOLLAR_SLASH_STRING:
				return applyDollarSlashLexing(states, index, c, next);
			case SLASH_STRING:
				return handleSlashString(states, index, c);
			default:
				states[index] = LexState.CODE;
				return new LexStep(LexState.CODE, index + 1);
		}
	}

	private LexStep handleLineComment(LexState[] states, int index, char c) {
		states[index] = LexState.LINE_COMMENT;
		return new LexStep(c == '\n' ? LexState.CODE : LexState.LINE_COMMENT, index + 1);
	}

	private LexStep handleBlockComment(LexState[] states, int index, char c, char next) {
		states[index] = LexState.BLOCK_COMMENT;
		if (c == '*' && next == '/') {
			markState(states, index + 1, LexState.BLOCK_COMMENT);
			return new LexStep(LexState.CODE, index + 2);
		}
		return new LexStep(LexState.BLOCK_COMMENT, index + 1);
	}

	private LexStep handleSingleQuoted(LexState[] states, int index, char c) {
		states[index] = LexState.SINGLE_QUOTED;
		if (c == '\\') {
			markState(states, index + 1, LexState.SINGLE_QUOTED);
			return new LexStep(LexState.SINGLE_QUOTED, index + 2);
		}
		return new LexStep(c == '\'' ? LexState.CODE : LexState.SINGLE_QUOTED, index + 1);
	}

	private LexStep handleGStringExpression(LexState[] states, int index, char c, int gstringBraceDepth) {
		states[index] = LexState.CODE;
		if (c == '}' && gstringBraceDepth == 1) {
			states[index] = LexState.DOUBLE_QUOTED;
			return new LexStep(findEnclosingStringState(states, index), index + 1);
		}
		return new LexStep(LexState.GSTRING_EXPR, index + 1);
	}

	private LexStep handleSlashString(LexState[] states, int index, char c) {
		states[index] = LexState.SLASH_STRING;
		if (c == '\\') {
			markState(states, index + 1, LexState.SLASH_STRING);
			return new LexStep(LexState.SLASH_STRING, index + 2);
		}
		return new LexStep(c == '/' ? LexState.CODE : LexState.SLASH_STRING, index + 1);
	}

	private LexStep applyCodeStateLexing(LexState[] states, int index, char c, char next, char next2) {
		if (c == '/' && next == '/') {
			states[index] = LexState.LINE_COMMENT;
			return new LexStep(LexState.LINE_COMMENT, index + 1);
		}
		if (c == '/' && next == '*') {
			states[index] = LexState.BLOCK_COMMENT;
			return new LexStep(LexState.BLOCK_COMMENT, index + 1);
		}
		if (c == '\'' && next == '\'' && next2 == '\'') {
			markState(states, index, LexState.TRIPLE_SINGLE_QUOTED);
			markState(states, index + 1, LexState.TRIPLE_SINGLE_QUOTED);
			markState(states, index + 2, LexState.TRIPLE_SINGLE_QUOTED);
			return new LexStep(LexState.TRIPLE_SINGLE_QUOTED, index + 3);
		}
		if (c == '"' && next == '"' && next2 == '"') {
			markState(states, index, LexState.TRIPLE_DOUBLE_QUOTED);
			markState(states, index + 1, LexState.TRIPLE_DOUBLE_QUOTED);
			markState(states, index + 2, LexState.TRIPLE_DOUBLE_QUOTED);
			return new LexStep(LexState.TRIPLE_DOUBLE_QUOTED, index + 3);
		}
		if (c == '\'') {
			states[index] = LexState.SINGLE_QUOTED;
			return new LexStep(LexState.SINGLE_QUOTED, index + 1);
		}
		if (c == '"') {
			states[index] = LexState.DOUBLE_QUOTED;
			return new LexStep(LexState.DOUBLE_QUOTED, index + 1);
		}
		if (c == '$' && next == '/') {
			states[index] = LexState.DOLLAR_SLASH_STRING;
			return new LexStep(LexState.DOLLAR_SLASH_STRING, index + 1);
		}
		states[index] = LexState.CODE;
		return new LexStep(LexState.CODE, index + 1);
	}

	private LexStep applyDoubleQuotedLexing(LexState[] states, int index, char c, char next) {
		states[index] = LexState.DOUBLE_QUOTED;
		if (c == '\\') {
			markState(states, index + 1, LexState.DOUBLE_QUOTED);
			return new LexStep(LexState.DOUBLE_QUOTED, index + 2);
		}
		if (c == '$' && next == '{') {
			markState(states, index + 1, LexState.DOUBLE_QUOTED);
			return new LexStep(LexState.GSTRING_EXPR, index + 2);
		}
		return new LexStep(c == '"' ? LexState.CODE : LexState.DOUBLE_QUOTED, index + 1);
	}

	private LexStep applyTripleSingleQuotedLexing(LexState[] states, int index, char c, char next, char next2) {
		states[index] = LexState.TRIPLE_SINGLE_QUOTED;
		if (c == '\\') {
			markState(states, index + 1, LexState.TRIPLE_SINGLE_QUOTED);
			return new LexStep(LexState.TRIPLE_SINGLE_QUOTED, index + 2);
		}
		if (c == '\'' && next == '\'' && next2 == '\'') {
			markState(states, index + 1, LexState.TRIPLE_SINGLE_QUOTED);
			markState(states, index + 2, LexState.TRIPLE_SINGLE_QUOTED);
			return new LexStep(LexState.CODE, index + 3);
		}
		return new LexStep(LexState.TRIPLE_SINGLE_QUOTED, index + 1);
	}

	private LexStep applyTripleDoubleQuotedLexing(LexState[] states, int index, char c, char next, char next2) {
		states[index] = LexState.TRIPLE_DOUBLE_QUOTED;
		if (c == '\\') {
			markState(states, index + 1, LexState.TRIPLE_DOUBLE_QUOTED);
			return new LexStep(LexState.TRIPLE_DOUBLE_QUOTED, index + 2);
		}
		if (c == '$' && next == '{') {
			markState(states, index + 1, LexState.TRIPLE_DOUBLE_QUOTED);
			return new LexStep(LexState.GSTRING_EXPR, index + 2);
		}
		if (c == '"' && next == '"' && next2 == '"') {
			markState(states, index + 1, LexState.TRIPLE_DOUBLE_QUOTED);
			markState(states, index + 2, LexState.TRIPLE_DOUBLE_QUOTED);
			return new LexStep(LexState.CODE, index + 3);
		}
		return new LexStep(LexState.TRIPLE_DOUBLE_QUOTED, index + 1);
	}

	private LexStep applyDollarSlashLexing(LexState[] states, int index, char c, char next) {
		states[index] = LexState.DOLLAR_SLASH_STRING;
		if ((c == '/' && next == '$') || (c == '$' && next == '/') || (c == '$' && next == '$')) {
			markState(states, index + 1, LexState.DOLLAR_SLASH_STRING);
			LexState nextState = (c == '/' && next == '$') ? LexState.CODE : LexState.DOLLAR_SLASH_STRING;
			return new LexStep(nextState, index + 2);
		}
		return new LexStep(LexState.DOLLAR_SLASH_STRING, index + 1);
	}

	private void markState(LexState[] states, int index, LexState state) {
		if (index >= 0 && index < states.length) {
			states[index] = state;
		}
	}

	/**
	 * Find the enclosing string state when exiting a GString expression.
	 * Looks backwards through states to find either DOUBLE_QUOTED or TRIPLE_DOUBLE_QUOTED.
	 */
	private LexState findEnclosingStringState(LexState[] states, int pos) {
		for (int i = pos - 1; i >= 0; i--) {
			if (states[i] == LexState.TRIPLE_DOUBLE_QUOTED) {
				return LexState.TRIPLE_DOUBLE_QUOTED;
			}
			if (states[i] == LexState.DOUBLE_QUOTED) {
				return LexState.DOUBLE_QUOTED;
			}
		}
		return LexState.DOUBLE_QUOTED;
	}

	/**
	 * Check if every character in the range [start, end) is in one of the given states.
	 */
	private boolean isEntirelyInState(LexState[] charStates, int start, int end, LexState... allowedStates) {
		if (start >= end) return false;
		for (int i = start; i < end && i < charStates.length; i++) {
			boolean match = false;
			for (LexState allowed : allowedStates) {
				if (charStates[i] == allowed) {
					match = true;
					break;
				}
			}
			if (!match) return false;
		}
		return true;
	}

	private boolean isStringState(LexState state) {
		return state == LexState.TRIPLE_SINGLE_QUOTED
				|| state == LexState.TRIPLE_DOUBLE_QUOTED
				|| state == LexState.DOLLAR_SLASH_STRING
				|| state == LexState.SLASH_STRING;
	}

	/**
	 * Apply spacing fixes (comma, keyword-paren, brace) only to CODE portions.
	 * Uses the lexer state to avoid modifying string literals or comments.
	 */
	private String fixSpacing(String trimmedLine, LexState[] charStates, int firstNonWhitespaceOffset) {
		// If the line starts with a comment indicator, skip formatting
		if (trimmedLine.startsWith("//") || trimmedLine.startsWith("/*") || trimmedLine.startsWith("*")) {
			return trimmedLine;
		}

		// Apply comma spacing (state-aware)
		trimmedLine = fixCommaSpacingStateful(trimmedLine, charStates, firstNonWhitespaceOffset);

		// Apply keyword-paren spacing (state-aware)
		trimmedLine = fixKeywordParenSpacingStateful(trimmedLine, charStates, firstNonWhitespaceOffset);

		// Apply brace spacing (state-aware)
		trimmedLine = fixBraceSpacingStateful(trimmedLine, charStates, firstNonWhitespaceOffset);

		return trimmedLine;
	}

	/**
	 * Ensure a space after commas, but only when the comma is in CODE state.
	 */
	private String fixCommaSpacingStateful(String line, LexState[] charStates, int firstNonWhitespaceOffset) {
		StringBuilder sb = new StringBuilder();
		int stateIdx = firstNonWhitespaceOffset;
		// Track how many characters we've inserted (e.g. spaces after commas)
		// so that stateIdx stays aligned with the original charStates array.
		int offsetDelta = 0;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			int mappedIdx = stateIdx - offsetDelta;
			LexState charState = (mappedIdx >= 0 && mappedIdx < charStates.length) ? charStates[mappedIdx] : LexState.CODE;

			if (c == ',' && charState == LexState.CODE) {
				sb.append(c);
				if (i + 1 < line.length() && line.charAt(i + 1) != ' '
						&& line.charAt(i + 1) != '\t' && line.charAt(i + 1) != '\n') {
					sb.append(' ');
					offsetDelta++;
				}
			} else {
				sb.append(c);
			}
			stateIdx++;
		}
		return sb.toString();
	}

	/**
	 * Ensure a space between control-flow keywords and opening parenthesis,
	 * only when in CODE state.
	 */
	private String fixKeywordParenSpacingStateful(String line, LexState[] charStates, int firstNonWhitespaceOffset) {
		String[] keywords = {"if", "for", "while", "switch", "catch"};

		for (String keyword : keywords) {
			String pattern = keyword + "(";
			int idx = 0;
			StringBuilder sb = new StringBuilder();
			while (idx < line.length()) {
				int found = line.indexOf(pattern, idx);
				if (found == -1) {
					sb.append(line.substring(idx));
					break;
				}
				// Check that the keyword is at a word boundary
				boolean wordBoundary = (found == 0 || !Character.isLetterOrDigit(line.charAt(found - 1)));
				// Check that this position is in CODE state
				int statePos = firstNonWhitespaceOffset + found;
				boolean inCode = statePos < charStates.length && charStates[statePos] == LexState.CODE;

				if (wordBoundary && inCode) {
					sb.append(line, idx, found + keyword.length());
					sb.append(" (");
					idx = found + pattern.length();
				} else {
					sb.append(line, idx, found + pattern.length());
					idx = found + pattern.length();
				}
			}
			line = sb.toString();
		}
		return line;
	}

	/**
	 * Ensure a space before opening brace, only when in CODE state.
	 */
	private String fixBraceSpacingStateful(String line, LexState[] charStates, int firstNonWhitespaceOffset) {
		StringBuilder sb = new StringBuilder();

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			int statePos = firstNonWhitespaceOffset + i;
			LexState charState = (statePos < charStates.length) ? charStates[statePos] : LexState.CODE;

			if (c == '{' && charState == LexState.CODE && sb.length() > 0) {
				char prev = sb.charAt(sb.length() - 1);
				if (prev != ' ' && prev != '\t' && prev != '$' && prev != '{') {
					sb.append(' ');
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/**
	 * Find the position of the first non-whitespace character starting from offset.
	 */
	private int findFirstNonWhitespace(String originalLine, int lineStartOffset) {
		for (int i = 0; i < originalLine.length(); i++) {
			if (!Character.isWhitespace(originalLine.charAt(i))) {
				return lineStartOffset + i;
			}
		}
		return lineStartOffset + originalLine.length();
	}

	/**
	 * Count net opening braces on a line, using lexer state to ignore braces
	 * in strings and comments. Only counts { and } in CODE state.
	 */
	private int countNetBraces(String line, LexState[] charStates, int lineStartOffset) {
		int net = 0;
		for (int i = 0; i < line.length(); i++) {
			int statePos = lineStartOffset + i;
			LexState state = (statePos < charStates.length) ? charStates[statePos] : LexState.CODE;
			if (state == LexState.CODE) {
				char c = line.charAt(i);
				if (c == '{') {
					net++;
				} else if (c == '}') {
					net--;
				}
			}
		}
		return net;
	}

	private int countNetGroupingDelimiters(String line, LexState[] charStates, int lineStartOffset) {
		int net = 0;
		for (int i = 0; i < line.length(); i++) {
			int statePos = lineStartOffset + i;
			LexState state = (statePos < charStates.length) ? charStates[statePos] : LexState.CODE;
			if (state == LexState.CODE) {
				char c = line.charAt(i);
				if (c == '[') {
					net++;
				} else if (c == ']') {
					net--;
				}
			}
		}
		return net;
	}

	private int countLeadingClosers(String trimmedLine, LexState[] charStates, int lineStartOffset) {
		int closers = 0;
		for (int i = 0; i < trimmedLine.length(); i++) {
			char c = trimmedLine.charAt(i);
			boolean isCloser = c == '}' || c == ']';
			if (!isCloser) {
				return closers;
			}
			int statePos = lineStartOffset + i;
			LexState state = (statePos < charStates.length) ? charStates[statePos] : LexState.CODE;
			if (state != LexState.CODE) {
				return closers;
			}
			closers++;
		}
		return closers;
	}

	private String buildIndent(String singleIndent, int depth) {
		if (depth <= 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			sb.append(singleIndent);
		}
		return sb.toString();
	}

	private String trimTrailingNewlines(String text) {
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			end--;
		}
		return text.substring(0, end);
	}

	private boolean isContinuationMemberAccessLine(String trimmedLine) {
		return trimmedLine.startsWith(".")
				|| trimmedLine.startsWith("?.")
				|| trimmedLine.startsWith("*.")
				|| trimmedLine.startsWith(".@")
				|| trimmedLine.startsWith(".&");
	}
}
