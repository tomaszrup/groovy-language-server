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
package com.tomaszrup.groovyls.compiler.util;

import java.util.ArrayList;
import java.util.List;

import groovy.lang.groovydoc.Groovydoc;

public class GroovydocUtils {
	private static final String MARKDOWN_LINE_BREAK = "  \n";
	private static final int MAX_REGEX_LINE_CHARS = 20_000;

	private GroovydocUtils() {
	}

	public static String groovydocToMarkdownDescription(Groovydoc groovydoc) {
		if (groovydoc == null || !groovydoc.isPresent()) {
			return null;
		}
		GroovydocParseState state = new GroovydocParseState();
		parseGroovydocLines(groovydoc.getContent(), state);
		String result = buildMarkdown(state).toString().trim();
		return result.isEmpty() ? null : result;
	}

	private static void parseGroovydocLines(String content, GroovydocParseState state) {
		String[] lines = content.split("\n");
		for (int i = 0; i < lines.length; i++) {
			String line = normalizeGroovydocLine(lines[i], i, lines.length);
			if (!handleTagLine(line, state)) {
				handleDescriptionOrContinuation(line, state);
			}
		}
		flushCurrentTag(state);
	}

	private static String normalizeGroovydocLine(String line, int index, int totalLines) {
		if (index == 0) {
			int startLen = Math.min(line.length(), 3);
			line = line.substring(startLen);
		}
		if (index == totalLines - 1) {
			int endIndex = line.indexOf("*/");
			if (endIndex != -1) {
				line = line.substring(0, endIndex);
			}
		}
		if (index > 0) {
			int star = line.indexOf("*");
			if (star > -1) {
				line = line.substring(star + 1);
			}
		}
		return line.trim();
	}

	private static boolean handleTagLine(String line, GroovydocParseState state) {
		if (line.startsWith("@param ")) {
			startTag(state, "param", line.substring(7).trim());
			return true;
		}
		if (line.startsWith("@return ") || line.equals("@return")) {
			startTag(state, "return", line.length() > 8 ? line.substring(8).trim() : "");
			return true;
		}
		if (line.startsWith("@throws ") || line.startsWith("@exception ")) {
			String tagContent = line.startsWith("@throws ") ? line.substring(8) : line.substring(11);
			startTag(state, "throws", tagContent.trim());
			return true;
		}
		if (line.startsWith("@see ")) {
			startTag(state, "see", line.substring(5).trim());
			return true;
		}
		if (line.startsWith("@since ")) {
			flushCurrentTag(state);
			state.sinceDoc = line.substring(7).trim();
			return true;
		}
		if (line.startsWith("@deprecated")) {
			flushCurrentTag(state);
			state.deprecatedDoc = line.length() > 12 ? line.substring(12).trim() : "";
			return true;
		}
		if (line.startsWith("@")) {
			flushCurrentTag(state);
			return true;
		}
		return false;
	}

	private static void startTag(GroovydocParseState state, String tagType, String content) {
		flushCurrentTag(state);
		state.currentTagType = tagType;
		state.currentTagContent = new StringBuilder(content);
	}

	private static void flushCurrentTag(GroovydocParseState state) {
		flushTag(state.currentTagType, state.currentTagContent,
				state.paramDocs, state.throwsDocs, state.seeDocs, state.returnDocs);
		state.currentTagType = null;
		state.currentTagContent = null;
	}

	private static void handleDescriptionOrContinuation(String line, GroovydocParseState state) {
		if (state.currentTagType != null && state.currentTagContent != null) {
			if (!line.isEmpty()) {
				state.currentTagContent.append(" ").append(line);
			}
			return;
		}
		String reformatted = reformatLine(line);
		if (!reformatted.isEmpty()) {
			if (state.descriptionBuilder.length() > 0) {
				state.descriptionBuilder.append("\n");
			}
			state.descriptionBuilder.append(reformatted);
		}
	}

	private static StringBuilder buildMarkdown(GroovydocParseState state) {
		StringBuilder markdown = new StringBuilder();
		appendDeprecated(markdown, state.deprecatedDoc);
		appendDescription(markdown, state.descriptionBuilder.toString().trim());
		appendParamDocs(markdown, state.paramDocs);
		appendReturnDoc(markdown, state.returnDocs.isEmpty() ? null : state.returnDocs.get(0));
		appendThrowsDocs(markdown, state.throwsDocs);
		appendSinceDoc(markdown, state.sinceDoc);
		appendSeeDocs(markdown, state.seeDocs);
		return markdown;
	}

	private static void appendDeprecated(StringBuilder markdown, String deprecatedDoc) {
		if (deprecatedDoc == null) {
			return;
		}
		markdown.append("**@deprecated**");
		if (!deprecatedDoc.isEmpty()) {
			markdown.append(" ").append(deprecatedDoc);
		}
		markdown.append("\n\n");
	}

	private static void appendDescription(StringBuilder markdown, String description) {
		if (!description.isEmpty()) {
			markdown.append(description);
		}
	}

	private static void appendParamDocs(StringBuilder markdown, List<String> paramDocs) {
		if (paramDocs.isEmpty()) {
			return;
		}
		if (markdown.length() > 0) {
			markdown.append("\n\n");
		}
		for (String param : paramDocs) {
			appendNamedTag(markdown, "@param", param);
		}
	}

	private static void appendReturnDoc(StringBuilder markdown, String returnDoc) {
		if (returnDoc == null || returnDoc.isEmpty()) {
			return;
		}
		if (markdown.length() > 0) {
			markdown.append("\n");
		}
		markdown.append("**@return** ").append(returnDoc).append("\n");
	}

	private static void appendThrowsDocs(StringBuilder markdown, List<String> throwsDocs) {
		if (throwsDocs.isEmpty()) {
			return;
		}
		if (markdown.length() > 0) {
			markdown.append("\n");
		}
		for (String value : throwsDocs) {
			appendNamedTag(markdown, "@throws", value);
		}
	}

	private static void appendSinceDoc(StringBuilder markdown, String sinceDoc) {
		if (sinceDoc == null) {
			return;
		}
		if (markdown.length() > 0) {
			markdown.append("\n");
		}
		markdown.append("**@since** ").append(sinceDoc).append("\n");
	}

	private static void appendSeeDocs(StringBuilder markdown, List<String> seeDocs) {
		if (seeDocs.isEmpty()) {
			return;
		}
		if (markdown.length() > 0) {
			markdown.append("\n");
		}
		for (String see : seeDocs) {
			markdown.append("**@see** `").append(see).append("`").append(MARKDOWN_LINE_BREAK);
		}
	}

	private static void appendNamedTag(StringBuilder markdown, String tag, String value) {
		int space = value.indexOf(' ');
		if (space > 0) {
			String name = value.substring(0, space);
			String desc = value.substring(space + 1).trim();
			markdown.append("**").append(tag).append("** `").append(name).append("` — ")
					.append(desc).append(MARKDOWN_LINE_BREAK);
		} else {
			markdown.append("**").append(tag).append("** `").append(value.trim()).append("`")
					.append(MARKDOWN_LINE_BREAK);
		}
	}

	private static final class GroovydocParseState {
		private final StringBuilder descriptionBuilder = new StringBuilder();
		private final List<String> paramDocs = new ArrayList<>();
		private final List<String> throwsDocs = new ArrayList<>();
		private final List<String> seeDocs = new ArrayList<>();
		private final List<String> returnDocs = new ArrayList<>();
		private String sinceDoc;
		private String deprecatedDoc;
		private StringBuilder currentTagContent;
		private String currentTagType;
	}

	/**
	 * Flush the current tag content into the appropriate list.
	 */
	private static void flushTag(String tagType, StringBuilder tagContent,
			List<String> paramDocs, List<String> throwsDocs, List<String> seeDocs,
			List<String> returnDocs) {
		if (tagType == null || tagContent == null) {
			return;
		}
		String content = tagContent.toString().trim();
		if (content.isEmpty()) {
			return;
		}
		switch (tagType) {
			case "param":
				paramDocs.add(content);
				break;
			case "return":
				returnDocs.add(content);
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

	private static String reformatLine(String line) {
		if (line.length() > MAX_REGEX_LINE_CHARS) {
			line = line.substring(0, MAX_REGEX_LINE_CHARS);
		}
		line = stripHtmlTagAttributes(line);
		line = line.replace("<pre>", "\n\n```\n");
		line = line.replace("</pre>", "\n```\n");
		line = replaceSimpleTag(line, "em", "_");
		line = replaceSimpleTag(line, "i", "_");
		line = replaceSimpleTag(line, "strong", "**");
		line = replaceSimpleTag(line, "b", "**");
		line = replaceSimpleTag(line, "code", "`");
		line = line.replace("<hr/>", "\n\n---\n\n").replace("<hr />", "\n\n---\n\n");
		line = replaceBlockOpenTagsWithBlankLine(line);

		// to add a line break to markdown, there needs to be at least two
		// spaces at the end of the line
		line = replaceBrTags(line, "  \n");
		line = stripHtmlTags(line);
		line = replaceInlineTag(line, "@code");
		line = replaceInlineTag(line, "@link");
		line = replaceInlineTag(line, "@linkplain");
		return line;
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

	private static String replaceBlockOpenTagsWithBlankLine(String text) {
		String[] tags = { "p", "ul", "ol", "dl", "li", "dt", "table", "tr", "div", "blockquote" };
		String out = text;
		for (String tag : tags) {
			out = out.replace("<" + tag + ">", "\n\n");
		}
		return out;
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
}
