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

import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.*;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

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
    private Map<Path, List<String>> registeredProjects = new LinkedHashMap<>();

    public GroovyLanguageServer() {
        this(new CompilationUnitFactory());
    }

    public GroovyLanguageServer(ICompilationUnitFactory compilationUnitFactory) {
        this.groovyServices = new GroovyServices(compilationUnitFactory);
        this.groovyServices.setJavaChangeListener(this::recompileGradleProject);
    }

    /**
     * Called when Java/Gradle files change in a registered project.
     * Runs Gradle compile tasks to update build/classes so the Groovy
     * compilation unit picks up the changes.
     */
    private void recompileGradleProject(Path projectRoot) {
        logger.info("Recompiling Gradle project: {}", projectRoot);
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile());

        try (ProjectConnection connection = connector.connect()) {
            try {
                connection.newBuild()
                        .forTasks("classes", "testClasses")
                        .setStandardOutput(System.out)
                        .setStandardError(System.err)
                        .run();
                logger.info("Gradle recompile succeeded for {}", projectRoot);
            } catch (Exception buildEx) {
                logger.warn("Gradle recompile failed for {}: {}", projectRoot, buildEx.getMessage());
                try {
                    connection.newBuild()
                            .forTasks("classes")
                            .setStandardOutput(System.out)
                            .setStandardError(System.err)
                            .run();
                } catch (Exception ex) {
                    logger.warn("Gradle recompile fallback also failed for {}: {}", projectRoot, ex.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Could not connect to Gradle for recompile of {}: {}", projectRoot, e.getMessage());
        }
    }

    private List<Path> discoverGradleProjects(Path root) throws IOException {
        List<Path> gradleProjects = new ArrayList<>();
        try (Stream<Path> fileStream = Files.walk(root)) {
            fileStream
                    .filter(Files::isRegularFile)
                    .filter(p -> Set.of("build.gradle", "build.gradle.kts").contains(p.getFileName().toString()))
                    .map(buildFile -> buildFile.getParent())
                    .filter(this::isJvmGradleProject)
                    .forEach(gradleProjects::add);
        }
        return gradleProjects;
    }

    private boolean isJvmGradleProject(Path projectDir) {
        // Only import Gradle projects that contain Java or Groovy sources
        return Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/groovy"))
                || Files.isDirectory(projectDir.resolve("src/test/java"))
                || Files.isDirectory(projectDir.resolve("src/test/groovy"));
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        String rootUriString = params.getRootUri();
        if (rootUriString != null) {
            URI uri = URI.create(rootUriString);
            Path workspaceRoot = Paths.get(uri);
            groovyServices.setWorkspaceRoot(workspaceRoot);
        }

        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null) {
            // Collect all Gradle projects and their classpaths first,
            // then register them in batch so subproject exclusions can be computed
            Map<Path, List<String>> projectClasspaths = new LinkedHashMap<>();
            for (WorkspaceFolder folder : folders) {
                Path folderPath = Paths.get(URI.create(folder.getUri()));
                try {
                    List<Path> gradleProjects = discoverGradleProjects(folderPath);
                    for (Path gradleProject : gradleProjects) {
                        if (client != null) {
                            client.showMessage(new MessageParams(
                                    MessageType.Info,
                                    "Importing Gradle project: " + gradleProject
                            ));
                        }
                        List<String> classpathList = importGradleProject(gradleProject);
                        projectClasspaths.put(gradleProject, classpathList);
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
            if (!projectClasspaths.isEmpty()) {
                registeredProjects = projectClasspaths;
                groovyServices.addProjects(projectClasspaths);
            }
        }

        CompletionOptions completionOptions = new CompletionOptions(false, Arrays.asList("."));
        ServerCapabilities serverCapabilities = new ServerCapabilities();
        serverCapabilities.setCompletionProvider(completionOptions);
        serverCapabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        serverCapabilities.setDocumentSymbolProvider(true);
        serverCapabilities.setWorkspaceSymbolProvider(true);
        serverCapabilities.setDocumentSymbolProvider(true);
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

        InitializeResult initializeResult = new InitializeResult(serverCapabilities);
        return CompletableFuture.completedFuture(initializeResult);
    }

    public List<String> importGradleProject(Path folderPath) {
        List<String> classpathList = new ArrayList<>();
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(folderPath.toFile());

        try (ProjectConnection connection = connector.connect()) {
            // Try to compile both main and test classes, but don't fail if tasks don't exist
            try {
                connection.newBuild()
                        .forTasks("classes", "testClasses")
                        .setStandardOutput(System.out)
                        .setStandardError(System.err)
                        .run();
            } catch (Exception buildEx) {
                logger.warn("Could not run compile tasks for project {}: {}", folderPath, buildEx.getMessage());
                // Fallback: try just 'classes'
                try {
                    connection.newBuild()
                            .forTasks("classes")
                            .setStandardOutput(System.out)
                            .setStandardError(System.err)
                            .run();
                } catch (Exception ex) {
                    logger.warn("Could not run 'classes' task for project {}: {}", folderPath, ex.getMessage());
                }
            }

            // Resolve all dependency jars from Gradle configurations via init script
            classpathList.addAll(resolveClasspathViaInitScript(connection));

            // Also add discovered class output dirs (build/classes/**)
            classpathList.addAll(discoverClassDirs(folderPath));

            logger.info("Classpath for project {}: {} entries", folderPath, classpathList.size());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return classpathList;
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
                    .setStandardError(System.err)
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
            try (Stream<Path> stream = Files.walk(classesRoot, 2)) {
                stream
                        .filter(Files::isDirectory)
                        .map(Path::toString)
                        .forEach(classDirs::add);
            }
        }
        return classDirs;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
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
}
