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
package com.tomaszrup.lsp.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class Ranges {
	private Ranges() {
	}

	public static boolean contains(Range range, Position position) {
		return Positions.COMPARATOR.compare(position, range.getStart()) >= 0
				&& Positions.COMPARATOR.compare(position, range.getEnd()) <= 0;
	}

	public static boolean intersect(Range r1, Range r2) {
		return contains(r1, r2.getStart()) || contains(r1, r2.getEnd());
	}

	public static String getSubstring(String string, Range range) {
		return getSubstring(string, range, 0);
	}

	public static String getSubstring(String string, Range range, int maxLines) {
		if (string == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		Position start = range.getStart();
		Position end = range.getEnd();
		int startLine = start.getLine();
		int startChar = start.getCharacter();
		int endLine = end.getLine();
		int endChar = end.getCharacter();
		int lineCount = 1 + (endLine - startLine);
		if (maxLines > 0 && lineCount > maxLines) {
			endLine = startLine + maxLines - 1;
			endChar = 0;
		}
		try (BufferedReader reader = new BufferedReader(new StringReader(string))) {
			if (!skipLines(reader, startLine)) {
				return builder.toString();
			}
			if (!skipCharacters(reader, startChar)) {
				return builder.toString();
			}

			int endCharStart = appendUntilLineBreaks(reader, builder, endLine - startLine, startChar);
			if (endCharStart < 0) {
				return builder.toString();
			}
			if (!appendCharacters(reader, builder, endCharStart, endChar)) {
				return builder.toString();
			}
		} catch (IOException e) {
			return null;
		}
		return builder.toString();
	}

	private static boolean skipLines(BufferedReader reader, int linesToSkip) throws IOException {
		for (int i = 0; i < linesToSkip; i++) {
			String line = reader.readLine();
			if (line == null) {
				return false;
			}
		}
		return true;
	}

	private static boolean skipCharacters(BufferedReader reader, int charactersToSkip) throws IOException {
		for (int i = 0; i < charactersToSkip; i++) {
			if (reader.read() == -1) {
				return false;
			}
		}
		return true;
	}

	private static int appendUntilLineBreaks(BufferedReader reader,
									 StringBuilder builder,
									 int maxLineBreaks,
									 int startChar) throws IOException {
		if (maxLineBreaks <= 0) {
			return startChar;
		}
		int readLines = 0;
		while (readLines < maxLineBreaks) {
			int ch = reader.read();
			if (ch == -1) {
				return -1;
			}
			char character = (char) ch;
			if (character == '\n') {
				readLines++;
			}
			builder.append(character);
		}
		return 0;
	}

	private static boolean appendCharacters(BufferedReader reader,
									 StringBuilder builder,
									 int from,
									 int to) throws IOException {
		for (int i = from; i < to; i++) {
			int ch = reader.read();
			if (ch == -1) {
				return false;
			}
			builder.append((char) ch);
		}
		return true;
	}
}