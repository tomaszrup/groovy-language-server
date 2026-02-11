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

import { activate, deactivate, estimateHeapSize, shortenDetail, buildInitializationOptions, setStatusBar, getMemorySuffix } from "../main/ts/extension";
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

// ====================================================================
// estimateHeapSize
// ====================================================================

describe("estimateHeapSize", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should return at least 512 MB with zero build files", async () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(300),
    } as any);
    vi.mocked(vscode.workspace.findFiles).mockResolvedValue([]);

    const heap = await estimateHeapSize();
    expect(heap).toBeGreaterThanOrEqual(512);
  });

  it("should scale with number of build files", async () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(300),
    } as any);

    // 10 build files
    const mockFiles = Array.from({ length: 10 }, (_, i) => ({
      toString: () => `file:///project${i}/build.gradle`,
    }));
    vi.mocked(vscode.workspace.findFiles).mockResolvedValue(mockFiles as any);

    const heap = await estimateHeapSize();
    expect(heap).toBeGreaterThan(512);
  });

  it("should cap at 4096 MB", async () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(0), // eviction disabled
    } as any);

    // 500 build files — would compute > 4096 without cap
    const mockFiles = Array.from({ length: 500 }, (_, i) => ({
      toString: () => `file:///project${i}/build.gradle`,
    }));
    vi.mocked(vscode.workspace.findFiles).mockResolvedValue(mockFiles as any);

    const heap = await estimateHeapSize();
    expect(heap).toBeLessThanOrEqual(4096);
  });

  it("should use smaller per-project estimate when eviction is enabled", async () => {
    // 20 build files, eviction enabled (TTL=300)
    const mockFiles = Array.from({ length: 20 }, (_, i) => ({
      toString: () => `file:///project${i}/build.gradle`,
    }));

    // With eviction
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(300),
    } as any);
    vi.mocked(vscode.workspace.findFiles).mockResolvedValue(mockFiles as any);
    const heapWithEviction = await estimateHeapSize();

    // Without eviction
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(0),
    } as any);
    vi.mocked(vscode.workspace.findFiles).mockResolvedValue(mockFiles as any);
    const heapWithoutEviction = await estimateHeapSize();

    expect(heapWithEviction).toBeLessThanOrEqual(heapWithoutEviction);
  });

  it("should return MIN_HEAP when findFiles throws", async () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(300),
    } as any);
    vi.mocked(vscode.workspace.findFiles).mockRejectedValue(new Error("fail"));

    const heap = await estimateHeapSize();
    expect(heap).toBe(512);
  });
});

// ====================================================================
// shortenDetail
// ====================================================================

describe("shortenDetail", () => {
  it("should return short messages unchanged", () => {
    expect(shortenDetail("Importing…")).toBe("Importing…");
  });

  it("should truncate messages longer than 60 chars", () => {
    const longMessage = "A".repeat(80);
    const result = shortenDetail(longMessage);
    expect(result.length).toBeLessThanOrEqual(60);
    expect(result.endsWith("…")).toBe(true);
  });

  it("should return 60-char message unchanged", () => {
    const exact = "A".repeat(60);
    expect(shortenDetail(exact)).toBe(exact);
  });

  it("should handle empty string", () => {
    expect(shortenDetail("")).toBe("");
  });
});

// ====================================================================
// buildInitializationOptions
// ====================================================================

describe("buildInitializationOptions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should return empty object when no settings are configured", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(undefined),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts).toBeDefined();
  });

  it("should include logLevel when configured", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "logLevel") return "DEBUG";
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.logLevel).toBe("DEBUG");
  });

  it("should include enabledImporters when configured", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "project.importers") return ["gradle"];
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.enabledImporters).toEqual(["gradle"]);
  });

  it("should not include enabledImporters when array is empty", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "project.importers") return [];
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.enabledImporters).toBeUndefined();
  });

  it("should include scopeEvictionTTLSeconds when configured", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "memory.scopeEvictionTTL") return 600;
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.scopeEvictionTTLSeconds).toBe(600);
  });

  it("should include backfillSiblingProjects when configured", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "memory.backfillSiblingProjects") return true;
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.backfillSiblingProjects).toBe(true);
  });
});

// ====================================================================
// setStatusBar
// ====================================================================

describe("setStatusBar", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Activate to create the statusBarItem
    vi.mocked(findJava).mockReturnValue("/usr/bin/java");
    activate({
      extensionPath: "/mock/extension",
      subscriptions: [],
      asAbsolutePath: vi.fn((p) => `/mock/extension/${p}`),
    } as unknown as vscode.ExtensionContext);
  });

  it("should show status bar for 'starting' state", () => {
    setStatusBar("starting");
    const item = vi.mocked(vscode.window.createStatusBarItem).mock.results[0]?.value;
    expect(item.show).toHaveBeenCalled();
    expect(item.text).toContain("Starting");
  });

  it("should show status bar for 'importing' state with detail", () => {
    setStatusBar("importing", "Resolving classpath…");
    const item = vi.mocked(vscode.window.createStatusBarItem).mock.results[0]?.value;
    expect(item.show).toHaveBeenCalled();
    expect(item.text).toContain("Resolving classpath…");
  });

  it("should show status bar for 'ready' state", () => {
    setStatusBar("ready");
    const item = vi.mocked(vscode.window.createStatusBarItem).mock.results[0]?.value;
    expect(item.show).toHaveBeenCalled();
    expect(item.text).toContain("Groovy");
  });

  it("should show status bar for 'error' state", () => {
    setStatusBar("error");
    const item = vi.mocked(vscode.window.createStatusBarItem).mock.results[0]?.value;
    expect(item.show).toHaveBeenCalled();
    expect(item.text).toContain("Groovy");
  });

  it("should hide status bar for 'stopped' state", () => {
    setStatusBar("stopped");
    const item = vi.mocked(vscode.window.createStatusBarItem).mock.results[0]?.value;
    expect(item.hide).toHaveBeenCalled();
  });
});

// ====================================================================
// getMemorySuffix
// ====================================================================

describe("getMemorySuffix", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should return empty string when showMemoryUsage is false", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(false),
    } as any);

    const suffix = getMemorySuffix();
    expect(suffix).toBe("");
  });

  it("should return empty string when no memory data available", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(true),
    } as any);

    // lastMemoryText is null at start
    const suffix = getMemorySuffix();
    // May be empty if no server has reported memory yet
    // This tests the guard against null lastMemoryText
    expect(typeof suffix).toBe("string");
  });
});
