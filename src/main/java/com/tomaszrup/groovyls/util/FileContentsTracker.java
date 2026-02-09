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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
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
 *
 * <p>For files that are <b>not open</b> in the editor, a TTL-based cache
 * avoids repeated blocking {@link Files#readString} calls on the LSP thread.
 * The default TTL is {@value #CLOSED_FILE_CACHE_TTL_MS} ms.</p>
 */
public class FileContentsTracker {

	/** TTL for closed-file cache entries, in milliseconds. */
	static final long CLOSED_FILE_CACHE_TTL_MS = 5_000;

	private final ConcurrentHashMap<URI, String> openFiles = new ConcurrentHashMap<>();
	private final Set<URI> changedFiles = ConcurrentHashMap.newKeySet();

	/**
	 * Cache for files that are not open in the editor. Entries expire after
	 * {@link #CLOSED_FILE_CACHE_TTL_MS} and are eagerly invalidated when the
	 * file is opened or when an external change event arrives.
	 */
	private final ConcurrentHashMap<URI, CachedContent> closedFileCache = new ConcurrentHashMap<>();

	/** Immutable holder for a cached disk-read result with a timestamp. */
	private static final class CachedContent {
		final String content;
		final long readTimeMillis;

		CachedContent(String content) {
			this.content = content;
			this.readTimeMillis = System.currentTimeMillis();
		}

		boolean isExpired() {
			return System.currentTimeMillis() - readTimeMillis > CLOSED_FILE_CACHE_TTL_MS;
		}
	}

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

	/**
	 * Returns {@code true} if there is at least one changed URI whose path
	 * falls under the given root directory.  This allows callers to check
	 * for scope-relevant changes without iterating the full set themselves.
	 *
	 * @param root the project root path to check against; if {@code null},
	 *             returns {@code !changedFiles.isEmpty()} (backward compat)
	 */
	public boolean hasChangedURIsUnder(Path root) {
		if (changedFiles.isEmpty()) {
			return false;
		}
		if (root == null) {
			return !changedFiles.isEmpty();
		}
		for (URI uri : changedFiles) {
			try {
				if (Paths.get(uri).startsWith(root)) {
					return true;
				}
			} catch (Exception e) {
				// ignore URIs that can't be converted to Path
			}
		}
		return false;
	}

	public boolean isOpen(URI uri) {
		return openFiles.containsKey(uri);
	}

	public void didOpen(DidOpenTextDocumentParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		openFiles.put(uri, params.getTextDocument().getText());
		closedFileCache.remove(uri);
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
					if (offsetStart < 0 || offsetEnd < 0 || offsetStart > currentText.length() || offsetEnd > currentText.length()) {
						// Invalid offsets — fall back to full content replacement
						currentText = change.getText();
					} else {
						StringBuilder builder = new StringBuilder();
						builder.append(currentText.substring(0, offsetStart));
						builder.append(change.getText());
						builder.append(currentText.substring(offsetEnd));
						currentText = builder.toString();
					}
				}
			}
			return currentText;
		});
		changedFiles.add(uri);
	}

	public void didClose(DidCloseTextDocumentParams params) {
		URI uri = URI.create(params.getTextDocument().getUri());
		String lastContent = openFiles.remove(uri);
		// Pre-populate the closed-file cache so that immediate subsequent
		// getContents() calls don't need to hit disk.
		if (lastContent != null) {
			closedFileCache.put(uri, new CachedContent(lastContent));
		}
		changedFiles.add(uri);
	}

	/**
	 * Returns the contents for the given URI.
	 *
	 * <ol>
	 *   <li>If the file is open in the editor, returns the in-memory text.</li>
	 *   <li>Otherwise checks the closed-file TTL cache.</li>
	 *   <li>On cache miss / expiry, reads from disk and caches the result.</li>
	 * </ol>
	 */
	public String getContents(URI uri) {
		// 1. Open files — always authoritative
		String contents = openFiles.get(uri);
		if (contents != null) {
			return contents;
		}

		// 2. Closed-file cache — avoid repeated blocking I/O
		CachedContent cached = closedFileCache.get(uri);
		if (cached != null && !cached.isExpired()) {
			return cached.content;
		}

		// 3. Disk read — cache the result for subsequent calls
		try {
			String diskContent = Files.readString(Paths.get(uri));
			closedFileCache.put(uri, new CachedContent(diskContent));
			return diskContent;
		} catch (IOException e) {
			// Remove stale cache entry on read failure
			closedFileCache.remove(uri);
			return null;
		}
	}

	public void setContents(URI uri, String contents) {
		openFiles.put(uri, contents);
	}

	// --- Closed-file cache invalidation ---

	/**
	 * Invalidate cached disk-read entries for the given URIs. Call this
	 * when external file-system changes are detected (e.g. from
	 * {@code didChangeWatchedFiles}) to ensure stale content is not served.
	 */
	public void invalidateClosedFileCache(Collection<URI> uris) {
		for (URI uri : uris) {
			closedFileCache.remove(uri);
		}
	}

	/**
	 * Invalidate a single URI from the closed-file cache.
	 */
	public void invalidateClosedFileCache(URI uri) {
		closedFileCache.remove(uri);
	}

	/**
	 * Invalidate all entries in the closed-file cache.
	 */
	public void invalidateAllClosedFileCache() {
		closedFileCache.clear();
	}
}