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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CompletionItemKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts Javadoc comments from *-sources.jar files for classpath classes.
 * This enables documentation display for library classes that are only
 * available as compiled bytecode on the classpath.
 *
 * <p>Results are cached per source JAR + class to avoid repeatedly reading
 * the same JAR entries.</p>
 */
public class JavadocResolver {
	private static final Logger logger = LoggerFactory.getLogger(JavadocResolver.class);
	private static final int MAX_REGEX_INPUT_CHARS = 200_000;

	/**
	 * Maximum number of source JAR entries cached.
	 */
	private static final int MAX_CACHE_SIZE = 500;

	/**
	 * Cache: sourcesJarPath -> (className -> list of parsed Javadoc entries).
	 * Bounded LRU map: eldest entries are evicted when the cache exceeds
	 * {@link #MAX_CACHE_SIZE} keys.
	 */
	private static final Map<String, Map<String, List<JavadocEntry>>> cache =
			Collections.synchronizedMap(new LinkedHashMap<String, Map<String, List<JavadocEntry>>>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Map<String, List<JavadocEntry>>> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			});

	// Matches a Javadoc comment block: /** ... */
	private static final Pattern JAVADOC_PATTERN = Pattern.compile("/\\*\\*([\\s\\S]{0,200000}?)\\*/");

	private static final int DECLARATION_SCAN_LIMIT = 200;
	private static final String KIND_METHOD = "method";
	private static final String KIND_CLASS = "class";
	private static final String KIND_FIELD = "field";
	private static final String MARKDOWN_SOFT_BREAK = "  \n";

	static class JavadocEntry {
		final String name;
		final String kind; // "method", "class", "field"
		final String paramSignature; // comma-separated FQCNs for methods, null otherwise
		final String javadoc; // the raw Javadoc content (already rendered to markdown)

		JavadocEntry(String name, String kind, String paramSignature, String javadoc) {
			this.name = name;
			this.kind = kind;
			this.paramSignature = paramSignature;
			this.javadoc = javadoc;
		}
	}

	private JavadocResolver() {
	}

	/**
	 * Attempt to resolve Javadoc from a sources JAR for a specific member.
	 *
	 * @param sourcesJar       path to the *-sources.jar
	 * @param declaringClass   fully qualified class name (e.g. "java.util.List")
	 * @param memberName       name of the method/field/class
	 * @param kind             the CompletionItemKind
	 * @param signature        comma-separated parameter type FQCNs for method matching
	 * @return rendered Markdown documentation, or null if not found
	 */
	public static String resolveFromSourcesJar(Path sourcesJar, String declaringClass,
			String memberName, CompletionItemKind kind, String signature) {
		try {
			String jarKey = sourcesJar.toAbsolutePath().toString();
			Map<String, List<JavadocEntry>> jarCache;
			synchronized (cache) {
				jarCache = cache.computeIfAbsent(jarKey, k -> new ConcurrentHashMap<>());
			}

			List<JavadocEntry> entries = jarCache.computeIfAbsent(declaringClass,
					className -> parseJavadocFromJar(sourcesJar, className));

			if (entries.isEmpty()) {
				return null;
			}

			return findMatchingEntry(entries, memberName, kind, signature);
		} catch (Exception e) {
			logger.debug("Failed to resolve Javadoc from {}: {}", sourcesJar, e.getMessage());
			return null;
		}
	}

	private static List<JavadocEntry> parseJavadocFromJar(Path sourcesJar, String className) {
		List<JavadocEntry> entries = new ArrayList<>();
		String entryPath = className.replace('.', '/') + ".java";

		try (JarFile jar = new JarFile(sourcesJar.toFile())) {
			JarEntry jarEntry = jar.getJarEntry(entryPath);
			if (jarEntry == null) {
				return entries;
			}
			String source = readSourceFromJarEntry(jar, jarEntry);
			collectJavadocEntries(source, entries);
		} catch (IOException e) {
			logger.debug("Failed to read source JAR {}: {}", sourcesJar, e.getMessage());
		}

		return entries;
	}

	private static String readSourceFromJarEntry(JarFile jar, JarEntry jarEntry) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(jar.getInputStream(jarEntry), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append("\n");
			}
			return sb.toString();
		}
	}

	private static void collectJavadocEntries(String source, List<JavadocEntry> entries) {
		if (source != null && source.length() > MAX_REGEX_INPUT_CHARS) {
			logger.debug("Skipping Javadoc parse for oversized source ({} chars)", source.length());
			return;
		}
		Matcher javadocMatcher = JAVADOC_PATTERN.matcher(source);
		while (javadocMatcher.find()) {
			JavadocEntry entry = parseEntryAfterJavadoc(source, javadocMatcher);
			if (entry != null) {
				entries.add(entry);
			}
		}
	}

	private static JavadocEntry parseEntryAfterJavadoc(String source, Matcher javadocMatcher) {
		String javadocContent = javadocMatcher.group(1);
		String rest = skipLeadingAnnotationLines(source.substring(javadocMatcher.end()));

		JavadocEntry methodEntry = parseMethodEntry(rest, javadocContent);
		if (methodEntry != null) {
			return methodEntry;
		}
		JavadocEntry classEntry = parseClassEntry(rest, javadocContent);
		if (classEntry != null) {
			return classEntry;
		}
		return parseFieldEntry(rest, javadocContent);
	}

	private static String skipLeadingAnnotationLines(String text) {
		int start = 0;
		while (true) {
			int probe = start;
			while (probe < text.length() && Character.isWhitespace(text.charAt(probe))) {
				probe++;
			}
			if (probe >= text.length() || text.charAt(probe) != '@') {
				return text.substring(start);
			}
			int lineEnd = text.indexOf('\n', probe);
			if (lineEnd < 0) {
				return "";
			}
			start = lineEnd + 1;
		}
	}

	private static JavadocEntry parseMethodEntry(String rest, String javadocContent) {
		String line = firstDeclarationLine(rest, DECLARATION_SCAN_LIMIT);
		if (line == null) {
			return null;
		}
		int openParen = line.indexOf('(');
		int closeParen = openParen >= 0 ? line.indexOf(')', openParen + 1) : -1;
		if (openParen <= 0 || closeParen <= openParen || containsClassKeyword(line)) {
			return null;
		}
		String methodName = identifierBefore(line, openParen);
		if (methodName == null || isControlKeyword(methodName)) {
			return null;
		}
		String params = line.substring(openParen + 1, closeParen).trim();
		String paramSig = parseParameterSignature(params);
		return createEntry(methodName, KIND_METHOD, paramSig, javadocContent);
	}

	private static JavadocEntry parseClassEntry(String rest, String javadocContent) {
		String line = firstDeclarationLine(rest, DECLARATION_SCAN_LIMIT);
		if (line == null) {
			return null;
		}
		String classDeclName = classNameFromDeclaration(line);
		if (classDeclName == null) {
			return null;
		}
		return createEntry(classDeclName, KIND_CLASS, null, javadocContent);
	}

	private static JavadocEntry parseFieldEntry(String rest, String javadocContent) {
		String line = firstDeclarationLine(rest, DECLARATION_SCAN_LIMIT);
		if (line == null || line.indexOf('(') >= 0) {
			return null;
		}
		int semicolon = line.indexOf(';');
		int equals = line.indexOf('=');
		int boundary = resolveFieldBoundary(semicolon, equals);
		if (boundary <= 0) {
			return null;
		}
		String fieldName = identifierBefore(line, boundary);
		if (fieldName == null) {
			return null;
		}
		return createEntry(fieldName, KIND_FIELD, null, javadocContent);
	}

	private static String firstDeclarationLine(String rest, int limit) {
		if (rest == null || rest.isEmpty()) {
			return null;
		}
		int max = Math.min(rest.length(), limit);
		String head = rest.substring(0, max);
		for (String line : head.split("\\R", -1)) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				return trimmed;
			}
		}
		return null;
	}

	private static String identifierBefore(String text, int indexExclusive) {
		int end = Math.min(indexExclusive - 1, text.length() - 1);
		while (end >= 0 && Character.isWhitespace(text.charAt(end))) {
			end--;
		}
		if (end < 0 || !Character.isJavaIdentifierPart(text.charAt(end))) {
			return null;
		}
		int start = end;
		while (start >= 0 && Character.isJavaIdentifierPart(text.charAt(start))) {
			start--;
		}
		return text.substring(start + 1, end + 1);
	}

	private static boolean containsClassKeyword(String line) {
		return line.contains(" class ") || line.contains(" interface ")
				|| line.contains(" enum ") || line.contains(" record ");
	}

	private static boolean isControlKeyword(String token) {
		return "if".equals(token) || "for".equals(token) || "while".equals(token)
				|| "switch".equals(token) || "catch".equals(token) || "new".equals(token);
	}

	private static String classNameFromDeclaration(String line) {
		String[] keys = { KIND_CLASS, "interface", "enum", "record" };
		for (String key : keys) {
			int at = line.indexOf(key + " ");
			if (at >= 0) {
				int start = at + key.length() + 1;
				while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
					start++;
				}
				int end = start;
				while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
					end++;
				}
				if (end > start) {
					return line.substring(start, end);
				}
			}
		}
		return null;
	}

	private static int resolveFieldBoundary(int semicolon, int equals) {
		if (semicolon < 0) {
			return equals;
		}
		if (equals < 0) {
			return semicolon;
		}
		return Math.min(semicolon, equals);
	}

	private static JavadocEntry createEntry(String name, String kind, String paramSignature, String javadocContent) {
		String markdown = javadocToMarkdown(javadocContent);
		if (markdown == null || markdown.isEmpty()) {
			return null;
		}
		return new JavadocEntry(name, kind, paramSignature, markdown);
	}

	/**
	 * Convert raw Javadoc comment content into Markdown.
	 * Handles common Javadoc tags such as param, return, throws, see, since, and deprecated.
	 */
	static String javadocToMarkdown(String javadocContent) {
		if (javadocContent == null) {
			return null;
		}
		JavadocParseState state = new JavadocParseState();
		parseJavadocLines(javadocContent, state);
		return buildMarkdown(state).toString().trim();
	}

	private static void parseJavadocLines(String javadocContent, JavadocParseState state) {
		String[] lines = javadocContent.split("\n");
		for (String rawLine : lines) {
			String line = normalizeJavadocLine(rawLine);
			if (!handleTagLineIfPresent(line, state)) {
				handleContinuationLine(line, state);
			}
		}
		flushTag(state.currentTagType, state.currentTag,
				state.paramDocs, state.returnDoc, state.throwsDocs, state.seeDocs);
	}

	private static String normalizeJavadocLine(String rawLine) {
		return rawLine.replaceFirst("^\\s*\\*\\s?", "").trim();
	}

	private static boolean handleTagLineIfPresent(String line, JavadocParseState state) {
		if (line.startsWith("@param ")) {
			startTag(state, "param", line.substring(7).trim());
			return true;
		}
		if (line.startsWith("@return ") || line.equals("@return")) {
			startTag(state, "return", line.length() > 8 ? line.substring(8).trim() : "");
			return true;
		}
		if (line.startsWith("@throws ") || line.startsWith("@exception ")) {
			String content = line.startsWith("@throws ") ? line.substring(8) : line.substring(11);
			startTag(state, "throws", content.trim());
			return true;
		}
		if (line.startsWith("@see ")) {
			startTag(state, "see", line.substring(5).trim());
			return true;
		}
		if (line.startsWith("@since ")) {
			flushAndResetCurrentTag(state);
			state.sinceDoc = line.substring(7).trim();
			return true;
		}
		if (line.startsWith("@deprecated")) {
			flushAndResetCurrentTag(state);
			state.deprecatedDoc = line.length() > 12 ? line.substring(12).trim() : "";
			return true;
		}
		if (line.startsWith("@")) {
			flushAndResetCurrentTag(state);
			return true;
		}
		return false;
	}

	private static void startTag(JavadocParseState state, String tagType, String content) {
		flushAndResetCurrentTag(state);
		state.currentTagType = tagType;
		state.currentTag = new StringBuilder(content);
	}

	private static void flushAndResetCurrentTag(JavadocParseState state) {
		flushTag(state.currentTagType, state.currentTag,
				state.paramDocs, state.returnDoc, state.throwsDocs, state.seeDocs);
		state.currentTagType = null;
		state.currentTag = null;
	}

	private static void handleContinuationLine(String line, JavadocParseState state) {
		if (state.currentTag != null) {
			state.currentTag.append(" ").append(line);
			return;
		}
		if (state.description.length() > 0 && !line.isEmpty()) {
			state.description.append("\n");
		}
		state.description.append(line);
	}

	private static StringBuilder buildMarkdown(JavadocParseState state) {
		StringBuilder md = new StringBuilder();
		appendDeprecated(md, state.deprecatedDoc);
		appendDescription(md, state.description.toString().trim());
		appendParamDocs(md, state.paramDocs);
		appendReturnDoc(md, state.returnDoc[0]);
		appendThrowsDocs(md, state.throwsDocs);
		appendSinceDoc(md, state.sinceDoc);
		appendSeeDocs(md, state.seeDocs);
		return md;
	}

	private static void appendDeprecated(StringBuilder md, String deprecatedDoc) {
		if (deprecatedDoc == null) {
			return;
		}
		md.append("**@deprecated**");
		if (!deprecatedDoc.isEmpty()) {
			md.append(" ").append(htmlToMarkdown(deprecatedDoc));
		}
		md.append("\n\n");
	}

	private static void appendDescription(StringBuilder md, String description) {
		if (!description.isEmpty()) {
			md.append(htmlToMarkdown(description));
		}
	}

	private static void appendParamDocs(StringBuilder md, List<String> paramDocs) {
		if (paramDocs.isEmpty()) {
			return;
		}
		md.append("\n\n");
		for (String param : paramDocs) {
			appendNamedTag(md, "@param", param);
		}
	}

	private static void appendReturnDoc(StringBuilder md, String returnDoc) {
		if (returnDoc != null) {
			md.append("\n**@return** ").append(htmlToMarkdown(returnDoc)).append("\n");
		}
	}

	private static void appendThrowsDocs(StringBuilder md, List<String> throwsDocs) {
		if (throwsDocs.isEmpty()) {
			return;
		}
		md.append("\n");
		for (String t : throwsDocs) {
			appendNamedTag(md, "@throws", t);
		}
	}

	private static void appendSinceDoc(StringBuilder md, String sinceDoc) {
		if (sinceDoc != null) {
			md.append("\n**@since** ").append(sinceDoc).append("\n");
		}
	}

	private static void appendSeeDocs(StringBuilder md, List<String> seeDocs) {
		if (seeDocs.isEmpty()) {
			return;
		}
		md.append("\n");
		for (String see : seeDocs) {
			md.append("**@see** `").append(see).append("`").append(MARKDOWN_SOFT_BREAK);
		}
	}

	private static void appendNamedTag(StringBuilder md, String tag, String value) {
		int space = value.indexOf(' ');
		if (space > 0) {
			String name = value.substring(0, space);
			String desc = value.substring(space + 1).trim();
			md.append("**").append(tag).append("** `").append(name).append("` — ")
					.append(htmlToMarkdown(desc)).append(MARKDOWN_SOFT_BREAK);
		} else {
			md.append("**").append(tag).append("** `").append(value).append("`")
					.append(MARKDOWN_SOFT_BREAK);
		}
	}

	private static final class JavadocParseState {
		private final StringBuilder description = new StringBuilder();
		private final List<String> paramDocs = new ArrayList<>();
		private final String[] returnDoc = { null };
		private final List<String> throwsDocs = new ArrayList<>();
		private final List<String> seeDocs = new ArrayList<>();
		private String sinceDoc;
		private String deprecatedDoc;
		private StringBuilder currentTag;
		private String currentTagType;
	}

	/**
	 * Parse a Java parameter list string into a simplified signature.
	 * Input: "String name, int count"
	 * Output: "java.lang.String,int"
	 */
	private static String parseParameterSignature(String params) {
		if (params == null || params.trim().isEmpty()) {
			return "";
		}
		if (params.length() > MAX_REGEX_INPUT_CHARS) {
			params = params.substring(0, MAX_REGEX_INPUT_CHARS);
		}
		StringBuilder sig = new StringBuilder();
		String[] parts = params.split(",");
		for (int i = 0; i < parts.length; i++) {
			String param = parts[i].trim();
			param = stripLeadingAnnotations(param);
			param = removeStandaloneWord(param, "final");
			// Extract type (everything before the last word which is the param name)
			param = param.trim();
			int lastSpace = param.lastIndexOf(' ');
			if (lastSpace > 0) {
				String type = param.substring(0, lastSpace).trim();
				// Remove generics for matching
				type = stripGenericArguments(type);
				// Remove array brackets from type
				type = type.replace("[]", "");
				// Handle varargs
				type = type.replace("...", "");
				type = type.trim();
				if (i > 0) {
					sig.append(",");
				}
				sig.append(type);
			}
		}
		return sig.toString();
	}

	/**
	 * Find a matching JavadocEntry by name, kind, and optionally signature.
	 */
	private static String findMatchingEntry(List<JavadocEntry> entries, String memberName,
			CompletionItemKind kind, String signature) {
		String targetKind = targetKindForCompletion(kind);
		String exactMatch = findByNameKindAndSignature(entries, memberName, targetKind, signature);
		if (exactMatch != null) {
			return exactMatch;
		}
		String kindMatch = findByNameAndKind(entries, memberName, targetKind);
		if (kindMatch != null) {
			return kindMatch;
		}
		return findByName(entries, memberName);
	}

	private static String targetKindForCompletion(CompletionItemKind kind) {
		if (kind == CompletionItemKind.Method) {
			return KIND_METHOD;
		}
		if (kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
				|| kind == CompletionItemKind.Enum) {
			return KIND_CLASS;
		}
		if (kind == CompletionItemKind.Property || kind == CompletionItemKind.Field) {
			return KIND_FIELD;
		}
		return null;
	}

	private static String findByNameKindAndSignature(List<JavadocEntry> entries, String memberName,
			String targetKind, String signature) {
		if (!KIND_METHOD.equals(targetKind) || signature == null) {
			return null;
		}
		for (JavadocEntry entry : entries) {
			if (entry.name.equals(memberName) && entry.kind.equals(targetKind)
					&& signatureMatches(entry.paramSignature, signature)) {
				return entry.javadoc;
			}
		}
		return null;
	}

	private static String findByNameAndKind(List<JavadocEntry> entries, String memberName, String targetKind) {
		if (targetKind == null) {
			return null;
		}
		for (JavadocEntry entry : entries) {
			if (entry.name.equals(memberName) && entry.kind.equals(targetKind)) {
				return entry.javadoc;
			}
		}
		return null;
	}

	private static String findByName(List<JavadocEntry> entries, String memberName) {
		for (JavadocEntry entry : entries) {
			if (entry.name.equals(memberName)) {
				return entry.javadoc;
			}
		}
		return null;
	}

	/**
	 * Check if two parameter signatures match. Uses simple name matching
	 * since source JARs may use simple names while the AST uses FQCNs.
	 */
	private static boolean signatureMatches(String sourceJarSig, String astSig) {
		if (sourceJarSig == null && astSig == null) {
			return true;
		}
		if (sourceJarSig == null || astSig == null) {
			return false;
		}
		if (sourceJarSig.equals(astSig)) {
			return true;
		}
		// Compare by simple type names (last component after '.')
		String[] srcTypes = sourceJarSig.split(",");
		String[] astTypes = astSig.split(",");
		if (srcTypes.length != astTypes.length) {
			return false;
		}
		for (int i = 0; i < srcTypes.length; i++) {
			String srcSimple = simpleName(srcTypes[i].trim());
			String astSimple = simpleName(astTypes[i].trim());
			if (!srcSimple.equals(astSimple)) {
				return false;
			}
		}
		return true;
	}

	private static String simpleName(String fqcn) {
		int dot = fqcn.lastIndexOf('.');
		return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
	}

	private static void flushTag(String tagType, StringBuilder tagContent,
			List<String> paramDocs, String[] returnDoc,
			List<String> throwsDocs, List<String> seeDocs) {
		if (tagType == null || tagContent == null) {
			return;
		}
		String content = tagContent.toString().trim();
		switch (tagType) {
			case "param":
				paramDocs.add(content);
				break;
			case "return":
				returnDoc[0] = content;
				break;
			case "throws":
				throwsDocs.add(content);
				break;
			case "see":
				seeDocs.add(content);
				break;
			default:
				break;
		}
	}

	/**
	 * Basic HTML to Markdown conversion for Javadoc content.
	 */
	static String htmlToMarkdown(String html) {
		if (html == null) {
			return "";
		}
		if (html.length() > MAX_REGEX_INPUT_CHARS) {
			html = html.substring(0, MAX_REGEX_INPUT_CHARS);
		}
		html = stripHtmlTagAttributes(html);
		html = html.replace("<pre>", "\n\n```\n");
		html = html.replace("</pre>", "\n```\n\n");
		html = html.replace("<code>", "`");
		html = html.replace("</code>", "`");
		html = replaceSimpleTag(html, "em", "_");
		html = replaceSimpleTag(html, "i", "_");
		html = replaceSimpleTag(html, "strong", "**");
		html = replaceSimpleTag(html, "b", "**");
		html = replaceBrTags(html, "  \n");
		html = html.replace("<p>", "\n\n");
		html = stripHtmlTags(html);
		html = replaceInlineTag(html, "@code");
		html = replaceInlineTag(html, "@link");
		html = replaceInlineTag(html, "@linkplain");
		html = replaceInlineTag(html, "@value");
		return html;
	}

	private static String stripLeadingAnnotations(String text) {
		String value = text == null ? "" : text.trim();
		while (value.startsWith("@")) {
			int i = 1;
			while (i < value.length() && Character.isJavaIdentifierPart(value.charAt(i))) {
				i++;
			}
			while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
				i++;
			}
			value = value.substring(i);
		}
		return value;
	}

	private static String removeStandaloneWord(String text, String word) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		StringBuilder sb = new StringBuilder();
		int from = 0;
		while (from < text.length()) {
			int at = text.indexOf(word, from);
			if (at < 0) {
				sb.append(text.substring(from));
				break;
			}
			int before = at - 1;
			int after = at + word.length();
			boolean left = before < 0 || !Character.isJavaIdentifierPart(text.charAt(before));
			boolean right = after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
			if (left && right) {
				sb.append(text, from, at);
				from = after;
				while (from < text.length() && Character.isWhitespace(text.charAt(from))) {
					from++;
				}
			} else {
				sb.append(text, from, after);
				from = after;
			}
		}
		return sb.toString();
	}

	private static String stripGenericArguments(String type) {
		StringBuilder sb = new StringBuilder(type.length());
		int depth = 0;
		for (int i = 0; i < type.length(); i++) {
			char ch = type.charAt(i);
			if (ch == '<') {
				depth++;
			} else if (ch == '>' && depth > 0) {
				depth--;
			} else if (depth == 0) {
				sb.append(ch);
			}
		}
		return sb.toString();
	}

	private static String replaceSimpleTag(String text, String tag, String replacement) {
		return text.replace("<" + tag + ">", replacement)
				.replace("</" + tag + ">", replacement);
	}

	private static String replaceBrTags(String text, String replacement) {
		return text.replace("<br>", replacement)
				.replace("<br/>", replacement)
				.replace("<br />", replacement);
	}

	private static String stripHtmlTagAttributes(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		int cursor = 0;
		while (cursor < text.length()) {
			int open = text.indexOf('<', cursor);
			if (open < 0) {
				sb.append(text.substring(cursor));
				cursor = text.length();
			} else {
				sb.append(text, cursor, open);
				int close = text.indexOf('>', open + 1);
				if (close < 0) {
					sb.append(text.substring(open));
					cursor = text.length();
				} else {
					appendNormalizedTag(sb, text, open, close);
					cursor = close + 1;
				}
			}
		}
		return sb.toString();
	}

	private static void appendNormalizedTag(StringBuilder sb, String text, int open, int close) {
		String body = text.substring(open + 1, close).trim();
		boolean closing = body.startsWith("/");
		if (closing) {
			body = body.substring(1).trim();
		}
		int p = 0;
		while (p < body.length() && Character.isLetterOrDigit(body.charAt(p))) {
			p++;
		}
		if (p == 0) {
			sb.append(text, open, close + 1);
			return;
		}
		sb.append('<');
		if (closing) {
			sb.append('/');
		}
		sb.append(body, 0, p).append('>');
	}

	private static String stripHtmlTags(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		boolean inTag = false;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '<') {
				inTag = true;
			} else if (inTag && ch == '>') {
				inTag = false;
			} else if (!inTag) {
				sb.append(ch);
			}
		}
		return sb.toString();
	}

	private static String replaceInlineTag(String text, String tagName) {
		String marker = "{" + tagName;
		StringBuilder sb = new StringBuilder(text.length());
		int from = 0;
		while (from < text.length()) {
			int open = text.indexOf(marker, from);
			if (open < 0) {
				sb.append(text.substring(from));
				from = text.length();
			} else {
				int close = text.indexOf('}', open + marker.length());
				if (close < 0) {
					sb.append(text.substring(from));
					from = text.length();
				} else {
					sb.append(text, from, open);
					String inner = text.substring(open + marker.length(), close).trim();
					sb.append('`').append(inner).append('`');
					from = close + 1;
				}
			}
		}
		return sb.toString();
	}

	/**
	 * Clear the cache. Should be called when the classpath changes.
	 */
	public static void clearCache() {
		cache.clear();
	}

	/**
	 * Estimates the total heap memory consumed by the static Javadoc cache.
	 * Each JavadocEntry holds 4 String fields; we estimate ~400 bytes per
	 * entry (including map overhead and String char arrays).
	 *
	 * @return estimated bytes consumed by the Javadoc cache
	 */
	public static long estimateCacheMemoryBytes() {
		long total = 0;
		synchronized (cache) {
			for (Map<String, List<JavadocEntry>> classMap : cache.values()) {
				if (classMap != null) {
					for (List<JavadocEntry> entries : classMap.values()) {
						if (entries != null) {
							total += (long) entries.size() * 400;
						}
					}
				}
			}
		}
		return total;
	}

	/**
	 * Returns the number of source JARs currently cached.
	 */
	public static int getCacheSize() {
		synchronized (cache) {
			return cache.size();
		}
	}
}
