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
import java.lang.ref.SoftReference;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.lsp4j.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates Java and Groovy source files ({@code .java} and {@code .groovy}) by
 * fully-qualified class name and finds precise declaration positions for
 * classes, methods, fields, and constructors.
 * <p>
 * When a Groovy file references a class that exists in the same project or in
 * a dependency (e.g., under {@code src/main/java} or inside a
 * {@code *-sources.jar}), the Groovy compiler only sees the compiled bytecode
 * on the classpath. This locator bridges the gap by mapping FQCNs back to
 * their source file URIs so that "Go to Definition" can navigate into real
 * source code at the correct line — for both Java and Groovy sources.
 */
public class JavaSourceLocator {
    private static final Logger logger = LoggerFactory.getLogger(JavaSourceLocator.class);
    private static final String JAVA_EXTENSION = ".java";
    private static final String GROOVY_EXTENSION = ".groovy";
    private static final String CLASS_EXTENSION = ".class";
    private static final String DECOMPILED_SCHEME = "decompiled:";

    private static final List<String> JAVA_SOURCE_DIRS = Arrays.asList(
            "src/main/java",
            "src/test/java",
            "src/main/groovy",
            "src/test/groovy"
    );

    /** Maps fully-qualified class name → source file path (volatile for safe publication) */
    private final Map<String, Path> classNameToSource = new ConcurrentHashMap<>();

    /**
     * Maps fully-qualified class name → source JAR path + entry name for classes
     * found inside {@code *-sources.jar} dependency archives.
     */
    private final Map<String, SourceJarEntry> classNameToSourceJar = new ConcurrentHashMap<>();

    /** All project roots being tracked (thread-safe for concurrent adds) */
    private final List<Path> projectRoots = new CopyOnWriteArrayList<>();

    /** Classpath JAR paths registered for source-JAR discovery */
    private final List<String> classpathEntries = new CopyOnWriteArrayList<>();

    /**
     * Tracks which source JARs have already been indexed to avoid
     * re-scanning the same archive on repeated calls.
     */
    /**
     * Maximum number of entries in the decompiled content LRU cache.
     * Each entry is a list of source lines (~1-10 KB).  With 256 entries
     * the cache uses at most ~2.5 MB per scope instead of growing unbounded.
     */
    private static final int DECOMPILED_CACHE_MAX_ENTRIES = 256;

    /**
     * Cache of decompiled class content, keyed by FQCN. Values are wrapped
     * in {@link SoftReference} so the GC can reclaim them under memory
     * pressure (the content can be re-decompiled on demand).
     * Uses a bounded LRU eviction policy to prevent unbounded growth.
     */
    private final Map<String, SoftReference<List<String>>> decompiledContentCache =
            new LinkedHashMap<String, SoftReference<List<String>>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SoftReference<List<String>>> eldest) {
                    return size() > DECOMPILED_CACHE_MAX_ENTRIES;
                }
            };

    /**
     * Maximum number of entries in the class-file URI and reverse-lookup
     * caches.  These caches grow as classes are navigated to; bounding them
     * prevents unbounded memory growth in workspaces with many dependency
     * classes.
     */
    private static final int CLASS_FILE_CACHE_MAX_ENTRIES = 2000;

    /** Cache of FQCN → class-file URI (jar: or jrt: scheme). Bounded LRU. */
    private final Map<String, URI> classFileURICache =
            new LinkedHashMap<String, URI>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, URI> eldest) {
                    return size() > CLASS_FILE_CACHE_MAX_ENTRIES;
                }
            };

    /** Reverse lookup: URI string → FQCN for serving decompiled content by URI. Bounded LRU. */
    private final Map<String, String> uriToClassName =
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CLASS_FILE_CACHE_MAX_ENTRIES;
                }
            };

    /** Shared source JAR index entry (may be null if not using shared indexing). */
    private final AtomicReference<SharedSourceJarIndex.IndexEntry> sharedIndexEntry = new AtomicReference<>();

    // --- SoftReference decompiled-cache helpers ---

    /**
     * Get decompiled content from the cache, returning {@code null} if the
     * entry is missing or the soft reference has been cleared by the GC.
     */
    private List<String> getDecompiledCacheEntry(String className) {
        SoftReference<List<String>> ref = decompiledContentCache.get(className);
        return ref != null ? ref.get() : null;
    }

    /**
     * Put decompiled content into the cache wrapped in a {@link SoftReference}.
     */
    private void putDecompiledCacheEntry(String className, List<String> lines) {
        decompiledContentCache.put(className, new SoftReference<>(lines));
    }

    /** The classloader used by the Groovy compiler, for locating .class files. */
    private final AtomicReference<ClassLoader> compilationClassLoader = new AtomicReference<>();

    /**
     * Represents a Java source entry inside a source JAR.
     */
    static class SourceJarEntry {
        final Path sourceJarPath;
        final String entryName;  // e.g. "com/example/Foo.java"

        SourceJarEntry(Path sourceJarPath, String entryName) {
            this.sourceJarPath = sourceJarPath;
            this.entryName = entryName;
        }
    }

    public JavaSourceLocator() {
        // Intentionally empty.
    }

    /**
     * Release shared resources held by this locator. Call on scope disposal
     * to decrement the shared source-JAR index ref count.
     */
    public void dispose() {
        SharedSourceJarIndex.IndexEntry entry = sharedIndexEntry.getAndSet(null);
        if (entry != null) {
            SharedSourceJarIndex.getInstance().release(entry);
        }
        decompiledContentCache.clear();
        classFileURICache.clear();
        uriToClassName.clear();
    }

    /**
     * Estimates the heap memory consumed by this locator's per-scope caches
     * (decompiled content, class-file URI cache, reverse URI lookup).
     * Does NOT count the shared source-JAR index (tracked globally).
     *
     * @return estimated bytes consumed
     */
    public long estimateMemoryBytes() {
        long bytes = 0;
        // classNameToSource: FQCN string -> Path (~200 bytes per entry)
        bytes += (long) classNameToSource.size() * 200;
        // decompiledContentCache: up to DECOMPILED_CACHE_MAX_ENTRIES.
        // Each entry is SoftReference<List<String>> — only count if not GC'd.
        // Average decompiled class ~5 KB of source lines.
        int liveEntries = 0;
        for (SoftReference<List<String>> ref : decompiledContentCache.values()) {
            if (ref != null && ref.get() != null) {
                liveEntries++;
            }
        }
        bytes += (long) liveEntries * 5120;
        // classFileURICache + uriToClassName: ~200 bytes per entry each
        bytes += (long) classFileURICache.size() * 200;
        bytes += (long) uriToClassName.size() * 200;
        return bytes;
    }

    /**
     * Set the classloader used by the Groovy compiler. This is used to
     * locate {@code .class} files on the classpath so that "Go to Definition"
     * can return a {@code jar:} or {@code jrt:} URI pointing to the actual
     * class file inside the JAR.
     *
     * @param classLoader the compilation classloader (may be null)
     */
    public void setCompilationClassLoader(ClassLoader classLoader) {
        this.compilationClassLoader.set(classLoader);
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
        classNameToSource.clear();
        classNameToSource.putAll(snapshot);
        logger.debug("addProjectRoot({}): indexed {} source classes", projectRoot, snapshot.size());
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
        classNameToSource.clear();
        classNameToSource.putAll(snapshot);
    }

    /**
     * Index source JARs corresponding to the given classpath entries.
     * <p>
     * For each JAR on the classpath (e.g. {@code foo-1.0.jar}), this method
     * looks for a corresponding {@code foo-1.0-sources.jar} in the same
     * directory (the Maven/Gradle convention). If found, all {@code .java}
     * entries inside the source JAR are indexed so that "Go to Definition"
     * can navigate into dependency source code.
     *
     * @param classpathJars the classpath entries (JAR file paths)
     */
    public void addClasspathJars(List<String> classpathJars) {
        if (classpathJars == null || classpathJars.isEmpty()) {
            return;
        }

        LinkedHashSet<String> deduped = new LinkedHashSet<>(classpathEntries);
        deduped.addAll(classpathJars);
        if (deduped.size() == classpathEntries.size()) {
            return;
        }
        classpathEntries.clear();
        classpathEntries.addAll(deduped);

        // Use the shared source-JAR index to avoid per-scope duplication.
        // Release any previously held entry before acquiring a new one.
        SharedSourceJarIndex sharedIndex = SharedSourceJarIndex.getInstance();
        SharedSourceJarIndex.IndexEntry oldEntry = sharedIndexEntry.get();
        SharedSourceJarIndex.IndexEntry newEntry = sharedIndex.acquire(new ArrayList<>(classpathEntries));
        sharedIndexEntry.set(newEntry);
        if (oldEntry != null) {
            sharedIndex.release(oldEntry);
        }

        // Update classNameToSourceJar to point to the shared map
        classNameToSourceJar.clear();
        classNameToSourceJar.putAll(newEntry.getClassNameToSourceJar());

        logger.info("Using shared source JAR index: {} dependency classes",
                classNameToSourceJar.size());

        // Evict stale decompiled stubs from the cache for classes that
        // now have real source available.
        int evicted = 0;
        for (String className : classNameToSourceJar.keySet()) {
            List<String> cached = getDecompiledCacheEntry(className);
            if (cached != null && isDecompiledStub(cached)) {
                decompiledContentCache.remove(className);
                evicted++;
            }
        }
        if (evicted > 0) {
            logger.info("Evicted {} stale decompiled stubs now covered by source JARs",
                    evicted);
        }
    }

    /**
     * Given a classpath JAR path, find the corresponding {@code -sources.jar}.
     * Delegates to {@link SourceJarIndexer#findSourceJar(String)}.
     */
    static Path findSourceJar(String classpathEntry) {
        return SourceJarIndexer.findSourceJar(classpathEntry);
    }

    /**
     * Static variant used by {@link SharedSourceJarIndex} to build the shared
     * index. Delegates to {@link SourceJarIndexer#indexSourceJar}.
     */
    static void indexSourceJarStatic(Path sourceJar, Map<String, SourceJarEntry> target) {
        SourceJarIndexer.indexSourceJar(sourceJar, target);
    }

    /**
     * Read the content of a Java source file from inside a source JAR.
     */
    private List<String> readSourceFromJar(SourceJarEntry entry) {
        return SourceJarIndexer.readSourceFromJar(entry);
    }

    /**
     * Create a VS Code-compatible URI for a source JAR entry.
     * <p>
     * Uses the same simplified format as {@link #toVSCodeCompatibleURI(URI)}:
     * only the JAR filename is kept and package separators use dots.
     * This avoids the URI round-trip corruption that occurs with nested
     * {@code file:///} URIs on Windows (colons are percent-encoded by
     * {@code vscode.Uri.toString()}).
     * <p>
     * Example: {@code spock-core-2.4-sources.jar} + {@code spock/lang/Specification.java}
     * → {@code jar:///spock-core-2.4-sources.jar/spock.lang/Specification.java}
     */
    private static URI sourceJarToVSCodeURI(SourceJarEntry entry) {
        return ClassFileURIResolver.sourceJarToVSCodeURI(entry);
    }

    private void indexSourceDirectory(Path sourceRoot, Map<String, Path> target) {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.toString();
                        return name.endsWith(JAVA_EXTENSION) || name.endsWith(GROOVY_EXTENSION);
                    })
                    .forEach(sourceFile -> {
                        String fqcn = pathToClassName(sourceRoot, sourceFile);
                        if (fqcn != null) {
                            target.put(fqcn, sourceFile);
                        }
                    });
        } catch (IOException e) {
            logger.warn("Failed to index sources in {}: {}", sourceRoot, e.getMessage());
        }
    }

    /**
     * Convert a .java or .groovy file path to a fully-qualified class name.
     * e.g., src/main/java/com/example/Foo.java → com.example.Foo
     * e.g., src/main/groovy/com/example/Bar.groovy → com.example.Bar
     */
    private String pathToClassName(Path sourceRoot, Path sourceFile) {
        Path relative = sourceRoot.relativize(sourceFile);
        String relStr = relative.toString();
        if (relStr.endsWith(JAVA_EXTENSION)) {
            relStr = relStr.substring(0, relStr.length() - JAVA_EXTENSION.length());
        } else if (relStr.endsWith(GROOVY_EXTENSION)) {
            relStr = relStr.substring(0, relStr.length() - GROOVY_EXTENSION.length());
        }
        return relStr.replace('/', '.').replace('\\', '.');
    }

    /**
     * Look up the source file for a fully-qualified class name.
     * Checks project sources first, then dependency source JARs.
     * For inner classes (names containing {@code $}), falls back to the
     * enclosing top-level class source file.
     *
     * @param className fully-qualified class name, e.g. "com.example.MyClass"
     * @return the source file URI, or null if not found
     */
    public URI findSourceURI(String className) {
        Path path = classNameToSource.get(className);
        if (path != null) {
            return path.toUri();
        }
        SourceJarEntry jarEntry = classNameToSourceJar.get(className);
        if (jarEntry != null) {
            return sourceJarToVSCodeURI(jarEntry);
        }
        // For inner classes, try the outer class
        String outerClassName = toOuterClassName(className);
        if (outerClassName != null) {
            return findSourceURI(outerClassName);
        }
        return null;
    }

    /**
     * Abstraction over a source location — either a local file, a JAR entry,
     * or pre-loaded decompiled content.
     */
    private static class SourceInfo {
        final URI uri;
        private final Path filePath;              // non-null for local files
        private final SourceJarEntry jarEntry;     // non-null for JAR entries
        private final JavaSourceLocator locator;
        private final List<String> preloadedLines; // non-null for decompiled content

        SourceInfo(URI uri, Path filePath, JavaSourceLocator locator) {
            this.uri = uri;
            this.filePath = filePath;
            this.jarEntry = null;
            this.locator = locator;
            this.preloadedLines = null;
        }

        SourceInfo(URI uri, SourceJarEntry jarEntry, JavaSourceLocator locator) {
            this.uri = uri;
            this.filePath = null;
            this.jarEntry = jarEntry;
            this.locator = locator;
            this.preloadedLines = null;
        }

        SourceInfo(URI uri, List<String> preloadedLines) {
            this.uri = uri;
            this.filePath = null;
            this.jarEntry = null;
            this.locator = null;
            this.preloadedLines = preloadedLines;
        }

        List<String> readLines() {
            if (preloadedLines != null) {
                return preloadedLines;
            }
            if (filePath != null) {
                try {
                    return Files.readAllLines(filePath);
                } catch (IOException e) {
                    logger.warn("Failed to read Java source {}: {}", filePath, e.getMessage());
                    return Collections.emptyList();
                }
            }
            if (jarEntry != null && locator != null) {
                return locator.readSourceFromJar(jarEntry);
            }
            return Collections.emptyList();
        }
    }

    /**
     * Resolve a fully-qualified class name to a source location.
     * Checks project sources first, then dependency source JARs.
     * For inner classes (names containing {@code $}), falls back to the
     * enclosing top-level class source file.
     *
     * @return a SourceInfo for reading the source, or null if not found
     */
    private SourceInfo resolveSource(String className) {
        Path path = classNameToSource.get(className);
        if (path != null) {
            return new SourceInfo(path.toUri(), path, this);
        }
        SourceJarEntry jarEntry = classNameToSourceJar.get(className);
        if (jarEntry != null) {
            // Pre-read and cache source content for reliable serving via the
            // extension's content provider.  The jar:file:/// format gets
            // mangled by VS Code's URI parser (colons are percent-encoded),
            // so we use a simplified jar:///filename.jar/... format and serve
            // content from our in-memory cache.
            URI uri = sourceJarToVSCodeURI(jarEntry);
            uriToClassName.put(uri.toString(), className);
            List<String> lines = readSourceFromJar(jarEntry);
            if (!lines.isEmpty()) {
                putDecompiledCacheEntry(className, lines);
                return new SourceInfo(uri, lines);
            }
            // Fall back to JAR entry reference if reading fails
            return new SourceInfo(uri, jarEntry, this);
        }
        // For inner classes (com.example.Outer$Inner), try the outer class
        String outerClassName = toOuterClassName(className);
        if (outerClassName != null) {
            return resolveSource(outerClassName);
        }
        return null;
    }

    /**
     * If {@code className} is an inner/nested class name (contains {@code $}),
     * return the top-level enclosing class name. Otherwise return {@code null}.
     * <p>
     * Example: {@code "com.example.Outer$Inner$Deep"} → {@code "com.example.Outer"}
     */
    static String toOuterClassName(String className) {
        int dollarIdx = className.indexOf('$');
        if (dollarIdx > 0) {
            return className.substring(0, dollarIdx);
        }
        return null;
    }

    /**
     * Create an LSP Location pointing to the class declaration in a Java
     * source file for the given fully-qualified class name.
     * Checks project sources first, then dependency source JARs.
     *
     * @param className fully-qualified class name
     * @return a Location at the class declaration, or null if not found
     */
    public Location findLocationForClass(String className) {
        SourceInfo source = resolveSource(className);
        if (source == null) {
            return null;
        }
        return DeclarationLocationFinder.findLocationForClass(source.uri, source.readLines(), className);
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
        SourceInfo source = resolveSource(className);
        if (source == null) {
            return null;
        }
        Location location = DeclarationLocationFinder.findLocationForMethod(
                source.uri, source.readLines(), methodName, paramCount);
        if (location != null) {
            return location;
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
        SourceInfo source = resolveSource(className);
        if (source == null) {
            return null;
        }
        Location location = DeclarationLocationFinder.findLocationForConstructor(
                source.uri, source.readLines(), className, paramCount);
        if (location != null) {
            return location;
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
        SourceInfo source = resolveSource(className);
        if (source == null) {
            return null;
        }
        Location location = DeclarationLocationFinder.findLocationForField(
                source.uri, source.readLines(), fieldName);
        if (location != null) {
            return location;
        }
        return findLocationForClass(className);
    }

    /**
     * Check if this locator can resolve the given class name to any source
     * (project source, source JAR, or decompiled content).
     * For inner classes (names containing {@code $}), also checks the
     * enclosing top-level class.
     */
    public boolean hasSource(String className) {
        if (classNameToSource.containsKey(className)
                || classNameToSourceJar.containsKey(className)) {
            return true;
        }
        // For inner classes, check the outer class
        String outerClassName = toOuterClassName(className);
        if (outerClassName != null) {
            return classNameToSource.containsKey(outerClassName)
                    || classNameToSourceJar.containsKey(outerClassName);
        }
        return false;
    }

    /**
     * Check whether the given class resolves specifically to a project source
     * file (not only a source JAR/decompiled fallback).
     */
    public boolean hasProjectSource(String className) {
        if (classNameToSource.containsKey(className)) {
            return true;
        }
        String outerClassName = toOuterClassName(className);
        if (outerClassName != null) {
            return classNameToSource.containsKey(outerClassName);
        }
        return false;
    }

    /**
     * Find fully-qualified class names whose simple name matches the given
     * unresolved class token.
     *
     * <p>Results include project sources and source JAR entries. The returned
     * list is deduplicated and sorted in insertion order (project sources first,
     * then source JARs).</p>
     */
    public List<String> findClassNamesBySimpleName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String fqcn : classNameToSource.keySet()) {
            if (simpleNameOf(fqcn).equals(simpleName)) {
                out.add(fqcn);
            }
        }
        for (String fqcn : classNameToSourceJar.keySet()) {
            if (simpleNameOf(fqcn).equals(simpleName)) {
                out.add(fqcn);
            }
        }
        return new ArrayList<>(out);
    }

    private static String simpleNameOf(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) {
            return "";
        }
        int dot = fqcn.lastIndexOf('.');
        String base = dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
        int dollar = base.indexOf('$');
        if (dollar >= 0) {
            return base.substring(0, dollar);
        }
        return base;
    }

    /**
     * Register decompiled content for a class that has no source file or
     * source JAR available. The content is stored in memory and served
     * when lookups are performed.
     * <p>
     * If a classloader is available and the class can be located on the
     * classpath, the returned URI will use the {@code jar:} or {@code jrt:}
     * scheme. The {@code jar:} URIs are simplified to use only the JAR
     * filename (not the full filesystem path) so that VS Code's URI parser
     * handles them correctly — matching the format used by the RedHat Java
     * extension. Otherwise, a synthetic {@code decompiled:} URI is returned.
     *
     * @param className fully-qualified class name
     * @param lines the decompiled source lines
     * @return the URI for the decompiled content (jar:, jrt:, or decompiled: scheme)
     */
    public URI registerDecompiledContent(String className, List<String> lines) {
        // If real source (project file or source JAR) is now available,
        // prefer it over the decompiled stub.  This prevents a race where
        // the decompilation fallback fires before lazy classpath resolution
        // indexes the source JARs, and then the cached stub permanently
        // shadows the real source.
        SourceInfo realSource = resolveSource(className);
        if (realSource != null) {
            // resolveSource() already cached the real content and registered
            // the URI in uriToClassName — just return the real URI.
            return realSource.uri;
        }

        decompiledContentCache.put(className, new SoftReference<>(lines));
        URI classFileURI = findClassFileURI(className);
        if (classFileURI != null) {
            uriToClassName.put(classFileURI.toString(), className);
            return classFileURI;
        }
        URI syntheticURI = ClassFileURIResolver.decompiledContentToURI(className);
        uriToClassName.put(syntheticURI.toString(), className);
        return syntheticURI;
    }

    /**
     * Retrieve previously decompiled content for the given fully-qualified
     * class name.
     *
     * @param className fully-qualified class name (e.g. {@code spock.lang.Specification})
     * @return the decompiled source text, or {@code null} if not cached
     */
    public String getDecompiledContent(String className) {
        List<String> lines = getDecompiledCacheEntry(className);
        if (lines == null) {
            return null;
        }
        return String.join("\n", lines);
    }

    /**
     * If the cached content for the given class is a decompiled stub and
     * real source (project file or source JAR) is now available, re-resolve
     * it from the real source, update the cache, and return the content.
     *
     * @return the real source content, or {@code null} if no real source is
     *         available or the cached content is already real source
     */
    private String refreshFromRealSourceIfAvailable(String className) {
        List<String> cached = getDecompiledCacheEntry(className);
        // Refresh if:
        // (a) the cached content is a decompiled stub, OR
        // (b) the entry was previously evicted (null) but the class name is
        //     still registered in uriToClassName (so a document was opened)
        boolean needsRefresh = (cached != null && isDecompiledStub(cached))
                || (cached == null && hasSource(className));
        if (needsRefresh) {
            SourceInfo realSource = resolveSource(className);
            if (realSource != null) {
                // resolveSource() updated decompiledContentCache with real content
                List<String> refreshed = getDecompiledCacheEntry(className);
                if (refreshed != null) {
                    return String.join("\n", refreshed);
                }
            }
        }
        return null;
    }

    /**
     * Detect whether cached lines are a decompiled stub (generated by
     * {@link ClassNodeDecompiler}) rather than real source.
     */
    private static boolean isDecompiledStub(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("package ")) {
                // The decompiler always emits this as the first non-package line
                return trimmed.startsWith("// Decompiled from bytecode");
            }
        }
        return false;
    }

    /**
     * Retrieve decompiled content by URI string. Supports {@code decompiled:},
     * {@code jar:}, and {@code jrt:} URIs.
     *
     * @param uri the URI string
     * @return the decompiled source text, or {@code null} if not found
     */
    public String getDecompiledContentByURI(String uri) {
        // Try reverse lookup first (covers jar: and jrt: URIs)
        String className = uriToClassName.get(uri);
        if (className != null) {
            // Before returning cached content, check if real source is now
            // available (source JARs may have been indexed after a stub was
            // cached due to lazy classpath resolution).
            String freshContent = refreshFromRealSourceIfAvailable(className);
            if (freshContent != null) {
                return freshContent;
            }
            return getDecompiledContent(className);
        }
        // Fall back to parsing the URI for the decompiled: scheme
        if (uri.startsWith(DECOMPILED_SCHEME)) {
            className = uri.substring(DECOMPILED_SCHEME.length());
            if (className.endsWith(JAVA_EXTENSION)) {
                className = className.substring(0, className.length() - JAVA_EXTENSION.length());
            }
            className = className.replace('/', '.');
            String freshContent = refreshFromRealSourceIfAvailable(className);
            if (freshContent != null) {
                return freshContent;
            }
            return getDecompiledContent(className);
        }
        // Try extracting FQCN from jar: or jrt: class-file URIs
        className = ClassFileURIResolver.classFileURIToClassName(uri);
        if (className != null) {
            String freshContent = refreshFromRealSourceIfAvailable(className);
            if (freshContent != null) {
                return freshContent;
            }
            return getDecompiledContent(className);
        }
        // Handle jar: URIs pointing to .java/.groovy source files inside source JARs
        if (uri.startsWith("jar:") && uri.contains("!/")
                && (uri.endsWith(JAVA_EXTENSION) || uri.endsWith(GROOVY_EXTENSION))) {
            return readSourceFromJarURI(uri);
        }
        return null;
    }

    public String getJavaNavigationURI(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }

        String className = uriToClassName.get(uri);
        if (className == null && uri.startsWith("jar:/") && uri.endsWith(JAVA_EXTENSION)) {
            className = classNameFromVirtualJarURI(uri);
        }

        if (className == null) {
            return null;
        }

        Path sourcePath = classNameToSource.get(className);
        if (sourcePath != null) {
            return sourcePath.toUri().toString();
        }

        SourceJarEntry sourceJarEntry = classNameToSourceJar.get(className);
        if (sourceJarEntry == null) {
            String outerClassName = toOuterClassName(className);
            sourceJarEntry = classNameToSourceJar.get(outerClassName);
        }

        if (sourceJarEntry == null) {
            return null;
        }

        return String.format("jar:%s!/%s", sourceJarEntry.sourceJarPath.toUri(), sourceJarEntry.entryName);
    }

    private String classNameFromVirtualJarURI(String uri) {
        String withoutPrefix = uri.substring("jar:/".length());
        int firstSlash = withoutPrefix.indexOf('/');
        if (firstSlash < 0 || firstSlash + 1 >= withoutPrefix.length()) {
            return null;
        }

        String packageAndFile = withoutPrefix.substring(firstSlash + 1);
        if (!packageAndFile.endsWith(JAVA_EXTENSION)) {
            return null;
        }

        String javaPath = packageAndFile.substring(0, packageAndFile.length() - JAVA_EXTENSION.length());
        int lastSlash = javaPath.lastIndexOf('/');
        if (lastSlash <= 0 || lastSlash + 1 >= javaPath.length()) {
            return null;
        }

        String packageName = javaPath.substring(0, lastSlash);
        String className = javaPath.substring(lastSlash + 1);
        if (packageName.isBlank() || className.isBlank()) {
            return null;
        }

        return packageName + "." + className;
    }

    /**
     * Read source content from a {@code jar:} URI pointing to a {@code .java}
     * or {@code .groovy} file inside a source JAR (e.g.
     * {@code jar:file:///path/to/sources.jar!/com/example/Foo.java}).
     */
    private String readSourceFromJarURI(String jarUri) {
        try {
            int bang = jarUri.indexOf("!/");
            if (bang <= 4) {
                return null;
            }
            // Extract the JAR file path: "jar:file:///path/to/foo.jar" -> file URI
            String jarFileUri = jarUri.substring(4, bang); // strip "jar:"
            Path jarPath = Paths.get(URI.create(jarFileUri));
            String entryName = jarUri.substring(bang + 2);
            if (!SourceJarIndexer.isSafeSourceJarPath(jarPath) || !SourceJarIndexer.isSafeArchiveEntryName(entryName)) {
                return null;
            }
            SourceJarEntry entry = new SourceJarEntry(jarPath, entryName);
            List<String> lines = readSourceFromJar(entry);
            if (!lines.isEmpty()) {
                return String.join("\n", lines);
            }
        } catch (Exception e) {
            logger.debug("Failed to read source from JAR URI {}: {}", jarUri, e.getMessage());
        }
        return null;
    }

    /**
     * Locate the {@code .class} file for a fully-qualified class name using
     * the compilation classloader. Returns a {@code jar:} URI for classes
     * inside JARs, a {@code jrt:} URI for JDK modules (Java 9+), or
     * {@code null} if the class cannot be found.
     * <p>
     * For {@code jar:} URIs the full filesystem path is stripped so that
     * only the JAR filename remains in the URI path component. This
     * matches the format used by the RedHat Java extension and avoids
     * VS Code's URI parser interpreting the nested {@code file:///} as a
     * literal path (which would produce broken paths like
     * {@code .\file\C:\…}).
     * <p>
     * Example: {@code jar:file:///C:/Users/.../spock-core-2.4-M1-groovy-4.0.jar!/spock/lang/Specification.class}
     * becomes  {@code jar:///spock-core-2.4-M1-groovy-4.0.jar!/spock/lang/Specification.class}
     *
     * @param className fully-qualified class name
     * @return the class-file URI, or {@code null}
     */
    public URI findClassFileURI(String className) {
        URI cached = classFileURICache.get(className);
        if (cached != null) {
            return cached;
        }
        ClassLoader cl = compilationClassLoader.get();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        String resourcePath = className.replace('.', '/') + CLASS_EXTENSION;
        URL url = cl.getResource(resourcePath);
        if (url == null) {
            return null;
        }
        try {
            URI rawUri = url.toURI();
            URI uri = ClassFileURIResolver.toVSCodeCompatibleURI(rawUri);
            classFileURICache.put(className, uri);
            return uri;
        } catch (Exception e) {
            logger.debug("Failed to convert class URL to URI for {}: {}", className, e.getMessage());
            return null;
        }
    }

}
