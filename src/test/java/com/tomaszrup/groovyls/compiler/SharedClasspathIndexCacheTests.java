package com.tomaszrup.groovyls.compiler;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
}
