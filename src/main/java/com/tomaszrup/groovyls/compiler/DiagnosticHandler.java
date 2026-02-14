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
package com.tomaszrup.groovyls.compiler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.*;

import com.tomaszrup.groovyls.compiler.ast.UnusedImportFinder;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles conversion of Groovy compiler errors and unused-import analysis
 * into LSP diagnostics. Extracted from {@code GroovyServices} to reduce
 * class size and improve separation of concerns.
 *
 * <p>Tracks previous diagnostics per file so stale diagnostics can be
 * cleared when errors are resolved.</p>
 */
public class DiagnosticHandler {
	private static final Logger logger = LoggerFactory.getLogger(DiagnosticHandler.class);
	private static final Pattern UNRESOLVED_CLASS_PATTERN =
			Pattern.compile("unable to resolve class\\s+([^\\s\\r\\n]+)", Pattern.CASE_INSENSITIVE);

	/**
	 * Processes the error collector from a compilation and produces LSP
	 * diagnostic params, including unused import hints.
	 *
	 * @param compilationUnit the compilation unit to analyse for unused imports
	 * @param collector       the error collector from the last compilation
	 * @param projectRoot     the project root path (for logging)
	 * @param prevDiagnosticsByFile the previous round's diagnostics (may be null)
	 * @return a result containing the diagnostics to publish and the new
	 *         diagnostics-by-file map for the next round
	 */
	public DiagnosticResult handleErrorCollector(
			GroovyLSCompilationUnit compilationUnit,
			ErrorCollector collector,
			Path projectRoot,
			Map<URI, List<Diagnostic>> prevDiagnosticsByFile) {

		Map<URI, List<Diagnostic>> diagnosticsByFile = new HashMap<>();

		// Find unused imports and add them as diagnostics with the Unnecessary tag.
		// Wrapped in try/catch because incompletely-compiled ASTs (e.g. projects
		// that have never been built) can contain null arrays in MethodNode,
		// and we don't want unused-import analysis failures to prevent real
		// diagnostics from being published.
		try {
			UnusedImportFinder unusedImportFinder = new UnusedImportFinder();
			Map<URI, List<org.codehaus.groovy.ast.ImportNode>> unusedImportsByFile = unusedImportFinder
					.findUnusedImports(compilationUnit);
			for (Map.Entry<URI, List<org.codehaus.groovy.ast.ImportNode>> entry : unusedImportsByFile.entrySet()) {
				URI uri = entry.getKey();
				for (org.codehaus.groovy.ast.ImportNode importNode : entry.getValue()) {
					Range range = GroovyLanguageServerUtils.astNodeToRange(importNode);
					if (range == null) {
						continue;
					}
					Diagnostic diagnostic = new Diagnostic();
					diagnostic.setRange(range);
					diagnostic.setSeverity(DiagnosticSeverity.Hint);
					diagnostic.setMessage("Unused import");
					diagnostic.setTags(Collections.singletonList(DiagnosticTag.Unnecessary));
					diagnostic.setSource("groovy");
					diagnosticsByFile.computeIfAbsent(uri, (key) -> new ArrayList<>()).add(diagnostic);
				}
			}
		} catch (Exception e) {
			logger.warn("Unused import analysis failed for scope {}: {}", projectRoot, e.getMessage());
		}

		List<? extends Message> errors = collector.getErrors();
		if (errors != null && !errors.isEmpty()) {
			logger.debug("Scope {} has {} compilation errors", projectRoot, errors.size());
		}
		if (errors != null) {
			errors.stream().filter((Object message) -> message instanceof SyntaxErrorMessage)
					.forEach((Object message) -> {
						SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
						SyntaxException cause = syntaxErrorMessage.getCause();
						Range range = GroovyLanguageServerUtils.syntaxExceptionToRange(cause);
						if (range == null) {
							range = new Range(new Position(0, 0), new Position(0, 0));
						}
						Diagnostic diagnostic = new Diagnostic();
						diagnostic.setRange(range);
						diagnostic.setSeverity(cause.isFatal() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning);
						diagnostic.setMessage(cause.getMessage());
						String sourceLocator = cause.getSourceLocator();
						if (sourceLocator == null || sourceLocator.isEmpty()) {
							logger.debug("Skipping diagnostic with null/empty source locator: {}", cause.getMessage());
							return;
						}
						URI uri;
						uri = GroovyLanguageServerUtils.sourceLocatorToUri(sourceLocator);
						if (uri == null) {
							logger.debug("Skipping diagnostic with invalid source locator '{}': {}", sourceLocator, cause.getMessage());
							return;
						}
						logger.debug("  Diagnostic [{}] in {}: {}", projectRoot, uri, cause.getMessage());
						traceUnresolvedClassDiagnostic(projectRoot, cause, uri, range);
						diagnosticsByFile.computeIfAbsent(uri, (key) -> new ArrayList<>()).add(diagnostic);
					});
		}

		// Deduplicate diagnostics per file — the Groovy compiler can report
		// the same error in multiple compilation phases because
		// LanguageServerErrorCollector.failIfErrors() is a no-op.
		for (Map.Entry<URI, List<Diagnostic>> entry : diagnosticsByFile.entrySet()) {
			List<Diagnostic> unique = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (Diagnostic d : entry.getValue()) {
				String key = d.getRange() + "|" + d.getMessage() + "|" + d.getSeverity();
				if (seen.add(key)) {
					unique.add(d);
				}
			}
			entry.setValue(unique);
		}

		Set<PublishDiagnosticsParams> result = diagnosticsByFile.entrySet().stream()
				.map(entry -> new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()))
				.collect(Collectors.toSet());

		if (prevDiagnosticsByFile != null) {
			for (URI key : prevDiagnosticsByFile.keySet()) {
				if (!diagnosticsByFile.containsKey(key)) {
					result.add(new PublishDiagnosticsParams(key.toString(), new ArrayList<>()));
				}
			}
		}

		return new DiagnosticResult(result, diagnosticsByFile);
	}

	private void traceUnresolvedClassDiagnostic(Path projectRoot, SyntaxException cause, URI uri, Range range) {
		if (!logger.isDebugEnabled()) {
			return;
		}
		String message = cause.getMessage();
		if (message == null) {
			return;
		}
		Matcher matcher = UNRESOLVED_CLASS_PATTERN.matcher(message);
		if (!matcher.find()) {
			return;
		}
		String unresolved = matcher.group(1);
		String locator = cause.getSourceLocator();
		String sourceLine = readLineFromLocator(locator, range != null ? range.getStart().getLine() + 1 : -1);
		String importToken = extractImportToken(sourceLine);
		logger.debug(
				"unresolvedTrace projectRoot={} uri={} unresolved={} unresolvedCodepoints={} line={} col={} sourceLocator={} sourceLine={}",
				projectRoot,
				uri,
				unresolved,
				toCodepointHex(unresolved, 64),
				range != null ? (range.getStart().getLine() + 1) : null,
				range != null ? (range.getStart().getCharacter() + 1) : null,
				locator,
				sourceLine);
		if (importToken != null && !importToken.equals(unresolved)) {
			logger.warn(
					"unresolvedTrace mismatch projectRoot={} uri={} importToken={} unresolved={} line={} col={} sourceLocator={}",
					projectRoot,
					uri,
					importToken,
					unresolved,
					range != null ? (range.getStart().getLine() + 1) : null,
					range != null ? (range.getStart().getCharacter() + 1) : null,
					locator);
		}
	}

	private String extractImportToken(String sourceLine) {
		if (sourceLine == null || sourceLine.startsWith("<")) {
			return null;
		}
		String trimmed = sourceLine.trim();
		if (!trimmed.startsWith("import ")) {
			return null;
		}
		String token = trimmed.substring("import ".length()).trim();
		if (token.endsWith(";")) {
			token = token.substring(0, token.length() - 1).trim();
		}
		if (token.isEmpty()) {
			return null;
		}
		return token;
	}

	private String readLineFromLocator(String sourceLocator, int oneBasedLine) {
		if (sourceLocator == null || sourceLocator.isEmpty() || oneBasedLine < 1) {
			return "<unavailable>";
		}
		try {
			Path p = GroovyLanguageServerUtils.sourceLocatorToPath(sourceLocator);
			if (p == null) {
				return "<not-a-file>";
			}
			if (!Files.isRegularFile(p)) {
				return "<not-a-file>";
			}
			List<String> lines = Files.readAllLines(p);
			if (oneBasedLine > lines.size()) {
				return "<line-out-of-range>";
			}
			return preview(lines.get(oneBasedLine - 1), 220);
		} catch (Exception e) {
			return "<read-failed:" + e.getClass().getSimpleName() + ">";
		}
	}

	private String toCodepointHex(String text, int maxCodepoints) {
		if (text == null) {
			return "<null>";
		}
		StringBuilder sb = new StringBuilder();
		int idx = 0;
		int count = 0;
		while (idx < text.length() && count < maxCodepoints) {
			int cp = text.codePointAt(idx);
			if (count > 0) {
				sb.append(' ');
			}
			sb.append(String.format("U+%04X", cp));
			idx += Character.charCount(cp);
			count++;
		}
		if (idx < text.length()) {
			sb.append(" ...");
		}
		return sb.toString();
	}

	private String preview(String text, int maxLen) {
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

	/**
	 * Immutable result from processing diagnostics, containing the params to
	 * publish and the updated diagnostics-by-file map for the next round.
	 */
	public static class DiagnosticResult {
		private final Set<PublishDiagnosticsParams> diagnosticsToPublish;
		private final Map<URI, List<Diagnostic>> diagnosticsByFile;

		public DiagnosticResult(Set<PublishDiagnosticsParams> diagnosticsToPublish,
				Map<URI, List<Diagnostic>> diagnosticsByFile) {
			this.diagnosticsToPublish = diagnosticsToPublish;
			this.diagnosticsByFile = diagnosticsByFile;
		}

		public Set<PublishDiagnosticsParams> getDiagnosticsToPublish() {
			return diagnosticsToPublish;
		}

		public Map<URI, List<Diagnostic>> getDiagnosticsByFile() {
			return diagnosticsByFile;
		}
	}
}
