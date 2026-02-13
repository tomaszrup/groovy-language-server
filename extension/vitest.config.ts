import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globals: true,
    environment: "node",
    root: ".",
    include: ["src/test/**/*.test.ts"],
    alias: {
      vscode: new URL("src/test/__mocks__/vscode.ts", import.meta.url)
        .pathname,
    },
    coverage: {
      provider: "v8",
      reporter: ["text", "json-summary", "html"],
      reportsDirectory: "./coverage",
      include: ["src/main/ts/**/*.ts"],
      exclude: [
        "src/test/**",
        "**/*.d.ts",
        "build/**",
        "node_modules/**",
      ],
      thresholds: {
        lines: 75,
      },
    },
  },
});
