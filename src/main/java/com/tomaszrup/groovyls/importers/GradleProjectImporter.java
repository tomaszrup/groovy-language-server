////////////////////////////////////////////////////////////////////////////////
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
package com.tomaszrup.groovyls.importers;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers and imports Gradle-based JVM projects. Uses the Gradle Tooling API
 * to compile Java sources, resolve dependency classpaths via an injected init
 * script, and discover compiled class output directories.
 */
public class GradleProjectImporter implements ProjectImporter {

    private static final Logger logger = LoggerFactory.getLogger(GradleProjectImporter.class);

    @Override
    public String getName() {
        return "Gradle";
    }

    @Override
    public List<Path> discoverProjects(Path workspaceRoot) throws IOException {
        List<Path> gradleProjects = new ArrayList<>();
        try (Stream<Path> fileStream = Files.walk(workspaceRoot)) {
            fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> Set.of("build.gradle", "build.gradle.kts").contains(p.getFileName().toString()))
                    .map(buildFile -> buildFile.getParent())
                    .filter(this::isJvmProject)
                    .forEach(gradleProjects::add);
        }
        return gradleProjects;
    }

    @Override
    public List<String> importProject(Path projectRoot) {
        List<String> classpathList = new ArrayList<>();
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            // Try to compile both main and test classes, but don't fail if tasks don't exist
            try {
                connection.newBuild()
                        .forTasks("classes", "testClasses")
                        .setStandardOutput(newLogOutputStream())
                        .setStandardError(newLogOutputStream())
                        .run();
            } catch (Exception buildEx) {
                logger.warn("Could not run compile tasks for project {}: {}", projectRoot, buildEx.getMessage());
                // Fallback: try just 'classes'
                try {
                    connection.newBuild()
                            .forTasks("classes")
                            .setStandardOutput(newLogOutputStream())
                            .setStandardError(newLogOutputStream())
                            .run();
                } catch (Exception ex) {
                    logger.warn("Could not run 'classes' task for project {}: {}", projectRoot, ex.getMessage());
                }
            }

            // Resolve all dependency jars from Gradle configurations via init script
            classpathList.addAll(resolveClasspathViaInitScript(connection));

            // Also add discovered class output dirs (build/classes/**)
            classpathList.addAll(discoverClassDirs(projectRoot));

            logger.info("Classpath for project {}: {} entries", projectRoot, classpathList.size());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return classpathList;
    }

    @Override
    public void recompile(Path projectRoot) {
        logger.info("Recompiling Gradle project: {}", projectRoot);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            try {
                connection.newBuild()
                        .forTasks("classes", "testClasses")
                        .setStandardOutput(newLogOutputStream())
                        .setStandardError(newLogOutputStream())
                        .run();
                logger.info("Gradle recompile succeeded for {}", projectRoot);
            } catch (Exception buildEx) {
                logger.warn("Gradle recompile failed for {}: {}", projectRoot, buildEx.getMessage());
                try {
                    connection.newBuild()
                            .forTasks("classes")
                            .setStandardOutput(newLogOutputStream())
                            .setStandardError(newLogOutputStream())
                            .run();
                } catch (Exception ex) {
                    logger.warn("Gradle recompile fallback also failed for {}: {}", projectRoot, ex.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Could not connect to Gradle for recompile of {}: {}", projectRoot, e.getMessage());
        }
    }

    @Override
    public boolean isProjectFile(String filePath) {
        return filePath != null
                && (filePath.endsWith("build.gradle") || filePath.endsWith("build.gradle.kts"));
    }

    // ---- private helpers ----

    private boolean isJvmProject(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/groovy"))
                || Files.isDirectory(projectDir.resolve("src/test/java"))
                || Files.isDirectory(projectDir.resolve("src/test/groovy"));
    }

    private List<String> resolveClasspathViaInitScript(ProjectConnection connection) {
        List<String> classpathEntries = new ArrayList<>();
        Path initScript = null;
        try {
            initScript = Files.createTempFile("groovyls-init", ".gradle");
            String initScriptContent =
                "allprojects {\n" +
                "    tasks.register('_groovyLSResolveClasspath') {\n" +
                "        doLast {\n" +
                "            ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'].each { configName ->\n" +
                "                def config = configurations.findByName(configName)\n" +
                "                if (config != null && config.canBeResolved) {\n" +
                "                    try {\n" +
                "                        config.files.each { f ->\n" +
                "                            println \"GROOVYLS_CP:${f.absolutePath}\"\n" +
                "                        }\n" +
                "                    } catch (Exception e) {\n" +
                "                        // skip unresolvable configurations\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            Files.write(initScript, initScriptContent.getBytes("UTF-8"));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            connection.newBuild()
                    .forTasks("_groovyLSResolveClasspath")
                    .withArguments("--init-script", initScript.toString())
                    .setStandardOutput(baos)
                    .setStandardError(newLogOutputStream())
                    .run();

            String output = baos.toString("UTF-8");
            for (String line : output.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("GROOVYLS_CP:")) {
                    String path = line.substring("GROOVYLS_CP:".length());
                    if (new File(path).exists()) {
                        classpathEntries.add(path);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not resolve classpath via init script: {}", e.getMessage());
        } finally {
            if (initScript != null) {
                try {
                    Files.deleteIfExists(initScript);
                } catch (IOException ignored) {
                }
            }
        }
        return classpathEntries;
    }

    private List<String> discoverClassDirs(Path projectDir) throws IOException {
        Path classesRoot = projectDir.resolve("build/classes");
        List<String> classDirs = new ArrayList<>();

        if (Files.exists(classesRoot)) {
            List<Path> allDirs = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(classesRoot, 2)) {
                stream.filter(Files::isDirectory).forEach(allDirs::add);
            }
            // Only keep leaf directories — skip parents that contain subdirectories
            for (Path dir : allDirs) {
                boolean isLeaf = allDirs.stream()
                        .noneMatch(other -> !other.equals(dir) && other.startsWith(dir));
                if (isLeaf) {
                    classDirs.add(dir.toString());
                }
            }
        }
        return classDirs;
    }

    /**
     * Creates an OutputStream that routes each line of output to the SLF4J
     * logger at DEBUG level, instead of dumping to System.out/System.err.
     */
    private OutputStream newLogOutputStream() {
        return new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                if (b == '\n') {
                    flush();
                } else {
                    buffer.write(b);
                }
            }

            @Override
            public void write(byte[] bytes, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    write(bytes[i]);
                }
            }

            @Override
            public void flush() {
                String line = buffer.toString(StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    logger.debug("[Gradle] {}", line);
                }
                buffer.reset();
            }

            @Override
            public void close() {
                flush();
            }
        };
    }
}
