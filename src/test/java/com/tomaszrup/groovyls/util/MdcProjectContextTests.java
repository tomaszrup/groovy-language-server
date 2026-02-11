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
package com.tomaszrup.groovyls.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link MdcProjectContext}: verifies MDC key management,
 * workspace-relative path computation, cross-thread propagation via
 * {@link MdcProjectContext#wrap(Runnable)}, and snapshot/restore
 * round-tripping.
 */
class MdcProjectContextTests {

	@BeforeEach
	void setup() {
		MDC.clear();
		MdcProjectContext.setWorkspaceRoot(null);
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
		MdcProjectContext.setWorkspaceRoot(null);
	}

	// ------------------------------------------------------------------
	// setProject() — basic behaviour
	// ------------------------------------------------------------------

	@Test
	void testSetProjectWithNullSetsDefault() {
		MdcProjectContext.setProject(null);
		Assertions.assertEquals("default", MDC.get(MdcProjectContext.MDC_KEY),
				"null project root should set MDC to 'default'");
	}

	@Test
	void testSetProjectWithoutWorkspaceRootUsesDirectoryName() {
		// No workspace root set — should fall back to directory name
		Path projectRoot = Paths.get("/home/user/workspace/my-project");
		MdcProjectContext.setProject(projectRoot);

		Assertions.assertEquals("my-project", MDC.get(MdcProjectContext.MDC_KEY),
				"Without workspace root, should use directory name");
	}

	@Test
	void testSetProjectWithWorkspaceRootUsesRelativePath() {
		Path workspaceRoot = Paths.get("/home/user/workspace");
		MdcProjectContext.setWorkspaceRoot(workspaceRoot);

		Path projectRoot = Paths.get("/home/user/workspace/apps/my-service");
		MdcProjectContext.setProject(projectRoot);

		String value = MDC.get(MdcProjectContext.MDC_KEY);
		// On Windows the path separator will be normalised to /
		Assertions.assertTrue(
				"apps/my-service".equals(value) || "apps\\my-service".equals(value),
				"Should use workspace-relative path, got: " + value);
	}

	@Test
	void testSetProjectWhenProjectEqualsWorkspaceRoot() {
		Path root = Paths.get("/home/user/workspace");
		MdcProjectContext.setWorkspaceRoot(root);
		MdcProjectContext.setProject(root);

		String value = MDC.get(MdcProjectContext.MDC_KEY);
		// relativize() produces "" → falls back to fileName
		Assertions.assertEquals("workspace", value,
				"When project equals workspace root, should use directory name");
	}

	@Test
	void testSetProjectOutsideWorkspaceUsesDirectoryName() {
		Path workspaceRoot = Paths.get("/home/user/workspace");
		MdcProjectContext.setWorkspaceRoot(workspaceRoot);

		// Project is outside the workspace
		Path projectRoot = Paths.get("/tmp/external-project");
		MdcProjectContext.setProject(projectRoot);

		Assertions.assertEquals("external-project", MDC.get(MdcProjectContext.MDC_KEY),
				"Project outside workspace should fall back to directory name");
	}

	// ------------------------------------------------------------------
	// clear()
	// ------------------------------------------------------------------

	@Test
	void testClearRemovesMdcKey() {
		MdcProjectContext.setProject(Paths.get("/some/project"));
		Assertions.assertNotNull(MDC.get(MdcProjectContext.MDC_KEY));

		MdcProjectContext.clear();
		Assertions.assertNull(MDC.get(MdcProjectContext.MDC_KEY),
				"clear() should remove the MDC key");
	}

	@Test
	void testClearOnAlreadyClearedIsNoOp() {
		Assertions.assertDoesNotThrow(() -> MdcProjectContext.clear(),
				"clear() on an already-clear MDC should not throw");
		Assertions.assertNull(MDC.get(MdcProjectContext.MDC_KEY));
	}

	// ------------------------------------------------------------------
	// snapshot() / restore()
	// ------------------------------------------------------------------

	@Test
	void testSnapshotCapturesCurrentContext() {
		MdcProjectContext.setProject(Paths.get("/my/project"));
		Map<String, String> snap = MdcProjectContext.snapshot();

		Assertions.assertNotNull(snap, "snapshot() should return non-null when MDC has entries");
		Assertions.assertTrue(snap.containsKey(MdcProjectContext.MDC_KEY),
				"Snapshot should contain the project key");
	}

	@Test
	void testRestoreAppliesSnapshot() {
		MdcProjectContext.setProject(Paths.get("/first/project"));
		Map<String, String> snap = MdcProjectContext.snapshot();

		// Change MDC to something else
		MdcProjectContext.setProject(Paths.get("/second/project"));

		// Restore
		MdcProjectContext.restore(snap);
		Assertions.assertEquals("project", MDC.get(MdcProjectContext.MDC_KEY),
				"restore() should bring back the snapshotted context");
	}

	@Test
	void testRestoreNullClearsMdc() {
		MdcProjectContext.setProject(Paths.get("/my/project"));
		MdcProjectContext.restore(null);

		Assertions.assertNull(MDC.get(MdcProjectContext.MDC_KEY),
				"restore(null) should clear the MDC");
	}

	@Test
	void testSnapshotRestoreRoundTrip() {
		MDC.put(MdcProjectContext.MDC_KEY, "round-trip-value");
		MDC.put("extra-key", "extra-value");

		Map<String, String> snap = MdcProjectContext.snapshot();

		MDC.clear();
		Assertions.assertNull(MDC.get(MdcProjectContext.MDC_KEY));

		MdcProjectContext.restore(snap);
		Assertions.assertEquals("round-trip-value", MDC.get(MdcProjectContext.MDC_KEY));
		Assertions.assertEquals("extra-value", MDC.get("extra-key"),
				"Restored snapshot should include all MDC keys");
	}

	// ------------------------------------------------------------------
	// wrap(Runnable) — cross-thread propagation
	// ------------------------------------------------------------------

	@Test
	void testWrapPropagatesMdcToChildThread() throws Exception {
		MDC.put(MdcProjectContext.MDC_KEY, "wrapped-project");
		Runnable wrapped = MdcProjectContext.wrap(() -> {});
		AtomicReference<String> captured = new AtomicReference<>();

		// Replace the task to capture MDC inside the wrapper
		wrapped = MdcProjectContext.wrap(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
		});

		ExecutorService exec = Executors.newSingleThreadExecutor();
		try {
			Future<?> f = exec.submit(wrapped);
			f.get(5, TimeUnit.SECONDS);
		} finally {
			exec.shutdownNow();
		}

		Assertions.assertEquals("wrapped-project", captured.get(),
				"wrap() should propagate caller's MDC to child thread");
	}

	@Test
	void testWrapRestoresChildThreadMdcAfterTask() throws Exception {
		// Child thread has its own MDC before the wrapped task runs
		MDC.put(MdcProjectContext.MDC_KEY, "caller-context");

		AtomicReference<String> beforeTask = new AtomicReference<>();
		AtomicReference<String> duringTask = new AtomicReference<>();
		AtomicReference<String> afterTask = new AtomicReference<>();

		Runnable wrapped = MdcProjectContext.wrap(() -> {
			duringTask.set(MDC.get(MdcProjectContext.MDC_KEY));
		});

		ExecutorService exec = Executors.newSingleThreadExecutor();
		try {
			// First set child thread's own MDC
			exec.submit(() -> {
				MDC.put(MdcProjectContext.MDC_KEY, "child-own-context");
				beforeTask.set(MDC.get(MdcProjectContext.MDC_KEY));
			}).get(5, TimeUnit.SECONDS);

			// Now run the wrapped task on the same thread
			exec.submit(wrapped).get(5, TimeUnit.SECONDS);

			// Check what the child thread's MDC looks like after
			exec.submit(() -> {
				afterTask.set(MDC.get(MdcProjectContext.MDC_KEY));
			}).get(5, TimeUnit.SECONDS);
		} finally {
			exec.shutdownNow();
		}

		Assertions.assertEquals("child-own-context", beforeTask.get());
		Assertions.assertEquals("caller-context", duringTask.get(),
				"During task, should see caller's MDC");
		Assertions.assertEquals("child-own-context", afterTask.get(),
				"After task, child thread's MDC should be restored");
	}

	@Test
	void testWrapWithNoCallerMdc() throws Exception {
		MDC.clear();
		AtomicReference<String> captured = new AtomicReference<>();

		Runnable wrapped = MdcProjectContext.wrap(() -> {
			captured.set(MDC.get(MdcProjectContext.MDC_KEY));
		});

		ExecutorService exec = Executors.newSingleThreadExecutor();
		try {
			exec.submit(wrapped).get(5, TimeUnit.SECONDS);
		} finally {
			exec.shutdownNow();
		}

		Assertions.assertNull(captured.get(),
				"When caller has no MDC, child should see null");
	}

	// ------------------------------------------------------------------
	// MDC_KEY constant
	// ------------------------------------------------------------------

	@Test
	void testMdcKeyValue() {
		Assertions.assertEquals("project", MdcProjectContext.MDC_KEY,
				"MDC key should be 'project'");
	}
}
