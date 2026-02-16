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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.services.LanguageClient;

import com.tomaszrup.groovyls.util.FileContentsTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles rewriting of Groovy import statements when Java classes are moved
 * or renamed.  Extracted from {@link GroovyServices} for single-responsibility.
 */
class GroovyImportRewriter {
	private static final Logger logger = LoggerFactory.getLogger(GroovyImportRewriter.class);
	private static final String IMPORT_KEYWORD = "import ";
	private static final String STATIC_KEYWORD = "static ";
	private static final String PACKAGE_KEYWORD = "package ";
	private static final String GROOVY_EXTENSION = ".groovy";

	private final FileContentsTracker fileContentsTracker;

	GroovyImportRewriter(FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
	}

	void applyGroovyImportUpdatesForJavaMoves(
			Path projectRoot,
			Map<String, String> movedImports,
			LanguageClient client) {
		if (projectRoot == null || movedImports == null || movedImports.isEmpty()) {
			return;
		}
		if (client == null) {
			return;
		}

		Set<URI> candidateUris = collectGroovyUrisForProject(projectRoot);
		if (candidateUris.isEmpty()) {
			return;
		}
		Map<String, List<TextEdit>> changes = collectGroovyImportRewriteChanges(candidateUris, movedImports);
		if (changes.isEmpty()) {
			return;
		}
		applyGroovyImportWorkspaceEdit(client, changes);
	}

	private Map<String, List<TextEdit>> collectGroovyImportRewriteChanges(
			Set<URI> candidateUris,
			Map<String, String> movedImports) {
		Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
		for (URI uri : candidateUris) {
			addGroovyImportRewriteForUri(uri, movedImports, changes);
		}
		return changes;
	}

	private void addGroovyImportRewriteForUri(
			URI uri,
			Map<String, String> movedImports,
			Map<String, List<TextEdit>> changes) {
		String source = fileContentsTracker.getContents(uri);
		if (source == null || source.isEmpty()) {
			return;
		}
		String updated = rewriteGroovyImports(source, movedImports);
		if (updated.equals(source)) {
			return;
		}

		TextEdit fullReplacement = createFullReplacementEdit(source, updated);
		changes.put(uri.toString(), Collections.singletonList(fullReplacement));
		updateOpenFileContentsIfNeeded(uri, updated);
	}

	private TextEdit createFullReplacementEdit(String source, String updated) {
		String[] lines = source.split("\\n", -1);
		int endLine = lines.length - 1;
		int endChar = lines[endLine].length();
		return new TextEdit(
				new Range(new Position(0, 0), new Position(endLine, endChar)),
				updated);
	}

	private void updateOpenFileContentsIfNeeded(URI uri, String updated) {
		if (!fileContentsTracker.isOpen(uri)) {
			return;
		}
		fileContentsTracker.setContents(uri, updated);
		fileContentsTracker.forceChanged(uri);
	}

	private void applyGroovyImportWorkspaceEdit(LanguageClient client, Map<String, List<TextEdit>> changes) {
		WorkspaceEdit edit = new WorkspaceEdit();
		edit.setChanges(changes);
		ApplyWorkspaceEditParams params = new ApplyWorkspaceEditParams(edit);
		client.applyEdit(params).exceptionally(error -> {
			logger.debug("Failed to apply Groovy import updates after Java move: {}", error.getMessage());
			return null;
		});
	}

	Set<URI> collectGroovyUrisForProject(Path projectRoot) {
		Set<URI> uris = new LinkedHashSet<>();
		for (URI openUri : fileContentsTracker.getOpenURIs()) {
			try {
				Path openPath = Path.of(openUri);
				if (openPath.startsWith(projectRoot)
						&& openPath.toString().endsWith(GROOVY_EXTENSION)
						&& !FileChangeHandler.isBuildOutputFile(openPath, projectRoot)) {
					uris.add(openUri);
				}
			} catch (Exception e) {
				// ignore URI that cannot be converted to a path
			}
		}

		try (var stream = Files.walk(projectRoot)) {
			stream.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(GROOVY_EXTENSION))
					.filter(path -> !FileChangeHandler.isBuildOutputFile(path, projectRoot))
					.forEach(path -> uris.add(path.toUri()));
		} catch (Exception e) {
			logger.debug("Failed to scan Groovy files for import updates under {}: {}",
					projectRoot, e.getMessage());
		}

		return uris;
	}

	String rewriteGroovyImports(String source, Map<String, String> movedImports) {
		String updated = source;
		for (Map.Entry<String, String> move : movedImports.entrySet()) {
			updated = rewriteGroovyImportStatements(updated, move.getKey(), move.getValue());
		}
		updated = addImportsForSamePackageJavaMoves(updated, movedImports);
		return updated;
	}

	String rewriteGroovyImportStatements(String source, String oldFqcn, String newFqcn) {
		if (source == null || source.isEmpty() || oldFqcn == null || oldFqcn.isEmpty()
				|| newFqcn == null || newFqcn.isEmpty()) {
			return source;
		}

		String[] lines = source.split("\\R", -1);
		for (int i = 0; i < lines.length; i++) {
			lines[i] = rewriteSingleImportLine(lines[i], oldFqcn, newFqcn);
		}
		return String.join(detectNewline(source), lines);
	}

	String rewriteSingleImportLine(String line, String oldFqcn, String newFqcn) {
		if (line == null || line.isEmpty()) {
			return line;
		}

		String trimmed = line.trim();
		if (!trimmed.startsWith(IMPORT_KEYWORD)) {
			return line;
		}

		boolean hadSemicolon = trimmed.endsWith(";");
		String importBody = trimmed.substring(IMPORT_KEYWORD.length()).trim();
		if (hadSemicolon) {
			importBody = importBody.substring(0, importBody.length() - 1).trim();
		}

		String rewrittenBody = rewriteImportBody(importBody, oldFqcn, newFqcn);
		if (rewrittenBody.equals(importBody)) {
			return line;
		}

		String leadingWhitespace = line.substring(0, line.indexOf(trimmed));
		return leadingWhitespace + IMPORT_KEYWORD + rewrittenBody + (hadSemicolon ? ";" : "");
	}

	String rewriteImportBody(String importBody, String oldFqcn, String newFqcn) {
		if (importBody.startsWith(STATIC_KEYWORD)) {
			String staticBody = importBody.substring(STATIC_KEYWORD.length()).trim();
			if (staticBody.equals(oldFqcn)) {
				return STATIC_KEYWORD + newFqcn;
			}
			String staticPrefix = oldFqcn + ".";
			if (staticBody.startsWith(staticPrefix)) {
				return STATIC_KEYWORD + newFqcn + staticBody.substring(oldFqcn.length());
			}
			return importBody;
		}

		String aliasSuffix = "";
		String target = importBody;
		int asIndex = importBody.indexOf(" as ");
		if (asIndex >= 0) {
			target = importBody.substring(0, asIndex).trim();
			aliasSuffix = importBody.substring(asIndex);
		}

		if (!target.equals(oldFqcn)) {
			return importBody;
		}
		return newFqcn + aliasSuffix;
	}

	String addImportsForSamePackageJavaMoves(String source, Map<String, String> movedImports) {
		String updated = source;
		String filePackage = extractGroovyPackage(updated);
		if (filePackage == null || filePackage.isEmpty()) {
			return updated;
		}

		for (Map.Entry<String, String> move : movedImports.entrySet()) {
			String oldFqcn = move.getKey();
			String newFqcn = move.getValue();
			String oldPackage = packageNameOf(oldFqcn);
			String newPackage = packageNameOf(newFqcn);
			String simpleName = simpleNameOf(newFqcn);

			boolean movedOutOfFilePackage = filePackage.equals(oldPackage) && !filePackage.equals(newPackage);
			boolean hasRelevantImport = hasImport(updated, oldFqcn) || hasImport(updated, newFqcn);
			boolean referencesSimpleType = containsUnqualifiedTypeReference(updated, simpleName);
			if (movedOutOfFilePackage && !hasRelevantImport && referencesSimpleType) {
				updated = insertImport(updated, newFqcn);
			}
		}

		return updated;
	}

	String extractGroovyPackage(String source) {
		for (String line : source.split("\\R", -1)) {
			String trimmed = line.trim();
			boolean commentOrEmpty = trimmed.isEmpty() || trimmed.startsWith("//")
					|| trimmed.startsWith("/*") || trimmed.startsWith("*");
			if (!commentOrEmpty) {
				if (trimmed.startsWith(PACKAGE_KEYWORD)) {
					String pkg = trimmed.substring(PACKAGE_KEYWORD.length()).trim();
					if (pkg.endsWith(";")) {
						pkg = pkg.substring(0, pkg.length() - 1).trim();
					}
					return pkg;
				}
				break;
			}
		}
		return null;
	}

	static String packageNameOf(String fqcn) {
		if (fqcn == null) {
			return "";
		}
		int dot = fqcn.lastIndexOf('.');
		return dot >= 0 ? fqcn.substring(0, dot) : "";
	}

	static String simpleNameOf(String fqcn) {
		if (fqcn == null) {
			return "";
		}
		int dot = fqcn.lastIndexOf('.');
		return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
	}

	boolean hasImport(String source, String fqcn) {
		if (fqcn == null || fqcn.isEmpty()) {
			return false;
		}
		for (String line : source.split("\\R", -1)) {
			String trimmed = line.trim();
			if (!trimmed.startsWith(IMPORT_KEYWORD) || trimmed.startsWith(IMPORT_KEYWORD + STATIC_KEYWORD)) {
				continue;
			}

			String imported = trimmed.substring(IMPORT_KEYWORD.length()).trim();
			if (imported.endsWith(";")) {
				imported = imported.substring(0, imported.length() - 1).trim();
			}
			int asIndex = imported.indexOf(" as ");
			if (asIndex >= 0) {
				imported = imported.substring(0, asIndex).trim();
			}
			if (imported.equals(fqcn)) {
				return true;
			}
		}
		return false;
	}

	boolean containsUnqualifiedTypeReference(String source, String simpleName) {
		if (simpleName == null || simpleName.isEmpty()) {
			return false;
		}
		int from = 0;
		while (from <= source.length() - simpleName.length()) {
			int at = source.indexOf(simpleName, from);
			if (at < 0) {
				return false;
			}
			if (isIdentifierBoundary(source, at - 1)
					&& isIdentifierBoundary(source, at + simpleName.length())) {
				return true;
			}
			from = at + simpleName.length();
		}
		return false;
	}

	static boolean isIdentifierBoundary(String text, int index) {
		if (index < 0 || index >= text.length()) {
			return true;
		}
		return !Character.isJavaIdentifierPart(text.charAt(index));
	}

	String insertImport(String source, String fqcn) {
		String newline = detectNewline(source);
		String importLine = IMPORT_KEYWORD + fqcn + newline;
		String[] lines = source.split("\\R", -1);
		int insertAt = findImportInsertIndex(lines);
		return buildSourceWithInsertedImport(source, lines, insertAt, importLine, newline);
	}

	static String detectNewline(String source) {
		return source.contains("\r\n") ? "\r\n" : "\n";
	}

	int findImportInsertIndex(String[] lines) {
		int insertAt = 0;
		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith(PACKAGE_KEYWORD) || trimmed.startsWith(IMPORT_KEYWORD)) {
				insertAt = i + 1;
			} else if (trimmed.isEmpty() && insertAt == i) {
				insertAt = i + 1;
			} else if (!trimmed.isEmpty()) {
				break;
			}
		}
		return insertAt;
	}

	String buildSourceWithInsertedImport(
			String source,
			String[] lines,
			int insertAt,
			String importLine,
			String newline) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i == insertAt) {
				sb.append(importLine);
			}
			sb.append(lines[i]);
			if (i < lines.length - 1) {
				sb.append(newline);
			}
		}
		if (insertAt >= lines.length) {
			if (!source.endsWith(newline) && !source.isEmpty()) {
				sb.append(newline);
			}
			sb.append(importLine);
		}
		return sb.toString();
	}
}
