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
  },
});
