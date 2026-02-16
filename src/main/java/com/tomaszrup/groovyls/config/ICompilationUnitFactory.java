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
package com.tomaszrup.groovyls.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;

public interface ICompilationUnitFactory {
	/**
	 * If this factory would normally reuse an existing compilation unit, forces
	 * the creation of a new one.
	 */
	public void invalidateCompilationUnit();

	/**
	 * Fully invalidates the compilation unit <em>and</em> the class loader,
	 * forcing a fresh class loader on the next creation. Use this when the
	 * contents of classpath directories have changed (e.g. stale {@code .class}
	 * files deleted).
	 */
	default void invalidateCompilationUnitFull() {
		invalidateCompilationUnit();
	}

	/**
	 * Invalidate the cached file tree so that the next compilation will
	 * re-walk the workspace directory. Call this when filesystem changes are
	 * detected (e.g. files created, deleted, or renamed).
	 */
	default void invalidateFileCache() {
		// no-op by default for test implementations
	}

	/**
	 * Invalidate the Java source index so that stub classes for newly
	 * created/deleted/moved {@code .java} files are refreshed.
	 */
	default void invalidateJavaSourceIndex() {
		// no-op by default for test implementations
	}

	public List<String> getAdditionalClasspathList();

	public void setAdditionalClasspathList(List<String> classpathList);

	/**
	 * Returns test-only classpath entries, or {@code null} if not separated.
	 */
	default List<String> getTestOnlyClasspathList() {
		return Collections.emptyList();
	}

	/**
	 * Set test-only classpath entries separately from the main classpath.
	 */
	default void setTestOnlyClasspathList(List<String> testOnlyClasspathList) {
		// no-op by default
	}

	/**
	 * Returns the combined classpath (main + test-only).
	 */
	default List<String> getCombinedClasspathList() {
		return getAdditionalClasspathList();
	}

	/**
	 * Returns a compilation unit.
	 */
	public GroovyLSCompilationUnit create(Path workspaceRoot, FileContentsTracker fileContentsTracker);

	/**
	 * Returns a compilation unit, additionally invalidating the given URIs
	 * even if they are not marked as changed in the {@code fileContentsTracker}.
	 * This is used for dependency-driven invalidation: when a file changes,
	 * its dependents need to be recompiled even though their contents haven't
	 * changed.
	 *
	 * @param workspaceRoot        the project root directory
	 * @param fileContentsTracker  tracks open file contents and changed URIs
	 * @param additionalInvalidations  URIs of dependent files to force-invalidate
	 * @return the (possibly reused) compilation unit
	 */
	default GroovyLSCompilationUnit create(Path workspaceRoot, FileContentsTracker fileContentsTracker,
			Set<URI> additionalInvalidations) {
		// Default implementation ignores additional invalidations for backward
		// compatibility (e.g. test implementations that don't need this).
		return create(workspaceRoot, fileContentsTracker);
	}

	/**
	 * Creates a temporary, lightweight compilation unit containing only the
	 * specified source files. Used for single-file incremental compilation.
	 * The returned unit is <em>not</em> stored and does not replace the main
	 * compilation unit.
	 *
	 * @param workspaceRoot       the project root directory
	 * @param fileContentsTracker tracks open file contents
	 * @param filesToInclude      the URIs of files to include as sources
	 * @return a compilation unit containing only the specified files, or
	 *         {@code null} if incremental compilation is not supported
	 */
	default GroovyLSCompilationUnit createIncremental(Path workspaceRoot,
			FileContentsTracker fileContentsTracker, Set<URI> filesToInclude) {
		// Default: not supported — caller should fall back to full compilation
		return null;
	}
}