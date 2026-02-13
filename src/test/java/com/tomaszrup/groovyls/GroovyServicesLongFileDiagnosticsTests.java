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
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesLongFileDiagnosticsTests {
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
	void testSyntaxDiagnosticPublishedForHighLineInLongFile() {
		Path filePath = srcRoot.resolve("LongFileWithError.groovy");
		String uri = filePath.toUri().toString();

		StringBuilder contents = new StringBuilder();
		contents.append("class LongFileWithError {\n");
		for (int i = 0; i < 399; i++) {
			contents.append("  def value").append(i).append(" = ").append(i).append("\n");
		}
		contents.append("  def broken = \n");
		contents.append("}\n");

		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		List<Diagnostic> diagnostics = getDiagnosticsForUri(uri);
		Assertions.assertFalse(diagnostics.isEmpty(), "Expected diagnostics to be published for long file");

		boolean foundHighLineDiagnostic = diagnostics.stream()
				.anyMatch(diagnostic -> diagnostic.getRange() != null
						&& diagnostic.getRange().getStart() != null
						&& diagnostic.getRange().getStart().getLine() >= 399);

		Assertions.assertTrue(foundHighLineDiagnostic,
				"Expected at least one diagnostic on/after line 400 (0-indexed >= 399)");
	}

	private List<Diagnostic> getDiagnosticsForUri(String uri) {
		for (int i = publishedDiagnostics.size() - 1; i >= 0; i--) {
			PublishDiagnosticsParams params = publishedDiagnostics.get(i);
			if (params.getUri().equals(uri)) {
				return params.getDiagnostics();
			}
		}
		return new ArrayList<>();
	}
}
