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

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

/**
 * Handles LSP definition requests, including fallback resolution from
 * Java virtual-source URIs.
 * Extracted from {@link GroovyServices} for single-responsibility.
 */
class DefinitionHandler {
	private static final String IMPORT_KEYWORD = "import ";
	private static final String STATIC_KEYWORD = "static ";
	private static final String PACKAGE_KEYWORD = "package ";

	private final FileContentsTracker fileContentsTracker;

	DefinitionHandler(FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
	}

	@SuppressWarnings("java:S1452")
	Either<List<? extends Location>, List<? extends LocationLink>> toLspDefinitionResult(
			Either<List<Location>, List<LocationLink>> result) {
		if (result == null) {
			return Either.forLeft(Collections.emptyList());
		}
		if (result.isLeft()) {
			return Either.forLeft(result.getLeft());
		}
		return Either.forRight(result.getRight());
	}

	boolean isEmptyDefinitionResult(Either<List<? extends Location>, List<? extends LocationLink>> result) {
		if (result == null) {
			return true;
		}
		if (result.isLeft()) {
			List<? extends Location> left = result.getLeft();
			return left == null || left.isEmpty();
		}
		List<? extends LocationLink> right = result.getRight();
		return right == null || right.isEmpty();
	}

	@SuppressWarnings("java:S1452")
	Either<List<? extends Location>, List<? extends LocationLink>> resolveDefinitionFromJavaVirtualSource(
			URI uri,
			Position position,
			JavaSourceLocator javaSourceLocator) {
		if (uri == null || position == null || javaSourceLocator == null || !isLikelyJavaVirtualUri(uri)) {
			return Either.forLeft(Collections.emptyList());
		}

		String contents = fileContentsTracker.getContents(uri);
		if (contents == null || contents.isBlank()) {
			return Either.forLeft(Collections.emptyList());
		}

		String symbol = symbolAtPosition(contents, position);
		if (symbol == null || symbol.isBlank()) {
			return Either.forLeft(Collections.emptyList());
		}

		List<String> candidates = javaSourceLocator.findClassNamesBySimpleName(symbol);
		if (candidates == null || candidates.isEmpty()) {
			return Either.forLeft(Collections.emptyList());
		}

		String preferred = choosePreferredCandidate(symbol, contents, candidates);
		if (preferred != null) {
			Location loc = javaSourceLocator.findLocationForClass(preferred);
			if (loc != null) {
				return Either.forLeft(Collections.singletonList(loc));
			}
		}

		for (String candidate : candidates) {
			Location loc = javaSourceLocator.findLocationForClass(candidate);
			if (loc != null) {
				return Either.forLeft(Collections.singletonList(loc));
			}
		}

		return Either.forLeft(Collections.emptyList());
	}

	boolean isLikelyJavaVirtualUri(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null) {
			return false;
		}
		String normalized = scheme.toLowerCase();
		if (!"jar".equals(normalized) && !"jrt".equals(normalized) && !"decompiled".equals(normalized)) {
			return false;
		}
		String asText = uri.toString().toLowerCase();
		return asText.endsWith(".java") || asText.contains(".java!");
	}

	String symbolAtPosition(String contents, Position position) {
		String[] lines = contents.split("\\R", -1);
		if (position.getLine() < 0 || position.getLine() >= lines.length) {
			return null;
		}
		String line = lines[position.getLine()];
		if (line.isEmpty()) {
			return null;
		}

		int character = Math.max(0, Math.min(position.getCharacter(), line.length()));
		int index = character;
		if (index >= line.length()) {
			index = line.length() - 1;
		}
		if (index < 0) {
			return null;
		}

		if (!Character.isJavaIdentifierPart(line.charAt(index)) && index > 0
				&& Character.isJavaIdentifierPart(line.charAt(index - 1))) {
			index--;
		}
		if (!Character.isJavaIdentifierPart(line.charAt(index))) {
			return null;
		}

		int start = index;
		while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
			start--;
		}
		int end = index + 1;
		while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
			end++;
		}
		return line.substring(start, end);
	}

	String choosePreferredCandidate(String simpleName, String contents, List<String> candidates) {
		String imported = importedTypeForSimpleName(simpleName, contents);
		if (imported != null && candidates.contains(imported)) {
			return imported;
		}

		String packageName = packageName(contents);
		if (packageName != null && !packageName.isBlank()) {
			List<String> samePackage = new ArrayList<>();
			for (String candidate : candidates) {
				if (candidate.startsWith(packageName + ".")) {
					samePackage.add(candidate);
				}
			}
			if (samePackage.size() == 1) {
				return samePackage.get(0);
			}
		}

		if (candidates.size() == 1) {
			return candidates.get(0);
		}

		return null;
	}

	String importedTypeForSimpleName(String simpleName, String contents) {
		for (String line : contents.split("\\R", -1)) {
			String imported = parseImportedType(line);
			if (isDirectImportForSimpleName(imported, simpleName)) {
				return imported;
			}
		}
		return null;
	}

	String parseImportedType(String line) {
		if (line == null) {
			return null;
		}
		String trimmed = line.trim();
		if (!trimmed.startsWith(IMPORT_KEYWORD)) {
			return null;
		}
		String imported = trimmed.substring(IMPORT_KEYWORD.length()).trim();
		if (imported.startsWith(STATIC_KEYWORD)) {
			imported = imported.substring(STATIC_KEYWORD.length()).trim();
		}
		if (imported.endsWith(";")) {
			imported = imported.substring(0, imported.length() - 1).trim();
		}
		if (imported.isEmpty()) {
			return null;
		}
		return imported;
	}

	boolean isDirectImportForSimpleName(String imported, String simpleName) {
		if (imported == null || imported.endsWith(".*")) {
			return false;
		}
		int lastDot = imported.lastIndexOf('.');
		return lastDot >= 0 && imported.substring(lastDot + 1).equals(simpleName);
	}

	String packageName(String contents) {
		for (String line : contents.split("\\R", -1)) {
			String pkg = parsePackageNameLine(line);
			if (pkg != null) {
				return pkg;
			}
		}
		return null;
	}

	String parsePackageNameLine(String line) {
		if (line == null) {
			return null;
		}
		String trimmed = line.trim();
		if (!trimmed.startsWith(PACKAGE_KEYWORD)) {
			return null;
		}
		String pkg = trimmed.substring(PACKAGE_KEYWORD.length()).trim();
		if (pkg.endsWith(";")) {
			pkg = pkg.substring(0, pkg.length() - 1).trim();
		}
		return pkg.isEmpty() ? null : pkg;
	}
}
