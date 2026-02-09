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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

import com.tomaszrup.lsp.utils.Positions;

/**
 * Thread-safe tracker for open document contents and change notifications.
 *
 * <p>Uses {@link ConcurrentHashMap} internally so that multiple per-project
 * locks can safely read/write concurrently without a single global lock.</p>
 */
public class FileContentsTracker {

	private final ConcurrentHashMap<URI, String> openFiles = new ConcurrentHashMap<>();
	private final Set<URI> changedFiles = ConcurrentHashMap.newKeySet();

	public Set<URI> getOpenURIs() {
		return Collections.unmodifiableSet(openFiles.keySet());
	}

	public Set<URI> getChangedURIs() {
		return changedFiles;
	}

	/**
	 * Clear all tracked changes.
	 */
	public void resetChangedFiles() {
		changedFiles.clear();
	}

	/**
	 * Clear only the specified URIs from the changed set. This allows
	 * per-project resets without discarding changes for other projects.
	 */
	public void resetChangedFiles(Set<URI> toReset) {
		changedFiles.removeAll(toReset);
	}

	public void forceChanged(URI uri) {
		changedFiles.add(uri);
	}

	public boolean isOpen(URI uri) {
		return openFiles.containsKey(uri);
	}

	public void didOpen(DidOpenTextDocumentParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		openFiles.put(uri, params.getTextDocument().getText());
		changedFiles.add(uri);
	}

	/**
	 * Applies incremental or full-content changes atomically using
	 * {@link ConcurrentHashMap#compute} to avoid races between concurrent
	 * reads and writes to the same document.
	 */
	public void didChange(DidChangeTextDocumentParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		openFiles.compute(uri, (key, currentText) -> {
			if (currentText == null) {
				// Should not happen (didOpen not called), but handle gracefully
				for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
					currentText = change.getText();
				}
				return currentText;
			}
			// Apply all content changes in order (incremental sync may send multiple)
			for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
				Range range = change.getRange();
				if (range == null) {
					// Full content replacement
					currentText = change.getText();
				} else {
					int offsetStart = Positions.getOffset(currentText, range.getStart());
					int offsetEnd = Positions.getOffset(currentText, range.getEnd());
					StringBuilder builder = new StringBuilder();
					builder.append(currentText.substring(0, offsetStart));
					builder.append(change.getText());
					builder.append(currentText.substring(offsetEnd));
					currentText = builder.toString();
				}
			}
			return currentText;
		});
		changedFiles.add(uri);
	}

	public void didClose(DidCloseTextDocumentParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		openFiles.remove(uri);
		changedFiles.add(uri);
	}

	public String getContents(URI uri) {
		String contents = openFiles.get(uri);
		if (contents != null) {
			return contents;
		}
		try {
			return Files.readString(Paths.get(uri));
		} catch (IOException e) {
			return null;
		}
	}

	public void setContents(URI uri, String contents) {
		openFiles.put(uri, contents);
	}
}