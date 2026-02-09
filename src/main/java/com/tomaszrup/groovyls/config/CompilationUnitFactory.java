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
		invalidateCompilationUnit();
	}

	public void invalidateCompilationUnit() {
		compilationUnit = null;
		config = null;
		classLoader = null;
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
		if (config == null) {
			config = getConfiguration();
		}

		if (classLoader == null) {
			classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
		}

		Set<URI> changedUris = fileContentsTracker.getChangedURIs();
		if (compilationUnit == null) {
			compilationUnit = new GroovyLSCompilationUnit(config, null, classLoader);
			// we don't care about changed URIs if there's no compilation unit yet
			changedUris = null;
		} else {
			compilationUnit.setClassLoader(classLoader);
			final Set<URI> urisToRemove = changedUris;
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
			addDirectoryToCompilationUnit(workspaceRoot, compilationUnit, fileContentsTracker, changedUris);
		} else {
			final Set<URI> urisToAdd = changedUris;
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
                result.add(file.getPath());

                // And if user used '*', include jars inside
                if (mustBeDirectory) {
                    File[] children = file.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child.isFile() && child.getName().endsWith(".jar")) {
                                result.add(child.getPath());
                            }
                        }
                    }
                }
            } else if (!mustBeDirectory && file.isFile() && file.getName().endsWith(".jar")) {
                result.add(entry);
            }
        }
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
				Files.walk(dirPath).forEach((filePath) -> {
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