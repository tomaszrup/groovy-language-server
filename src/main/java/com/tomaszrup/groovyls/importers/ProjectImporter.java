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
     * Resolve classpaths for the given projects <b>without compiling</b>
     * source code first.  This is much faster than {@link #importProjects}
     * because it skips the build tasks ({@code classes}/{@code testClasses})
     * and only resolves dependency JARs from the build tool's configuration
     * metadata.  Existing compiled class-output directories (e.g.
     * {@code build/classes/}) are still discovered from prior builds.
     *
     * <p>The default implementation delegates to {@link #importProjects},
     * which includes compilation.  Subclasses should override to provide a
     * faster, resolution-only path.</p>
     *
     * @param projectRoots all project roots discovered for this importer
     * @return map from project root to its classpath entries
     */
    default Map<Path, List<String>> resolveClasspaths(List<Path> projectRoots) {
        return importProjects(projectRoots);
    }

    /**
     * Resolve the classpath for a <b>single</b> project <b>without compiling</b>.
     * This is the lazy on-demand variant used when the user opens a file in a
     * project whose classpath hasn't been resolved yet.
     *
     * <p>The default implementation delegates to
     * {@link #resolveClasspaths(List)} with a single-element list.
     * Subclasses may override to provide a more targeted (and faster) approach,
     * e.g. Gradle can run a targeted init-script task for just one subproject.</p>
     *
     * @param projectRoot the project root to resolve
     * @return the list of classpath entries, or an empty list on failure
     */
    default List<String> resolveClasspath(Path projectRoot) {
        Map<Path, List<String>> result = resolveClasspaths(java.util.Collections.singletonList(projectRoot));
        return result.getOrDefault(projectRoot, java.util.Collections.emptyList());
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
