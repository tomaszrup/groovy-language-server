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
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.services.LanguageClient;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.ClassSignature;
import com.tomaszrup.groovyls.compiler.CompilationOrchestrator;
import com.tomaszrup.groovyls.compiler.DependencyGraph;
import com.tomaszrup.groovyls.compiler.DiagnosticHandler;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates compilation orchestration: full and incremental compilation,
 * AST visiting, dependency graph updates, placeholder injection, and
 * diagnostics publishing.
 */
public class CompilationService {
	private static final Logger logger = LoggerFactory.getLogger(CompilationService.class);

	/**
	 * Maximum number of simultaneously changed files for which incremental
	 * compilation is attempted instead of falling back to full compilation.
	 */
	private static final int INCREMENTAL_MAX_CHANGED = 3;

	/**
	 * Maximum total source files (changed + dependencies) to include in an
	 * incremental compilation unit. If exceeded, falls back to full compile.
	 */
	private static final int INCREMENTAL_MAX_FILES = 50;

	private final CompilationOrchestrator compilationOrchestrator = new CompilationOrchestrator();
	private final DiagnosticHandler diagnosticHandler = new DiagnosticHandler();
	private final FileContentsTracker fileContentsTracker;
	private volatile LanguageClient languageClient;

	public CompilationService(FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
	}

	public void setLanguageClient(LanguageClient client) {
		this.languageClient = client;
	}

	public FileContentsTracker getFileContentsTracker() {
		return fileContentsTracker;
	}

	// --- AST visiting ---

	public void visitAST(ProjectScope scope) {
		ASTNodeVisitor visitor = compilationOrchestrator.visitAST(scope.getCompilationUnit());
		if (visitor != null) {
			scope.setAstVisitor(visitor);
		}
	}

	public void visitAST(ProjectScope scope, Set<URI> uris) {
		scope.setAstVisitor(compilationOrchestrator.visitAST(
				scope.getCompilationUnit(), scope.getAstVisitor(), uris));
	}

	// --- Compilation unit management ---

	public boolean createOrUpdateCompilationUnit(ProjectScope scope) {
		return createOrUpdateCompilationUnit(scope, java.util.Collections.emptySet());
	}

	public boolean createOrUpdateCompilationUnit(ProjectScope scope, Set<URI> additionalInvalidations) {
		GroovyLSCompilationUnit[] cuHolder = { scope.getCompilationUnit() };
		ASTNodeVisitor[] avHolder = { scope.getAstVisitor() };
		ScanResult[] srHolder = { scope.getClassGraphScanResult() };
		GroovyClassLoader[] clHolder = { scope.getClassLoader() };

		boolean result = compilationOrchestrator.createOrUpdateCompilationUnit(
				cuHolder, avHolder, scope.getProjectRoot(),
				scope.getCompilationUnitFactory(), fileContentsTracker,
				srHolder, clHolder, additionalInvalidations);

		scope.setCompilationUnit(cuHolder[0]);
		scope.setClassGraphScanResult(srHolder[0]);
		scope.setClassLoader(clHolder[0]);
		// Keep the source locator in sync with the compilation classloader
		// so that "Go to Definition" can locate .class files inside JARs.
		if (scope.getJavaSourceLocator() != null && scope.getClassLoader() != null) {
			scope.getJavaSourceLocator().setCompilationClassLoader(scope.getClassLoader());
		}
		return result;
	}

	// --- Compilation ---

	public void compile(ProjectScope scope) {
		ErrorCollector collector = compilationOrchestrator.compile(
				scope.getCompilationUnit(), scope.getProjectRoot());
		if (collector != null) {
			DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
					scope.getCompilationUnit(), collector, scope.getProjectRoot(), scope.getPrevDiagnosticsByFile());
			scope.setPrevDiagnosticsByFile(result.getDiagnosticsByFile());
			LanguageClient client = languageClient;
			if (client != null) {
				result.getDiagnosticsToPublish().forEach(client::publishDiagnostics);
			}
		}
	}

	/**
	 * Performs the initial (deferred) compilation of a scope if it hasn't
	 * been compiled yet.  Called under the scope's write lock.
	 *
	 * @return {@code true} if a full compilation was actually performed,
	 *         {@code false} if the scope was already compiled or could not
	 *         be compiled (e.g. classpath not yet resolved)
	 */
	public boolean ensureScopeCompiled(ProjectScope scope) {
		if (scope.isCompiled()) {
			return false;
		}
		// Guard: do not compile a scope whose classpath hasn't been resolved
		// yet — this would produce thousands of false-positive diagnostics.
		if (!scope.isClasspathResolved() && scope.getProjectRoot() != null) {
			logger.debug("Skipping compilation of {} — classpath not yet resolved", scope.getProjectRoot());
			return false;
		}
		createOrUpdateCompilationUnit(scope);
		resetChangedFilesForScope(scope);
		compile(scope);
		visitAST(scope);
		// Build the full dependency graph after initial compilation
		if (scope.getAstVisitor() != null) {
			scope.getDependencyGraph().clear();
			for (URI uri : scope.getAstVisitor().getDependenciesByURI().keySet()) {
				Set<URI> deps = scope.getAstVisitor().resolveSourceDependencies(uri);
				scope.getDependencyGraph().updateDependencies(uri, deps);
			}
		}
		scope.setCompiled(true);
		return true;
	}

	/**
	 * Ensures the project scope that owns {@code uri} has been compiled at
	 * least once, using the given {@link ProjectScopeManager} to resolve
	 * the scope. Submits background compilation if needed.
	 *
	 * @return the {@link ProjectScope} that owns the URI, or {@code null}
	 */
	public ProjectScope ensureCompiledForContext(URI uri, ProjectScopeManager scopeManager,
			java.util.concurrent.ExecutorService backgroundCompiler) {
		ProjectScope scope = scopeManager.findProjectScope(uri);
		if (scope == null) {
			return null;
		}

		if (!scope.isCompiled() || scope.getAstVisitor() == null) {
			scope.getLock().writeLock().lock();
			try {
				ensureScopeCompiled(scope);
				if (scope.getAstVisitor() == null) {
					return scope;
				}
				if (fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
					compileAndVisitAST(scope, uri);
				}
			} finally {
				scope.getLock().writeLock().unlock();
			}
		} else if (fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
			// Compile synchronously so callers always see up-to-date AST state
			scope.getLock().writeLock().lock();
			try {
				if (fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
					compileAndVisitAST(scope, uri);
				}
			} finally {
				scope.getLock().writeLock().unlock();
			}
		}

		return scope;
	}

	protected void recompileIfContextChanged(ProjectScope scope, URI newContext) {
		if (scope.getPreviousContext() == null || scope.getPreviousContext().equals(newContext)) {
			return;
		}
		if (fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
			compileAndVisitAST(scope, newContext);
		} else {
			scope.setPreviousContext(newContext);
		}
	}

	public void compileAndVisitAST(ProjectScope scope, URI contextURI) {
		// Early return: if the scope is already compiled and there are no
		// pending changes under this scope's root, skip the compile cycle.
		if (scope.isCompiled() && !fileContentsTracker.hasChangedURIsUnder(scope.getProjectRoot())) {
			scope.setPreviousContext(contextURI);
			return;
		}

		Set<URI> changedSnapshot = new HashSet<>(fileContentsTracker.getChangedURIs());

		// Try incremental (single-file) compilation for small change sets
		if (changedSnapshot.size() <= INCREMENTAL_MAX_CHANGED
				&& scope.getAstVisitor() != null
				&& scope.isCompiled()
				&& !scope.getDependencyGraph().isEmpty()) {
			if (tryIncrementalCompile(scope, contextURI, changedSnapshot)) {
				clearProcessedChanges(scope, changedSnapshot);
				scope.setPreviousContext(contextURI);
				return;
			}
			logger.info("Incremental compile failed for scope {}, falling back to full", scope.getProjectRoot());
		}

		// Full compilation path
		Set<URI> affectedDependents = scope.getDependencyGraph().getTransitiveDependents(changedSnapshot);

		Set<URI> allAffectedURIs = new HashSet<>(changedSnapshot);
		allAffectedURIs.add(contextURI);
		allAffectedURIs.addAll(affectedDependents);

		boolean isSameUnit = createOrUpdateCompilationUnit(scope, affectedDependents);
		clearProcessedChanges(scope, changedSnapshot);
		compile(scope);
		if (isSameUnit) {
			visitAST(scope, allAffectedURIs);
		} else {
			visitAST(scope);
		}

		updateDependencyGraph(scope, allAffectedURIs);

		scope.setPreviousContext(contextURI);
		scope.setCompiled(true);
	}

	/**
	 * Attempts incremental single-file compilation for a small set of changed files.
	 */
	private boolean tryIncrementalCompile(ProjectScope scope, URI contextURI, Set<URI> changedSnapshot) {
		Set<URI> changedPlusContext = new HashSet<>(changedSnapshot);
		changedPlusContext.add(contextURI);

		Set<URI> forwardDeps = scope.getDependencyGraph().getTransitiveDependencies(changedPlusContext, 2);

		Set<URI> filesToCompile = new HashSet<>(changedPlusContext);
		filesToCompile.addAll(forwardDeps);

		if (filesToCompile.size() > INCREMENTAL_MAX_FILES) {
			logger.info("Incremental compile aborted: {} files exceeds limit of {}",
					filesToCompile.size(), INCREMENTAL_MAX_FILES);
			return false;
		}

		Map<String, ClassSignature> oldSignatures = captureClassSignatures(scope.getAstVisitor(), changedPlusContext);

		GroovyLSCompilationUnit incrementalUnit = scope.getCompilationUnitFactory().createIncremental(
				scope.getProjectRoot(), fileContentsTracker, filesToCompile);
		if (incrementalUnit == null) {
			return false;
		}

		ErrorCollector collector = compilationOrchestrator.compileIncremental(incrementalUnit, scope.getProjectRoot());

		ASTNodeVisitor newVisitor = compilationOrchestrator.visitAST(
				incrementalUnit, scope.getAstVisitor(), changedPlusContext);

		Map<String, ClassSignature> newSignatures = captureClassSignatures(newVisitor, changedPlusContext);
		if (!oldSignatures.equals(newSignatures)) {
			logger.info("API change detected in incremental compile for scope {}, need full recompile",
					scope.getProjectRoot());
			return false;
		}

		scope.setAstVisitor(newVisitor);

		updateDependencyGraph(scope, changedPlusContext);

		if (collector != null) {
			DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
					incrementalUnit, collector, scope.getProjectRoot(), scope.getPrevDiagnosticsByFile());
			scope.setPrevDiagnosticsByFile(result.getDiagnosticsByFile());
			LanguageClient client = languageClient;
			if (client != null) {
				result.getDiagnosticsToPublish().forEach(client::publishDiagnostics);
			}
		}

		logger.debug("Incremental compilation succeeded for scope {} ({} changed, {} total in unit)",
				scope.getProjectRoot(), changedPlusContext.size(), filesToCompile.size());
		return true;
	}

	/**
	 * Captures class signatures for all classes defined in the given URIs.
	 */
	private Map<String, ClassSignature> captureClassSignatures(ASTNodeVisitor visitor, Set<URI> uris) {
		Map<String, ClassSignature> signatures = new HashMap<>();
		if (visitor == null) {
			return signatures;
		}
		for (URI uri : uris) {
			List<org.codehaus.groovy.ast.ClassNode> classNodes = visitor.getClassNodes(uri);
			for (org.codehaus.groovy.ast.ClassNode cn : classNodes) {
				try {
					signatures.put(cn.getName(), ClassSignature.of(cn));
				} catch (Exception e) {
					logger.debug("Failed to capture signature for {}: {}", cn.getName(), e.getMessage());
				}
			}
		}
		return signatures;
	}

	// --- Dependency graph ---

	public void updateDependencyGraph(ProjectScope scope, Set<URI> uris) {
		if (scope.getAstVisitor() == null) {
			return;
		}
		for (URI uri : uris) {
			Set<URI> deps = scope.getAstVisitor().resolveSourceDependencies(uri);
			scope.getDependencyGraph().updateDependencies(uri, deps);
		}
	}

	// --- Changed file tracking ---

	public void resetChangedFilesForScope(ProjectScope scope) {
		if (scope.getProjectRoot() != null) {
			Set<URI> toReset = new HashSet<>();
			for (URI uri : fileContentsTracker.getChangedURIs()) {
				try {
					if (Paths.get(uri).startsWith(scope.getProjectRoot())) {
						toReset.add(uri);
					}
				} catch (Exception e) {
					// ignore URIs that can't be converted to Path
				}
			}
			if (!toReset.isEmpty()) {
				fileContentsTracker.resetChangedFiles(toReset);
			}
		} else {
			fileContentsTracker.resetChangedFiles();
		}
	}

	public void clearProcessedChanges(ProjectScope scope, Set<URI> snapshot) {
		if (scope.getProjectRoot() != null) {
			Set<URI> toReset = new HashSet<>();
			for (URI uri : snapshot) {
				try {
					if (Paths.get(uri).startsWith(scope.getProjectRoot())) {
						toReset.add(uri);
					}
				} catch (Exception e) {
					// ignore
				}
			}
			if (!toReset.isEmpty()) {
				fileContentsTracker.resetChangedFiles(toReset);
			}
		} else {
			fileContentsTracker.resetChangedFiles(snapshot);
		}
	}

	// --- Placeholder injection helpers ---

	/**
	 * Injects a placeholder into the document source to force AST node creation
	 * at the cursor position for completion.
	 *
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	public String injectCompletionPlaceholder(ProjectScope scope, URI uri, Position position) {
		String originalSource = compilationOrchestrator.injectCompletionPlaceholder(
				scope.getAstVisitor(), fileContentsTracker, uri, position);
		if (originalSource != null) {
			compileAndVisitAST(scope, uri);
		}
		return originalSource;
	}

	/**
	 * Injects a closing parenthesis placeholder into the document source to force
	 * {@code ArgumentListExpression} creation in the AST for signature help.
	 *
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	public String injectSignatureHelpPlaceholder(ProjectScope scope, URI uri, Position position) {
		String originalSource = compilationOrchestrator.injectSignatureHelpPlaceholder(
				fileContentsTracker, uri, position);
		if (originalSource != null) {
			compileAndVisitAST(scope, uri);
		}
		return originalSource;
	}

	/**
	 * Restores the original document source after placeholder injection.
	 *
	 * <p>The source text is restored immediately and the URI is marked as
	 * changed, but recompilation is <b>deferred</b> until the next request
	 * that needs an up-to-date AST (e.g. the next completion, hover, or
	 * didChange-triggered compile).  This avoids an expensive extra
	 * compile+AST-visit cycle on every completion/signature-help request
	 * that uses placeholder injection.</p>
	 *
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	public void restoreDocumentSource(ProjectScope scope, URI uri, String originalSource) {
		compilationOrchestrator.restoreDocumentSource(fileContentsTracker, uri, originalSource);
		// Recompilation is intentionally skipped here.  The call to
		// restoreDocumentSource() above already calls forceChanged(uri),
		// so the next ensureCompiledForContext / compileAndVisitAST
		// invocation will detect the dirty flag and recompile then.
		logger.debug("Deferred recompile after restoring document source for {}", uri);
	}

	/**
	 * Performs a quick, syntax-only parse of a single file and publishes
	 * any syntax errors as diagnostics.
	 */
	public void syntaxCheckSingleFile(URI uri) {
		String source = fileContentsTracker.getContents(uri);
		if (source == null) {
			return;
		}
		try {
			CompilerConfiguration config = new CompilerConfiguration();
			GroovyLSCompilationUnit unit = new GroovyLSCompilationUnit(config);
			unit.addSource(uri.toString(), source);
			try {
				unit.compile(Phases.CONVERSION);
			} catch (CompilationFailedException e) {
				// Expected for code with syntax errors
			} catch (Exception e) {
				logger.debug("Syntax check failed for {}: {}", uri, e.getMessage());
			}

			ErrorCollector collector = unit.getErrorCollector();
			if (collector != null) {
				DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
						unit, collector, null, null);
				LanguageClient client = languageClient;
				if (client != null) {
					result.getDiagnosticsToPublish().forEach(client::publishDiagnostics);
				}
			}
		} catch (Exception e) {
			logger.debug("Syntax-only check failed for {}: {}", uri, e.getMessage());
		}
	}

	/**
	 * Performs a full classpath-change compilation on the given scope.
	 * Clears the dependency graph and rebuilds it from scratch.
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	public void recompileForClasspathChange(ProjectScope scope) {
		scope.getDependencyGraph().clear();
		createOrUpdateCompilationUnit(scope);
		resetChangedFilesForScope(scope);
		compile(scope);
		visitAST(scope);
		if (scope.getAstVisitor() != null) {
			for (URI uri : scope.getAstVisitor().getDependenciesByURI().keySet()) {
				Set<URI> deps = scope.getAstVisitor().resolveSourceDependencies(uri);
				scope.getDependencyGraph().updateDependencies(uri, deps);
			}
		}
		scope.setPreviousContext(null);
		scope.setCompiled(true);
	}

	/**
	 * Performs a full rebuild for a scope after Java/build-file changes.
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	public void recompileAfterJavaChange(ProjectScope scope) {
		scope.getCompilationUnitFactory().invalidateFileCache();
		scope.getCompilationUnitFactory().invalidateCompilationUnit();
		scope.getDependencyGraph().clear();
		createOrUpdateCompilationUnit(scope);
		resetChangedFilesForScope(scope);
		compile(scope);
		visitAST(scope);
		if (scope.getAstVisitor() != null) {
			for (URI uri : scope.getAstVisitor().getDependenciesByURI().keySet()) {
				Set<URI> deps = scope.getAstVisitor().resolveSourceDependencies(uri);
				scope.getDependencyGraph().updateDependencies(uri, deps);
			}
		}
	}
}
