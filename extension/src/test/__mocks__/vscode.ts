/**
 * Manual mock for the 'vscode' module.
 * Provides just enough surface for unit-testing extension.ts and findJava.ts
 * without loading a real VS Code instance.
 */

import { vi } from "vitest";

const workspace = {
  getConfiguration: vi.fn().mockReturnValue({
    get: vi.fn().mockReturnValue(null),
  }),
  onDidChangeConfiguration: vi.fn().mockReturnValue({ dispose: vi.fn() }),
  createFileSystemWatcher: vi.fn().mockReturnValue({
    onDidChange: vi.fn(),
    onDidCreate: vi.fn(),
    onDidDelete: vi.fn(),
    dispose: vi.fn(),
  }),
  findFiles: vi.fn().mockResolvedValue([]),
  registerTextDocumentContentProvider: vi.fn().mockReturnValue({ dispose: vi.fn() }),
};

const window = {
  showErrorMessage: vi.fn().mockResolvedValue(undefined),
  showWarningMessage: vi.fn().mockResolvedValue(undefined),
  showInformationMessage: vi.fn().mockResolvedValue(undefined),
  withProgress: vi.fn().mockImplementation((_opts, task) => {
    const progress = { report: vi.fn() };
    return task(progress, { isCancellationRequested: false });
  }),
  createOutputChannel: vi.fn().mockReturnValue({
    appendLine: vi.fn(),
    append: vi.fn(),
    clear: vi.fn(),
    show: vi.fn(),
    hide: vi.fn(),
    dispose: vi.fn(),
  }),
  createStatusBarItem: vi.fn().mockReturnValue({
    text: "",
    tooltip: "",
    command: undefined,
    name: "",
    show: vi.fn(),
    hide: vi.fn(),
    dispose: vi.fn(),
  }),
};

const commands = {
  registerCommand: vi.fn().mockReturnValue({ dispose: vi.fn() }),
  executeCommand: vi.fn(),
};

const extensions = {
  getExtension: vi.fn().mockReturnValue(undefined),
};

const Uri = {
  parse: vi.fn((s: string) => ({ toString: () => s })),
  file: vi.fn((p: string) => ({ toString: () => `file://${p}` })),
};

const ProgressLocation = {
  Window: 15,
  Notification: 15,
};

const StatusBarAlignment = {
  Left: 1,
  Right: 2,
};

export {
  workspace,
  window,
  commands,
  extensions,
  Uri,
  ProgressLocation,
  StatusBarAlignment,
};
