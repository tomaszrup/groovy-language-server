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
package com.tomaszrup.groovyls.compiler.ast;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Unit tests for {@link UsedTypeCollectorVisitor}: verifying that types
 * referenced in constructor calls, casts, class expressions, variable
 * declarations, catch statements, and for loops are correctly collected.
 *
 * <p>Since UsedTypeCollectorVisitor is package-private, we test it indirectly
 * through {@link UnusedImportFinder} — if a type is collected by the visitor,
 * it won't appear as an unused import.</p>
 */
class UsedTypeCollectorVisitorTests {

	@Test
	void testCollectsConstructorCallTypes() {
		// ArrayList is used in "new ArrayList()" — should not be unused
		assertImportUsed(
				"import java.util.ArrayList\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    def x = new ArrayList()\n" +
				"  }\n" +
				"}\n",
				"ArrayList");
	}

	@Test
	void testCollectsCastExpressionTypes() {
		// List is used in a cast "(List) items" — should not be unused
		assertImportUsed(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    def items = null\n" +
				"    def x = (List) items\n" +
				"  }\n" +
				"}\n",
				"List");
	}

	@Test
	void testCollectsClassExpressionTypes() {
		// HashMap referenced via HashMap.class — should not be unused
		assertImportUsed(
				"import java.util.HashMap\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    def clazz = HashMap.class\n" +
				"  }\n" +
				"}\n",
				"HashMap");
	}

	@Test
	void testCollectsVariableDeclarationTypes() {
		// List used as a declared variable type — should not be unused
		assertImportUsed(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    List items = null\n" +
				"  }\n" +
				"}\n",
				"List");
	}

	@Test
	void testCollectsCatchStatementTypes() {
		// IOException used in catch clause — should not be unused
		assertImportUsed(
				"import java.io.IOException\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    try {} catch (IOException e) {}\n" +
				"  }\n" +
				"}\n",
				"IOException");
	}

	@Test
	void testCollectsForLoopVariableTypes() {
		// List used as for-loop variable type — should not be unused
		assertImportUsed(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    for (List item : []) {}\n" +
				"  }\n" +
				"}\n",
				"List");
	}

	@Test
	void testUnusedTypeNotCollected() {
		// Set is imported but never used anywhere in code
		assertImportUnused(
				"import java.util.Set\n" +
				"class Foo {\n" +
				"  String name\n" +
				"}\n",
				"Set");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void assertImportUsed(String source, String className) {
		GroovyLSCompilationUnit cu = compileSource(source);
		UnusedImportFinder finder = new UnusedImportFinder();
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);

		boolean isUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals(className));
		Assertions.assertFalse(isUnused,
				className + " should be detected as USED (not appear in unused list)");
	}

	private void assertImportUnused(String source, String className) {
		GroovyLSCompilationUnit cu = compileSource(source);
		UnusedImportFinder finder = new UnusedImportFinder();
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);

		boolean isUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals(className));
		Assertions.assertTrue(isUnused,
				className + " should be detected as UNUSED");
	}

	private GroovyLSCompilationUnit compileSource(String source) {
		CompilerConfiguration config = new CompilerConfiguration();
		GroovyClassLoader classLoader = new GroovyClassLoader(
				ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config, null, classLoader);
		URI uri = URI.create("file:///test.groovy");
		SourceUnit sourceUnit = new SourceUnit("test.groovy",
				new StringReaderSourceWithURI(source, uri, config),
				config, classLoader, cu.getErrorCollector());
		cu.addSource(sourceUnit);
		try {
			cu.compile(Phases.CANONICALIZATION);
		} catch (Exception e) {
			// expected
		}
		return cu;
	}
}
