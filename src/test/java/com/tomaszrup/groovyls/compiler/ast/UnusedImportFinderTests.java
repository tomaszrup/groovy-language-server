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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Unit tests for {@link UnusedImportFinder}: detecting unused imports in
 * Groovy source files.
 */
class UnusedImportFinderTests {

	private UnusedImportFinder finder;

	@BeforeEach
	void setup() {
		finder = new UnusedImportFinder();
	}

	@Test
	void testFindUnusedImportsNullCompilationUnit() {
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(null);
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isEmpty());
	}

	@Test
	void testFindUnusedImportsNoImports() {
		GroovyLSCompilationUnit cu = compileSource("class Foo {}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isEmpty(), "No imports → no unused imports");
	}

	@Test
	void testFindUnusedImportsAllUsed() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.ArrayList\n" +
				"class Foo {\n" +
				"  void bar() {\n" +
				"    new ArrayList()\n" +
				"  }\n" +
				"}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		// ArrayList is used in a constructor call, so no unused imports
		for (List<ImportNode> unused : result.values()) {
			Assertions.assertTrue(unused.isEmpty(),
					"All imports are used, should have no unused imports");
		}
	}

	@Test
	void testFindUnusedImportsOneUnused() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  String name\n" +
				"}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		Assertions.assertFalse(result.isEmpty(), "Should find unused import");
		int totalUnused = result.values().stream().mapToInt(List::size).sum();
		Assertions.assertTrue(totalUnused >= 1, "Should have at least 1 unused import");
	}

	@Test
	void testFindUnusedImportsMixedUsedAndUnused() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.List\n" +
				"import java.util.ArrayList\n" +
				"class Foo {\n" +
				"  List items\n" +
				"}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		// List is used as a field type, ArrayList is not used
		int totalUnused = result.values().stream().mapToInt(List::size).sum();
		Assertions.assertTrue(totalUnused >= 1, "ArrayList should be unused");

		// Check that the unused one is ArrayList
		boolean hasArrayListUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals("ArrayList"));
		Assertions.assertTrue(hasArrayListUnused, "ArrayList import should be detected as unused");
	}

	@Test
	void testFindUnusedImportsInSourceNullModule() {
		GroovyLSCompilationUnit cu = compileSource("class Foo {}\n");
		// Create a SourceUnit with null AST
		CompilerConfiguration config = new CompilerConfiguration();
		GroovyClassLoader classLoader = new GroovyClassLoader(
				ClassLoader.getSystemClassLoader().getParent(), config, true);
		SourceUnit fakeSourceUnit = new SourceUnit("fake.groovy",
				new StringReaderSourceWithURI("", URI.create("file:///fake.groovy"), config),
				config, classLoader, cu.getErrorCollector());
		// Don't compile — AST will be null
		List<ImportNode> result = finder.findUnusedImportsInSource(fakeSourceUnit);
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isEmpty());
	}

	@Test
	void testFindUnusedImportsWithUsedFieldType() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.Map\n" +
				"class Foo {\n" +
				"  Map data\n" +
				"}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		// Map is used as a field type
		boolean mapUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals("Map"));
		Assertions.assertFalse(mapUnused, "Map import should NOT be detected as unused");
	}

	@Test
	void testFindUnusedImportsWithUsedReturnType() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  List getItems() { return null }\n" +
				"}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		boolean listUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals("List"));
		Assertions.assertFalse(listUnused, "List import used as return type should NOT be unused");
	}

	@Test
	void testFindUnusedImportsWithUsedParameterType() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  void process(List items) {}\n" +
				"}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		boolean listUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals("List"));
		Assertions.assertFalse(listUnused, "List import used as parameter type should NOT be unused");
	}

	@Test
	void testFindUnusedImportsWithUsedSuperclass() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.ArrayList\n" +
				"class Foo extends ArrayList {}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		boolean arrayListUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals("ArrayList"));
		Assertions.assertFalse(arrayListUnused, "ArrayList used as superclass should NOT be unused");
	}

	@Test
	void testFindUnusedImportsWithUsedInterface() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.io.Serializable\n" +
				"class Foo implements Serializable {}\n");
		Map<URI, List<ImportNode>> result = finder.findUnusedImports(cu);
		boolean serializableUnused = result.values().stream()
				.flatMap(List::stream)
				.anyMatch(i -> i.getType() != null &&
						i.getType().getNameWithoutPackage().equals("Serializable"));
		Assertions.assertFalse(serializableUnused,
				"Serializable used as interface should NOT be unused");
	}

	// ------------------------------------------------------------------
	// Helper
	// ------------------------------------------------------------------

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
			// expected for some test cases
		}
		return cu;
	}
}
