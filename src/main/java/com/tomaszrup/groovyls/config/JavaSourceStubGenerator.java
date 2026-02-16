/// /////////////////////////////////////////////////////////////////////////////
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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.control.SourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Generates synthetic Groovy source stubs for Java source files so that
 * Groovy imports resolve without waiting for Gradle/Maven compilation.
 *
 * <p>This is a package-private helper extracted from
 * {@link CompilationUnitFactory} to keep the factory under 1000 lines.</p>
 */
class JavaSourceStubGenerator {

	private static final Logger logger = LoggerFactory.getLogger(JavaSourceStubGenerator.class);

	private static final String FILE_EXTENSION_JAVA = ".java";
	private static final String PACKAGE_PREFIX = "package ";

	/**
	 * Prefix used to identify synthetic Java source stub SourceUnits in the
	 * compilation unit.
	 */
	private static final String JAVA_STUB_NAME_PREFIX = "[java-stub] ";

	/**
	 * Standard Gradle/Maven Java source directory names relative to a project
	 * root.
	 */
	private static final String[][] JAVA_SOURCE_DIR_PATTERNS = {
			{"src", "main", "java"},
			{"src", "test", "java"},
	};

	private final Path projectRoot;

	JavaSourceStubGenerator(Path projectRoot) {
		this.projectRoot = projectRoot;
	}

	// ----------------------------------------------------------------
	// Public entry point
	// ----------------------------------------------------------------

	/**
	 * Adds synthetic Groovy source stubs for Java source files whose compiled
	 * {@code .class} files are not yet on the classpath.
	 *
	 * <p>Stubs are rebuilt from disk on every call: previous stubs are removed
	 * and fresh ones are added based on the current Java source directory
	 * state.</p>
	 */
	void addJavaSourceStubs(Path workspaceRoot, GroovyLSCompilationUnit compilationUnit,
			List<String> resolvedClasspathCache) {
		Path effectiveRoot = projectRoot != null ? projectRoot : workspaceRoot;
		if (effectiveRoot == null) {
			return;
		}

		int removedStubs = removeExistingJavaStubs(compilationUnit);
		logger.trace("javaStubTrace projectRoot={} removedPreviousStubs={}", effectiveRoot, removedStubs);

		Map<String, Path> javaIndex = scanJavaSources(effectiveRoot);
		if (javaIndex.isEmpty()) {
			logger.trace("javaStubTrace projectRoot={} javaSourceCount=0", effectiveRoot);
			return;
		}
		logger.trace("javaStubTrace projectRoot={} javaSourceCount={}", effectiveRoot, javaIndex.size());
		logJavaStubIndexSummary(effectiveRoot, javaIndex);

		int added = addMissingJavaStubs(javaIndex, compilationUnit, resolvedClasspathCache);

		if (added > 0) {
			logger.debug("Added {} Java source stub(s) to compilation unit", added);
		}
	}

	// ----------------------------------------------------------------
	// Stub management
	// ----------------------------------------------------------------

	private int removeExistingJavaStubs(GroovyLSCompilationUnit compilationUnit) {
		List<SourceUnit> stubsToRemove = new ArrayList<>();
		compilationUnit.iterator().forEachRemaining(su -> {
			if (su.getName() != null && su.getName().startsWith(JAVA_STUB_NAME_PREFIX)) {
				stubsToRemove.add(su);
			}
		});
		if (!stubsToRemove.isEmpty()) {
			compilationUnit.removeSources(stubsToRemove);
		}
		return stubsToRemove.size();
	}

	private void logJavaStubIndexSummary(Path effectiveRoot, Map<String, Path> javaIndex) {
		if (!logger.isDebugEnabled()) {
			return;
		}
		List<String> discovered = new ArrayList<>(javaIndex.keySet());
		Collections.sort(discovered);
		int limit = Math.min(discovered.size(), 12);
		List<String> sample = discovered.subList(0, limit);
		logger.debug("javaStubSummary projectRoot={} javaSourceCount={} sample={}",
				effectiveRoot, discovered.size(), sample);
	}

	private int addMissingJavaStubs(Map<String, Path> javaIndex, GroovyLSCompilationUnit compilationUnit,
			List<String> resolvedClasspathCache) {
		int added = 0;
		for (Map.Entry<String, Path> entry : javaIndex.entrySet()) {
			String fqcn = entry.getKey();
			Path javaSourcePath = entry.getValue();
			File classFileOnClasspath = findClassFileOnClasspath(fqcn, resolvedClasspathCache);
			if (classFileOnClasspath == null) {
				addJavaStubSource(fqcn, javaSourcePath, compilationUnit);
				added++;
			} else {
				logger.trace("javaStubTrace skip fqcn={} source={} reason=classOnClasspath classFile={}",
						fqcn, javaSourcePath, classFileOnClasspath);
			}
		}
		return added;
	}

	private void addJavaStubSource(String fqcn, Path javaSourcePath, GroovyLSCompilationUnit compilationUnit) {
		String pkg = "";
		String simpleName = fqcn;
		int lastDot = fqcn.lastIndexOf('.');
		if (lastDot >= 0) {
			pkg = fqcn.substring(0, lastDot);
			simpleName = fqcn.substring(lastDot + 1);
		}
		StringBuilder source = new StringBuilder();
		if (!pkg.isEmpty()) {
			source.append(PACKAGE_PREFIX).append(pkg).append("\n");
		}
		source.append("class ").append(simpleName).append(" {}\n");

		URI stubUri = javaSourcePath.toUri();
		SourceUnit su = new SourceUnit(
				JAVA_STUB_NAME_PREFIX + fqcn,
				new StringReaderSourceWithURI(source.toString(), stubUri,
						compilationUnit.getConfiguration()),
				compilationUnit.getConfiguration(),
				compilationUnit.getClassLoader(),
				compilationUnit.getErrorCollector());
		compilationUnit.addSource(su);
		logger.trace("javaStubTrace add fqcn={} source={} stubUri={}", fqcn, javaSourcePath, stubUri);
	}

	// ----------------------------------------------------------------
	// Classpath checking
	// ----------------------------------------------------------------

	private File findClassFileOnClasspath(String fqcn, List<String> resolvedClasspathCache) {
		List<String> entries = resolvedClasspathCache;
		if (entries == null || entries.isEmpty()) {
			logger.debug("javaStubTrace classpathCheck fqcn={} entries=0", fqcn);
			return null;
		}
		String classFileRelative = fqcn.replace('.', File.separatorChar) + ".class";
		for (String entry : entries) {
			File entryFile = new File(entry);
			if (entryFile.isDirectory()) {
				File classFile = new File(entryFile, classFileRelative);
				if (classFile.isFile()) {
					return classFile;
				}
			}
		}
		return null;
	}

	// ----------------------------------------------------------------
	// Java source scanning
	// ----------------------------------------------------------------

	private Map<String, Path> scanJavaSources(Path root) {
		Map<String, Path> index = new HashMap<>();
		for (String[] pattern : JAVA_SOURCE_DIR_PATTERNS) {
			Path sourceDir = resolveSourceDir(root, pattern);
			if (Files.isDirectory(sourceDir)) {
				scanJavaSourceDirectory(index, sourceDir);
			}
		}
		return index;
	}

	private Path resolveSourceDir(Path root, String[] pattern) {
		Path sourceDir = root;
		for (String segment : pattern) {
			sourceDir = sourceDir.resolve(segment);
		}
		return sourceDir;
	}

	private void scanJavaSourceDirectory(Map<String, Path> index, Path sourceDir) {
		final Path finalSourceDir = sourceDir;
		try {
			Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (isJavaSourceFile(file)) {
						indexJavaSourceFile(index, finalSourceDir, file);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			logger.debug("Could not scan Java source dir {}: {}", sourceDir, e.getMessage());
		}
	}

	private boolean isJavaSourceFile(Path file) {
		return file.getFileName().toString().endsWith(FILE_EXTENSION_JAVA);
	}

	private void indexJavaSourceFile(Map<String, Path> index, Path sourceDir, Path file) {
		Path relative = sourceDir.relativize(file);
		String pathFqcn = javaPathToFqcn(relative);
		String declaredFqcn = javaFileToDeclaredFqcn(file);
		if (isPathPackageMismatch(pathFqcn, declaredFqcn)) {
			indexPathPackageMismatch(index, file, pathFqcn, declaredFqcn);
			return;
		}
		String fqcn = declaredFqcn != null ? declaredFqcn : pathFqcn;
		putJavaIndexEntry(index, fqcn, file);
	}

	private boolean isPathPackageMismatch(String pathFqcn, String declaredFqcn) {
		return pathFqcn != null && declaredFqcn != null && !pathFqcn.equals(declaredFqcn);
	}

	private void indexPathPackageMismatch(Map<String, Path> index,
			Path file, String pathFqcn, String declaredFqcn) {
		logger.info("javaStubPathPackageMismatch file={} pathFqcn={} declaredFqcn={}",
				file, pathFqcn, declaredFqcn);
		putJavaIndexEntry(index, pathFqcn, file);
		putJavaIndexEntry(index, declaredFqcn, file);
		logger.info("javaStubTransitionAliases file={} aliases=[{}, {}]",
				file, pathFqcn, declaredFqcn);
	}

	private void putJavaIndexEntry(Map<String, Path> index, String fqcn, Path file) {
		if (fqcn == null || fqcn.isEmpty()) {
			return;
		}
		Path previous = index.put(fqcn, file);
		if (previous != null && !previous.equals(file)) {
			logger.warn("javaStubDuplicateFqcn fqcn={} first={} second={}", fqcn, previous, file);
		}
	}

	private String javaFileToDeclaredFqcn(Path javaFile) {
		String fileName = javaFile.getFileName().toString();
		if (!fileName.endsWith(FILE_EXTENSION_JAVA)) {
			return null;
		}
		String simpleName = fileName.substring(0, fileName.length() - FILE_EXTENSION_JAVA.length());
		String pkg = extractJavaPackage(javaFile);
		if (pkg == null || pkg.isEmpty()) {
			return simpleName;
		}
		return pkg + "." + simpleName;
	}

	private String extractJavaPackage(Path javaFile) {
		try {
			for (String line : Files.readAllLines(javaFile)) {
				String trimmed = line.trim();
				boolean isCommentOrEmpty = trimmed.isEmpty() || trimmed.startsWith("//")
						|| trimmed.startsWith("/*") || trimmed.startsWith("*");
				if (!isCommentOrEmpty) {
					if (trimmed.startsWith(PACKAGE_PREFIX)) {
						String tail = trimmed.substring(PACKAGE_PREFIX.length()).trim();
						if (tail.endsWith(";")) {
							tail = tail.substring(0, tail.length() - 1).trim();
						}
						return tail;
					}
					break;
				}
			}
		} catch (IOException e) {
			logger.debug("Could not read Java source file for package extraction {}: {}",
					javaFile, e.getMessage());
		}
		return "";
	}

	/**
	 * Converts a path relative to a Java source root to a fully-qualified
	 * class name. E.g. {@code com/example/Frame.java} &rarr;
	 * {@code com.example.Frame}.
	 */
	static String javaPathToFqcn(Path relativePath) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < relativePath.getNameCount(); i++) {
			String segment = relativePath.getName(i).toString();
			if (i == relativePath.getNameCount() - 1) {
				if (!segment.endsWith(FILE_EXTENSION_JAVA)) {
					return null;
				}
				segment = segment.substring(0, segment.length() - FILE_EXTENSION_JAVA.length());
			}
			if (segment.isEmpty()) {
				return null;
			}
			if (sb.length() > 0) {
				sb.append('.');
			}
			sb.append(segment);
		}
		return sb.toString();
	}
}
