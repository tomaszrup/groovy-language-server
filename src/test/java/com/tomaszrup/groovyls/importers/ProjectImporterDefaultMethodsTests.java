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
package com.tomaszrup.groovyls.importers;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ProjectImporterDefaultMethodsTests {

	private static class StubImporter implements ProjectImporter {
		private final Map<Path, List<String>> classpathsByRoot = new HashMap<>();
		private final List<Path> recompiledRoots = new ArrayList<>();
		private final List<Path> importedRoots = new ArrayList<>();
		private boolean failOnRecompile = false;

		@Override
		public String getName() {
			return "Stub";
		}

		@Override
		public List<Path> discoverProjects(Path workspaceRoot) {
			return Collections.emptyList();
		}

		@Override
		public List<String> importProject(Path projectRoot) {
			importedRoots.add(projectRoot);
			return classpathsByRoot.getOrDefault(projectRoot, Collections.emptyList());
		}

		@Override
		public void recompile(Path projectRoot) {
			recompiledRoots.add(projectRoot);
			if (failOnRecompile) {
				throw new RuntimeException("boom");
			}
		}

		@Override
		public boolean isProjectFile(String filePath) {
			return false;
		}

		@Override
		public boolean claimsProject(Path projectRoot) {
			return true;
		}
	}

	@Test
	void testImportProjectsDefaultCallsImportProjectForEachRoot() {
		StubImporter importer = new StubImporter();
		Path rootA = Paths.get("/workspace/a");
		Path rootB = Paths.get("/workspace/b");
		importer.classpathsByRoot.put(rootA, Arrays.asList("/a.jar"));
		importer.classpathsByRoot.put(rootB, Arrays.asList("/b.jar"));

		Map<Path, List<String>> result = importer.importProjects(Arrays.asList(rootA, rootB));

		Assertions.assertEquals(2, importer.importedRoots.size());
		Assertions.assertEquals(Arrays.asList(rootA, rootB), importer.importedRoots);
		Assertions.assertEquals(Arrays.asList("/a.jar"), result.get(rootA));
		Assertions.assertEquals(Arrays.asList("/b.jar"), result.get(rootB));
	}

	@Test
	void testResolveClasspathsDefaultDelegatesToImportProjects() {
		StubImporter importer = new StubImporter();
		Path root = Paths.get("/workspace/a");
		importer.classpathsByRoot.put(root, Arrays.asList("/dep.jar"));

		Map<Path, List<String>> result = importer.resolveClasspaths(Collections.singletonList(root));

		Assertions.assertEquals(Arrays.asList(root), importer.importedRoots);
		Assertions.assertEquals(Arrays.asList("/dep.jar"), result.get(root));
	}

	@Test
	void testResolveClasspathDefaultReturnsClasspathForSingleRoot() {
		StubImporter importer = new StubImporter();
		Path root = Paths.get("/workspace/a");
		importer.classpathsByRoot.put(root, Arrays.asList("/a.jar", "/b.jar"));

		List<String> classpath = importer.resolveClasspath(root);

		Assertions.assertEquals(Arrays.asList("/a.jar", "/b.jar"), classpath);
	}

	@Test
	void testResolveClasspathDefaultReturnsEmptyWhenRootMissingInMap() {
		ProjectImporter importer = new StubImporter() {
			@Override
			public Map<Path, List<String>> resolveClasspaths(List<Path> projectRoots) {
				return Collections.emptyMap();
			}
		};

		List<String> classpath = importer.resolveClasspath(Paths.get("/workspace/missing"));

		Assertions.assertTrue(classpath.isEmpty());
	}

	@Test
	void testNoOpDefaultMethodsDoNotThrow() {
		ProjectImporter importer = new StubImporter();

		Assertions.assertDoesNotThrow(() -> importer.setWorkspaceBound(Paths.get("/workspace")));
		Assertions.assertDoesNotThrow(() -> importer.setWorkspaceBounds(
				Arrays.asList(Paths.get("/workspace/a"), Paths.get("/workspace/b"))));
		Assertions.assertDoesNotThrow(() -> importer.setWorkspaceBounds(null));
		Assertions.assertDoesNotThrow(() -> importer.setWorkspaceBounds(Collections.emptyList()));
		Assertions.assertDoesNotThrow(() -> importer.applySettings(new JsonObject()));
		Assertions.assertDoesNotThrow(() -> importer.downloadSourceJarsAsync(Paths.get("/workspace/a")));
	}

	@Test
	void testDefaultBuildToolRootAndSiblingBatching() {
		ProjectImporter importer = new StubImporter();
		Path root = Paths.get("/workspace/a");

		Assertions.assertEquals(root, importer.getBuildToolRoot(root));
		Assertions.assertFalse(importer.supportsSiblingBatching());
	}

	@Test
	void testResolveClasspathsForRootDefaultDelegatesToResolveClasspaths() {
		StubImporter importer = new StubImporter();
		Path rootA = Paths.get("/workspace/a");
		Path rootB = Paths.get("/workspace/b");
		importer.classpathsByRoot.put(rootA, Arrays.asList("/a.jar"));
		importer.classpathsByRoot.put(rootB, Arrays.asList("/b.jar"));

		Map<Path, List<String>> result = importer.resolveClasspathsForRoot(Paths.get("/workspace"), Arrays.asList(rootA, rootB));

		Assertions.assertEquals(Arrays.asList(rootA, rootB), importer.importedRoots);
		Assertions.assertEquals(Arrays.asList("/a.jar"), result.get(rootA));
		Assertions.assertEquals(Arrays.asList("/b.jar"), result.get(rootB));
	}

	@Test
	void testCompileSourcesDefaultCallsRecompileAndSwallowsFailures() {
		StubImporter importer = new StubImporter();
		importer.failOnRecompile = true;
		Path rootA = Paths.get("/workspace/a");
		Path rootB = Paths.get("/workspace/b");

		Assertions.assertDoesNotThrow(() -> importer.compileSources(Arrays.asList(rootA, rootB)));
		Assertions.assertEquals(Arrays.asList(rootA, rootB), importer.recompiledRoots);
	}

	@Test
	void testDetectProjectGroovyVersionDefaultFromClasspath() {
		ProjectImporter importer = new StubImporter();
		Optional<String> detected = importer.detectProjectGroovyVersion(
				Paths.get("/workspace/a"),
				Collections.singletonList("/repo/org/apache/groovy/groovy/5.0.4/groovy-5.0.4.jar"));

		Assertions.assertTrue(detected.isPresent());
		Assertions.assertEquals("5.0.4", detected.get());
	}

	@Test
	void testShouldMarkClasspathResolvedDefaultTrue() {
		ProjectImporter importer = new StubImporter();

		Assertions.assertTrue(importer.shouldMarkClasspathResolved(
				Paths.get("/workspace/a"),
				Collections.singletonList("/dep.jar")));
	}
}
