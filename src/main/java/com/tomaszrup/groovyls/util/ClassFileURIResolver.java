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

/**
 * Static utility methods for manipulating class-file URIs
 * ({@code jar:}, {@code jrt:}, {@code decompiled:} schemes) and
 * converting between URI formats and fully-qualified class names.
 * Extracted from {@link JavaSourceLocator} for single-responsibility.
 */
class ClassFileURIResolver {
    private static final String JAVA_EXTENSION = ".java";
    private static final String GROOVY_EXTENSION = ".groovy";
    private static final String CLASS_EXTENSION = ".class";
    private static final String DECOMPILED_SCHEME = "decompiled:";

    private ClassFileURIResolver() {
        // utility class
    }

    /**
     * Transform a JVM-produced {@code jar:file:///…} URI into a VS Code
     * compatible format matching the RedHat Java extension convention:
     * only the JAR filename is kept, packages use dotted notation, and
     * there is no {@code !} separator.
     */
    static URI toVSCodeCompatibleURI(URI rawUri) {
        String raw = rawUri.toString();
        if (!raw.startsWith("jar:")) {
            return rawUri;
        }
        int bangSlash = raw.indexOf("!/");
        if (bangSlash < 0) {
            return rawUri;
        }
        String jarFilePart = raw.substring(4, bangSlash);
        int lastSlash = jarFilePart.lastIndexOf('/');
        String jarFileName = (lastSlash >= 0) ? jarFilePart.substring(lastSlash + 1) : jarFilePart;
        String entryPath = raw.substring(bangSlash + 2);
        int lastEntrySlash = entryPath.lastIndexOf('/');
        String dottedEntry;
        if (lastEntrySlash >= 0) {
            String pkg = entryPath.substring(0, lastEntrySlash).replace('/', '.');
            String fileName = entryPath.substring(lastEntrySlash + 1);
            dottedEntry = pkg + "/" + fileName;
        } else {
            dottedEntry = entryPath;
        }
        return URI.create("jar:///" + jarFileName + "/" + dottedEntry);
    }

    /**
     * Create a VS Code-compatible URI for a source JAR entry.
     */
    static URI sourceJarToVSCodeURI(JavaSourceLocator.SourceJarEntry entry) {
        String jarFileName = entry.sourceJarPath.getFileName().toString();
        String entryPath = entry.entryName;
        int lastSlash = entryPath.lastIndexOf('/');
        String dottedEntry;
        if (lastSlash >= 0) {
            String pkg = entryPath.substring(0, lastSlash).replace('/', '.');
            String fileName = entryPath.substring(lastSlash + 1);
            dottedEntry = pkg + "/" + fileName;
        } else {
            dottedEntry = entryPath;
        }
        return URI.create("jar:///" + jarFileName + "/" + dottedEntry);
    }

    /**
     * Extract a FQCN from a class-file URI ({@code jar:} or {@code jrt:} scheme).
     */
    static String classFileURIToClassName(String uri) {
        if (uri == null) {
            return null;
        }
        String path = extractPathFromClassFileURI(uri);
        return pathToClassName(path);
    }

    private static String extractPathFromClassFileURI(String uri) {
        if (uri.startsWith("jar:")) {
            return extractJarEntryPath(uri);
        }
        if (uri.startsWith("jrt:")) {
            return extractJrtEntryPath(uri);
        }
        return null;
    }

    static String extractJarEntryPath(String uri) {
        int bangSlash = uri.indexOf("!/");
        if (bangSlash >= 0) {
            return uri.substring(bangSlash + 2);
        }
        int jarExt = uri.indexOf(".jar/");
        if (jarExt < 0) {
            return null;
        }
        String path = uri.substring(jarExt + 5);
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return path;
        }
        String pkg = path.substring(0, lastSlash).replace('.', '/');
        return pkg + path.substring(lastSlash);
    }

    static String extractJrtEntryPath(String uri) {
        String path = uri.substring("jrt:/".length());
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : null;
    }

    static String pathToClassName(String path) {
        if (path == null) {
            return null;
        }
        String withoutExtension = stripKnownTypeExtension(path);
        return withoutExtension == null ? null : withoutExtension.replace('/', '.');
    }

    static String stripKnownTypeExtension(String path) {
        if (path.endsWith(CLASS_EXTENSION)) {
            return path.substring(0, path.length() - CLASS_EXTENSION.length());
        }
        if (path.endsWith(JAVA_EXTENSION)) {
            return path.substring(0, path.length() - JAVA_EXTENSION.length());
        }
        if (path.endsWith(GROOVY_EXTENSION)) {
            return path.substring(0, path.length() - GROOVY_EXTENSION.length());
        }
        return null;
    }

    /**
     * Create a synthetic URI for decompiled content.
     */
    static URI decompiledContentToURI(String className) {
        return URI.create(DECOMPILED_SCHEME + className.replace('.', '/') + JAVA_EXTENSION);
    }
}
