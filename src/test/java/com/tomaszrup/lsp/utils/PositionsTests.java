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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Positions}: comparator ordering, validity checks,
 * and offset calculation from Position within a multi-line string.
 */
class PositionsTests {

	// ------------------------------------------------------------------
	// COMPARATOR
	// ------------------------------------------------------------------

	@Test
	void testComparatorSamePosition() {
		Position p1 = new Position(5, 10);
		Position p2 = new Position(5, 10);
		Assertions.assertEquals(0, Positions.COMPARATOR.compare(p1, p2));
	}

	@Test
	void testComparatorDifferentLines() {
		Position earlier = new Position(1, 0);
		Position later = new Position(5, 0);
		Assertions.assertTrue(Positions.COMPARATOR.compare(earlier, later) < 0);
		Assertions.assertTrue(Positions.COMPARATOR.compare(later, earlier) > 0);
	}

	@Test
	void testComparatorSameLineDifferentColumns() {
		Position left = new Position(3, 2);
		Position right = new Position(3, 10);
		Assertions.assertTrue(Positions.COMPARATOR.compare(left, right) < 0);
		Assertions.assertTrue(Positions.COMPARATOR.compare(right, left) > 0);
	}

	@Test
	void testComparatorOrigin() {
		Position origin = new Position(0, 0);
		Position other = new Position(0, 1);
		Assertions.assertTrue(Positions.COMPARATOR.compare(origin, other) < 0);
	}

	// ------------------------------------------------------------------
	// valid()
	// ------------------------------------------------------------------

	@Test
	void testValidPositiveLineAndColumn() {
		Assertions.assertTrue(Positions.valid(new Position(1, 1)));
	}

	@Test
	void testValidZeroLineAndColumn() {
		Assertions.assertTrue(Positions.valid(new Position(0, 0)));
	}

	@Test
	void testValidPositiveLineNegativeColumn() {
		// valid() returns true only if BOTH line >= 0 AND character >= 0
		Assertions.assertFalse(Positions.valid(new Position(1, -1)));
	}

	@Test
	void testValidNegativeLinePositiveColumn() {
		Assertions.assertFalse(Positions.valid(new Position(-1, 1)));
	}

	@Test
	void testValidBothNegative() {
		Assertions.assertFalse(Positions.valid(new Position(-1, -1)));
	}

	// ------------------------------------------------------------------
	// getOffset()
	// ------------------------------------------------------------------

	@Test
	void testGetOffsetFirstLineFirstColumn() {
		Assertions.assertEquals(0, Positions.getOffset("hello", new Position(0, 0)));
	}

	@Test
	void testGetOffsetFirstLineMiddle() {
		Assertions.assertEquals(3, Positions.getOffset("hello", new Position(0, 3)));
	}

	@Test
	void testGetOffsetSecondLine() {
		// "hello\nworld" — line 1 starts at offset 6
		Assertions.assertEquals(6, Positions.getOffset("hello\nworld", new Position(1, 0)));
	}

	@Test
	void testGetOffsetSecondLineWithColumn() {
		// "hello\nworld" — line 1, col 3 → offset 9
		Assertions.assertEquals(9, Positions.getOffset("hello\nworld", new Position(1, 3)));
	}

	@Test
	void testGetOffsetThirdLine() {
		String text = "aaa\nbbb\nccc";
		// line 2 starts at offset 8
		Assertions.assertEquals(8, Positions.getOffset(text, new Position(2, 0)));
	}

	@Test
	void testGetOffsetEmptyString() {
		Assertions.assertEquals(0, Positions.getOffset("", new Position(0, 0)));
	}

	@Test
	void testGetOffsetLineBeyondContent() {
		// Requesting line 5 in a single-line string should return -1
		Assertions.assertEquals(-1, Positions.getOffset("hello", new Position(5, 0)));
	}

	@Test
	void testGetOffsetSingleCharLines() {
		String text = "a\nb\nc\n";
		Assertions.assertEquals(0, Positions.getOffset(text, new Position(0, 0)));
		Assertions.assertEquals(2, Positions.getOffset(text, new Position(1, 0)));
		Assertions.assertEquals(4, Positions.getOffset(text, new Position(2, 0)));
	}
}
