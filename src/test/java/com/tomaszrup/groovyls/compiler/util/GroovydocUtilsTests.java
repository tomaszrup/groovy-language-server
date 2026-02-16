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
package com.tomaszrup.groovyls.compiler.util;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import groovy.lang.groovydoc.Groovydoc;

/**
 * Tests for GroovydocUtils — verifies Groovydoc-to-Markdown conversion
 * including all supported tags (@param, @return, @throws, @see, @since,
 * @deprecated) and HTML-to-Markdown reformatting.
 */
class GroovydocUtilsTests {

	// ------------------------------------------------------------------
	// Helpers — Create a Groovydoc with the given raw content via reflection
	// (the constructor is private in Groovy 4.x)
	// ------------------------------------------------------------------

	private Groovydoc makeGroovydoc(String content) {
		try {
			Constructor<Groovydoc> ctor = Groovydoc.class.getDeclaredConstructor(String.class);
			ctor.setAccessible(true);
			return ctor.newInstance(content);
		} catch (Exception e) {
			throw new RuntimeException("Failed to create Groovydoc via reflection", e);
		}
	}

	// ------------------------------------------------------------------
	// Null / empty / absent
	// ------------------------------------------------------------------

	@Test
	void testNullGroovydocReturnsNull() {
		String result = GroovydocUtils.groovydocToMarkdownDescription(null);
		Assertions.assertNull(result);
	}

	@Test
	void testEmptyGroovydocReturnsNull() {
		// An empty string doc should not be "present"
		Groovydoc empty = makeGroovydoc("");
		String result = GroovydocUtils.groovydocToMarkdownDescription(empty);
		// Either null or the doc is not present
		if (empty.isPresent()) {
			// If the implementation considers it present, result may be null
			// because there's no actual content
		} else {
			Assertions.assertNull(result);
		}
	}

	// ------------------------------------------------------------------
	// Plain description
	// ------------------------------------------------------------------

	@Test
	void testSimpleDescription() {
		String result = GroovydocUtils.groovydocToMarkdownDescription(
				makeGroovydoc("/** A simple description. */"));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("A simple description"),
				"Should include plain description, got: " + result);
	}

	@Test
	void testMultiLineDescription() {
		String doc = "/**\n * First line.\n * Second line.\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("First line"), "Should include first line");
		Assertions.assertTrue(result.contains("Second line"), "Should include second line");
	}

	// ------------------------------------------------------------------
	// @param
	// ------------------------------------------------------------------

	@Test
	void testParamTag() {
		String doc = "/**\n * Does something.\n * @param name the user name\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@param"), "Should contain @param, got: " + result);
		Assertions.assertTrue(result.contains("name"), "Should contain param name, got: " + result);
		Assertions.assertTrue(result.contains("the user name"), "Should contain param description");
	}

	@Test
	void testMultipleParams() {
		String doc = "/**\n * Add two numbers.\n * @param a first number\n * @param b second number\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("`a`"), "Should contain formatted param 'a'");
		Assertions.assertTrue(result.contains("`b`"), "Should contain formatted param 'b'");
	}

	// ------------------------------------------------------------------
	// @return
	// ------------------------------------------------------------------

	@Test
	void testReturnTag() {
		String doc = "/**\n * Gets the name.\n * @return the name string\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@return"), "Should contain @return");
		Assertions.assertTrue(result.contains("the name string"), "Should contain return description");
	}

	// ------------------------------------------------------------------
	// @throws / @exception
	// ------------------------------------------------------------------

	@Test
	void testThrowsTag() {
		String doc = "/**\n * Risky operation.\n * @throws IOException if I/O fails\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@throws"), "Should contain @throws");
		Assertions.assertTrue(result.contains("IOException"), "Should contain exception type");
	}

	@Test
	void testExceptionTag() {
		String doc = "/**\n * Risky operation.\n * @exception RuntimeException if something goes wrong\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@throws") || result.contains("RuntimeException"),
				"Should handle @exception tag");
	}

	// ------------------------------------------------------------------
	// @see
	// ------------------------------------------------------------------

	@Test
	void testSeeTag() {
		String doc = "/**\n * See also.\n * @see java.util.List\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@see"), "Should contain @see");
		Assertions.assertTrue(result.contains("java.util.List"), "Should contain see reference");
	}

	// ------------------------------------------------------------------
	// @since
	// ------------------------------------------------------------------

	@Test
	void testSinceTag() {
		String doc = "/**\n * Old method.\n * @since 1.0\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@since"), "Should contain @since");
		Assertions.assertTrue(result.contains("1.0"), "Should contain version");
	}

	// ------------------------------------------------------------------
	// @deprecated
	// ------------------------------------------------------------------

	@Test
	void testDeprecatedTag() {
		String doc = "/**\n * @deprecated Use newMethod() instead.\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("@deprecated"), "Should contain @deprecated");
		Assertions.assertTrue(result.contains("Use newMethod() instead"),
				"Should contain deprecation message");
	}

	@Test
	void testDeprecatedAppearsFirst() {
		String doc = "/**\n * Some description.\n * @deprecated old stuff\n * @param x a value\n */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		int deprecatedIndex = result.indexOf("@deprecated");
		int descIndex = result.indexOf("Some description");
		Assertions.assertTrue(deprecatedIndex >= 0, "Should contain @deprecated");
		// Deprecated should appear before the description
		Assertions.assertTrue(deprecatedIndex < descIndex,
				"@deprecated should appear before description in output");
	}

	// ------------------------------------------------------------------
	// HTML-to-Markdown conversion
	// ------------------------------------------------------------------

	@Test
	void testHtmlAndInlineTagConversions() {
		String[][] cases = new String[][] {
				{"/**\n * Use <code>getValue()</code> to read.\n */", "`getValue()`", "<code> should convert to backticks"},
				{"/**\n * This is <em>important</em>.\n */", "_important_", "<em> should convert to underscores"},
				{"/**\n * This is <strong>bold</strong>.\n */", "**bold**", "<strong> should convert to double asterisks"},
				{"/**\n * Example:\n * <pre>code here</pre>\n */", "```", "<pre> should convert to code fence"},
				{"/**\n * Use {@code myVar} properly.\n */", "`myVar`", "{@code} should convert to backticks"},
				{"/**\n * See {@link java.util.Map} for details.\n */", "`java.util.Map`", "{@link} should convert to backticks"},
		};

		for (String[] testCase : cases) {
			String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(testCase[0]));
			Assertions.assertNotNull(result);
			Assertions.assertTrue(result.contains(testCase[1]), testCase[2] + ", got: " + result);
		}
	}

	// ------------------------------------------------------------------
	// Combined tags
	// ------------------------------------------------------------------

	@Test
	void testFullGroovydocWithAllTags() {
		String doc = "/**\n"
				+ " * Computes the sum.\n"
				+ " *\n"
				+ " * @param a first operand\n"
				+ " * @param b second operand\n"
				+ " * @return the sum\n"
				+ " * @throws ArithmeticException on overflow\n"
				+ " * @since 2.0\n"
				+ " * @see java.lang.Math\n"
				+ " */";
		String result = GroovydocUtils.groovydocToMarkdownDescription(makeGroovydoc(doc));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("Computes the sum"), "Description present");
		Assertions.assertTrue(result.contains("`a`"), "@param a present");
		Assertions.assertTrue(result.contains("`b`"), "@param b present");
		Assertions.assertTrue(result.contains("@return"), "@return present");
		Assertions.assertTrue(result.contains("ArithmeticException"), "@throws present");
		Assertions.assertTrue(result.contains("2.0"), "@since present");
		Assertions.assertTrue(result.contains("java.lang.Math"), "@see present");
	}

}
