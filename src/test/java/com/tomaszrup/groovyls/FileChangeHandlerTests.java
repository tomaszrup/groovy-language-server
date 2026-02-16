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
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Unit tests for {@link FileChangeHandler}: file change classification,
 * build-output filtering, and watched-file event handling.
 */
class FileChangeHandlerTests {

	private FileChangeHandler handler;
	private ProjectScopeManager scopeManager;
	private CompilationService compilationService;
	private FileContentsTracker fileContentsTracker;
	private ScheduledExecutorService executor;
	private Path tempDir;

	@BeforeEach
	void setup() throws IOException {
		fileContentsTracker = new FileContentsTracker();
		CompilationUnitFactory defaultFactory = new CompilationUnitFactory();
		scopeManager = new ProjectScopeManager(defaultFactory, fileContentsTracker);
		compilationService = new CompilationService(fileContentsTracker);
		executor = Executors.newSingleThreadScheduledExecutor();
		handler = new FileChangeHandler(scopeManager, compilationService, executor);

		tempDir = Files.createTempDirectory("fch-test");
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
		if (tempDir != null) {
			try {
				Files.walk(tempDir)
						.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			} catch (IOException e) {
				// ignore
			}
		}
	}

	// --- isBuildOutputFile ---

	@Test
	void testIsBuildOutputFileBuildDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path buildFile = projectRoot.resolve("build/generated/Foo.java");
		Assertions.assertTrue(FileChangeHandler.isBuildOutputFile(buildFile, projectRoot));
	}

	@Test
	void testIsBuildOutputFileTargetDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path targetFile = projectRoot.resolve("target/classes/Foo.java");
		Assertions.assertTrue(FileChangeHandler.isBuildOutputFile(targetFile, projectRoot));
	}

	@Test
	void testIsBuildOutputFileGradleDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path gradleFile = projectRoot.resolve(".gradle/cache/Foo.java");
		Assertions.assertTrue(FileChangeHandler.isBuildOutputFile(gradleFile, projectRoot));
	}

	@Test
	void testIsBuildOutputFileOutDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path outFile = projectRoot.resolve("out/production/Foo.java");
		Assertions.assertTrue(FileChangeHandler.isBuildOutputFile(outFile, projectRoot));
	}

	@Test
	void testIsBuildOutputFileBinDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path binFile = projectRoot.resolve("bin/Foo.java");
		Assertions.assertTrue(FileChangeHandler.isBuildOutputFile(binFile, projectRoot));
	}

	@Test
	void testNotBuildOutputFileSrcDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path srcFile = projectRoot.resolve("src/main/java/Foo.java");
		Assertions.assertFalse(FileChangeHandler.isBuildOutputFile(srcFile, projectRoot));
	}

	@Test
	void testNotBuildOutputFileLibDir() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		Path libFile = projectRoot.resolve("lib/Utils.java");
		Assertions.assertFalse(FileChangeHandler.isBuildOutputFile(libFile, projectRoot));
	}

	@Test
	void testIsBuildOutputFileProjectRootItself() {
		Path projectRoot = Path.of("/project").toAbsolutePath();
		// Relativizing root against itself produces an empty path
		Assertions.assertFalse(FileChangeHandler.isBuildOutputFile(projectRoot, projectRoot));
	}

	// --- handleDidChangeWatchedFiles with no project scopes ---

	@Test
	void testHandleDidChangeWatchedFilesNoProjectScopes() throws IOException {
		// With no project scopes, the handler should process changes against the
		// default scope. This shouldn't throw.
		Path groovyFile = tempDir.resolve("src/main/groovy/Test.groovy");
		Files.createDirectories(groovyFile.getParent());
		Files.writeString(groovyFile, "class Test {}");

		scopeManager.setWorkspaceRoot(tempDir);

		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(
						new FileEvent(groovyFile.toUri().toString(), FileChangeType.Changed)));

		Assertions.assertDoesNotThrow(() -> handler.handleDidChangeWatchedFiles(params));
	}

	// --- handleDidChangeWatchedFiles with project scopes ---

	@Test
	void testHandleDidChangeWatchedFilesGroovyFileUnderScope() throws IOException {
		Path projectRoot = tempDir.resolve("myproject");
		Path srcDir = projectRoot.resolve("src/main/groovy");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("App.groovy"), "class App {}");

		scopeManager.registerDiscoveredProjects(Arrays.asList(projectRoot));
		// Resolve classpath so the handler will compile
		scopeManager.updateProjectClasspath(projectRoot, Collections.emptyList());

		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(
						new FileEvent(srcDir.resolve("App.groovy").toUri().toString(), FileChangeType.Changed)));

		Assertions.assertDoesNotThrow(() -> handler.handleDidChangeWatchedFiles(params));
	}

	@Test
	void testHandleDidChangeWatchedFilesJavaFile() throws IOException {
		Path projectRoot = tempDir.resolve("javaproject");
		Path srcDir = projectRoot.resolve("src/main/java");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Helper.java"), "public class Helper {}");

		scopeManager.registerDiscoveredProjects(Arrays.asList(projectRoot));
		scopeManager.updateProjectClasspath(projectRoot, Collections.emptyList());

		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(
						new FileEvent(srcDir.resolve("Helper.java").toUri().toString(), FileChangeType.Changed)));

		Assertions.assertDoesNotThrow(() -> handler.handleDidChangeWatchedFiles(params));
	}

	@Test
	void testHandleDidChangeWatchedFilesBuildGradle() throws IOException {
		Path projectRoot = tempDir.resolve("gradleproject");
		Files.createDirectories(projectRoot);
		Files.writeString(projectRoot.resolve("build.gradle"), "apply plugin: 'groovy'");

		scopeManager.registerDiscoveredProjects(Arrays.asList(projectRoot));
		scopeManager.updateProjectClasspath(projectRoot, Collections.emptyList());

		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(
						new FileEvent(projectRoot.resolve("build.gradle").toUri().toString(),
								FileChangeType.Changed)));

		Assertions.assertDoesNotThrow(() -> handler.handleDidChangeWatchedFiles(params));
	}

	@Test
	void testHandleDidChangeWatchedFilesSkipsUnresolvedClasspath() throws IOException {
		Path projectRoot = tempDir.resolve("unresolvedproject");
		Path srcDir = projectRoot.resolve("src/main/groovy");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("Unresolved.groovy"), "class Unresolved {}");

		scopeManager.registerDiscoveredProjects(Arrays.asList(projectRoot));
		// Do NOT resolve classpath

		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(
				Collections.singletonList(
						new FileEvent(srcDir.resolve("Unresolved.groovy").toUri().toString(),
								FileChangeType.Changed)));

		Assertions.assertDoesNotThrow(() -> handler.handleDidChangeWatchedFiles(params));
	}

	// --- setJavaChangeListener ---

	@Test
	void testSetJavaChangeListener() {
		boolean[] called = { false };
		Assertions.assertDoesNotThrow(() -> handler.setJavaChangeListener(root -> called[0] = true));
		Assertions.assertFalse(called[0]);
	}

	// --- Empty params ---

	@Test
	void testHandleDidChangeWatchedFilesEmptyParams() {
		DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(Collections.emptyList());
		Assertions.assertDoesNotThrow(() -> handler.handleDidChangeWatchedFiles(params));
	}
}
