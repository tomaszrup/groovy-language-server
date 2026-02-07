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

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ImplementationParams;
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

class GroovyServicesImplementationTests {
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
		services.connect(new LanguageClient() {
			@Override
			public void telemetryEvent(Object object) {
			}

			@Override
			public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
				return null;
			}

			@Override
			public void showMessage(MessageParams messageParams) {
			}

			@Override
			public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
			}

			@Override
			public void logMessage(MessageParams message) {
			}
		});
	}

	@AfterEach
	void tearDown() {
		services = null;
		workspaceRoot = null;
		srcRoot = null;
	}

	// --- Interface implementations ---

	@Test
	void testImplementationOfInterface() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface Greeter {\n");
		contents.append("  String greet(String name)\n");
		contents.append("}\n");
		contents.append("class FriendlyGreeter implements Greeter {\n");
		contents.append("  String greet(String name) {\n");
		contents.append("    return \"Hello, \" + name\n");
		contents.append("  }\n");
		contents.append("}\n");
		contents.append("class FormalGreeter implements Greeter {\n");
		contents.append("  String greet(String name) {\n");
		contents.append("    return \"Good day, \" + name\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 12); // on "Greeter" interface declaration
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.size() >= 2,
				"Should find at least 2 implementations of Greeter, found: " + locations.size());
	}

	@Test
	void testImplementationOfInterfaceMethod() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("interface Calculator {\n");
		contents.append("  int compute(int a, int b)\n");
		contents.append("}\n");
		contents.append("class Adder implements Calculator {\n");
		contents.append("  int compute(int a, int b) {\n");
		contents.append("    return a + b\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 8); // on "compute" in interface
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.size() >= 1,
				"Should find at least 1 method implementation, found: " + locations.size());
	}

	// --- Abstract class implementations ---

	@Test
	void testImplementationOfAbstractClass() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("abstract class Shape {\n");
		contents.append("  abstract double area()\n");
		contents.append("}\n");
		contents.append("class Circle extends Shape {\n");
		contents.append("  double radius = 1.0\n");
		contents.append("  double area() {\n");
		contents.append("    return 3.14 * radius * radius\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 18); // on "Shape" abstract class declaration
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.size() >= 1,
				"Should find at least 1 implementation of Shape, found: " + locations.size());
	}

	// --- Class hierarchy ---

	@Test
	void testImplementationWithInheritanceChain() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Animal {\n");
		contents.append("  String speak() { return \"\" }\n");
		contents.append("}\n");
		contents.append("class Dog extends Animal {\n");
		contents.append("  String speak() { return \"Woof\" }\n");
		contents.append("}\n");
		contents.append("class Cat extends Animal {\n");
		contents.append("  String speak() { return \"Meow\" }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 8); // on "Animal" class
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.size() >= 2,
				"Should find at least 2 subclasses of Animal, found: " + locations.size());
	}

	// --- Edge cases ---

	@Test
	void testImplementationOnConcreteClassWithNoSubclasses() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Standalone {\n");
		contents.append("  void method() {}\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(0, 8); // on "Standalone" class
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.isEmpty(),
				"Concrete class with no subclasses should have no implementations");
	}

	@Test
	void testImplementationOnEmptySpaceReturnsEmpty() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ImplTest {\n");
		contents.append("\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 0); // on empty line
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.isEmpty(),
				"Implementation on empty space should return empty list");
	}

	@Test
	void testImplementationOfMethodInBaseClass() throws Exception {
		Path filePath = srcRoot.resolve("ImplTest.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class Base {\n");
		contents.append("  void process() {}\n");
		contents.append("}\n");
		contents.append("class Derived extends Base {\n");
		contents.append("  void process() {\n");
		contents.append("    println \"derived\"\n");
		contents.append("  }\n");
		contents.append("}");
		TextDocumentItem textDocumentItem = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(textDocumentItem));

		TextDocumentIdentifier textDocument = new TextDocumentIdentifier(uri);
		Position position = new Position(1, 10); // on "process" in Base
		Either<List<? extends Location>, List<? extends LocationLink>> result = services
				.implementation(new ImplementationParams(textDocument, position)).get();

		Assertions.assertTrue(result.isLeft(), "Result should be Location list");
		List<? extends Location> locations = result.getLeft();
		Assertions.assertTrue(locations.size() >= 1,
				"Should find at least 1 override of process, found: " + locations.size());
	}
}
