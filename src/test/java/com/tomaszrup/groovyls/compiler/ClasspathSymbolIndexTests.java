package com.tomaszrup.groovyls.compiler;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class ClasspathSymbolIndexTests {

    @Test
    void testFromScanResultNullReturnsEmpty() {
        ClasspathSymbolIndex index = ClasspathSymbolIndex.fromScanResult(null);
        Assertions.assertNotNull(index);
        Assertions.assertTrue(index.getAllSymbols().isEmpty());
        Assertions.assertTrue(index.getPackageNames().isEmpty());
    }

    @Test
    void testFromScanResultBuildsPackagesAndSymbols() {
        try (ScanResult scan = new ClassGraph().acceptPackages("com.tomaszrup.groovyls").enableClassInfo().scan()) {
            ClasspathSymbolIndex index = ClasspathSymbolIndex.fromScanResult(scan);
            Assertions.assertFalse(index.getAllSymbols().isEmpty());
            Assertions.assertTrue(index.getPackageNames().contains("com.tomaszrup.groovyls"));
            Assertions.assertTrue(index.getAllSymbols().stream().anyMatch(s -> "com.tomaszrup.groovyls.GroovyLanguageServer".equals(s.getName())));
        }
    }

    @Test
    void testGetSymbolsFiltersByClasspathElementsAndKeepsModuleSymbols() throws Exception {
        Constructor<ClasspathSymbolIndex.Symbol> symbolCtor = ClasspathSymbolIndex.Symbol.class
                .getDeclaredConstructor(String.class, String.class, String.class,
                        ClasspathSymbolIndex.SymbolKind.class, String.class);
        symbolCtor.setAccessible(true);

        ClasspathSymbolIndex.Symbol cpSymbol = symbolCtor.newInstance(
                "com.example.A", "A", "com.example", ClasspathSymbolIndex.SymbolKind.CLASS, "C:/libs/a.jar");
        ClasspathSymbolIndex.Symbol moduleSymbol = symbolCtor.newInstance(
                "java.lang.String", "String", "java.lang", ClasspathSymbolIndex.SymbolKind.CLASS, null);

        Constructor<ClasspathSymbolIndex> indexCtor = ClasspathSymbolIndex.class
                .getDeclaredConstructor(List.class, Set.class);
        indexCtor.setAccessible(true);
        ClasspathSymbolIndex index = indexCtor.newInstance(
                Arrays.asList(cpSymbol, moduleSymbol),
                new LinkedHashSet<>(Arrays.asList("com.example", "java.lang")));

        List<ClasspathSymbolIndex.Symbol> filtered = index.getSymbols(Set.of("C:/libs/a.jar"));
        Assertions.assertEquals(2, filtered.size());

        List<ClasspathSymbolIndex.Symbol> noMatch = index.getSymbols(Set.of("C:/libs/other.jar"));
        Assertions.assertEquals(1, noMatch.size());
        Assertions.assertEquals("java.lang.String", noMatch.get(0).getName());
    }
}
