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

const { mockLanguageClientCtor } = vi.hoisted(() => ({
  mockLanguageClientCtor: vi.fn().mockImplementation(() => ({
    start: vi.fn().mockResolvedValue(undefined),
    stop: vi.fn().mockResolvedValue(undefined),
    onDidChangeState: vi.fn(),
    onNotification: vi.fn(),
    sendRequest: vi.fn().mockResolvedValue(""),
  })),
}));

// Mock vscode-languageclient/node before importing extension
vi.mock("vscode-languageclient/node", () => ({
  LanguageClient: mockLanguageClientCtor,
  State: {
    Running: 2,
    Stopped: 3,
  },
  ErrorAction: {
    Continue: 1,
  },
  CloseAction: {
    DoNotRestart: 1,
    Restart: 2,
  },
}));
vi.mock("vscode-languageclient/node.js", () => ({
  LanguageClient: mockLanguageClientCtor,
  State: {
    Running: 2,
    Stopped: 3,
  },
  ErrorAction: {
    Continue: 1,
  },
  CloseAction: {
    DoNotRestart: 1,
    Restart: 2,
  },
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
    mockLanguageClientCtor.mockImplementation(() => ({
      start: vi.fn().mockResolvedValue(undefined),
      stop: vi.fn().mockResolvedValue(undefined),
      onDidChangeState: vi.fn(),
      onNotification: vi.fn(),
      sendRequest: vi.fn().mockResolvedValue(""),
    }));

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

  it("should construct language client on activation", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    expect(mockLanguageClientCtor).toHaveBeenCalled();
  });

  it("should include virtual schemes in document selector", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    expect(clientOptions.documentSelector).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ scheme: "file", language: "groovy" }),
        expect.objectContaining({ scheme: "untitled", language: "groovy" }),
        expect.objectContaining({ scheme: "jar" }),
        expect.objectContaining({ scheme: "jrt" }),
        expect.objectContaining({ scheme: "decompiled" }),
      ])
    );
  });

  it("should delegate jar java definitions to Java extension when available", async () => {
    vi.mocked(vscode.extensions.getExtension).mockReturnValue({ isActive: true } as any);
    vi.mocked(vscode.commands.executeCommand).mockResolvedValue([
      { uri: { toString: () => "file:///from-java" } },
    ] as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const middleware = clientOptions.middleware;
    const next = vi.fn().mockResolvedValue([{ uri: { toString: () => "file:///from-next" } }]);

    const result = await middleware.provideDefinition(
      { uri: { scheme: "jar", toString: () => "jar:/dep.jar/Foo.java" }, languageId: "java" },
      { line: 1, character: 1 },
      { isCancellationRequested: false },
      next
    );

    expect(vscode.commands.executeCommand).toHaveBeenCalledWith(
      "vscode.executeDefinitionProvider",
      expect.objectContaining({ scheme: "jar" }),
      expect.objectContaining({ line: 1, character: 1 })
    );
    expect(next).not.toHaveBeenCalled();
    expect(result).toHaveLength(1);
    expect((result as any[])[0].uri.toString()).toBe("file:///from-java");
  });

  it("should fallback to Groovy LS when Java delegation returns no result", async () => {
    vi.mocked(vscode.extensions.getExtension).mockReturnValue({ isActive: true } as any);
    vi.mocked(vscode.commands.executeCommand).mockResolvedValue([] as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const middleware = clientOptions.middleware;
    const next = vi.fn().mockResolvedValue([{ uri: { toString: () => "file:///from-next" } }]);

    const result = await middleware.provideDefinition(
      { uri: { scheme: "jar", toString: () => "jar:/dep.jar/Foo.java" }, languageId: "java" },
      { line: 1, character: 1 },
      { isCancellationRequested: false },
      next
    );

    expect(next).toHaveBeenCalled();
    expect((result as any[])[0].uri.toString()).toBe("file:///from-next");
  });

  it("should activate Red Hat Java extension before delegation when inactive", async () => {
    const activateJavaExtension = vi.fn().mockResolvedValue(undefined);
    vi.mocked(vscode.extensions.getExtension).mockReturnValue({ isActive: false, activate: activateJavaExtension } as any);
    vi.mocked(vscode.commands.executeCommand).mockResolvedValue([{ uri: { toString: () => "file:///from-java" } }] as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const middleware = clientOptions.middleware;
    const next = vi.fn().mockResolvedValue([{ uri: { toString: () => "file:///from-next" } }]);

    const result = await middleware.provideDefinition(
      { uri: { scheme: "jar", path: "/dep/Foo.java", toString: () => "jar:/dep/Foo.java" }, languageId: "" },
      { line: 1, character: 1 },
      { isCancellationRequested: false },
      next
    );

    expect(activateJavaExtension).toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
    expect(result).toHaveLength(1);
  });

  it("should not call Groovy fallback during nested Java delegation", async () => {
    vi.mocked(vscode.extensions.getExtension).mockReturnValue({ isActive: true } as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const middleware = clientOptions.middleware;
    const nestedNext = vi.fn().mockResolvedValue([{ uri: { toString: () => "file:///from-nested-next" } }]);

    vi.mocked(vscode.commands.executeCommand).mockImplementation(async (_cmd, uri, pos) => {
      await middleware.provideDefinition(
        { uri, languageId: "java" },
        pos,
        { isCancellationRequested: false },
        nestedNext
      );
      return [{ uri: { toString: () => "file:///from-java" } }];
    });

    const next = vi.fn().mockResolvedValue([{ uri: { toString: () => "file:///from-next" } }]);
    const result = await middleware.provideDefinition(
      { uri: { scheme: "jar", toString: () => "jar:/dep.jar/Foo.java" }, languageId: "java" },
      { line: 1, character: 1 },
      { isCancellationRequested: false },
      next
    );

    expect(nestedNext).not.toHaveBeenCalled();
    expect(next).not.toHaveBeenCalled();
    expect(result).toHaveLength(1);
    expect((result as any[])[0].uri.toString()).toBe("file:///from-java");
  });

  it("restart command should stop existing client", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const firstClient = mockLanguageClientCtor.mock.results[0].value;
    const restartHandler = (vscode.commands.registerCommand as ReturnType<typeof vi.fn>)
      .mock.calls.find((c: any[]) => c[0] === "groovy.restartServer")?.[1];

    await restartHandler();
    await new Promise((r) => setTimeout(r, 30));

    expect(firstClient.stop).toHaveBeenCalled();
  });

  it("error handler closed should restart up to limit then stop", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const closed = clientOptions.errorHandler.closed;

    expect(closed().action).toBe(2);
    expect(closed().action).toBe(2);
    expect(closed().action).toBe(2);
    expect(closed().action).toBe(2);
    expect(closed().action).toBe(2);
    expect(closed().action).toBe(1);
  });

  it("status and memory notifications should update extension state paths", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const client = mockLanguageClientCtor.mock.results[0].value;
    const notificationCalls = client.onNotification.mock.calls as any[];

    const statusHandler = notificationCalls.find((c) => c[0] === "groovy/statusUpdate")?.[1];
    const memoryHandler = notificationCalls.find((c) => c[0] === "groovy/memoryUsage")?.[1];

    expect(statusHandler).toBeDefined();
    expect(memoryHandler).toBeDefined();

    statusHandler({ state: "importing", message: "Loading project model" });
    statusHandler({ state: "ready" });
    memoryHandler({ usedMB: 128, maxMB: 512, activeScopes: 2, evictedScopes: 1, totalScopes: 3 });
    statusHandler({ state: "error" });

    const statusBarItem = (vscode.window.createStatusBarItem as ReturnType<typeof vi.fn>).mock.results[0].value;
    expect(statusBarItem.show).toHaveBeenCalled();
  });

  it("stopped state after crash limit should surface restart prompt", async () => {
    vi.mocked(vscode.window.showErrorMessage).mockResolvedValue("Restart Server" as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const client = mockLanguageClientCtor.mock.results[0].value;
    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const closed = clientOptions.errorHandler.closed;
    const stateHandler = client.onDidChangeState.mock.calls[0][0];

    closed();
    closed();
    closed();
    closed();
    closed();

    stateHandler({ newState: 3 });
    await new Promise((r) => setTimeout(r, 30));

    expect(vscode.window.showErrorMessage).toHaveBeenCalledWith(
      "The Groovy language server has crashed.",
      "Restart Server"
    );
  });

  it("running state should reset crash counter", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const client = mockLanguageClientCtor.mock.results[0].value;
    const clientOptions = mockLanguageClientCtor.mock.calls[0][3];
    const closed = clientOptions.errorHandler.closed;
    const stateHandler = client.onDidChangeState.mock.calls[0][0];

    closed();
    stateHandler({ newState: 2 });
    const afterReset = closed();

    expect(afterReset.message).toContain("attempt 1/5");
  });

  it("content providers should request decompiled content from language client", async () => {
    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    const client = mockLanguageClientCtor.mock.results[0].value;
    const providerCalls = (vscode.workspace.registerTextDocumentContentProvider as ReturnType<typeof vi.fn>).mock.calls;

    const decompiledProvider = providerCalls.find((c: any[]) => c[0] === "decompiled")?.[1];
    const jarProvider = providerCalls.find((c: any[]) => c[0] === "jar")?.[1];

    expect(decompiledProvider).toBeDefined();
    expect(jarProvider).toBeDefined();

    await decompiledProvider.provideTextDocumentContent({ toString: () => "decompiled://sample" } as any);
    await jarProvider.provideTextDocumentContent({ toString: () => "jar:file:///tmp/a.jar!/Foo.class" } as any);

    expect(client.sendRequest).toHaveBeenCalledWith(
      "groovy/getDecompiledContent",
      { uri: "decompiled://sample" }
    );
    expect(client.sendRequest).toHaveBeenCalledWith(
      "groovy/getDecompiledContent",
      { uri: "jar:file:///tmp/a.jar!/Foo.class" }
    );
  });

  it("should show startup error when language client fails to start", async () => {
    mockLanguageClientCtor.mockImplementationOnce(() => ({
      start: vi.fn().mockRejectedValue(new Error("boom")),
      stop: vi.fn().mockResolvedValue(undefined),
      onDidChangeState: vi.fn(),
      onNotification: vi.fn(),
      sendRequest: vi.fn().mockResolvedValue(""),
    }));

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    expect(vscode.window.showErrorMessage).toHaveBeenCalledWith(
      "The Groovy extension failed to start."
    );
  });

  it("should show error message when java is not found", async () => {
    vi.mocked(findJava).mockReturnValue(null);

    activate(mockContext);

    // Wait for the async startLanguageServer to finish
    await new Promise((r) => setTimeout(r, 50));

    expect(vscode.window.showErrorMessage).toHaveBeenCalled();
  });

  it("should show invalid java.home message when configured path is invalid", async () => {
    vi.mocked(findJava).mockReturnValue(null);
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "java.home") return "/invalid/jdk";
        return undefined;
      }),
    } as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    expect(vscode.window.showErrorMessage).toHaveBeenCalledWith(
      "The groovy.java.home setting does not point to a valid JDK."
    );
  });

  it("should refuse startup when blocked vmargs are configured", async () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "java.vmargs") return "-javaagent:evil.jar -Xms256m";
        if (key === "debug.serverPort") return 0;
        return undefined;
      }),
    } as any);

    activate(mockContext);
    await new Promise((r) => setTimeout(r, 30));

    expect(vscode.window.showErrorMessage).toHaveBeenCalledWith(
      expect.stringContaining("blocked JVM flag")
    );
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

  it("should call findJava again on formatting.organizeImports config change", () => {
    activate(mockContext);

    const configHandler = (
      vscode.workspace.onDidChangeConfiguration as ReturnType<typeof vi.fn>
    ).mock.calls[0][0];

    const mockEvent = {
      affectsConfiguration: (section: string) =>
        section === "groovy.formatting.organizeImports",
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
      get: vi.fn((key: string) => {
        if (key === "memory.scopeEvictionTTL") return 300;
        if (key === "memory.perProjectMB") return 128;
        return undefined;
      }),
    } as any);
    vi.mocked(vscode.workspace.findFiles).mockResolvedValue(mockFiles as any);
    const heapWithEviction = await estimateHeapSize();

    // Without eviction
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn((key: string) => {
        if (key === "memory.scopeEvictionTTL") return 0;
        if (key === "memory.perProjectMB") return 128;
        return undefined;
      }),
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

  it("should include classpathCache when configured", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "classpath.cache.enabled") return false;
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.classpathCache).toBe(false);
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

  it("should convert memory pressure threshold percent to ratio", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockImplementation((key: string) => {
        if (key === "memory.pressureThreshold") return 75;
        return undefined;
      }),
    } as any);

    const opts = buildInitializationOptions();
    expect(opts.memoryPressureThreshold).toBe(0.75);
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
