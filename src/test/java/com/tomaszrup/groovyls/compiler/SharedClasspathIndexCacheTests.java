package com.tomaszrup.groovyls.compiler;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class SharedClasspathIndexCacheTests {

    @Test
    void testAcquireWithNullClassLoaderReturnsNull() {
        SharedClasspathIndexCache cache = new SharedClasspathIndexCache();
        Assertions.assertNull(cache.acquireWithResult(null));
    }

    @Test
    void testAcquireExactHitReturnsIndexAndThenReusesIt() {
        SharedClasspathIndexCache cache = new SharedClasspathIndexCache();
        GroovyClassLoader classLoader = new GroovyClassLoader(
                ClassLoader.getSystemClassLoader().getParent(),
                new CompilerConfiguration(),
                true
        );

        SharedClasspathIndexCache.AcquireResult first = cache.acquireWithResult(classLoader);
        SharedClasspathIndexCache.AcquireResult second = cache.acquireWithResult(classLoader);

        Assertions.assertNotNull(first);
        Assertions.assertNotNull(first.getIndex());
        Assertions.assertNull(first.getOwnClasspathElementPaths());

        Assertions.assertNotNull(second);
        Assertions.assertSame(first.getIndex(), second.getIndex());
        Assertions.assertNull(second.getOwnClasspathElementPaths());

        cache.clear();
    }

    @Test
    void testInvalidateEntriesUnderProjectRemovesMatchingEntry() throws Exception {
        SharedClasspathIndexCache cache = new SharedClasspathIndexCache();
        Path projectRoot = Files.createTempDirectory("cp-cache-project");
        Path classesDir = projectRoot.resolve("build/classes");
        Files.createDirectories(classesDir);

        GroovyClassLoader classLoader = new GroovyClassLoader(
                ClassLoader.getSystemClassLoader().getParent(),
                new CompilerConfiguration(),
                true
        );
        classLoader.addClasspath(classesDir.toString());

        SharedClasspathIndexCache.AcquireResult first = cache.acquireWithResult(classLoader);
        Assertions.assertNotNull(first);
        Assertions.assertNotNull(first.getIndex());

        int removed = cache.invalidateEntriesUnderProject(projectRoot);
        Assertions.assertTrue(removed >= 1,
                "Expected at least one cache entry to be invalidated for project root");

        SharedClasspathIndexCache.AcquireResult second = cache.acquireWithResult(classLoader);
        Assertions.assertNotNull(second);
        Assertions.assertNotNull(second.getIndex());
        Assertions.assertNotSame(first.getIndex(), second.getIndex(),
                "Invalidation should force index rebuild instead of reusing stale entry");

        cache.clear();
    }
}
