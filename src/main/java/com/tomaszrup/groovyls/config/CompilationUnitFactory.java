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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
	 * Directory names to exclude when walking the workspace for source files.
	 * These are typically build output directories that may contain copies of
	 * source files, which would cause duplicate class definition errors.
	 */
	private static final Set<String> EXCLUDED_DIR_NAMES = new HashSet<>(Arrays.asList(
			"bin", "build", "out", ".gradle", ".settings", ".metadata"));

	private GroovyLSCompilationUnit compilationUnit;
	private CompilerConfiguration config;
	private GroovyClassLoader classLoader;
	private List<String> additionalClasspathList;
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

	public CompilationUnitFactory() {
	}

	public void setExcludedSubRoots(List<Path> excludedSubRoots) {
		this.excludedSubRoots = excludedSubRoots != null ? excludedSubRoots : new ArrayList<>();
		logger.info("Set excludedSubRoots: {}", this.excludedSubRoots);
	}

	public List<String> getAdditionalClasspathList() {
		return additionalClasspathList;
	}

	public void setAdditionalClasspathList(List<String> additionalClasspathList) {
		this.additionalClasspathList = additionalClasspathList;
		logger.info("Set additionalClasspathList ({} entries)", additionalClasspathList != null ? additionalClasspathList.size() : 0);
		if (additionalClasspathList != null) {
			for (String cp : additionalClasspathList) {
				if (cp.toLowerCase().contains("spock")) {
					logger.info("  [SPOCK] classpath entry: {}", cp);
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
	public void invalidateCompilationUnit() {
		compilationUnit = null;
	}

	/**
	 * Invalidate the cached file tree so that the next compilation will
	 * re-walk the workspace directory. Call this when filesystem changes are
	 * detected (e.g. files created, deleted, or renamed).
	 */
	public void invalidateFileCache() {
		cachedGroovyFiles = null;
	}

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
			classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
		}

		Set<URI> changedUris = fileContentsTracker.getChangedURIs();
		// Merge additional invalidations (dependency-driven) with content changes
		Set<URI> effectiveChanged;
		if (additionalInvalidations != null && !additionalInvalidations.isEmpty()) {
			effectiveChanged = new HashSet<>(changedUris);
			effectiveChanged.addAll(additionalInvalidations);
		} else {
			effectiveChanged = changedUris;
		}

		if (compilationUnit == null) {
			compilationUnit = new GroovyLSCompilationUnit(config, null, classLoader);
			// we don't care about changed URIs if there's no compilation unit yet
			effectiveChanged = null;
		} else {
			compilationUnit.setClassLoader(classLoader);
			final Set<URI> urisToRemove = effectiveChanged;
			List<SourceUnit> sourcesToRemove = new ArrayList<>();
			compilationUnit.iterator().forEachRemaining(sourceUnit -> {
				URI uri = sourceUnit.getSource().getURI();
				if (urisToRemove.contains(uri)) {
					sourcesToRemove.add(sourceUnit);
				}
			});
			// if an URI has changed, we remove it from the compilation unit so
			// that a new version can be built from the updated source file
			compilationUnit.removeSources(sourcesToRemove);
		}

		if (workspaceRoot != null) {
			addDirectoryToCompilationUnit(workspaceRoot, compilationUnit, fileContentsTracker, effectiveChanged);
		} else {
			final Set<URI> urisToAdd = effectiveChanged;
			fileContentsTracker.getOpenURIs().forEach(uri -> {
				// if we're only tracking changes, skip all files that haven't
				// actually changed
				if (urisToAdd != null && !urisToAdd.contains(uri)) {
					return;
				}
				String contents = fileContentsTracker.getContents(uri);
				addOpenFileToCompilationUnit(uri, contents, compilationUnit);
			});
		}

		return compilationUnit;
	}

	@Override
	public GroovyLSCompilationUnit createIncremental(Path workspaceRoot,
			FileContentsTracker fileContentsTracker, Set<URI> filesToInclude) {
		if (config == null) {
			config = getConfiguration();
		}
		if (classLoader == null) {
			classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
		}

		// Create a separate, temporary compilation unit — NOT stored in the
		// 'compilationUnit' field so it doesn't interfere with the base unit.
		GroovyLSCompilationUnit incrementalUnit = new GroovyLSCompilationUnit(config, null, classLoader);

		for (URI uri : filesToInclude) {
			if (fileContentsTracker.isOpen(uri)) {
				String contents = fileContentsTracker.getContents(uri);
				if (contents != null) {
					addOpenFileToCompilationUnit(uri, contents, incrementalUnit);
				}
			} else {
				Path filePath = Paths.get(uri);
				if (Files.isRegularFile(filePath)) {
					incrementalUnit.addSource(filePath.toFile());
				}
			}
		}

		return incrementalUnit;
	}

	protected CompilerConfiguration getConfiguration() {
		CompilerConfiguration config = new CompilerConfiguration();

		Map<String, Boolean> optimizationOptions = new HashMap<>();
		optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);
		config.setOptimizationOptions(optimizationOptions);

		List<String> classpathList = new ArrayList<>();
		getClasspathList(classpathList);
		logger.info("CompilerConfiguration classpath ({} entries)", classpathList.size());
		for (String cp : classpathList) {
			if (cp.toLowerCase().contains("spock")) {
				logger.info("  [SPOCK] effective classpath entry: {}", cp);
			}
		}
		config.setClasspathList(classpathList);

		return config;
	}

	protected void getClasspathList(List<String> result) {
		if (additionalClasspathList == null) {
			return;
		}

		// Use cached resolved classpath when available to avoid repeated
		// filesystem stat calls (File.exists / isDirectory / isFile)
		if (resolvedClasspathCache != null) {
			result.addAll(resolvedClasspathCache);
			return;
		}

		List<String> resolved = new ArrayList<>();
		for (String entry : additionalClasspathList) {
			boolean mustBeDirectory = false;
			if (entry.endsWith("*")) {
				entry = entry.substring(0, entry.length() - 1);
				mustBeDirectory = true;
			}

            File file = new File(entry);
            if (!file.exists()) {
                continue;
            }

            if (file.isDirectory()) {
                // Always add directories (important for build/classes output)
                resolved.add(file.getPath());

                // And if user used '*', include jars inside
                if (mustBeDirectory) {
                    File[] children = file.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child.isFile() && child.getName().endsWith(".jar")) {
                                resolved.add(child.getPath());
                            }
                        }
                    }
                }
            } else if (!mustBeDirectory && file.isFile() && file.getName().endsWith(".jar")) {
                resolved.add(entry);
            }
        }

		resolvedClasspathCache = resolved;
		result.addAll(resolved);
    }

	/**
	 * Populate the file cache by walking the directory tree once, filtering by
	 * extension and exclusion rules. Subsequent calls are no-ops until
	 * {@link #invalidateFileCache()} is called.
	 */
	private Set<Path> getOrBuildFileCache(Path dirPath) {
		if (cachedGroovyFiles != null) {
			return cachedGroovyFiles;
		}
		cachedGroovyFiles = new HashSet<>();
		try {
			if (Files.exists(dirPath)) {
				logger.info("Building file cache for .groovy sources: {}", dirPath);
				logger.info("  excludedSubRoots: {}", excludedSubRoots);
				try (java.util.stream.Stream<Path> stream = Files.walk(dirPath)) {
					stream.forEach((filePath) -> {
						if (!filePath.toString().endsWith(FILE_EXTENSION_GROOVY)) {
							return;
						}
						if (isInsideExcludedDirectory(filePath, dirPath)) {
							logger.debug("  Excluded (dir): {}", filePath);
							return;
						}
						if (isInsideExcludedSubRoot(filePath)) {
							logger.info("  Excluded (subproject): {}", filePath);
							return;
						}
						cachedGroovyFiles.add(filePath);
					});
				}
				logger.info("File cache built: {} .groovy files", cachedGroovyFiles.size());
			}
		} catch (IOException e) {
			logger.error("Failed to walk directory for source files: {}", dirPath, e);
		}
		return cachedGroovyFiles;
	}

	protected void addDirectoryToCompilationUnit(Path dirPath, GroovyLSCompilationUnit compilationUnit,
			FileContentsTracker fileContentsTracker, Set<URI> changedUris) {
		Set<Path> groovyFiles = getOrBuildFileCache(dirPath);
		for (Path filePath : groovyFiles) {
			URI fileURI = filePath.toUri();
			if (!fileContentsTracker.isOpen(fileURI)) {
				File file = filePath.toFile();
				if (file.isFile()) {
					if (changedUris == null || changedUris.contains(fileURI)) {
						compilationUnit.addSource(file);
					}
				}
			}
		}
		fileContentsTracker.getOpenURIs().forEach(uri -> {
			Path openPath = Paths.get(uri);
			if (!openPath.normalize().startsWith(dirPath.normalize())) {
				return;
			}
			if (isInsideExcludedDirectory(openPath, dirPath)) {
				logger.debug("  Excluded open file (dir): {}", openPath);
				return;
			}
			if (isInsideExcludedSubRoot(openPath)) {
				logger.info("  Excluded open file (subproject): {}", openPath);
				return;
			}
			if (changedUris != null && !changedUris.contains(uri)) {
				return;
			}
			String contents = fileContentsTracker.getContents(uri);
			addOpenFileToCompilationUnit(uri, contents, compilationUnit);
		});
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

	protected void addOpenFileToCompilationUnit(URI uri, String contents, GroovyLSCompilationUnit compilationUnit) {
		Path filePath = Paths.get(uri);
		SourceUnit sourceUnit = new SourceUnit(filePath.toString(),
				new StringReaderSourceWithURI(contents, uri, compilationUnit.getConfiguration()),
				compilationUnit.getConfiguration(), compilationUnit.getClassLoader(),
				compilationUnit.getErrorCollector());
		compilationUnit.addSource(sourceUnit);
	}
}