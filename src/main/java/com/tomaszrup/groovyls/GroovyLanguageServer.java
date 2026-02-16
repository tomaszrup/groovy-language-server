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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GroovyLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger logger = LoggerFactory.getLogger(GroovyLanguageServer.class);
    private static final String GROOVY_SETTINGS_KEY = "groovy";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_IMPORTING = "importing";
    private static final String PROJECT_IMPORT_CANCELLED = "Project import cancelled";
    private GroovyLanguageClient client;

    public static void main(String[] args) throws IOException {
        // Install a global uncaught-exception handler so that unexpected
        // exceptions on any thread are logged instead of silently killing
        // the JVM process (which the client sees as an EPIPE).
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
            logger.error("Uncaught exception on thread {}: {}",
                thread.getName(), throwable.getMessage(), throwable));

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
            OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
            startServer(in, out);
        }
    }

    private static void startServer(InputStream in, OutputStream out) {
        // Redirect System.out to System.err to avoid corrupting the communication channel
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));

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
    private final AtomicReference<Future<?>> importFuture = new AtomicReference<>();
    /** Whether the on-disk classpath cache is enabled (default: true). */
    private volatile boolean classpathCacheEnabled = true;
    /**
     * Set of enabled importer names (e.g. "Gradle", "Maven"). When empty
     * all importers are enabled.  Populated from the {@code groovy.project.importers}
     * VS Code setting via {@code initializationOptions}.
     */
    private final AtomicReference<Set<String>> enabledImporters = new AtomicReference<>(Collections.emptySet());

    /** Whether backfill of sibling Gradle subprojects is enabled (default: false). */
    private volatile boolean backfillSiblingProjects = false;

    /** Scope eviction TTL in seconds (default: 300). 0 disables eviction. */
    private volatile long scopeEvictionTTLSeconds = 300;

    /**
     * Coordinator for lazy on-demand classpath resolution. Created during
     * {@link #importProjectsAsync} once the scope manager and importer map
     * are initialized.
     */
    private final AtomicReference<ClasspathResolutionCoordinator> resolutionCoordinator = new AtomicReference<>();

    /** Future for the periodic memory usage reporter, cancelled on shutdown. */
    private final AtomicReference<java.util.concurrent.ScheduledFuture<?>> memoryReporterFuture = new AtomicReference<>();

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
        if (!settings.has(GROOVY_SETTINGS_KEY) || !settings.get(GROOVY_SETTINGS_KEY).isJsonObject()) {
            return;
        }
        JsonObject groovy = settings.get(GROOVY_SETTINGS_KEY).getAsJsonObject();
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
        List<WorkspaceFolder> workspaceFolders = params.getWorkspaceFolders();
        logInitializeWorkspaceFolders(workspaceFolders);
        String workspaceUriString = resolvePrimaryWorkspaceUri(workspaceFolders);
        setWorkspaceRootIfPresent(workspaceUriString);

        applyInitializationOptions(params.getInitializationOptions());
        groovyServices.getScopeManager().setScopeEvictionTTLSeconds(scopeEvictionTTLSeconds);
        setWorkspaceBoundOnImporters(workspaceUriString);

        ServerCapabilities serverCapabilities = createServerCapabilities();
        scheduleInitialImport(workspaceFolders);

        // Start periodic memory usage reporter (every 5 seconds)
        startMemoryReporter();

        InitializeResult initializeResult = new InitializeResult(serverCapabilities);
        return CompletableFuture.completedFuture(initializeResult);
    }

    private void logInitializeWorkspaceFolders(List<WorkspaceFolder> workspaceFolders) {
        if (workspaceFolders != null && !workspaceFolders.isEmpty()) {
            logger.info("Initialize received workspace folders: {}",
                    workspaceFolders.stream().map(WorkspaceFolder::getUri).collect(Collectors.toList()));
        } else {
            logger.info("Initialize received no workspace folders");
        }
    }

    private String resolvePrimaryWorkspaceUri(List<WorkspaceFolder> workspaceFolders) {
        if (workspaceFolders == null || workspaceFolders.isEmpty()) {
            return null;
        }
        String workspaceUriString = workspaceFolders.get(0).getUri();
        logger.info("Primary workspace root selected from first folder: {}", workspaceUriString);
        return workspaceUriString;
    }

    private void setWorkspaceRootIfPresent(String workspaceUriString) {
        if (workspaceUriString == null) {
            return;
        }
        URI uri = URI.create(workspaceUriString);
        Path workspaceRoot = Paths.get(uri);
        groovyServices.setWorkspaceRoot(workspaceRoot);
    }

    private void applyInitializationOptions(Object initOptions) {
        InitializationOptionsParser.ParsedOptions parsed =
                InitializationOptionsParser.parse(initOptions);
        if (parsed == null) {
            return;
        }
        if (parsed.classpathCacheDisabled) {
            classpathCacheEnabled = false;
        }
        if (!parsed.enabledImporters.isEmpty()) {
            enabledImporters.set(parsed.enabledImporters);
        }
        if (parsed.backfillSiblingProjects != null) {
            backfillSiblingProjects = parsed.backfillSiblingProjects;
        }
        if (parsed.scopeEvictionTTLSeconds != null) {
            scopeEvictionTTLSeconds = parsed.scopeEvictionTTLSeconds;
        }
        if (parsed.memoryPressureThreshold != null) {
            groovyServices.getScopeManager().setMemoryPressureThreshold(parsed.memoryPressureThreshold);
        }
        if (!parsed.rejectedPackages.isEmpty()) {
            SharedClassGraphCache.getInstance().setAdditionalRejectedPackages(parsed.rejectedPackages);
        }
    }

    private void setWorkspaceBoundOnImporters(String workspaceUriString) {
        if (workspaceUriString == null) {
            return;
        }
        Path wsRoot = Paths.get(URI.create(workspaceUriString));
        for (ProjectImporter importer : importers) {
            importer.setWorkspaceBound(wsRoot);
        }
    }

    private ServerCapabilities createServerCapabilities() {
        CompletionOptions completionOptions = new CompletionOptions(true, Arrays.asList("."));
        ServerCapabilities serverCapabilities = new ServerCapabilities();
        serverCapabilities.setCompletionProvider(completionOptions);
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
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
        return serverCapabilities;
    }

    private void scheduleInitialImport(List<WorkspaceFolder> folders) {
        if (folders != null && !folders.isEmpty()) {
            final List<WorkspaceFolder> foldersSnapshot = new ArrayList<>(folders);
            groovyServices.setImportInProgress(true);
            importFuture.set(executorPools.getImportPool().submit(() -> importProjectsAsync(foldersSnapshot)));
            return;
        }
        logProgress("Project import complete");
        sendStatusUpdate(STATUS_READY, "Project import complete");
    }

    /**
     * Performs the heavy Gradle/Maven project discovery and import on a
     * background thread with progress reporting to the client.
     *
     * <p><b>Performance strategy (lazy two-phase import):</b>
     * <ol>
     *   <li><b>Phase 1 — Discovery:</b> discover/import project roots and register scopes.</li>
     *   <li><b>Phase 1b — Cache check:</b> validate and apply cached classpaths per project.</li>
     *   <li><b>Phase 2 — Lazy resolution:</b> unresolved projects resolve classpath on first open.</li>
     *   <li><b>Phase 3 — Finalization:</b> wire coordinator, compile open files, persist cache.</li>
     * </ol>

     * @param folders workspace folders from initialize
     */
    private void importProjectsAsync(List<WorkspaceFolder> folders) {
        long totalStart = System.currentTimeMillis();
        try {
            logger.info("Starting async project import for {} workspace folder(s): {}",
                    folders.size(),
                    folders.stream().map(WorkspaceFolder::getUri).collect(Collectors.toList()));
            Map<Path, ProjectImporter> importerMapLocal = new ConcurrentHashMap<>();
            Set<Path> claimedRoots = Collections.synchronizedSet(new LinkedHashSet<>());
            List<Path> newUncachedRoots = new ArrayList<>();
            Path workspaceRoot = groovyServices.getWorkspaceRoot();
            Map<ProjectImporter, List<Path>> discoveredByImporter = new LinkedHashMap<>();
            CacheBootstrapResult cacheBootstrap = loadValidCacheBootstrap(workspaceRoot);
            List<Path> allDiscoveredRoots = runProjectDiscoveryPhase(
                    folders,
                    cacheBootstrap,
                    importerMapLocal,
                    claimedRoots,
                    newUncachedRoots,
                    discoveredByImporter);
            if (allDiscoveredRoots == null || isImportInterrupted()) {
                return;
            }

            registerDiscoveredScopes(allDiscoveredRoots, discoveredByImporter, importerMapLocal);

            CachedClasspathApplyResult cachedClasspathResult = applyCachedProjectClasspaths(
                    cacheBootstrap.cachedData,
                    allDiscoveredRoots,
                    importerMapLocal);

            projectImporterMap.putAll(importerMapLocal);
            if (!cachedClasspathResult.projectClasspaths.isEmpty()) {
                groovyServices.updateProjectClasspaths(
                        cachedClasspathResult.projectClasspaths,
                        cachedClasspathResult.projectGroovyVersions,
                        cachedClasspathResult.projectResolvedStates);
            }

            int unresolvedCount = allDiscoveredRoots.size() - cachedClasspathResult.cacheHits;
            if (unresolvedCount > 0) {
                logProgress(unresolvedCount + " project(s) will resolve classpath on first file open");
                sendStatusUpdate(STATUS_IMPORTING, unresolvedCount + " project(s) will resolve classpath on first file open");
            }

            compileDiscoveredProjectSources(allDiscoveredRoots, discoveredByImporter);
            installResolutionCoordinator(workspaceRoot, allDiscoveredRoots);

            long totalElapsed = System.currentTimeMillis() - totalStart;
            String completeMsg = "Project discovery complete (" + totalElapsed + "ms, "
                    + cachedClasspathResult.cacheHits + " cached, " + unresolvedCount + " lazy)";
            logProgress(completeMsg);
            sendStatusUpdate(STATUS_READY, completeMsg);

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

    private CacheBootstrapResult loadValidCacheBootstrap(Path workspaceRoot) {
        if (!classpathCacheEnabled || workspaceRoot == null) {
            return new CacheBootstrapResult(false, null, null);
        }
        long cacheStart = System.currentTimeMillis();
        Optional<ClasspathCache.CacheData> cached = ClasspathCache.load(workspaceRoot);
        if (!cached.isPresent()) {
            return new CacheBootstrapResult(false, null, null);
        }

        ClasspathCache.CacheData cachedData = cached.get();
        Optional<List<Path>> cachedRoots = ClasspathCache.toDiscoveredProjectsList(cachedData);
        if (!cachedRoots.isPresent()) {
            logger.info("Classpath cache has no discovered-projects list — will discover");
            return new CacheBootstrapResult(false, null, cachedData);
        }

        Map<String, String> currentStamps = ClasspathCache.computeBuildFileStamps(cachedRoots.get());
        boolean valid = ClasspathCache.isValid(cachedData, currentStamps)
                && ClasspathCache.areClasspathEntriesPresent(cachedData, 5);
        if (!valid) {
            logger.info("Classpath cache stamp mismatch or stale entries — will re-discover and resolve");
            return new CacheBootstrapResult(false, null, cachedData);
        }

        long cacheElapsed = System.currentTimeMillis() - cacheStart;
        String cacheMsg = "Using cached classpath (" + cachedRoots.get().size() + " projects, " + cacheElapsed + "ms)";
        logProgress(cacheMsg);
        sendStatusUpdate(STATUS_IMPORTING, cacheMsg);
        return new CacheBootstrapResult(true, cachedRoots.get(), cachedData);
    }

    private List<Path> runProjectDiscoveryPhase(
            List<WorkspaceFolder> folders,
            CacheBootstrapResult cacheBootstrap,
            Map<Path, ProjectImporter> importerMapLocal,
            Set<Path> claimedRoots,
            List<Path> newUncachedRoots,
            Map<ProjectImporter, List<Path>> discoveredByImporter) {
        if (cacheBootstrap.cacheHit && cacheBootstrap.cachedDiscoveredRoots != null) {
            return runCachedDiscoveryPath(
                    folders,
                    cacheBootstrap.cachedDiscoveredRoots,
                    importerMapLocal,
                    claimedRoots,
                    newUncachedRoots,
                    discoveredByImporter);
        }
        return runFreshDiscoveryPath(folders, importerMapLocal, claimedRoots, discoveredByImporter);
    }

    private List<Path> runCachedDiscoveryPath(
            List<WorkspaceFolder> folders,
            List<Path> cachedDiscoveredRoots,
            Map<Path, ProjectImporter> importerMapLocal,
            Set<Path> claimedRoots,
            List<Path> newUncachedRoots,
            Map<ProjectImporter, List<Path>> discoveredByImporter) {
        List<Path> allDiscoveredRoots = new ArrayList<>(cachedDiscoveredRoots);
        String cachedListMsg = "Using cached project list (" + allDiscoveredRoots.size() + " projects)";
        logProgress(cachedListMsg);
        sendStatusUpdate(STATUS_IMPORTING, cachedListMsg);

        assignRootsToImporters(allDiscoveredRoots, importerMapLocal, discoveredByImporter, claimedRoots, null);

        List<Path> freshRoots = discoverRootsForFolders(folders, "Error discovering new projects in {}: {}");
        assignRootsToImporters(freshRoots, importerMapLocal, discoveredByImporter, claimedRoots, newUncachedRoots);
        if (!newUncachedRoots.isEmpty()) {
            String detectedMsg = "Detected " + newUncachedRoots.size() + " new project(s) not in cache: " + newUncachedRoots;
            logProgress(detectedMsg);
            sendStatusUpdate(STATUS_IMPORTING, detectedMsg);
            allDiscoveredRoots.addAll(newUncachedRoots);
        }
        return allDiscoveredRoots;
    }

    private List<Path> runFreshDiscoveryPath(
            List<WorkspaceFolder> folders,
            Map<Path, ProjectImporter> importerMapLocal,
            Set<Path> claimedRoots,
            Map<ProjectImporter, List<Path>> discoveredByImporter) {
        long discoveryStart = System.currentTimeMillis();
        logProgress("Discovering projects...");
        sendStatusUpdate(STATUS_IMPORTING, "Discovering projects...");

        List<Path> allFreshDiscovered = new ArrayList<>();
        Set<String> enabledNames = enabledImporters.get();
        List<Path> gradleRoots = new ArrayList<>();
        List<Path> mavenRoots = new ArrayList<>();

        for (WorkspaceFolder folder : folders) {
            if (isImportInterrupted()) {
                return Collections.emptyList();
            }
            Path folderPath = Paths.get(URI.create(folder.getUri()));
            collectDiscoveredProjects(folderPath, enabledNames, gradleRoots, mavenRoots,
                    "Error discovering projects in {}: {}");
        }

        allFreshDiscovered.addAll(gradleRoots);
        allFreshDiscovered.addAll(mavenRoots);
        assignRootsToImporters(allFreshDiscovered, importerMapLocal, discoveredByImporter, claimedRoots, null);

        logPerImporterDiscoveryCounts(discoveredByImporter);

        long discoveryElapsed = System.currentTimeMillis() - discoveryStart;
        List<Path> allDiscoveredRoots = discoveredByImporter.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        String discoveryMsg = "Discovery completed in " + discoveryElapsed + "ms ("
                + allDiscoveredRoots.size() + " projects)";
        logProgress(discoveryMsg);
        sendStatusUpdate(STATUS_IMPORTING, discoveryMsg);
        return allDiscoveredRoots;
    }

    private List<Path> discoverRootsForFolders(List<WorkspaceFolder> folders, String errorPattern) {
        Set<String> enabledNames = enabledImporters.get();
        List<Path> gradleRoots = new ArrayList<>();
        List<Path> mavenRoots = new ArrayList<>();
        for (WorkspaceFolder folder : folders) {
            Path folderPath = Paths.get(URI.create(folder.getUri()));
            collectDiscoveredProjects(folderPath, enabledNames, gradleRoots, mavenRoots, errorPattern);
        }
        List<Path> allFreshRoots = new ArrayList<>();
        allFreshRoots.addAll(gradleRoots);
        allFreshRoots.addAll(mavenRoots);
        return allFreshRoots;
    }

    private void assignRootsToImporters(
            List<Path> roots,
            Map<Path, ProjectImporter> importerMapLocal,
            Map<ProjectImporter, List<Path>> discoveredByImporter,
            Set<Path> claimedRoots,
            List<Path> acceptedRoots) {
        for (Path root : roots) {
            if (!claimedRoots.add(root)) {
                continue;
            }
            for (ProjectImporter importer : importers) {
                if (importer.claimsProject(root)) {
                    importerMapLocal.put(root, importer);
                    discoveredByImporter.computeIfAbsent(importer, k -> new ArrayList<>()).add(root);
                    if (acceptedRoots != null) {
                        acceptedRoots.add(root);
                    }
                    break;
                }
            }
        }
    }

    private void logPerImporterDiscoveryCounts(Map<ProjectImporter, List<Path>> discoveredByImporter) {
        for (ProjectImporter importer : importers) {
            List<Path> discovered = discoveredByImporter.getOrDefault(importer, List.of());
            if (!discovered.isEmpty()) {
                String msg = "Found " + discovered.size() + " " + importer.getName() + " project(s)";
                logProgress(msg);
                sendStatusUpdate(STATUS_IMPORTING, msg);
            } else {
                String msg = "No " + importer.getName() + " projects found";
                logProgress(msg);
                sendStatusUpdate(STATUS_IMPORTING, msg);
            }
        }
    }

    private void registerDiscoveredScopes(
            List<Path> allDiscoveredRoots,
            Map<ProjectImporter, List<Path>> discoveredByImporter,
            Map<Path, ProjectImporter> importerMapLocal) {
        if (allDiscoveredRoots.isEmpty()) {
            return;
        }
        String regMsg = "Discovered " + allDiscoveredRoots.size() + " project(s), registering scopes...";
        logProgress(regMsg);
        sendStatusUpdate(STATUS_IMPORTING, regMsg);
        groovyServices.registerDiscoveredProjects(allDiscoveredRoots);

        for (Map.Entry<ProjectImporter, List<Path>> entry : discoveredByImporter.entrySet()) {
            for (Path root : entry.getValue()) {
                importerMapLocal.put(root, entry.getKey());
            }
        }
    }

    private CachedClasspathApplyResult applyCachedProjectClasspaths(
            ClasspathCache.CacheData cachedData,
            List<Path> allDiscoveredRoots,
            Map<Path, ProjectImporter> importerMapLocal) {
        Map<Path, List<String>> projectClasspaths = new LinkedHashMap<>();
        Map<Path, String> projectGroovyVersions = new LinkedHashMap<>();
        Map<Path, Boolean> projectResolvedStates = new LinkedHashMap<>();
        int cacheHits = 0;

        if (cachedData != null && !allDiscoveredRoots.isEmpty()) {
            for (Path root : allDiscoveredRoots) {
                Optional<CachedClasspathCandidate> candidate = getCachedClasspathCandidate(cachedData, root, importerMapLocal);
                if (candidate.isPresent()) {
                    CachedClasspathCandidate cachedCandidate = candidate.get();
                    projectClasspaths.put(root, cachedCandidate.classpath);
                    projectResolvedStates.put(root, cachedCandidate.markResolved);
                    ClasspathCache.getProjectGroovyVersion(cachedData, root)
                            .ifPresent(version -> projectGroovyVersions.put(root, version));
                    if (cachedCandidate.markResolved) {
                        cacheHits++;
                    } else {
                        logger.info("Cached classpath for {} is incomplete; applying classpath but keeping scope unresolved",
                                root);
                    }
                }
            }

            if (cacheHits > 0) {
                String appliedMsg = "Applied cached classpaths for " + cacheHits
                        + "/" + allDiscoveredRoots.size() + " project(s)";
                logProgress(appliedMsg);
                sendStatusUpdate(STATUS_IMPORTING, appliedMsg);
            }
        }

        return new CachedClasspathApplyResult(projectClasspaths, projectGroovyVersions, projectResolvedStates, cacheHits);
    }

    private Optional<CachedClasspathCandidate> getCachedClasspathCandidate(
            ClasspathCache.CacheData cachedData,
            Path root,
            Map<Path, ProjectImporter> importerMapLocal) {
        if (!ClasspathCache.isValidForProject(cachedData, root)) {
            return Optional.empty();
        }
        Optional<List<String>> cached = ClasspathCache.getProjectClasspath(cachedData, root);
        if (!cached.isPresent()) {
            return Optional.empty();
        }
        List<String> classpath = cached.get();
        ProjectImporter importer = importerMapLocal.get(root);
        boolean markResolved = importer == null || importer.shouldMarkClasspathResolved(root, classpath);
        return Optional.of(new CachedClasspathCandidate(classpath, markResolved));
    }

    private void compileDiscoveredProjectSources(
            List<Path> allDiscoveredRoots,
            Map<ProjectImporter, List<Path>> discoveredByImporter) {
        if (allDiscoveredRoots.isEmpty()) {
            return;
        }
        for (Map.Entry<ProjectImporter, List<Path>> entry : discoveredByImporter.entrySet()) {
            ProjectImporter importer = entry.getKey();
            List<Path> roots = entry.getValue();
            if (!roots.isEmpty()) {
                String compileMsg = "Compiling " + importer.getName() + " sources ("
                        + roots.size() + " project(s))...";
                logProgress(compileMsg);
                sendStatusUpdate(STATUS_IMPORTING, compileMsg);
                compileSourcesSafely(importer, roots);
            }
        }
    }

    private void installResolutionCoordinator(Path workspaceRoot, List<Path> allDiscoveredRoots) {
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
        this.resolutionCoordinator.set(coordinator);
        groovyServices.setResolutionCoordinator(coordinator);
    }

    private boolean isImportInterrupted() {
        if (!Thread.currentThread().isInterrupted()) {
            return false;
        }
        logProgress(PROJECT_IMPORT_CANCELLED);
        sendStatusUpdate(STATUS_READY, PROJECT_IMPORT_CANCELLED);
        return true;
    }

    private static final class CacheBootstrapResult {
        private final boolean cacheHit;
        private final List<Path> cachedDiscoveredRoots;
        private final ClasspathCache.CacheData cachedData;

        private CacheBootstrapResult(boolean cacheHit, List<Path> cachedDiscoveredRoots, ClasspathCache.CacheData cachedData) {
            this.cacheHit = cacheHit;
            this.cachedDiscoveredRoots = cachedDiscoveredRoots;
            this.cachedData = cachedData;
        }
    }

    private static final class CachedClasspathApplyResult {
        private final Map<Path, List<String>> projectClasspaths;
        private final Map<Path, String> projectGroovyVersions;
        private final Map<Path, Boolean> projectResolvedStates;
        private final int cacheHits;

        private CachedClasspathApplyResult(
                Map<Path, List<String>> projectClasspaths,
                Map<Path, String> projectGroovyVersions,
                Map<Path, Boolean> projectResolvedStates,
                int cacheHits) {
            this.projectClasspaths = projectClasspaths;
            this.projectGroovyVersions = projectGroovyVersions;
            this.projectResolvedStates = projectResolvedStates;
            this.cacheHits = cacheHits;
        }
    }

    private static final class CachedClasspathCandidate {
        private final List<String> classpath;
        private final boolean markResolved;

        private CachedClasspathCandidate(List<String> classpath, boolean markResolved) {
            this.classpath = classpath;
            this.markResolved = markResolved;
        }
    }

    private void collectDiscoveredProjects(Path folderPath, Set<String> enabledNames,
            List<Path> gradleRoots, List<Path> mavenRoots, String errorMessagePattern) {
        try {
            ProjectDiscovery.DiscoveryResult result = ProjectDiscovery.discoverAll(folderPath, enabledNames);
            gradleRoots.addAll(result.gradleProjects);
            mavenRoots.addAll(result.mavenProjects);
        } catch (IOException e) {
            logger.error(errorMessagePattern, folderPath, e.getMessage(), e);
        }
    }

    private void compileSourcesSafely(ProjectImporter importer, List<Path> roots) {
        try {
            importer.compileSources(roots);
        } catch (Exception e) {
            logger.warn("Source compilation failed for {}: {}", importer.getName(), e.getMessage());
        }
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        Future<?> reporterFuture = memoryReporterFuture.getAndSet(null);
        if (reporterFuture != null) {
            reporterFuture.cancel(false);
        }
        Future<?> importTask = importFuture.get();
        if (importTask != null) {
            importTask.cancel(true);
        }
        ClasspathResolutionCoordinator coordinator = resolutionCoordinator.get();
        if (coordinator != null) {
            coordinator.shutdown();
        }
        groovyServices.getScopeManager().stopEvictionScheduler();
        groovyServices.shutdown();
        executorPools.shutdownAll();
        SharedClassGraphCache.getInstance().clear();
        com.tomaszrup.groovyls.compiler.SharedClasspathIndexCache.getInstance().clear();
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
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest(Protocol.REQUEST_GET_DECOMPILED_CONTENT)
    public CompletableFuture<String> getDecompiledContent(com.google.gson.JsonObject params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.has("uri") ? params.get("uri").getAsString() : null;
            if (uri == null) {
                return null;
            }
            return groovyServices.getDecompiledContentByURI(uri);
        });
    }

    /**
     * Custom LSP request: resolve a URI form suitable for Java definition providers.
     *
     * @param params a JSON object with a {@code uri} string field
     * @return Java-provider-compatible URI string, or {@code null} if not resolvable
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest(Protocol.REQUEST_GET_JAVA_NAVIGATION_URI)
    public CompletableFuture<String> getJavaNavigationUri(com.google.gson.JsonObject params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.has("uri") ? params.get("uri").getAsString() : null;
            if (uri == null) {
                return null;
            }
            return groovyServices.getJavaNavigationURI(uri);
        });
    }

    /**
     * Custom LSP request: returns custom protocol contract version used by
     * extension↔server Groovy-specific messages.
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest(Protocol.REQUEST_GET_PROTOCOL_VERSION)
    public CompletableFuture<String> getProtocolVersion() {
        return CompletableFuture.completedFuture(Protocol.VERSION);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return groovyServices;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return (WorkspaceService) getTextDocumentService();
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
        java.util.concurrent.ScheduledFuture<?> reporterFuture = executorPools.getSchedulingPool().scheduleAtFixedRate(() -> {
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
        memoryReporterFuture.set(reporterFuture);
    }

    /** Send a progress log message to the client (visible in output channel). */
    private void logProgress(String message) {
        logger.info(message);
        if (client != null) {
            client.logMessage(new MessageParams(MessageType.Info, message));
        }
    }
}
