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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.ClassSignature;
import com.tomaszrup.groovyls.compiler.CompilationOrchestrator;
import com.tomaszrup.groovyls.compiler.DiagnosticHandler;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.GroovyVersionDetector;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;
import com.tomaszrup.groovyls.util.MdcProjectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates compilation orchestration: full and incremental compilation,
 * AST visiting, dependency graph updates, placeholder injection, and
 * diagnostics publishing.
 */
public class CompilationService {
	private static final Logger logger = LoggerFactory.getLogger(CompilationService.class);
	private static final String VERSION_GUARD_DIAGNOSTIC_SOURCE = "groovy-language-server";
	private static final Pattern IMPLICATION_OPERATOR_PATTERN = Pattern.compile("==>");
	private static final Pattern INSTANCEOF_PATTERN_VARIABLE_PATTERN =
			Pattern.compile("\\binstanceof\\s+[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:<[^>]+>)?\\s+[A-Za-z_$][\\w$]*\\b");
	private static final Pattern VAR_MULTI_ASSIGNMENT_PATTERN =
			Pattern.compile("\\bvar\\s*\\(\\s*[^)]*\\)");
	private static final Pattern FOR_LOOP_INDEX_VARIABLE_PATTERN =
			Pattern.compile("\\bfor\\s*\\(\\s*[^,\\)]+,\\s*[^\\)]*\\bin\\b[^\\)]*\\)");
	private static final Pattern UNDERSCORE_PLACEHOLDER_LAMBDA_PATTERN =
			Pattern.compile("\\(\\s*[^)]*\\b_\\b[^)]*\\)\\s*->");
	private static final Pattern UNDERSCORE_PLACEHOLDER_CLOSURE_PATTERN =
			Pattern.compile("\\{[^\\n\\r{}]*\\b_\\b[^\\n\\r{}]*->");
	private static final Pattern MULTIDIMENSIONAL_ARRAY_JAVA_LITERAL_PATTERN =
			Pattern.compile("\\bnew\\s+[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*\\[\\s*]\\s*\\[\\s*]\\s*\\{\\s*\\{");
	private static final java.util.List<GuardedSyntaxFeature> GROOVY5_SYNTAX_FEATURES = java.util.List.of(
			new GuardedSyntaxFeature(IMPLICATION_OPERATOR_PATTERN, "implication operator (==>)"),
			new GuardedSyntaxFeature(INSTANCEOF_PATTERN_VARIABLE_PATTERN, "instanceof pattern variable"),
			new GuardedSyntaxFeature(VAR_MULTI_ASSIGNMENT_PATTERN, "var with multi-assignment"),
			new GuardedSyntaxFeature(FOR_LOOP_INDEX_VARIABLE_PATTERN, "for-loop index variable declaration"),
			new GuardedSyntaxFeature(UNDERSCORE_PLACEHOLDER_LAMBDA_PATTERN, "underscore placeholder parameters in lambdas"),
			new GuardedSyntaxFeature(UNDERSCORE_PLACEHOLDER_CLOSURE_PATTERN, "underscore placeholder parameters in closures"),
			new GuardedSyntaxFeature(MULTIDIMENSIONAL_ARRAY_JAVA_LITERAL_PATTERN, "Java-style multidimensional array literals"));

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

	/**
	 * Global semaphore that caps concurrent compilations across all thread
	 * pools (import, background, LSP).  May be {@code null} for tests that
	 * don't inject an {@link ExecutorPools} instance.
	 */
	private volatile Semaphore compilationPermits;

	public CompilationService(FileContentsTracker fileContentsTracker) {
		this.fileContentsTracker = fileContentsTracker;
	}

	public void setLanguageClient(LanguageClient client) {
		this.languageClient = client;
	}

	/**
	 * Inject the global compilation semaphore from {@link ExecutorPools}.
	 * When set, all compilation entry points will acquire a permit before
	 * starting compilation and release it in a {@code finally} block.
	 */
	public void setCompilationPermits(Semaphore permits) {
		this.compilationPermits = permits;
	}

	public FileContentsTracker getFileContentsTracker() {
		return fileContentsTracker;
	}

	// --- AST visiting ---

	public void visitAST(ProjectScope scope) {
		visitAST(scope, Collections.emptySet());
	}

	public void visitAST(ProjectScope scope, Set<URI> uris) {
		visitAST(scope, uris, Collections.emptySet());
	}

	/**
	 * Visits the AST for the given scope, optionally preserving the
	 * last-known-good AST data for files that have compilation errors
	 * and produced a degraded AST.
	 *
	 * @param scope     the project scope
	 * @param uris      URIs to visit (empty = full visit)
	 * @param errorURIs URIs that had compilation errors
	 */
	public void visitAST(ProjectScope scope, Set<URI> uris, Set<URI> errorURIs) {
		ASTNodeVisitor oldVisitor = scope.getAstVisitor();
		ASTNodeVisitor visitor;
		if (uris.isEmpty()) {
			visitor = compilationOrchestrator.visitAST(scope.getCompilationUnit());
		} else {
			visitor = compilationOrchestrator.visitAST(
					scope.getCompilationUnit(), oldVisitor, uris);
		}
		if (visitor != null) {
			preserveASTForErrorFiles(visitor, oldVisitor, errorURIs, uris);
			scope.setAstVisitor(visitor);
		}
	}

	// --- Compilation unit management ---

	public boolean createOrUpdateCompilationUnit(ProjectScope scope) {
		return createOrUpdateCompilationUnit(scope, java.util.Collections.emptySet());
	}

	public boolean createOrUpdateCompilationUnit(ProjectScope scope, Set<URI> additionalInvalidations) {
		GroovyClassLoader oldClassLoader = scope.getClassLoader();

		com.tomaszrup.groovyls.compiler.CompilationResult result =
				compilationOrchestrator.createOrUpdateCompilationUnit(
						scope.getCompilationUnit(), scope.getProjectRoot(),
						scope.getCompilationUnitFactory(), fileContentsTracker,
						scope.getClassGraphScanResult(), oldClassLoader,
						additionalInvalidations);

		scope.setCompilationUnit(result.getCompilationUnit());
		scope.setClassLoader(result.getClassLoader());

		// If the classloader changed, the lazily-acquired ClassGraph scan
		// result is stale — release it so the next ensureClassGraphScanned()
		// call acquires a fresh one for the new classpath.
		if (result.getClassLoader() != oldClassLoader) {
			ScanResult oldScan = scope.getClassGraphScanResult();
			if (oldScan != null) {
				com.tomaszrup.groovyls.compiler.SharedClassGraphCache.getInstance().release(oldScan);
				scope.setClassGraphScanResult(null);
			}
			scope.clearClasspathIndexes();
		} else {
			// Classloader unchanged — keep the existing scan result
			scope.setClassGraphScanResult(result.getScanResult());
		}

		// Keep the source locator in sync with the compilation classloader
		// so that "Go to Definition" can locate .class files inside JARs.
		if (scope.getJavaSourceLocator() != null && scope.getClassLoader() != null) {
			scope.getJavaSourceLocator().setCompilationClassLoader(scope.getClassLoader());
		}
		return result.isSameUnit();
	}

	// --- Compilation ---

	/**
	 * Compiles the scope and publishes diagnostics.
	 *
	 * @return the set of URIs that had compilation errors (syntax errors),
	 *         or an empty set if compilation succeeded without errors
	 */
	public Set<URI> compile(ProjectScope scope) {
		MdcProjectContext.setProject(scope.getProjectRoot());
		Semaphore permits = compilationPermits;
		if (permits != null) {
			try {
				permits.acquire();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.debug("Compilation interrupted for {}", scope.getProjectRoot());
				return Collections.emptySet();
			}
		}
		try {
			ErrorCollector collector = compilationOrchestrator.compile(
					scope.getCompilationUnit(), scope.getProjectRoot());
			if (collector != null) {
				DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
						scope.getCompilationUnit(), collector, scope.getProjectRoot(), scope.getPrevDiagnosticsByFile());
				result = mergeGroovyVersionSyntaxDiagnostics(scope, scope.getCompilationUnit(), result);
				scope.setPrevDiagnosticsByFile(result.getDiagnosticsByFile());
				LanguageClient client = languageClient;
				if (client != null) {
					publishDiagnosticsBatch(client, result.getDiagnosticsToPublish());
				}
				return extractErrorURIs(collector);
			}
		} catch (VirtualMachineError e) {
			handleCompilationOOM(scope, e, "compile");
		} finally {
			if (permits != null) {
				permits.release();
			}
		}
		return Collections.emptySet();
	}

	/**
	 * Extracts the set of source file URIs that have syntax errors from
	 * the given error collector.
	 */
	private Set<URI> extractErrorURIs(ErrorCollector collector) {
		Set<URI> errorURIs = new HashSet<>();
		List<? extends Message> errors = collector.getErrors();
		if (errors == null || errors.isEmpty()) {
			return errorURIs;
		}
		for (Message message : errors) {
			if (message instanceof SyntaxErrorMessage) {
				SyntaxErrorMessage sem = (SyntaxErrorMessage) message;
				String sourceLocator = sem.getCause().getSourceLocator();
				if (sourceLocator != null && !sourceLocator.isEmpty()) {
					URI errorUri = GroovyLanguageServerUtils.sourceLocatorToUri(sourceLocator);
					if (errorUri != null) {
						errorURIs.add(errorUri);
					}
				}
			}
		}
		return errorURIs;
	}

	/**
	 * For files that had compilation errors and produced a degraded AST
	 * (significantly fewer nodes than the previous compilation), restore
	 * the last-known-good AST data so semantic tokens remain intact.
	 *
	 * @param newVisitor  the newly built AST visitor
	 * @param oldVisitor  the previous (last-known-good) AST visitor, may be null
	 * @param errorURIs   URIs that had compilation errors
	 */
	private void preserveASTForErrorFiles(ASTNodeVisitor newVisitor, ASTNodeVisitor oldVisitor,
			Set<URI> errorURIs, Set<URI> recompiledURIs) {
		if (oldVisitor == null) {
			return;
		}
		Set<URI> candidates = !errorURIs.isEmpty() ? errorURIs : recompiledURIs;
		if (candidates == null || candidates.isEmpty()) {
			return;
		}
		for (URI errorURI : candidates) {
			int oldCount = oldVisitor.getNodeCount(errorURI);
			if (oldCount == 0) {
				// No previous data to preserve
				continue;
			}
			int newCount = newVisitor.getNodeCount(errorURI);
			// Restore when the new AST appears degraded due to errors.
			// This includes both:
			// 1) significantly fewer nodes than before, and
			// 2) a complete collapse to zero nodes for an error URI.
			// The second case commonly occurs during transient edit-time
			// syntax breaks (e.g. missing brace) and would otherwise drop
			// semantic tokens for the entire file.
			if (newCount == 0 || newCount < oldCount / 2) {
				logger.debug("Preserving last-known-good AST for {} (old: {} nodes, new: {} nodes)",
						errorURI, oldCount, newCount);
				newVisitor.restoreFromPrevious(errorURI, oldVisitor);
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
		return ensureScopeCompiled(scope, null, null);
	}

	/**
	 * Performs the initial (deferred) compilation of a scope if it hasn't
	 * been compiled yet.  When {@code triggerURI} is non-null and the
	 * project has many source files, a <b>staged compilation</b> strategy
	 * is used to reduce time to first diagnostic:
	 * <ol>
	 *   <li><b>Phase A</b> — compile only the trigger file (+ direct
	 *       imports) via {@code createIncremental}, publish diagnostics
	 *       for it immediately.</li>
	 *   <li><b>Phase B</b> — submit full compilation to the background
	 *       pool.  When complete, the partial AST is replaced with the
	 *       full AST and diagnostics are republished if they changed.</li>
	 * </ol>
	 *
	 * <p>Called under the scope's write lock.</p>
	 *
	 * @param triggerURI         the URI that triggered compilation (may be null)
	 * @param backgroundCompiler executor for deferred full compilation (may be null)
	 * @return {@code true} if a compilation was performed (full or staged)
	 */
	public boolean ensureScopeCompiled(ProjectScope scope, URI triggerURI,
			java.util.concurrent.ExecutorService backgroundCompiler) {
		if (scope.isCompiled()) {
			return false;
		}
		// If a previous compilation failed with OOM, don't retry endlessly.
		// The user needs to increase heap or reduce scope count.
		if (scope.isCompilationFailed()) {
			logger.debug("Skipping compilation of {} — previously failed with OOM", scope.getProjectRoot());
			return false;
		}
		// Guard: do not compile a scope whose classpath hasn't been resolved
		// yet — this would produce thousands of false-positive diagnostics.
		if (!scope.isClasspathResolved() && scope.getProjectRoot() != null) {
			logger.debug("Skipping compilation of {} — classpath not yet resolved", scope.getProjectRoot());
			return false;
		}

		// --- Staged compilation: fast single-file first, full compile later ---
		// Only use staged path when we have a trigger file AND a background pool.
		if (triggerURI != null && backgroundCompiler != null) {
			long stageStart = System.currentTimeMillis();

			// Phase A: compile just the opened file with the project's classpath
			Set<URI> singleFile = Set.of(triggerURI);
			GroovyLSCompilationUnit incrementalUnit = scope.getCompilationUnitFactory()
					.createIncremental(scope.getProjectRoot(), fileContentsTracker, singleFile);

			if (incrementalUnit != null) {
				// Ensure the classloader is set for the scope (needed later)
				if (scope.getClassLoader() == null) {
					scope.setClassLoader(incrementalUnit.getClassLoader());
				}

				ErrorCollector collector = compilationOrchestrator.compileIncremental(
						incrementalUnit, scope.getProjectRoot());
				ASTNodeVisitor visitor = compilationOrchestrator.visitAST(incrementalUnit);
				if (visitor != null) {
					scope.setAstVisitor(visitor);
				}

				// Publish diagnostics for the single file immediately
				if (collector != null) {
					DiagnosticHandler.DiagnosticResult result = diagnosticHandler.handleErrorCollector(
							incrementalUnit, collector, scope.getProjectRoot(),
							scope.getPrevDiagnosticsByFile());
					result = mergeGroovyVersionSyntaxDiagnostics(scope, incrementalUnit, result);
					scope.setPrevDiagnosticsByFile(result.getDiagnosticsByFile());
					LanguageClient client = languageClient;
					if (client != null) {
						publishDiagnosticsBatch(client, result.getDiagnosticsToPublish());
					}
				}

				long stageElapsed = System.currentTimeMillis() - stageStart;
				logger.info("Staged Phase A for {} completed in {}ms (single-file diagnostic)",
						scope.getProjectRoot(), stageElapsed);

				// Mark as compiled so semantic tokens requests don't trigger
				// redundant compilation while Phase B is running in background.
				// Phase A provides a partial but usable AST for immediate feedback.
				scope.setCompiled(true);
			}

			// Phase B: schedule full compilation in the background
			backgroundCompiler.submit(() -> {
				scope.getLock().writeLock().lock();
				try {
					// Guard: another Phase B (or standard full compilation) may
					// have completed while this task was queued.  This prevents
					// N open tabs from causing N full compilations of the same
					// project during startup.  Check fullyCompiled instead of
					// compiled so Phase B runs even though Phase A set compiled=true.
					if (scope.isFullyCompiled()) {
						logger.debug("Phase B skipped for {} — already fully compiled",
								scope.getProjectRoot());
						return;
					}
					doFullCompilation(scope);
				} finally {
					scope.getLock().writeLock().unlock();
				}
			});

			return true;
		}

		// --- Standard full compilation path ---
		doFullCompilation(scope);
		return true;
	}

	/**
	 * Performs a full compilation of the scope: creates/updates the compilation
	 * unit, compiles, visits the AST, and builds the dependency graph.
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	private void doFullCompilation(ProjectScope scope) {
		MdcProjectContext.setProject(scope.getProjectRoot());
		long fullStart = System.currentTimeMillis();
		try {
			createOrUpdateCompilationUnit(scope);
			resetChangedFilesForScope(scope);
			Set<URI> errorURIs = compile(scope);
			visitAST(scope, Collections.emptySet(), errorURIs);
			// Build the full dependency graph after initial compilation
			if (scope.getAstVisitor() != null) {
				scope.getDependencyGraph().clear();
				for (URI uri : scope.getAstVisitor().getDependenciesByURI().keySet()) {
					Set<URI> deps = scope.getAstVisitor().resolveSourceDependencies(uri);
					scope.getDependencyGraph().updateDependencies(uri, deps);
				}
			}
		} catch (LinkageError e) {
			// NoClassDefFoundError or similar — a project dependency could not
			// be class-loaded. Log and mark compiled so we don't retry
			// endlessly; the partial AST is still usable.
			logger.warn("Classpath linkage error during full compilation of {}: {}",
					scope.getProjectRoot(), e.toString());
			logger.debug("Full compilation LinkageError details", e);
		} catch (VirtualMachineError e) {
			handleCompilationOOM(scope, e, "doFullCompilation");
			return; // setCompiled(true) still runs via finally below
		} finally {
			// Always mark as compiled to prevent infinite retry loops.
			// Even after OOM, retrying immediately would just OOM again.
			scope.setCompiled(true);
			scope.setFullyCompiled(true);
			// If this scope was evicted, clear the evicted flag and update
			// the last access time so it doesn't get immediately re-evicted.
			if (scope.isEvicted()) {
				scope.clearEvicted();
				logger.info("Reactivated evicted scope {}", scope.getProjectRoot());
			}
		}
		long fullElapsed = System.currentTimeMillis() - fullStart;
		logger.info("Full compilation of {} completed in {}ms", scope.getProjectRoot(), fullElapsed);
		// Refresh semantic tokens after full compilation replaces the AST
		LanguageClient client = languageClient;
		if (client instanceof com.tomaszrup.groovyls.GroovyLanguageClient) {
			((com.tomaszrup.groovyls.GroovyLanguageClient) client).refreshSemanticTokens();
		}
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
			logger.warn("ensureCompiledForContext uri={} projectRoot=null (no matching scope)", uri);
			return null;
		}
		logger.debug("ensureCompiledForContext uri={} projectRoot={} compiled={} hasVisitor={} classpathResolved={}",
				uri,
				scope.getProjectRoot(),
				scope.isCompiled(),
				scope.getAstVisitor() != null,
				scope.isClasspathResolved());

		// Set MDC project context for all log messages during this request
		MdcProjectContext.setProject(scope.getProjectRoot());

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
			} catch (LinkageError e) {
				logger.warn("Classpath linkage error in ensureCompiledForContext for {}: {}",
						uri, e.toString());
				logger.debug("ensureCompiledForContext LinkageError details", e);
			} catch (VirtualMachineError e) {
				handleCompilationOOM(scope, e, "ensureCompiledForContext");
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
			} catch (LinkageError e) {
				logger.warn("Classpath linkage error in ensureCompiledForContext (recompile) for {}: {}",
						uri, e.toString());
				logger.debug("ensureCompiledForContext recompile LinkageError details", e);
			} catch (VirtualMachineError e) {
				handleCompilationOOM(scope, e, "ensureCompiledForContext recompile");
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
		Set<URI> errorURIs = compile(scope);
		ASTNodeVisitor previousVisitor = scope.getAstVisitor();
		if (isSameUnit) {
			visitAST(scope, allAffectedURIs, errorURIs);
		} else {
			visitAST(scope, Collections.emptySet(), errorURIs);
			ASTNodeVisitor newVisitor = scope.getAstVisitor();
			if (newVisitor != null) {
				preserveASTForErrorFiles(newVisitor, previousVisitor, errorURIs, allAffectedURIs);
			}
		}

		updateDependencyGraph(scope, allAffectedURIs);

		scope.setPreviousContext(contextURI);
		scope.setCompiled(true);
		scope.setFullyCompiled(true);
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

		Set<URI> errorURIs = collector != null ? extractErrorURIs(collector) : Collections.emptySet();

		ASTNodeVisitor oldVisitor = scope.getAstVisitor();
		ASTNodeVisitor newVisitor = compilationOrchestrator.visitAST(
				incrementalUnit, scope.getAstVisitor(), changedPlusContext);

		// Preserve last-known-good AST for files with errors
		preserveASTForErrorFiles(newVisitor, oldVisitor, errorURIs, changedPlusContext);

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
			result = mergeGroovyVersionSyntaxDiagnostics(scope, incrementalUnit, result);
			scope.setPrevDiagnosticsByFile(result.getDiagnosticsByFile());
			LanguageClient client = languageClient;
			if (client != null) {
				publishDiagnosticsBatch(client, result.getDiagnosticsToPublish());
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
					publishDiagnosticsBatch(client, result.getDiagnosticsToPublish());
				}
			}
		} catch (Exception e) {
			logger.debug("Syntax-only check failed for {}: {}", uri, e.getMessage());
		}
	}

	private void publishDiagnosticsBatch(LanguageClient client, Set<PublishDiagnosticsParams> diagnosticsToPublish) {
		if (diagnosticsToPublish == null || diagnosticsToPublish.isEmpty()) {
			return;
		}
		diagnosticsToPublish.stream()
				.sorted(Comparator.comparing(PublishDiagnosticsParams::getUri, Comparator.nullsFirst(String::compareTo)))
				.map(this::normalizeDiagnosticsForPublishedDocument)
				.forEach(client::publishDiagnostics);
	}

	private DiagnosticHandler.DiagnosticResult mergeGroovyVersionSyntaxDiagnostics(
			ProjectScope scope,
			GroovyLSCompilationUnit compilationUnit,
			DiagnosticHandler.DiagnosticResult baseResult) {
		if (scope == null || baseResult == null) {
			return baseResult;
		}

		String detectedVersion = scope.getDetectedGroovyVersion();
		Integer projectMajor = GroovyVersionDetector.major(detectedVersion).orElse(null);
		if (projectMajor == null || projectMajor >= 5) {
			if (logger.isDebugEnabled()) {
				logger.debug(
						"Groovy 5 syntax guard inactive for scope {} (detectedVersion={}, major={})",
						scope.getProjectRoot(),
						detectedVersion,
						projectMajor);
			}
			return baseResult;
		}
		if (logger.isDebugEnabled()) {
			logger.debug(
					"Groovy 5 syntax guard active for scope {} (detectedVersion={}, major={}, guardedFeatures={})",
					scope.getProjectRoot(),
					detectedVersion,
					projectMajor,
					GROOVY5_SYNTAX_FEATURES.size());
		}

		Map<URI, List<Diagnostic>> mergedDiagnosticsByFile = new HashMap<>();
		if (baseResult.getDiagnosticsByFile() != null) {
			for (Map.Entry<URI, List<Diagnostic>> entry : baseResult.getDiagnosticsByFile().entrySet()) {
				mergedDiagnosticsByFile.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
			}
		}

		Map<URI, List<Diagnostic>> versionDiagnostics = collectGroovy5SyntaxDiagnostics(
				compilationUnit, projectMajor, detectedVersion);
		for (Map.Entry<URI, List<Diagnostic>> entry : versionDiagnostics.entrySet()) {
			mergedDiagnosticsByFile
					.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>())
					.addAll(entry.getValue());
		}

		deduplicateDiagnostics(mergedDiagnosticsByFile);

		Set<PublishDiagnosticsParams> diagnosticsToPublish = new HashSet<>();
		for (Map.Entry<URI, List<Diagnostic>> entry : mergedDiagnosticsByFile.entrySet()) {
			diagnosticsToPublish.add(new PublishDiagnosticsParams(entry.getKey().toString(), entry.getValue()));
		}

		Map<URI, List<Diagnostic>> previousDiagnosticsByFile = scope.getPrevDiagnosticsByFile();
		if (previousDiagnosticsByFile != null) {
			for (URI uri : previousDiagnosticsByFile.keySet()) {
				if (!mergedDiagnosticsByFile.containsKey(uri)) {
					diagnosticsToPublish.add(new PublishDiagnosticsParams(uri.toString(), new java.util.ArrayList<>()));
				}
			}
		}

		return new DiagnosticHandler.DiagnosticResult(diagnosticsToPublish, mergedDiagnosticsByFile);
	}

	private Map<URI, List<Diagnostic>> collectGroovy5SyntaxDiagnostics(
			GroovyLSCompilationUnit compilationUnit,
			int projectMajor,
			String projectVersion) {
		Map<URI, List<Diagnostic>> diagnosticsByFile = new HashMap<>();
		if (compilationUnit == null || compilationUnit.getAST() == null || compilationUnit.getAST().getModules() == null) {
			return diagnosticsByFile;
		}

		for (org.codehaus.groovy.ast.ModuleNode module : compilationUnit.getAST().getModules()) {
			if (module == null || module.getContext() == null) {
				continue;
			}
			org.codehaus.groovy.control.SourceUnit sourceUnit = module.getContext();
			URI uri = GroovyLanguageServerUtils.sourceLocatorToUri(sourceUnit.getName());
			if (uri == null) {
				continue;
			}

			String source = fileContentsTracker.getContents(uri);
			if (source == null && "file".equalsIgnoreCase(uri.getScheme())) {
				try {
					source = Files.readString(Paths.get(uri));
				} catch (Exception ignored) {
					source = null;
				}
			}
			if (source == null || source.isEmpty()) {
				continue;
			}

			List<Diagnostic> fileDiagnostics = new java.util.ArrayList<>();
			for (GuardedSyntaxFeature feature : GROOVY5_SYNTAX_FEATURES) {
				fileDiagnostics.addAll(createFeatureDiagnostics(
						source,
						feature.pattern,
						feature.featureName,
						projectMajor,
						projectVersion));
			}

			if (!fileDiagnostics.isEmpty()) {
				diagnosticsByFile.put(uri, fileDiagnostics);
			}
		}

		return diagnosticsByFile;
	}

	private List<Diagnostic> createFeatureDiagnostics(
			String source,
			Pattern featurePattern,
			String featureName,
			int projectMajor,
			String projectVersion) {
		List<Diagnostic> diagnostics = new java.util.ArrayList<>();
		Matcher matcher = featurePattern.matcher(source);
		int[] lineStartOffsets = computeLineStartOffsets(source);
		while (matcher.find()) {
			int startOffset = matcher.start();
			int endOffset = matcher.end();
			Range range = new Range(
					offsetToPosition(startOffset, source, lineStartOffsets),
					offsetToPosition(endOffset, source, lineStartOffsets));

			Diagnostic diagnostic = new Diagnostic();
			diagnostic.setRange(range);
			diagnostic.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Error);
			diagnostic.setSource(VERSION_GUARD_DIAGNOSTIC_SOURCE);
			diagnostic.setMessage(String.format(
					"%s require Groovy 5+, but this project resolves Groovy %s (major %d).",
					featureName,
					projectVersion != null ? projectVersion : Integer.toString(projectMajor),
					projectMajor));
			diagnostics.add(diagnostic);
		}
		return diagnostics;
	}

	private int[] computeLineStartOffsets(String source) {
		java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
		starts.add(0);
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		int[] result = new int[starts.size()];
		for (int i = 0; i < starts.size(); i++) {
			result[i] = starts.get(i);
		}
		return result;
	}

	private Position offsetToPosition(int offset, String source, int[] lineStartOffsets) {
		int safeOffset = Math.max(0, Math.min(offset, source.length()));
		int idx = java.util.Arrays.binarySearch(lineStartOffsets, safeOffset);
		if (idx < 0) {
			idx = -idx - 2;
		}
		if (idx < 0) {
			idx = 0;
		}
		int lineStart = lineStartOffsets[idx];
		int character = Math.max(0, safeOffset - lineStart);
		return new Position(idx, character);
	}

	private void deduplicateDiagnostics(Map<URI, List<Diagnostic>> diagnosticsByFile) {
		for (Map.Entry<URI, List<Diagnostic>> entry : diagnosticsByFile.entrySet()) {
			List<Diagnostic> unique = new java.util.ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (Diagnostic diagnostic : entry.getValue()) {
				String key = diagnostic.getRange() + "|" + diagnostic.getMessage() + "|" + diagnostic.getSeverity();
				if (seen.add(key)) {
					unique.add(diagnostic);
				}
			}
			entry.setValue(unique);
		}
	}

	private static final class GuardedSyntaxFeature {
		private final Pattern pattern;
		private final String featureName;

		private GuardedSyntaxFeature(Pattern pattern, String featureName) {
			this.pattern = pattern;
			this.featureName = featureName;
		}
	}

	private PublishDiagnosticsParams normalizeDiagnosticsForPublishedDocument(PublishDiagnosticsParams params) {
		if (params == null || params.getDiagnostics() == null || params.getDiagnostics().isEmpty()) {
			return params;
		}
		URI uri;
		try {
			uri = URI.create(params.getUri());
		} catch (Exception e) {
			return params;
		}

		String contents = fileContentsTracker.getContents(uri);
		if (contents == null) {
			return params;
		}

		String[] lines = contents.split("\\r?\\n", -1);
		for (Diagnostic diagnostic : params.getDiagnostics()) {
			Range range = diagnostic.getRange();
			if (range == null) {
				diagnostic.setRange(new Range(new Position(0, 0), new Position(0, 0)));
				continue;
			}

			Position start = clampPosition(range.getStart(), lines);
			Position end = clampPosition(range.getEnd(), lines);
			if (isAfter(start, end)) {
				end = new Position(start.getLine(), start.getCharacter());
			}
			range.setStart(start);
			range.setEnd(end);
		}

		return params;
	}

	private Position clampPosition(Position position, String[] lines) {
		if (lines == null || lines.length == 0) {
			return new Position(0, 0);
		}
		if (position == null) {
			return new Position(0, 0);
		}

		int maxLine = lines.length - 1;
		int line = Math.max(0, Math.min(position.getLine(), maxLine));
		int lineLength = lines[line].length();
		int character = Math.max(0, Math.min(position.getCharacter(), lineLength));
		return new Position(line, character);
	}

	private boolean isAfter(Position left, Position right) {
		if (left.getLine() != right.getLine()) {
			return left.getLine() > right.getLine();
		}
		return left.getCharacter() > right.getCharacter();
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
		scope.setFullyCompiled(true);
	}

	/**
	 * Performs a full rebuild for a scope after Java/build-file changes.
	 * <p><b>Caller must hold the scope's write lock.</b></p>
	 */
	public void recompileAfterJavaChange(ProjectScope scope) {
		if (scope.getProjectRoot() != null) {
			com.tomaszrup.groovyls.compiler.SharedClasspathIndexCache.getInstance()
					.invalidateEntriesUnderProject(scope.getProjectRoot());
			com.tomaszrup.groovyls.compiler.SharedClassGraphCache.getInstance()
					.invalidateEntriesUnderProject(scope.getProjectRoot());
		}
		scope.clearClasspathIndexes();
		scope.getCompilationUnitFactory().invalidateFileCache();
		// Use full invalidation (including classloader) so that stale .class
		// files deleted from the build output are not served from the
		// classloader's cache.
		scope.getCompilationUnitFactory().invalidateCompilationUnitFull();
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

	// --- OOM handling ---

	/**
	 * Handles a {@link VirtualMachineError} (typically {@link OutOfMemoryError})
	 * caught during compilation.  This method:
	 * <ol>
	 *   <li>Logs the error</li>
	 *   <li>Attempts {@code System.gc()} to reclaim soft references</li>
	 *   <li>Marks the scope as failed so future compilations are skipped</li>
	 *   <li>Publishes a synthetic diagnostic so the user gets feedback</li>
	 *   <li>Shows a user-visible notification with actionable fix guidance</li>
	 * </ol>
	 */
	private void handleCompilationOOM(ProjectScope scope, VirtualMachineError e, String context) {
		logger.error("{}: {} for scope {} — marking as failed. "
				+ "Consider increasing -Xmx via groovy.java.vmargs setting.",
				context, e.toString(), scope.getProjectRoot());

		// Attempt to reclaim memory (soft references, finalizable objects)
		try {
			System.gc();
		} catch (Throwable ignored) {
			// Best effort
		}

		scope.setCompilationFailed(true);
		scope.setCompiled(true); // prevent retry loops

		// Publish a synthetic diagnostic so the user sees feedback
		publishOOMDiagnostic(scope, e);

		// Show a prominent notification with actionable fix guidance
		showOOMNotification(scope, e);

		logMemoryStats();
	}

	/**
	 * Publishes a synthetic error diagnostic for a scope that failed with OOM,
	 * so the user sees actionable feedback instead of silent failure.
	 */
	private void publishOOMDiagnostic(ProjectScope scope, VirtualMachineError e) {
		LanguageClient client = languageClient;
		if (client == null || scope.getProjectRoot() == null) {
			return;
		}
		try {
			// Create a synthetic diagnostic on the build file
			URI buildFileURI = scope.getProjectRoot().resolve("build.gradle").toUri();
			Diagnostic diag = new Diagnostic();
			diag.setRange(new org.eclipse.lsp4j.Range(
					new Position(0, 0), new Position(0, 0)));
			diag.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Error);
			diag.setSource("groovy-language-server");
			Runtime rt = Runtime.getRuntime();
			long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
			long maxMB = rt.maxMemory() / (1024 * 1024);
			diag.setMessage(String.format(
					"Compilation failed: %s (heap: %dMB/%dMB). "
					+ "Increase heap via groovy.java.vmargs setting, e.g. \"-Xmx2g\".",
					e.getClass().getSimpleName(), usedMB, maxMB));
			PublishDiagnosticsParams params = new PublishDiagnosticsParams(
					buildFileURI.toString(), List.of(diag));
			client.publishDiagnostics(params);
		} catch (Throwable t) {
			// If we're so low on memory we can't even publish diagnostics,
			// just log and move on.
			logger.debug("Failed to publish OOM diagnostic: {}", t.getMessage());
		}
	}

	/**
	 * Logs current JVM memory statistics for diagnostic purposes.
	 */
	private void logMemoryStats() {
		Runtime rt = Runtime.getRuntime();
		long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
		long totalMB = rt.totalMemory() / (1024 * 1024);
		long maxMB = rt.maxMemory() / (1024 * 1024);
		logger.warn("JVM memory: used={}MB, total={}MB, max={}MB", usedMB, totalMB, maxMB);
	}

	/**
	 * Shows a user-visible notification (window/showMessage) with specific
	 * guidance on how to fix OOM errors. This is more prominent than the
	 * synthetic diagnostic and provides an actionable fix.
	 */
	private void showOOMNotification(ProjectScope scope, VirtualMachineError e) {
		LanguageClient client = languageClient;
		if (client == null) {
			return;
		}
		try {
			Runtime rt = Runtime.getRuntime();
			long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
			long maxMB = rt.maxMemory() / (1024 * 1024);
			String projectName = scope.getProjectRoot() != null
					? scope.getProjectRoot().getFileName().toString() : "unknown";
			long suggestedMB = Math.min(maxMB * 2, 4096);
			String message = String.format(
					"Groovy Language Server: OutOfMemoryError while compiling '%s' "
					+ "(heap: %dMB/%dMB). To fix, add to VS Code settings: "
					+ "\"groovy.java.vmargs\": \"-Xmx%dm\"",
					projectName, usedMB, maxMB, suggestedMB);
			client.showMessage(new MessageParams(MessageType.Error, message));
		} catch (Throwable t) {
			logger.debug("Failed to show OOM notification: {}", t.getMessage());
		}
	}
}
