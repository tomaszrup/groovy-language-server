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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

/**
 * Provides textDocument/formatting support for Groovy source files.
 *
 * <p>Formatting rules applied:
 * <ul>
 *   <li>Re-indent code based on brace nesting depth</li>
 *   <li>Remove trailing whitespace from each line</li>
 *   <li>Ensure the file ends with a single newline</li>
 *   <li>Normalize blank lines (collapse 3+ consecutive blank lines to 2)</li>
 *   <li>Ensure consistent spacing after commas</li>
 *   <li>Ensure consistent spacing after control-flow keywords</li>
 *   <li>Ensure consistent spacing around opening braces</li>
 * </ul>
 */
public class FormattingProvider {

	// Matches a line that starts with whitespace (the indentation portion)
	private static final Pattern LEADING_WHITESPACE = Pattern.compile("^([ \\t]*)(.*)$");

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

		// Compute a single whole-document replacement edit.
		// Use the normalized source (\n only) so that line splitting is correct
		// and we don't include stray \r in character counts.
		String[] originalLines = normalizedSource.split("\\n", -1);
		int lastLine = originalLines.length - 1;
		int lastChar = originalLines[lastLine].length();

		TextEdit edit = new TextEdit(
				new Range(new Position(0, 0), new Position(lastLine, lastChar)),
				formatted);

		List<TextEdit> edits = new ArrayList<>();
		edits.add(edit);
		return CompletableFuture.completedFuture(edits);
	}

	/**
	 * Format the given Groovy source text.
	 */
	String format(String sourceText, int tabSize, boolean insertSpaces) {
		// Normalize line endings to \n for processing
		String text = sourceText.replace("\r\n", "\n").replace("\r", "\n");
		String[] lines = text.split("\\n", -1);

		String singleIndent = insertSpaces ? " ".repeat(tabSize) : "\t";

		StringBuilder result = new StringBuilder();
		int consecutiveBlankLines = 0;
		boolean inMultiLineString = false;
		boolean inBlockComment = false;
		int braceDepth = 0;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];

			// Track multi-line strings (triple-quoted) — don't format inside them
			if (inMultiLineString) {
				result.append(line);
				if (containsTripleQuoteEnd(line)) {
					inMultiLineString = false;
				}
				if (i < lines.length - 1) {
					result.append("\n");
				}
				consecutiveBlankLines = 0;
				continue;
			}

			// Track block comments — only trim trailing whitespace
			if (inBlockComment) {
				line = trimTrailingWhitespace(line);
				String trimmed = line.trim();
				// Re-indent block comment lines at current depth
				if (!trimmed.isEmpty()) {
					line = buildIndent(singleIndent, braceDepth) + " " + trimmed;
				}
				result.append(line);
				if (trimmed.contains("*/")) {
					inBlockComment = false;
				}
				if (i < lines.length - 1) {
					result.append("\n");
				}
				consecutiveBlankLines = 0;
				continue;
			}

			// Strip existing indentation to get content
			String trimmedLine = line.trim();

			// Check if this line starts a multi-line string
			if (startsMultiLineString(line)) {
				inMultiLineString = true;
				line = buildIndent(singleIndent, braceDepth) + trimmedLine;
				result.append(line);
				if (i < lines.length - 1) {
					result.append("\n");
				}
				consecutiveBlankLines = 0;
				continue;
			}

			// Check for block comment start
			if (trimmedLine.startsWith("/*") && !trimmedLine.contains("*/")) {
				inBlockComment = true;
			}

			// Handle blank lines — collapse 3+ to 2
			if (trimmedLine.isEmpty()) {
				consecutiveBlankLines++;
				if (consecutiveBlankLines <= 2) {
					if (i < lines.length - 1) {
						result.append("\n");
					}
				}
				continue;
			}
			consecutiveBlankLines = 0;

			// Apply spacing fixes (only on non-comment lines)
			if (!trimmedLine.startsWith("//") && !trimmedLine.startsWith("/*") && !trimmedLine.startsWith("*")) {
				trimmedLine = fixCommaSpacing(trimmedLine);
				trimmedLine = fixKeywordParenSpacing(trimmedLine);
				trimmedLine = fixBraceSpacing(trimmedLine);
			}

			// Determine indent level for this line.
			// Lines starting with } or ) reduce depth BEFORE printing.
			int lineDepth = braceDepth;
			if (trimmedLine.startsWith("}") || trimmedLine.startsWith(")")) {
				lineDepth = Math.max(0, lineDepth - 1);
			}

			// Build the indented line
			line = buildIndent(singleIndent, lineDepth) + trimmedLine;

			result.append(line);
			if (i < lines.length - 1) {
				result.append("\n");
			}

			// Update braceDepth based on unquoted braces/parens on this line
			// for subsequent lines.
			braceDepth += countNetBraces(trimmedLine);
			if (braceDepth < 0) {
				braceDepth = 0;
			}
		}

		String formatted = result.toString();

		// Ensure the file ends with exactly one newline
		formatted = trimTrailingNewlines(formatted);
		formatted = formatted + "\n";

		return formatted;
	}

	/**
	 * Build an indentation string for the given depth.
	 */
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

	/**
	 * Count net opening braces/parens on a line, ignoring those inside strings
	 * and comments. Returns positive for net openers, negative for net closers.
	 * Only { and } are counted for indentation, not ( and ).
	 * However, standalone ( at end-of-line or ) at start-of-line already handled
	 * by the open/close brace logic for curly braces specifically.
	 */
	private int countNetBraces(String line) {
		int net = 0;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean escaped = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (escaped) {
				escaped = false;
				continue;
			}

			if (c == '\\') {
				escaped = true;
				continue;
			}

			// Check for line comment — stop processing
			if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/'
					&& !inSingleQuote && !inDoubleQuote) {
				break;
			}

			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				continue;
			}

			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				continue;
			}

			if (!inSingleQuote && !inDoubleQuote) {
				if (c == '{') {
					net++;
				} else if (c == '}') {
					net--;
				}
			}
		}

		return net;
	}

	private String trimTrailingWhitespace(String line) {
		int end = line.length();
		while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
			end--;
		}
		return line.substring(0, end);
	}

	private String trimTrailingNewlines(String text) {
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			end--;
		}
		return text.substring(0, end);
	}

	/**
	 * Ensure a space after commas (but not inside string literals).
	 */
	private String fixCommaSpacing(String line) {
		StringBuilder sb = new StringBuilder();
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean escaped = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (escaped) {
				sb.append(c);
				escaped = false;
				continue;
			}

			if (c == '\\') {
				sb.append(c);
				escaped = true;
				continue;
			}

			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				sb.append(c);
				continue;
			}

			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				sb.append(c);
				continue;
			}

			if (c == ',' && !inSingleQuote && !inDoubleQuote) {
				sb.append(c);
				if (i + 1 < line.length() && line.charAt(i + 1) != ' '
						&& line.charAt(i + 1) != '\t' && line.charAt(i + 1) != '\n') {
					sb.append(' ');
				}
				continue;
			}

			sb.append(c);
		}

		return sb.toString();
	}

	/**
	 * Ensure a space between control-flow keywords and opening parenthesis.
	 */
	private String fixKeywordParenSpacing(String line) {
		line = line.replaceAll("\\bif\\(", "if (");
		line = line.replaceAll("\\bfor\\(", "for (");
		line = line.replaceAll("\\bwhile\\(", "while (");
		line = line.replaceAll("\\bswitch\\(", "switch (");
		line = line.replaceAll("\\bcatch\\(", "catch (");
		return line;
	}

	/**
	 * Ensure a space before opening brace, unless preceded by $ (GString) or
	 * is already at line start.
	 */
	private String fixBraceSpacing(String line) {
		StringBuilder sb = new StringBuilder();
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean escaped = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (escaped) {
				sb.append(c);
				escaped = false;
				continue;
			}

			if (c == '\\') {
				sb.append(c);
				escaped = true;
				continue;
			}

			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				sb.append(c);
				continue;
			}

			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				sb.append(c);
				continue;
			}

			if (c == '{' && !inSingleQuote && !inDoubleQuote && sb.length() > 0) {
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
	 * Check if a line starts a triple-quoted string that doesn't close on the same line.
	 */
	private boolean startsMultiLineString(String line) {
		String trimmed = line.trim();
		int tripleDouble = countOccurrences(trimmed, "\"\"\"");
		int tripleSingle = countOccurrences(trimmed, "'''");
		return (tripleDouble % 2 != 0) || (tripleSingle % 2 != 0);
	}

	/**
	 * Check if a line contains the closing of a triple-quoted string.
	 */
	private boolean containsTripleQuoteEnd(String line) {
		return line.contains("\"\"\"") || line.contains("'''");
	}

	private int countOccurrences(String text, String sub) {
		int count = 0;
		int idx = 0;
		while ((idx = text.indexOf(sub, idx)) != -1) {
			count++;
			idx += sub.length();
		}
		return count;
	}
}
