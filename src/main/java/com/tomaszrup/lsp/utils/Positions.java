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

import java.util.Comparator;

import org.eclipse.lsp4j.Position;

public class Positions {
	private Positions() {
	}

	public static final Comparator<Position> COMPARATOR = (Position p1, Position p2) -> {
		if (p1.getLine() != p2.getLine()) {
			return p1.getLine() - p2.getLine();
		}
		return p1.getCharacter() - p2.getCharacter();
	};

	public static boolean valid(Position p) {
		return p.getLine() >= 0 && p.getCharacter() >= 0;
	}

	public static int getOffset(String string, Position position) {
		if (string == null || position == null || !valid(position)) {
			return -1;
		}
		int lineStartOffset = findLineStartOffset(string, position.getLine());
		if (lineStartOffset < 0) {
			return -1;
		}
		int lineEndOffset = findLineEndOffset(string, lineStartOffset);
		int character = position.getCharacter();
		int lineLength = lineEndOffset - lineStartOffset;
		if (character < 0 || character > lineLength) {
			return -1;
		}

		return lineStartOffset + character;
	}

	private static int findLineStartOffset(String string, int line) {
		if (line == 0) {
			return 0;
		}
		int currentLine = 0;
		for (int i = 0; i < string.length(); i++) {
			if (string.charAt(i) == '\n') {
				currentLine++;
				if (currentLine == line) {
					return i + 1;
				}
			}
		}
		return -1;
	}

	private static int findLineEndOffset(String string, int lineStartOffset) {
		for (int i = lineStartOffset; i < string.length(); i++) {
			char c = string.charAt(i);
			if (c == '\n' || c == '\r') {
				return i;
			}
		}
		return string.length();
	}
}