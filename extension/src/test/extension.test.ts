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

/**
 * Tests for extension.ts — verifies activation, deactivation, command
 * registration, and configuration change handling of the VS Code extension.
 */

import { vi } from "vitest";
import * as vscode from "vscode";

// Mock vscode-languageclient/node before importing extension
vi.mock("vscode-languageclient/node", () => ({
  LanguageClient: vi.fn().mockImplementation(() => ({
    start: vi.fn().mockResolvedValue(undefined),
    stop: vi.fn().mockResolvedValue(undefined),
  })),
}));

// Mock findJava
vi.mock("../main/ts/utils/findJava", () => ({
  __esModule: true,
  default: vi.fn().mockReturnValue("/usr/bin/java"),
}));

import { activate, deactivate } from "../main/ts/extension";
import findJava from "../main/ts/utils/findJava";

describe("extension", () => {
  let mockContext: vscode.ExtensionContext;

  beforeEach(() => {
    vi.clearAllMocks();

    // Reset findJava mock to return a valid path
    vi.mocked(findJava).mockReturnValue("/usr/bin/java");

    mockContext = {
      extensionPath: "/mock/extension",
      subscriptions: [],
      workspaceState: {} as any,
      globalState: {} as any,
      extensionUri: {} as any,
      extensionMode: 1 as any,
      storageUri: undefined,
      globalStorageUri: {} as any,
      logUri: {} as any,
      storagePath: undefined,
      globalStoragePath: "/mock/global",
      logPath: "/mock/log",
      asAbsolutePath: vi.fn((p) => `/mock/extension/${p}`),
      environmentVariableCollection: {} as any,
      secrets: {} as any,
      extension: {} as any,
      languageModelAccessInformation: {} as any,
    } as unknown as vscode.ExtensionContext;
  });

  // ------------------------------------------------------------------
  // Activation
  // ------------------------------------------------------------------

  it("should register groovy.restartServer command on activation", () => {
    activate(mockContext);

    expect(vscode.commands.registerCommand).toHaveBeenCalledWith(
      "groovy.restartServer",
      expect.any(Function)
    );
  });

  it("should push disposables to context.subscriptions on activation", () => {
    activate(mockContext);

    // outputChannel + statusBarItem + onDidChangeConfiguration + registerCommand = 4
    expect(mockContext.subscriptions.length).toBeGreaterThanOrEqual(4);
  });

  it("should create an output channel on activation", () => {
    activate(mockContext);

    expect(vscode.window.createOutputChannel).toHaveBeenCalledWith(
      "Groovy Language Server"
    );
  });

  it("should create a status bar item on activation", () => {
    activate(mockContext);

    expect(vscode.window.createStatusBarItem).toHaveBeenCalled();
  });

  it("should listen for configuration changes on activation", () => {
    activate(mockContext);

    expect(vscode.workspace.onDidChangeConfiguration).toHaveBeenCalledWith(
      expect.any(Function)
    );
  });

  it("should call findJava on activation", () => {
    activate(mockContext);

    expect(findJava).toHaveBeenCalled();
  });

  it("should show error message when java is not found", async () => {
    vi.mocked(findJava).mockReturnValue(null);

    activate(mockContext);

    // Wait for the async startLanguageServer to finish
    await new Promise((r) => setTimeout(r, 50));

    expect(vscode.window.showErrorMessage).toHaveBeenCalled();
  });

  // ------------------------------------------------------------------
  // Deactivation
  // ------------------------------------------------------------------

  it("should not throw when deactivating", () => {
    // deactivate should safely complete (may return undefined or a thenable)
    expect(() => deactivate()).not.toThrow();
  });

  // ------------------------------------------------------------------
  // Configuration change handling
  // ------------------------------------------------------------------

  it("should call findJava again on java.home config change", () => {
    activate(mockContext);

    // Get the config change handler
    const configHandler = (
      vscode.workspace.onDidChangeConfiguration as ReturnType<typeof vi.fn>
    ).mock.calls[0][0];

    // Simulate a configuration change for groovy.java.home
    const mockEvent = {
      affectsConfiguration: (section: string) =>
        section === "groovy.java.home",
    };

    vi.mocked(findJava).mockClear();
    configHandler(mockEvent);

    expect(findJava).toHaveBeenCalled();
  });
});
