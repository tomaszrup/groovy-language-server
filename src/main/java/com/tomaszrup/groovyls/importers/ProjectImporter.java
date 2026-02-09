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
package com.tomaszrup.groovyls.importers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for build-tool-specific project discovery, classpath resolution,
 * and recompilation. Implementations exist for Gradle and Maven.
 */
public interface ProjectImporter {

    /**
     * Returns a human-readable name for this build tool (e.g. "Gradle", "Maven").
     */
    String getName();

    /**
     * Walk the given workspace root and return all project roots managed by this
     * build tool that contain JVM (Java/Groovy) sources.
     */
    List<Path> discoverProjects(Path workspaceRoot) throws IOException;

    /**
     * Import a single project: compile Java sources and resolve the full classpath
     * (dependency JARs + compiled class output directories).
     *
     * @return list of classpath entries (absolute paths)
     */
    List<String> importProject(Path projectRoot);

    /**
     * Batch-import multiple projects at once. Implementations can override this
     * to use a single build-tool connection for all projects that share a common
     * root (e.g. Gradle multi-project builds), which is dramatically faster than
     * importing each project individually.
     *
     * <p>The default implementation falls back to calling
     * {@link #importProject(Path)} for each project sequentially.</p>
     *
     * @param projectRoots all project roots discovered for this importer
     * @return map from project root to its classpath entries
     */
    default Map<Path, List<String>> importProjects(List<Path> projectRoots) {
        Map<Path, List<String>> result = new LinkedHashMap<>();
        for (Path root : projectRoots) {
            result.put(root, importProject(root));
        }
        return result;
    }

    /**
     * Recompile Java sources after file changes so that the Groovy compilation
     * unit picks up updated classes.
     */
    void recompile(Path projectRoot);

    /**
     * Returns {@code true} if the given file path is a build-tool configuration
     * file that should trigger a recompilation when changed (e.g. build.gradle,
     * pom.xml).
     */
    boolean isProjectFile(String filePath);
}
