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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Tests for concurrent access to GroovyServices — verifies that the
 * ReadWriteLock in GroovyServices prevents data corruption when multiple
 * threads call read and write operations simultaneously.
 */
class GroovyServicesConcurrentAccessTests {
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
		if (services != null) {
			services.setWorkspaceRoot(null);
		}
		services = null;
		workspaceRoot = null;
		srcRoot = null;
	}

	// ------------------------------------------------------------------
	// Concurrent reads should not throw
	// ------------------------------------------------------------------

	@Test
	void testConcurrentHoverRequests() throws Exception {
		Path filePath = srcRoot.resolve("ConcurrentReadTarget.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ConcurrentReadTarget {\n");
		contents.append("  String name = 'test'\n");
		contents.append("  int getValue() { return 42 }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		int threadCount = 8;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicInteger errors = new AtomicInteger(0);
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			final int line = (i % 3) + 1; // lines 1-3
			futures.add(executor.submit(() -> {
				try {
					startLatch.await();
					HoverParams params = new HoverParams(
							new TextDocumentIdentifier(uri),
							new Position(line, 5));
					Hover result = services.hover(params).get(10, TimeUnit.SECONDS);
					// result may be null, that's OK; we just want no exception
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			}));
		}

		// Release all threads simultaneously
		startLatch.countDown();

		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		executor.shutdown();

		Assertions.assertEquals(0, errors.get(),
				"Concurrent hover requests should not throw exceptions");
	}

	// ------------------------------------------------------------------
	// Concurrent reads with a concurrent write (didChange)
	// ------------------------------------------------------------------

	@Test
	void testConcurrentReadWriteDoesNotCorrupt() throws Exception {
		Path filePath = srcRoot.resolve("ConcurrentRWTarget.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ConcurrentRWTarget {\n");
		contents.append("  String name = 'initial'\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		int iterations = 10;
		ExecutorService executor = Executors.newFixedThreadPool(4);
		AtomicInteger errors = new AtomicInteger(0);
		List<Future<?>> futures = new ArrayList<>();

		// Reader threads — issue hover requests
		for (int r = 0; r < 2; r++) {
			futures.add(executor.submit(() -> {
				for (int i = 0; i < iterations; i++) {
					try {
						HoverParams params = new HoverParams(
								new TextDocumentIdentifier(uri),
								new Position(1, 5));
						services.hover(params).get(10, TimeUnit.SECONDS);
					} catch (Exception e) {
						errors.incrementAndGet();
					}
				}
			}));
		}

		// Writer thread — issue didChange with incrementing versions
		futures.add(executor.submit(() -> {
			for (int i = 0; i < iterations; i++) {
				try {
					String newContent = "class ConcurrentRWTarget {\n  String name = 'v" + i + "'\n}\n";
					VersionedTextDocumentIdentifier versionedId = new VersionedTextDocumentIdentifier(
							uri, i + 2);
					TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(newContent);
					DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams(
							versionedId, java.util.Collections.singletonList(change));
					services.didChange(changeParams);
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			}
		}));

		// Completion reader thread
		futures.add(executor.submit(() -> {
			for (int i = 0; i < iterations; i++) {
				try {
					CompletionParams params = new CompletionParams(
							new TextDocumentIdentifier(uri),
							new Position(1, 10));
					services.completion(params).get(10, TimeUnit.SECONDS);
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			}
		}));

		for (Future<?> f : futures) {
			f.get(60, TimeUnit.SECONDS);
		}
		executor.shutdown();

		Assertions.assertEquals(0, errors.get(),
				"Concurrent reads and writes should not throw or corrupt state");
	}

	// ------------------------------------------------------------------
	// Multiple didOpen calls with different files concurrently
	// ------------------------------------------------------------------

	@Test
	void testConcurrentDidOpenMultipleFiles() throws Exception {
		int fileCount = 5;
		ExecutorService executor = Executors.newFixedThreadPool(fileCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicInteger errors = new AtomicInteger(0);
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < fileCount; i++) {
			final int idx = i;
			futures.add(executor.submit(() -> {
				try {
					startLatch.await();
					Path filePath = srcRoot.resolve("ConcOpen" + idx + ".groovy");
					String uri = filePath.toUri().toString();
					String content = "class ConcOpen" + idx + " {\n  int value = " + idx + "\n}\n";
					TextDocumentItem doc = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, content);
					services.didOpen(new DidOpenTextDocumentParams(doc));
				} catch (Exception e) {
					errors.incrementAndGet();
				}
			}));
		}

		startLatch.countDown();

		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		executor.shutdown();

		Assertions.assertEquals(0, errors.get(),
				"Concurrent didOpen calls should not throw");
	}

	// ------------------------------------------------------------------
	// Read after write consistency
	// ------------------------------------------------------------------

	@Test
	void testReadAfterWriteCompletionConsistency() throws Exception {
		Path filePath = srcRoot.resolve("ReadAfterWriteTarget.groovy");
		String uri = filePath.toUri().toString();
		StringBuilder contents = new StringBuilder();
		contents.append("class ReadAfterWriteTarget {\n");
		contents.append("  String firstName = 'Alice'\n");
		contents.append("  def test() {\n");
		contents.append("    this.\n"); // line 3 — completion trigger point
		contents.append("  }\n");
		contents.append("}\n");

		TextDocumentItem document = new TextDocumentItem(uri, LANGUAGE_GROOVY, 1, contents.toString());
		services.didOpen(new DidOpenTextDocumentParams(document));

		// Request completion — should see 'firstName'
		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(3, 9));
		Either<List<CompletionItem>, CompletionList> result = services.completion(params).get(10, TimeUnit.SECONDS);
		Assertions.assertNotNull(result, "Completion result should not be null");

		List<CompletionItem> items = result.isLeft() ? result.getLeft() : result.getRight().getItems();
		boolean hasFirstName = items.stream()
				.anyMatch(i -> "firstName".equals(i.getLabel()));
		Assertions.assertTrue(hasFirstName,
				"Should see 'firstName' in completions after didOpen");

		// Now change the document — add a new field
		String newContent = "class ReadAfterWriteTarget {\n"
				+ "  String firstName = 'Alice'\n"
				+ "  String lastName = 'Smith'\n"
				+ "  def test() {\n"
				+ "    this.\n"
				+ "  }\n"
				+ "}\n";
		VersionedTextDocumentIdentifier versionedId = new VersionedTextDocumentIdentifier(uri, 2);
		DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams(
				versionedId,
				java.util.Collections.singletonList(new TextDocumentContentChangeEvent(newContent)));
		services.didChange(changeParams);

		// Request completion again — should now also see 'lastName'
		CompletionParams params2 = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(4, 9));
		Either<List<CompletionItem>, CompletionList> result2 = services.completion(params2).get(10, TimeUnit.SECONDS);
		Assertions.assertNotNull(result2);

		List<CompletionItem> items2 = result2.isLeft() ? result2.getLeft() : result2.getRight().getItems();
		boolean hasLastName = items2.stream()
				.anyMatch(i -> "lastName".equals(i.getLabel()));
		Assertions.assertTrue(hasLastName,
				"After didChange, should see 'lastName' in completions");
	}
}
