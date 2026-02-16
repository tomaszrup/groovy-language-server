package com.tomaszrup.groovyls.providers;

import com.tomaszrup.groovyls.util.JavaSourceLocator;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

class DefinitionProviderTests {

    @Test
    void testProvideDefinitionWithNullAstReturnsEmpty() throws Exception {
        DefinitionProvider provider = new DefinitionProvider(null);
        Either<List<Location>, List<LocationLink>> result =
                provider.provideDefinition(new TextDocumentIdentifier("file:///tmp/Test.groovy"), new Position(0, 0)).get();
        Assertions.assertTrue(result.isLeft());
        Assertions.assertTrue(result.getLeft().isEmpty());
    }

    @Test
    void testResolveJavaSourceClassMethodFieldAndVariableBranches() throws Exception {
        StubLocator locator = new StubLocator();
        DefinitionProvider provider = new DefinitionProvider(null, locator, Collections.emptyList());

        Method resolve = DefinitionProvider.class.getDeclaredMethod("resolveJavaSource",
                org.codehaus.groovy.ast.ASTNode.class,
                org.codehaus.groovy.ast.ASTNode.class);
        resolve.setAccessible(true);

        VariableExpression offset = new VariableExpression("x");

        ClassNode classNode = new ClassNode("pkg.HitClass", 0, ClassHelper.OBJECT_TYPE);
        Location classLoc = (Location) resolve.invoke(provider, offset, classNode);
        Assertions.assertNotNull(classLoc);

        ClassNode methodOwner = new ClassNode("pkg.MethodOwner", 0, ClassHelper.OBJECT_TYPE);
        MethodNode methodNode = new MethodNode(
                "doWork",
                0,
                ClassHelper.VOID_TYPE,
                new Parameter[]{new Parameter(ClassHelper.STRING_TYPE, "name")},
                ClassNode.EMPTY_ARRAY,
                null);
        methodOwner.addMethod(methodNode);
        Location methodLoc = (Location) resolve.invoke(provider, offset, methodNode);
        Assertions.assertNotNull(methodLoc);

        ClassNode fieldOwner = new ClassNode("pkg.FieldOwner", 0, ClassHelper.OBJECT_TYPE);
        FieldNode fieldNode = new FieldNode("value", 0, ClassHelper.STRING_TYPE, fieldOwner, null);
        fieldOwner.addField(fieldNode);
        Location fieldLoc = (Location) resolve.invoke(provider, offset, fieldNode);
        Assertions.assertNotNull(fieldLoc);

        VariableExpression variable = new VariableExpression("v", new ClassNode("pkg.VarType", 0, ClassHelper.OBJECT_TYPE));
        Location varLoc = (Location) resolve.invoke(provider, offset, variable);
        Assertions.assertNotNull(varLoc);
    }

    @Test
    void testResolveJavaSourceDoesNotUseSiblingLocatorFallback() throws Exception {
        NonDecompilingLocator sibling = new NonDecompilingLocator(true);
        DefinitionProvider provider = new DefinitionProvider(null, null, Collections.singletonList(sibling));

        Method resolve = DefinitionProvider.class.getDeclaredMethod("resolveJavaSource",
                org.codehaus.groovy.ast.ASTNode.class,
                org.codehaus.groovy.ast.ASTNode.class);
        resolve.setAccessible(true);

        VariableExpression offset = new VariableExpression("x");
        ClassNode classNode = new ClassNode("pkg.SiblingOnly", 0, ClassHelper.OBJECT_TYPE);

        Location location = (Location) resolve.invoke(provider, offset, classNode);
        Assertions.assertNull(location,
                "Definition resolution should stay within active scope locator and ignore sibling locators");
    }

    private static final class StubLocator extends JavaSourceLocator {
        private final Location fixed = new Location("file:///tmp/JavaStub.java",
                new Range(new Position(0, 0), new Position(0, 1)));

        @Override
        public Location findLocationForClass(String className) {
            if (className != null && (className.endsWith("HitClass") || className.endsWith("VarType"))) {
                return fixed;
            }
            return null;
        }

        @Override
        public Location findLocationForMethod(String className, String methodName, int paramCount) {
            if (className != null && className.endsWith("MethodOwner") && "doWork".equals(methodName) && paramCount == 1) {
                return fixed;
            }
            return null;
        }

        @Override
        public Location findLocationForField(String className, String fieldName) {
            if (className != null && className.endsWith("FieldOwner") && "value".equals(fieldName)) {
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
