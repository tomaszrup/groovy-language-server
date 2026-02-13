////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.util;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileContentsTrackerTests {
	private FileContentsTracker tracker;

	@BeforeEach
	void setup() {
		tracker = new FileContentsTracker();
	}

	@AfterEach
	void tearDown() {
		tracker = null;
	}

	@Test
	void testDidOpen() {
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
		params.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello world"));
		tracker.didOpen(params);
		Assertions.assertEquals("hello world", tracker.getContents(URI.create("file.txt")));
	}

	@Test
	void testDidChangeWithoutRange() {
		DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
		openParams.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello world"));
		tracker.didOpen(openParams);
		DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams();
		changeParams.setTextDocument(new VersionedTextDocumentIdentifier("file.txt", 2));
		TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent();
		changeEvent.setText("hi there");
		changeParams.setContentChanges(Collections.singletonList(changeEvent));
		tracker.didChange(changeParams);
		Assertions.assertEquals("hi there", tracker.getContents(URI.create("file.txt")));
	}

	@Test
	void testDidChangeWithRange() {
		DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
		openParams.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello world"));
		tracker.didOpen(openParams);
		DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams();
		changeParams.setTextDocument(new VersionedTextDocumentIdentifier("file.txt", 2));
		TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent();
		changeEvent.setText(", friend");
		changeEvent.setRange(new Range(new Position(0, 5), new Position(0, 11)));
		changeParams.setContentChanges(Collections.singletonList(changeEvent));
		tracker.didChange(changeParams);
		Assertions.assertEquals("hello, friend", tracker.getContents(URI.create("file.txt")));
	}

	@Test
	void testDidChangeWithRangeMultiline() {
		DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
		openParams.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello\nworld"));
		tracker.didOpen(openParams);
		DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams();
		changeParams.setTextDocument(new VersionedTextDocumentIdentifier("file.txt", 2));
		TextDocumentContentChangeEvent changeEvent = new TextDocumentContentChangeEvent();
		changeEvent.setText("affles");
		changeEvent.setRange(new Range(new Position(1, 1), new Position(1, 5)));
		changeParams.setContentChanges(Collections.singletonList(changeEvent));
		tracker.didChange(changeParams);
		Assertions.assertEquals("hello\nwaffles", tracker.getContents(URI.create("file.txt")));
	}

	// ------------------------------------------------------------------
	// didClose
	// ------------------------------------------------------------------

	@Test
	void testDidClose() {
		DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
		openParams.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(openParams);
		Assertions.assertTrue(tracker.isOpen(URI.create("file.txt")));

		DidCloseTextDocumentParams closeParams = new DidCloseTextDocumentParams();
		closeParams.setTextDocument(new TextDocumentIdentifier("file.txt"));
		tracker.didClose(closeParams);

		Assertions.assertFalse(tracker.isOpen(URI.create("file.txt")));
	}

	@Test
	void testDidCloseRemovesContents() {
		DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
		openParams.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(openParams);

		DidCloseTextDocumentParams closeParams = new DidCloseTextDocumentParams();
		closeParams.setTextDocument(new TextDocumentIdentifier("file.txt"));
		tracker.didClose(closeParams);

		// getContents will try to read from file system (which won't exist), returning null
		// The in-memory copy should be removed
		Assertions.assertFalse(tracker.isOpen(URI.create("file.txt")));
	}

	// ------------------------------------------------------------------
	// getOpenURIs
	// ------------------------------------------------------------------

	@Test
	void testGetOpenURIsEmpty() {
		Set<URI> uris = tracker.getOpenURIs();
		Assertions.assertNotNull(uris);
		Assertions.assertTrue(uris.isEmpty());
	}

	@Test
	void testGetOpenURIsAfterOpen() {
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
		params.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(params);

		Set<URI> uris = tracker.getOpenURIs();
		Assertions.assertEquals(1, uris.size());
		Assertions.assertTrue(uris.contains(URI.create("file.txt")));
	}

	@Test
	void testGetOpenURIsAfterOpenAndClose() {
		DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
		openParams.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(openParams);

		DidCloseTextDocumentParams closeParams = new DidCloseTextDocumentParams();
		closeParams.setTextDocument(new TextDocumentIdentifier("file.txt"));
		tracker.didClose(closeParams);

		Set<URI> uris = tracker.getOpenURIs();
		Assertions.assertTrue(uris.isEmpty());
	}

	// ------------------------------------------------------------------
	// getChangedURIs / resetChangedFiles
	// ------------------------------------------------------------------

	@Test
	void testGetChangedURIsAfterOpen() {
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
		params.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(params);

		Set<URI> changed = tracker.getChangedURIs();
		Assertions.assertTrue(changed.contains(URI.create("file.txt")));
	}

	@Test
	void testResetChangedFiles() {
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
		params.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(params);

		Assertions.assertFalse(tracker.getChangedURIs().isEmpty());
		tracker.resetChangedFiles();
		Assertions.assertTrue(tracker.getChangedURIs().isEmpty());
	}

	@Test
	void testResetChangedFilesSelective() {
		DidOpenTextDocumentParams params1 = new DidOpenTextDocumentParams();
		params1.setTextDocument(new TextDocumentItem("file1.txt", "plaintext", 1, "a"));
		tracker.didOpen(params1);

		DidOpenTextDocumentParams params2 = new DidOpenTextDocumentParams();
		params2.setTextDocument(new TextDocumentItem("file2.txt", "plaintext", 1, "b"));
		tracker.didOpen(params2);

		// Reset only file1
		tracker.resetChangedFiles(Collections.singleton(URI.create("file1.txt")));
		Set<URI> changed = tracker.getChangedURIs();
		Assertions.assertFalse(changed.contains(URI.create("file1.txt")));
		Assertions.assertTrue(changed.contains(URI.create("file2.txt")));
	}

	// ------------------------------------------------------------------
	// forceChanged
	// ------------------------------------------------------------------

	@Test
	void testForceChanged() {
		URI uri = URI.create("file.txt");
		tracker.resetChangedFiles();
		Assertions.assertFalse(tracker.getChangedURIs().contains(uri));

		tracker.forceChanged(uri);
		Assertions.assertTrue(tracker.getChangedURIs().contains(uri));
	}

	// ------------------------------------------------------------------
	// isOpen
	// ------------------------------------------------------------------

	@Test
	void testIsOpenFalseForUnknownFile() {
		Assertions.assertFalse(tracker.isOpen(URI.create("unknown.txt")));
	}

	@Test
	void testIsOpenTrueAfterDidOpen() {
		DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
		params.setTextDocument(new TextDocumentItem("file.txt", "plaintext", 1, "hello"));
		tracker.didOpen(params);
		Assertions.assertTrue(tracker.isOpen(URI.create("file.txt")));
	}

	// ------------------------------------------------------------------
	// setContents / getContents
	// ------------------------------------------------------------------

	@Test
	void testSetAndGetContents() {
		URI uri = URI.create("file.txt");
		tracker.setContents(uri, "custom content");
		Assertions.assertEquals("custom content", tracker.getContents(uri));
	}

	@Test
	void testGetContentsForNonExistentFile() {
		URI uri = URI.create("file:///nonexistent_file_12345.txt");
		// Should return null for a file that doesn't exist on disk and isn't open
		String contents = tracker.getContents(uri);
		Assertions.assertNull(contents);
	}

	// ------------------------------------------------------------------
	// hasChangedURIsUnder
	// ------------------------------------------------------------------

	private static final Path TEST_ROOT;
	static {
		// Use a temp-dir-based root so paths are absolute and platform-safe
		TEST_ROOT = Paths.get(System.getProperty("java.io.tmpdir")).resolve("groovyls-test");
	}

	@Test
	void testHasChangedURIsUnderReturnsFalseWhenEmpty() {
		Path root = TEST_ROOT.resolve("project");
		Assertions.assertFalse(tracker.hasChangedURIsUnder(root));
	}

	@Test
	void testHasChangedURIsUnderReturnsTrueForMatchingRoot() {
		Path root = TEST_ROOT.resolve("project");
		URI uri = root.resolve("src/Main.groovy").toUri();
		tracker.forceChanged(uri);
		Assertions.assertTrue(tracker.hasChangedURIsUnder(root));
	}

	@Test
	void testHasChangedURIsUnderReturnsFalseForDifferentRoot() {
		Path rootA = TEST_ROOT.resolve("project-a");
		URI uri = TEST_ROOT.resolve("project-b/src/Main.groovy").toUri();
		tracker.forceChanged(uri);
		Assertions.assertFalse(tracker.hasChangedURIsUnder(rootA));
	}

	@Test
	void testHasChangedURIsUnderWithNullRootChecksAny() {
		URI uri = TEST_ROOT.resolve("anywhere/file.groovy").toUri();
		tracker.forceChanged(uri);
		Assertions.assertTrue(tracker.hasChangedURIsUnder(null));
	}

	@Test
	void testHasChangedURIsUnderWithNullRootReturnsFalseWhenEmpty() {
		Assertions.assertFalse(tracker.hasChangedURIsUnder(null));
	}

	@Test
	void testHasChangedURIsUnderMultipleProjectsOnlyMatchesCorrectOne() {
		Path rootA = TEST_ROOT.resolve("project-a");
		Path rootB = TEST_ROOT.resolve("project-b");
		URI uriB = rootB.resolve("src/Main.groovy").toUri();
		tracker.forceChanged(uriB);
		Assertions.assertFalse(tracker.hasChangedURIsUnder(rootA));
		Assertions.assertTrue(tracker.hasChangedURIsUnder(rootB));
	}

	@Test
	void testHasChangedURIsUnderAfterResetReturnsFalse() {
		Path root = TEST_ROOT.resolve("project");
		URI uri = root.resolve("src/Main.groovy").toUri();
		tracker.forceChanged(uri);
		Assertions.assertTrue(tracker.hasChangedURIsUnder(root));
		tracker.resetChangedFiles();
		Assertions.assertFalse(tracker.hasChangedURIsUnder(root));
	}
}