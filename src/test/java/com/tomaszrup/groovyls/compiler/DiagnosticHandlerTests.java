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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
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
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.syntax.SyntaxException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;

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
	void testHandleErrorCollectorDeduplicatesDuplicateSyntaxDiagnostics() {
		URI uri = URI.create("file:///test-dedup.groovy");

		GroovyLSCompilationUnit baselineCu = compileSource("class Foo {\n  void bar(\n}\n", uri);
		ErrorCollector baselineCollector = baselineCu.getErrorCollector();
		DiagnosticHandler.DiagnosticResult baselineResult = handler.handleErrorCollector(
				baselineCu, baselineCollector, PROJECT_ROOT, null);
		int baselineCount = baselineResult.getDiagnosticsToPublish().stream()
				.mapToInt(p -> p.getDiagnostics() != null ? p.getDiagnostics().size() : 0)
				.sum();

		GroovyLSCompilationUnit duplicatedCu = compileSource("class Foo {\n  void bar(\n}\n", uri);
		ErrorCollector collector = duplicatedCu.getErrorCollector();

		Message firstSyntaxMessage = collector.getErrors().stream()
				.filter(Message.class::isInstance)
				.map(Message.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Expected at least one syntax error message"));
		collector.addErrorAndContinue(firstSyntaxMessage);

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				duplicatedCu, collector, PROJECT_ROOT, null);

		int duplicateInjectedCount = result.getDiagnosticsToPublish().stream()
				.mapToInt(p -> p.getDiagnostics() != null ? p.getDiagnostics().size() : 0)
				.sum();

		Assertions.assertTrue(baselineCount > 0,
				"Baseline syntax error should publish at least one diagnostic");
		Assertions.assertEquals(baselineCount, duplicateInjectedCount,
				"Injecting an identical compiler message should not increase published diagnostic count");
	}

	@Test
	void testHandleErrorCollectorWithWindowsFileUriSourceLocator() {
		URI windowsFileUri = URI.create("file:///c:/Users/test/WinPathSpec.groovy");
		GroovyLSCompilationUnit cu = compileSource("class Foo {\n  void bar(\n}\n", windowsFileUri);
		ErrorCollector collector = cu.getErrorCollector();

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		boolean hasDiagnosticsForWindowsUri = result.getDiagnosticsToPublish().stream()
				.anyMatch(p -> p.getUri().equals(windowsFileUri.toString()) && !p.getDiagnostics().isEmpty());
		Assertions.assertTrue(hasDiagnosticsForWindowsUri,
				"Should publish diagnostics for file URI source locator on Windows");
	}

	@Test
	void testHandleErrorCollectorSkipsInvalidSourceLocator() {
		URI validUri = URI.create("file:///test.groovy");
		GroovyLSCompilationUnit cu = compileSourceWithSourceName(
				"::::", "class Foo {\n  void bar(\n}\n", validUri);
		ErrorCollector collector = cu.getErrorCollector();

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		boolean hasAnyErrorDiagnostic = result.getDiagnosticsToPublish().stream()
				.anyMatch(p -> !p.getDiagnostics().isEmpty());
		Assertions.assertFalse(hasAnyErrorDiagnostic,
				"Should skip syntax diagnostics when source locator is invalid");
	}

	@Test
	void testHandleErrorCollectorSkipsEmptySourceLocator() {
		URI validUri = URI.create("file:///test-empty-locator.groovy");
		GroovyLSCompilationUnit cu = compileSourceWithSourceName(
				"", "class Foo {\n  void bar(\n}\n", validUri);
		ErrorCollector collector = cu.getErrorCollector();

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		boolean hasAnyErrorDiagnostic = result.getDiagnosticsToPublish().stream()
				.anyMatch(p -> !p.getDiagnostics().isEmpty());
		Assertions.assertFalse(hasAnyErrorDiagnostic,
				"Should skip syntax diagnostics when source locator is empty");
	}

	@Test
	void testHandleErrorCollectorFallsBackToZeroRangeWhenSyntaxRangeUnavailable() throws Exception {
		URI uri = URI.create("file:///fallback-range.groovy");
		GroovyLSCompilationUnit cu = compileSource("class FallbackRange {}\n", uri);

		CompilerConfiguration config = new CompilerConfiguration();
		ErrorCollector collector = new ErrorCollector(config);
		SyntaxException syntax = new SyntaxException("forced range fallback", -1, 1, -1, 1);
		SourceUnit sourceUnit = new SourceUnit(uri.toString(),
				new StringReaderSourceWithURI("class FallbackRange {}\n", uri, config),
				config, cu.getClassLoader(), collector);

		Class<?> semClass = Class.forName("org.codehaus.groovy.control.messages.SyntaxErrorMessage");
		Constructor<?> ctor = semClass.getConstructor(SyntaxException.class, SourceUnit.class);
		Message message = (Message) ctor.newInstance(syntax, sourceUnit);
		collector.addErrorAndContinue(message);

		DiagnosticHandler.DiagnosticResult result = handler.handleErrorCollector(
				cu, collector, PROJECT_ROOT, null);

		Diagnostic diagnostic = result.getDiagnosticsToPublish().stream()
				.flatMap(p -> p.getDiagnostics().stream())
				.findFirst()
				.orElseThrow(() -> new AssertionError("Expected a diagnostic for forced syntax error"));

		Assertions.assertEquals(new Range(new Position(0, 0), new Position(0, 0)), diagnostic.getRange(),
				"Should use zero range fallback when syntax range cannot be converted");
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

	@Test
	void testExtractImportTokenViaReflection() throws Exception {
		Method m = DiagnosticHandler.class.getDeclaredMethod("extractImportToken", String.class);
		m.setAccessible(true);

		Assertions.assertNull(m.invoke(handler, "class Foo {}"));
		Assertions.assertNull(m.invoke(handler, "<unavailable>"));
		Assertions.assertEquals("java.util.List", m.invoke(handler, "import java.util.List;"));
	}

	@Test
	void testReadLineFromLocatorViaReflection() throws Exception {
		Method m = DiagnosticHandler.class.getDeclaredMethod("readLineFromLocator", String.class, int.class);
		m.setAccessible(true);

		Path tmp = Files.createTempFile("diag-handler", ".groovy");
		try {
			Files.writeString(tmp, "line1\nline2\n");
			String inRange = (String) m.invoke(handler, tmp.toUri().toString(), 2);
			String outOfRange = (String) m.invoke(handler, tmp.toUri().toString(), 10);
			String nonFile = (String) m.invoke(handler, "jar:file:///tmp/a.jar!/A.groovy", 1);

			Assertions.assertEquals("line2", inRange);
			Assertions.assertEquals("<line-out-of-range>", outOfRange);
			Assertions.assertEquals("<not-a-file>", nonFile);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	// ------------------------------------------------------------------
	// Helper
	// ------------------------------------------------------------------

	private GroovyLSCompilationUnit compileSource(String source) {
		return compileSource(source, URI.create("file:///test.groovy"));
	}

	private GroovyLSCompilationUnit compileSource(String source, URI uri) {
		return compileSourceWithSourceName(uri.toString(), source, uri);
	}

	private GroovyLSCompilationUnit compileSourceWithSourceName(String sourceName, String source, URI uri) {
		CompilerConfiguration config = new CompilerConfiguration();
		GroovyClassLoader classLoader = new GroovyClassLoader(
				ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config, null, classLoader);
		SourceUnit sourceUnit = new SourceUnit(sourceName,
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
