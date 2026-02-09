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
package com.tomaszrup.groovyls.compiler.control;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Unit tests for {@link GroovyLSCompilationUnit}: construction, source
 * removal, error collector integration, and AST module management.
 */
class GroovyLSCompilationUnitTests {

	private CompilerConfiguration config;

	@BeforeEach
	void setup() {
		config = new CompilerConfiguration();
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	@Test
	void testConstructionWithConfig() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);

		Assertions.assertNotNull(cu);
		Assertions.assertNotNull(cu.getErrorCollector());
		Assertions.assertInstanceOf(LanguageServerErrorCollector.class, cu.getErrorCollector());
	}

	@Test
	void testConstructionWithNullLoaderAndSecurity() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config, null, null);

		Assertions.assertNotNull(cu);
		Assertions.assertInstanceOf(LanguageServerErrorCollector.class, cu.getErrorCollector());
	}

	@Test
	void testSetErrorCollector() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);
		LanguageServerErrorCollector newCollector = new LanguageServerErrorCollector(config);

		cu.setErrorCollector(newCollector);

		Assertions.assertSame(newCollector, cu.getErrorCollector());
	}

	// ------------------------------------------------------------------
	// addSource / removeSources
	// ------------------------------------------------------------------

	@Test
	void testRemoveSourcesFromEmptyUnit() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);

		// Should not throw when removing from an empty compilation unit
		Assertions.assertDoesNotThrow(() -> cu.removeSources(Collections.emptyList()));
	}

	@Test
	void testAddAndRemoveSingleSource() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);
		URI uri = URI.create("file:///test/Foo.groovy");
		String code = "class Foo { String name }";

		SourceUnit sourceUnit = addSource(cu, uri, code);
		compileToPhase(cu, Phases.SEMANTIC_ANALYSIS);

		// Verify source was added
		List<ModuleNode> modulesBefore = cu.getAST().getModules();
		Assertions.assertFalse(modulesBefore.isEmpty(), "Should have modules after compilation");

		// Remove the source
		cu.removeSources(Collections.singletonList(sourceUnit));

		// Modules should be cleared
		List<ModuleNode> modulesAfter = cu.getAST().getModules();
		Assertions.assertTrue(modulesAfter.isEmpty(),
				"Should have no modules after removing the only source");
	}

	@Test
	void testRemoveSourcePreservesOtherModules() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);
		URI uri1 = URI.create("file:///test/Alpha.groovy");
		URI uri2 = URI.create("file:///test/Beta.groovy");

		SourceUnit su1 = addSource(cu, uri1, "class Alpha {}");
		SourceUnit su2 = addSource(cu, uri2, "class Beta {}");
		compileToPhase(cu, Phases.SEMANTIC_ANALYSIS);

		int moduleCountBefore = cu.getAST().getModules().size();
		Assertions.assertEquals(2, moduleCountBefore, "Should have 2 modules");

		// Remove only the first source
		cu.removeSource(su1);

		int moduleCountAfter = cu.getAST().getModules().size();
		Assertions.assertEquals(1, moduleCountAfter, "Should have 1 module remaining");
	}

	@Test
	void testRemoveSourcesClearsErrors() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);
		URI uri = URI.create("file:///test/Bad.groovy");

		// Deliberately add code that causes a compilation error
		SourceUnit su = addSource(cu, uri, "class Bad { void foo( }");
		try {
			cu.compile(Phases.SEMANTIC_ANALYSIS);
		} catch (Exception e) {
			// Expected — compilation fails
		}

		// Error collector may have errors from the bad source
		// removeSources should clear them
		cu.removeSources(Collections.singletonList(su));

		LanguageServerErrorCollector collector = (LanguageServerErrorCollector) cu.getErrorCollector();
		// After clearing, the error collector should have no errors
		Assertions.assertFalse(collector.hasErrors(),
				"Error collector should be cleared after removeSources");
	}

	@Test
	void testRemoveMultipleSources() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);
		URI uri1 = URI.create("file:///test/One.groovy");
		URI uri2 = URI.create("file:///test/Two.groovy");
		URI uri3 = URI.create("file:///test/Three.groovy");

		SourceUnit su1 = addSource(cu, uri1, "class One {}");
		SourceUnit su2 = addSource(cu, uri2, "class Two {}");
		SourceUnit su3 = addSource(cu, uri3, "class Three {}");
		compileToPhase(cu, Phases.SEMANTIC_ANALYSIS);

		Assertions.assertEquals(3, cu.getAST().getModules().size());

		// Remove two of three
		cu.removeSources(Arrays.asList(su1, su3));

		Assertions.assertEquals(1, cu.getAST().getModules().size(),
				"Should have 1 module remaining after removing 2 of 3");
	}

	@Test
	void testRemoveSourceUsesRemoveSourcesSingletonPath() {
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config);
		URI uri = URI.create("file:///test/Single.groovy");

		SourceUnit su = addSource(cu, uri, "class Single {}");
		compileToPhase(cu, Phases.SEMANTIC_ANALYSIS);

		Assertions.assertEquals(1, cu.getAST().getModules().size());

		// removeSource is a convenience for removeSources(singleton)
		cu.removeSource(su);

		Assertions.assertTrue(cu.getAST().getModules().isEmpty());
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private SourceUnit addSource(GroovyLSCompilationUnit cu, URI uri, String code) {
		StringReaderSourceWithURI source = new StringReaderSourceWithURI(code, uri, cu.getConfiguration());
		SourceUnit sourceUnit = new SourceUnit(
				uri.toString(),
				source,
				cu.getConfiguration(),
				cu.getClassLoader(),
				cu.getErrorCollector());
		cu.addSource(sourceUnit);
		return sourceUnit;
	}

	private void compileToPhase(GroovyLSCompilationUnit cu, int phase) {
		try {
			cu.compile(phase);
		} catch (Exception e) {
			// May fail on resolution — acceptable for unit tests
		}
	}
}
