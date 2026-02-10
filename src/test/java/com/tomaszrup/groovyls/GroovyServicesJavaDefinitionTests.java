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
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for Go-to-Definition from Groovy source files to Java source files
 * in the same project (single-module layout).
 */
class GroovyServicesJavaDefinitionTests {
	private static final String LANGUAGE_GROOVY = "groovy";
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_GROOVY_SRC = "./src/main/groovy";
	private static final String PATH_JAVA_SRC = "./src/main/java";

	private GroovyServices services;
	private Path workspaceRoot;
	private Path groovySrcRoot;
	private Path javaSrcRoot;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		groovySrcRoot = workspaceRoot.resolve(PATH_GROOVY_SRC);
		javaSrcRoot = workspaceRoot.resolve(PATH_JAVA_SRC);
		if (!Files.exists(groovySrcRoot)) {
			groovySrcRoot.toFile().mkdirs();
		}
		if (!Files.exists(javaSrcRoot)) {
			javaSrcRoot.toFile().mkdirs();
		}

		services = new GroovyServices(new CompilationUnitFactory());
		services.setWorkspaceRoot(workspaceRoot);
		services.connect(new LanguageClient() {
			@Override
			public void telemetryEvent(Object object) {}

			@Override
			public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
				return null;
			}

			@Override
			public void showMessage(MessageParams messageParams) {}

			@Override
			public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}

			@Override
			public void logMessage(MessageParams message) {}
		});
	}

	@AfterEach
	void tearDown() {
		TestWorkspaceHelper.cleanSrcDirectory(groovySrcRoot);
		TestWorkspaceHelper.cleanSrcDirectory(javaSrcRoot);
		if (services != null) {
			services.setWorkspaceRoot(null);
		}
		services = null;
		workspaceRoot = null;
		groovySrcRoot = null;
		javaSrcRoot = null;
	}

	/**
	 * Verify that JavaSourceLocator indexes Java files alongside Groovy files
	 * in the same project — so "Go to Definition" on a Java class reference
	 * from a Groovy file navigates to the .java source (not a decompiled stub).
	 */
	@Test
	void testDefinitionNavigatesToJavaSourceInSameProject() throws Exception {
		// 1. Create a Java class in src/main/java
		Path javaDir = javaSrcRoot.resolve("com/example");
		Files.createDirectories(javaDir);
		Path javaFile = javaDir.resolve("MyJavaClass.java");
		Files.writeString(javaFile,
				"package com.example;\n"
				+ "\n"
				+ "public class MyJavaClass {\n"
				+ "    public String greet() {\n"
				+ "        return \"hello\";\n"
				+ "    }\n"
				+ "}\n");

		// 2. Refresh the locator so it picks up the newly-created Java file
		ProjectScope scope = services.getScopeManager().getDefaultScope();
		Assertions.assertNotNull(scope, "Should have a default scope");
		scope.getJavaSourceLocator().refresh();

		// 3. Create a Groovy file that references the Java class
		Path groovyFile = groovySrcRoot.resolve("TestConsumer.groovy");
		String groovyUri = groovyFile.toUri().toString();
		StringBuilder groovyContent = new StringBuilder();
		groovyContent.append("import com.example.MyJavaClass\n");
		groovyContent.append("\n");
		groovyContent.append("class TestConsumer {\n");
		groovyContent.append("    void run() {\n");
		groovyContent.append("        MyJavaClass obj\n");
		groovyContent.append("    }\n");
		groovyContent.append("}");

		TextDocumentItem textDocumentItem = new TextDocumentItem(groovyUri, LANGUAGE_GROOVY, 1,
				groovyContent.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		// 4. Verify the Java source locator has the Java class indexed
		Assertions.assertNotNull(scope.getJavaSourceLocator(), "Scope should have a JavaSourceLocator");
		Assertions.assertTrue(scope.getJavaSourceLocator().hasSource("com.example.MyJavaClass"),
				"JavaSourceLocator should have indexed the Java class from src/main/java");

		// 5. "Go to Definition" on MyJavaClass in the import statement (line 0, col ~25)
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(groovyUri);
		// Position on "MyJavaClass" in line 0: "import com.example.MyJavaClass"
		// "MyJavaClass" starts at column 19
		Position importPosition = new Position(0, 25);
		DefinitionParams importParams = new DefinitionParams(textDocument, importPosition);
		Either<List<? extends Location>, List<? extends LocationLink>> importResult =
				services.definition(importParams).get();
		Assertions.assertNotNull(importResult, "Definition result should not be null");
		List<? extends Location> importLocations = importResult.getLeft();
		Assertions.assertNotNull(importLocations, "Should return left (Location list)");
		Assertions.assertFalse(importLocations.isEmpty(),
				"Should find at least one definition location for the Java class");

		Location loc = importLocations.get(0);
		String locationUri = loc.getUri();
		// Verify it points to the .java file, not a decompiled stub
		Assertions.assertTrue(locationUri.endsWith("MyJavaClass.java"),
				"Definition should point to MyJavaClass.java, got: " + locationUri);
		Assertions.assertFalse(locationUri.startsWith("decompiled:"),
				"Should NOT be a decompiled URI: " + locationUri);
		Assertions.assertFalse(locationUri.startsWith("jar:"),
				"Should NOT be a JAR URI for a project source: " + locationUri);
	}

	/**
	 * Verify that Go-to-Definition on a Java inner class reference
	 * from Groovy navigates to the inner class declaration in the .java file.
	 */
	@Test
	void testDefinitionNavigatesToJavaInnerClassSource() throws Exception {
		// Create a Java class with an inner class
		Path javaDir = javaSrcRoot.resolve("com/example");
		Files.createDirectories(javaDir);
		Path javaFile = javaDir.resolve("Container.java");
		Files.writeString(javaFile,
				"package com.example;\n"
				+ "\n"
				+ "public class Container {\n"
				+ "\n"
				+ "    public static class Builder {\n"
				+ "        public Builder withName(String name) { return this; }\n"
				+ "    }\n"
				+ "}\n");

		// Refresh so the locator picks up the newly-created Java file
		ProjectScope scope = services.getScopeManager().getDefaultScope();
		Assertions.assertNotNull(scope.getJavaSourceLocator());
		scope.getJavaSourceLocator().refresh();

		Assertions.assertTrue(scope.getJavaSourceLocator().hasSource("com.example.Container"),
				"Should have source for Container");
		Assertions.assertTrue(scope.getJavaSourceLocator().hasSource("com.example.Container$Builder"),
				"Should have source for Container$Builder via inner class fallback");

		Location loc = scope.getJavaSourceLocator().findLocationForClass("com.example.Container$Builder");
		Assertions.assertNotNull(loc, "Should find location for inner class");
		Assertions.assertTrue(loc.getUri().endsWith("Container.java"),
				"Should point to Container.java");
		Assertions.assertEquals(4, loc.getRange().getStart().getLine(),
				"Should point to the Builder inner class declaration (line 4, 0-indexed)");
	}
}
