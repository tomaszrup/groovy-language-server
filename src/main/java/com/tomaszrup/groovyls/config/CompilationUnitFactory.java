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
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyClassLoader;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.compiler.control.io.StringReaderSourceWithURI;
import com.tomaszrup.groovyls.util.FileContentsTracker;

public class CompilationUnitFactory implements ICompilationUnitFactory {
	private static final Logger logger = LoggerFactory.getLogger(CompilationUnitFactory.class);
	private static final String FILE_EXTENSION_GROOVY = ".groovy";

	/**
	 * Maximum number of source files to include in a full compilation unit.
	 * When a project has more .groovy files than this, only open files and
	 * files in the same packages as open files are compiled, plus files up
	 * to this limit. This bounds peak AST memory and prevents OOM in very
	 * large projects.
	 */
	private static final int MAX_FULL_COMPILATION_FILES = 500;

	/**
	 * Directory names to exclude when walking the workspace for source files.
	 * These are typically build output directories that may contain copies of
	 * source files, which would cause duplicate class definition errors.
	 */
	private static final Set<String> EXCLUDED_DIR_NAMES = new HashSet<>(Arrays.asList(
			"bin", "build", "out", ".gradle", ".settings", ".metadata"));

	/**
	 * Build-file names used to detect separate sub-projects during the file
	 * walk.  When a directory (other than the walk root) contains one of these
	 * files AND has JVM source directories, it is treated as an independent
	 * project and its subtree is skipped.  This prevents duplicate-class
	 * errors when multiple sibling projects live under a common workspace root.
	 */
	private static final Set<String> BUILD_FILE_NAMES = Set.of(
			"build.gradle", "build.gradle.kts", "pom.xml");

	private GroovyLSCompilationUnit compilationUnit;
	private CompilerConfiguration config;
	private GroovyClassLoader classLoader;
	private List<String> additionalClasspathList;

	/**
	 * Classpath entries that are only needed for test compilation (Spock,
	 * JUnit, Mockito, etc.). Separated from the main classpath so that
	 * the ClassGraph scan can optionally exclude them, reducing memory
	 * usage by 20-40% for projects with heavy test dependencies.
	 * <p>These entries are <em>not</em> in {@link #additionalClasspathList}
	 * — they are stored separately and merged at compilation time.</p>
	 */
	private List<String> testOnlyClasspathList;

	private List<Path> excludedSubRoots = new ArrayList<>();

	/**
	 * Cached result of resolving {@link #additionalClasspathList} entries
	 * against the filesystem (existence checks, directory expansion, etc.).
	 * Invalidated when the classpath list itself changes.
	 */
	private List<String> resolvedClasspathCache = null;

	/**
	 * Cached set of .groovy file paths discovered by walking the workspace.
	 * Populated on first compilation and reused on subsequent compilations to
	 * avoid expensive {@code Files.walk()} on every keystroke. Invalidated
	 * explicitly when filesystem changes are detected (didChangeWatchedFiles).
	 */
	private Set<Path> cachedGroovyFiles = null;

	/**
	 * Project root used to discover Java source files and generate
	 * synthetic stub classes so that imports resolve without waiting
	 * for Gradle/Maven compilation.
	 */
	private JavaSourceStubGenerator javaStubGenerator;

	public CompilationUnitFactory() {
		// Default constructor for production wiring and tests.
	}

	/**
	 * Set the project root for Java source-aware class resolution.
	 * Must be called before the first {@link #create} call.
	 */
	public void setProjectRoot(Path projectRoot) {
		this.javaStubGenerator = projectRoot != null ? new JavaSourceStubGenerator(projectRoot) : null;
	}

	public void setExcludedSubRoots(List<Path> excludedSubRoots) {
		this.excludedSubRoots = excludedSubRoots != null ? excludedSubRoots : new ArrayList<>();
		logger.debug("Set excludedSubRoots: {}", this.excludedSubRoots);
	}

	@Override
	public List<String> getAdditionalClasspathList() {
		return additionalClasspathList;
	}

	/**
	 * Returns the combined classpath (main + test-only). This is what
	 * the Groovy compiler needs for full compilation including test files.
	 */
	@Override
	public List<String> getCombinedClasspathList() {
		if (testOnlyClasspathList == null || testOnlyClasspathList.isEmpty()) {
			return additionalClasspathList;
		}
		if (additionalClasspathList == null) {
			return testOnlyClasspathList;
		}
		List<String> combined = new ArrayList<>(additionalClasspathList);
		combined.addAll(testOnlyClasspathList);
		return combined;
	}

	@Override
	public List<String> getTestOnlyClasspathList() {
		return testOnlyClasspathList;
	}

	/**
	 * Set test-only classpath entries separately from the main classpath.
	 * These are merged with the main classpath for compilation but can be
	 * excluded from the ClassGraph scan for memory savings.
	 */
	@Override
	public void setTestOnlyClasspathList(List<String> testOnlyClasspathList) {
		this.testOnlyClasspathList = testOnlyClasspathList;
		logger.debug("Set testOnlyClasspathList ({} entries)",
				testOnlyClasspathList != null ? testOnlyClasspathList.size() : 0);
		// Test classpath change requires full reset same as main classpath
		resolvedClasspathCache = null;
		compilationUnit = null;
		config = null;
		if (classLoader != null) {
			try {
				classLoader.close();
			} catch (IOException e) {
				logger.debug("Failed to close old GroovyClassLoader", e);
			}
		}
		classLoader = null;
	}

	@Override
	public void setAdditionalClasspathList(List<String> additionalClasspathList) {
		this.additionalClasspathList = additionalClasspathList;
		logger.debug("Set additionalClasspathList ({} entries)", additionalClasspathList != null ? additionalClasspathList.size() : 0);
		if (additionalClasspathList != null) {
			for (String cp : additionalClasspathList) {
				if (cp.toLowerCase().contains("spock")) {
					logger.debug("  [SPOCK] classpath entry: {}", cp);
				}
			}
		}
		// Classpath changed — full reset of config, classloader and compilation unit
		resolvedClasspathCache = null;
		compilationUnit = null;
		config = null;
		if (classLoader != null) {
			try {
				classLoader.close();
			} catch (IOException e) {
				logger.debug("Failed to close old GroovyClassLoader", e);
			}
		}
		classLoader = null;
	}

	/**
	 * Invalidate only the compilation unit so that a fresh one is created on
	 * the next {@link #create} call. The {@link CompilerConfiguration} and
	 * {@link GroovyClassLoader} are <em>preserved</em> because the classpath
	 * has not changed — only the compiled output has (e.g. after a Java
	 * recompile). This avoids expensive per-entry filesystem stat calls in
	 * {@link #getClasspathList} on every invalidation cycle.
	 */
	@Override
	public void invalidateCompilationUnit() {
		compilationUnit = null;
	}

	/**
	 * Fully invalidates the compilation unit <em>and</em> the class loader,
	 * so that the next {@link #create} call builds a fresh
	 * {@link GroovyClassLoader} from scratch.  Use this when the
	 * <em>contents</em> of classpath directories change in a way that the
	 * classloader cache cannot reflect (e.g. source files deleted whose
	 * compiled {@code .class} artefacts have been cleaned from disk).
	 */
	@Override
	public void invalidateCompilationUnitFull() {
		compilationUnit = null;
		config = null;
		if (classLoader != null) {
			try {
				classLoader.close();
			} catch (IOException e) {
				logger.debug("Failed to close old GroovyClassLoader during full invalidation", e);
			}
		}
		classLoader = null;
	}

	/**
	 * Invalidate the cached file tree so that the next compilation will
	 * re-walk the workspace directory. Call this when filesystem changes are
	 * detected (e.g. files created, deleted, or renamed).
	 */
	@Override
	public void invalidateFileCache() {
		cachedGroovyFiles = null;
	}

	/**
	 * No-op — Java source stubs are now added as synthetic Groovy sources
	 * directly to the compilation unit (fresh on every {@link #create} call),
	 * so there is no separate index to invalidate.
	 */
	@Override
	public void invalidateJavaSourceIndex() {
		// no-op: stubs are rebuilt from disk on each create() call
	}

	/**
	 * Returns the resolved classpath cache, or {@code null} if not yet
	 * computed. Used by the memory profiler for size estimation.
	 */
	public List<String> getResolvedClasspathCache() {
		return resolvedClasspathCache;
	}

	/**
	 * Returns the number of cached .groovy file paths, or 0 if the cache
	 * has not been populated yet. Used by the memory profiler.
	 */
	public int getCachedGroovyFileCount() {
		Set<Path> cached = cachedGroovyFiles;
		return cached != null ? cached.size() : 0;
	}

	@Override
	public GroovyLSCompilationUnit create(Path workspaceRoot, FileContentsTracker fileContentsTracker) {
		return create(workspaceRoot, fileContentsTracker, Collections.emptySet());
	}

	@Override
	public GroovyLSCompilationUnit create(Path workspaceRoot, FileContentsTracker fileContentsTracker,
			Set<URI> additionalInvalidations) {
		if (config == null) {
			config = getConfiguration();
		}

		if (classLoader == null) {
			ClassLoader parentLoader = ClassLoader.getSystemClassLoader().getParent();
			classLoader = new GroovyClassLoader(parentLoader, config, true);
		}

		boolean hadCompilationUnit = compilationUnit != null;
		Set<URI> effectiveChanged = mergeChangedUris(fileContentsTracker.getChangedURIs(), additionalInvalidations);
		syncCompilationUnitWithChanges(effectiveChanged);
		if (!hadCompilationUnit) {
			effectiveChanged = null;
		}

		if (workspaceRoot != null) {
			addDirectoryToCompilationUnit(workspaceRoot, compilationUnit, fileContentsTracker, effectiveChanged);
		} else {
			addOpenFilesToCompilationUnit(fileContentsTracker, effectiveChanged, compilationUnit);
		}

		// Add synthetic Groovy stubs for Java source files that haven't been
		// compiled yet. This is done fresh on every create() call — scanning
		// the disk each time — so stubs always reflect the current state of
		// Java source files, even if they were just moved/renamed.
		if (javaStubGenerator != null) {
			javaStubGenerator.addJavaSourceStubs(workspaceRoot, compilationUnit, resolvedClasspathCache);
		}

		return compilationUnit;
	}

	private Set<URI> mergeChangedUris(Set<URI> changedUris, Set<URI> additionalInvalidations) {
		if (additionalInvalidations == null || additionalInvalidations.isEmpty()) {
			return changedUris;
		}
		Set<URI> effectiveChanged = new HashSet<>(changedUris);
		effectiveChanged.addAll(additionalInvalidations);
		return effectiveChanged;
	}

	private void syncCompilationUnitWithChanges(Set<URI> effectiveChanged) {
		if (compilationUnit == null) {
			compilationUnit = new GroovyLSCompilationUnit(config, null, classLoader);
			return;
		}
		compilationUnit.setClassLoader(classLoader);
		List<SourceUnit> sourcesToRemove = new ArrayList<>();
		compilationUnit.iterator().forEachRemaining(sourceUnit -> {
			URI uri = sourceUnit.getSource().getURI();
			if (effectiveChanged.contains(uri)) {
				sourcesToRemove.add(sourceUnit);
			}
		});
		compilationUnit.removeSources(sourcesToRemove);
	}

	private void addOpenFilesToCompilationUnit(FileContentsTracker fileContentsTracker,
			Set<URI> urisToAdd,
			GroovyLSCompilationUnit targetCompilationUnit) {
		fileContentsTracker.getOpenURIs().forEach(uri -> {
			if (urisToAdd != null && !urisToAdd.contains(uri)) {
				return;
			}
			String contents = fileContentsTracker.getContents(uri);
			addOpenFileToCompilationUnit(uri, contents, targetCompilationUnit);
		});
	}

	@Override
	public GroovyLSCompilationUnit createIncremental(Path workspaceRoot,
			FileContentsTracker fileContentsTracker, Set<URI> filesToInclude) {
		if (config == null) {
			config = getConfiguration();
		}
		if (classLoader == null) {
			ClassLoader parentLoader = ClassLoader.getSystemClassLoader().getParent();
			classLoader = new GroovyClassLoader(parentLoader, config, true);
		}

		// Create a separate, temporary compilation unit — NOT stored in the
		// 'compilationUnit' field so it doesn't interfere with the base unit.
		GroovyLSCompilationUnit incrementalUnit = new GroovyLSCompilationUnit(config, null, classLoader);

		for (URI uri : filesToInclude) {
			addSourceToIncrementalUnit(uri, fileContentsTracker, incrementalUnit);
		}

		// Add Java source stubs to the incremental unit as well, so that
		// Java class imports resolve even in single-file compilation.
		if (javaStubGenerator != null) {
			javaStubGenerator.addJavaSourceStubs(workspaceRoot, incrementalUnit, resolvedClasspathCache);
		}

		return incrementalUnit;
	}

	private void addSourceToIncrementalUnit(URI uri,
			FileContentsTracker fileContentsTracker,
			GroovyLSCompilationUnit unit) {
		if (fileContentsTracker.isOpen(uri)) {
			String contents = fileContentsTracker.getContents(uri);
			if (contents != null) {
				addOpenFileToCompilationUnit(uri, contents, unit);
			}
		} else {
			try {
				Path filePath = Paths.get(uri);
				if (Files.isRegularFile(filePath)) {
					unit.addSource(filePath.toFile());
				}
			} catch (Exception ignored) {
				// Non-file URI (e.g. jar:/, jrt:/) cannot be added as a local source file.
			}
		}
	}

	protected CompilerConfiguration getConfiguration() {
		CompilerConfiguration compilerConfiguration = new CompilerConfiguration();

		Map<String, Boolean> optimizationOptions = new HashMap<>();
		optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);
		compilerConfiguration.setOptimizationOptions(optimizationOptions);

		List<String> classpathList = new ArrayList<>();
		getClasspathList(classpathList);
		logger.debug("CompilerConfiguration classpath ({} entries)", classpathList.size());
		for (String cp : classpathList) {
			if (cp.toLowerCase().contains("spock")) {
				logger.debug("  [SPOCK] effective classpath entry: {}", cp);
			}
		}
		compilerConfiguration.setClasspathList(classpathList);

		return compilerConfiguration;
	}

	protected void getClasspathList(List<String> result) {
		if (additionalClasspathList == null && testOnlyClasspathList == null) {
			return;
		}

		// Use cached resolved classpath when available to avoid repeated
		// filesystem stat calls (File.exists / isDirectory / isFile)
		if (resolvedClasspathCache != null) {
			result.addAll(resolvedClasspathCache);
			return;
		}

		Set<String> seen = new HashSet<>();
		List<String> resolved = new ArrayList<>();
		for (String entry : getCombinedClasspathEntries()) {
			resolveClasspathEntry(entry, seen, resolved);
		}

		resolvedClasspathCache = resolved;
		result.addAll(resolved);
    }

	private List<String> getCombinedClasspathEntries() {
		List<String> allEntries = new ArrayList<>();
		if (additionalClasspathList != null) {
			allEntries.addAll(additionalClasspathList);
		}
		if (testOnlyClasspathList != null) {
			allEntries.addAll(testOnlyClasspathList);
		}
		return allEntries;
	}

	private void resolveClasspathEntry(String rawEntry, Set<String> seen, List<String> resolved) {
		String entry = rawEntry;
		boolean mustBeDirectory = false;
		if (entry.endsWith("*")) {
			entry = entry.substring(0, entry.length() - 1);
			mustBeDirectory = true;
		}

		File file = new File(entry);
		if (!file.exists()) {
			return;
		}

		String canonicalPath = toCanonicalPath(file);
		if (file.isDirectory()) {
			addUniquePath(canonicalPath, seen, resolved);
			if (mustBeDirectory) {
				addDirectoryJars(file, seen, resolved);
			}
			return;
		}

		if (!mustBeDirectory && file.isFile() && file.getName().endsWith(".jar")) {
			addUniquePath(canonicalPath, seen, resolved);
		}
	}

	private void addDirectoryJars(File directory, Set<String> seen, List<String> resolved) {
		File[] children = directory.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (child.isFile() && child.getName().endsWith(".jar")) {
				addUniquePath(toCanonicalPath(child), seen, resolved);
			}
		}
	}

	private void addUniquePath(String path, Set<String> seen, List<String> resolved) {
		if (seen.add(path)) {
			resolved.add(path);
		}
	}

	private String toCanonicalPath(File file) {
		try {
			return file.getCanonicalPath();
		} catch (IOException e) {
			return file.getPath();
		}
	}

	/**
	 * Populate the file cache by walking the directory tree once, filtering by
	 * extension and exclusion rules. Subsequent calls are no-ops until
	 * {@link #invalidateFileCache()} is called.
	 *
	 * <p>Uses {@link Files#walkFileTree} so that entire subtrees can be
	 * pruned via {@link FileVisitResult#SKIP_SUBTREE}.  In particular,
	 * directories that look like separate projects (contain a build file
	 * <b>and</b> JVM source directories) are skipped when they are not the
	 * walk root &mdash; this prevents duplicate-class errors when multiple
	 * sibling projects live under a common workspace root.</p>
	 */
	private Set<Path> getOrBuildFileCache(Path dirPath) {
		if (cachedGroovyFiles != null) {
			return cachedGroovyFiles;
		}
		cachedGroovyFiles = new HashSet<>();
		try {
			if (Files.exists(dirPath)) {
				logger.debug("Building file cache for .groovy sources: {}", dirPath);
				logger.debug("  excludedSubRoots: {}", excludedSubRoots);
				Path normalizedRoot = dirPath.normalize();

				Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
						return shouldSkipDirectory(dir, normalizedRoot)
								? FileVisitResult.SKIP_SUBTREE
								: FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						if (attrs.isRegularFile()
								&& file.toString().endsWith(FILE_EXTENSION_GROOVY)) {
							cachedGroovyFiles.add(file);
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) {
						logger.debug("Cannot access {}: {}", file, exc.getMessage());
						return FileVisitResult.CONTINUE;
					}
				});

				logger.debug("File cache built: {} .groovy files", cachedGroovyFiles.size());
			}
		} catch (IOException e) {
			logger.error("Failed to walk directory for source files: {}", dirPath, e);
		}
		return cachedGroovyFiles;
	}

	private boolean shouldSkipDirectory(Path dir, Path normalizedRoot) {
		if (dir.equals(normalizedRoot)) {
			return false;
		}
		String dirName = dir.getFileName().toString();
		if (EXCLUDED_DIR_NAMES.contains(dirName) || dirName.startsWith(".")) {
			return true;
		}
		if (isInsideExcludedSubRoot(dir)) {
			logger.debug("  Skipping subtree (subproject root): {}", dir);
			return true;
		}
		if (isSeparateProject(dir)) {
			logger.debug("  Skipping subtree (separate project): {}", dir);
			return true;
		}
		return false;
	}

	protected void addDirectoryToCompilationUnit(Path dirPath, GroovyLSCompilationUnit compilationUnit,
			FileContentsTracker fileContentsTracker, Set<URI> changedUris) {
		Set<Path> groovyFiles = getOrBuildFileCache(dirPath);

		// Collect open file packages to prioritize related files when the
		// project exceeds MAX_FULL_COMPILATION_FILES
		boolean needsLimiting = groovyFiles.size() > MAX_FULL_COMPILATION_FILES;
		Set<String> openPackageDirs = collectOpenPackageDirs(fileContentsTracker, needsLimiting, dirPath,
				groovyFiles.size());

		int addedCount = addPrioritizedSources(groovyFiles, fileContentsTracker, changedUris,
				needsLimiting, openPackageDirs, compilationUnit);

		addRemainingSources(groovyFiles, fileContentsTracker, changedUris,
				needsLimiting, openPackageDirs, compilationUnit, addedCount);

		Path normalizedDir = dirPath.normalize();
		addOpenSourcesInDirectory(dirPath, normalizedDir, compilationUnit, fileContentsTracker, changedUris);
	}

	private Set<String> collectOpenPackageDirs(FileContentsTracker fileContentsTracker, boolean needsLimiting,
			Path dirPath, int groovyFileCount) {
		Set<String> openPackageDirs = new HashSet<>();
		if (!needsLimiting) {
			return openPackageDirs;
		}
		logger.warn("Project {} has {} .groovy files (limit {}). Prioritizing open file packages to reduce memory usage.",
				dirPath, groovyFileCount, MAX_FULL_COMPILATION_FILES);
		fileContentsTracker.getOpenURIs().forEach(uri -> {
			Path openPath = toFilePath(uri);
			if (openPath != null && openPath.getParent() != null) {
				openPackageDirs.add(openPath.getParent().normalize().toString());
			}
		});
		return openPackageDirs;
	}

	private int addPrioritizedSources(Set<Path> groovyFiles, FileContentsTracker fileContentsTracker,
			Set<URI> changedUris, boolean needsLimiting, Set<String> openPackageDirs,
			GroovyLSCompilationUnit compilationUnit) {
		if (!needsLimiting) {
			return 0;
		}
		int addedCount = 0;
		for (Path filePath : groovyFiles) {
			URI fileURI = filePath.toUri();
			String parentDir = filePath.getParent() != null ? filePath.getParent().normalize().toString() : "";
			if (!fileContentsTracker.isOpen(fileURI)
					&& openPackageDirs.contains(parentDir)
					&& (changedUris == null || changedUris.contains(fileURI))
					&& addRegularFileSource(filePath, compilationUnit)) {
				addedCount++;
			}
		}
		return addedCount;
	}

	private int addRemainingSources(Set<Path> groovyFiles, FileContentsTracker fileContentsTracker,
			Set<URI> changedUris, boolean needsLimiting, Set<String> openPackageDirs,
			GroovyLSCompilationUnit compilationUnit, int alreadyAddedCount) {
		int addedCount = 0;
		for (Path filePath : groovyFiles) {
			if (needsLimiting && alreadyAddedCount + addedCount >= MAX_FULL_COMPILATION_FILES) {
				logger.warn("Reached file limit of {}. {} files excluded from compilation.",
						MAX_FULL_COMPILATION_FILES, groovyFiles.size() - (alreadyAddedCount + addedCount));
				break;
			}
			if (shouldAddInRemainingPass(filePath, fileContentsTracker, changedUris, needsLimiting, openPackageDirs)
					&& addRegularFileSource(filePath, compilationUnit)) {
				addedCount++;
			}
		}
		return addedCount;
	}

	private boolean shouldAddInRemainingPass(Path filePath, FileContentsTracker fileContentsTracker,
			Set<URI> changedUris, boolean needsLimiting, Set<String> openPackageDirs) {
		URI fileURI = filePath.toUri();
		if (fileContentsTracker.isOpen(fileURI)) {
			return false;
		}
		String parentDir = filePath.getParent() != null ? filePath.getParent().normalize().toString() : "";
		boolean alreadyAddedInPriorityPass = needsLimiting && !openPackageDirs.isEmpty() && openPackageDirs.contains(parentDir);
		return !alreadyAddedInPriorityPass && (changedUris == null || changedUris.contains(fileURI));
	}

	private boolean addRegularFileSource(Path filePath, GroovyLSCompilationUnit compilationUnit) {
		File file = filePath.toFile();
		if (!file.isFile()) {
			return false;
		}
		compilationUnit.addSource(file);
		return true;
	}

	private void addOpenSourcesInDirectory(Path dirPath, Path normalizedDir,
			GroovyLSCompilationUnit compilationUnit, FileContentsTracker fileContentsTracker,
			Set<URI> changedUris) {
		fileContentsTracker.getOpenURIs().forEach(uri -> {
			Path openPath = toFilePath(uri);
			if (openPath == null || !openPath.normalize().startsWith(normalizedDir)) {
				return;
			}
			if (isInsideExcludedDirectory(openPath, dirPath)) {
				logger.debug("  Excluded open file (dir): {}", openPath);
				return;
			}
			if (isInsideExcludedSubRoot(openPath)) {
				logger.debug("  Excluded open file (subproject): {}", openPath);
				return;
			}
			if (isInsideSeparateProject(openPath, normalizedDir)) {
				logger.debug("  Excluded open file (separate project): {}", openPath);
				return;
			}
			if (changedUris != null && !changedUris.contains(uri)) {
				return;
			}
			addOpenFileToCompilationUnit(uri, fileContentsTracker.getContents(uri), compilationUnit);
		});
	}

	private Path toFilePath(URI uri) {
		try {
			if (uri == null || uri.getScheme() == null || !"file".equalsIgnoreCase(uri.getScheme())) {
				return null;
			}
			return Paths.get(uri);
		} catch (Exception ignored) {
			return null;
		}
	}

	/**
	 * Checks whether the given file path is inside a directory that should be
	 * excluded from source file scanning (e.g., build output directories).
	 */
	private boolean isInsideExcludedDirectory(Path filePath, Path rootPath) {
		Path relativePath = rootPath.relativize(filePath);
		for (int i = 0; i < relativePath.getNameCount(); i++) {
			String segment = relativePath.getName(i).toString();
			if (EXCLUDED_DIR_NAMES.contains(segment)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether the given file path is inside a nested subproject root
	 * that should be handled by its own project scope.
	 */
	private boolean isInsideExcludedSubRoot(Path filePath) {
		for (Path subRoot : excludedSubRoots) {
			if (filePath.startsWith(subRoot)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether the given directory looks like a separate build-tool
	 * project: it contains a recognised build file <b>and</b> has at least
	 * one standard JVM source directory ({@code src/main/java},
	 * {@code src/main/groovy}, {@code src/test/java}, or
	 * {@code src/test/groovy}).  Used during the file walk to prune
	 * subtrees that belong to independent sibling projects.
	 */
	private static boolean isSeparateProject(Path dir) {
		boolean hasBuildFile = false;
		for (String buildFile : BUILD_FILE_NAMES) {
			if (Files.isRegularFile(dir.resolve(buildFile))) {
				hasBuildFile = true;
				break;
			}
		}
		if (!hasBuildFile) {
			return false;
		}
		return Files.isDirectory(dir.resolve("src/main/java"))
				|| Files.isDirectory(dir.resolve("src/main/groovy"))
				|| Files.isDirectory(dir.resolve("src/test/java"))
				|| Files.isDirectory(dir.resolve("src/test/groovy"));
	}

	/**
	 * Checks whether the given file is inside a sub-directory of
	 * {@code rootPath} that is a separate project.  Walks the path
	 * segments between the root and the file looking for the first
	 * directory that passes {@link #isSeparateProject(Path)}.
	 */
	private static boolean isInsideSeparateProject(Path filePath, Path rootPath) {
		Path relative = rootPath.relativize(filePath.normalize());
		// Walk intermediate directories (skip the file name itself)
		Path current = rootPath;
		for (int i = 0; i < relative.getNameCount() - 1; i++) {
			current = current.resolve(relative.getName(i));
			if (Files.isDirectory(current) && isSeparateProject(current)) {
				return true;
			}
		}
		return false;
	}

	protected void addOpenFileToCompilationUnit(URI uri, String contents, GroovyLSCompilationUnit compilationUnit) {
		if (uri == null || contents == null) {
			return;
		}
		if (uri.getScheme() != null && !"file".equalsIgnoreCase(uri.getScheme())
				&& !"untitled".equalsIgnoreCase(uri.getScheme())) {
			return;
		}
		SourceUnit sourceUnit = new SourceUnit(uri.toString(),
				new StringReaderSourceWithURI(contents, uri, compilationUnit.getConfiguration()),
				compilationUnit.getConfiguration(), compilationUnit.getClassLoader(),
				compilationUnit.getErrorCollector());
		compilationUnit.addSource(sourceUnit);
	}
}