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
package com.tomaszrup.groovyls;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JavadocResolver}: source-JAR extraction, Javadoc-to-Markdown
 * conversion, parameter signature parsing, and cache behaviour.
 */
class JavadocResolverTests {

	private Path tempDir;

	@BeforeEach
	void setup() throws IOException {
		tempDir = Files.createTempDirectory("javadoc-resolver-test");
		JavadocResolver.clearCache();
	}

	@AfterEach
	void tearDown() throws IOException {
		JavadocResolver.clearCache();
		if (tempDir != null) {
			// Clean up temp files
			Files.walk(tempDir)
					.sorted(java.util.Comparator.reverseOrder())
					.map(Path::toFile)
					.forEach(File::delete);
		}
	}

	// --- Source JAR extraction ---

	@Test
	void testResolveMethodJavadocFromSourcesJar() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/MyClass.java",
				"package com.example;\n" +
				"public class MyClass {\n" +
				"    /**\n" +
				"     * Greets the user.\n" +
				"     * @param name the name of the user\n" +
				"     * @return greeting string\n" +
				"     */\n" +
				"    public String greet(String name) {\n" +
				"        return \"Hello \" + name;\n" +
				"    }\n" +
				"}\n");

		String result = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.MyClass", "greet",
				CompletionItemKind.Method, "String");
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("Greets the user"));
		Assertions.assertTrue(result.contains("@param"));
		Assertions.assertTrue(result.contains("name"));
	}

	@Test
	void testResolveClassJavadocFromSourcesJar() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/Documented.java",
				"package com.example;\n" +
				"/**\n" +
				" * A well-documented class.\n" +
				" * @since 1.0\n" +
				" */\n" +
				"public class Documented {\n" +
				"}\n");

		String result = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.Documented", "Documented",
				CompletionItemKind.Class, null);
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("well-documented class"));
		Assertions.assertTrue(result.contains("@since"));
	}

	@Test
	void testResolveFieldJavadocFromSourcesJar() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/Constants.java",
				"package com.example;\n" +
				"public class Constants {\n" +
				"    /** The maximum retry count. */\n" +
				"    public static final int MAX_RETRIES = 3;\n" +
				"}\n");

		String result = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.Constants", "MAX_RETRIES",
				CompletionItemKind.Field, null);
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.contains("maximum retry count"));
	}

	@Test
	void testResolveReturnsNullForMissingClass() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/Exists.java",
				"package com.example;\npublic class Exists {}\n");

		String result = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.DoesNotExist", "foo",
				CompletionItemKind.Method, null);
		Assertions.assertNull(result);
	}

	@Test
	void testResolveReturnsNullForMissingMember() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/NoMethods.java",
				"package com.example;\n" +
				"/** Class doc. */\n" +
				"public class NoMethods {\n" +
				"}\n");

		// Looking for a method that doesn't exist
		String result = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.NoMethods", "nonExistent",
				CompletionItemKind.Method, null);
		Assertions.assertNull(result);
	}

	@Test
	void testResolveReturnsNullForNonexistentJar() {
		Path fake = tempDir.resolve("nonexistent-sources.jar");
		String result = JavadocResolver.resolveFromSourcesJar(
				fake, "com.example.Foo", "bar",
				CompletionItemKind.Method, null);
		Assertions.assertNull(result);
	}

	@Test
	void testCacheIsUsedOnSecondCall() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/Cached.java",
				"package com.example;\n" +
				"public class Cached {\n" +
				"    /** Cached method doc. */\n" +
				"    public void doSomething() {}\n" +
				"}\n");

		// First call populates cache
		String result1 = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.Cached", "doSomething",
				CompletionItemKind.Method, "");
		Assertions.assertNotNull(result1);

		// Second call should use cache (same result)
		String result2 = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.Cached", "doSomething",
				CompletionItemKind.Method, "");
		Assertions.assertEquals(result1, result2);
	}

	@Test
	void testClearCacheAllowsFreshRead() throws Exception {
		Path sourcesJar = createSourcesJar("com/example/ClearTest.java",
				"package com.example;\n" +
				"public class ClearTest {\n" +
				"    /** Doc. */\n" +
				"    public void method() {}\n" +
				"}\n");

		JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.ClearTest", "method",
				CompletionItemKind.Method, "");

		JavadocResolver.clearCache();

		// After clearing, should still work (re-parses the JAR)
		String result = JavadocResolver.resolveFromSourcesJar(
				sourcesJar, "com.example.ClearTest", "method",
				CompletionItemKind.Method, "");
		Assertions.assertNotNull(result);
	}

	// --- javadocToMarkdown ---

	@Test
	void testJavadocToMarkdownBasicDescription() {
		String md = JavadocResolver.javadocToMarkdown(" Simple description. ");
		Assertions.assertNotNull(md);
		Assertions.assertTrue(md.contains("Simple description."));
	}

	@Test
	void testJavadocToMarkdownWithParam() {
		String md = JavadocResolver.javadocToMarkdown(
				" Does something.\n * @param x the x coordinate\n");
		Assertions.assertNotNull(md);
		Assertions.assertTrue(md.contains("Does something"));
		Assertions.assertTrue(md.contains("@param"));
		Assertions.assertTrue(md.contains("`x`"));
	}

	@Test
	void testJavadocToMarkdownWithReturn() {
		String md = JavadocResolver.javadocToMarkdown(
				" Computes a value.\n * @return the result\n");
		Assertions.assertNotNull(md);
		Assertions.assertTrue(md.contains("Computes a value"));
	}

	@Test
	void testJavadocToMarkdownWithThrows() {
		String md = JavadocResolver.javadocToMarkdown(
				" Risky operation.\n * @throws IOException if I/O fails\n");
		Assertions.assertNotNull(md);
		Assertions.assertTrue(md.contains("@throws"));
		Assertions.assertTrue(md.contains("IOException"));
	}

	@Test
	void testJavadocToMarkdownWithDeprecated() {
		String md = JavadocResolver.javadocToMarkdown(
				" @deprecated Use newMethod instead.\n");
		Assertions.assertNotNull(md);
		Assertions.assertTrue(md.contains("@deprecated"));
		Assertions.assertTrue(md.contains("newMethod"));
	}

	@Test
	void testJavadocToMarkdownNull() {
		Assertions.assertNull(JavadocResolver.javadocToMarkdown(null));
	}

	// --- htmlToMarkdown ---

	@Test
	void testHtmlToMarkdownCode() {
		String result = JavadocResolver.htmlToMarkdown("Use <code>foo</code> method");
		Assertions.assertTrue(result.contains("`foo`"));
	}

	@Test
	void testHtmlToMarkdownPre() {
		String result = JavadocResolver.htmlToMarkdown("Example:<pre>code</pre>done");
		Assertions.assertTrue(result.contains("```"));
	}

	@Test
	void testHtmlToMarkdownInlineCodeTag() {
		String result = JavadocResolver.htmlToMarkdown("Pass {@code null} to reset");
		Assertions.assertTrue(result.contains("`null`"));
	}

	@Test
	void testHtmlToMarkdownLinkTag() {
		String result = JavadocResolver.htmlToMarkdown("See {@link java.util.List}");
		Assertions.assertTrue(result.contains("`java.util.List`"));
	}

	@Test
	void testHtmlToMarkdownNull() {
		Assertions.assertEquals("", JavadocResolver.htmlToMarkdown(null));
	}

	// --- Helper ---

	private Path createSourcesJar(String entryPath, String javaSource) throws Exception {
		Path jarFile = tempDir.resolve("test-sources.jar");
		try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile.toFile()))) {
			JarEntry entry = new JarEntry(entryPath);
			jos.putNextEntry(entry);
			jos.write(javaSource.getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		return jarFile;
	}
}
