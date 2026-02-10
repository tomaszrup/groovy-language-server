import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Smoke test that runs against the shadow JAR to verify all Groovy
 * classes needed for compilation are present after minimization.
 *
 * Usage:
 *   java -cp build/libs/groovy-language-server-all.jar ShadowJarSmokeTest.java
 */
public class ShadowJarSmokeTest {
    public static void main(String[] args) {
        System.out.println("=== Shadow JAR Smoke Test ===");

        try {
            // 1. Create a CompilationUnit — this triggers ClassHelper, VMPluginFactory,
            //    DefaultGroovyMethods, and dgmimpl static initialization
            CompilerConfiguration config = new CompilerConfiguration();
            CompilationUnit unit = new CompilationUnit(config);
            System.out.println("[PASS] CompilationUnit created successfully");

            // 2. Add a Groovy source and compile to SEMANTIC_ANALYSIS
            //    (same phase the language server uses)
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
            System.out.println("[PASS] Compiled to SEMANTIC_ANALYSIS phase");

            // 3. Verify AST was built
            var modules = unit.getAST().getModules();
            if (modules.isEmpty()) {
                System.out.println("[FAIL] No modules in AST");
                System.exit(1);
            }
            var classNodes = modules.get(0).getClasses();
            System.out.println("[PASS] AST contains " + classNodes.size() + " class(es)");

            // 4. Verify class node details are accessible
            var helloClass = classNodes.stream()
                .filter(c -> c.getNameWithoutPackage().equals("Hello"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Hello class not found in AST"));

            var methods = helloClass.getMethods();
            var fields = helloClass.getFields();
            System.out.println("[PASS] Hello class has " + methods.size() + " method(s), " + fields.size() + " field(s)");

            System.out.println("\n=== All checks passed! Shadow JAR is functional. ===");

        } catch (Throwable t) {
            System.out.println("[FAIL] " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
