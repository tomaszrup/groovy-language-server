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
package com.tomaszrup.groovyls.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates Java source files (.java) by fully-qualified class name and finds
 * precise declaration positions for classes, methods, fields, and constructors.
 * <p>
 * When a Groovy file references a Java class that exists in the same project
 * (e.g., under src/main/java), the Groovy compiler only sees the compiled
 * bytecode on the classpath. This locator bridges the gap by mapping FQCNs
 * back to their .java source file URIs so that "Go to Definition" can navigate
 * into Java source code at the correct line.
 */
public class JavaSourceLocator {
    private static final Logger logger = LoggerFactory.getLogger(JavaSourceLocator.class);

    private static final List<String> JAVA_SOURCE_DIRS = Arrays.asList(
            "src/main/java",
            "src/test/java",
            "src/main/groovy",
            "src/test/groovy"
    );

    // Matches class/interface/enum/record declarations (not inside comments)
    private static final Pattern CLASS_DECL_PATTERN = Pattern.compile(
            "^[^/]*\\b(?:class|interface|enum|record)\\s+(\\w+)");

    // Matches method declarations: optional modifiers, return type, method name, opening paren
    // Captures: group(1)=method name
    private static final Pattern METHOD_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)*"
            + "(?:<[^>]+>\\s+)?"        // optional generic type params
            + "(?:[\\w\\[\\]<>,?\\s]+)\\s+" // return type (words, arrays, generics)
            + "(\\w+)\\s*\\(");          // method name + open paren

    // Matches constructor declarations
    private static final Pattern CONSTRUCTOR_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|protected|private)\\s+)?(\\w+)\\s*\\(");

    // Matches field declarations
    private static final Pattern FIELD_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|volatile|transient)\\s+)*"
            + "(?:[\\w\\[\\]<>,?]+)\\s+"  // type
            + "(\\w+)\\s*[;=]");           // field name followed by ; or =

    /** Maps fully-qualified class name → source file path (volatile for safe publication) */
    private volatile Map<String, Path> classNameToSource = Collections.emptyMap();

    /** All project roots being tracked (thread-safe for concurrent adds) */
    private final List<Path> projectRoots = new CopyOnWriteArrayList<>();

    public JavaSourceLocator() {
    }

    /**
     * Index all Java source files under the given project root.
     */
    public void addProjectRoot(Path projectRoot) {
        projectRoots.add(projectRoot);
        Map<String, Path> snapshot = new HashMap<>(classNameToSource);
        for (String sourceDir : JAVA_SOURCE_DIRS) {
            Path sourcePath = projectRoot.resolve(sourceDir);
            if (Files.isDirectory(sourcePath)) {
                indexSourceDirectory(sourcePath, snapshot);
            }
        }
        classNameToSource = Collections.unmodifiableMap(snapshot);
    }

    /**
     * Re-scan all registered project roots. Call this when files change.
     */
    public void refresh() {
        List<Path> roots = new ArrayList<>(projectRoots);
        Map<String, Path> snapshot = new HashMap<>();
        for (Path root : roots) {
            for (String sourceDir : JAVA_SOURCE_DIRS) {
                Path sourcePath = root.resolve(sourceDir);
                if (Files.isDirectory(sourcePath)) {
                    indexSourceDirectory(sourcePath, snapshot);
                }
            }
        }
        classNameToSource = Collections.unmodifiableMap(snapshot);
    }

    private void indexSourceDirectory(Path sourceRoot, Map<String, Path> target) {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFile -> {
                        String fqcn = pathToClassName(sourceRoot, javaFile);
                        if (fqcn != null) {
                            target.put(fqcn, javaFile);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to index Java sources in {}: {}", sourceRoot, e.getMessage());
        }
    }

    /**
     * Convert a .java file path to a fully-qualified class name.
     * e.g., src/main/java/com/example/Foo.java → com.example.Foo
     */
    private String pathToClassName(Path sourceRoot, Path javaFile) {
        Path relative = sourceRoot.relativize(javaFile);
        String relStr = relative.toString();
        if (relStr.endsWith(".java")) {
            relStr = relStr.substring(0, relStr.length() - 5);
        }
        return relStr.replace('/', '.').replace('\\', '.');
    }

    /**
     * Look up the source file for a fully-qualified class name.
     *
     * @param className fully-qualified class name, e.g. "com.example.MyClass"
     * @return the source file URI, or null if not found
     */
    public URI findSourceURI(String className) {
        Path path = classNameToSource.get(className);
        if (path != null) {
            return path.toUri();
        }
        return null;
    }

    /**
     * Create an LSP Location pointing to the class declaration in a Java
     * source file for the given fully-qualified class name.
     *
     * @param className fully-qualified class name
     * @return a Location at the class declaration, or null if not found
     */
    public Location findLocationForClass(String className) {
        Path sourcePath = classNameToSource.get(className);
        if (sourcePath == null) {
            return null;
        }
        URI sourceURI = sourcePath.toUri();
        String simpleName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;

        try {
            List<String> lines = Files.readAllLines(sourcePath);
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                // Track block comments
                if (inBlockComment) {
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                if (line.trim().startsWith("/*")) {
                    inBlockComment = true;
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                // Skip single-line comments
                String trimmed = line.trim();
                if (trimmed.startsWith("//")) {
                    continue;
                }

                Matcher m = CLASS_DECL_PATTERN.matcher(line);
                if (m.find() && m.group(1).equals(simpleName)) {
                    int col = line.indexOf(simpleName);
                    Position start = new Position(i, col);
                    Position end = new Position(i, col + simpleName.length());
                    return new Location(sourceURI.toString(), new Range(start, end));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read Java source {}: {}", sourcePath, e.getMessage());
        }

        // Fallback: top of file
        return new Location(sourceURI.toString(), new Range(new Position(0, 0), new Position(0, 0)));
    }

    /**
     * Create an LSP Location pointing to a method declaration in a Java
     * source file.
     *
     * @param className  fully-qualified class name containing the method
     * @param methodName the method name to find
     * @param paramCount number of parameters (-1 to match any overload)
     * @return a Location at the method declaration, or null if not found
     */
    public Location findLocationForMethod(String className, String methodName, int paramCount) {
        Path sourcePath = classNameToSource.get(className);
        if (sourcePath == null) {
            return null;
        }
        URI sourceURI = sourcePath.toUri();

        try {
            List<String> lines = Files.readAllLines(sourcePath);
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                // Track block comments
                if (inBlockComment) {
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                if (line.trim().startsWith("/*")) {
                    inBlockComment = true;
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("//")) {
                    continue;
                }

                Matcher m = METHOD_DECL_PATTERN.matcher(line);
                if (m.find() && m.group(1).equals(methodName)) {
                    if (paramCount >= 0) {
                        // Collect the full signature across lines if needed
                        String fullSig = collectUntilClosingParen(lines, i, m.start());
                        if (!matchesParamCount(fullSig, methodName, paramCount)) {
                            continue;
                        }
                    }
                    int col = line.indexOf(methodName);
                    Position start = new Position(i, col);
                    Position end = new Position(i, col + methodName.length());
                    return new Location(sourceURI.toString(), new Range(start, end));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read Java source {}: {}", sourcePath, e.getMessage());
        }

        // Fallback to class location if method not found
        return findLocationForClass(className);
    }

    /**
     * Create an LSP Location pointing to a constructor declaration in a Java
     * source file.
     *
     * @param className  fully-qualified class name
     * @param paramCount number of constructor parameters (-1 to match any)
     * @return a Location at the constructor, or null if not found
     */
    public Location findLocationForConstructor(String className, int paramCount) {
        Path sourcePath = classNameToSource.get(className);
        if (sourcePath == null) {
            return null;
        }
        URI sourceURI = sourcePath.toUri();
        String simpleName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;

        try {
            List<String> lines = Files.readAllLines(sourcePath);
            boolean inBlockComment = false;
            boolean seenClassDecl = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                if (inBlockComment) {
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                if (line.trim().startsWith("/*")) {
                    inBlockComment = true;
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("//")) {
                    continue;
                }

                // Only look for constructors after the class declaration
                if (!seenClassDecl) {
                    Matcher cm = CLASS_DECL_PATTERN.matcher(line);
                    if (cm.find() && cm.group(1).equals(simpleName)) {
                        seenClassDecl = true;
                    }
                    continue;
                }

                Matcher m = CONSTRUCTOR_DECL_PATTERN.matcher(line);
                if (m.find() && m.group(1).equals(simpleName)) {
                    // Make sure this isn't actually a method/class decl by
                    // checking no return type precedes it
                    if (paramCount >= 0) {
                        String fullSig = collectUntilClosingParen(lines, i, m.start());
                        if (!matchesParamCount(fullSig, simpleName, paramCount)) {
                            continue;
                        }
                    }
                    int col = line.indexOf(simpleName);
                    Position start = new Position(i, col);
                    Position end = new Position(i, col + simpleName.length());
                    return new Location(sourceURI.toString(), new Range(start, end));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read Java source {}: {}", sourcePath, e.getMessage());
        }

        return findLocationForClass(className);
    }

    /**
     * Create an LSP Location pointing to a field or property declaration.
     *
     * @param className fully-qualified class name
     * @param fieldName the field name to find
     * @return a Location at the field declaration, or null if not found
     */
    public Location findLocationForField(String className, String fieldName) {
        Path sourcePath = classNameToSource.get(className);
        if (sourcePath == null) {
            return null;
        }
        URI sourceURI = sourcePath.toUri();

        try {
            List<String> lines = Files.readAllLines(sourcePath);
            boolean inBlockComment = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                if (inBlockComment) {
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                if (line.trim().startsWith("/*")) {
                    inBlockComment = true;
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("//")) {
                    continue;
                }

                Matcher m = FIELD_DECL_PATTERN.matcher(line);
                if (m.find() && m.group(1).equals(fieldName)) {
                    int col = line.indexOf(fieldName);
                    Position start = new Position(i, col);
                    Position end = new Position(i, col + fieldName.length());
                    return new Location(sourceURI.toString(), new Range(start, end));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read Java source {}: {}", sourcePath, e.getMessage());
        }

        return findLocationForClass(className);
    }

    /**
     * Collect source text starting from the method/constructor name until the
     * closing parenthesis of the parameter list, spanning multiple lines if
     * necessary.
     */
    private String collectUntilClosingParen(List<String> lines, int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = startLine; i < lines.size(); i++) {
            String line = (i == startLine) ? lines.get(i).substring(startCol) : lines.get(i);
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '(') {
                    depth++;
                    started = true;
                } else if (c == ')') {
                    depth--;
                    if (started && depth == 0) {
                        sb.append(line, 0, j + 1);
                        return sb.toString();
                    }
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Check if the collected signature has the expected parameter count.
     * A parameter count of 0 matches empty parens "()" or "()".
     */
    private boolean matchesParamCount(String signature, String name, int expected) {
        int parenOpen = signature.indexOf('(');
        int parenClose = signature.lastIndexOf(')');
        if (parenOpen == -1 || parenClose == -1 || parenClose <= parenOpen) {
            return false;
        }
        String params = signature.substring(parenOpen + 1, parenClose).trim();
        if (params.isEmpty()) {
            return expected == 0;
        }
        // Count commas at top-level (not inside < > generics)
        int count = 1;
        int genericDepth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') genericDepth++;
            else if (c == '>') genericDepth--;
            else if (c == ',' && genericDepth == 0) count++;
        }
        return count == expected;
    }
}
