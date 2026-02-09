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
	public static String groovydocToMarkdownDescription(Groovydoc groovydoc) {
		if (groovydoc == null || !groovydoc.isPresent()) {
			return null;
		}
		String content = groovydoc.getContent();
		String[] lines = content.split("\n");
		StringBuilder descriptionBuilder = new StringBuilder();
		List<String> paramDocs = new ArrayList<>();
		List<String> throwsDocs = new ArrayList<>();
		List<String> seeDocs = new ArrayList<>();
		List<String> returnDocs = new ArrayList<>();
		String sinceDoc = null;
		String deprecatedDoc = null;
		StringBuilder currentTagContent = null;
		String currentTagType = null;

		int n = lines.length;
		if (n == 1) {
			// strip end of groovydoc comment
			int c = lines[0].indexOf("*/");
			if (c != -1) {
				lines[0] = lines[0].substring(0, c);
			}
		}
		// strip start of groovydoc comment
		String firstLine = lines[0];
		int lengthToRemove = Math.min(firstLine.length(), 3);
		firstLine = firstLine.substring(lengthToRemove);
		processGroovydocLine(firstLine, descriptionBuilder, paramDocs, throwsDocs, seeDocs,
				new String[]{currentTagType}, new StringBuilder[]{currentTagContent});
		// Re-read state from array wrappers
		currentTagType = null;
		currentTagContent = null;

		// Actually process all lines with proper state tracking
		descriptionBuilder = new StringBuilder();
		paramDocs = new ArrayList<>();
		throwsDocs = new ArrayList<>();
		seeDocs = new ArrayList<>();
		returnDocs = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			String line = lines[i];

			if (i == 0) {
				// Strip start of groovydoc comment (/**)
				int startLen = Math.min(line.length(), 3);
				line = line.substring(startLen);
			}
			if (i == n - 1) {
				// Strip end of groovydoc comment (*/)
				int endIndex = line.indexOf("*/");
				if (endIndex != -1) {
					line = line.substring(0, endIndex);
				}
			}

			// Strip leading * character from continuation lines
			if (i > 0) {
				int star = line.indexOf("*");
				if (star > -1) {
					line = line.substring(star + 1);
				}
			}

			line = line.trim();

			// Check for tag lines
			if (line.startsWith("@param ")) {
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				currentTagType = "param";
				currentTagContent = new StringBuilder(line.substring(7).trim());
			} else if (line.startsWith("@return ") || line.equals("@return")) {
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				currentTagType = "return";
				currentTagContent = new StringBuilder(line.length() > 8 ? line.substring(8).trim() : "");
			} else if (line.startsWith("@throws ") || line.startsWith("@exception ")) {
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				currentTagType = "throws";
				String tagContent = line.startsWith("@throws ") ? line.substring(8) : line.substring(11);
				currentTagContent = new StringBuilder(tagContent.trim());
			} else if (line.startsWith("@see ")) {
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				currentTagType = "see";
				currentTagContent = new StringBuilder(line.substring(5).trim());
			} else if (line.startsWith("@since ")) {
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				sinceDoc = line.substring(7).trim();
				currentTagType = null;
				currentTagContent = null;
			} else if (line.startsWith("@deprecated")) {
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				deprecatedDoc = line.length() > 12 ? line.substring(12).trim() : "";
				currentTagType = null;
				currentTagContent = null;
			} else if (line.startsWith("@")) {
				// Other tag — flush current and ignore
				flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
				currentTagType = null;
				currentTagContent = null;
			} else {
				// Continuation or description line
				if (currentTagType != null && currentTagContent != null) {
					if (!line.isEmpty()) {
						currentTagContent.append(" ").append(line);
					}
				} else {
					String reformatted = reformatLine(line);
					if (!reformatted.isEmpty()) {
						if (descriptionBuilder.length() > 0) {
							descriptionBuilder.append("\n");
						}
						descriptionBuilder.append(reformatted);
					}
				}
			}
		}

		// Flush any remaining tag
		flushTag(currentTagType, currentTagContent, paramDocs, throwsDocs, seeDocs, returnDocs);
		String returnDoc = returnDocs.isEmpty() ? null : returnDocs.get(0);

		// Build the final Markdown output
		StringBuilder markdown = new StringBuilder();

		if (deprecatedDoc != null) {
			markdown.append("**@deprecated**");
			if (!deprecatedDoc.isEmpty()) {
				markdown.append(" ").append(deprecatedDoc);
			}
			markdown.append("\n\n");
		}

		String desc = descriptionBuilder.toString().trim();
		if (!desc.isEmpty()) {
			markdown.append(desc);
		}

		if (!paramDocs.isEmpty()) {
			if (markdown.length() > 0) {
				markdown.append("\n\n");
			}
			for (String param : paramDocs) {
				// param format: "name description"
				int space = param.indexOf(' ');
				if (space > 0) {
					String pName = param.substring(0, space);
					String pDesc = param.substring(space + 1).trim();
					markdown.append("**@param** `").append(pName).append("` — ").append(pDesc).append("  \n");
				} else {
					markdown.append("**@param** `").append(param.trim()).append("`  \n");
				}
			}
		}

		if (returnDoc != null && !returnDoc.isEmpty()) {
			if (markdown.length() > 0) {
				markdown.append("\n");
			}
			markdown.append("**@return** ").append(returnDoc).append("\n");
		}

		if (!throwsDocs.isEmpty()) {
			if (markdown.length() > 0) {
				markdown.append("\n");
			}
			for (String t : throwsDocs) {
				int space = t.indexOf(' ');
				if (space > 0) {
					String tName = t.substring(0, space);
					String tDesc = t.substring(space + 1).trim();
					markdown.append("**@throws** `").append(tName).append("` — ").append(tDesc).append("  \n");
				} else {
					markdown.append("**@throws** `").append(t.trim()).append("`  \n");
				}
			}
		}

		if (sinceDoc != null) {
			if (markdown.length() > 0) {
				markdown.append("\n");
			}
			markdown.append("**@since** ").append(sinceDoc).append("\n");
		}

		if (!seeDocs.isEmpty()) {
			if (markdown.length() > 0) {
				markdown.append("\n");
			}
			for (String see : seeDocs) {
				markdown.append("**@see** `").append(see).append("`  \n");
			}
		}

		String result = markdown.toString().trim();
		return result.isEmpty() ? null : result;
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

	/**
	 * Placeholder overload used during initial line processing. Not actually
	 * used in the final implementation but kept for API compatibility.
	 */
	private static void processGroovydocLine(String line, StringBuilder description,
			List<String> paramDocs, List<String> throwsDocs, List<String> seeDocs,
			String[] tagType, StringBuilder[] tagContent) {
		// no-op; real processing happens in the main loop
	}

	private static String reformatLine(String line) {
		// remove all attributes (including namespaced)
		line = line.replaceAll("<(\\w+)(?:\\s+\\w+(?::\\w+)?=(\"|\')[^\"\']*\\2)*\\s*(\\/{0,1})>", "<$1$3>");
		line = line.replaceAll("<pre>", "\n\n```\n");
		line = line.replaceAll("</pre>", "\n```\n");
		line = line.replaceAll("</?(em|i)>", "_");
		line = line.replaceAll("</?(strong|b)>", "**");
		line = line.replaceAll("</?code>", "`");
		line = line.replaceAll("<hr ?\\/>", "\n\n---\n\n");
		line = line.replaceAll("<(p|ul|ol|dl|li|dt|table|tr|div|blockquote)>", "\n\n");

		// to add a line break to markdown, there needs to be at least two
		// spaces at the end of the line
		line = line.replaceAll("<br\\s*/?>\\s*", "  \n");
		line = line.replaceAll("<\\/{0,1}\\w+\\/{0,1}>", "");
		// Handle {@code ...} inline tags
		line = line.replaceAll("\\{@code\\s+([^}]*)\\}", "`$1`");
		// Handle {@link ...} inline tags
		line = line.replaceAll("\\{@link(?:plain)?\\s+([^}]*)\\}", "`$1`");
		return line;
	}
}
