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
 * Tests for findJava.ts — verifies that the Java executable is located
 * from the VS Code setting, JAVA_HOME, JDK_HOME, or PATH, and that
 * validation rejects missing/non-file entries.
 */

import { vi, type MockedObject } from "vitest";
import * as path from "path";
import * as fs from "fs";
import * as vscode from "vscode";

// We need to mock 'fs' before importing findJava
vi.mock("fs");
const mockedFs = fs as MockedObject<typeof fs>;

// Import after mocking
import findJava from "../main/ts/utils/findJava";

const isWindows = process.platform === "win32";
const javaExe = isWindows ? "java.exe" : "java";

describe("findJava", () => {
  const originalEnv = { ...process.env };

  beforeEach(() => {
    vi.clearAllMocks();
    // Reset environment
    delete process.env.JAVA_HOME;
    delete process.env.JDK_HOME;

    // Default: getConfiguration returns null for java.home
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(null),
    } as any);
  });

  afterEach(() => {
    // Restore environment
    process.env = { ...originalEnv };
  });

  // ------------------------------------------------------------------
  // groovy.java.home setting
  // ------------------------------------------------------------------

  it("should return java path from groovy.java.home setting when valid", () => {
    const javaHome = "/opt/java17";
    const expectedPath = path.join(javaHome, "bin", javaExe);

    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue(javaHome),
    } as any);

    mockedFs.existsSync.mockImplementation((p) =>
      p === expectedPath ? true : false
    );
    mockedFs.statSync.mockImplementation(
      (p) =>
        ({
          isFile: () => p === expectedPath,
        } as any)
    );

    const result = findJava();
    expect(result).toBe(expectedPath);
  });

  it("should return null when groovy.java.home is set but invalid", () => {
    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue("/invalid/path"),
    } as any);

    mockedFs.existsSync.mockReturnValue(false);

    const result = findJava();
    expect(result).toBeNull();
  });

  it("should not fallback to env vars when groovy.java.home is set but invalid", () => {
    const validJavaHome = "/valid/java";
    const validJavaPath = path.join(validJavaHome, "bin", javaExe);
    process.env.JAVA_HOME = validJavaHome;

    vi.mocked(vscode.workspace.getConfiguration).mockReturnValue({
      get: vi.fn().mockReturnValue("/bad/setting/path"),
    } as any);

    // JAVA_HOME path is valid, but setting path is not
    mockedFs.existsSync.mockImplementation(
      (p) => p === validJavaPath
    );
    mockedFs.statSync.mockImplementation(
      (p) =>
        ({
          isFile: () => p === validJavaPath,
        } as any)
    );

    const result = findJava();
    // Should NOT fallback to JAVA_HOME since the setting was explicitly set
    expect(result).toBeNull();
  });

  // ------------------------------------------------------------------
  // JAVA_HOME env var
  // ------------------------------------------------------------------

  it("should find java from JAVA_HOME when setting is not set", () => {
    const javaHome = "/usr/lib/jvm/java-17";
    const expectedPath = path.join(javaHome, "bin", javaExe);
    process.env.JAVA_HOME = javaHome;

    mockedFs.existsSync.mockImplementation((p) => p === expectedPath);
    mockedFs.statSync.mockImplementation(
      (p) =>
        ({
          isFile: () => p === expectedPath,
        } as any)
    );

    const result = findJava();
    expect(result).toBe(expectedPath);
  });

  it("should skip invalid JAVA_HOME and try JDK_HOME", () => {
    process.env.JAVA_HOME = "/invalid/java_home";
    const jdkHome = "/valid/jdk_home";
    const expectedPath = path.join(jdkHome, "bin", javaExe);
    process.env.JDK_HOME = jdkHome;

    mockedFs.existsSync.mockImplementation((p) => p === expectedPath);
    mockedFs.statSync.mockImplementation(
      (p) =>
        ({
          isFile: () => p === expectedPath,
        } as any)
    );

    const result = findJava();
    expect(result).toBe(expectedPath);
  });

  // ------------------------------------------------------------------
  // JDK_HOME env var
  // ------------------------------------------------------------------

  it("should find java from JDK_HOME when JAVA_HOME is not set", () => {
    const jdkHome = "/opt/jdk";
    const expectedPath = path.join(jdkHome, "bin", javaExe);
    process.env.JDK_HOME = jdkHome;

    mockedFs.existsSync.mockImplementation((p) => p === expectedPath);
    mockedFs.statSync.mockImplementation(
      (p) =>
        ({
          isFile: () => p === expectedPath,
        } as any)
    );

    const result = findJava();
    expect(result).toBe(expectedPath);
  });

  // ------------------------------------------------------------------
  // PATH env var
  // ------------------------------------------------------------------

  it("should find java from PATH when no home vars are set", () => {
    const binDir = "/usr/local/bin";
    const expectedPath = path.join(binDir, javaExe);
    process.env.PATH = `/some/other/dir${path.delimiter}${binDir}`;

    mockedFs.existsSync.mockImplementation((p) => p === expectedPath);
    mockedFs.statSync.mockImplementation(
      (p) =>
        ({
          isFile: () => p === expectedPath,
        } as any)
    );

    const result = findJava();
    expect(result).toBe(expectedPath);
  });

  it("should skip non-file entries in PATH", () => {
    process.env.PATH = "/some/dir";

    // existsSync returns true but isFile returns false (it's a directory)
    mockedFs.existsSync.mockReturnValue(true);
    mockedFs.statSync.mockReturnValue({
      isFile: () => false,
    } as any);

    const result = findJava();
    expect(result).toBeNull();
  });

  // ------------------------------------------------------------------
  // No java found
  // ------------------------------------------------------------------

  it("should return null when java is not found anywhere", () => {
    delete process.env.JAVA_HOME;
    delete process.env.JDK_HOME;
    process.env.PATH = "/empty/path";

    mockedFs.existsSync.mockReturnValue(false);

    const result = findJava();
    expect(result).toBeNull();
  });
});
