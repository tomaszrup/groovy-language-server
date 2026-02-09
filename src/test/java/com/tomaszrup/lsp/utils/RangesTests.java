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
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.lsp.utils;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Ranges}: containment checks, intersection detection,
 * and substring extraction from multi-line text.
 */
class RangesTests {

	// ------------------------------------------------------------------
	// contains()
	// ------------------------------------------------------------------

	@Test
	void testContainsPositionAtStart() {
		Range range = new Range(new Position(1, 5), new Position(3, 10));
		Assertions.assertTrue(Ranges.contains(range, new Position(1, 5)));
	}

	@Test
	void testContainsPositionAtEnd() {
		Range range = new Range(new Position(1, 5), new Position(3, 10));
		Assertions.assertTrue(Ranges.contains(range, new Position(3, 10)));
	}

	@Test
	void testContainsPositionInMiddle() {
		Range range = new Range(new Position(1, 5), new Position(3, 10));
		Assertions.assertTrue(Ranges.contains(range, new Position(2, 0)));
	}

	@Test
	void testContainsPositionBeforeRange() {
		Range range = new Range(new Position(1, 5), new Position(3, 10));
		Assertions.assertFalse(Ranges.contains(range, new Position(0, 0)));
	}

	@Test
	void testContainsPositionAfterRange() {
		Range range = new Range(new Position(1, 5), new Position(3, 10));
		Assertions.assertFalse(Ranges.contains(range, new Position(4, 0)));
	}

	@Test
	void testContainsPositionSameLineBeforeStart() {
		Range range = new Range(new Position(1, 5), new Position(1, 10));
		Assertions.assertFalse(Ranges.contains(range, new Position(1, 3)));
	}

	@Test
	void testContainsPositionSameLineAfterEnd() {
		Range range = new Range(new Position(1, 5), new Position(1, 10));
		Assertions.assertFalse(Ranges.contains(range, new Position(1, 12)));
	}

	@Test
	void testContainsSinglePointRange() {
		Range range = new Range(new Position(2, 5), new Position(2, 5));
		Assertions.assertTrue(Ranges.contains(range, new Position(2, 5)));
		Assertions.assertFalse(Ranges.contains(range, new Position(2, 6)));
	}

	// ------------------------------------------------------------------
	// intersect()
	// ------------------------------------------------------------------

	@Test
	void testIntersectOverlapping() {
		Range r1 = new Range(new Position(1, 0), new Position(3, 0));
		Range r2 = new Range(new Position(2, 0), new Position(4, 0));
		Assertions.assertTrue(Ranges.intersect(r1, r2));
	}

	@Test
	void testIntersectContained() {
		Range outer = new Range(new Position(1, 0), new Position(10, 0));
		Range inner = new Range(new Position(3, 0), new Position(5, 0));
		Assertions.assertTrue(Ranges.intersect(outer, inner));
	}

	@Test
	void testIntersectDisjoint() {
		Range r1 = new Range(new Position(1, 0), new Position(2, 0));
		Range r2 = new Range(new Position(5, 0), new Position(6, 0));
		Assertions.assertFalse(Ranges.intersect(r1, r2));
	}

	@Test
	void testIntersectTouchingAtBoundary() {
		Range r1 = new Range(new Position(1, 0), new Position(3, 0));
		Range r2 = new Range(new Position(3, 0), new Position(5, 0));
		// r2 starts at r1's end, so r1 contains r2.start
		Assertions.assertTrue(Ranges.intersect(r1, r2));
	}

	@Test
	void testIntersectIdentical() {
		Range r = new Range(new Position(2, 5), new Position(4, 10));
		Assertions.assertTrue(Ranges.intersect(r, r));
	}

	// ------------------------------------------------------------------
	// getSubstring()
	// ------------------------------------------------------------------

	@Test
	void testGetSubstringSingleLine() {
		String text = "hello world";
		Range range = new Range(new Position(0, 0), new Position(0, 5));
		Assertions.assertEquals("hello", Ranges.getSubstring(text, range));
	}

	@Test
	void testGetSubstringMiddleOfLine() {
		String text = "hello world";
		Range range = new Range(new Position(0, 6), new Position(0, 11));
		Assertions.assertEquals("world", Ranges.getSubstring(text, range));
	}

	@Test
	void testGetSubstringMultiLine() {
		String text = "line1\nline2\nline3";
		Range range = new Range(new Position(0, 3), new Position(1, 4));
		String result = Ranges.getSubstring(text, range);
		Assertions.assertEquals("e1\nline", result);
	}

	@Test
	void testGetSubstringEntireFirstLine() {
		String text = "hello\nworld";
		Range range = new Range(new Position(0, 0), new Position(0, 5));
		Assertions.assertEquals("hello", Ranges.getSubstring(text, range));
	}

	@Test
	void testGetSubstringSecondLine() {
		String text = "aaa\nbbb\nccc";
		Range range = new Range(new Position(1, 0), new Position(1, 3));
		Assertions.assertEquals("bbb", Ranges.getSubstring(text, range));
	}

	@Test
	void testGetSubstringEmptyRange() {
		String text = "hello";
		Range range = new Range(new Position(0, 2), new Position(0, 2));
		Assertions.assertEquals("", Ranges.getSubstring(text, range));
	}

	@Test
	void testGetSubstringWithMaxLines() {
		String text = "line1\nline2\nline3\nline4";
		Range range = new Range(new Position(0, 0), new Position(3, 5));
		// maxLines = 2 → only lines 0 and 1
		String result = Ranges.getSubstring(text, range, 2);
		Assertions.assertEquals("line1\n", result);
	}

	@Test
	void testGetSubstringSpanningAllLines() {
		String text = "abc\ndef";
		Range range = new Range(new Position(0, 0), new Position(1, 3));
		Assertions.assertEquals("abc\ndef", Ranges.getSubstring(text, range));
	}
}
