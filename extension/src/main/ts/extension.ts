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
  StreamInfo,
} from "vscode-languageclient/node";

const MISSING_JAVA_ERROR =
  "Could not locate valid JDK. To configure JDK manually, use the groovy.java.home setting.";
const INVALID_JAVA_ERROR =
  "The groovy.java.home setting does not point to a valid JDK.";
const INITIALIZING_MESSAGE = "Initializing Groovy language server...";
const RELOAD_WINDOW_MESSAGE =
  "To apply new settings for Groovy, please reload the window.";
const STARTUP_ERROR = "The Groovy extension failed to start.";
const LABEL_RELOAD_WINDOW = "Reload Window";
let extensionContext: vscode.ExtensionContext | null = null;
let languageClient: LanguageClient | null = null;
let javaPath: string | null = null;
let outputChannel: vscode.OutputChannel | null = null;
let statusBarItem: vscode.StatusBarItem | null = null;

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
  return languageClient.stop();
}

function setStatusBar(
  state: "starting" | "importing" | "ready" | "error" | "stopped",
  detail?: string
): void {
  if (!statusBarItem) {
    return;
  }
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
    case "ready":
      statusBarItem.text = "$(check) Groovy";
      statusBarItem.tooltip =
        "Groovy Language Server: Ready (click to show output)";
      statusBarItem.show();
      break;
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
 * Shorten a server progress message for display in the status bar.
 * Keeps it concise so it doesn't take too much horizontal space.
 */
function shortenDetail(message: string): string {
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
    event.affectsConfiguration("groovy.debug.serverPort") ||
    event.affectsConfiguration("groovy.semanticHighlighting.enabled") ||
    event.affectsConfiguration("groovy.formatting.enabled")
  ) {
    javaPath = findJava();
    //we're going to try to kill the language server and then restart
    //it with the new settings
    restartLanguageServer();
  }
}

function restartLanguageServer() {
  if (!languageClient) {
    startLanguageServer();
    return;
  }
  let oldLanguageClient = languageClient;
  languageClient = null;
  oldLanguageClient.stop().then(
    () => {
      startLanguageServer();
    },
    () => {
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
function buildInitializationOptions(): Record<string, unknown> {
  const config = vscode.workspace.getConfiguration("groovy");
  const options: Record<string, unknown> = {};

  // Enabled importers (empty array means all enabled)
  const importers = config.get<string[]>("project.importers");
  if (importers && importers.length > 0) {
    options.enabledImporters = importers;
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

          //uncomment to allow a debugger to attach to the language server
          //args.unshift("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005,quiet=y");
          let executable: Executable = {
            command: javaPath,
            args,
          };

          serverOptions = executable;
        }

        progress.report({ message: "Starting language server…" });

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
          // transition the status bar.  This replaces the previous approach
          // of parsing window/logMessage text with fragile string-prefix matching.
          setStatusBar("importing");
          languageClient.onNotification("groovy/statusUpdate", (params: { state: string; message?: string }) => {
            const state = params.state;
            if (state === "importing") {
              setStatusBar("importing", params.message);
            } else if (state === "ready") {
              setStatusBar("ready");
            } else if (state === "error") {
              setStatusBar("error");
            }
          });
        } catch (e) {
          setStatusBar("error");
          vscode.window.showErrorMessage(STARTUP_ERROR);
        }

        resolve();
      });
    }
  );
}
