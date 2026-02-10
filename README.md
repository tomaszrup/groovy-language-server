![code status: vibed](https://img.shields.io/badge/code_status-Vibed-green)

# Groovy Language Server

A [Language Server Protocol](https://microsoft.github.io/language-server-protocol/) implementation for [Groovy](http://groovy-lang.org/), with first-class support for Gradle and Maven projects.

## Features

The following LSP capabilities are supported:

- **Completion** — context-aware suggestions for methods, properties, variables, and types
- **Definition** — go-to-definition for classes, methods, and variables
- **Type Definition** — navigate to the type declaration of a symbol
- **Hover** — inline documentation and type information on hover
- **References** — find all references to a symbol across the workspace
- **Rename** — safe rename refactoring with all usages updated
- **Signature Help** — parameter hints when calling methods and constructors
- **Document Symbols** — outline view of classes, methods, and fields in a file
- **Workspace Symbols** — search for symbols across the entire workspace
- **Code Actions** — quick-fix suggestions and source actions including: add/remove/organize imports, implement interface methods, generate constructors, generate getter/setter, generate `toString()`/`equals()`/`hashCode()`, and add `@Override`
- **Diagnostics** — real-time syntax and compilation error reporting
- **Inlay Hints** — inline type annotations for `def` variables and parameter name hints for method/constructor calls
- **Semantic Highlighting** — semantic-aware syntax highlighting for classes, interfaces, enums, traits, methods, properties, variables, parameters, and more, with modifiers for `static`, `readonly`, `abstract`, and `declaration`
- **Document Highlight** — highlights all occurrences of a symbol in the current file
- **Implementation** — find all implementations of an interface or abstract class
- **Formatting** — automatic document formatting with a built-in Groovy formatter
- **Traits** — full support for Groovy traits (see below)

### Trait Support

Groovy [traits](https://groovy-lang.org/objectorientation.html#_traits) are fully supported across all language features:

- **Completion** — trait methods appear in completions on implementing classes, within the trait itself, and on external instances
- **Definition** — go-to-definition resolves to the original trait method declaration, both within the trait and from implementing classes
- **Type Definition** — navigate from a trait-typed variable to the trait declaration
- **Hover** — traits are displayed with the `trait` keyword (not `interface`), including `extends` clauses
- **Document Symbols** — traits appear with `Interface` symbol kind; trait methods appear as `Method` symbols
- **Semantic Highlighting** — traits are highlighted as interfaces with appropriate modifiers

### Spock Framework Support

Built-in support for the [Spock](https://spockframework.org/) testing framework:

- **Smart completions inside specifications:**
  - Block labels (`given:`, `when:`, `then:`, `expect:`, `cleanup:`, `where:`, `and:`) with documentation
  - `where:` block variants — data table scaffold, data pipes, multi-assignment, derived variables
  - Assertion helpers — `thrown()`, `notThrown()`, `noExceptionThrown()`, `old()`
  - Mocking — `Mock()`, `Stub()`, `Spy()`, `interaction {}`, `with {}`, `verifyAll {}`
  - Feature method snippets — given-when-then, expect, data-driven, exception, and interaction patterns
  - Lifecycle method snippets — `setup()`, `cleanup()`, `setupSpec()`, `cleanupSpec()`
  - Spock annotations — `@Unroll`, `@Shared`, `@Stepwise`, `@Timeout`, `@Ignore`, and more

- **Rich hover information:**
  - Block labels show detailed descriptions of semantics and usage
  - Specification classes show Spock-specific documentation
  - Feature methods are annotated as "Spock Feature Method"
  - Lifecycle methods display fixture documentation

- **Enhanced document symbols:**
  - Spock specifications are prefixed with ✱ in the outline
  - Feature methods are prefixed with ▶
  - Lifecycle methods are prefixed with ⚙

- **Code actions:**
  - Insert given-when-then blocks into an empty feature method
  - Generate a new feature method at the class level

Spock support activates automatically when a class extends `spock.lang.Specification` (resolved via the superclass chain or unresolved name).

### Build Tool Integration

Automatic classpath resolution for both **Gradle** and **Maven** projects:

#### Gradle

Gradle projects are imported via the [Gradle Tooling API](https://docs.gradle.org/current/userguide/third_party_integration.html):

- Automatically discovers Gradle projects in the workspace (including multi-project builds)
- Supports both Groovy DSL (`build.gradle`) and Kotlin DSL (`build.gradle.kts`) build scripts
- Supports the **Gradle Wrapper** (`gradlew` / `gradlew.bat`) — the Tooling API automatically uses the wrapper when present
- Resolves all four classpath configurations (`compileClasspath`, `runtimeClasspath`, `testCompileClasspath`, `testRuntimeClasspath`) so that autocomplete and navigation work for third-party libraries
- Caches resolved classpaths and validates them against build-file timestamps to avoid redundant resolution
- Recompiles Java/Gradle sources on change so the Groovy compilation unit picks up updates
- Per-project scoping — each Gradle subproject gets its own classpath and compilation context

#### Maven

Maven projects are imported by invoking `mvn` to resolve dependencies:

- Automatically discovers Maven projects in the workspace (including multi-module builds)
- Resolves the full dependency classpath via `mvn dependency:build-classpath` (including test-scoped dependencies like Spock)
- Supports the **Maven Wrapper** (`mvnw` / `mvnw.cmd`) — the wrapper is detected automatically in the project directory or any parent directory
- Compiles Java sources via `mvn compile test-compile` on import and when `.java` or `pom.xml` files change
- Discovers `target/classes` and `target/test-classes` output directories
- Per-project scoping — each Maven module gets its own classpath and compilation context

#### Priority

If a directory contains both `build.gradle` and `pom.xml`, the Gradle importer takes priority.

### VS Code Extension

A bundled VS Code extension provides a seamless editor experience:

- **Automatic server lifecycle** — the language server JAR starts automatically when a `.groovy` file is opened
- **JDK auto-detection** — locates a suitable JDK from `JAVA_HOME`, `JDK_HOME`, `PATH`, or the `groovy.java.home` setting
- **Go-to-definition into external libraries** — navigating to classes from JARs or the JDK opens a decompiled skeleton view (supports `decompiled:`, `jar:`, and `jrt:` URI schemes)
- **Status bar indicator** — shows server state (starting, importing, ready, error) with live progress during project import
- **Restart command** — `Groovy: Restart Groovy language server` command to restart the server without reloading the window
- **Show Output Channel** — `Groovy: Show Output Channel` command to view the language server log

### Language Configuration

The extension ships a `language-configuration.json` that enables rich editing support for Groovy files out of the box:

- **Comment toggling** — `Ctrl+/` toggles `//` line comments; `Shift+Alt+A` wraps selections in `/* */` block comments
- **Bracket matching & auto-closing** — automatically closes `()`, `[]`, `{}`, `""`, `''`, and backticks; matching brackets are highlighted
- **Doc-comment continuation** — pressing Enter inside a `/** */` block automatically inserts ` * ` on the next line
- **Smart indentation** — increases indent after `{`, `class`, `interface`, `enum`, `trait`, `if`, `for`, `while`, etc.; decreases indent on `}` or `]`
- **Folding markers** — `// region` / `// endregion` (and `<editor-fold>`) markers create foldable regions in the editor
- **Surrounding pairs** — selecting text and typing a bracket or quote character wraps the selection

### Configuration

| Option | Type | Description |
|---|---|---|
| `groovy.java.home` | `string` | Path to a custom JDK installation |
| `groovy.classpath` | `string[]` | Additional `.jar` files to include on the classpath |
| `groovy.debug.serverPort` | `number` | Connect to an existing Groovy LSP server on this TCP port instead of starting one automatically |
| `groovy.semanticHighlighting.enabled` | `boolean` | Enable or disable semantic syntax highlighting (default: `true`) |
| `groovy.formatting.enabled` | `boolean` | Enable or disable document formatting (default: `true`) |
| `groovy.maven.home` | `string` | Path to a Maven installation (used when `mvn` is not on `PATH` and no Maven Wrapper is present) |
| `groovy.project.importers` | `string[]` | Limit which build-tool importers are active during project discovery. Valid values: `"Gradle"`, `"Maven"`. An empty array (default) enables all importers. |


## Build

```sh
./gradlew build
```

This produces:
- `build/libs/groovy-language-server-all.jar` — the language server fat JAR
- `extension/groovy-spock-support-0.1.0.vsix` — the VS Code extension package (bundling the JAR)

The server communicates via standard I/O using the Language Server Protocol.

## Provenance & Credits

This project is a fork-of-a-fork of the original **Groovy Language Server**. The full lineage:

1. **[GroovyLanguageServer/groovy-language-server](https://github.com/GroovyLanguageServer/groovy-language-server)** — the original project by [Prominic.NET, Inc.](https://prominic.net/), created for [Moonshine IDE](https://moonshine-ide.com/). It established the core LSP implementation for Groovy (completion, definition, hover, references, rename, signature help, document/workspace symbols).

2. **[pvangeel/groovy-language-server](https://github.com/pvangeel/groovy-language-server)** — a fork by **Peter Van Geel** ([@pvangeel](https://github.com/pvangeel)) that added the `fetch_classpath_from_gradle_configuration` branch, introducing Gradle Tooling API integration for automatic classpath resolution.

3. **[PR #1](https://github.com/pvangeel/groovy-language-server/pull/1)** on the pvangeel fork — contributed by **[@turchinc](https://github.com/turchinc)**, which fixed autocomplete for imported classes from the classpath. Key improvements included:
   - Simplified compilation strategy (compile only open files instead of entire directories)
   - Fixed URI format mismatches between LSP (`file:///`) and Groovy's parser (`file:/`)
   - Added missing import statement indexing in the AST visitor

4. **This fork** — maintained by **Tomasz Rup**, with updated dependencies, package renaming (`com.tomaszrup`), and ongoing maintenance.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Original code copyright © 2022 Prominic.NET, Inc.  
Modifications copyright © 2026 Tomasz Rup.
