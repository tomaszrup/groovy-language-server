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
import findJava from "./utils/findJava";
import * as path from "path";
import * as vscode from "vscode";
import * as net from "net";
import {
  LanguageClient,
  LanguageClientOptions,
  Executable,
  ServerOptions,
  State,
  StreamInfo,
  ErrorAction,
  CloseAction,
  ErrorHandler,
  Message,
} from "vscode-languageclient/node";

const MISSING_JAVA_ERROR =
  "Could not locate valid JDK. To configure JDK manually, use the groovy.java.home setting.";
const INVALID_JAVA_ERROR =
  "The groovy.java.home setting does not point to a valid JDK.";
const INITIALIZING_MESSAGE = "Initializing Groovy language server...";
const RELOAD_WINDOW_MESSAGE =
  "To apply new settings for Groovy, please reload the window.";
const STARTUP_ERROR = "The Groovy extension failed to start.";
const SERVER_CRASHED_MESSAGE = "The Groovy language server has crashed.";
const LABEL_RESTART_SERVER = "Restart Server";
const LABEL_RELOAD_WINDOW = "Reload Window";
const MAX_SERVER_RESTARTS = 5;

// JVM argument prefixes that could allow arbitrary code execution when set
// via workspace-level settings. If any of these are detected in
// groovy.java.vmargs, the language server will refuse to start.
const BLOCKED_VM_ARG_PREFIXES = [
  "-javaagent:",
  "-agentpath:",
  "-agentlib:",
  "-Xbootclasspath",
  "-Xbootclasspath/a:",
  "-Xbootclasspath/p:",
  "-XX:+EnableDynamicAgentLoading",
];

/**
 * Estimate appropriate JVM heap size by counting build files in the workspace.
 * Each Gradle/Maven project scope consumes ~30-40 MB (classloader, AST,
 * source locator, etc.), so we scale linearly from a base.
 *
 * When scope eviction is enabled (TTL > 0), only a few scopes are expected
 * to be fully loaded at any time, so we use a smaller per-project estimate
 * capped to a maximum number of active scopes.
 */
export async function estimateHeapSize(): Promise<number> {
  const MIN_HEAP = 512;
  const MAX_HEAP = 4096;
  const BASE_HEAP = 256;

  const config = vscode.workspace.getConfiguration("groovy");
  const evictionTTL = config.get<number>("memory.scopeEvictionTTL") ?? 300;
  const evictionEnabled = evictionTTL > 0;

  // Each active scope with AST + classloader + ClassGraph scan realistically
  // needs ~100-160 MB based on real-world usage. With eviction, only a handful
  // of scopes are loaded at once; without eviction, every discovered project
  // is potentially active.
  const MAX_ACTIVE_SCOPES = 8;
  const perProjectMB = config.get<number>("memory.perProjectMB") ?? 128;
  const PER_PROJECT_MB = evictionEnabled ? perProjectMB : Math.floor(perProjectMB * 1.25);

  try {
    const buildFiles = await vscode.workspace.findFiles(
      "**/{build.gradle,build.gradle.kts,pom.xml}",
      "**/{build,node_modules,.gradle}/**",
      500
    );
    const projectCount = buildFiles.length;
    const effectiveCount = evictionEnabled
      ? Math.min(projectCount, MAX_ACTIVE_SCOPES)
      : projectCount;
    const computed = BASE_HEAP + effectiveCount * PER_PROJECT_MB;
    return Math.max(MIN_HEAP, Math.min(MAX_HEAP, computed));
  } catch {
    return MIN_HEAP;
  }
}

let extensionContext: vscode.ExtensionContext | null = null;
let serverCrashCount = 0;
/** True when the user (or code) intentionally stops the server. */
let isIntentionalStop = false;
let languageClient: LanguageClient | null = null;
let javaPath: string | null = null;
let outputChannel: vscode.OutputChannel | null = null;
let statusBarItem: vscode.StatusBarItem | null = null;
/** Last known memory usage string from the server (e.g. "128/512 MB"). */
let lastMemoryText: string | null = null;
/** Last known scope counts from the server. */
let lastScopeCounts: { active: number; evicted: number; total: number } | null = null;
/** Current status bar state, used to re-render when memory updates arrive. */
let currentStatusState: "starting" | "importing" | "ready" | "error" | "stopped" = "stopped";

export function activate(context: vscode.ExtensionContext) {
  extensionContext = context;
  javaPath = findJava();

  // Create a dedicated output channel for server logs
  outputChannel = vscode.window.createOutputChannel("Groovy Language Server");
  context.subscriptions.push(outputChannel);

  // Create a status bar item to show server state
  statusBarItem = vscode.window.createStatusBarItem(
    vscode.StatusBarAlignment.Left
  );
  statusBarItem.name = "Groovy Language Server";
  statusBarItem.command = "groovy.showOutputChannel";
  context.subscriptions.push(statusBarItem);

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration(onDidChangeConfiguration)
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("groovy.restartServer", restartLanguageServer)
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("groovy.showOutputChannel", () => {
      outputChannel?.show();
    })
  );

  startLanguageServer();
}

export function deactivate(): Thenable<void> | undefined {
  if (!languageClient) {
    return undefined;
  }
  isIntentionalStop = true;
  return languageClient.stop();
}

export function setStatusBar(
  state: "starting" | "importing" | "ready" | "error" | "stopped",
  detail?: string
): void {
  if (!statusBarItem) {
    return;
  }
  currentStatusState = state;
  const memSuffix = getMemorySuffix();
  switch (state) {
    case "starting":
      statusBarItem.text = "$(sync~spin) Groovy: Starting…";
      statusBarItem.tooltip = "Groovy Language Server: Starting…";
      statusBarItem.show();
      break;
    case "importing": {
      const shortDetail = detail ? shortenDetail(detail) : "Importing projects…";
      statusBarItem.text = `$(sync~spin) Groovy: ${shortDetail}`;
      statusBarItem.tooltip =
        "Groovy Language Server: " + (detail || "Importing projects…");
      statusBarItem.show();
      break;
    }
    case "ready": {
      statusBarItem.text = `$(check) Groovy${memSuffix}`;
      let tooltip = "Groovy Language Server: Ready";
      if (lastScopeCounts && lastScopeCounts.total > 0) {
        tooltip += ` (${lastScopeCounts.active} active`;
        if (lastScopeCounts.evicted > 0) {
          tooltip += `, ${lastScopeCounts.evicted} evicted`;
        }
        tooltip += ` of ${lastScopeCounts.total} projects)`;
      }
      tooltip += " — click to show output";
      statusBarItem.tooltip = tooltip;
      statusBarItem.show();
      break;
    }
    case "error":
      statusBarItem.text = "$(error) Groovy";
      statusBarItem.tooltip =
        "Groovy Language Server: Error (click to show output)";
      statusBarItem.show();
      break;
    case "stopped":
      statusBarItem.hide();
      break;
  }
}

/**
 * Returns a formatted memory suffix for the status bar (e.g. " [128/512 MB]")
 * if the setting is enabled and memory data is available, otherwise "".
 */
export function getMemorySuffix(): string {
  const config = vscode.workspace.getConfiguration("groovy");
  if (!config.get<boolean>("showMemoryUsage") || !lastMemoryText) {
    return "";
  }
  return ` [${lastMemoryText}]`;
}

/**
 * Shorten a server progress message for display in the status bar.
 * Keeps it concise so it doesn't take too much horizontal space.
 */
export function shortenDetail(message: string): string {
  // Truncate overly long messages (e.g. long project names)
  const maxLen = 60;
  if (message.length > maxLen) {
    return message.substring(0, maxLen - 1) + "…";
  }
  return message;
}

function onDidChangeConfiguration(event: vscode.ConfigurationChangeEvent) {
  if (
    event.affectsConfiguration("groovy.java.home") ||
    event.affectsConfiguration("groovy.java.vmargs") ||
    event.affectsConfiguration("groovy.debug.serverPort") ||
    event.affectsConfiguration("groovy.logLevel") ||
    event.affectsConfiguration("groovy.semanticHighlighting.enabled") ||
    event.affectsConfiguration("groovy.formatting.enabled") ||
    event.affectsConfiguration("groovy.memory.scopeEvictionTTL") ||
    event.affectsConfiguration("groovy.memory.backfillSiblingProjects") ||
    event.affectsConfiguration("groovy.memory.pressureThreshold") ||
    event.affectsConfiguration("groovy.memory.rejectedPackages")
  ) {
    javaPath = findJava();
    //we're going to try to kill the language server and then restart
    //it with the new settings
    restartLanguageServer();
  } else if (event.affectsConfiguration("groovy.showMemoryUsage")) {
    // Toggling memory display doesn't need a restart — just re-render
    if (currentStatusState === "ready") {
      setStatusBar("ready");
    }
  }
}

function restartLanguageServer() {
  if (!languageClient) {
    startLanguageServer();
    return;
  }
  isIntentionalStop = true;
  let oldLanguageClient = languageClient;
  languageClient = null;
  oldLanguageClient.stop().then(
    () => {
      isIntentionalStop = false;
      startLanguageServer();
    },
    () => {
      isIntentionalStop = false;
      //something went wrong restarting the language server...
      //this shouldn't happen, but if it does, the user can manually restart
      setStatusBar("error");
      vscode.window
        .showWarningMessage(RELOAD_WINDOW_MESSAGE, LABEL_RELOAD_WINDOW)
        .then((action) => {
          if (action === LABEL_RELOAD_WINDOW) {
            vscode.commands.executeCommand("workbench.action.reloadWindow");
          }
        });
    }
  );
}

/**
 * Build the initializationOptions object passed to the language server.
 * Reads VS Code settings and translates them into the format expected by
 * GroovyLanguageServer.initialize().
 */
export function buildInitializationOptions(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("groovy");
  const options: Record<string, unknown> = {};

  // Log level
  const logLevel = config.get<string>("logLevel");
  if (logLevel) {
    options.logLevel = logLevel;
  }

  // Enabled importers (empty array means all enabled)
  const importers = config.get<string[]>("project.importers");
  if (importers && importers.length > 0) {
    options.enabledImporters = importers;
  }

  // Memory management settings
  const scopeEvictionTTL = config.get<number>("memory.scopeEvictionTTL");
  if (scopeEvictionTTL !== undefined) {
    options.scopeEvictionTTLSeconds = scopeEvictionTTL;
  }
  const backfillSiblings = config.get<boolean>("memory.backfillSiblingProjects");
  if (backfillSiblings !== undefined) {
    options.backfillSiblingProjects = backfillSiblings;
  }
  const pressureThreshold = config.get<number>("memory.pressureThreshold");
  if (pressureThreshold !== undefined) {
    // Convert from percentage (30-95) to ratio (0.30-0.95) for the server
    options.memoryPressureThreshold = pressureThreshold / 100;
  }

  // Rejected packages for ClassGraph scanning
  const rejectedPackages = config.get<string[]>("memory.rejectedPackages");
  if (rejectedPackages !== undefined) {
    options.rejectedPackages = rejectedPackages;
  }

  return options;
}

function startLanguageServer() {
  vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: INITIALIZING_MESSAGE, cancellable: false },
    (progress) => {
      return new Promise<void>(async (resolve, reject) => {
        if (!extensionContext) {
          resolve();
          setStatusBar("error");
          vscode.window.showErrorMessage(STARTUP_ERROR);
          return;
        }

        const config = vscode.workspace.getConfiguration("groovy");
        const port = config.get<number>("debug.serverPort") ?? 0;

        setStatusBar("starting");
        progress.report({ message: "Locating JDK…" });

        let serverOptions: ServerOptions;

        if (port > 0) {
          // === Debug mode: connect to running server ===
          serverOptions = () => {
            return new Promise<StreamInfo>((resolve, reject) => {
              const socket = new net.Socket();
              socket.connect(port, "127.0.0.1", () => {
                outputChannel?.appendLine(
                  `Connected to Groovy LSP on port ${port}`
                );
                resolve({reader: socket, writer: socket});
              });
              socket.on("error", reject);
            });
          };
        } else {
          // === Normal mode: launch Java process ===
          if (!javaPath) {
            resolve();
            setStatusBar("error");
            let settingsJavaHome = config.get<string>("java.home");
            if (settingsJavaHome) {
              vscode.window.showErrorMessage(INVALID_JAVA_ERROR);
            } else {
              vscode.window.showErrorMessage(MISSING_JAVA_ERROR);
            }
            return;
          }

          const args = [
            "-jar",
            path.resolve(
              extensionContext.extensionPath,
              "build",
              "bin",
              "groovy-language-server-all.jar"
            ),
          ];

          // Apply user-configurable JVM arguments (e.g. -Xmx768m).
          // When not configured, dynamically size the heap based on the
          // number of discovered build files so large multi-project
          // workspaces don't OOM-kill the server process.
          const vmargs = config.get<string>("java.vmargs");
          if (vmargs) {
            const vmTokens = vmargs.match(/\S+/g) || [];
            const blockedArgs = vmTokens.filter((token) =>
              BLOCKED_VM_ARG_PREFIXES.some((prefix) =>
                token.toLowerCase().startsWith(prefix.toLowerCase())
              )
            );
            if (blockedArgs.length > 0) {
              resolve();
              setStatusBar("error");
              vscode.window.showErrorMessage(
                `Groovy language server refused to start: groovy.java.vmargs contains ` +
                `blocked JVM flag(s) that could allow arbitrary code execution: ${blockedArgs.join(", ")}. ` +
                `Remove them and reload the window.`
              );
              return;
            }
            // If the user set vmargs but didn't include -Xmx, auto-size
            // the heap so the JVM doesn't default to 1/4 of physical RAM.
            const hasXmx = vmTokens.some((t) =>
              t.toLowerCase().startsWith("-xmx")
            );
            if (!hasXmx) {
              const heapMb = await estimateHeapSize();
              args.unshift(`-Xmx${heapMb}m`, "-XX:+UseG1GC", "-XX:+HeapDumpOnOutOfMemoryError");
              outputChannel?.appendLine(
                `Auto-detected heap size: -Xmx${heapMb}m (user vmargs don't include -Xmx)`
              );
            }
            args.unshift(...vmTokens);
          } else {
            const heapMb = await estimateHeapSize();
            args.unshift(
              `-Xmx${heapMb}m`,
              "-XX:+UseG1GC",
              "-XX:+HeapDumpOnOutOfMemoryError",
            );
            outputChannel?.appendLine(
              `Auto-detected heap size: -Xmx${heapMb}m (G1GC, HeapDumpOnOOM)`
            );
          }

          //uncomment to allow a debugger to attach to the language server
          //args.unshift("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005,quiet=y");
          let executable: Executable = {
            command: javaPath,
            args,
          };

          serverOptions = executable;
        }

        progress.report({ message: "Starting language server…" });

        // Custom error handler: tolerate transient connection errors
        // and auto-restart the server up to MAX_SERVER_RESTARTS times.
        const errorHandler: ErrorHandler = {
          error(
            _error: Error,
            _message: Message | undefined,
            _count: number | undefined
          ) {
            // Continue on transient write/read errors — the server may
            // still be alive.  If it truly died the "closed" handler
            // takes over.
            return { action: ErrorAction.Continue };
          },
          closed() {
            if (isIntentionalStop) {
              return { action: CloseAction.DoNotRestart };
            }
            serverCrashCount++;
            if (serverCrashCount <= MAX_SERVER_RESTARTS) {
              outputChannel?.appendLine(
                `Server connection lost — restarting (attempt ${serverCrashCount}/${MAX_SERVER_RESTARTS})…`
              );
              return {
                action: CloseAction.Restart,
                message: `Connection lost. Restarting server (attempt ${serverCrashCount}/${MAX_SERVER_RESTARTS})…`,
              };
            }
            outputChannel?.appendLine(
              "Server has crashed too many times. Not restarting."
            );
            return {
              action: CloseAction.DoNotRestart,
              message: `The Groovy language server has crashed ${MAX_SERVER_RESTARTS} times and will not be restarted.`,
            };
          },
        };

        let clientOptions: LanguageClientOptions = {
          documentSelector: [
            { scheme: "file", language: "groovy" },
            { scheme: "untitled", language: "groovy" },
          ],
          // Pass configuration to the server as initialization options
          initializationOptions: buildInitializationOptions(),
          synchronize: {
            configurationSection: "groovy",
            fileEvents: [
              vscode.workspace.createFileSystemWatcher("**/*.java"),
              vscode.workspace.createFileSystemWatcher("**/build.gradle"),
              vscode.workspace.createFileSystemWatcher("**/build.gradle.kts"),
              vscode.workspace.createFileSystemWatcher("**/pom.xml"),
            ],
          },
          errorHandler,
          outputChannel: outputChannel ?? undefined,
          uriConverters: {
            code2Protocol: (value: vscode.Uri) => {
              if (/^win32/.test(process.platform)) {
                //drive letters on Windows are encoded with %3A instead of :
                //but Java doesn't treat them the same
                return value.toString().replace("%3A", ":");
              } else {
                return value.toString();
              }
            },
            //this is just the default behavior, but we need to define both
            protocol2Code: (value) => vscode.Uri.parse(value),
          },
        };

        languageClient = new LanguageClient(
          "groovy",
          "Groovy Language Server",
          serverOptions,
          clientOptions
        );

        try {
          await languageClient.start();

          // Register a content provider for the "decompiled:" URI scheme so
          // that "Go to Definition" on classes from external JARs opens a
          // read-only view of the decompiled skeleton source.
          const decompiledProvider = vscode.workspace.registerTextDocumentContentProvider(
            "decompiled",
            {
              provideTextDocumentContent(
                uri: vscode.Uri
              ): Thenable<string> {
                if (!languageClient) {
                  return Promise.resolve("");
                }
                return languageClient.sendRequest<string>(
                  "groovy/getDecompiledContent",
                  { uri: uri.toString() }
                ).then((content) => content ?? "// No decompiled content available");
              },
            }
          );
          extensionContext!.subscriptions.push(decompiledProvider);

          // Register content providers for "jar:" and "jrt:" URI schemes.
          // "Go to Definition" on external classes returns jar: URIs
          // (e.g. jar:file:///path/to/dep.jar!/com/example/Foo.class)
          // and JDK classes return jrt: URIs on Java 9+.
          // VS Code needs content providers for these schemes to display
          // the decompiled class skeleton or source JAR content.
          for (const scheme of ["jar", "jrt"]) {
            const provider = vscode.workspace.registerTextDocumentContentProvider(
              scheme,
              {
                provideTextDocumentContent(
                  uri: vscode.Uri
                ): Thenable<string> {
                  if (!languageClient) {
                    return Promise.resolve("");
                  }
                  // Reconstruct the full URI (jar:file:///... or jrt:/...)
                  // vscode.Uri.toString() for the "jar" scheme produces
                  // "jar:file:///path!/entry" from the parsed components.
                  const fullUri = uri.toString();
                  return languageClient.sendRequest<string>(
                    "groovy/getDecompiledContent",
                    { uri: fullUri }
                  ).then((content) => content ?? "// No decompiled content available");
                },
              }
            );
            extensionContext!.subscriptions.push(provider);
          }

          // The server starts a background import (Gradle/Maven) after
          // initialization.  Listen for structured status notifications to
          // transition the status bar and keep the progress notification
          // visible until the server is ready.
          setStatusBar("importing");
          progress.report({ message: "Importing projects…" });

          // Handle unexpected server stops: resolve the progress
          // notification, update the status bar, and offer a restart.
          languageClient.onDidChangeState((event) => {
            if (event.newState === State.Stopped) {
              resolve();
              if (!isIntentionalStop) {
                setStatusBar("error");
                if (serverCrashCount >= MAX_SERVER_RESTARTS) {
                  vscode.window
                    .showErrorMessage(
                      SERVER_CRASHED_MESSAGE,
                      LABEL_RESTART_SERVER
                    )
                    .then((action) => {
                      if (action === LABEL_RESTART_SERVER) {
                        serverCrashCount = 0;
                        restartLanguageServer();
                      }
                    });
                }
              }
            } else if (event.newState === State.Running) {
              // Server (re)started successfully — reset crash counter.
              serverCrashCount = 0;
            }
          });

          languageClient.onNotification("groovy/statusUpdate", (params: { state: string; message?: string }) => {
            const state = params.state;
            if (state === "importing") {
              setStatusBar("importing", params.message);
              progress.report({ message: params.message || "Importing projects…" });
            } else if (state === "ready") {
              setStatusBar("ready");
              resolve();
            } else if (state === "error") {
              setStatusBar("error");
              resolve();
            }
          });

          // Listen for periodic memory usage reports from the server
          languageClient.onNotification("groovy/memoryUsage", (params: { usedMB: number; maxMB: number; activeScopes?: number; evictedScopes?: number; totalScopes?: number }) => {
            lastMemoryText = `${params.usedMB}/${params.maxMB} MB`;
            if (params.totalScopes !== undefined && params.totalScopes > 0) {
              lastScopeCounts = {
                active: params.activeScopes ?? 0,
                evicted: params.evictedScopes ?? 0,
                total: params.totalScopes,
              };
            }
            // Re-render the status bar if we're in "ready" state
            if (currentStatusState === "ready") {
              setStatusBar("ready");
            }
          });
        } catch (e) {
          setStatusBar("error");
          vscode.window.showErrorMessage(STARTUP_ERROR);
          resolve();
        }
      });
    }
  );
}
