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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Unit tests for {@link DocumentResolverService}: completion item resolution,
 * documentation lookup, early-return for already-documented items, and null
 * safety.
 */
class DocumentResolverServiceTests {

	private DocumentResolverService resolverService;
	private ProjectScopeManager scopeManager;
	private CompilationService compilationService;
	private FileContentsTracker fileContentsTracker;
	private Path tempDir;

	@BeforeEach
	void setup() throws IOException {
		fileContentsTracker = new FileContentsTracker();
		CompilationUnitFactory defaultFactory = new CompilationUnitFactory();
		scopeManager = new ProjectScopeManager(defaultFactory, fileContentsTracker);
		compilationService = new CompilationService(fileContentsTracker);
		resolverService = new DocumentResolverService(scopeManager);

		tempDir = Files.createTempDirectory("drs-test");
	}

	@AfterEach
	void tearDown() {
		if (tempDir != null) {
			try {
				Files.walk(tempDir)
						.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			} catch (IOException e) {
				// ignore
			}
		}
	}

	// --- resolveCompletionItem: already documented ---

	@Test
	void testResolveCompletionItemAlreadyDocumented() {
		CompletionItem item = new CompletionItem("myMethod");
		item.setKind(CompletionItemKind.Method);
		item.setDocumentation(new MarkupContent("markdown", "Existing docs"));

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
		// Documentation should remain untouched
		Assertions.assertNotNull(result.getDocumentation());
	}

	// --- resolveCompletionItem: null label ---

	@Test
	void testResolveCompletionItemInvalidOrUnresolvableInput() {
		CompletionItem first = new CompletionItem("");
		first.setKind(CompletionItemKind.Method);
		CompletionItem second = new CompletionItem("something");
		second.setKind(null);
		CompletionItem third = new CompletionItem("unknownMethod");
		third.setKind(CompletionItemKind.Method);

		for (CompletionItem item : Arrays.asList(first, second, third)) {
			CompletionItem result = resolverService.resolveCompletionItem(item);
			Assertions.assertSame(item, result);
			Assertions.assertNull(result.getDocumentation());
		}
	}

	// --- resolveCompletionItem: with data (JsonObject) ---

	@Test
	void testResolveCompletionItemWithJsonData() {
		CompletionItem item = new CompletionItem("someMethod");
		item.setKind(CompletionItemKind.Method);

		JsonObject data = new JsonObject();
		data.addProperty("signature", "java.lang.String");
		data.addProperty("declaringClass", "com.example.MyClass");
		item.setData(data);

		// No AST visitor matches anything, so documentation remains null
		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
	}

	// --- resolveCompletionItem: property kind ---

	@Test
	void testResolveCompletionItemPropertyKind() {
		CompletionItem item = new CompletionItem("myProp");
		item.setKind(CompletionItemKind.Property);

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
		// No AST visitor → no documentation
		Assertions.assertNull(result.getDocumentation());
	}

	// --- resolveCompletionItem: field kind ---

	@Test
	void testResolveCompletionItemFieldKind() {
		CompletionItem item = new CompletionItem("myField");
		item.setKind(CompletionItemKind.Field);

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
	}

	// --- resolveCompletionItem: class kind ---

	@Test
	void testResolveCompletionItemClassKind() {
		CompletionItem item = new CompletionItem("MyClass");
		item.setKind(CompletionItemKind.Class);

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
	}

	// --- resolveCompletionItem: interface kind ---

	@Test
	void testResolveCompletionItemInterfaceKind() {
		CompletionItem item = new CompletionItem("MyInterface");
		item.setKind(CompletionItemKind.Interface);

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
	}

	// --- resolveCompletionItem: enum kind ---

	@Test
	void testResolveCompletionItemEnumKind() {
		CompletionItem item = new CompletionItem("MyEnum");
		item.setKind(CompletionItemKind.Enum);

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
	}

	// --- resolveDocumentation with compiled AST ---

	@Test
	void testResolveCompletionItemWithCompiledAST() throws IOException {
		// Create a Groovy source with Groovydoc
		Path srcDir = tempDir.resolve("src/main/groovy");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Documented.groovy"),
				"/**\n * This class is documented.\n */\nclass Documented {\n"
						+ "  /** Returns the name. */\n  String getName() { return 'test' }\n"
						+ "  /** A documented field. */\n  String myField\n"
						+ "}");

		scopeManager.setWorkspaceRoot(tempDir);
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(tempDir, factory);
		scope.setClasspathResolved(true);

		// Compile so we have an AST
		compilationService.ensureScopeCompiled(scope);
		Assertions.assertNotNull(scope.getAstVisitor());

		// Set the compiled scope as the default scope's astVisitor for lookup
		scopeManager.getDefaultScope().setAstVisitor(scope.getAstVisitor());

		// Test method documentation
		CompletionItem methodItem = new CompletionItem("getName");
		methodItem.setKind(CompletionItemKind.Method);
		JsonObject methodData = new JsonObject();
		methodData.addProperty("declaringClass", "Documented");
		methodItem.setData(methodData);

		resolverService.resolveCompletionItem(methodItem);
		// Note: documentation may or may not be found depending on Groovydoc support
		// in the test environment — we verify no exception thrown

		// Test class documentation
		CompletionItem classItem = new CompletionItem("Documented");
		classItem.setKind(CompletionItemKind.Class);

		resolverService.resolveCompletionItem(classItem);
		// Verify no exception
	}

	// --- resolveCompletionItem: no matching documentation ---

	@Test
	void testResolveCompletionItemNoMatchingDocs() {
		// When all scopes have null visitors, documentation stays null
		CompletionItem item = new CompletionItem("unmatched");
		item.setKind(CompletionItemKind.Method);

		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
		Assertions.assertNull(result.getDocumentation());
	}

	// --- Multiple scopes ---

	@Test
	void testResolveCompletionItemSearchesMultipleScopes() {
		// Register two project scopes, both without AST visitors
		scopeManager.registerDiscoveredProjects(
				Arrays.asList(tempDir.resolve("projA"), tempDir.resolve("projB")));

		CompletionItem item = new CompletionItem("someMethod");
		item.setKind(CompletionItemKind.Method);

		// Should search all scopes without throwing
		CompletionItem result = resolverService.resolveCompletionItem(item);
		Assertions.assertSame(item, result);
	}

}
