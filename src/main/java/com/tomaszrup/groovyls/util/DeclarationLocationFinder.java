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

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Finds precise declaration positions (class, method, field, constructor)
 * within Java/Groovy source lines.
 * Extracted from {@link JavaSourceLocator} for single-responsibility.
 */
class DeclarationLocationFinder {
    private static final int MAX_DECLARATION_LINE_CHARS = 10_000;

    // Matches class/interface/enum/record declarations (not inside comments)
    static final Pattern CLASS_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|abstract|final|static)\\s++)*+"
                + "(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)\\b");

    // Matches constructor declarations
    static final Pattern CONSTRUCTOR_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|protected|private)\\s++)?([A-Za-z_$][\\w$]*)\\s*+\\(");

    private DeclarationLocationFinder() {
        // utility class
    }

    @FunctionalInterface
    interface LineResolver {
        Location resolve(String line, int lineIndex, List<String> lines);
    }

    static final class DeclarationInfo {
        final String name;
        final int startCol;

        DeclarationInfo(String name, int startCol) {
            this.name = name;
            this.startCol = startCol;
        }
    }

    static final class CommentScanState {
        boolean inBlockComment;
    }

    static final class ConstructorScanState {
        boolean seenClassDecl;
    }

    static String simpleClassName(String className) {
        String baseName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;
        return baseName.contains("$")
                ? baseName.substring(baseName.lastIndexOf('$') + 1)
                : baseName;
    }

    static Location locationAtToken(URI uri, int lineIndex, String line, String token) {
        int col = line.indexOf(token);
        Position start = new Position(lineIndex, col);
        Position end = new Position(lineIndex, col + token.length());
        return new Location(uri.toString(), new Range(start, end));
    }

    static boolean shouldSkipAsComment(String line, CommentScanState state) {
        String trimmed = line.trim();
        if (state.inBlockComment) {
            if (line.contains("*/")) {
                state.inBlockComment = false;
            }
            return true;
        }
        if (trimmed.startsWith("/*")) {
            state.inBlockComment = !line.contains("*/");
            return true;
        }
        return trimmed.startsWith("//");
    }

    static Location scanSourceLines(List<String> lines, LineResolver resolver) {
        if (lines.isEmpty()) {
            return null;
        }
        CommentScanState commentState = new CommentScanState();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!shouldSkipAsComment(line, commentState)) {
                Location location = resolver.resolve(line, i, lines);
                if (location != null) {
                    return location;
                }
            }
        }
        return null;
    }

    static Location findLocationForClass(URI sourceUri, List<String> lines, String className) {
        String simpleName = simpleClassName(className);
        Location location = scanSourceLines(lines, (line, lineIndex, allLines) -> {
            if (line.length() > MAX_DECLARATION_LINE_CHARS) {
                return null;
            }
            Matcher matcher = CLASS_DECL_PATTERN.matcher(line);
            if (matcher.find() && matcher.group(1).equals(simpleName)) {
                return locationAtToken(sourceUri, lineIndex, line, simpleName);
            }
            return null;
        });
        if (location != null) {
            return location;
        }
        // Fallback: top of file
        return new Location(sourceUri.toString(), new Range(new Position(0, 0), new Position(0, 0)));
    }

    static Location findLocationForMethod(URI sourceUri, List<String> lines,
            String methodName, int paramCount) {
        return scanSourceLines(lines, (line, lineIndex, allLines) -> {
            if (line.length() > MAX_DECLARATION_LINE_CHARS) {
                return null;
            }
            DeclarationInfo methodDecl = parseMethodDeclaration(line);
            if (methodDecl != null && methodDecl.name.equals(methodName)) {
                if (paramCount >= 0) {
                    String fullSig = collectUntilClosingParen(allLines, lineIndex, methodDecl.startCol);
                    if (!matchesParamCount(fullSig, paramCount)) {
                        return null;
                    }
                }
                return locationAtToken(sourceUri, lineIndex, line, methodName);
            }
            return null;
        });
    }

    static Location findLocationForConstructor(URI sourceUri, List<String> lines,
            String className, int paramCount) {
        String simpleName = simpleClassName(className);
        ConstructorScanState scanState = new ConstructorScanState();
        return scanSourceLines(lines,
            (line, lineIndex, allLines) -> resolveConstructorLocation(
                line, lineIndex, allLines, sourceUri, simpleName, paramCount, scanState));
    }

    private static Location resolveConstructorLocation(
            String line,
            int lineIndex,
            List<String> lines,
            URI sourceUri,
            String simpleName,
            int paramCount,
            ConstructorScanState scanState) {
        if (line.length() > MAX_DECLARATION_LINE_CHARS) {
            return null;
        }
        if (!scanState.seenClassDecl) {
            Matcher classMatcher = CLASS_DECL_PATTERN.matcher(line);
            scanState.seenClassDecl = classMatcher.find() && classMatcher.group(1).equals(simpleName);
            return null;
        }
        Matcher constructorMatcher = CONSTRUCTOR_DECL_PATTERN.matcher(line);
        if (!constructorMatcher.find() || !constructorMatcher.group(1).equals(simpleName)) {
            return null;
        }
        if (paramCount >= 0) {
            String fullSig = collectUntilClosingParen(lines, lineIndex, constructorMatcher.start());
            if (!matchesParamCount(fullSig, paramCount)) {
                return null;
            }
        }
        return locationAtToken(sourceUri, lineIndex, line, simpleName);
    }

    static Location findLocationForField(URI sourceUri, List<String> lines,
            String fieldName) {
        return scanSourceLines(lines, (line, lineIndex, allLines) -> {
            if (line.length() > MAX_DECLARATION_LINE_CHARS) {
                return null;
            }
            DeclarationInfo fieldDecl = parseFieldDeclaration(line);
            if (fieldDecl != null && fieldDecl.name.equals(fieldName)) {
                return locationAtToken(sourceUri, lineIndex, line, fieldName);
            }
            return null;
        });
    }

    static String collectUntilClosingParen(List<String> lines, int startLine, int startCol) {
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

    static boolean matchesParamCount(String signature, int expected) {
        int parenOpen = signature.indexOf('(');
        int parenClose = signature.lastIndexOf(')');
        if (parenOpen == -1 || parenClose == -1 || parenClose <= parenOpen) {
            return false;
        }
        String params = signature.substring(parenOpen + 1, parenClose).trim();
        if (params.isEmpty()) {
            return expected == 0;
        }
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

    static DeclarationInfo parseMethodDeclaration(String line) {
        if (line == null) {
            return null;
        }
        int openParen = line.indexOf('(');
        if (openParen < 0 || line.indexOf("->") >= 0) {
            return null;
        }
        int end = openParen - 1;
        while (end >= 0 && Character.isWhitespace(line.charAt(end))) {
            end--;
        }
        if (end < 0 || !Character.isJavaIdentifierPart(line.charAt(end))) {
            return null;
        }
        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(line.charAt(start))) {
            start--;
        }
        start++;
        String candidate = line.substring(start, end + 1);
        if (isControlKeyword(candidate) || start == 0) {
            return null;
        }
        return new DeclarationInfo(candidate, start);
    }

    static DeclarationInfo parseFieldDeclaration(String line) {
        if (line == null || line.indexOf('(') >= 0) {
            return null;
        }
        int semicolon = line.indexOf(';');
        int equals = line.indexOf('=');
        int boundary;
        if (semicolon < 0) {
            boundary = equals;
        } else if (equals < 0) {
            boundary = semicolon;
        } else {
            boundary = Math.min(semicolon, equals);
        }
        if (boundary <= 0) {
            return null;
        }
        int end = boundary - 1;
        while (end >= 0 && Character.isWhitespace(line.charAt(end))) {
            end--;
        }
        if (end < 0 || !Character.isJavaIdentifierPart(line.charAt(end))) {
            return null;
        }
        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(line.charAt(start))) {
            start--;
        }
        start++;
        if (start <= 0) {
            return null;
        }
        String candidate = line.substring(start, end + 1);
        if (isControlKeyword(candidate)) {
            return null;
        }
        return new DeclarationInfo(candidate, start);
    }

    static boolean isControlKeyword(String token) {
        return "if".equals(token) || "for".equals(token) || "while".equals(token)
                || "switch".equals(token) || "catch".equals(token) || "return".equals(token)
                || "throw".equals(token) || "new".equals(token);
    }
}
