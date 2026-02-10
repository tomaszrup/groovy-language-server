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
package com.tomaszrup.groovyls.util;

import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.Map;

/**
 * Manages the SLF4J MDC (Mapped Diagnostic Context) key {@code "project"} so
 * that every log line automatically includes a short project identifier.
 *
 * <p>The project identifier is computed as the workspace-relative path of the
 * project root (e.g. {@code apps/my-service} instead of
 * {@code /home/user/workspace/apps/my-service}), making logs in multi-project
 * workspaces easy to filter by project.</p>
 *
 * <h3>Usage at entry points (LSP handlers, thread pool tasks):</h3>
 * <pre>{@code
 * MdcProjectContext.setProject(scope.getProjectRoot());
 * try {
 *     // ... all log calls inside here will include [project-name]
 * } finally {
 *     MdcProjectContext.clear();
 * }
 * }</pre>
 *
 * <h3>MDC propagation across threads:</h3>
 * <p>Use {@link #wrap(Runnable)} to capture the current MDC context and
 * restore it in the target thread:</p>
 * <pre>{@code
 * executor.submit(MdcProjectContext.wrap(() -> doWork()));
 * }</pre>
 */
public final class MdcProjectContext {

    /** MDC key used in the logback pattern via {@code %X{project}}. */
    public static final String MDC_KEY = "project";

    /**
     * Workspace root path, set once during server initialization.
     * Used to compute relative project paths.
     */
    private static volatile Path workspaceRoot;

    private MdcProjectContext() {
        // utility class
    }

    /**
     * Sets the workspace root path. Should be called once during
     * {@code initialize()} before any project context is set.
     *
     * @param root the workspace root path
     */
    public static void setWorkspaceRoot(Path root) {
        workspaceRoot = root;
    }

    /**
     * Sets the MDC {@code "project"} key to the workspace-relative path
     * of the given project root. If the workspace root is not set or the
     * project root is null, uses the project root's file name or "unknown".
     *
     * @param projectRoot the project's root path
     */
    public static void setProject(Path projectRoot) {
        if (projectRoot == null) {
            MDC.put(MDC_KEY, "default");
            return;
        }

        Path wsRoot = workspaceRoot;
        if (wsRoot != null && projectRoot.startsWith(wsRoot)) {
            Path relative = wsRoot.relativize(projectRoot);
            String label = relative.toString().replace('\\', '/');
            MDC.put(MDC_KEY, label.isEmpty() ? projectRoot.getFileName().toString() : label);
        } else {
            // Fallback: use just the directory name
            Path fileName = projectRoot.getFileName();
            MDC.put(MDC_KEY, fileName != null ? fileName.toString() : projectRoot.toString());
        }
    }

    /**
     * Removes the MDC {@code "project"} key from the current thread.
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * Returns a snapshot of the current thread's MDC context map.
     * Used to propagate context across thread boundaries.
     *
     * @return the current MDC context map, or null if empty
     */
    public static Map<String, String> snapshot() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * Restores a previously captured MDC context map on the current thread.
     *
     * @param contextMap the context map to restore (may be null)
     */
    public static void restore(Map<String, String> contextMap) {
        if (contextMap != null) {
            MDC.setContextMap(contextMap);
        } else {
            MDC.clear();
        }
    }

    /**
     * Wraps a {@link Runnable} so that the current thread's MDC context is
     * captured and restored in the executing thread. After the task completes,
     * the executing thread's MDC is restored to its previous state.
     *
     * @param task the task to wrap
     * @return a new Runnable that propagates MDC context
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> callerContext = snapshot();
        return () -> {
            Map<String, String> previousContext = snapshot();
            restore(callerContext);
            try {
                task.run();
            } finally {
                restore(previousContext);
            }
        };
    }
}
