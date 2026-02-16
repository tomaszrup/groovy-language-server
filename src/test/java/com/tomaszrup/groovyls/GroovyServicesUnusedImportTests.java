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
package com.tomaszrup.groovyls;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

@SuppressWarnings("all")
class GroovyServicesUnusedImportTests {
	private static final String LANGUAGE_GROOVY = "groovy";
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_SRC = "./src/main/groovy";

	private GroovyServices services;
	private Path workspaceRoot;
	private Path srcRoot;
	private List<PublishDiagnosticsParams> publishedDiagnostics;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		srcRoot = workspaceRoot.resolve(PATH_SRC);
		if (!Files.exists(srcRoot)) {
			srcRoot.toFile().mkdirs();
		}

		publishedDiagnostics = new ArrayList<>();

		services = new GroovyServices(new CompilationUnitFactory());
		services.setWorkspaceRoot(workspaceRoot);
		services.connect(new TestLanguageClient(publishedDiagnostics::add));
	}

	@AfterEach
	void tearDown() {
		TestWorkspaceHelper.cleanSrcDirectory(srcRoot);
		if (services != null) {
			services.setWorkspaceRoot(null);
		}
		services = null;
		workspaceRoot = null;
		srcRoot = null;
		publishedDiagnostics = null;
	}

	@Test
	void testUnusedImportIsReportedAsUnnecessary() throws Exception {
		Path filePath = srcRoot.resolve("UnusedImportTest1.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class UnusedImportTest1 {\n");
		contents.append("  void hello() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(1, unusedDiagnostics.size(),
				"Should report exactly one unused import");
		Diagnostic diag = unusedDiagnostics.get(0);
		Assertions.assertEquals(DiagnosticSeverity.Hint, diag.getSeverity());
		Assertions.assertTrue(diag.getTags().contains(DiagnosticTag.Unnecessary));
		Assertions.assertEquals("Unused import", diag.getMessage());
		// The import is on line 0 (0-indexed)
		Assertions.assertEquals(0, diag.getRange().getStart().getLine());
	}

	@Test
	void testUsedImportIsNotReported() throws Exception {
		Path filePath = srcRoot.resolve("UsedImportTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class UsedImportTest {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report any unused imports when the import is used");
	}

	@Test
	void testMultipleUnusedImports() throws Exception {
		Path filePath = srcRoot.resolve("MultiUnusedImportTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("import java.util.HashMap\n");
		contents.append("import java.util.LinkedList\n");
		contents.append("\n");
		contents.append("class MultiUnusedImportTest {\n");
		contents.append("  void hello() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(3, unusedDiagnostics.size(),
				"Should report three unused imports");
	}

	@Test
	void testMixOfUsedAndUnusedImports() throws Exception {
		Path filePath = srcRoot.resolve("MixedImportTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("import java.util.HashMap\n");
		contents.append("\n");
		contents.append("class MixedImportTest {\n");
		contents.append("  ArrayList<String> list = new ArrayList<>()\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(1, unusedDiagnostics.size(),
				"Should report only the unused HashMap import");
		// HashMap is on line 1
		Assertions.assertEquals(1, unusedDiagnostics.get(0).getRange().getStart().getLine());
	}

	@Test
	void testNoImportsNoUnusedDiagnostics() throws Exception {
		Path filePath = srcRoot.resolve("NoImportTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class NoImportTest {\n");
		contents.append("  void hello() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report any unused imports when there are no imports");
	}

	@Test
	void testImportUsedInMethodParameter() throws Exception {
		Path filePath = srcRoot.resolve("ImportInParamTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class ImportInParamTest {\n");
		contents.append("  void process(ArrayList<String> items) {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report import used as method parameter type");
	}

	@Test
	void testImportUsedInReturnType() throws Exception {
		Path filePath = srcRoot.resolve("ImportInReturnTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class ImportInReturnTest {\n");
		contents.append("  ArrayList<String> getItems() { return new ArrayList<>() }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report import used as method return type");
	}

	@Test
	void testImportUsedInFieldType() throws Exception {
		Path filePath = srcRoot.resolve("ImportInFieldTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.HashMap\n");
		contents.append("\n");
		contents.append("class ImportInFieldTest {\n");
		contents.append("  HashMap<String, String> map\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report import used as field type");
	}

	@Test
	void testImportUsedInConstructorCall() throws Exception {
		Path filePath = srcRoot.resolve("ImportInCtorTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class ImportInCtorTest {\n");
		contents.append("  def list = new ArrayList()\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report import used in constructor call");
	}

	@Test
	void testImportUsedAsSuperclass() throws Exception {
		Path filePath = srcRoot.resolve("ImportAsSuperTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class ImportAsSuperTest extends ArrayList {\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(0, unusedDiagnostics.size(),
				"Should not report import used as superclass");
	}

	@Test
	void testUnusedDiagnosticHasSourceGroovy() throws Exception {
		Path filePath = srcRoot.resolve("SourceTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("import java.util.ArrayList\n");
		contents.append("\n");
		contents.append("class SourceTest {\n");
		contents.append("  void hello() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		List<Diagnostic> unusedDiagnostics = filterUnusedImportDiagnostics(diagnostics);
		Assertions.assertEquals(1, unusedDiagnostics.size());
		Assertions.assertEquals("groovy", unusedDiagnostics.get(0).getSource());
	}

	// --- Helpers

	private List<Diagnostic> getDiagnosticsForUri(String uri) {
		for (int i = publishedDiagnostics.size() - 1; i >= 0; i--) {
			PublishDiagnosticsParams params = publishedDiagnostics.get(i);
			if (params.getUri().equals(uri)) {
				return params.getDiagnostics();
			}
		}
		return new ArrayList<>();
	}

	private List<Diagnostic> filterUnusedImportDiagnostics(List<Diagnostic> diagnostics) {
		List<Diagnostic> result = new ArrayList<>();
		for (Diagnostic d : diagnostics) {
			if (d.getTags() != null && d.getTags().contains(DiagnosticTag.Unnecessary)
					&& "Unused import".equals(d.getMessage())) {
				result.add(d);
			}
		}
		return result;
	}
}
