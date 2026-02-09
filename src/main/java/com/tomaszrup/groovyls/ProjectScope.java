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
package com.tomaszrup.groovyls;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.lsp4j.Diagnostic;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.DependencyGraph;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.util.JavaSourceLocator;

/**
 * Holds all per-project state: compilation unit, AST visitor, classpath, etc.
 * Each build-tool project (Gradle/Maven) in the workspace gets its own scope
 * so that classpaths don't leak between independent projects.
 */
public class ProjectScope {
	Path projectRoot;
	final ICompilationUnitFactory compilationUnitFactory;
	GroovyLSCompilationUnit compilationUnit;

	/**
	 * The latest AST visitor snapshot.  Published via volatile write after
	 * every successful compilation so that lock-free readers (hover,
	 * definition, etc.) always see a consistent, immutable snapshot.
	 * Writers produce a <em>new</em> visitor via copy-on-write rather than
	 * mutating this reference in place.
	 */
	volatile ASTNodeVisitor astVisitor;

	Map<URI, List<Diagnostic>> prevDiagnosticsByFile;

	/** Published via volatile write when the classloader changes. */
	volatile ScanResult classGraphScanResult;

	GroovyClassLoader classLoader;
	volatile URI previousContext;
	volatile JavaSourceLocator javaSourceLocator;
	final DependencyGraph dependencyGraph = new DependencyGraph();

	/**
	 * Per-project lock. Write-lock is acquired for compilation and AST
	 * mutation. Read-only LSP handlers no longer acquire a read-lock —
	 * they read the volatile {@link #astVisitor} reference directly
	 * (stale-AST reads). The read-lock is still used in a few places
	 * that need to coordinate with the write-lock (e.g. workspace
	 * symbols aggregation).
	 */
	final ReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * Whether this scope has been compiled at least once. Used for
	 * deferred/lazy compilation: scopes registered via {@code addProjects}
	 * are not compiled eagerly — compilation is deferred until the first
	 * request that actually needs the AST.
	 */
	volatile boolean compiled = false;

	/**
	 * Whether this scope's classpath has been resolved (dependency JARs
	 * are known). Scopes registered via {@code registerDiscoveredProjects}
	 * start with empty classpaths ({@code classpathResolved = false}) and
	 * are upgraded later via {@code updateProjectClasspaths}.
	 */
	volatile boolean classpathResolved = false;

	public ProjectScope(Path projectRoot, ICompilationUnitFactory factory) {
		this.projectRoot = projectRoot;
		this.compilationUnitFactory = factory;
		this.javaSourceLocator = new JavaSourceLocator();
		if (projectRoot != null) {
			this.javaSourceLocator.addProjectRoot(projectRoot);
		}
	}
}
