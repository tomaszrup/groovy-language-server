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
package com.tomaszrup.groovyls;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper to clean up the shared {@code build/test_workspace} directory between
 * test runs. All {@code GroovyServices*Tests} classes should call
 * {@link #cleanSrcDirectory(Path)} in their {@code @AfterEach} teardown to
 * prevent leftover Groovy files from leaking state across tests.
 */
public final class TestWorkspaceHelper {
    private static final Logger logger = LoggerFactory.getLogger(TestWorkspaceHelper.class);

    private TestWorkspaceHelper() {
    }

    /**
     * Delete all {@code .groovy} files directly inside {@code srcRoot}
     * (non-recursive) so that the next test starts with a clean workspace.
     *
     * @param srcRoot the source root directory, e.g. {@code build/test_workspace/src/main/groovy}
     */
    public static void cleanSrcDirectory(Path srcRoot) {
        if (srcRoot == null || !Files.isDirectory(srcRoot)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(srcRoot, "*.groovy")) {
            for (Path entry : stream) {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    logger.warn("Failed to clean up test file: {}", entry, e);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to list test workspace files in: {}", srcRoot, e);
        }
    }
}
