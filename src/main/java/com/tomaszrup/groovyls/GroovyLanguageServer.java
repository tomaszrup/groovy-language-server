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
package com.tomaszrup.groovyls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tomaszrup.groovyls.compiler.SharedClassGraphCache;
import com.tomaszrup.groovyls.config.ClasspathCache;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.importers.GradleProjectImporter;
import com.tomaszrup.groovyls.importers.MavenProjectImporter;
import com.tomaszrup.groovyls.importers.ProjectDiscovery;
import com.tomaszrup.groovyls.importers.ProjectImporter;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class GroovyLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger logger = LoggerFactory.getLogger(GroovyLanguageServer.class);
    private LanguageClient client;

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "--tcp".equals(args[0])) {
            int port = 5007;
            if (args.length > 1) {
                try {
                    port = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    logger.error("Invalid port number: {}", args[1]);
                    System.exit(1);
                }
            }

            try (ServerSocket serverSocket = new ServerSocket(port)) {
                logger.info("Groovy Language Server listening on port {}", port);
                Socket socket = serverSocket.accept();
                logger.info("Client connected.");

                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                startServer(in, out);
            }
        } else {
            logger.info("Groovy Language Server starting in stdio mode.");
            InputStream in = System.in;
            OutputStream out = System.out;
            startServer(in, out);
        }
    }

    private static void startServer(InputStream in, OutputStream out) {
        // Redirect System.out to System.err to avoid corrupting the communication channel
        System.setOut(new PrintStream(System.err));

        GroovyLanguageServer server = new GroovyLanguageServer();
        Launcher<LanguageClient> launcher = Launcher.createLauncher(server, LanguageClient.class, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening();
    }

    private final GroovyServices groovyServices;
    private final List<ProjectImporter> importers;
    /** Maps each registered project root to the importer that owns it. */
    private final Map<Path, ProjectImporter> projectImporterMap = new LinkedHashMap<>();
    /** Executor for background project import work (Gradle/Maven). */
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "groovyls-import");
        t.setDaemon(true);
        return t;
    });
    /**
     * Thread pool used inside {@link #importProjectsAsync} to import
     * independent build roots (different Gradle roots, Maven modules) in
     * parallel.
     */
    private final ExecutorService parallelImportPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                Thread t = new Thread(r, "groovyls-parallel-import");
                t.setDaemon(true);
                return t;
            });
    /** Future for the background import task, used for cancellation on shutdown. */
    private volatile Future<?> importFuture;
    /** Whether the on-disk classpath cache is enabled (default: true). */
    private volatile boolean classpathCacheEnabled = true;
    /**
     * Set of enabled importer names (e.g. "Gradle", "Maven"). When empty
     * all importers are enabled.  Populated from the {@code groovy.project.importers}
     * VS Code setting via {@code initializationOptions}.
     */
    private volatile Set<String> enabledImporters = Collections.emptySet();

    public GroovyLanguageServer() {
        this(new CompilationUnitFactory());
    }

    public GroovyLanguageServer(ICompilationUnitFactory compilationUnitFactory) {
        this.groovyServices = new GroovyServices(compilationUnitFactory);
        this.groovyServices.setJavaChangeListener(this::recompileProject);

        // Register all supported build-tool importers.
        // Order matters: if both build.gradle and pom.xml exist in the same
        // directory, the first importer that claims it wins (Gradle preferred).
        this.importers = new ArrayList<>();
        this.importers.add(new GradleProjectImporter());
        this.importers.add(new MavenProjectImporter());

        // Forward configuration changes to importers that need them.
        this.groovyServices.setSettingsChangeListener(this::applyImporterSettings);
    }

    /**
     * Push relevant VS Code settings to importers that can use them.
     * Currently handles {@code groovy.maven.home}.
     */
    private void applyImporterSettings(JsonObject settings) {
        if (!settings.has("groovy") || !settings.get("groovy").isJsonObject()) {
            return;
        }
        JsonObject groovy = settings.get("groovy").getAsJsonObject();
        if (groovy.has("maven") && groovy.get("maven").isJsonObject()) {
            JsonObject maven = groovy.get("maven").getAsJsonObject();
            JsonElement homeElem = maven.get("home");
            String mavenHome = (homeElem != null && !homeElem.isJsonNull()) ? homeElem.getAsString() : null;

            for (ProjectImporter importer : importers) {
                if (importer instanceof MavenProjectImporter) {
                    ((MavenProjectImporter) importer).setMavenHome(mavenHome);
                }
            }
        }
    }

    /**
     * Returns the list of project importers. Visible for testing and
     * for configuring importer-specific settings (e.g. Maven home).
     */
    public List<ProjectImporter> getImporters() {
        return importers;
    }

    /**
     * Called when Java or build-tool files change in a registered project.
     * Delegates to the appropriate build-tool importer for recompilation.
     *
     * <p>The classpath cache is <b>not</b> invalidated here.  Cache validity
     * is checked on next startup via build-file stamps, and clearing it
     * mid-session would only cause unnecessary full re-resolution on restart
     * even when the classpath hasn't actually changed.</p>
     */
    private void recompileProject(Path projectRoot) {
        ProjectImporter importer = projectImporterMap.get(projectRoot);
        if (importer != null) {
            logger.info("Recompiling {} project: {}", importer.getName(), projectRoot);
            importer.recompile(projectRoot);
        } else {
            logger.warn("No importer found for project root: {}", projectRoot);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        String rootUriString = params.getRootUri();
        if (rootUriString != null) {
            URI uri = URI.create(rootUriString);
            Path workspaceRoot = Paths.get(uri);
            groovyServices.setWorkspaceRoot(workspaceRoot);
        }

        // Parse initializationOptions for cache settings
        Object initOptions = params.getInitializationOptions();
        if (initOptions instanceof JsonObject) {
            JsonObject opts = (JsonObject) initOptions;
            if (opts.has("classpathCache") && !opts.get("classpathCache").getAsBoolean()) {
                classpathCacheEnabled = false;
                logger.info("Classpath caching disabled via initializationOptions");
            }
            // Parse enabled importers list (e.g. ["Gradle", "Maven"])
            if (opts.has("enabledImporters") && opts.get("enabledImporters").isJsonArray()) {
                JsonArray arr = opts.getAsJsonArray("enabledImporters");
                Set<String> enabled = new LinkedHashSet<>();
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) {
                        enabled.add(el.getAsString());
                    }
                }
                if (!enabled.isEmpty()) {
                    enabledImporters = Collections.unmodifiableSet(enabled);
                    logger.info("Enabled importers: {}", enabledImporters);
                }
            }
        }

        // Set workspace bound on the Gradle importer so findGradleRoot()
        // stops at the workspace root instead of walking to the filesystem root.
        if (rootUriString != null) {
            Path wsRoot = Paths.get(URI.create(rootUriString));
            for (ProjectImporter importer : importers) {
                if (importer instanceof GradleProjectImporter) {
                    ((GradleProjectImporter) importer).setWorkspaceBound(wsRoot);
                }
            }
        }

        // Build capabilities immediately so the client doesn't block
        CompletionOptions completionOptions = new CompletionOptions(true, Arrays.asList("."));
        ServerCapabilities serverCapabilities = new ServerCapabilities();
        serverCapabilities.setCompletionProvider(completionOptions);
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        serverCapabilities.setDocumentSymbolProvider(true);
        serverCapabilities.setWorkspaceSymbolProvider(true);
        serverCapabilities.setReferencesProvider(true);
        serverCapabilities.setDefinitionProvider(true);
        serverCapabilities.setTypeDefinitionProvider(true);
        serverCapabilities.setImplementationProvider(true);
        serverCapabilities.setDocumentHighlightProvider(true);
        serverCapabilities.setHoverProvider(true);
        RenameOptions renameOptions = new RenameOptions();
        renameOptions.setPrepareProvider(true);
        serverCapabilities.setRenameProvider(renameOptions);
        CodeActionOptions codeActionOptions = new CodeActionOptions();
        codeActionOptions.setCodeActionKinds(Arrays.asList(
                CodeActionKind.QuickFix,
                CodeActionKind.Refactor,
                CodeActionKind.SourceOrganizeImports));
        serverCapabilities.setCodeActionProvider(codeActionOptions);
        SignatureHelpOptions signatureHelpOptions = new SignatureHelpOptions();
        signatureHelpOptions.setTriggerCharacters(Arrays.asList("(", ","));
        serverCapabilities.setSignatureHelpProvider(signatureHelpOptions);
        SemanticTokensWithRegistrationOptions semanticTokensOptions = new SemanticTokensWithRegistrationOptions();
        semanticTokensOptions.setLegend(com.tomaszrup.groovyls.providers.SemanticTokensProvider.getLegend());
        semanticTokensOptions.setFull(true);
        serverCapabilities.setSemanticTokensProvider(semanticTokensOptions);
        InlayHintRegistrationOptions inlayHintOptions = new InlayHintRegistrationOptions();
        inlayHintOptions.setResolveProvider(false);
        serverCapabilities.setInlayHintProvider(inlayHintOptions);
        serverCapabilities.setDocumentFormattingProvider(true);

        // Schedule heavy Gradle/Maven import work on a background thread so
        // the LSP initialization response is returned immediately.
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null && !folders.isEmpty()) {
            final List<WorkspaceFolder> foldersSnapshot = new ArrayList<>(folders);
            groovyServices.setImportInProgress(true);
            importFuture = importExecutor.submit(() -> importProjectsAsync(foldersSnapshot));
        } else {
            // No workspace folders — nothing to import, signal completion
            logProgress("Project import complete");
        }

        InitializeResult initializeResult = new InitializeResult(serverCapabilities);
        return CompletableFuture.completedFuture(initializeResult);
    }

    /**
     * Performs the heavy Gradle/Maven project discovery and import on a
     * background thread with progress reporting to the client.
     *
     * <p><b>Performance strategy (lazy two-phase import):</b>
     * <ol>
     *   <li><b>Phase 1 — Discovery (fast):</b> discover all projects across
     *       all importers and register scopes immediately with empty
     *       classpaths.  This lets {@code didOpen} compile files with basic
     *       syntax while the classpath is being resolved.</li>
     *   <li><b>Phase 1b — Cache check:</b> if a valid classpath cache
     *       exists, apply cached classpaths instantly.</li>
     *   <li><b>Phase 2 — Classpath resolution (no compilation):</b> resolve
     *       dependency JARs and discover existing class-output directories
     *       via {@link ProjectImporter#resolveClasspaths}. This is
     *       dramatically faster than a full import because it skips the
     *       expensive {@code classes}/{@code testClasses} build tasks.</li>
     *   <li>Update scopes with resolved classpaths; recompile any files
     *       the user already had open.</li>
     * </ol>
     */
    private void importProjectsAsync(List<WorkspaceFolder> folders) {
        long totalStart = System.currentTimeMillis();
        try {
            Map<Path, List<String>> projectClasspaths = new ConcurrentHashMap<>();
            Map<Path, ProjectImporter> importerMapLocal = new ConcurrentHashMap<>();
            Set<Path> claimedRoots = Collections.synchronizedSet(new LinkedHashSet<>());

            // ── Phase 0: Try cache-first ──────────────────────────────────
            // Load the on-disk cache ONCE.  This single load is used for
            // all subsequent cache-validity checks (Phases 0, 1b).
            Path workspaceRoot = groovyServices.getWorkspaceRoot();
            boolean cacheHit = false;
            List<Path> cachedDiscoveredRoots = null;
            ClasspathCache.CacheData cachedData = null;

            if (classpathCacheEnabled && workspaceRoot != null) {
                long cacheStart = System.currentTimeMillis();
                Optional<ClasspathCache.CacheData> cached = ClasspathCache.load(workspaceRoot);
                if (cached.isPresent()) {
                    cachedData = cached.get();
                    // We need project roots to validate stamps.  If the cache
                    // contains a discovered-projects list we can use that;
                    // otherwise we must fall through to discovery.
                    Optional<List<Path>> cachedRoots = ClasspathCache.toDiscoveredProjectsList(cachedData);
                    if (cachedRoots.isPresent()) {
                        Map<String, String> currentStamps =
                                ClasspathCache.computeBuildFileStamps(cachedRoots.get());
                        if (ClasspathCache.isValid(cachedData, currentStamps)) {
                            cachedDiscoveredRoots = cachedRoots.get();
                            Map<Path, List<String>> cachedClasspaths =
                                    ClasspathCache.toClasspathMap(cachedData);
                            projectClasspaths.putAll(cachedClasspaths);
                            cacheHit = true;

                            long cacheElapsed = System.currentTimeMillis() - cacheStart;
                            logProgress("Using cached classpath ("
                                    + cachedDiscoveredRoots.size() + " projects, "
                                    + cacheElapsed + "ms)");
                        } else {
                            logger.info("Classpath cache stamp mismatch — will re-discover and resolve");
                        }
                    } else {
                        logger.info("Classpath cache has no discovered-projects list — will discover");
                    }
                }
            }

            // ── Phase 1: Discover projects ────────────────────────────────
            // Map: importer → list of discovered project roots
            Map<ProjectImporter, List<Path>> discoveredByImporter = new LinkedHashMap<>();
            List<Path> allDiscoveredRoots;

            if (cacheHit && cachedDiscoveredRoots != null) {
                // Use the cached project list — skip Files.walk() entirely.
                allDiscoveredRoots = cachedDiscoveredRoots;
                logProgress("Using cached project list (" + allDiscoveredRoots.size() + " projects)");

                // We still need to populate importerMapLocal so that
                // recompileProject() can look up the right importer.
                // Assign all cached roots to the first importer that would
                // claim them (Gradle first, then Maven).
                for (Path root : allDiscoveredRoots) {
                    for (ProjectImporter importer : importers) {
                        // Check if a build file for this importer exists
                        if (importer instanceof GradleProjectImporter
                                && (root.resolve("build.gradle").toFile().exists()
                                    || root.resolve("build.gradle.kts").toFile().exists())) {
                            importerMapLocal.put(root, importer);
                            discoveredByImporter
                                    .computeIfAbsent(importer, k -> new ArrayList<>())
                                    .add(root);
                            break;
                        } else if (importer instanceof MavenProjectImporter
                                && root.resolve("pom.xml").toFile().exists()) {
                            importerMapLocal.put(root, importer);
                            discoveredByImporter
                                    .computeIfAbsent(importer, k -> new ArrayList<>())
                                    .add(root);
                            break;
                        }
                    }
                }
            } else {
                // No usable cache — discover from scratch using a SINGLE
                // unified filesystem walk that finds both Gradle and Maven
                // projects with directory pruning (skips .git, node_modules,
                // build, target, etc.)
                long discoveryStart = System.currentTimeMillis();
                logProgress("Discovering projects...");

                // Determine which importer names are enabled
                Set<String> enabledNames = enabledImporters;

                List<Path> gradleRoots = new ArrayList<>();
                List<Path> mavenRoots = new ArrayList<>();

                for (WorkspaceFolder folder : folders) {
                    if (Thread.currentThread().isInterrupted()) {
                        logProgress("Project import cancelled");
                        return;
                    }
                    Path folderPath = Paths.get(URI.create(folder.getUri()));

                    try {
                        ProjectDiscovery.DiscoveryResult result =
                                ProjectDiscovery.discoverAll(folderPath, enabledNames);
                        gradleRoots.addAll(result.gradleProjects);
                        mavenRoots.addAll(result.mavenProjects);
                    } catch (IOException e) {
                        logger.error("Error discovering projects in {}: {}",
                                folderPath, e.getMessage(), e);
                    }
                }

                // Map discovered roots to their importers, respecting priority
                // (Gradle first — if a dir has both build.gradle and pom.xml, Gradle wins)
                ProjectImporter gradleImporter = null;
                ProjectImporter mavenImporter = null;
                for (ProjectImporter importer : importers) {
                    if (importer instanceof GradleProjectImporter) gradleImporter = importer;
                    if (importer instanceof MavenProjectImporter) mavenImporter = importer;
                }

                if (gradleImporter != null) {
                    for (Path p : gradleRoots) {
                        if (claimedRoots.add(p)) {
                            discoveredByImporter
                                    .computeIfAbsent(gradleImporter, k -> new ArrayList<>())
                                    .add(p);
                        }
                    }
                    if (!gradleRoots.isEmpty()) {
                        logProgress("Found " + gradleRoots.size() + " Gradle project(s)");
                    } else {
                        logProgress("No Gradle projects found");
                    }
                }

                if (mavenImporter != null) {
                    List<Path> unclaimed = new ArrayList<>();
                    for (Path p : mavenRoots) {
                        if (claimedRoots.add(p)) {
                            unclaimed.add(p);
                            discoveredByImporter
                                    .computeIfAbsent(mavenImporter, k -> new ArrayList<>())
                                    .add(p);
                        }
                    }
                    if (!mavenRoots.isEmpty()) {
                        logProgress("Found " + unclaimed.size() + " Maven project(s)"
                                + (unclaimed.size() < mavenRoots.size()
                                        ? " (" + (mavenRoots.size() - unclaimed.size()) + " already claimed by Gradle)"
                                        : ""));
                    } else {
                        logProgress("No Maven projects found");
                    }
                }

                long discoveryElapsed = System.currentTimeMillis() - discoveryStart;

                allDiscoveredRoots = discoveredByImporter.values().stream()
                        .flatMap(List::stream).collect(Collectors.toList());

                logProgress("Discovery completed in " + discoveryElapsed + "ms ("
                        + allDiscoveredRoots.size() + " projects)");
            }

            if (Thread.currentThread().isInterrupted()) {
                logProgress("Project import cancelled");
                return;
            }

            // ── Phase 1a: Register scopes ─────────────────────────────────
            if (!allDiscoveredRoots.isEmpty()) {
                logProgress("Discovered " + allDiscoveredRoots.size()
                        + " project(s), registering scopes...");
                groovyServices.registerDiscoveredProjects(allDiscoveredRoots);

                for (Map.Entry<ProjectImporter, List<Path>> de : discoveredByImporter.entrySet()) {
                    for (Path root : de.getValue()) {
                        importerMapLocal.put(root, de.getKey());
                    }
                }
            }

            // ── Phase 1b: Cache validation for non-cache-first path ──────
            // When Phase 0 didn't hit (e.g. cache had no discoveredProjects
            // list), re-check using the SAME cachedData loaded in Phase 0
            // now that we know the project roots.
            if (!cacheHit && cachedData != null && !allDiscoveredRoots.isEmpty()) {
                Map<String, String> currentStamps =
                        ClasspathCache.computeBuildFileStamps(allDiscoveredRoots);
                if (ClasspathCache.isValid(cachedData, currentStamps)) {
                    logProgress("Using cached classpath (build files unchanged)");
                    Map<Path, List<String>> cachedClasspaths =
                            ClasspathCache.toClasspathMap(cachedData);
                    projectClasspaths.putAll(cachedClasspaths);
                    cacheHit = true;
                } else {
                    logger.info("Classpath cache miss — will resolve classpaths");
                }
            }

            // ── Phase 2: Resolve classpaths (skipped on cache hit) ───────
            if (!cacheHit) {
                int totalProjects = discoveredByImporter.values().stream()
                        .mapToInt(List::size).sum();

                if (totalProjects > 0) {
                    logProgress("Resolving classpaths for " + totalProjects + " project(s) across "
                            + discoveredByImporter.size() + " build tool(s)...");

                    long resolveStart = System.currentTimeMillis();
                    List<Future<?>> importFutures = new ArrayList<>();
                    for (Map.Entry<ProjectImporter, List<Path>> entry : discoveredByImporter.entrySet()) {
                        ProjectImporter importer = entry.getKey();
                        List<Path> roots = entry.getValue();

                        importFutures.add(parallelImportPool.submit(() -> {
                            try {
                                logProgress("Resolving classpaths for " + roots.size() + " "
                                        + importer.getName() + " project(s)...");
                                long start = System.currentTimeMillis();

                                Map<Path, List<String>> batchResult = importer.resolveClasspaths(roots);

                                long elapsed = System.currentTimeMillis() - start;
                                logProgress(importer.getName() + " classpath resolution completed in "
                                        + (elapsed / 1000) + "s (" + batchResult.size() + " projects)");

                                for (Map.Entry<Path, List<String>> e : batchResult.entrySet()) {
                                    projectClasspaths.put(e.getKey(), e.getValue());
                                }
                            } catch (Exception e) {
                                logger.error("Error resolving {} classpaths: {}",
                                        importer.getName(), e.getMessage(), e);
                                logProgress(importer.getName() + " classpath resolution failed: " + e.getMessage());
                            }
                        }));
                    }

                    for (Future<?> f : importFutures) {
                        try {
                            f.get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logProgress("Project import cancelled");
                            return;
                        } catch (ExecutionException e) {
                            logger.error("Import task failed: {}", e.getCause().getMessage(), e.getCause());
                        }
                    }

                    if (Thread.currentThread().isInterrupted()) {
                        logProgress("Project import cancelled");
                        return;
                    }

                    long resolveElapsed = System.currentTimeMillis() - resolveStart;
                    logProgress("Classpath resolution completed in "
                            + (resolveElapsed / 1000) + "s");

                    // Save classpath + discovered projects for next startup
                    if (classpathCacheEnabled && workspaceRoot != null && !projectClasspaths.isEmpty()) {
                        Map<String, String> stamps =
                                ClasspathCache.computeBuildFileStamps(allDiscoveredRoots);
                        ClasspathCache.save(workspaceRoot, projectClasspaths, stamps, allDiscoveredRoots);
                    }
                }
            } else {
                // Cache hit — re-save with discoveredProjects if the cache
                // didn't contain them (upgrade from v1 format)
                if (cachedData != null && cachedData.discoveredProjects == null
                        && classpathCacheEnabled && workspaceRoot != null
                        && !projectClasspaths.isEmpty() && !allDiscoveredRoots.isEmpty()) {
                    Map<String, String> stamps =
                            ClasspathCache.computeBuildFileStamps(allDiscoveredRoots);
                    ClasspathCache.save(workspaceRoot, projectClasspaths, stamps, allDiscoveredRoots);
                }
            }

            // ── Phase 3: Apply classpaths ─────────────────────────────────
            projectImporterMap.putAll(importerMapLocal);

            if (!projectClasspaths.isEmpty()) {
                logProgress("Updating classpaths for " + projectClasspaths.size() + " project(s)...");
                groovyServices.updateProjectClasspaths(projectClasspaths);
            }

            long totalElapsed = System.currentTimeMillis() - totalStart;
            logProgress("Project import complete (" + totalElapsed + "ms"
                    + (cacheHit ? ", cached" : "") + ")");
        } catch (Exception e) {
            logger.error("Background project import failed: {}", e.getMessage(), e);
            logProgress("Project import failed: " + e.getMessage());
        } finally {
            groovyServices.onImportComplete();
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        if (importFuture != null) {
            importFuture.cancel(true);
        }
        groovyServices.shutdown();
        parallelImportPool.shutdownNow();
        importExecutor.shutdownNow();
        SharedClassGraphCache.getInstance().clear();
        return CompletableFuture.completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return groovyServices;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return groovyServices;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        groovyServices.connect(client);
    }

    /** Send a progress log message to the client (visible in output channel). */
    private void logProgress(String message) {
        logger.info(message);
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Info, message));
        }
    }
}
