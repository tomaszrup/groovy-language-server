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
package com.tomaszrup.groovyls.importers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.tomaszrup.groovyls.util.TempFileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.tomaszrup.groovyls.util.GroovyVersionDetector;

/**
 * Discovers and imports Maven-based JVM projects. Shells out to the {@code mvn}
 * command to compile Java sources and resolve the dependency classpath via
 * {@code dependency:build-classpath}.
 *
 * <p>Maven projects are imported in parallel (up to the number of available
 * CPU cores) since they are typically independent of each other.</p>
 */
public class MavenProjectImporter implements ProjectImporter {

    private static final Logger logger = LoggerFactory.getLogger(MavenProjectImporter.class);

    /** Optional override for the Maven home directory (set from VS Code setting). */
    private String mavenHome;

    /**
     * Optional shared import pool for parallel Maven operations.
     * If set, {@link #doImportProjects} uses this pool instead of creating
     * a new throw-away pool per call.
     */
    private volatile ExecutorService sharedImportPool;

    private static final Pattern PROPERTY_REF = Pattern.compile("^\\$\\{([^}]+)}$");
    private static final int WRAPPER_HEADER_SCAN_BYTES = 8192;

    public void setMavenHome(String mavenHome) {
        this.mavenHome = mavenHome;
    }

    /**
     * Set the shared import pool for parallel Maven operations.
     * When set, {@link #doImportProjects} submits tasks to this pool
     * instead of creating a new thread pool per invocation.
     */
    public void setImportPool(ExecutorService pool) {
        this.sharedImportPool = pool;
    }

    @Override
    public String getName() {
        return "Maven";
    }

    @Override
    public List<Path> discoverProjects(Path workspaceRoot) throws IOException {
        // Delegate to the unified discovery with directory pruning
        ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(
                workspaceRoot, Set.of("Maven"));
        return result.mavenProjects;
    }

    @Override
    public List<String> importProject(Path projectRoot) {
        List<String> classpathList = new ArrayList<>();

        // 1. Compile Java sources
        compileProject(projectRoot);

        // 2. Resolve dependency classpath via mvn dependency:build-classpath
        classpathList.addAll(resolveClasspathInternal(projectRoot));

        // 3. Add compiled class output directories
        classpathList.addAll(discoverClassDirs(projectRoot));

        logger.info("Classpath for Maven project {}: {} entries", projectRoot, classpathList.size());
        return classpathList;
    }

    /**
     * Import all Maven projects in parallel. Each project gets its own
     * {@code mvn} invocation, but they run concurrently since Maven
     * modules are typically independent.
     */
    @Override
    public Map<Path, List<String>> importProjects(List<Path> projectRoots) {
        return doImportProjects(projectRoots, true);
    }

    /**
     * Resolve classpaths for all Maven projects <b>with compilation</b>.
     * Unlike Gradle, Maven's {@code dependency:build-classpath} only reports
     * external dependency JARs — it does <em>not</em> include the project's
     * own compiled output directories ({@code target/classes},
     * {@code target/test-classes}).  Those directories are discovered via
     * {@link #discoverClassDirs}, but they must actually exist on disk.
     * Without compilation, Java classes from {@code src/main/java} are never
     * compiled, so Groovy test files in {@code src/test/groovy} cannot
     * resolve them at analysis time.
     */
    @Override
    public Map<Path, List<String>> resolveClasspaths(List<Path> projectRoots) {
        return doImportProjects(projectRoots, true);
    }

    /**
     * Resolve classpath for a <b>single</b> Maven project, compiling first
     * so that {@code target/classes} and {@code target/test-classes} exist.
     *
     * <p>This is the lazy on-demand path called when the user opens a file
     * in a project whose classpath hasn't been resolved yet.  For Maven,
     * compilation is essential because the dependency resolution
     * ({@code mvn dependency:build-classpath}) only returns external JARs —
     * the project's own compiled output is discovered separately via
     * {@link #discoverClassDirs}, which requires the directories to be
     * populated.</p>
     */
    @Override
    public List<String> resolveClasspath(Path projectRoot) {
        List<String> classpath = new ArrayList<>();

        // Compile so that target/classes and target/test-classes are populated
        compileProject(projectRoot);

        // Resolve external dependency JARs
        classpath.addAll(resolveClasspathInternal(projectRoot));

        // Discover compiled class output directories
        classpath.addAll(discoverClassDirs(projectRoot));

        logger.info("Lazy-resolved classpath for Maven project {}: {} entries",
                projectRoot, classpath.size());
        return classpath;
    }

    private Map<Path, List<String>> doImportProjects(List<Path> projectRoots, boolean compile) {
        Map<Path, List<String>> result = new ConcurrentHashMap<>();
        ExecutorService pool = sharedImportPool;
        boolean ownPool = (pool == null);
        if (ownPool) {
            int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
            pool = Executors.newFixedThreadPool(parallelism, r -> {
                Thread t = new Thread(r, "groovyls-maven-import");
                t.setDaemon(true);
                return t;
            });
        }
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path root : projectRoots) {
                futures.add(pool.submit(() -> {
                    try {
                        List<String> cp = new ArrayList<>();
                        if (compile) {
                            compileProject(root);
                        }
                        cp.addAll(resolveClasspathInternal(root));
                        cp.addAll(discoverClassDirs(root));
                        logger.info("Classpath for Maven project {}: {} entries (compile={})",
                                root, cp.size(), compile);
                        result.put(root, cp);
                    } catch (Exception e) {
                        logger.error("Error importing Maven project {}: {}", root, e.getMessage(), e);
                        result.put(root, new ArrayList<>());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    logger.error("Maven import task failed: {}", e.getCause().getMessage());
                }
            }
        } finally {
            if (ownPool) {
                pool.shutdownNow();
            }
        }
        // Preserve insertion order
        Map<Path, List<String>> ordered = new LinkedHashMap<>();
        for (Path root : projectRoots) {
            List<String> cp = result.get(root);
            if (cp != null) {
                ordered.put(root, cp);
            }
        }
        return ordered;
    }

    @Override
    public void recompile(Path projectRoot) {
        logger.info("Recompiling Maven project: {}", projectRoot);
        compileProject(projectRoot);
    }

    @Override
    public boolean claimsProject(Path projectRoot) {
        return projectRoot != null
                && projectRoot.resolve("pom.xml").toFile().exists();
    }

    @Override
    public void applySettings(JsonObject settings) {
        if (settings != null && settings.has("maven") && settings.get("maven").isJsonObject()) {
            JsonObject maven = settings.get("maven").getAsJsonObject();
            JsonElement homeElem = maven.get("home");
            String home = (homeElem != null && !homeElem.isJsonNull()) ? homeElem.getAsString() : null;
            setMavenHome(home);
        }
    }

    @Override
    public boolean isProjectFile(String filePath) {
        return filePath != null && filePath.endsWith("pom.xml");
    }

    @Override
    public Optional<String> detectProjectGroovyVersion(Path projectRoot, List<String> classpathEntries) {
        Optional<String> fromPom = detectGroovyVersionFromPom(projectRoot);
        if (fromPom.isPresent()) {
            return fromPom;
        }
        return GroovyVersionDetector.detect(classpathEntries);
    }

    @Override
    public boolean shouldMarkClasspathResolved(Path projectRoot, List<String> classpathEntries) {
        if (projectRoot == null || classpathEntries == null || classpathEntries.isEmpty()) {
            logger.warn("Maven classpath for {} is empty — keeping scope unresolved", projectRoot);
            return false;
        }

        if (!containsOnlyTargetClassDirs(projectRoot, classpathEntries)) {
            return true;
        }

        if (hasDeclaredPomDependencies(projectRoot)) {
            logger.warn(
                    "Maven classpath for {} contains only target class dirs and no dependency jars — keeping scope unresolved for retry",
                    projectRoot);
            return false;
        }

        logger.info(
                "Maven classpath for {} contains only target class dirs, but pom.xml has no declared dependencies — treating as resolved",
                projectRoot);
        return true;
    }

    // ---- private helpers ----

    /**
     * Compile both main and test sources. Failures are logged but not fatal —
     * classpath resolution can still succeed even if compilation fails.
     */
    private void compileProject(Path projectRoot) {
        try {
            int exitCode = runMaven(projectRoot, "compile", "test-compile", "-q");
            if (exitCode != 0) {
                logger.warn("Maven compile failed for {} (exit code {}), trying compile only", projectRoot, exitCode);
                int fallbackCode = runMaven(projectRoot, "compile", "-q");
                if (fallbackCode != 0) {
                    logger.warn("Maven compile fallback also failed for {} (exit code {})", projectRoot, fallbackCode);
                }
            } else {
                logger.info("Maven compile succeeded for {}", projectRoot);
            }
        } catch (IOException e) {
            logger.error("Could not run Maven for compile of {}: {}", projectRoot, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Maven compile interrupted for {}", projectRoot);
        }
    }

    /**
     * Resolve the full dependency classpath by running
     * {@code mvn dependency:build-classpath} and writing output to a temp file.
     * Also triggers downloading of source JARs (best-effort) so that
     * "Go to Definition" can show real source instead of decompiled stubs.
     */
    private List<String> resolveClasspathInternal(Path projectRoot) {
        // Best-effort: download source JARs into ~/.m2/repository
        // so findSourceJar() can discover them via sibling convention.
        downloadSourceJars(projectRoot);

        List<String> classpathEntries = new ArrayList<>();
        Path cpFile = null;
        try {
            cpFile = TempFileUtils.createSecureTempFile("groovyls-mvn-cp", ".txt");
            int exitCode = runMaven(projectRoot,
                    "dependency:build-classpath",
                    "-DincludeScope=test",
                    "-Dmdep.outputFile=" + cpFile.toString(),
                    "-q");

            if (exitCode == 0) {
                String content = new String(Files.readAllBytes(cpFile), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    String[] entries = content.split(File.pathSeparator.equals(";")
                            ? ";" : File.pathSeparator);
                    for (String entry : entries) {
                        entry = entry.trim();
                        if (!entry.isEmpty() && new File(entry).exists()) {
                            classpathEntries.add(entry);
                        }
                    }
                }
                logger.info("Resolved {} dependency classpath entries for {}", classpathEntries.size(), projectRoot);
            } else {
                logger.warn("mvn dependency:build-classpath failed for {} (exit code {})", projectRoot, exitCode);
            }
        } catch (IOException e) {
            logger.error("Could not resolve Maven classpath for {}: {}", projectRoot, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Maven classpath resolution interrupted for {}", projectRoot);
        } finally {
            if (cpFile != null) {
                try {
                    Files.deleteIfExists(cpFile);
                } catch (IOException ignored) {
                }
            }
        }
        return classpathEntries;
    }

    /**
     * Download source JARs for all dependencies (best-effort).
     * <p>
     * Runs {@code mvn dependency:sources} to download {@code *-sources.jar}
     * artifacts into the local Maven repository ({@code ~/.m2/repository}).
     * The existing {@code findSourceJar()} sibling-convention lookup in
     * {@link com.tomaszrup.groovyls.util.JavaSourceLocator} will then discover
     * them automatically. Failures are logged but never fatal — classpath
     * resolution proceeds regardless.
     */
    private void downloadSourceJars(Path projectRoot) {
        try {
            logger.info("Downloading source JARs for Maven project {}", projectRoot);
            int exitCode = runMaven(projectRoot,
                    "dependency:sources",
                    "-DincludeScope=test",
                    "-DfailOnMissingClassifierArtifact=false",
                    "-q");
            if (exitCode == 0) {
                logger.info("Source JARs downloaded for {}", projectRoot);
            } else {
                logger.debug("mvn dependency:sources exited with code {} for {} (non-fatal)",
                        exitCode, projectRoot);
            }
        } catch (IOException e) {
            logger.debug("Could not download source JARs for {}: {}", projectRoot, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Source JAR download interrupted for {}", projectRoot);
        }
    }

    private Optional<String> detectGroovyVersionFromPom(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            return Optional.empty();
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            dbf.setXIncludeAware(false);
            dbf.setNamespaceAware(false);

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc;
            try (InputStream in = Files.newInputStream(pomPath)) {
                doc = db.parse(in);
            }

            Element root = doc.getDocumentElement();
            if (root == null) {
                return Optional.empty();
            }

            Map<String, String> properties = readPomProperties(root);

            Optional<String> fromDependencyManagement = findGroovyVersionInDependencies(
                    childElement(root, "dependencyManagement"), properties);
            if (fromDependencyManagement.isPresent()) {
                return fromDependencyManagement;
            }

            return findGroovyVersionInDependencies(childElement(root, "dependencies"), properties);
        } catch (Exception e) {
            logger.debug("Could not parse pom.xml for Groovy version in {}: {}",
                    projectRoot, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean hasDeclaredPomDependencies(Path projectRoot) {
        Path pomPath = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            return false;
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            dbf.setXIncludeAware(false);
            dbf.setNamespaceAware(false);

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc;
            try (InputStream in = Files.newInputStream(pomPath)) {
                doc = db.parse(in);
            }

            Element root = doc.getDocumentElement();
            if (root == null) {
                return false;
            }

            Element deps = childElement(root, "dependencies");
            if (deps == null) {
                return false;
            }

            NodeList dependencyNodes = deps.getElementsByTagName("dependency");
            return dependencyNodes.getLength() > 0;
        } catch (Exception e) {
            logger.warn("Could not verify pom dependencies for {}: {}", projectRoot, e.getMessage());
            return true;
        }
    }

    private boolean containsOnlyTargetClassDirs(Path projectRoot, List<String> classpathEntries) {
        if (classpathEntries.isEmpty()) {
            return false;
        }

        Path targetClasses = projectRoot.resolve("target/classes").toAbsolutePath().normalize();
        Path targetTestClasses = projectRoot.resolve("target/test-classes").toAbsolutePath().normalize();

        for (String entry : classpathEntries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path normalized;
            try {
                normalized = Path.of(entry).toAbsolutePath().normalize();
            } catch (Exception e) {
                return false;
            }

            if (!normalized.equals(targetClasses) && !normalized.equals(targetTestClasses)) {
                return false;
            }
        }
        return true;
    }

    private Optional<String> findGroovyVersionInDependencies(Element dependenciesElement,
                                                             Map<String, String> properties) {
        if (dependenciesElement == null) {
            return Optional.empty();
        }

        NodeList dependencyNodes = dependenciesElement.getElementsByTagName("dependency");
        String best = null;
        for (int i = 0; i < dependencyNodes.getLength(); i++) {
            Node node = dependencyNodes.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            Element dep = (Element) node;
            String groupId = textOfChild(dep, "groupId");
            String artifactId = textOfChild(dep, "artifactId");
            String version = textOfChild(dep, "version");

            if (!isGroovyDependency(groupId, artifactId) || version == null || version.isBlank()) {
                continue;
            }

            String resolved = resolvePropertyReference(version.trim(), properties);
            if (resolved == null || resolved.isBlank()) {
                continue;
            }

            if (best == null) {
                best = resolved;
            } else {
                Optional<String> max = GroovyVersionDetector.detect(Arrays.asList(
                        "/fake/groovy-" + best + ".jar",
                        "/fake/groovy-" + resolved + ".jar"));
                if (max.isPresent() && max.get().equals(resolved)) {
                    best = resolved;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Map<String, String> readPomProperties(Element root) {
        Map<String, String> properties = new LinkedHashMap<>();
        Element propsElement = childElement(root, "properties");
        if (propsElement == null) {
            return properties;
        }

        NodeList nodes = propsElement.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                String key = node.getNodeName();
                String value = node.getTextContent();
                if (key != null && value != null) {
                    properties.put(key.trim(), value.trim());
                }
            }
        }
        return properties;
    }

    private String resolvePropertyReference(String version, Map<String, String> properties) {
        Matcher m = PROPERTY_REF.matcher(version);
        if (!m.matches()) {
            return version;
        }
        String property = m.group(1);
        String resolved = properties.get(property);
        return resolved != null ? resolved : version;
    }

    private boolean isGroovyDependency(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) {
            return false;
        }
        boolean groovyGroup = "org.apache.groovy".equals(groupId)
                || "org.codehaus.groovy".equals(groupId);
        return groovyGroup && artifactId.startsWith("groovy");
    }

    private Element childElement(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private String textOfChild(Element parent, String name) {
        Element child = childElement(parent, name);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent();
        return value != null ? value.trim() : null;
    }

    /**
     * Discover compiled class output directories under {@code target/}.
     */
    private List<String> discoverClassDirs(Path projectDir) {
        List<String> classDirs = new ArrayList<>();
        Path targetClasses = projectDir.resolve("target/classes");
        Path targetTestClasses = projectDir.resolve("target/test-classes");

        if (Files.isDirectory(targetClasses)) {
            classDirs.add(targetClasses.toString());
        }
        if (Files.isDirectory(targetTestClasses)) {
            classDirs.add(targetTestClasses.toString());
        }
        return classDirs;
    }

    /**
     * Run a Maven command with the given goals/arguments in the specified
     * project directory.
     *
     * @return the process exit code
     */
    private int runMaven(Path projectRoot, String... args) throws IOException, InterruptedException {
        String primaryCommand = findMvnCommand(projectRoot);
        boolean usedWrapper = primaryCommand.toLowerCase().contains("mvnw");

        try {
            int exitCode = executeMavenCommand(projectRoot, primaryCommand, args);
            if (exitCode == 0 || !usedWrapper) {
                return exitCode;
            }

            String fallback = findSystemMvnCommand();
            logger.warn("Maven wrapper failed for {} (exit code {}), retrying with {}",
                    projectRoot, exitCode, fallback);
            return executeMavenCommand(projectRoot, fallback, args);
        } catch (IOException e) {
            if (!usedWrapper) {
                throw e;
            }
            String fallback = findSystemMvnCommand();
            logger.warn("Maven wrapper execution failed for {} ({}), retrying with {}",
                    projectRoot, e.getMessage(), fallback);
            return executeMavenCommand(projectRoot, fallback, args);
        }
    }

    private int executeMavenCommand(Path projectRoot, String mvnCommand, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(mvnCommand);
        command.addAll(Arrays.asList(args));

        logger.info("Running Maven: {} in {}", command, projectRoot);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false);

        Process process = pb.start();

        StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
        StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
        stdoutGobbler.start();
        stderrGobbler.start();

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Maven process timed out after 120 seconds: " + command);
        }
        int exitCode = process.exitValue();
        stdoutGobbler.join(5000);
        stderrGobbler.join(5000);

        if (exitCode != 0) {
            String stderr = stderrGobbler.getOutput();
            if (!stderr.isEmpty()) {
                logger.warn("Maven stderr: {}", stderr);
            }
        }

        return exitCode;
    }

    /**
     * Locate the Maven executable. Checks in order:
     * 1. Maven Wrapper ({@code mvnw.cmd} / {@code mvnw}) in the project directory or a parent
     * 2. {@code mavenHome} setting (if configured)
     * 3. {@code mvn} / {@code mvn.cmd} on PATH
     */
    private String findMvnCommand(Path projectRoot) {
        // 1. Check for Maven Wrapper in project dir and parent directories
        String wrapper = findMavenWrapper(projectRoot);
        if (wrapper != null) {
            logger.info("Using Maven Wrapper: {}", wrapper);
            return wrapper;
        }

        return findSystemMvnCommand();
    }

    private String findSystemMvnCommand() {

        // 2. Check configured mavenHome
        if (mavenHome != null && !mavenHome.isEmpty()) {
            Path mvnBin = Path.of(mavenHome, "bin",
                    isWindows() ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(mvnBin)) {
                return mvnBin.toString();
            }
            // Also try without .cmd extension on Windows (wrapper scripts)
            if (isWindows()) {
                Path mvnBinAlt = Path.of(mavenHome, "bin", "mvn");
                if (Files.isRegularFile(mvnBinAlt)) {
                    return mvnBinAlt.toString();
                }
            }
            logger.warn("Maven home '{}' does not contain a valid mvn binary, falling back to PATH", mavenHome);
        }

        // 3. Fall back to mvn on PATH
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    /**
     * Search for a Maven Wrapper script ({@code mvnw.cmd} / {@code mvnw}) starting
     * from the given directory and walking up to parent directories. This supports
     * multi-module projects where the wrapper lives in the root but submodules are
     * discovered individually.
     *
     * @return absolute path to the wrapper script, or {@code null} if not found
     */
    private String findMavenWrapper(Path startDir) {
        Path dir = startDir;
        // Limit traversal to prevent walking past the workspace root into system dirs
        int maxDepth = 10;
        int depth = 0;
        while (dir != null && depth < maxDepth) {
            Path wrapper = dir.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(wrapper) && isLegitMavenWrapper(wrapper)) {
                return wrapper.toAbsolutePath().toString();
            }
            // On Windows also check for mvnw (batch-less wrapper)
            if (isWindows()) {
                Path wrapperAlt = dir.resolve("mvnw");
                if (Files.isRegularFile(wrapperAlt) && isLegitMavenWrapper(wrapperAlt)) {
                    return wrapperAlt.toAbsolutePath().toString();
                }
            }
            dir = dir.getParent();
            depth++;
        }
        return null;
    }

    /**
     * Basic content validation of a Maven Wrapper script.
     *
        * <p>Reads the first {@value #WRAPPER_HEADER_SCAN_BYTES} bytes and checks
        * for expected markers:
     * <ul>
     * <li>Unix ({@code mvnw}): must start with {@code #!} (shebang) and
     *     contain "maven" or "mvn" (case-insensitive).</li>
     * <li>Windows ({@code mvnw.cmd}): must start with {@code @} or {@code ::}
     *     (batch preamble/comment) and contain "maven" or "mvn"
     *     (case-insensitive).</li>
     * </ul>
     *
     * <p>This is a best-effort check to reject obviously tampered wrapper
     * scripts (e.g. a shell script that runs {@code rm -rf /} but is named
     * {@code mvnw}). It does not cryptographically verify the wrapper.</p>
     *
     * @return {@code true} if the file looks like a legitimate Maven wrapper
     */
    private boolean isLegitMavenWrapper(Path wrapperPath) {
        try {
            byte[] buf = new byte[WRAPPER_HEADER_SCAN_BYTES];
            int bytesRead = 0;
            try (InputStream in = Files.newInputStream(wrapperPath)) {
                while (bytesRead < buf.length) {
                    int read = in.read(buf, bytesRead, buf.length - bytesRead);
                    if (read < 0) {
                        break;
                    }
                    bytesRead += read;
                }
            }
            if (bytesRead <= 0) {
                logger.warn("Skipping empty Maven wrapper at {}", wrapperPath);
                return false;
            }
            String header = new String(buf, 0, bytesRead, StandardCharsets.UTF_8).toLowerCase();

            boolean hasShebangOrBatch;
            if (wrapperPath.getFileName().toString().endsWith(".cmd")) {
                // Windows batch: first non-whitespace should be @ or ::
                String trimmed = header.stripLeading();
                hasShebangOrBatch = trimmed.startsWith("@") || trimmed.startsWith("::");
            } else {
                // Unix shell: must start with #!
                hasShebangOrBatch = header.startsWith("#!");
            }

            boolean hasMavenMarker = header.contains("maven") || header.contains("mvn");

            if (!hasShebangOrBatch || !hasMavenMarker) {
                logger.warn("Skipping suspicious Maven wrapper at {}: content does not match " +
                        "expected format (shebang/batch={}, maven-marker={})",
                        wrapperPath, hasShebangOrBatch, hasMavenMarker);
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.warn("Could not read Maven wrapper at {}: {}", wrapperPath, e.getMessage());
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Simple thread to consume a process stream and capture its output.
     */
    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final StringBuilder output = new StringBuilder();

        StreamGobbler(InputStream inputStream) {
            this.inputStream = inputStream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                // stream closed, ignore
            }
        }

        String getOutput() {
            return output.toString();
        }
    }
}
