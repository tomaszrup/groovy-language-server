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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger logger = LoggerFactory.getLogger(ProjectScope.class);

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
	 *
	 * <p>Set to {@code true} after staged Phase A (single-file) or full
	 * compilation completes, so that subsequent LSP requests (e.g. semantic
	 * tokens, hover) don't trigger redundant compilation.</p>
	 */
	private volatile boolean compiled = false;

	/**
	 * Whether this scope has completed a <b>full</b> project compilation
	 * (all source files). Used by staged compilation: Phase A (single-file)
	 * sets {@code compiled=true} but not {@code fullyCompiled}, so Phase B
	 * knows to run the background full compilation.
	 */
	private volatile boolean fullyCompiled = false;

	/**
	 * Whether this scope's classpath has been resolved (dependency JARs
	 * are known). Scopes registered via {@code registerDiscoveredProjects}
	 * start with empty classpaths ({@code classpathResolved = false}) and
	 * are upgraded later via {@code updateProjectClasspaths}.
	 */
	private volatile boolean classpathResolved = false;

	/**
	 * Whether compilation of this scope failed with an unrecoverable error
	 * (typically {@link OutOfMemoryError}). When set, the scope will not
	 * be retried until the classpath changes (which calls
	 * {@link #resetCompilationFailed()}).
	 */
	private volatile boolean compilationFailed = false;

	/**
	 * Whether this scope's heavy state (AST, classloader, compilation unit)
	 * has been evicted to reduce memory usage. Evicted scopes are
	 * transparently reactivated on the next access.
	 */
	private volatile boolean evicted = false;

	/**
	 * Timestamp (millis) of the last access to this scope. Updated on
	 * every read/write lock acquisition. Used by the eviction scheduler
	 * to identify inactive scopes.
	 */
	private volatile long lastAccessedAt = System.currentTimeMillis();

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

	public boolean isFullyCompiled() {
		return fullyCompiled;
	}

	public void setFullyCompiled(boolean fullyCompiled) {
		this.fullyCompiled = fullyCompiled;
	}

	public boolean isClasspathResolved() {
		return classpathResolved;
	}

	public void setClasspathResolved(boolean classpathResolved) {
		this.classpathResolved = classpathResolved;
	}

	public boolean isCompilationFailed() {
		return compilationFailed;
	}

	public void setCompilationFailed(boolean compilationFailed) {
		this.compilationFailed = compilationFailed;
	}

	/**
	 * Reset the compilation-failed flag so the scope can be retried,
	 * e.g. after a classpath change or heap increase.  Also resets
	 * the compiled flag so the next request triggers a fresh compilation.
	 */
	public void resetCompilationFailed() {
		this.compilationFailed = false;
		this.compiled = false;
		this.fullyCompiled = false;
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
		try {
			com.tomaszrup.groovyls.compiler.SharedClassGraphCache sharedCache =
					com.tomaszrup.groovyls.compiler.SharedClassGraphCache.getInstance();
			classGraphScanResult = sharedCache.acquire(cl);
		} catch (VirtualMachineError e) {
			// ClassGraph scan OOM — log and return null. Completion/code
			// actions will degrade (no classpath type suggestions) but
			// the server survives.
			org.slf4j.LoggerFactory.getLogger(ProjectScope.class)
					.error("ClassGraph scan failed with {}: {} — classpath type suggestions "
							+ "will be unavailable for {}", e.getClass().getSimpleName(),
							e.getMessage(), projectRoot);
			classGraphScanResult = null;
		}
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

	/**
	 * Record an access to this scope, preventing TTL-based eviction.
	 * Called automatically when the scope's lock is acquired.
	 */
	public void touchAccess() {
		lastAccessedAt = System.currentTimeMillis();
	}

	public long getLastAccessedAt() {
		return lastAccessedAt;
	}

	public boolean isEvicted() {
		return evicted;
	}

	/**
	 * Evict heavy state (AST, classloader, compilation unit, scan result)
	 * from this scope to reduce memory usage. The scope shell (project root,
	 * factory, dependency graph) is preserved so it can be reactivated.
	 *
	 * <p>Must be called under the write lock.</p>
	 */
	public void evictHeavyState() {
		long usedBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

		// Release ClassGraph scan result
		ScanResult sr = classGraphScanResult;
		if (sr != null) {
			com.tomaszrup.groovyls.compiler.SharedClassGraphCache.getInstance().release(sr);
			classGraphScanResult = null;
		}

		// Close classloader
		GroovyClassLoader cl = classLoader;
		if (cl != null) {
			try {
				cl.close();
			} catch (Exception e) {
				// Best effort
			}
			classLoader = null;
		}

		// Clear AST data and compilation unit
		astVisitor = null;
		compilationUnit = null;
		prevDiagnosticsByFile = null;

		// Reset compilation state so next access triggers recompilation
		compiled = false;
		fullyCompiled = false;
		compilationFailed = false;
		evicted = true;

		// Suggest GC to reclaim freed objects
		System.gc();
		long usedAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		long freedMB = (usedBefore - usedAfter) / (1024 * 1024);
		logger.info("Evicted heavy state for scope {} (freed ~{}MB)", projectRoot, freedMB);
	}

	/**
	 * Clear the evicted flag, allowing the scope to be used normally.
	 * Called after the scope is reactivated by recompilation.
	 */
	public void clearEvicted() {
		evicted = false;
		touchAccess();
	}

	/**
	 * Release all heavy resources held by this scope. Call on server
	 * shutdown or scope removal.
	 * <ul>
	 *   <li>Releases the shared ClassGraph scan result ref</li>
	 *   <li>Disposes the Java source locator (releases shared index ref)</li>
	 *   <li>Closes the classloader</li>
	 *   <li>Clears AST data</li>
	 * </ul>
	 */
	public void dispose() {
		// Release ClassGraph scan result
		ScanResult sr = classGraphScanResult;
		if (sr != null) {
			com.tomaszrup.groovyls.compiler.SharedClassGraphCache.getInstance().release(sr);
			classGraphScanResult = null;
		}

		// Dispose source locator (releases shared source-JAR index)
		JavaSourceLocator locator = javaSourceLocator;
		if (locator != null) {
			locator.dispose();
		}

		// Close classloader
		GroovyClassLoader cl = classLoader;
		if (cl != null) {
			try {
				cl.close();
			} catch (Exception e) {
				// Best effort
			}
			classLoader = null;
		}

		// Clear AST data
		astVisitor = null;
		compilationUnit = null;
		prevDiagnosticsByFile = null;
	}
}
