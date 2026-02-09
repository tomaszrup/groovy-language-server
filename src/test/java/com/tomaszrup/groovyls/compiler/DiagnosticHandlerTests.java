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
package com.tomaszrup.groovyls.compiler;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Unit tests for {@link DiagnosticHandler}: conversion of compiler errors
 * and unused imports into LSP diagnostics.
 */
class DiagnosticHandlerTests {

	private DiagnosticHandler handler;
	private static final Path PROJECT_ROOT = Paths.get("/test/project");

	@BeforeEach
	void setup() {
		handler = new DiagnosticHandler();
	}

	@Test
	void testHandleErrorCollectorWithNoErrors() {
		GroovyLSCompilationUnit cu = compileSource("class Foo {}\n");
		ErrorCollector collector = cu.getErrorCollector();

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		Assertions.assertNotNull(result);
		Assertions.assertNotNull(result.getDiagnosticsToPublish());
		Assertions.assertNotNull(result.getDiagnosticsByFile());
	}

	@Test
	void testHandleErrorCollectorWithSyntaxError() {
		GroovyLSCompilationUnit cu = compileSource("class Foo {\n  void bar(\n}\n");
		ErrorCollector collector = cu.getErrorCollector();

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		Assertions.assertNotNull(result);
		Set<PublishDiagnosticsParams> params = result.getDiagnosticsToPublish();
		Assertions.assertNotNull(params);
		// Should have at least one diagnostic from the syntax error
		boolean hasDiagnostics = params.stream()
				.anyMatch(p -> !p.getDiagnostics().isEmpty());
		Assertions.assertTrue(hasDiagnostics, "Should have diagnostics for syntax error");
	}

	@Test
	void testHandleErrorCollectorClearsOldDiagnostics() {
		GroovyLSCompilationUnit cu = compileSource("class Foo {}\n");
		ErrorCollector collector = cu.getErrorCollector();

		// Simulate previous round having diagnostics for a file
		Map<URI, List<Diagnostic>> prevDiagnostics = new HashMap<>();
		URI oldUri = URI.create("file:///old-file.groovy");
		List<Diagnostic> oldDiags = new ArrayList<>();
		Diagnostic diag = new Diagnostic();
		diag.setMessage("old error");
		oldDiags.add(diag);
		prevDiagnostics.put(oldUri, oldDiags);

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, prevDiagnostics);

		// The old file should get empty diagnostics to clear them
		boolean clearsOldFile = result.getDiagnosticsToPublish().stream()
				.anyMatch(p -> p.getUri().equals(oldUri.toString()) && p.getDiagnostics().isEmpty());
		Assertions.assertTrue(clearsOldFile, "Should clear diagnostics for files no longer in error");
	}

	@Test
	void testHandleErrorCollectorWithUnusedImports() {
		GroovyLSCompilationUnit cu = compileSource(
				"import java.util.List\n" +
				"class Foo {\n" +
				"  String name\n" +
				"}\n");
		ErrorCollector collector = cu.getErrorCollector();

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		Assertions.assertNotNull(result);
		// Should find unused import for java.util.List
		boolean hasUnusedImport = result.getDiagnosticsToPublish().stream()
				.flatMap(p -> p.getDiagnostics().stream())
				.anyMatch(d -> d.getMessage().contains("Unused import"));
		Assertions.assertTrue(hasUnusedImport, "Should detect unused import");
	}

	@Test
	void testDiagnosticResultAccessors() {
		Map<URI, List<Diagnostic>> map = new HashMap<>();
		Set<PublishDiagnosticsParams> params = java.util.Collections.emptySet();
		DiagnosticHandler.DiagnosticResult result = new DiagnosticHandler.DiagnosticResult(params, map);

		Assertions.assertSame(params, result.getDiagnosticsToPublish());
		Assertions.assertSame(map, result.getDiagnosticsByFile());
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
			// expected for syntax error tests
		}
		return cu;
	}
}
