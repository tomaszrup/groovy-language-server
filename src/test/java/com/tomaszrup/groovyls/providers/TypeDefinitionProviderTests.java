package com.tomaszrup.groovyls.providers;

import com.tomaszrup.groovyls.util.JavaSourceLocator;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

class TypeDefinitionProviderTests {

    @Test
    void testProvideTypeDefinitionWithNullAstReturnsEmpty() throws Exception {
        TypeDefinitionProvider provider = new TypeDefinitionProvider(null);
        Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                provider.provideTypeDefinition(new TextDocumentIdentifier("file:///tmp/Test.groovy"), new Position(0, 0)).get();
        Assertions.assertTrue(result.isLeft());
        Assertions.assertTrue(result.getLeft().isEmpty());
    }

    @Test
    void testExtractTypeClassForSupportedNodeKinds() throws Exception {
        TypeDefinitionProvider provider = new TypeDefinitionProvider(null, new StubLocator(), Collections.emptyList());
        Method extract = TypeDefinitionProvider.class.getDeclaredMethod("extractTypeClass", org.codehaus.groovy.ast.ASTNode.class);
        extract.setAccessible(true);

        ClassNode directClass = new ClassNode("pkg.Direct", 0, ClassHelper.OBJECT_TYPE);
        ClassNode classResult = (ClassNode) extract.invoke(provider, directClass);
        Assertions.assertEquals("pkg.Direct", classResult.getName());

        MethodNode method = new MethodNode("m", 0, ClassHelper.STRING_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, null);
        ClassNode methodResult = (ClassNode) extract.invoke(provider, method);
        Assertions.assertEquals("java.lang.String", methodResult.getName());

        VariableExpression variable = new VariableExpression("v", ClassHelper.Integer_TYPE);
        ClassNode variableResult = (ClassNode) extract.invoke(provider, variable);
        Assertions.assertEquals("java.lang.Integer", variableResult.getName());
    }

    @Test
    void testResolveJavaSourceFindsClassLocation() throws Exception {
        TypeDefinitionProvider provider = new TypeDefinitionProvider(null, new StubLocator(), Collections.emptyList());
        Method resolve = TypeDefinitionProvider.class.getDeclaredMethod("resolveJavaSource", org.codehaus.groovy.ast.ASTNode.class);
        resolve.setAccessible(true);

        ClassNode classNode = new ClassNode("pkg.FoundType", 0, ClassHelper.OBJECT_TYPE);
        Location location = (Location) resolve.invoke(provider, classNode);
        Assertions.assertNotNull(location);
        Assertions.assertTrue(location.getUri().contains("JavaStub"));
    }

    @Test
    void testResolveJavaSourceDoesNotUseSiblingLocatorFallback() throws Exception {
        NonDecompilingLocator sibling = new NonDecompilingLocator(true);
        TypeDefinitionProvider provider = new TypeDefinitionProvider(null, null, Collections.singletonList(sibling));

        Method resolve = TypeDefinitionProvider.class.getDeclaredMethod("resolveJavaSource", org.codehaus.groovy.ast.ASTNode.class);
        resolve.setAccessible(true);

        ClassNode classNode = new ClassNode("pkg.SiblingOnly", 0, ClassHelper.OBJECT_TYPE);
        Location location = (Location) resolve.invoke(provider, classNode);
        Assertions.assertNull(location,
                "Type definition resolution should stay within active scope locator and ignore sibling locators");
    }

    private static final class StubLocator extends JavaSourceLocator {
        private final Location fixed = new Location("file:///tmp/JavaStub.java",
                new Range(new Position(0, 0), new Position(0, 1)));

        @Override
        public Location findLocationForClass(String className) {
            if (className != null && className.endsWith("FoundType")) {
                return fixed;
            }
            return null;
        }
    }

    private static final class NonDecompilingLocator extends JavaSourceLocator {
        private final boolean shouldResolveClass;

        private NonDecompilingLocator(boolean shouldResolveClass) {
            this.shouldResolveClass = shouldResolveClass;
        }

        @Override
        public Location findLocationForClass(String className) {
            if (shouldResolveClass && className != null && className.endsWith("SiblingOnly")) {
                return new Location("file:///tmp/SiblingOnly.java",
                        new Range(new Position(0, 0), new Position(0, 1)));
            }
            return null;
        }
    }
}
