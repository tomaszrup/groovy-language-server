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
 *
 * <p>All mutable state is encapsulated behind accessors to prevent
 * accidental mutation from external classes.</p>
 */
public class ProjectScope {
	private final Path projectRoot;
	private final ICompilationUnitFactory compilationUnitFactory;
	private GroovyLSCompilationUnit compilationUnit;

	/**
	 * The latest AST visitor snapshot.  Published via volatile write after
	 * every successful compilation so that lock-free readers (hover,
	 * definition, etc.) always see a consistent, immutable snapshot.
	 * Writers produce a <em>new</em> visitor via copy-on-write rather than
	 * mutating this reference in place.
	 */
	private volatile ASTNodeVisitor astVisitor;

	private Map<URI, List<Diagnostic>> prevDiagnosticsByFile;

	/** Published via volatile write when the classloader changes. */
	private volatile ScanResult classGraphScanResult;

	private GroovyClassLoader classLoader;
	private volatile URI previousContext;
	private volatile JavaSourceLocator javaSourceLocator;
	private final DependencyGraph dependencyGraph = new DependencyGraph();

	/**
	 * Per-project lock. Write-lock is acquired for compilation and AST
	 * mutation. Read-only LSP handlers no longer acquire a read-lock —
	 * they read the volatile {@link #astVisitor} reference directly
	 * (stale-AST reads). The read-lock is still used in a few places
	 * that need to coordinate with the write-lock (e.g. workspace
	 * symbols aggregation).
	 */
	private final ReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * Whether this scope has been compiled at least once. Used for
	 * deferred/lazy compilation: scopes registered via {@code addProjects}
	 * are not compiled eagerly — compilation is deferred until the first
	 * request that actually needs the AST.
	 */
	private volatile boolean compiled = false;

	/**
	 * Whether this scope's classpath has been resolved (dependency JARs
	 * are known). Scopes registered via {@code registerDiscoveredProjects}
	 * start with empty classpaths ({@code classpathResolved = false}) and
	 * are upgraded later via {@code updateProjectClasspaths}.
	 */
	private volatile boolean classpathResolved = false;

	public ProjectScope(Path projectRoot, ICompilationUnitFactory factory) {
		this.projectRoot = projectRoot;
		this.compilationUnitFactory = factory;
		this.javaSourceLocator = new JavaSourceLocator();
		if (projectRoot != null) {
			this.javaSourceLocator.addProjectRoot(projectRoot);
		}
	}

	// --- Accessors ---

	public Path getProjectRoot() {
		return projectRoot;
	}

	public ICompilationUnitFactory getCompilationUnitFactory() {
		return compilationUnitFactory;
	}

	public GroovyLSCompilationUnit getCompilationUnit() {
		return compilationUnit;
	}

	public void setCompilationUnit(GroovyLSCompilationUnit compilationUnit) {
		this.compilationUnit = compilationUnit;
	}

	public ASTNodeVisitor getAstVisitor() {
		return astVisitor;
	}

	public void setAstVisitor(ASTNodeVisitor astVisitor) {
		this.astVisitor = astVisitor;
	}

	public Map<URI, List<Diagnostic>> getPrevDiagnosticsByFile() {
		return prevDiagnosticsByFile;
	}

	public void setPrevDiagnosticsByFile(Map<URI, List<Diagnostic>> prevDiagnosticsByFile) {
		this.prevDiagnosticsByFile = prevDiagnosticsByFile;
	}

	public ScanResult getClassGraphScanResult() {
		return classGraphScanResult;
	}

	public void setClassGraphScanResult(ScanResult classGraphScanResult) {
		this.classGraphScanResult = classGraphScanResult;
	}

	public GroovyClassLoader getClassLoader() {
		return classLoader;
	}

	public void setClassLoader(GroovyClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	public URI getPreviousContext() {
		return previousContext;
	}

	public void setPreviousContext(URI previousContext) {
		this.previousContext = previousContext;
	}

	public JavaSourceLocator getJavaSourceLocator() {
		return javaSourceLocator;
	}

	public DependencyGraph getDependencyGraph() {
		return dependencyGraph;
	}

	public ReadWriteLock getLock() {
		return lock;
	}

	public boolean isCompiled() {
		return compiled;
	}

	public void setCompiled(boolean compiled) {
		this.compiled = compiled;
	}

	public boolean isClasspathResolved() {
		return classpathResolved;
	}

	public void setClasspathResolved(boolean classpathResolved) {
		this.classpathResolved = classpathResolved;
	}

	/**
	 * Lazily initialise the ClassGraph scan result for this scope.
	 * The scan is expensive (2–10 s for large classpaths) but is only
	 * needed by providers that enumerate classpath types (completion,
	 * code actions).  Compilation and diagnostic generation do NOT
	 * need it, so deferring the scan significantly reduces time to
	 * first diagnostic.
	 *
	 * <p>Thread-safe: uses double-checked locking on the scope's write
	 * lock.  Callers that already hold the write lock should use
	 * {@link #ensureClassGraphScannedUnsafe()} instead.</p>
	 *
	 * @return the scan result, or {@code null} if the classloader is
	 *         not yet available
	 */
	public ScanResult ensureClassGraphScanned() {
		if (classGraphScanResult != null) {
			return classGraphScanResult;
		}
		lock.writeLock().lock();
		try {
			return ensureClassGraphScannedUnsafe();
		} finally {
			lock.writeLock().unlock();
		}
	}

	/**
	 * Same as {@link #ensureClassGraphScanned()} but assumes the caller
	 * already holds the write lock.
	 */
	public ScanResult ensureClassGraphScannedUnsafe() {
		if (classGraphScanResult != null) {
			return classGraphScanResult;
		}
		GroovyClassLoader cl = classLoader;
		if (cl == null) {
			return null;
		}
		com.tomaszrup.groovyls.compiler.SharedClassGraphCache sharedCache =
				com.tomaszrup.groovyls.compiler.SharedClassGraphCache.getInstance();
		classGraphScanResult = sharedCache.acquire(cl);
		return classGraphScanResult;
	}

	/**
	 * Update the Java source locator with classpath entries so that
	 * "Go to Definition" can navigate into dependency source JARs.
	 * This should be called whenever the classpath is resolved or updated.
	 */
	void updateSourceLocatorClasspath(List<String> classpathEntries) {
		if (classpathEntries != null && !classpathEntries.isEmpty() && javaSourceLocator != null) {
			javaSourceLocator.addClasspathJars(classpathEntries);
		}
	}
}
