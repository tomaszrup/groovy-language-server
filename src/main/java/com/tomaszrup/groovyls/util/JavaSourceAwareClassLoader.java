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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A classloader that sits between the {@code GroovyClassLoader} and its parent
 * and provides "virtual" stub classes for Java source files that have not been
 * compiled yet.
 *
 * <p>When the Groovy compiler (via its {@code GroovyClassLoader}) cannot find a
 * class on the normal classpath, the request falls through to the parent
 * classloader chain.  This classloader intercepts {@link #findClass(String)}
 * and, before giving up, checks whether a corresponding {@code .java} source
 * file exists in any known Java source directory (e.g.
 * {@code src/main/java}, {@code src/test/java}).  If it does, a minimal
 * empty stub class is defined in-memory so the import resolves without
 * waiting for a Gradle/Maven compilation.</p>
 *
 * <p>The stub class has no methods or fields — it exists purely to satisfy
 * the Groovy compiler's import resolution at the
 * {@code Phases.CANONICALIZATION} phase.  Once the real {@code .class} file is
 * produced by the build tool, a full classloader invalidation replaces this
 * classloader with a fresh one that picks up the real class.</p>
 *
 * <p><b>Thread safety:</b> this class is safe for concurrent use.  The
 * source-file index is built lazily and cached; the stub-class cache uses
 * a {@link ConcurrentHashMap}.</p>
 */
public class JavaSourceAwareClassLoader extends ClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(JavaSourceAwareClassLoader.class);

    /**
     * Standard Gradle/Maven Java source directory names relative to a project
     * root.  These are scanned to discover {@code .java} files.
     */
    private static final String[][] SOURCE_DIR_PATTERNS = {
            {"src", "main", "java"},
            {"src", "test", "java"},
    };

    private final Path projectRoot;

    /** Lazily-built index: FQCN → source Path.  {@code null} until built. */
    private volatile Map<String, Path> sourceIndex;

    /** Stub classes already defined by this loader to avoid re-definition. */
    private final ConcurrentHashMap<String, Class<?>> definedStubs = new ConcurrentHashMap<>();

    /**
     * @param parent      the parent classloader (typically the system classloader's parent)
     * @param projectRoot the project root directory used to locate Java source dirs
     */
    public JavaSourceAwareClassLoader(ClassLoader parent, Path projectRoot) {
        super(parent);
        this.projectRoot = projectRoot;
    }

    /**
     * Invalidate the cached source index so the next lookup triggers a fresh
     * scan.  Call this when Java files are created, deleted, or moved.
     */
    public void invalidateIndex() {
        sourceIndex = null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Check if we already defined a stub for this class
        Class<?> existing = definedStubs.get(name);
        if (existing != null) {
            return existing;
        }

        // Check if a .java source file exists for this class
        Map<String, Path> index = getOrBuildIndex();
        // For inner classes (com.example.Foo$Bar), check the outer class source
        String outerName = name;
        int dollar = name.indexOf('$');
        if (dollar >= 0) {
            outerName = name.substring(0, dollar);
        }
        Path sourceFile = index.get(outerName);
        if (sourceFile != null) {
            logger.debug("Defining stub class for {} from source {}", name, sourceFile);
            return defineStubClass(name);
        }

        throw new ClassNotFoundException(name);
    }

    /**
     * Defines a minimal empty stub class with the given fully-qualified name.
     * The class extends {@code Object} and has no methods or fields.
     */
    private Class<?> defineStubClass(String className) {
        return definedStubs.computeIfAbsent(className, name -> {
            // Build minimal class bytecode using raw bytes (no ASM dependency)
            byte[] bytecode = buildStubBytecode(name);
            try {
                return defineClass(name, bytecode, 0, bytecode.length);
            } catch (LinkageError e) {
                // Class might have been defined concurrently or by parent
                logger.debug("Could not define stub for {}: {}", name, e.getMessage());
                try {
                    return loadClass(name);
                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException("Failed to define stub class: " + name, e);
                }
            }
        });
    }

    /**
     * Builds minimal valid Java class bytecode for an empty class that extends
     * {@code java.lang.Object}.  This avoids a dependency on ASM.
     *
     * <p>The bytecode follows the JVM class file format (JVMS §4).</p>
     */
    private static byte[] buildStubBytecode(String className) {
        // Internal name: com.example.Foo → com/example/Foo
        String internalName = className.replace('.', '/');
        String superName = "java/lang/Object";

        // We build a minimal class file by hand:
        //   magic, version, constant_pool, access_flags, this_class,
        //   super_class, interfaces_count, fields_count, methods_count,
        //   attributes_count
        //
        // Constant pool entries:
        //   #1 = Class #3          (this class)
        //   #2 = Class #4          (super class)
        //   #3 = Utf8 <internalName>
        //   #4 = Utf8 java/lang/Object

        byte[] nameBytes = internalName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] superBytes = superName.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int poolSize = 5; // entries #1..#4, pool count = 5

        // Calculate total size
        int size = 4  // magic
                + 2   // minor version
                + 2   // major version
                + 2   // constant_pool_count
                // #1: CONSTANT_Class → tag(1) + name_index(2)
                + 3
                // #2: CONSTANT_Class → tag(1) + name_index(2)
                + 3
                // #3: CONSTANT_Utf8 → tag(1) + length(2) + bytes
                + 3 + nameBytes.length
                // #4: CONSTANT_Utf8 → tag(1) + length(2) + bytes
                + 3 + superBytes.length
                + 2   // access_flags
                + 2   // this_class
                + 2   // super_class
                + 2   // interfaces_count
                + 2   // fields_count
                + 2   // methods_count
                + 2;  // attributes_count

        byte[] b = new byte[size];
        int pos = 0;

        // Magic number: 0xCAFEBABE
        b[pos++] = (byte) 0xCA;
        b[pos++] = (byte) 0xFE;
        b[pos++] = (byte) 0xBA;
        b[pos++] = (byte) 0xBE;

        // Version: Java 8 (52.0)
        pos = writeU2(b, pos, 0);  // minor
        pos = writeU2(b, pos, 52); // major

        // Constant pool count
        pos = writeU2(b, pos, poolSize);

        // #1: CONSTANT_Class → name_index = #3
        b[pos++] = 7; // tag
        pos = writeU2(b, pos, 3);

        // #2: CONSTANT_Class → name_index = #4
        b[pos++] = 7;
        pos = writeU2(b, pos, 4);

        // #3: CONSTANT_Utf8 → internalName
        b[pos++] = 1; // tag
        pos = writeU2(b, pos, nameBytes.length);
        System.arraycopy(nameBytes, 0, b, pos, nameBytes.length);
        pos += nameBytes.length;

        // #4: CONSTANT_Utf8 → "java/lang/Object"
        b[pos++] = 1;
        pos = writeU2(b, pos, superBytes.length);
        System.arraycopy(superBytes, 0, b, pos, superBytes.length);
        pos += superBytes.length;

        // access_flags: ACC_PUBLIC | ACC_SUPER
        pos = writeU2(b, pos, 0x0021);

        // this_class: #1
        pos = writeU2(b, pos, 1);

        // super_class: #2
        pos = writeU2(b, pos, 2);

        // interfaces_count: 0
        pos = writeU2(b, pos, 0);

        // fields_count: 0
        pos = writeU2(b, pos, 0);

        // methods_count: 0
        pos = writeU2(b, pos, 0);

        // attributes_count: 0
        writeU2(b, pos, 0);

        return b;
    }

    private static int writeU2(byte[] b, int pos, int value) {
        b[pos] = (byte) (value >>> 8);
        b[pos + 1] = (byte) value;
        return pos + 2;
    }

    /**
     * Returns the set of fully-qualified class names discovered from Java
     * source files.  Useful for testing and diagnostics.
     */
    public Set<String> getDiscoveredClasses() {
        return Collections.unmodifiableSet(getOrBuildIndex().keySet());
    }

    private Map<String, Path> getOrBuildIndex() {
        Map<String, Path> idx = sourceIndex;
        if (idx != null) {
            return idx;
        }
        synchronized (this) {
            idx = sourceIndex;
            if (idx != null) {
                return idx;
            }
            idx = buildIndex();
            sourceIndex = idx;
            return idx;
        }
    }

    private Map<String, Path> buildIndex() {
        Map<String, Path> idx = new ConcurrentHashMap<>();
        List<Path> sourceDirs = discoverSourceDirs();
        for (Path sourceDir : sourceDirs) {
            try {
                Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName().toString();
                        if (fileName.endsWith(".java")) {
                            Path relative = sourceDir.relativize(file);
                            String fqcn = pathToFqcn(relative);
                            if (fqcn != null) {
                                idx.put(fqcn, file);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                logger.debug("Could not scan Java source dir {}: {}", sourceDir, e.getMessage());
            }
        }
        if (!idx.isEmpty()) {
            logger.info("Indexed {} Java source file(s) under {}", idx.size(), projectRoot);
        }
        return idx;
    }

    /**
     * Discover standard Java source directories under the project root.
     */
    private List<Path> discoverSourceDirs() {
        List<Path> dirs = new ArrayList<>();
        for (String[] pattern : SOURCE_DIR_PATTERNS) {
            Path dir = projectRoot;
            for (String segment : pattern) {
                dir = dir.resolve(segment);
            }
            if (Files.isDirectory(dir)) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    /**
     * Convert a path relative to a source root to a fully-qualified class name.
     * E.g. {@code com/example/Frame.java} → {@code com.example.Frame}.
     */
    private static String pathToFqcn(Path relativePath) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            String segment = relativePath.getName(i).toString();
            if (i == relativePath.getNameCount() - 1) {
                // Last segment: strip .java extension
                if (!segment.endsWith(".java")) {
                    return null;
                }
                segment = segment.substring(0, segment.length() - ".java".length());
            }
            if (segment.isEmpty()) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(segment);
        }
        return sb.toString();
    }
}
