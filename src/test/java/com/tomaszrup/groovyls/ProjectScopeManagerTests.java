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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.compiler.SharedClasspathIndexCache;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.util.FileContentsTracker;

/**
 * Unit tests for {@link ProjectScopeManager}: scope lookup, project
 * registration, classpath updates, configuration handling, and resolution
 * tracking.
 */
class ProjectScopeManagerTests {

	private ProjectScopeManager manager;
	private FileContentsTracker fileContentsTracker;
	private CompilationUnitFactory defaultFactory;

	private static final Path ROOT = Paths.get("/workspace").toAbsolutePath();
	private static final Path PROJECT_A = Paths.get("/workspace/projectA").toAbsolutePath();
	private static final Path PROJECT_B = Paths.get("/workspace/projectB").toAbsolutePath();
	private static final Path PROJECT_A_SUB = Paths.get("/workspace/projectA/submodule").toAbsolutePath();

	@BeforeEach
	void setup() {
		fileContentsTracker = new FileContentsTracker();
		defaultFactory = new CompilationUnitFactory();
		manager = new ProjectScopeManager(defaultFactory, fileContentsTracker);
	}

	// --- Default scope ---

	@Test
	void testDefaultScopeIsNonNull() {
		Assertions.assertNotNull(manager.getDefaultScope());
	}

	@Test
	void testDefaultScopeClasspathResolved() {
		Assertions.assertTrue(manager.getDefaultScope().isClasspathResolved());
	}

	@Test
	void testDefaultScopeReturnedWhenNoProjectScopes() {
		URI fileUri = URI.create("file:///workspace/src/Foo.groovy");
		ProjectScope scope = manager.findProjectScope(fileUri);
		Assertions.assertSame(manager.getDefaultScope(), scope);
	}

	@Test
	void testDefaultScopeReturnedWhenUriIsNull() {
		ProjectScope scope = manager.findProjectScope(null);
		Assertions.assertSame(manager.getDefaultScope(), scope);
	}

	// --- setWorkspaceRoot ---

	@Test
	void testSetWorkspaceRoot() {
		manager.setWorkspaceRoot(ROOT);
		Assertions.assertEquals(ROOT, manager.getWorkspaceRoot());
	}

	@Test
	void testSetWorkspaceRootCreatesNewDefaultScope() {
		ProjectScope oldDefault = manager.getDefaultScope();
		manager.setWorkspaceRoot(ROOT);
		ProjectScope newDefault = manager.getDefaultScope();
		Assertions.assertNotSame(oldDefault, newDefault);
		Assertions.assertTrue(newDefault.isClasspathResolved());
	}

	// --- Feature toggles ---

	@Test
	void testSemanticHighlightingEnabledByDefault() {
		Assertions.assertTrue(manager.isSemanticHighlightingEnabled());
	}

	@Test
	void testFormattingEnabledByDefault() {
		Assertions.assertTrue(manager.isFormattingEnabled());
	}

	@Test
	void testFormattingOrganizeImportsEnabledByDefault() {
		Assertions.assertTrue(manager.isFormattingOrganizeImportsEnabled());
	}

	@Test
	void testUpdateFeatureTogglesDisableSemanticHighlighting() {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject sh = new JsonObject();
		sh.addProperty("enabled", false);
		groovy.add("semanticHighlighting", sh);
		settings.add("groovy", groovy);

		manager.updateFeatureToggles(settings);
		Assertions.assertFalse(manager.isSemanticHighlightingEnabled());
	}

	@Test
	void testUpdateFeatureTogglesDisableFormatting() {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject fmt = new JsonObject();
		fmt.addProperty("enabled", false);
		groovy.add("formatting", fmt);
		settings.add("groovy", groovy);

		manager.updateFeatureToggles(settings);
		Assertions.assertFalse(manager.isFormattingEnabled());
	}

	@Test
	void testUpdateFeatureTogglesDisableFormattingOrganizeImports() {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject fmt = new JsonObject();
		fmt.addProperty("organizeImports", false);
		groovy.add("formatting", fmt);
		settings.add("groovy", groovy);

		manager.updateFeatureToggles(settings);
		Assertions.assertFalse(manager.isFormattingOrganizeImportsEnabled());
	}

	@Test
	void testUpdateFeatureTogglesIgnoresJsonNullSemanticHighlightingEnabled() {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject sh = new JsonObject();
		sh.add("enabled", com.google.gson.JsonNull.INSTANCE);
		groovy.add("semanticHighlighting", sh);
		settings.add("groovy", groovy);

		manager.updateFeatureToggles(settings);
		Assertions.assertTrue(manager.isSemanticHighlightingEnabled());
	}

	@Test
	void testUpdateFeatureTogglesIgnoresJsonNullFormattingEnabled() {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		JsonObject fmt = new JsonObject();
		fmt.add("enabled", com.google.gson.JsonNull.INSTANCE);
		groovy.add("formatting", fmt);
		settings.add("groovy", groovy);

		manager.updateFeatureToggles(settings);
		Assertions.assertTrue(manager.isFormattingEnabled());
	}

	@Test
	void testUpdateFeatureTogglesIgnoresUnrelatedSettings() {
		JsonObject settings = new JsonObject();
		settings.addProperty("unrelated", "value");

		manager.updateFeatureToggles(settings);
		Assertions.assertTrue(manager.isSemanticHighlightingEnabled());
		Assertions.assertTrue(manager.isFormattingEnabled());
	}

	@Test
	void testUpdateFeatureTogglesEmptyObject() {
		JsonObject settings = new JsonObject();
		manager.updateFeatureToggles(settings);
		Assertions.assertTrue(manager.isSemanticHighlightingEnabled());
		Assertions.assertTrue(manager.isFormattingEnabled());
	}

	// --- registerDiscoveredProjects ---

	@Test
	void testRegisterDiscoveredProjects() {
		List<Path> roots = Arrays.asList(PROJECT_A, PROJECT_B);
		manager.registerDiscoveredProjects(roots);

		List<ProjectScope> scopes = manager.getProjectScopes();
		Assertions.assertEquals(2, scopes.size());
	}

	@Test
	void testRegisterDiscoveredProjectsSortedByPathLengthDesc() {
		// PROJECT_A_SUB is longer than PROJECT_A, ensuring longest-prefix-first
		List<Path> roots = Arrays.asList(PROJECT_A, PROJECT_A_SUB);
		manager.registerDiscoveredProjects(roots);

		List<ProjectScope> scopes = manager.getProjectScopes();
		Assertions.assertEquals(2, scopes.size());
		// Longest path first
		Assertions.assertEquals(PROJECT_A_SUB, scopes.get(0).getProjectRoot());
		Assertions.assertEquals(PROJECT_A, scopes.get(1).getProjectRoot());
	}

	@Test
	void testRegisterDiscoveredProjectsNotClasspathResolved() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));

		ProjectScope scope = manager.getProjectScopes().get(0);
		Assertions.assertFalse(scope.isClasspathResolved());
	}

	@Test
	void testRegisterDiscoveredProjectsInvalidatesWorkspaceLocalClasspathIndexCache() throws Exception {
		SharedClasspathIndexCache cache = SharedClasspathIndexCache.getInstance();
		cache.clear();

		Path projectRoot = Files.createTempDirectory("gls-cache-invalidation-");
		Path classesDir = projectRoot.resolve("build/classes");
		Files.createDirectories(classesDir);

		GroovyClassLoader classLoader = new GroovyClassLoader(
				ClassLoader.getSystemClassLoader().getParent(),
				new CompilerConfiguration(),
				true);
		classLoader.addClasspath(classesDir.toString());

		SharedClasspathIndexCache.AcquireResult first = cache.acquireWithResult(classLoader);
		Assertions.assertNotNull(first);
		Assertions.assertNotNull(first.getIndex());

		manager.registerDiscoveredProjects(Arrays.asList(projectRoot));

		SharedClasspathIndexCache.AcquireResult second = cache.acquireWithResult(classLoader);
		Assertions.assertNotNull(second);
		Assertions.assertNotNull(second.getIndex());
		Assertions.assertNotSame(first.getIndex(), second.getIndex(),
				"Workspace project registration should evict stale shared classpath indexes");

		classLoader.close();
		cache.clear();
	}

	// --- findProjectScope (longest-prefix match) ---

	@Test
	void testFindProjectScopeLongestPrefixMatch() {
		List<Path> roots = Arrays.asList(PROJECT_A, PROJECT_A_SUB);
		manager.registerDiscoveredProjects(roots);

		// A file inside the submodule should match PROJECT_A_SUB, not PROJECT_A
		URI subFile = PROJECT_A_SUB.resolve("src/Foo.groovy").toUri();
		ProjectScope scope = manager.findProjectScope(subFile);
		Assertions.assertNotNull(scope);
		Assertions.assertEquals(PROJECT_A_SUB, scope.getProjectRoot());
	}

	@Test
	void testFindProjectScopeMatchesParent() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A, PROJECT_B));

		URI fileInA = PROJECT_A.resolve("src/Main.groovy").toUri();
		ProjectScope scope = manager.findProjectScope(fileInA);
		Assertions.assertNotNull(scope);
		Assertions.assertEquals(PROJECT_A, scope.getProjectRoot());
	}

	@Test
	void testFindProjectScopeReturnsNullForUnmatchedFile() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));

		URI outsideFile = Paths.get("/other/project/Foo.groovy").toAbsolutePath().toUri();
		ProjectScope scope = manager.findProjectScope(outsideFile);
		Assertions.assertNull(scope);
	}

	// --- findProjectScopeByRoot ---

	@Test
	void testFindProjectScopeByRootFound() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A, PROJECT_B));

		ProjectScope scope = manager.findProjectScopeByRoot(PROJECT_A);
		Assertions.assertNotNull(scope);
		Assertions.assertEquals(PROJECT_A, scope.getProjectRoot());
	}

	@Test
	void testFindProjectScopeByRootNotFound() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));

		ProjectScope scope = manager.findProjectScopeByRoot(PROJECT_B);
		Assertions.assertNull(scope);
	}

	// --- getAllScopes ---

	@Test
	void testGetAllScopesReturnsProjectScopesWhenRegistered() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A, PROJECT_B));

		List<ProjectScope> all = manager.getAllScopes();
		Assertions.assertEquals(2, all.size());
	}

	@Test
	void testGetAllScopesReturnsDefaultWhenNoProjects() {
		List<ProjectScope> all = manager.getAllScopes();
		Assertions.assertEquals(1, all.size());
		Assertions.assertSame(manager.getDefaultScope(), all.get(0));
	}

	// --- importInProgress / isImportPendingFor ---

	@Test
	void testImportInProgressDefaultFalse() {
		Assertions.assertFalse(manager.isImportInProgress());
	}

	@Test
	void testIsImportPendingForDefaultScope() {
		manager.setImportInProgress(true);
		Assertions.assertTrue(manager.isImportPendingFor(manager.getDefaultScope()));
	}

	@Test
	void testIsImportPendingNotBlockingProjectScopes() {
		manager.setImportInProgress(true);
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));

		ProjectScope projectScope = manager.getProjectScopes().get(0);
		Assertions.assertFalse(manager.isImportPendingFor(projectScope));
	}

	@Test
	void testIsImportPendingFalseWhenImportNotInProgress() {
		Assertions.assertFalse(manager.isImportPendingFor(manager.getDefaultScope()));
	}

	// --- Resolution tracking ---

	@Test
	void testMarkResolutionStartedReturnsTrue() {
		Assertions.assertTrue(manager.markResolutionStarted(PROJECT_A));
	}

	@Test
	void testMarkResolutionStartedReturnsFalseOnDuplicate() {
		manager.markResolutionStarted(PROJECT_A);
		Assertions.assertFalse(manager.markResolutionStarted(PROJECT_A));
	}

	@Test
	void testMarkResolutionComplete() {
		manager.markResolutionStarted(PROJECT_A);
		Assertions.assertTrue(manager.isResolutionInFlight(PROJECT_A));

		manager.markResolutionComplete(PROJECT_A);
		Assertions.assertFalse(manager.isResolutionInFlight(PROJECT_A));
	}

	@Test
	void testResolutionInFlightIndependentPerProject() {
		manager.markResolutionStarted(PROJECT_A);
		Assertions.assertTrue(manager.isResolutionInFlight(PROJECT_A));
		Assertions.assertFalse(manager.isResolutionInFlight(PROJECT_B));
	}

	// --- addProjects ---

	@Test
	void testAddProjectsRegistersWithClasspath() {
		Map<Path, List<String>> classpaths = new HashMap<>();
		classpaths.put(PROJECT_A, Arrays.asList("/lib/a.jar"));
		classpaths.put(PROJECT_B, Arrays.asList("/lib/b.jar"));

		Set<URI> openUris = manager.addProjects(classpaths);
		Assertions.assertNotNull(openUris);

		List<ProjectScope> scopes = manager.getProjectScopes();
		Assertions.assertEquals(2, scopes.size());

		// Each scope should have its classpath resolved
		for (ProjectScope scope : scopes) {
			Assertions.assertTrue(scope.isClasspathResolved());
		}
	}

	@Test
	void testAddProjectsSortedByPathLength() {
		Map<Path, List<String>> classpaths = new HashMap<>();
		classpaths.put(PROJECT_A, Collections.emptyList());
		classpaths.put(PROJECT_A_SUB, Collections.emptyList());

		manager.addProjects(classpaths);

		List<ProjectScope> scopes = manager.getProjectScopes();
		// Longest-prefix first for correct lookup
		Assertions.assertEquals(PROJECT_A_SUB, scopes.get(0).getProjectRoot());
		Assertions.assertEquals(PROJECT_A, scopes.get(1).getProjectRoot());
	}

	// --- updateProjectClasspaths ---

	@Test
	void testUpdateProjectClasspaths() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A, PROJECT_B));

		Map<Path, List<String>> classpaths = new HashMap<>();
		classpaths.put(PROJECT_A, Arrays.asList("/lib/dep1.jar", "/lib/dep2.jar"));

		manager.updateProjectClasspaths(classpaths);

		ProjectScope scopeA = manager.findProjectScopeByRoot(PROJECT_A);
		Assertions.assertNotNull(scopeA);
		Assertions.assertTrue(scopeA.isClasspathResolved());

		// PROJECT_B should remain unresolved
		ProjectScope scopeB = manager.findProjectScopeByRoot(PROJECT_B);
		Assertions.assertNotNull(scopeB);
		Assertions.assertFalse(scopeB.isClasspathResolved());
	}

	// --- updateProjectClasspath (single) ---

	@Test
	void testUpdateProjectClasspathSingle() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));

		ProjectScope result = manager.updateProjectClasspath(PROJECT_A, Arrays.asList("/lib/x.jar"));
		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isClasspathResolved());
	}

	@Test
	void testUpdateProjectClasspathReturnsNullForUnknownRoot() {
		manager.registerDiscoveredProjects(Arrays.asList(PROJECT_A));

		ProjectScope result = manager.updateProjectClasspath(PROJECT_B, Arrays.asList("/lib/x.jar"));
		Assertions.assertNull(result);
	}

	// --- updateClasspath (default scope) ---

	@Test
	void testUpdateClasspathOnDefaultScope() {
		List<String> classpath = Arrays.asList("/lib/dep.jar");
		manager.updateClasspath(classpath);

		Assertions.assertEquals(classpath,
				manager.getDefaultScope().getCompilationUnitFactory().getAdditionalClasspathList());
	}

	@Test
	void testUpdateClasspathIgnoredWhenProjectScopesExist() {
		Map<Path, List<String>> classpaths = new HashMap<>();
		classpaths.put(PROJECT_A, Collections.emptyList());
		manager.addProjects(classpaths);

		// This should be ignored since project scopes are active
		manager.updateClasspath(Arrays.asList("/lib/ignored.jar"));
	}

	// --- updateClasspathFromSettings ---

	@Test
	void testUpdateClasspathFromSettings() {
		JsonObject settings = new JsonObject();
		JsonObject groovy = new JsonObject();
		com.google.gson.JsonArray cp = new com.google.gson.JsonArray();
		cp.add("/lib/from-settings.jar");
		groovy.add("classpath", cp);
		settings.add("groovy", groovy);

		manager.updateClasspathFromSettings(settings);

		List<String> result = manager.getDefaultScope().getCompilationUnitFactory().getAdditionalClasspathList();
		Assertions.assertNotNull(result);
		Assertions.assertEquals(1, result.size());
		Assertions.assertEquals("/lib/from-settings.jar", result.get(0));
	}

	@Test
	void testUpdateClasspathFromSettingsEmptyWhenNoGroovyKey() {
		JsonObject settings = new JsonObject();
		manager.updateClasspathFromSettings(settings);
		// Should not throw; default classpath unchanged or set to empty
	}

	// --- hasClasspathChanged ---

	@Test
	void testHasClasspathChangedTrue() {
		Assertions.assertTrue(manager.hasClasspathChanged(Arrays.asList("/new.jar")));
	}

	@Test
	void testHasClasspathChangedFalse() {
		List<String> classpath = Arrays.asList("/lib/same.jar");
		manager.updateClasspath(classpath);
		Assertions.assertFalse(manager.hasClasspathChanged(classpath));
	}
}
