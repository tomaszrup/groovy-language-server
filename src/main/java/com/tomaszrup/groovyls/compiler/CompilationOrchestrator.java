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
package com.tomaszrup.groovyls.compiler;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.eclipse.lsp4j.Position;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.lsp.utils.Positions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates compilation, AST visiting, ClassGraph scanning, and placeholder
 * injection. Extracted from {@code GroovyServices} to reduce class size and
 * improve separation of concerns.
 *
 * <p>All methods in this class assume that proper locking is already handled
 * by the caller (typically {@code GroovyServices} with its {@code stateLock}).
 */
public class CompilationOrchestrator {
	private static final Logger logger = LoggerFactory.getLogger(CompilationOrchestrator.class);
	private static final Pattern PATTERN_CONSTRUCTOR_CALL = Pattern.compile(".*new \\w*$");
	private static final Set<String> REPORTED_GROOVY_BUG_KEYS = ConcurrentHashMap.newKeySet();
	private static final String KNOWN_HARMLESS_GROOVY_BUG_PREFIX = "Known Groovy compiler bug during";
	private static final String MISSING_OR_INCOMPATIBLE_DEPENDENCY_MSG =
			"(a dependency may be missing or incompatible): {}";

	/**
	 * Process-wide shared cache for ClassGraph scan results. Scopes with
	 * identical classpaths (common in Gradle multi-project builds) share a
	 * single {@code ScanResult}, saving 50–200 MB of heap per duplicate.
	 */
	private final SharedClassGraphCache sharedScanCache;

	public CompilationOrchestrator() {
		this(SharedClassGraphCache.getInstance());
	}

	/**
	 * Constructor accepting a custom cache — primarily for testing.
	 */
	public CompilationOrchestrator(SharedClassGraphCache sharedScanCache) {
		this.sharedScanCache = sharedScanCache;
	}

	/**
	 * Creates or updates the compilation unit for the given scope.
	 *
	 * @return a {@link CompilationResult} containing the new compilation unit,
	 *         classloader, scan result, and whether the unit was reused
	 */
	public CompilationResult createOrUpdateCompilationUnit(
			GroovyLSCompilationUnit existingUnit,
			Path projectRoot,
			com.tomaszrup.groovyls.config.ICompilationUnitFactory compilationUnitFactory,
			FileContentsTracker fileContentsTracker,
			ScanResult existingScanResult,
			GroovyClassLoader existingClassLoader) {
		return createOrUpdateCompilationUnit(existingUnit, projectRoot,
				compilationUnitFactory, fileContentsTracker,
				existingScanResult, existingClassLoader, Collections.emptySet());
	}

	/**
	 * Creates or updates the compilation unit for the given scope, with
	 * additional URIs to force-invalidate (dependency-driven recompilation).
	 *
	 * @param additionalInvalidations  URIs of dependent files to force-invalidate
	 *                                  even if their contents have not changed
	 * @return a {@link CompilationResult} containing the new compilation unit,
	 *         classloader, scan result, and whether the unit was reused
	 */
	public CompilationResult createOrUpdateCompilationUnit(
			GroovyLSCompilationUnit existingUnit,
			Path projectRoot,
			com.tomaszrup.groovyls.config.ICompilationUnitFactory compilationUnitFactory,
			FileContentsTracker fileContentsTracker,
			ScanResult existingScanResult,
			GroovyClassLoader existingClassLoader,
			Set<URI> additionalInvalidations) {

		if (existingUnit != null) {
			File targetDirectory = existingUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && targetDirectory.exists()) {
				try (Stream<Path> walk = Files.walk(targetDirectory.toPath())) {
					walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
				} catch (IOException e) {
					logger.error("Failed to delete target directory: {}", targetDirectory.getAbsolutePath(), e);
					return new CompilationResult(null, existingClassLoader, existingScanResult, false);
				}
			}
		}

		GroovyLSCompilationUnit newUnit = compilationUnitFactory.create(
				projectRoot, fileContentsTracker, additionalInvalidations);

		if (newUnit != null) {
			File targetDirectory = newUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
				logger.error("Failed to create target directory: {}", targetDirectory.getAbsolutePath());
			}
			// ClassGraph scan is deferred — not needed for compilation or
			// diagnostics. It will be triggered lazily via
			// ProjectScope.ensureClassGraphScanned() when a provider
			// (completion, code action) first needs it.
			return new CompilationResult(newUnit, newUnit.getClassLoader(),
					existingScanResult, newUnit == existingUnit);
		} else {
			if (existingScanResult != null) {
				sharedScanCache.release(existingScanResult);
			}
			return new CompilationResult(null, existingClassLoader, null, false);
		}
	}

	/**
	 * Visits the entire AST for the given compilation unit.
	 */
	public ASTNodeVisitor visitAST(GroovyLSCompilationUnit compilationUnit) {
		if (compilationUnit == null) {
			return null;
		}
		ASTNodeVisitor astVisitor = new ASTNodeVisitor();
		try {
			astVisitor.visitCompilationUnit(compilationUnit);
		} catch (Exception e) {
			logger.warn("Exception during AST visit: {}", e.getMessage());
			logger.debug("AST visit exception details", e);
		} catch (LinkageError e) {
			logger.warn("Classpath linkage error during AST visit "
					+ MISSING_OR_INCOMPATIBLE_DEPENDENCY_MSG, e.toString());
			logger.debug("AST visit LinkageError details", e);
		}
		return astVisitor;
	}

	/**
	 * Incrementally visits only the given URIs in the AST, producing a
	 * <b>new</b> {@code ASTNodeVisitor} via copy-on-write. The existing
	 * visitor is not mutated, so concurrent readers using the old reference
	 * remain safe (stale-AST reads).
	 *
	 * <p>Falls back to a full visit if the existing visitor is null.</p>
	 *
	 * @return a new visitor containing updated data for {@code uris} and
	 *         unchanged data for everything else
	 */
	public ASTNodeVisitor visitAST(GroovyLSCompilationUnit compilationUnit,
			ASTNodeVisitor existingVisitor, Set<URI> uris) {
		if (existingVisitor == null) {
			return visitAST(compilationUnit);
		}
		if (compilationUnit == null) {
			return existingVisitor;
		}
		// Create a snapshot that excludes the URIs about to be re-visited
		ASTNodeVisitor newVisitor = existingVisitor.createSnapshotExcluding(uris);
		try {
			newVisitor.visitCompilationUnit(compilationUnit, uris);
		} catch (Exception e) {
			logger.warn("Exception during incremental AST visit: {}", e.getMessage());
			logger.debug("Incremental AST visit exception details", e);
		} catch (LinkageError e) {
			logger.warn("Classpath linkage error during incremental AST visit "
					+ MISSING_OR_INCOMPATIBLE_DEPENDENCY_MSG, e.toString());
			logger.debug("Incremental AST visit LinkageError details", e);
		}
		return newVisitor;
	}

	/**
	 * Compiles the given compilation unit to the CANONICALIZATION phase.
	 *
	 * @return the error collector from compilation
	 */
	public ErrorCollector compile(GroovyLSCompilationUnit compilationUnit, Path projectRoot) {
		if (compilationUnit == null) {
			logger.warn("compile() called but compilationUnit is null for scope {}", projectRoot);
			return null;
		}
		logger.debug("Compiling scope: {}, classpath entries: {}", projectRoot,
				compilationUnit.getConfiguration().getClasspath().size());
		try {
			compilationUnit.compile(Phases.CANONICALIZATION);
		} catch (CompilationFailedException e) {
			logger.debug("Compilation failed (expected for incomplete code) for scope {}: {}", projectRoot,
					e.getMessage());
		} catch (GroovyBugError e) {
			if (isKnownHarmlessTraitComposerBug(e)) {
				String key = projectRoot + "|" + e.getMessage();
				if (REPORTED_GROOVY_BUG_KEYS.add(key)) {
					logger.debug(
							"{} compilation for scope {} (suppressing stack trace; benign for language features): {}",
							KNOWN_HARMLESS_GROOVY_BUG_PREFIX,
							projectRoot, e.getMessage());
				}
			} else {
				logger.debug(
						"Groovy compiler bug during compilation for scope {} (this is usually harmless for code intelligence): {}",
						projectRoot, e.getMessage());
				logger.debug("GroovyBugError details", e);
			}
		} catch (Exception e) {
			logger.warn("Unexpected exception during compilation for scope {}: {}",
					projectRoot, e.getMessage());
			logger.debug("Compilation exception details", e);
		} catch (LinkageError e) {
			// NoClassDefFoundError, UnsatisfiedLinkError, etc. — a class from
			// the project's classpath could not be loaded.  Log and continue
			// so this doesn't propagate to LSP4J's listener thread and kill
			// the server (EPIPE).
			logger.warn("Classpath linkage error during compilation for scope {} "
					+ MISSING_OR_INCOMPATIBLE_DEPENDENCY_MSG,
					projectRoot, e.toString());
			logger.debug("LinkageError details", e);
		}
		return compilationUnit.getErrorCollector();
	}

	/**
	 * Compiles an incremental (lightweight) compilation unit to the
	 * CANONICALIZATION phase. Unlike {@link #compile}, this does not manage
	 * the target directory since the incremental unit is temporary.
	 *
	 * @param incrementalUnit the incremental compilation unit containing
	 *                        only the changed files and their dependencies
	 * @param projectRoot     the project root path (for logging)
	 * @return the error collector, or {@code null} if the unit is null
	 */
	public ErrorCollector compileIncremental(GroovyLSCompilationUnit incrementalUnit, Path projectRoot) {
		if (incrementalUnit == null) {
			logger.warn("compileIncremental() called but incrementalUnit is null for scope {}", projectRoot);
			return null;
		}
		int sourceCount = 0;
		var iter = incrementalUnit.iterator();
		while (iter.hasNext()) {
			iter.next();
			sourceCount++;
		}
		logger.debug("Incremental compile for scope: {}, {} sources", projectRoot, sourceCount);
		try {
			incrementalUnit.compile(Phases.CANONICALIZATION);
		} catch (CompilationFailedException e) {
			logger.debug("Incremental compilation failed for {}: {}", projectRoot, e.getMessage());
		} catch (GroovyBugError e) {
			if (isKnownHarmlessTraitComposerBug(e)) {
				String key = projectRoot + "|incremental|" + e.getMessage();
				if (REPORTED_GROOVY_BUG_KEYS.add(key)) {
					logger.debug(
							"{} incremental compile for {} (suppressing stack trace; benign for language features): {}",
							KNOWN_HARMLESS_GROOVY_BUG_PREFIX,
							projectRoot, e.getMessage());
				}
			} else {
				logger.debug("Groovy compiler bug during incremental compile for {}: {}", projectRoot, e.getMessage());
				logger.debug("GroovyBugError details", e);
			}
		} catch (Exception e) {
			logger.warn("Unexpected exception during incremental compile for {}: {}", projectRoot, e.getMessage());
		} catch (LinkageError e) {
			logger.warn("Classpath linkage error during incremental compile for {} "
					+ MISSING_OR_INCOMPATIBLE_DEPENDENCY_MSG,
					projectRoot, e.toString());
			logger.debug("LinkageError details", e);
		}
		return incrementalUnit.getErrorCollector();
	}

	private static boolean isKnownHarmlessTraitComposerBug(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof NullPointerException && current.getMessage() != null
					&& current.getMessage().contains("helperClassNode")) {
				return true;
			}
			StackTraceElement[] stack = current.getStackTrace();
			if (stack == null) {
				continue;
			}
			for (StackTraceElement element : stack) {
				if ("org.codehaus.groovy.transform.trait.TraitComposer".equals(element.getClassName())
						&& "applyTrait".equals(element.getMethodName())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Injects a placeholder into the document source to force AST node creation
	 * at the cursor position for completion. The placeholder text depends on
	 * context: {@code "a()"} for constructor calls, {@code "a"} otherwise.
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	public String injectCompletionPlaceholder(FileContentsTracker fileContentsTracker,
			URI uri, Position position) {
		String originalSource = fileContentsTracker.getContents(uri);
		if (originalSource == null) {
			return null;
		}
		int offset = Positions.getOffset(originalSource, position);
		if (offset < 0 || offset > originalSource.length()) {
			logger.debug("completion: offset {} out of bounds for source length {}", offset,
					originalSource.length());
			return null;
		}

		String lineBeforeOffset = originalSource.substring(
				Math.max(0, offset - position.getCharacter()), offset);
		Matcher matcher = PATTERN_CONSTRUCTOR_CALL.matcher(lineBeforeOffset);
		String placeholder = matcher.matches() ? "a()" : "a";

		String modifiedSource = originalSource.substring(0, offset) + placeholder
				+ originalSource.substring(offset);
		fileContentsTracker.setContents(uri, modifiedSource);
		fileContentsTracker.forceChanged(uri);
		return originalSource;
	}

	/**
	 * Injects a closing parenthesis placeholder into the document source to force
	 * {@code ArgumentListExpression} creation in the AST for signature help.
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	public String injectSignatureHelpPlaceholder(FileContentsTracker fileContentsTracker,
			URI uri, Position position) {
		String originalSource = fileContentsTracker.getContents(uri);
		if (originalSource == null) {
			return null;
		}
		int offset = Positions.getOffset(originalSource, position);
		if (offset < 0 || offset > originalSource.length()) {
			logger.debug("signatureHelp: offset {} out of bounds for source length {}", offset,
					originalSource.length());
			return null;
		}

		String modifiedSource = originalSource.substring(0, offset) + ")"
				+ originalSource.substring(offset);
		fileContentsTracker.setContents(uri, modifiedSource);
		fileContentsTracker.forceChanged(uri);
		return originalSource;
	}

	/**
	 * Restores the original document source after placeholder injection.
	 */
	public void restoreDocumentSource(FileContentsTracker fileContentsTracker,
			URI uri, String originalSource) {
		fileContentsTracker.setContents(uri, originalSource);
		fileContentsTracker.forceChanged(uri);
	}
}
