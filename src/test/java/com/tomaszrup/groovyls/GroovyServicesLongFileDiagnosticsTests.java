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

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import groovy.lang.GroovyClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

class GroovyServicesLongFileDiagnosticsTests {
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_SRC = "./src/main/groovy";

	private Path workspaceRoot;
	private Path srcRoot;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		srcRoot = workspaceRoot.resolve(PATH_SRC);
		if (!Files.exists(srcRoot)) {
			srcRoot.toFile().mkdirs();
		}
	}

	@AfterEach
	void tearDown() {
		TestWorkspaceHelper.cleanSrcDirectory(srcRoot);
		workspaceRoot = null;
		srcRoot = null;
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
		contents.append("  def broken = \"unterminated\n");
		contents.append("}\n");

		try {
			Files.writeString(filePath, contents.toString());
		} catch (Exception e) {
			Assertions.fail("Could not prepare long-file fixture on disk", e);
		}

		CompilerConfiguration config = new CompilerConfiguration();
		GroovyClassLoader classLoader = new GroovyClassLoader(
				ClassLoader.getSystemClassLoader().getParent(), config, true);
		GroovyLSCompilationUnit cu = new GroovyLSCompilationUnit(config, null, classLoader);
		SourceUnit sourceUnit = new SourceUnit(uri,
				new StringReaderSourceWithURI(contents.toString(), filePath.toUri(), config),
				config, classLoader, cu.getErrorCollector());
		cu.addSource(sourceUnit);
		try {
			cu.compile(Phases.CANONICALIZATION);
		} catch (Exception e) {
			// expected for syntax error fixture
		}

		ErrorCollector collector = cu.getErrorCollector();
		boolean foundHighLineDiagnostic = collector.getErrors().stream()
				.filter(SyntaxErrorMessage.class::isInstance)
				.map(SyntaxErrorMessage.class::cast)
				.anyMatch(message -> message.getCause() != null
						&& message.getCause().getStartLine() >= 400);

		Assertions.assertTrue(foundHighLineDiagnostic,
				"Expected at least one syntax diagnostic on/after line 400 (1-indexed >= 400)");
	}
}
