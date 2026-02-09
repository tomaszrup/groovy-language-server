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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tomaszrup.groovyls.compiler.SharedClassGraphCache;
import com.tomaszrup.groovyls.config.ClasspathCache;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import com.tomaszrup.groovyls.importers.GradleProjectImporter;
import com.tomaszrup.groovyls.importers.MavenProjectImporter;
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
     */
    private void recompileProject(Path projectRoot) {
        ProjectImporter importer = projectImporterMap.get(projectRoot);
        if (importer != null) {
            logger.info("Recompiling {} project: {}", importer.getName(), projectRoot);
            // Invalidate classpath cache — build files may have changed
            Path workspaceRoot = groovyServices.getWorkspaceRoot();
            if (classpathCacheEnabled && workspaceRoot != null) {
                ClasspathCache.invalidate(workspaceRoot);
            }
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
        try {
            Map<Path, List<String>> projectClasspaths = new ConcurrentHashMap<>();
            Map<Path, ProjectImporter> importerMapLocal = new ConcurrentHashMap<>();
            Set<Path> claimedRoots = Collections.synchronizedSet(new LinkedHashSet<>());

            // Phase 1: Discover all projects (fast, sequential per-folder)
            // Map: importer → list of discovered project roots
            Map<ProjectImporter, List<Path>> discoveredByImporter = new LinkedHashMap<>();
            for (WorkspaceFolder folder : folders) {
                if (Thread.currentThread().isInterrupted()) {
                    logProgress("Project import cancelled");
                    return;
                }
                Path folderPath = Paths.get(URI.create(folder.getUri()));

                for (ProjectImporter importer : importers) {
                    try {
                        logProgress("Discovering " + importer.getName() + " projects...");
                        List<Path> projects = importer.discoverProjects(folderPath);
                        if (!projects.isEmpty()) {
                            logProgress("Found " + projects.size() + " " + importer.getName() + " project(s)");
                            // Filter out roots already claimed by a higher-priority importer
                            List<Path> unclaimed = new ArrayList<>();
                            for (Path p : projects) {
                                if (claimedRoots.add(p)) {
                                    unclaimed.add(p);
                                } else {
                                    logger.info("Skipping {} project at {} — already claimed by another importer",
                                            importer.getName(), p);
                                }
                            }
                            if (!unclaimed.isEmpty()) {
                                discoveredByImporter
                                        .computeIfAbsent(importer, k -> new ArrayList<>())
                                        .addAll(unclaimed);
                            }
                        }
                    } catch (IOException e) {
                        logger.error("Error discovering {} projects in {}: {}",
                                importer.getName(), folderPath, e.getMessage(), e);
                    }
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                logProgress("Project import cancelled");
                return;
            }

            // Collect all discovered roots for cache key computation
            List<Path> allDiscoveredRoots = discoveredByImporter.values().stream()
                    .flatMap(List::stream).collect(Collectors.toList());

            // Phase 1a: Register discovered project scopes IMMEDIATELY so
            // that didOpen can compile files with basic syntax while the
            // classpath is being resolved in the background.
            if (!allDiscoveredRoots.isEmpty()) {
                logProgress("Discovered " + allDiscoveredRoots.size()
                        + " project(s), registering scopes...");
                groovyServices.registerDiscoveredProjects(allDiscoveredRoots);

                // Assign importers for discovered roots
                for (Map.Entry<ProjectImporter, List<Path>> de : discoveredByImporter.entrySet()) {
                    for (Path root : de.getValue()) {
                        importerMapLocal.put(root, de.getKey());
                    }
                }
            }

            // Phase 1b: Check classpath cache
            Path workspaceRoot = groovyServices.getWorkspaceRoot();
            boolean cacheHit = false;
            if (classpathCacheEnabled && workspaceRoot != null && !allDiscoveredRoots.isEmpty()) {
                Map<String, String> currentHashes = ClasspathCache.computeBuildFileHashes(allDiscoveredRoots);
                Optional<ClasspathCache.CacheData> cached = ClasspathCache.load(workspaceRoot);
                if (cached.isPresent() && ClasspathCache.isValid(cached.get(), currentHashes)) {
                    logProgress("Using cached classpath (build files unchanged)");
                    Map<Path, List<String>> cachedClasspaths = ClasspathCache.toClasspathMap(cached.get());
                    projectClasspaths.putAll(cachedClasspaths);
                    cacheHit = true;
                } else {
                    logger.info("Classpath cache miss — will resolve classpaths");
                }
            }

            // Phase 2: Resolve classpaths per importer, in parallel across
            //          importers.  Uses resolveClasspaths() which SKIPS the
            //          expensive compilation step and only resolves dependency
            //          JARs + discovers existing class-output directories.
            //          (skipped entirely when cache hit)
            if (!cacheHit) {
                int totalProjects = discoveredByImporter.values().stream()
                        .mapToInt(List::size).sum();
                logProgress("Resolving classpaths for " + totalProjects + " project(s) across "
                        + discoveredByImporter.size() + " build tool(s)...");

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

                // Wait for all importers to finish
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

                // Save classpath cache for next startup
                if (classpathCacheEnabled && workspaceRoot != null && !projectClasspaths.isEmpty()) {
                    Map<String, String> hashes = ClasspathCache.computeBuildFileHashes(allDiscoveredRoots);
                    ClasspathCache.save(workspaceRoot, projectClasspaths, hashes);
                }
            }

            // Phase 3: Update all registered scopes with resolved classpaths
            projectImporterMap.putAll(importerMapLocal);

            if (!projectClasspaths.isEmpty()) {
                logProgress("Updating classpaths for " + projectClasspaths.size() + " project(s)...");
                groovyServices.updateProjectClasspaths(projectClasspaths);
            }
            logProgress("Project import complete");
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
