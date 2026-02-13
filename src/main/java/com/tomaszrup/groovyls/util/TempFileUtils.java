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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Utility for creating temp files in a private, user-owned directory
 * ({@code ~/.groovyls/tmp/}) instead of the system temp directory.
 *
 * <p>On POSIX systems the directory is created with {@code rwx------} (700)
 * permissions, preventing other users from reading or replacing the temp files.
 * On Windows, {@link PosixFilePermissions} is not supported, so the directory
 * inherits the default ACL of the user's home directory, which is typically
 * restricted to the current user.</p>
 */
public final class TempFileUtils {

    private static final Logger logger = LoggerFactory.getLogger(TempFileUtils.class);

    private static volatile Path privateTmpDir;

    private TempFileUtils() {
        // utility class
    }

    /**
     * Create a temp file in the private {@code ~/.groovyls/tmp/} directory.
     *
     * @param prefix filename prefix (e.g. {@code "groovyls-init"})
     * @param suffix filename suffix (e.g. {@code ".gradle"})
     * @return path to the newly created temp file
     * @throws IOException if the file cannot be created
     */
    public static Path createSecureTempFile(String prefix, String suffix) throws IOException {
        Path dir = getPrivateTmpDir();
        return Files.createTempFile(dir, prefix, suffix);
    }

    private static Path getPrivateTmpDir() throws IOException {
        Path dir = privateTmpDir;
        if (dir != null && Files.isDirectory(dir)) {
            return dir;
        }
        synchronized (TempFileUtils.class) {
            dir = privateTmpDir;
            if (dir != null && Files.isDirectory(dir)) {
                return dir;
            }
            dir = Paths.get(System.getProperty("user.home"), ".groovyls", "tmp");
            if (!Files.isDirectory(dir)) {
                try {
                    // Attempt POSIX 700 permissions (owner-only)
                    FileAttribute<?> ownerOnly = PosixFilePermissions
                            .asFileAttribute(PosixFilePermissions.fromString("rwx------"));
                    Files.createDirectories(dir, ownerOnly);
                } catch (UnsupportedOperationException e) {
                    // Windows — POSIX attributes not supported; fall back to
                    // default ACL which inherits from the user's home directory.
                    Files.createDirectories(dir);
                }
                logger.debug("Created private temp directory: {}", dir);
            }
            privateTmpDir = dir;
            return dir;
        }
    }
}
