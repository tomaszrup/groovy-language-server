package com.tomaszrup.groovyls;

import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke test that runs against the shadow JAR to verify all Groovy
 * classes needed for compilation are present after minimization.
 *
 * Usage:
 *   java -cp build/libs/groovy-language-server-all.jar com.tomaszrup.groovyls.ShadowJarSmokeTest
 */
@SuppressWarnings("all")
public class ShadowJarSmokeTest {
	private static final Logger logger = LoggerFactory.getLogger(ShadowJarSmokeTest.class);

    public static void main(String[] args) {
        logger.info("=== Shadow JAR Smoke Test ===");

        try {
            CompilerConfiguration config = new CompilerConfiguration();
            CompilationUnit unit = new CompilationUnit(config);
            logger.info("[PASS] CompilationUnit created successfully");

            String groovySource = String.join("\n",
                "package test",
                "",
                "class Hello {",
                "    String name",
                "    int count = 42",
                "",
                "    String greet() {",
                "        return \"Hello, ${name}! Count: ${count + 1}\"",
                "    }",
                "",
                "    static void main(String[] args) {",
                "        def h = new Hello(name: 'World')",
                "        println h.greet()",
                "    }",
                "}"
            );

            unit.addSource("Hello.groovy", groovySource);
            unit.compile(Phases.SEMANTIC_ANALYSIS);
            logger.info("[PASS] Compiled to SEMANTIC_ANALYSIS phase");

            var modules = unit.getAST().getModules();
            if (modules.isEmpty()) {
                logger.error("[FAIL] No modules in AST");
                System.exit(1);
            }

            var classNodes = modules.get(0).getClasses();
            logger.info("[PASS] AST contains {} class(es)", classNodes.size());

            var helloClass = classNodes.stream()
                .filter(c -> c.getNameWithoutPackage().equals("Hello"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Hello class not found in AST"));

            var methods = helloClass.getMethods();
            var fields = helloClass.getFields();
            logger.info("[PASS] Hello class has {} method(s), {} field(s)", methods.size(), fields.size());

            logger.info("=== All checks passed! Shadow JAR is functional. ===");
        } catch (RuntimeException | LinkageError e) {
            logger.error("[FAIL] {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            System.exit(1);
        }
    }
}