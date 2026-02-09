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

import java.nio.file.Path;
import java.util.List;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;

public interface ICompilationUnitFactory {
	/**
	 * If this factory would normally reuse an existing compilation unit, forces
	 * the creation of a new one.
	 */
	public void invalidateCompilationUnit();

	/**
	 * Invalidate the cached file tree so that the next compilation will
	 * re-walk the workspace directory. Call this when filesystem changes are
	 * detected (e.g. files created, deleted, or renamed).
	 */
	default void invalidateFileCache() {
		// no-op by default for test implementations
	}

	public List<String> getAdditionalClasspathList();

	public void setAdditionalClasspathList(List<String> classpathList);

	/**
	 * Returns a compilation unit.
	 */
	public GroovyLSCompilationUnit create(Path workspaceRoot, FileContentsTracker fileContentsTracker);
}