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
	private static final Pattern JAVADOC_PATTERN = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);

	// Matches a method declaration after a Javadoc block
	private static final Pattern METHOD_PATTERN = Pattern.compile(
			"^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)*"
			+ "(?:<[^>]+>\\s+)?"
			+ "(?:[\\w\\[\\]<>,?\\s.]+)\\s+"
			+ "(\\w+)\\s*\\(([^)]*)\\)",
			Pattern.MULTILINE);

	// Matches a class/interface/enum declaration
	private static final Pattern CLASS_PATTERN = Pattern.compile(
			"^\\s*(?:(?:public|protected|private|static|final|abstract)\\s+)*"
			+ "(?:class|interface|enum|record)\\s+(\\w+)",
			Pattern.MULTILINE);

	// Matches a field declaration
	private static final Pattern FIELD_PATTERN = Pattern.compile(
			"^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*"
			+ "(?:[\\w\\[\\]<>,?\\s.]+)\\s+(\\w+)\\s*[;=]",
			Pattern.MULTILINE);

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

			List<JavadocEntry> entries = jarCache.computeIfAbsent(declaringClass, className -> {
				return parseJavadocFromJar(sourcesJar, className);
			});

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

			String source;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(jar.getInputStream(jarEntry), StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line).append("\n");
				}
				source = sb.toString();
			}

			// Find all Javadoc comments and the declaration following each one
			Matcher javadocMatcher = JAVADOC_PATTERN.matcher(source);
			while (javadocMatcher.find()) {
				String javadocContent = javadocMatcher.group(1);
				int endPos = javadocMatcher.end();

				// Look at what follows the Javadoc comment (skip whitespace/annotations)
				String rest = source.substring(endPos);
				// Skip annotations
				rest = rest.replaceFirst("^(\\s*@\\w+[^\\n]*\\n)*", "");

				// Try to match a method declaration
				Matcher methodMatcher = METHOD_PATTERN.matcher(rest);
				if (methodMatcher.find() && methodMatcher.start() < 200) {
					String methodName = methodMatcher.group(1);
					String params = methodMatcher.group(2).trim();
					String paramSig = parseParameterSignature(params);
					String markdown = javadocToMarkdown(javadocContent);
					if (markdown != null && !markdown.isEmpty()) {
						entries.add(new JavadocEntry(methodName, "method", paramSig, markdown));
					}
					continue;
				}

				// Try to match a class declaration
				Matcher classMatcher = CLASS_PATTERN.matcher(rest);
				if (classMatcher.find() && classMatcher.start() < 200) {
					String classDeclName = classMatcher.group(1);
					String markdown = javadocToMarkdown(javadocContent);
					if (markdown != null && !markdown.isEmpty()) {
						entries.add(new JavadocEntry(classDeclName, "class", null, markdown));
					}
					continue;
				}

				// Try to match a field declaration
				Matcher fieldMatcher = FIELD_PATTERN.matcher(rest);
				if (fieldMatcher.find() && fieldMatcher.start() < 200) {
					String fieldName = fieldMatcher.group(1);
					String markdown = javadocToMarkdown(javadocContent);
					if (markdown != null && !markdown.isEmpty()) {
						entries.add(new JavadocEntry(fieldName, "field", null, markdown));
					}
				}
			}
		} catch (IOException e) {
			logger.debug("Failed to read source JAR {}: {}", sourcesJar, e.getMessage());
		}

		return entries;
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
		StringBuilder sig = new StringBuilder();
		String[] parts = params.split(",");
		for (int i = 0; i < parts.length; i++) {
			String param = parts[i].trim();
			// Remove annotations
			param = param.replaceAll("@\\w+\\s*", "");
			// Remove final keyword
			param = param.replaceAll("\\bfinal\\s+", "");
			// Extract type (everything before the last word which is the param name)
			param = param.trim();
			int lastSpace = param.lastIndexOf(' ');
			if (lastSpace > 0) {
				String type = param.substring(0, lastSpace).trim();
				// Remove generics for matching
				type = type.replaceAll("<[^>]*>", "");
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
		String targetKind;
		if (kind == CompletionItemKind.Method) {
			targetKind = "method";
		} else if (kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface
				|| kind == CompletionItemKind.Enum) {
			targetKind = "class";
		} else if (kind == CompletionItemKind.Property || kind == CompletionItemKind.Field) {
			targetKind = "field";
		} else {
			targetKind = null;
		}

		// First pass: try exact match with signature
		if (targetKind != null && "method".equals(targetKind) && signature != null) {
			for (JavadocEntry entry : entries) {
				if (entry.name.equals(memberName) && entry.kind.equals(targetKind)
						&& signatureMatches(entry.paramSignature, signature)) {
					return entry.javadoc;
				}
			}
		}

		// Second pass: match by name and kind
		if (targetKind != null) {
			for (JavadocEntry entry : entries) {
				if (entry.name.equals(memberName) && entry.kind.equals(targetKind)) {
					return entry.javadoc;
				}
			}
		}

		// Final fallback: match by name only
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

	/**
	 * Convert raw Javadoc comment content into Markdown.
	 * Handles @param, @return, @throws, @see, @since, @deprecated, etc.
	 */
	static String javadocToMarkdown(String javadocContent) {
		if (javadocContent == null) {
			return null;
		}

		String[] lines = javadocContent.split("\n");
		StringBuilder description = new StringBuilder();
		List<String> paramDocs = new ArrayList<>();
		String[] returnDoc = { null };
		List<String> throwsDocs = new ArrayList<>();
		List<String> seeDocs = new ArrayList<>();
		String sinceDoc = null;
		String deprecatedDoc = null;

		StringBuilder currentTag = null;
		String currentTagType = null;

		for (String rawLine : lines) {
			// Strip leading whitespace and * characters
			String line = rawLine.replaceFirst("^\\s*\\*\\s?", "").trim();

			if (line.startsWith("@param ")) {
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				currentTagType = "param";
				currentTag = new StringBuilder(line.substring(7).trim());
			} else if (line.startsWith("@return ") || line.equals("@return")) {
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				currentTagType = "return";
				currentTag = new StringBuilder(line.length() > 8 ? line.substring(8).trim() : "");
			} else if (line.startsWith("@throws ") || line.startsWith("@exception ")) {
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				currentTagType = "throws";
				String content = line.startsWith("@throws ") ? line.substring(8) : line.substring(11);
				currentTag = new StringBuilder(content.trim());
			} else if (line.startsWith("@see ")) {
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				currentTagType = "see";
				currentTag = new StringBuilder(line.substring(5).trim());
			} else if (line.startsWith("@since ")) {
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				sinceDoc = line.substring(7).trim();
				currentTagType = null;
				currentTag = null;
			} else if (line.startsWith("@deprecated")) {
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				deprecatedDoc = line.length() > 12 ? line.substring(12).trim() : "";
				currentTagType = null;
				currentTag = null;
			} else if (line.startsWith("@")) {
				// Other tag, just flush current
				flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);
				currentTagType = null;
				currentTag = null;
			} else {
				// Continuation line
				if (currentTag != null) {
					currentTag.append(" ").append(line);
				} else {
					if (description.length() > 0 && !line.isEmpty()) {
						description.append("\n");
					}
					description.append(line);
				}
			}
		}
		flushTag(currentTagType, currentTag, description, paramDocs, returnDoc, throwsDocs, seeDocs);

		// Build the final Markdown
		StringBuilder md = new StringBuilder();

		if (deprecatedDoc != null) {
			md.append("**@deprecated**");
			if (!deprecatedDoc.isEmpty()) {
				md.append(" ").append(htmlToMarkdown(deprecatedDoc));
			}
			md.append("\n\n");
		}

		String desc = description.toString().trim();
		if (!desc.isEmpty()) {
			md.append(htmlToMarkdown(desc));
		}

		if (!paramDocs.isEmpty()) {
			md.append("\n\n");
			for (String param : paramDocs) {
				// param format: "name description"
				int space = param.indexOf(' ');
				if (space > 0) {
					String pName = param.substring(0, space);
					String pDesc = param.substring(space + 1).trim();
					md.append("**@param** `").append(pName).append("` — ").append(htmlToMarkdown(pDesc)).append("  \n");
				} else {
					md.append("**@param** `").append(param).append("`  \n");
				}
			}
		}

		if (returnDoc[0] != null) {
			md.append("\n**@return** ").append(htmlToMarkdown(returnDoc[0])).append("\n");
		}

		if (!throwsDocs.isEmpty()) {
			md.append("\n");
			for (String t : throwsDocs) {
				int space = t.indexOf(' ');
				if (space > 0) {
					String tName = t.substring(0, space);
					String tDesc = t.substring(space + 1).trim();
					md.append("**@throws** `").append(tName).append("` — ").append(htmlToMarkdown(tDesc)).append("  \n");
				} else {
					md.append("**@throws** `").append(t).append("`  \n");
				}
			}
		}

		if (sinceDoc != null) {
			md.append("\n**@since** ").append(sinceDoc).append("\n");
		}

		if (!seeDocs.isEmpty()) {
			md.append("\n");
			for (String see : seeDocs) {
				md.append("**@see** `").append(see).append("`  \n");
			}
		}

		return md.toString().trim();
	}

	private static void flushTag(String tagType, StringBuilder tagContent,
			StringBuilder description, List<String> paramDocs, String[] returnDoc,
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
		}
	}

	/**
	 * Basic HTML to Markdown conversion for Javadoc content.
	 */
	static String htmlToMarkdown(String html) {
		if (html == null) {
			return "";
		}
		// Remove all attributes from HTML tags
		html = html.replaceAll("<(\\w+)(?:\\s+[^>]*)?>", "<$1>");
		html = html.replaceAll("<pre>", "\n\n```\n");
		html = html.replaceAll("</pre>", "\n```\n\n");
		html = html.replaceAll("<code>", "`");
		html = html.replaceAll("</code>", "`");
		html = html.replaceAll("</?(em|i)>", "_");
		html = html.replaceAll("</?(strong|b)>", "**");
		html = html.replaceAll("<br\\s*/?>", "  \n");
		html = html.replaceAll("<p>", "\n\n");
		html = html.replaceAll("</?[a-zA-Z][^>]*>", "");
		// Handle {@code ...} inline tags
		html = html.replaceAll("\\{@code\\s+([^}]*)\\}", "`$1`");
		// Handle {@link ...} inline tags
		html = html.replaceAll("\\{@link(?:plain)?\\s+([^}]*)\\}", "`$1`");
		// Handle {@value ...}
		html = html.replaceAll("\\{@value\\s+([^}]*)\\}", "`$1`");
		return html;
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
