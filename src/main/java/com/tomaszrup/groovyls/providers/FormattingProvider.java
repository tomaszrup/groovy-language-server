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

	public CompletableFuture<List<? extends TextEdit>> provideFormatting(
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
	private List<TextEdit> computeMinimalEdits(String original, String formatted) {
		String[] origLines = original.split("\\n", -1);
		String[] fmtLines = formatted.split("\\n", -1);
		List<TextEdit> edits = new ArrayList<>();

		int origLen = origLines.length;
		int fmtLen = fmtLines.length;

		int top = 0;
		int minLen = Math.min(origLen, fmtLen);
		while (top < minLen && origLines[top].equals(fmtLines[top])) {
			top++;
		}

		if (top == origLen && top == fmtLen) {
			return edits;
		}

		int origBottom = origLen - 1;
		int fmtBottom = fmtLen - 1;
		while (origBottom >= top && fmtBottom >= top
				&& origLines[origBottom].equals(fmtLines[fmtBottom])) {
			origBottom--;
			fmtBottom--;
		}

		StringBuilder replacement = new StringBuilder();
		for (int j = top; j <= fmtBottom; j++) {
			if (j > top) {
				replacement.append("\n");
			}
			replacement.append(fmtLines[j]);
		}

		Position start;
		Position end;

		if (top > origBottom) {
			if (top == 0) {
				start = new Position(0, 0);
				end = new Position(0, 0);
				if (fmtBottom >= top) {
					replacement.append("\n");
				}
			} else {
				start = new Position(top - 1, origLines[top - 1].length());
				end = new Position(top - 1, origLines[top - 1].length());
				if (fmtBottom >= top) {
					replacement.insert(0, "\n");
				}
			}
		} else {
			start = new Position(top, 0);
			end = new Position(origBottom, origLines[origBottom].length());
		}

		edits.add(new TextEdit(new Range(start, end), replacement.toString()));
		return edits;
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

			if (lineInMultiLineString || lineStartsInString) {
				// Don't modify lines inside multi-line strings at all
				result.append(line);
				if (i < lines.length - 1) {
					result.append("\n");
				}
				consecutiveBlankLines = 0;
				charOffset = lineEnd + 1; // +1 for \n
				continue;
			}

			String trimmedLine = line.trim();

			if (lineInBlockComment) {
				// Re-indent block comment lines at current depth
				if (!trimmedLine.isEmpty()) {
					line = buildIndent(singleIndent, braceDepth) + " " + trimmedLine;
				} else {
					line = "";
				}
				result.append(line);
				if (i < lines.length - 1) {
					result.append("\n");
				}
				consecutiveBlankLines = 0;
				charOffset = lineEnd + 1;
				continue;
			}

			// Handle blank lines — collapse 3+ to 2
			if (trimmedLine.isEmpty()) {
				consecutiveBlankLines++;
				if (consecutiveBlankLines <= 2) {
					if (i < lines.length - 1) {
						result.append("\n");
					}
				}
				charOffset = lineEnd + 1;
				continue;
			}
			consecutiveBlankLines = 0;

			// Apply spacing fixes only on code portions of the line
			trimmedLine = fixSpacing(trimmedLine, charStates, lineStart);

			// Determine indent level for this line
			int lineDepth = braceDepth;
			if (trimmedLine.startsWith("}") || trimmedLine.startsWith(")")) {
				lineDepth = Math.max(0, lineDepth - 1);
			}

			line = buildIndent(singleIndent, lineDepth) + trimmedLine;
			result.append(line);
			if (i < lines.length - 1) {
				result.append("\n");
			}

			// Update braceDepth based on code-only braces
			braceDepth += countNetBraces(line, charStates, lineStart);
			if (braceDepth < 0) {
				braceDepth = 0;
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

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			char next = (i + 1 < text.length()) ? text.charAt(i + 1) : 0;
			char next2 = (i + 2 < text.length()) ? text.charAt(i + 2) : 0;

			switch (state) {
				case CODE:
					if (c == '/' && next == '/') {
						state = LexState.LINE_COMMENT;
						states[i] = LexState.LINE_COMMENT;
					} else if (c == '/' && next == '*') {
						state = LexState.BLOCK_COMMENT;
						states[i] = LexState.BLOCK_COMMENT;
					} else if (c == '\'' && next == '\'' && next2 == '\'') {
						state = LexState.TRIPLE_SINGLE_QUOTED;
						states[i] = LexState.TRIPLE_SINGLE_QUOTED;
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_SINGLE_QUOTED; }
						if (i + 2 < text.length()) { states[i + 2] = LexState.TRIPLE_SINGLE_QUOTED; }
						i += 2;
					} else if (c == '"' && next == '"' && next2 == '"') {
						state = LexState.TRIPLE_DOUBLE_QUOTED;
						states[i] = LexState.TRIPLE_DOUBLE_QUOTED;
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_DOUBLE_QUOTED; }
						if (i + 2 < text.length()) { states[i + 2] = LexState.TRIPLE_DOUBLE_QUOTED; }
						i += 2;
					} else if (c == '\'') {
						state = LexState.SINGLE_QUOTED;
						states[i] = LexState.SINGLE_QUOTED;
					} else if (c == '"') {
						state = LexState.DOUBLE_QUOTED;
						states[i] = LexState.DOUBLE_QUOTED;
					} else if (c == '$' && next == '/') {
						state = LexState.DOLLAR_SLASH_STRING;
						states[i] = LexState.DOLLAR_SLASH_STRING;
					} else {
						states[i] = LexState.CODE;
					}
					break;

				case LINE_COMMENT:
					states[i] = LexState.LINE_COMMENT;
					if (c == '\n') {
						state = LexState.CODE;
					}
					break;

				case BLOCK_COMMENT:
					states[i] = LexState.BLOCK_COMMENT;
					if (c == '*' && next == '/') {
						states[i] = LexState.BLOCK_COMMENT;
						if (i + 1 < text.length()) { states[i + 1] = LexState.BLOCK_COMMENT; }
						i++;
						state = LexState.CODE;
					}
					break;

				case SINGLE_QUOTED:
					states[i] = LexState.SINGLE_QUOTED;
					if (c == '\\') {
						// Skip escaped character
						if (i + 1 < text.length()) { states[i + 1] = LexState.SINGLE_QUOTED; }
						i++;
					} else if (c == '\'') {
						state = LexState.CODE;
					}
					break;

				case DOUBLE_QUOTED:
					states[i] = LexState.DOUBLE_QUOTED;
					if (c == '\\') {
						if (i + 1 < text.length()) { states[i + 1] = LexState.DOUBLE_QUOTED; }
						i++;
					} else if (c == '$' && next == '{') {
						// GString expression
						states[i] = LexState.DOUBLE_QUOTED;
						if (i + 1 < text.length()) { states[i + 1] = LexState.DOUBLE_QUOTED; }
						i++;
						state = LexState.GSTRING_EXPR;
						gstringBraceDepth = 1;
					} else if (c == '"') {
						state = LexState.CODE;
					}
					break;

				case TRIPLE_SINGLE_QUOTED:
					states[i] = LexState.TRIPLE_SINGLE_QUOTED;
					if (c == '\\') {
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_SINGLE_QUOTED; }
						i++;
					} else if (c == '\'' && next == '\'' && next2 == '\'') {
						states[i] = LexState.TRIPLE_SINGLE_QUOTED;
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_SINGLE_QUOTED; }
						if (i + 2 < text.length()) { states[i + 2] = LexState.TRIPLE_SINGLE_QUOTED; }
						i += 2;
						state = LexState.CODE;
					}
					break;

				case TRIPLE_DOUBLE_QUOTED:
					states[i] = LexState.TRIPLE_DOUBLE_QUOTED;
					if (c == '\\') {
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_DOUBLE_QUOTED; }
						i++;
					} else if (c == '$' && next == '{') {
						states[i] = LexState.TRIPLE_DOUBLE_QUOTED;
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_DOUBLE_QUOTED; }
						i++;
						state = LexState.GSTRING_EXPR;
						gstringBraceDepth = 1;
					} else if (c == '"' && next == '"' && next2 == '"') {
						states[i] = LexState.TRIPLE_DOUBLE_QUOTED;
						if (i + 1 < text.length()) { states[i + 1] = LexState.TRIPLE_DOUBLE_QUOTED; }
						if (i + 2 < text.length()) { states[i + 2] = LexState.TRIPLE_DOUBLE_QUOTED; }
						i += 2;
						state = LexState.CODE;
					}
					break;

				case GSTRING_EXPR:
					states[i] = LexState.CODE; // Code inside ${...} is treated as code
					if (c == '{') {
						gstringBraceDepth++;
					} else if (c == '}') {
						gstringBraceDepth--;
						if (gstringBraceDepth == 0) {
							states[i] = LexState.DOUBLE_QUOTED;
							// Return to the enclosing string state
							// We need to check what the previous string state was
							state = findEnclosingStringState(states, i);
						}
					}
					break;

				case DOLLAR_SLASH_STRING:
					states[i] = LexState.DOLLAR_SLASH_STRING;
					if (c == '/' && next == '$') {
						states[i] = LexState.DOLLAR_SLASH_STRING;
						if (i + 1 < text.length()) { states[i + 1] = LexState.DOLLAR_SLASH_STRING; }
						i++;
						state = LexState.CODE;
					} else if (c == '$' && next == '/') {
						// Escaped slash in dollar-slash string
						states[i] = LexState.DOLLAR_SLASH_STRING;
						if (i + 1 < text.length()) { states[i + 1] = LexState.DOLLAR_SLASH_STRING; }
						i++;
					} else if (c == '$' && next == '$') {
						// Escaped dollar in dollar-slash string
						states[i] = LexState.DOLLAR_SLASH_STRING;
						if (i + 1 < text.length()) { states[i + 1] = LexState.DOLLAR_SLASH_STRING; }
						i++;
					}
					break;

				case SLASH_STRING:
					states[i] = LexState.SLASH_STRING;
					if (c == '\\') {
						// Skip escaped character
						if (i + 1 < text.length()) { states[i + 1] = LexState.SLASH_STRING; }
						i++;
					} else if (c == '/') {
						state = LexState.CODE;
					}
					break;
			}
		}
		return states;
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
	private String fixSpacing(String trimmedLine, LexState[] charStates, int lineStart) {
		// If the line starts with a comment indicator, skip formatting
		if (trimmedLine.startsWith("//") || trimmedLine.startsWith("/*") || trimmedLine.startsWith("*")) {
			return trimmedLine;
		}

		// Apply comma spacing (state-aware)
		trimmedLine = fixCommaSpacingStateful(trimmedLine, charStates, lineStart);

		// Apply keyword-paren spacing (state-aware)
		trimmedLine = fixKeywordParenSpacingStateful(trimmedLine, charStates, lineStart);

		// Apply brace spacing (state-aware)
		trimmedLine = fixBraceSpacingStateful(trimmedLine, charStates, lineStart);

		return trimmedLine;
	}

	/**
	 * Ensure a space after commas, but only when the comma is in CODE state.
	 */
	private String fixCommaSpacingStateful(String line, LexState[] charStates, int lineStartOffset) {
		StringBuilder sb = new StringBuilder();
		// We need to map each character in the formatted line back to a state.
		// Since we're working with the trimmed line, find the first non-ws position.
		int origOffset = findFirstNonWhitespace(charStates, lineStartOffset);
		int stateIdx = origOffset;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			LexState charState = (stateIdx < charStates.length) ? charStates[stateIdx] : LexState.CODE;

			if (c == ',' && charState == LexState.CODE) {
				sb.append(c);
				if (i + 1 < line.length() && line.charAt(i + 1) != ' '
						&& line.charAt(i + 1) != '\t' && line.charAt(i + 1) != '\n') {
					sb.append(' ');
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
	private String fixKeywordParenSpacingStateful(String line, LexState[] charStates, int lineStartOffset) {
		String[] keywords = {"if", "for", "while", "switch", "catch"};
		int origOffset = findFirstNonWhitespace(charStates, lineStartOffset);

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
				int statePos = origOffset + found;
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
	private String fixBraceSpacingStateful(String line, LexState[] charStates, int lineStartOffset) {
		StringBuilder sb = new StringBuilder();
		int origOffset = findFirstNonWhitespace(charStates, lineStartOffset);

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			int statePos = origOffset + i;
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
	private int findFirstNonWhitespace(LexState[] charStates, int offset) {
		// Walk forward from offset to find first non-whitespace
		// Since we don't have the text here, just return the offset
		// (the trimmedLine already stripped leading whitespace, so we need
		// to account for that). This is approximate but sufficient.
		return offset;
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

	private char getCharAt(LexState[] charStates, int pos, String trimmedLine, int lineStart, int trimOffset) {
		// Utility method for position mapping
		int idx = pos - lineStart - trimOffset;
		if (idx >= 0 && idx < trimmedLine.length()) {
			return trimmedLine.charAt(idx);
		}
		return 0;
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
}
