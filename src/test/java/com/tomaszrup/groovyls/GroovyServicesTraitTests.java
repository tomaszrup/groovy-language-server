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
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

class GroovyServicesTraitTests {
	private static final String LANGUAGE_GROOVY = "groovy";
	private static final String PATH_WORKSPACE = "./build/test_workspace/";
	private static final String PATH_SRC = "./src/main/groovy";

	private GroovyServices services;
	private Path workspaceRoot;
	private Path srcRoot;

	@BeforeEach
	void setup() {
		workspaceRoot = Paths.get(System.getProperty("user.dir")).resolve(PATH_WORKSPACE);
		srcRoot = workspaceRoot.resolve(PATH_SRC);
		if (!Files.exists(srcRoot)) {
			srcRoot.toFile().mkdirs();
		}

		services = new GroovyServices(new CompilationUnitFactory());
		services.setWorkspaceRoot(workspaceRoot);
		services.connect(new TestLanguageClient());
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
	}

	// --- trait definition

	@Test
	void testTraitDefinitionFromDeclaration() throws Exception {
		Path filePath = srcRoot.resolve("Traits.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait Traits {\n");
		contents.append("  String hello() { 'hello' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 7);
		List<? extends Location> locations = services.definition(new DefinitionParams(textDocument, position)).get()
				.getLeft();
		Assertions.assertEquals(1, locations.size());
		Location location = locations.get(0);
		Assertions.assertEquals(uri, location.getUri());
		Assertions.assertEquals(0, location.getRange().getStart().getLine());
		Assertions.assertEquals(0, location.getRange().getStart().getCharacter());
	}

	@Test
	void testTraitDefinitionFromImplementingClass() throws Exception {
		Path filePath = srcRoot.resolve("MyTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait MyTrait {\n");
		contents.append("  String greet() { 'hi' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("MyClass.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class MyClass implements MyTrait {\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		TextDocumentIdentifier textDocument2 = new TextDocumentIdentifier(uri2);
		Position position = new Position(0, 27);
		List<? extends Location> locations = services.definition(new DefinitionParams(textDocument2, position)).get()
				.getLeft();
		Assertions.assertEquals(1, locations.size());
		Location location = locations.get(0);
		Assertions.assertEquals(uri, location.getUri());
		Assertions.assertEquals(0, location.getRange().getStart().getLine());
		Assertions.assertEquals(0, location.getRange().getStart().getCharacter());
	}

	// --- trait hover

	@Test
	void testTraitHoverShowsTraitKeyword() throws Exception {
		Path filePath = srcRoot.resolve("HoverTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait HoverTrait {\n");
		contents.append("  String hello() { 'hello' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 7);
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();
		Assertions.assertNotNull(hover);
		String hoverContent = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverContent.contains("trait HoverTrait"),
				"Hover should show 'trait' keyword, but was: " + hoverContent);
		Assertions.assertFalse(hoverContent.contains("interface HoverTrait"),
				"Hover should not show 'interface' keyword for traits, but was: " + hoverContent);
	}

	@Test
	void testTraitMethodHover() throws Exception {
		Path filePath = srcRoot.resolve("MethodTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait MethodTrait {\n");
		contents.append("  String greet() { 'hi' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 11);
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();
		Assertions.assertNotNull(hover);
		String hoverContent = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverContent.contains("greet"),
				"Hover should show the trait method name, but was: " + hoverContent);
	}

	// --- trait document symbols   

	@Test
	void testTraitDocumentSymbol() throws Exception {
		Path filePath = srcRoot.resolve("SymTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait SymTrait {\n");
		contents.append("  String hello() { 'hello' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();
		Assertions.assertTrue(symbols.size() > 0);
		// Trait should appear as Interface symbol kind
		List<Either<SymbolInformation, DocumentSymbol>> traitSymbols = symbols.stream().filter(symbol -> {
			SymbolInformation info = symbol.getLeft();
			return info != null && info.getName().contains("SymTrait")
					&& info.getKind().equals(SymbolKind.Interface);
		}).collect(Collectors.toList());
		Assertions.assertEquals(1, traitSymbols.size());
	}

	@Test
	void testTraitMethodDocumentSymbol() throws Exception {
		Path filePath = srcRoot.resolve("SymTrait2.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait SymTrait2 {\n");
		contents.append("  String hello() { 'hello' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		List<Either<SymbolInformation, DocumentSymbol>> symbols = services
				.documentSymbol(new DocumentSymbolParams(textDocument)).get();
		List<Either<SymbolInformation, DocumentSymbol>> methodSymbols = symbols.stream().filter(symbol -> {
			SymbolInformation info = symbol.getLeft();
			return info != null && info.getName().equals("hello")
					&& info.getKind().equals(SymbolKind.Method);
		}).collect(Collectors.toList());
		Assertions.assertEquals(1, methodSymbols.size());
	}

	// --- trait completion

	@Test
	void testCompletionOfTraitMethodOnImplementingClass() throws Exception {
		Path filePath = srcRoot.resolve("CompTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait CompTrait {\n");
		contents.append("  String traitMethod() { 'from trait' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("CompClass.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class CompClass implements CompTrait {\n");
		contents2.append("  public CompClass() {\n");
		contents2.append("    this.\n");
		contents2.append("  }\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		TextDocumentIdentifier textDocument2 = new TextDocumentIdentifier(uri2);
		Position position = new Position(2, 9);
		Either<List<CompletionItem>, CompletionList> result = services
				.completion(new CompletionParams(textDocument2, position)).get();
		Assertions.assertTrue(result.isLeft());
		List<CompletionItem> items = result.getLeft();
		List<CompletionItem> filteredItems = items.stream().filter(item -> {
			return item.getLabel().equals("traitMethod") && item.getKind().equals(CompletionItemKind.Method);
		}).collect(Collectors.toList());
		Assertions.assertEquals(1, filteredItems.size(),
				"Trait method should be available in completion on implementing class");
	}

	@Test
	void testCompletionOfTraitMethodDirectly() throws Exception {
		Path filePath = srcRoot.resolve("DirectTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait DirectTrait {\n");
		contents.append("  String traitDirectMethod() { 'direct' }\n");
		contents.append("  void other() {\n");
		contents.append("    this.\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(3, 9);
		Either<List<CompletionItem>, CompletionList> result = services
				.completion(new CompletionParams(textDocument, position)).get();
		Assertions.assertTrue(result.isLeft());
		List<CompletionItem> items = result.getLeft();
		List<CompletionItem> filteredItems = items.stream().filter(item -> {
			return item.getLabel().equals("traitDirectMethod") && item.getKind().equals(CompletionItemKind.Method);
		}).collect(Collectors.toList());
		Assertions.assertEquals(1, filteredItems.size(),
				"Trait method should be available in completion within the trait itself");
	}

	// --- trait type definition

	@Test
	void testTypeDefinitionFromTraitVariable() throws Exception {
		Path filePath = srcRoot.resolve("TypeDefTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait TypeDefTrait {\n");
		contents.append("  String greet() { 'hi' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("TypeDefUser.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class TypeDefUser {\n");
		contents2.append("  public TypeDefUser() {\n");
		contents2.append("    TypeDefTrait t\n");
		contents2.append("  }\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		TextDocumentIdentifier textDocument2 = new TextDocumentIdentifier(uri2);
		// position on variable 't' at column 17
		Position position = new Position(2, 17);
		List<? extends Location> locations = services
				.typeDefinition(new TypeDefinitionParams(textDocument2, position)).get().getLeft();
		Assertions.assertEquals(1, locations.size());
		Location location = locations.get(0);
		Assertions.assertEquals(uri, location.getUri());
		Assertions.assertEquals(0, location.getRange().getStart().getLine());
		Assertions.assertEquals(0, location.getRange().getStart().getCharacter());
	}

	// --- trait with extends (trait extending interfaces)

	@Test
	void testTraitHoverShowsExtends() throws Exception {
		Path filePath = srcRoot.resolve("ExtTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait ExtTrait extends Serializable {\n");
		contents.append("  String hello() { 'hello' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));
		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 7);
		Hover hover = services.hover(new HoverParams(textDocument, position)).get();
		Assertions.assertNotNull(hover);
		String hoverContent = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverContent.contains("trait ExtTrait"),
				"Hover should show 'trait' keyword, but was: " + hoverContent);
		Assertions.assertTrue(hoverContent.contains("Serializable"),
				"Hover should show the extended interface, but was: " + hoverContent);
	}

	// --- trait implementation (class implements trait)

	@Test
	void testTraitImplementsClauseInHover() throws Exception {
		Path filePath = srcRoot.resolve("ImplTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait ImplTrait {\n");
		contents.append("  String greet() { 'hi' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("ImplClass.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class ImplClass implements ImplTrait {\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		TextDocumentIdentifier textDocument2 = new TextDocumentIdentifier(uri2);
		Position position = new Position(0, 7);
		Hover hover = services.hover(new HoverParams(textDocument2, position)).get();
		Assertions.assertNotNull(hover);
		String hoverContent = hover.getContents().getRight().getValue();
		Assertions.assertTrue(hoverContent.contains("class ImplClass"),
				"Hover should show 'class' keyword, but was: " + hoverContent);
		Assertions.assertTrue(hoverContent.contains("implements ImplTrait"),
				"Hover should show implements clause with trait, but was: " + hoverContent);
	}

	// --- multiple traits

	@Test
	void testMultipleTraitsCompletion() throws Exception {
		Path filePath = srcRoot.resolve("TraitA.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait TraitA {\n");
		contents.append("  String methodFromA() { 'A' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("TraitB.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("trait TraitB {\n");
		contents2.append("  String methodFromB() { 'B' }\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		Path filePath3 = srcRoot.resolve("MultiTraitClass.groovy");
		String uri3 = filePath3.toUri().toString();
		StringBuilder contents3 = new StringBuilder();
		contents3.append("class MultiTraitClass implements TraitA, TraitB {\n");
		contents3.append("  public MultiTraitClass() {\n");
		contents3.append("    this.\n");
		contents3.append("  }\n");
		contents3.append("}");
		TextDocumentItem textDocumentItem3 = new TextDocumentItem(uri3, LANGUAGE_GROOVY, 1, contents3.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem3));

		TextDocumentIdentifier textDocument3 = new TextDocumentIdentifier(uri3);
		Position position = new Position(2, 9);
		Either<List<CompletionItem>, CompletionList> result = services
				.completion(new CompletionParams(textDocument3, position)).get();
		Assertions.assertTrue(result.isLeft());
		List<CompletionItem> items = result.getLeft();
		List<CompletionItem> filteredA = items.stream().filter(item -> {
			return item.getLabel().equals("methodFromA") && item.getKind().equals(CompletionItemKind.Method);
		}).collect(Collectors.toList());
		Assertions.assertEquals(1, filteredA.size(),
				"Method from TraitA should be available in completion");
		List<CompletionItem> filteredB = items.stream().filter(item -> {
			return item.getLabel().equals("methodFromB") && item.getKind().equals(CompletionItemKind.Method);
		}).collect(Collectors.toList());
		Assertions.assertEquals(1, filteredB.size(),
				"Method from TraitB should be available in completion");
	}

	// --- trait method definition from implementing class

	@Test
	void testTraitMethodDefinitionWithinTrait() throws Exception {
		Path filePath = srcRoot.resolve("DefTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait DefTrait {\n");
		contents.append("  String traitHello() { 'hello' }\n");
		contents.append("  void other() {\n");
		contents.append("    this.traitHello()\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(3, 12);
		List<? extends Location> locations = services.definition(new DefinitionParams(textDocument, position)).get()
				.getLeft();
		Assertions.assertTrue(locations.size() > 0,
				"Should find definition for trait method called within the trait itself");
	}

	@Test
	void testTraitMethodDefinitionFromImplementingClass() throws Exception {
		Path filePath = srcRoot.resolve("NavTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait NavTrait {\n");
		contents.append("  String traitNav() { 'nav' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("NavClass.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class NavClass implements NavTrait {\n");
		contents2.append("  void doWork() {\n");
		contents2.append("    this.traitNav()\n");
		contents2.append("  }\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		TextDocumentIdentifier textDocument2 = new TextDocumentIdentifier(uri2);
		// position on 'traitNav' in 'this.traitNav()'
		Position position = new Position(2, 12);
		List<? extends Location> locations = services.definition(new DefinitionParams(textDocument2, position)).get()
				.getLeft();
		Assertions.assertTrue(locations.size() > 0,
				"Should navigate to trait method definition from implementing class");
		Location location = locations.get(0);
		Assertions.assertEquals(uri, location.getUri(),
				"Definition should point to the trait source file");
	}

	@Test
	void testTraitMethodDefinitionFromExternalInstance() throws Exception {
		Path filePath = srcRoot.resolve("ExtNavTrait.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("trait ExtNavTrait {\n");
		contents.append("  String extMethod() { 'ext' }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		Path filePath2 = srcRoot.resolve("ExtNavImpl.groovy");
		String uri2 = filePath2.toUri().toString();
		StringBuilder contents2 = new StringBuilder();
		contents2.append("class ExtNavImpl implements ExtNavTrait {\n");
		contents2.append("}");
		TextDocumentItem textDocumentItem2 = new TextDocumentItem(uri2, LANGUAGE_GROOVY, 1, contents2.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem2));

		Path filePath3 = srcRoot.resolve("ExtNavCaller.groovy");
		String uri3 = filePath3.toUri().toString();
		StringBuilder contents3 = new StringBuilder();
		contents3.append("class ExtNavCaller {\n");
		contents3.append("  void callIt() {\n");
		contents3.append("    ExtNavImpl obj = new ExtNavImpl()\n");
		contents3.append("    obj.extMethod()\n");
		contents3.append("  }\n");
		contents3.append("}");
		TextDocumentItem textDocumentItem3 = new TextDocumentItem(uri3, LANGUAGE_GROOVY, 1, contents3.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem3));

		TextDocumentIdentifier textDocument3 = new TextDocumentIdentifier(uri3);
		// position on 'extMethod' in 'obj.extMethod()'
		Position position = new Position(3, 10);
		List<? extends Location> locations = services.definition(new DefinitionParams(textDocument3, position)).get()
				.getLeft();
		Assertions.assertTrue(locations.size() > 0,
				"Should navigate to trait method definition from external caller");
		Location location = locations.get(0);
		Assertions.assertEquals(uri, location.getUri(),
				"Definition should point to the trait source file");
	}
}
