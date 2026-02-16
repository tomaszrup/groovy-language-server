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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.providers.codeactions.OrganizeImportsAction;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles LSP document formatting requests, including optional
 * organize-imports integration.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class FormattingHandler {
	private static final Logger logger = LoggerFactory.getLogger(FormattingHandler.class);
	private static final String IMPORT_KEYWORD = "import ";

	private final LspProviderFacade providerFacade;
	private final FileContentsTracker fileContentsTracker;

	FormattingHandler(LspProviderFacade providerFacade, FileContentsTracker fileContentsTracker) {
		this.providerFacade = providerFacade;
		this.fileContentsTracker = fileContentsTracker;
	}

	@SuppressWarnings("java:S1452")
	CompletableFuture<List<? extends TextEdit>> formatting(
			DocumentFormattingParams params,
			boolean formattingOrganizeImportsEnabled,
			Function<URI, ProjectScope> ensureCompiledForContext) {
		URI uri = URI.create(params.getTextDocument().getUri());
		String sourceText = fileContentsTracker.getContents(uri);
		if (sourceText == null || sourceText.isEmpty()) {
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		String normalizedSource = normalizeLineEndings(sourceText);
		String textToFormat = normalizedSource;

		if (formattingOrganizeImportsEnabled) {
			try {
				String organizedText = applyOrganizeImportsForFormatting(uri, textToFormat, ensureCompiledForContext);
				if (organizedText != null) {
					textToFormat = organizedText;
				}
			} catch (Exception e) {
				logger.debug("formatting organize imports skipped uri={} reason={}", uri, e.toString());
			}
		}

		final String textForFormatting = textToFormat;
		return providerFacade.provideFormatting(params, textForFormatting)
				.thenApply(formattingEdits -> {
					String formattedText = applyTextEdits(textForFormatting, formattingEdits);
					formattedText = normalizeBlankLinesAfterLastImport(formattedText);
					if (formattedText.equals(normalizedSource)) {
						return Collections.<TextEdit>emptyList();
					}
					return Collections.singletonList(new TextEdit(
							new Range(new Position(0, 0), documentEndPosition(normalizedSource)),
							formattedText));
				});
	}

	static Position documentEndPosition(String text) {
		String[] lines = text.split("\\n", -1);
		return new Position(lines.length - 1, lines[lines.length - 1].length());
	}

	static String normalizeBlankLinesAfterLastImport(String text) {
		String[] lines = text.split("\\n", -1);
		int lastImportLine = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith(IMPORT_KEYWORD)) {
				lastImportLine = i;
			}
		}
		if (lastImportLine < 0 || lastImportLine + 1 >= lines.length) {
			return text;
		}

		int afterImportStart = lastImportLine + 1;
		int firstNonBlank = afterImportStart;
		while (firstNonBlank < lines.length && lines[firstNonBlank].trim().isEmpty()) {
			firstNonBlank++;
		}

		int blankCount = firstNonBlank - afterImportStart;
		if (blankCount <= 1) {
			return text;
		}

		StringBuilder normalized = new StringBuilder();
		for (int i = 0; i <= lastImportLine; i++) {
			normalized.append(lines[i]).append("\n");
		}
		normalized.append("\n");
		for (int i = firstNonBlank; i < lines.length; i++) {
			normalized.append(lines[i]);
			if (i < lines.length - 1) {
				normalized.append("\n");
			}
		}
		return normalized.toString();
	}

	private String applyOrganizeImportsForFormatting(
			URI uri,
			String sourceText,
			Function<URI, ProjectScope> ensureCompiledForContext) {
		ProjectScope scope = ensureCompiledForContext.apply(uri);
		ASTNodeVisitor visitor = scope != null ? scope.getAstVisitor() : null;
		if (visitor == null) {
			return sourceText;
		}

		OrganizeImportsAction organizeImportsAction = new OrganizeImportsAction(visitor);
		TextEdit importEdit = organizeImportsAction.createOrganizeImportsTextEdit(uri);
		if (importEdit == null) {
			return sourceText;
		}
		importEdit = normalizeImportEditForExistingBlankLine(sourceText, importEdit);

		return applyTextEdits(sourceText, Collections.singletonList(importEdit));
	}

	TextEdit normalizeImportEditForExistingBlankLine(String sourceText, TextEdit importEdit) {
		String newText = importEdit.getNewText();
		if (newText == null) {
			return importEdit;
		}

		String normalizedNewText = newText;
		if (!normalizedNewText.endsWith("\n\n")) {
			normalizedNewText = normalizedNewText + "\n\n";
		}

		int startOffset = positionToOffset(sourceText, importEdit.getRange().getEnd());
		int endOffset = startOffset;
		while (endOffset < sourceText.length() && sourceText.charAt(endOffset) == '\n') {
			endOffset++;
		}

		if (endOffset == startOffset && normalizedNewText.equals(importEdit.getNewText())) {
			return importEdit;
		}

		Range normalizedRange = new Range(
				importEdit.getRange().getStart(),
				offsetToPosition(sourceText, endOffset));
		return new TextEdit(normalizedRange, normalizedNewText);
	}

	static Position offsetToPosition(String text, int offset) {
		int clampedOffset = Math.max(0, Math.min(offset, text.length()));
		int line = 0;
		int lineStartOffset = 0;
		for (int i = 0; i < clampedOffset; i++) {
			if (text.charAt(i) == '\n') {
				line++;
				lineStartOffset = i + 1;
			}
		}
		return new Position(line, clampedOffset - lineStartOffset);
	}

	static String normalizeLineEndings(String text) {
		return text.replace("\r\n", "\n").replace("\r", "\n");
	}

	static String applyTextEdits(String original, List<? extends TextEdit> edits) {
		if (edits == null || edits.isEmpty()) {
			return original;
		}

		String text = original;
		List<? extends TextEdit> sorted = new ArrayList<>(edits);
		sorted.sort((a, b) -> {
			int lineCmp = Integer.compare(b.getRange().getStart().getLine(), a.getRange().getStart().getLine());
			if (lineCmp != 0) {
				return lineCmp;
			}
			return Integer.compare(b.getRange().getStart().getCharacter(), a.getRange().getStart().getCharacter());
		});

		for (TextEdit edit : sorted) {
			Range range = edit.getRange();
			int startOffset = positionToOffset(text, range.getStart());
			int endOffset = positionToOffset(text, range.getEnd());
			text = text.substring(0, startOffset) + edit.getNewText() + text.substring(endOffset);
		}

		return text;
	}

	static int positionToOffset(String text, Position pos) {
		int line = 0;
		int offset = 0;
		while (line < pos.getLine() && offset < text.length()) {
			if (text.charAt(offset) == '\n') {
				line++;
			}
			offset++;
		}
		return offset + pos.getCharacter();
	}

	static String preview(String text, int maxLen) {
		if (text == null) {
			return "<null>";
		}
		String normalized = text
				.replace("\r", "\\r")
				.replace("\n", "\\n")
				.replace("\t", "\\t");
		if (normalized.length() <= maxLen) {
			return normalized;
		}
		return normalized.substring(0, maxLen) + "...";
	}

	static List<String> collectImportLines(String text, int maxLines) {
		List<String> result = new ArrayList<>();
		String[] lines = text.split("\\R", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.contains(IMPORT_KEYWORD)) {
				result.add((i + 1) + ":" + preview(line, 200));
				if (result.size() >= maxLines) {
					break;
				}
			}
		}
		return result;
	}
}
