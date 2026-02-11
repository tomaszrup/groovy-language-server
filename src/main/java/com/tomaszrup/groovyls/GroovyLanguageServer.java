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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GroovyLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger logger = LoggerFactory.getLogger(GroovyLanguageServer.class);
    private GroovyLanguageClient client;

    public static void main(String[] args) throws IOException {
        // Install a global uncaught-exception handler so that unexpected
        // exceptions on any thread are logged instead of silently killing
        // the JVM process (which the client sees as an EPIPE).
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("[FATAL] Uncaught exception on thread " + thread.getName());
            throwable.printStackTrace(System.err);
            logger.error("Uncaught exception on thread {}: {}",
                    thread.getName(), throwable.getMessage(), throwable);
        });

        // Suppress noisy "Unmatched cancel notification for request id" warnings
        // from LSP4J's RemoteEndpoint. These occur normally when the client
        // sends $/cancelRequest for a request that already completed.
        java.util.logging.Logger.getLogger("org.eclipse.lsp4j.jsonrpc.RemoteEndpoint")
                .setLevel(Level.SEVERE);
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

            try (ServerSocket serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
                logger.info("Groovy Language Server listening on port {} (localhost only)", port);
                try (Socket socket = serverSocket.accept()) {
                    logger.info("Client connected.");

                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    startServer(in, out);
                }
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
        Launcher<GroovyLanguageClient> launcher = Launcher.createLauncher(server, GroovyLanguageClient.class, in, out);
        server.connect(launcher.getRemoteProxy());

        // Block the main (non-daemon) thread on the listener future.
        // Without this, the main thread exits immediately after starting
        // the listener, and since all pool threads are daemon threads the
        // JVM terminates — which the client sees as an EPIPE.
        Future<Void> future = launcher.startListening();
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Language server listener interrupted");
        } catch (ExecutionException e) {
            logger.error("Language server listener terminated with error: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
        }
    }

    private final GroovyServices groovyServices;
    private final List<ProjectImporter> importers;
    /** Maps each registered project root to the importer that owns it. */
    private final Map<Path, ProjectImporter> projectImporterMap = new LinkedHashMap<>();
    /** Shared executor pools for all server components. */
    private final ExecutorPools executorPools = new ExecutorPools();
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

    /** Whether backfill of sibling Gradle subprojects is enabled (default: false). */
    private volatile boolean backfillSiblingProjects = false;

    /** Scope eviction TTL in seconds (default: 300). 0 disables eviction. */
    private volatile long scopeEvictionTTLSeconds = 300;

    /**
     * Coordinator for lazy on-demand classpath resolution. Created during
     * {@link #importProjectsAsync} once the scope manager and importer map
     * are initialized.
     */
    private volatile ClasspathResolutionCoordinator resolutionCoordinator;

    /** Future for the periodic memory usage reporter, cancelled on shutdown. */
    private volatile java.util.concurrent.ScheduledFuture<?> memoryReporterFuture;

    public GroovyLanguageServer() {
        this(new CompilationUnitFactory());
    }

    public GroovyLanguageServer(ICompilationUnitFactory compilationUnitFactory) {
        this.groovyServices = new GroovyServices(compilationUnitFactory, executorPools);
        this.groovyServices.setJavaChangeListener(this::recompileProject);

        // Register all supported build-tool importers.
        // Order matters: if both build.gradle and pom.xml exist in the same
        // directory, the first importer that claims it wins (Gradle preferred).
        this.importers = new ArrayList<>();
        this.importers.add(new GradleProjectImporter());
        MavenProjectImporter mavenImporter = new MavenProjectImporter();
        mavenImporter.setImportPool(executorPools.getImportPool());
        this.importers.add(mavenImporter);

        // Forward configuration changes to importers that need them.
        this.groovyServices.setSettingsChangeListener(this::applyImporterSettings);
    }

    /**
     * Push relevant VS Code settings to importers that can use them.
     * Each importer extracts its own relevant keys via
     * {@link ProjectImporter#applySettings(JsonObject)}.
     */
    private void applyImporterSettings(JsonObject settings) {
        if (!settings.has("groovy") || !settings.get("groovy").isJsonObject()) {
            return;
        }
        JsonObject groovy = settings.get("groovy").getAsJsonObject();
        for (ProjectImporter importer : importers) {
            importer.applySettings(groovy);
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
            // Apply log level before any other processing so all subsequent
            // log messages respect the configured level.
            if (opts.has("logLevel") && opts.get("logLevel").isJsonPrimitive()) {
                applyLogLevel(opts.get("logLevel").getAsString());
            }
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
            // Memory management settings
            if (opts.has("backfillSiblingProjects") && opts.get("backfillSiblingProjects").isJsonPrimitive()) {
                backfillSiblingProjects = opts.get("backfillSiblingProjects").getAsBoolean();
                logger.info("Backfill sibling projects: {}", backfillSiblingProjects);
            }
            if (opts.has("scopeEvictionTTLSeconds") && opts.get("scopeEvictionTTLSeconds").isJsonPrimitive()) {
                scopeEvictionTTLSeconds = opts.get("scopeEvictionTTLSeconds").getAsLong();
                logger.info("Scope eviction TTL: {}s", scopeEvictionTTLSeconds);
            }
            if (opts.has("memoryPressureThreshold") && opts.get("memoryPressureThreshold").isJsonPrimitive()) {
                double threshold = opts.get("memoryPressureThreshold").getAsDouble();
                groovyServices.getScopeManager().setMemoryPressureThreshold(threshold);
            }
            // Configurable ClassGraph rejected packages
            if (opts.has("rejectedPackages") && opts.get("rejectedPackages").isJsonArray()) {
                JsonArray arr = opts.getAsJsonArray("rejectedPackages");
                java.util.List<String> packages = new java.util.ArrayList<>();
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) {
                        String pkg = el.getAsString().trim();
                        if (!pkg.isEmpty()) {
                            packages.add(pkg);
                        }
                    }
                }
                SharedClassGraphCache.getInstance().setAdditionalRejectedPackages(packages);
            }
        }

        // Apply memory settings to scope manager
        groovyServices.getScopeManager().setScopeEvictionTTLSeconds(scopeEvictionTTLSeconds);

        // Set workspace bound on importers so build-tool root searches
        // stop at the workspace root instead of walking to the filesystem root.
        if (rootUriString != null) {
            Path wsRoot = Paths.get(URI.create(rootUriString));
            for (ProjectImporter importer : importers) {
                importer.setWorkspaceBound(wsRoot);
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
        semanticTokensOptions.setRange(true);
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
            importFuture = executorPools.getImportPool().submit(() -> importProjectsAsync(foldersSnapshot));
        } else {
            // No workspace folders — nothing to import, signal completion
            logProgress("Project import complete");
            sendStatusUpdate("ready", "Project import complete");
        }

        // Start periodic memory usage reporter (every 5 seconds)
        startMemoryReporter();

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
            Map<Path, ProjectImporter> importerMapLocal = new ConcurrentHashMap<>();
            Set<Path> claimedRoots = Collections.synchronizedSet(new LinkedHashSet<>());
            // Tracks project roots discovered AFTER a cache hit — these are
            // added to allDiscoveredRoots and will resolve lazily on first file open.
            List<Path> newUncachedRoots = new ArrayList<>();

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
                        if (ClasspathCache.isValid(cachedData, currentStamps)
                                && ClasspathCache.areClasspathEntriesPresent(cachedData, 5)) {
                            cachedDiscoveredRoots = cachedRoots.get();
                            cacheHit = true;

                            long cacheElapsed = System.currentTimeMillis() - cacheStart;
                            String cacheMsg = "Using cached classpath ("
                                    + cachedDiscoveredRoots.size() + " projects, "
                                    + cacheElapsed + "ms)";
                            logProgress(cacheMsg);
                            sendStatusUpdate("importing", cacheMsg);
                        } else {
                            logger.info("Classpath cache stamp mismatch or stale entries — will re-discover and resolve");
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
                // Start with the cached project list.
                allDiscoveredRoots = new ArrayList<>(cachedDiscoveredRoots);
                String cachedListMsg = "Using cached project list (" + allDiscoveredRoots.size() + " projects)";
                logProgress(cachedListMsg);
                sendStatusUpdate("importing", cachedListMsg);

                // We still need to populate importerMapLocal so that
                // recompileProject() can look up the right importer.
                // Assign all cached roots to the first importer that claims them.
                for (Path root : allDiscoveredRoots) {
                    for (ProjectImporter importer : importers) {
                        if (importer.claimsProject(root)) {
                            importerMapLocal.put(root, importer);
                            discoveredByImporter
                                    .computeIfAbsent(importer, k -> new ArrayList<>())
                                    .add(root);
                            claimedRoots.add(root);
                            break;
                        }
                    }
                }

                // ── Detect NEW projects not in the cache ──────────────────
                // Run a fast discovery pass to find projects that were added
                // to the workspace since the cache was built.  Discovery uses
                // directory pruning so it's very quick.
                Set<String> enabledNames = enabledImporters;
                List<Path> freshGradleRoots = new ArrayList<>();
                List<Path> freshMavenRoots = new ArrayList<>();
                for (WorkspaceFolder folder : folders) {
                    Path folderPath = Paths.get(URI.create(folder.getUri()));
                    try {
                        ProjectDiscovery.DiscoveryResult result =
                                ProjectDiscovery.discoverAll(folderPath, enabledNames);
                        freshGradleRoots.addAll(result.gradleProjects);
                        freshMavenRoots.addAll(result.mavenProjects);
                    } catch (IOException e) {
                        logger.error("Error discovering new projects in {}: {}",
                                folderPath, e.getMessage(), e);
                    }
                }

                // Find roots that are freshly discovered but NOT in the cache.
                // Use the unified discovered lists and let each root find its importer.
                List<Path> allFreshRoots = new ArrayList<>();
                allFreshRoots.addAll(freshGradleRoots);
                allFreshRoots.addAll(freshMavenRoots);

                List<Path> newRoots = newUncachedRoots;
                for (Path p : allFreshRoots) {
                    if (claimedRoots.add(p)) {
                        for (ProjectImporter importer : importers) {
                            if (importer.claimsProject(p)) {
                                newRoots.add(p);
                                importerMapLocal.put(p, importer);
                                discoveredByImporter
                                        .computeIfAbsent(importer, k -> new ArrayList<>())
                                        .add(p);
                                break;
                            }
                        }
                    }
                }

                if (!newRoots.isEmpty()) {
                    String detectedMsg = "Detected " + newRoots.size()
                            + " new project(s) not in cache: " + newRoots;
                    logProgress(detectedMsg);
                    sendStatusUpdate("importing", detectedMsg);
                    allDiscoveredRoots.addAll(newRoots);
                    // We keep cacheHit=true so Phase 2 doesn't re-resolve
                    // the cached projects.  New projects will be resolved
                    // separately after Phase 1a registration.
                }
            } else {
                // No usable cache — discover from scratch using a SINGLE
                // unified filesystem walk that finds both Gradle and Maven
                // projects with directory pruning (skips .git, node_modules,
                // build, target, etc.)
                long discoveryStart = System.currentTimeMillis();
                logProgress("Discovering projects...");
                sendStatusUpdate("importing", "Discovering projects...");

                // Determine which importer names are enabled
                Set<String> enabledNames = enabledImporters;

                List<Path> gradleRoots = new ArrayList<>();
                List<Path> mavenRoots = new ArrayList<>();

                for (WorkspaceFolder folder : folders) {
                    if (Thread.currentThread().isInterrupted()) {
                        logProgress("Project import cancelled");
                        sendStatusUpdate("ready", "Project import cancelled");
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

                // Map discovered roots to their importers using claimsProject(),
                // respecting priority (importers list order — Gradle first).
                // Collect all discovered roots and let each find its importer.
                List<Path> allFreshDiscovered = new ArrayList<>();
                allFreshDiscovered.addAll(gradleRoots);
                allFreshDiscovered.addAll(mavenRoots);

                for (Path p : allFreshDiscovered) {
                    if (claimedRoots.add(p)) {
                        for (ProjectImporter importer : importers) {
                            if (importer.claimsProject(p)) {
                                discoveredByImporter
                                        .computeIfAbsent(importer, k -> new ArrayList<>())
                                        .add(p);
                                break;
                            }
                        }
                    }
                }

                // Log per-importer discovery counts
                for (ProjectImporter importer : importers) {
                    List<Path> discovered = discoveredByImporter.getOrDefault(importer, List.of());
                    if (!discovered.isEmpty()) {
                        String msg = "Found " + discovered.size() + " " + importer.getName() + " project(s)";
                        logProgress(msg);
                        sendStatusUpdate("importing", msg);
                    } else {
                        logProgress("No " + importer.getName() + " projects found");
                        sendStatusUpdate("importing", "No " + importer.getName() + " projects found");
                    }
                }

                long discoveryElapsed = System.currentTimeMillis() - discoveryStart;

                allDiscoveredRoots = discoveredByImporter.values().stream()
                        .flatMap(List::stream).collect(Collectors.toList());

                String discoveryMsg = "Discovery completed in " + discoveryElapsed + "ms ("
                        + allDiscoveredRoots.size() + " projects)";
                logProgress(discoveryMsg);
                sendStatusUpdate("importing", discoveryMsg);
            }

            if (Thread.currentThread().isInterrupted()) {
                logProgress("Project import cancelled");
                sendStatusUpdate("ready", "Project import cancelled");
                return;
            }

            // ── Phase 1a: Register scopes ─────────────────────────────────
            if (!allDiscoveredRoots.isEmpty()) {
                String regMsg = "Discovered " + allDiscoveredRoots.size()
                        + " project(s), registering scopes...";
                logProgress(regMsg);
                sendStatusUpdate("importing", regMsg);
                groovyServices.registerDiscoveredProjects(allDiscoveredRoots);

                for (Map.Entry<ProjectImporter, List<Path>> de : discoveredByImporter.entrySet()) {
                    for (Path root : de.getValue()) {
                        importerMapLocal.put(root, de.getKey());
                    }
                }
            }

            // ── Phase 1b: Apply per-project cached classpaths ────────────
            // With the v3 per-project cache, we validate each project's
            // stamps independently.  Valid projects get their classpath
            // applied immediately; invalid or missing projects remain
            // unresolved and will be resolved lazily on first didOpen.
            Map<Path, List<String>> projectClasspaths = new LinkedHashMap<>();
            int cacheHits = 0;

            if (cachedData != null && !allDiscoveredRoots.isEmpty()) {
                for (Path root : allDiscoveredRoots) {
                    if (ClasspathCache.isValidForProject(cachedData, root)) {
                        Optional<List<String>> cached = ClasspathCache.getProjectClasspath(cachedData, root);
                        if (cached.isPresent()) {
                            projectClasspaths.put(root, cached.get());
                            cacheHits++;
                        }
                    }
                }
                if (cacheHits > 0) {
                    String appliedMsg = "Applied cached classpaths for " + cacheHits
                            + "/" + allDiscoveredRoots.size() + " project(s)";
                    logProgress(appliedMsg);
                    sendStatusUpdate("importing", appliedMsg);
                }
            }

            // ── Phase 2: Apply cached classpaths + set up lazy resolution ─
            // Unlike the old approach, we do NOT bulk-resolve uncached projects
            // here.  Instead, they resolve lazily when the user opens a file.
            projectImporterMap.putAll(importerMapLocal);

            if (!projectClasspaths.isEmpty()) {
                groovyServices.updateProjectClasspaths(projectClasspaths);
            }

            int unresolvedCount = allDiscoveredRoots.size() - cacheHits;
            if (unresolvedCount > 0) {
                logProgress(unresolvedCount + " project(s) will resolve classpath on first file open");
                sendStatusUpdate("importing", unresolvedCount + " project(s) will resolve classpath on first file open");
            }

            // ── Phase 3: Wire up the lazy resolution coordinator ──────────
            ClasspathResolutionCoordinator coordinator = new ClasspathResolutionCoordinator(
                    groovyServices.getScopeManager(),
                    groovyServices.getCompilationService(),
                    projectImporterMap,
                    executorPools);
            coordinator.setLanguageClient(client);
            coordinator.setWorkspaceRoot(workspaceRoot);
            coordinator.setAllDiscoveredRoots(allDiscoveredRoots);
            coordinator.setClasspathCacheEnabled(classpathCacheEnabled);
            coordinator.setBackfillEnabled(backfillSiblingProjects);
            this.resolutionCoordinator = coordinator;
            groovyServices.setResolutionCoordinator(coordinator);

            long totalElapsed = System.currentTimeMillis() - totalStart;
            String completeMsg = "Project discovery complete (" + totalElapsed + "ms, "
                    + cacheHits + " cached, " + unresolvedCount + " lazy)";
            logProgress(completeMsg);
            sendStatusUpdate("ready", completeMsg);

            // Start the eviction scheduler after import is complete
            groovyServices.getScopeManager().startEvictionScheduler(executorPools.getSchedulingPool());
        } catch (Exception e) {
            logger.error("Background project import failed: {}", e.getMessage(), e);
            String failMsg = "Project import failed: " + e.getMessage();
            logProgress(failMsg);
            sendStatusUpdate("error", failMsg);
        } finally {
            groovyServices.onImportComplete();
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        if (memoryReporterFuture != null) {
            memoryReporterFuture.cancel(false);
        }
        if (importFuture != null) {
            importFuture.cancel(true);
        }
        if (resolutionCoordinator != null) {
            resolutionCoordinator.shutdown();
        }
        groovyServices.getScopeManager().stopEvictionScheduler();
        groovyServices.shutdown();
        executorPools.shutdownAll();
        SharedClassGraphCache.getInstance().clear();
        com.tomaszrup.groovyls.util.SharedSourceJarIndex.getInstance().clear();
        return CompletableFuture.completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    /**
     * Custom LSP request: retrieve decompiled content for a {@code decompiled:},
     * {@code jar:}, or {@code jrt:} URI so the client can display it in a
     * read-only editor.
     *
     * @param params a JSON object with a {@code uri} string field
     * @return the decompiled source text, or {@code null} if not found
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("groovy/getDecompiledContent")
    public CompletableFuture<String> getDecompiledContent(com.google.gson.JsonObject params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.has("uri") ? params.get("uri").getAsString() : null;
            if (uri == null) {
                return null;
            }
            return groovyServices.getDecompiledContentByURI(uri);
        });
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
        if (client instanceof GroovyLanguageClient) {
            this.client = (GroovyLanguageClient) client;
        } else {
            // Wrap a plain LanguageClient (e.g. in tests) so statusUpdate is a no-op
            this.client = null;
        }
        groovyServices.connect(client);
    }

    /**
     * Send a structured status update to the client via the
     * {@code groovy/statusUpdate} custom notification.
     */
    private void sendStatusUpdate(String state, String message) {
        if (client != null) {
            try {
                client.statusUpdate(new StatusUpdateParams(state, message));
            } catch (Exception e) {
                logger.debug("Failed to send statusUpdate: {}", e.getMessage());
            }
        }
    }

    /**
     * Start a periodic timer that sends JVM memory usage to the client
     * every 5 seconds via the {@code groovy/memoryUsage} notification.
     */
    private void startMemoryReporter() {
        memoryReporterFuture = executorPools.getSchedulingPool().scheduleAtFixedRate(() -> {
            try {
                if (client == null) {
                    return;
                }
                Runtime rt = Runtime.getRuntime();
                long usedBytes = rt.totalMemory() - rt.freeMemory();
                long maxBytes = rt.maxMemory();
                int usedMB = (int) (usedBytes / (1024 * 1024));
                int maxMB = (int) (maxBytes / (1024 * 1024));
                int[] counts = groovyServices.getScopeManager().getScopeCounts();
                client.memoryUsage(new MemoryUsageParams(usedMB, maxMB,
                        counts[0], counts[1], counts[2]));
            } catch (Exception e) {
                // Ignore — client may have disconnected
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** Send a progress log message to the client (visible in output channel). */
    private void logProgress(String message) {
        logger.info(message);
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Info, message));
        }
    }

    /**
     * Dynamically set the Logback root logger level from a string value.
     * Accepted values (case-insensitive): ERROR, WARN, INFO, DEBUG, TRACE.
     * Invalid values are ignored and a warning is logged.
     */
    private static void applyLogLevel(String levelName) {
        try {
            ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.toLevel(levelName, null);
            if (level == null) {
                logger.warn("Unknown log level '{}', keeping current level", levelName);
                return;
            }
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            ch.qos.logback.classic.Level previous = root.getLevel();
            root.setLevel(level);
            logger.info("Log level changed from {} to {}", previous, level);
        } catch (Exception e) {
            logger.warn("Failed to set log level to '{}': {}", levelName, e.getMessage());
        }
    }
}
