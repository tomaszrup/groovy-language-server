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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.eclipse.lsp4j.Position;

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassGraphException;
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

	/**
	 * Holds the cached ClassGraph scan result. When the classloader changes,
	 * this is updated lazily.
	 */
	private ScanResult cachedScanResult;
	private GroovyClassLoader cachedClassLoader;

	/**
	 * Creates or updates the compilation unit for the given scope.
	 *
	 * @return {@code true} if the compilation unit is the same object as before
	 *         (i.e. it was reused rather than recreated)
	 */
	public boolean createOrUpdateCompilationUnit(
			GroovyLSCompilationUnit[] compilationUnitHolder,
			ASTNodeVisitor[] astVisitorHolder,
			Path projectRoot,
			com.tomaszrup.groovyls.config.ICompilationUnitFactory compilationUnitFactory,
			FileContentsTracker fileContentsTracker,
			ScanResult[] scanResultHolder,
			GroovyClassLoader[] classLoaderHolder) {

		GroovyLSCompilationUnit compilationUnit = compilationUnitHolder[0];
		if (compilationUnit != null) {
			File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && targetDirectory.exists()) {
				try {
					Files.walk(targetDirectory.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile)
							.forEach(File::delete);
				} catch (IOException e) {
					logger.error("Failed to delete target directory: {}", targetDirectory.getAbsolutePath(), e);
					compilationUnitHolder[0] = null;
					return false;
				}
			}
		}

		GroovyLSCompilationUnit oldCompilationUnit = compilationUnit;
		compilationUnit = compilationUnitFactory.create(projectRoot, fileContentsTracker);
		compilationUnitHolder[0] = compilationUnit;

		if (compilationUnit != null) {
			File targetDirectory = compilationUnit.getConfiguration().getTargetDirectory();
			if (targetDirectory != null && !targetDirectory.exists() && !targetDirectory.mkdirs()) {
				logger.error("Failed to create target directory: {}", targetDirectory.getAbsolutePath());
			}
			GroovyClassLoader newClassLoader = compilationUnit.getClassLoader();
			updateClassGraphScan(newClassLoader, scanResultHolder, classLoaderHolder);
		} else {
			if (scanResultHolder[0] != null) {
				scanResultHolder[0].close();
			}
			scanResultHolder[0] = null;
		}

		return compilationUnit != null && compilationUnit.equals(oldCompilationUnit);
	}

	/**
	 * Updates the ClassGraph scan result if the classloader has changed.
	 * Caches the scan result to avoid expensive rescanning when the classloader
	 * is the same object.
	 */
	private void updateClassGraphScan(GroovyClassLoader newClassLoader,
			ScanResult[] scanResultHolder, GroovyClassLoader[] classLoaderHolder) {
		if (newClassLoader.equals(classLoaderHolder[0])) {
			return;
		}
		classLoaderHolder[0] = newClassLoader;

		// Check if we already have a cached scan for this exact classloader instance
		if (newClassLoader == cachedClassLoader && cachedScanResult != null) {
			ScanResult oldScanResult = scanResultHolder[0];
			scanResultHolder[0] = cachedScanResult;
			if (oldScanResult != null && oldScanResult != cachedScanResult) {
				oldScanResult.close();
			}
			return;
		}

		ScanResult oldScanResult = scanResultHolder[0];
		try {
			ScanResult newResult = new ClassGraph().overrideClassLoaders(newClassLoader)
					.enableClassInfo()
					.enableSystemJarsAndModules()
					.scan();
			scanResultHolder[0] = newResult;
			cachedScanResult = newResult;
			cachedClassLoader = newClassLoader;
		} catch (ClassGraphException e) {
			scanResultHolder[0] = null;
			cachedScanResult = null;
			cachedClassLoader = null;
		} finally {
			if (oldScanResult != null && oldScanResult != scanResultHolder[0]) {
				oldScanResult.close();
			}
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
		astVisitor.visitCompilationUnit(compilationUnit);
		return astVisitor;
	}

	/**
	 * Incrementally visits only the given URIs in the AST.
	 * Falls back to a full visit if the existing visitor is null.
	 */
	public ASTNodeVisitor visitAST(GroovyLSCompilationUnit compilationUnit,
			ASTNodeVisitor existingVisitor, Set<URI> uris) {
		if (existingVisitor == null) {
			return visitAST(compilationUnit);
		}
		if (compilationUnit == null) {
			return existingVisitor;
		}
		existingVisitor.visitCompilationUnit(compilationUnit, uris);
		return existingVisitor;
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
		logger.info("Compiling scope: {}, classpath entries: {}", projectRoot,
				compilationUnit.getConfiguration().getClasspath().size());
		try {
			compilationUnit.compile(Phases.CANONICALIZATION);
		} catch (CompilationFailedException e) {
			logger.info("Compilation failed (expected for incomplete code) for scope {}: {}", projectRoot,
					e.getMessage());
		} catch (GroovyBugError e) {
			logger.warn(
					"Groovy compiler bug during compilation for scope {} (this is usually harmless for code intelligence): {}",
					projectRoot, e.getMessage());
			logger.debug("GroovyBugError details", e);
		} catch (Exception e) {
			logger.warn("Unexpected exception during compilation for scope {}: {}",
					projectRoot, e.getMessage());
			logger.debug("Compilation exception details", e);
		}
		return compilationUnit.getErrorCollector();
	}

	/**
	 * Injects a placeholder into the document source to force AST node creation
	 * at the cursor position for completion. The placeholder text depends on
	 * context: {@code "a()"} for constructor calls, {@code "a"} otherwise.
	 *
	 * @return the original source text before injection, or {@code null} if no
	 *         injection was performed
	 */
	public String injectCompletionPlaceholder(ASTNodeVisitor astVisitor,
			FileContentsTracker fileContentsTracker, URI uri, Position position) {
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
