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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.tomaszrup.groovyls.config.CompilationUnitFactory;

/**
 * Unit tests for {@link ProjectScope}: construction, initial state, and
 * source locator classpath updates.
 */
class ProjectScopeTests {

	private static final Path PROJECT_ROOT = Paths.get("/workspace/project").toAbsolutePath();

	// --- Construction ---

	@Test
	void testConstructorWithProjectRoot() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertEquals(PROJECT_ROOT, scope.getProjectRoot());
		Assertions.assertSame(factory, scope.getCompilationUnitFactory());
		Assertions.assertNotNull(scope.getJavaSourceLocator());
		Assertions.assertNotNull(scope.getDependencyGraph());
		Assertions.assertNotNull(scope.getLock());
	}

	@Test
	void testConstructorWithNullProjectRoot() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(null, factory);

		Assertions.assertNull(scope.getProjectRoot());
		Assertions.assertNotNull(scope.getJavaSourceLocator());
	}

	// --- Initial state ---

	@Test
	void testInitialCompiledFalse() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertFalse(scope.isCompiled());
	}

	@Test
	void testInitialClasspathResolvedFalse() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertFalse(scope.isClasspathResolved());
	}

	@Test
	void testInitialAstVisitorNull() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertNull(scope.getAstVisitor());
	}

	@Test
	void testInitialCompilationUnitNull() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertNull(scope.getCompilationUnit());
	}

	@Test
	void testInitialPreviousContextNull() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertNull(scope.getPreviousContext());
	}

	@Test
	void testDependencyGraphInitiallyEmpty() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertTrue(scope.getDependencyGraph().isEmpty());
	}

	// --- updateSourceLocatorClasspath ---

	@Test
	void testUpdateSourceLocatorClasspathWithEntries() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		List<String> classpath = Arrays.asList("/lib/dep1.jar", "/lib/dep2.jar");
		Assertions.assertDoesNotThrow(() -> scope.updateSourceLocatorClasspath(classpath));
		Assertions.assertNotNull(scope);
	}

	@Test
	void testUpdateSourceLocatorClasspathWithNull() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertDoesNotThrow(() -> scope.updateSourceLocatorClasspath(null));
		Assertions.assertNotNull(scope);
	}

	@Test
	void testUpdateSourceLocatorClasspathWithEmptyList() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		Assertions.assertDoesNotThrow(() -> scope.updateSourceLocatorClasspath(Collections.emptyList()));
		Assertions.assertNotNull(scope);
	}

	// --- Mutable state ---

	@Test
	void testCompiledFlagCanBeSet() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		scope.setCompiled(true);
		Assertions.assertTrue(scope.isCompiled());
	}

	@Test
	void testClasspathResolvedFlagCanBeSet() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);

		scope.setClasspathResolved(true);
		Assertions.assertTrue(scope.isClasspathResolved());
	}

	// --- Lock ---

	@Test
	void testReadWriteLockAccessible() {
		CompilationUnitFactory factory = new CompilationUnitFactory();
		ProjectScope scope = new ProjectScope(PROJECT_ROOT, factory);
		boolean readLocked = false;
		boolean writeLocked = false;

		scope.getLock().readLock().lock();
		try {
			readLocked = true;
		} finally {
			scope.getLock().readLock().unlock();
		}

		scope.getLock().writeLock().lock();
		try {
			writeLocked = true;
		} finally {
			scope.getLock().writeLock().unlock();
		}

		Assertions.assertTrue(readLocked);
		Assertions.assertTrue(writeLocked);
	}
}
