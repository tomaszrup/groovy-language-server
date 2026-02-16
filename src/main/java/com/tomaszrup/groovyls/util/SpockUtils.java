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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;

/**
 * Utility methods for detecting and working with Spock Framework specifications.
 * <p>
 * Spock is a testing and specification framework for Groovy applications.
 * Spock specs extend {@code spock.lang.Specification} and use labeled blocks
 * ({@code given:}, {@code when:}, {@code then:}, etc.) to structure test methods
 * (called "feature methods").
 */
public class SpockUtils {
	private static final String BLOCK_GIVEN = "given";
	private static final String BLOCK_SETUP = "setup";
	private static final String BLOCK_WHEN = "when";
	private static final String BLOCK_THEN = "then";
	private static final String BLOCK_EXPECT = "expect";
	private static final String BLOCK_CLEANUP = "cleanup";
	private static final String BLOCK_WHERE = "where";
	private static final String BLOCK_AND = "and";

	private SpockUtils() {
	}

    /** Fully qualified name of the Spock base class. */
    public static final String SPOCK_SPECIFICATION_CLASS = "spock.lang.Specification";

    /** All recognized Spock block labels. */
    public static final Set<String> SPOCK_BLOCK_LABELS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        BLOCK_GIVEN, BLOCK_SETUP, BLOCK_WHEN, BLOCK_THEN, BLOCK_EXPECT, BLOCK_CLEANUP, BLOCK_WHERE, BLOCK_AND)));

    /** Spock lifecycle method names. */
    public static final Set<String> SPOCK_LIFECYCLE_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        BLOCK_SETUP, BLOCK_CLEANUP, "setupSpec", "cleanupSpec")));

    /** Commonly used Spock annotations (fully qualified). */
    public static final Set<String> SPOCK_ANNOTATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "spock.lang.Unroll",
            "spock.lang.Shared",
            "spock.lang.Stepwise",
            "spock.lang.Timeout",
            "spock.lang.Ignore",
            "spock.lang.IgnoreRest",
            "spock.lang.IgnoreIf",
            "spock.lang.Requires",
            "spock.lang.Retry",
            "spock.lang.Title",
            "spock.lang.Narrative",
            "spock.lang.Issue",
            "spock.lang.See",
            "spock.lang.Subject",
            "spock.lang.AutoCleanup")));

    /**
     * Descriptions of each Spock block label for use in hover/documentation.
     */
    public static final Map<String, String> BLOCK_DESCRIPTIONS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(BLOCK_GIVEN, "**given:** (alias: **setup:**) — Sets up preconditions and test fixtures. "
                + "This is the first block in a feature method and prepares the environment for the stimulus.");
        map.put(BLOCK_SETUP, "**setup:** (alias: **given:**) — Sets up preconditions and test fixtures. "
                + "Equivalent to `given:` and can be used interchangeably.");
        map.put(BLOCK_WHEN, "**when:** — Performs the action or stimulus being tested. "
                + "Always paired with a `then:` block. Contains the code that triggers the behavior under test.");
        map.put(BLOCK_THEN, "**then:** — Asserts the expected outcome after the `when:` block. "
                + "Conditions in this block are implicitly treated as assertions (no `assert` keyword needed). "
                + "May also contain exception conditions (`thrown()`) and mock interactions.");
        map.put(BLOCK_EXPECT, "**expect:** — Combines stimulus and expected response in a single expression. "
                + "Useful when the stimulus and assertion can be expressed together. "
                + "Each expression is implicitly treated as an assertion.");
        map.put(BLOCK_CLEANUP, "**cleanup:** — Post-conditions and resource cleanup. "
                + "Always runs, even if a previous block threw an exception (similar to `finally`).");
        map.put(BLOCK_WHERE, "**where:** — Provides data for parameterized (data-driven) tests. "
                + "Must be the last block in a feature method.\n\n"
                + "Supports three data supply styles:\n"
                + "- **Data tables** — columns separated by `|`, inputs/outputs by `||`\n"
                + "- **Data pipes** — `variable << [values]` or any iterable\n"
                + "- **Multi-variable assignment** — `[a, b] << [[1,2], [3,4]]`\n\n"
                + "Variables declared in `where:` are available in **all** preceding blocks "
                + "(`given:`, `when:`, `then:`, `expect:`).\n\n"
                + "Use `@Unroll` on the method to get individual test names with `#variable` interpolation.\n\n"
                + "```groovy\n"
                + "where:\n"
                + "a | b || expected\n"
                + "1 | 2 || 3\n"
                + "4 | 5 || 9\n"
                + "```");
            map.put(BLOCK_AND, "**and:** — Subdivides any other block for readability. "
                + "Does not change the semantics of the enclosing block but improves documentation.");
        BLOCK_DESCRIPTIONS = Collections.unmodifiableMap(map);
    }

    /**
     * Allowed successor blocks for each Spock block label.
     * Used for validating block ordering and providing smart completions.
     */
    public static final Map<String, List<String>> ALLOWED_SUCCESSORS;

    static {
        Map<String, List<String>> map = new HashMap<>();
        map.put(BLOCK_GIVEN, Arrays.asList(BLOCK_WHEN, BLOCK_EXPECT, BLOCK_AND, BLOCK_CLEANUP, BLOCK_WHERE));
        map.put(BLOCK_SETUP, Arrays.asList(BLOCK_WHEN, BLOCK_EXPECT, BLOCK_AND, BLOCK_CLEANUP, BLOCK_WHERE));
        map.put(BLOCK_WHEN, Arrays.asList(BLOCK_THEN, BLOCK_AND));
        map.put(BLOCK_THEN, Arrays.asList(BLOCK_WHEN, BLOCK_THEN, BLOCK_EXPECT, BLOCK_CLEANUP, BLOCK_WHERE, BLOCK_AND));
        map.put(BLOCK_EXPECT, Arrays.asList(BLOCK_WHEN, BLOCK_THEN, BLOCK_CLEANUP, BLOCK_WHERE, BLOCK_AND));
        map.put(BLOCK_CLEANUP, Arrays.asList(BLOCK_WHERE));
        map.put(BLOCK_WHERE, Collections.emptyList());
        map.put(BLOCK_AND, Arrays.asList(BLOCK_WHEN, BLOCK_THEN, BLOCK_EXPECT, BLOCK_CLEANUP, BLOCK_WHERE, BLOCK_AND));
        ALLOWED_SUCCESSORS = Collections.unmodifiableMap(map);
    }

    /**
     * Checks whether a class node represents a Spock Specification
     * by walking up the superclass chain.
     *
     * @param classNode the class to check
     * @return true if the class extends spock.lang.Specification
     */
    public static boolean isSpockSpecification(ClassNode classNode) {
        if (classNode == null) {
            return false;
        }
        ClassNode current = classNode;
        Set<String> visited = new HashSet<>();
        while (current != null) {
            String name = current.getName();
            if (visited.contains(name)) {
                break; // avoid infinite loops on circular hierarchies
            }
            visited.add(name);
            if (SPOCK_SPECIFICATION_CLASS.equals(name)) {
                return true;
            }
            try {
                current = current.getSuperClass();
            } catch (NoClassDefFoundError e) {
                // The superclass may not be on the classpath — that's fine.
                // Also try unresolved superclass name as a fallback.
                ClassNode unresolved = classNode.getUnresolvedSuperClass();
                if (unresolved != null) {
                    return SPOCK_SPECIFICATION_CLASS.equals(unresolved.getName())
                            || "Specification".equals(unresolved.getNameWithoutPackage());
                }
                return false;
            }
        }
        // Fallback: check unresolved superclass name (handles cases where
        // Spock is not on the compile classpath but user imports it)
        ClassNode unresolved = classNode.getUnresolvedSuperClass();
        if (unresolved != null) {
            String unresolvedName = unresolved.getName();
            return SPOCK_SPECIFICATION_CLASS.equals(unresolvedName)
                    || "Specification".equals(unresolved.getNameWithoutPackage());
        }
        return false;
    }

    /**
     * Checks whether a method node is a Spock feature method.
     * A feature method is a method in a Specification that is not a lifecycle method
     * and not a helper method (i.e., it's a test).
     * <p>
     * In Spock, feature methods typically have descriptive string names like
     * "should calculate total price" but this is not required.
     *
     * @param methodNode the method to check
     * @return true if the method appears to be a Spock feature method
     */
    public static boolean isSpockFeatureMethod(MethodNode methodNode) {
        if (methodNode == null) {
            return false;
        }
        // Feature methods are public/package-private void methods
        if (methodNode.isStatic() || methodNode.isAbstract()) {
            return false;
        }
        String name = methodNode.getName();
        // Skip known lifecycle methods
        if (SPOCK_LIFECYCLE_METHODS.contains(name)) {
            return false;
        }
        // Skip constructors and special methods
        if (name.startsWith("$") || name.equals("<init>") || name.equals("<clinit>")) {
            return false;
        }
        // Check that the declaring class is a Spock specification
        ClassNode declaringClass = methodNode.getDeclaringClass();
        return isSpockSpecification(declaringClass);
    }

    /**
     * Checks whether the given label is a recognized Spock block label.
     *
     * @param label the label text (without the colon)
     * @return true if it's a Spock block label
     */
    public static boolean isSpockBlockLabel(String label) {
        return label != null && SPOCK_BLOCK_LABELS.contains(label);
    }

    /**
     * Checks whether a method name is a Spock lifecycle method.
     *
     * @param methodName the method name
     * @return true if it's a Spock lifecycle method
     */
    public static boolean isSpockLifecycleMethod(String methodName) {
        return methodName != null && SPOCK_LIFECYCLE_METHODS.contains(methodName);
    }

    /**
     * Returns the description for a Spock block label.
     *
     * @param label the block label (e.g. "given", "when")
     * @return a markdown description or null
     */
    public static String getBlockDescription(String label) {
        return BLOCK_DESCRIPTIONS.get(label);
    }

    /**
     * Returns the allowed successor block labels for the given block.
     *
     * @param currentBlock the current block label
     * @return list of allowed successors, never null
     */
    public static List<String> getAllowedSuccessors(String currentBlock) {
        List<String> successors = ALLOWED_SUCCESSORS.get(currentBlock);
        return successors != null ? successors : Collections.emptyList();
    }

    /**
     * Gets a rich description for a Spock feature method for hover display.
     *
     * @param methodNode the feature method
     * @return markdown string with Spock-specific information
     */
    public static String getFeatureMethodDescription(MethodNode methodNode) {
        if (!isSpockFeatureMethod(methodNode)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**Spock Feature Method**\n\n");
        sb.append("Test: `").append(methodNode.getName()).append("`\n\n");
        sb.append("Class: `").append(methodNode.getDeclaringClass().getNameWithoutPackage()).append("`");
        return sb.toString();
    }

    /**
     * Gets a rich description for a Spock specification class for hover display.
     *
     * @param classNode the specification class
     * @return markdown string with Spock-specific information, or null
     */
    public static String getSpecificationDescription(ClassNode classNode) {
        if (!isSpockSpecification(classNode)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**Spock Specification**\n\n");
        sb.append("This class extends `spock.lang.Specification` and can contain:\n");
        sb.append("- **Feature methods** — test methods with descriptive names\n");
        sb.append("- **Fixture methods** — `setup()`, `cleanup()`, `setupSpec()`, `cleanupSpec()`\n");
        sb.append("- **Fields** — use `@Shared` for fields shared across feature methods\n");
        sb.append("- **Helper methods** — reusable logic called from feature methods\n");
        return sb.toString();
    }

    /**
     * Gets the Spock lifecycle method description.
     *
     * @param methodName the lifecycle method name
     * @return markdown description, or null if not a lifecycle method
     */
    public static String getLifecycleMethodDescription(String methodName) {
        if (methodName == null) {
            return null;
        }
        switch (methodName) {
            case BLOCK_SETUP:
                return "**Spock Fixture:** `setup()` — Runs before **each** feature method. "
                        + "Use for per-test initialization.";
            case BLOCK_CLEANUP:
                return "**Spock Fixture:** `cleanup()` — Runs after **each** feature method. "
                        + "Use for per-test resource cleanup.";
            case "setupSpec":
                return "**Spock Fixture:** `setupSpec()` — Runs **once** before the first feature method. "
                        + "Use for expensive shared initialization. Only `@Shared` fields are accessible.";
            case "cleanupSpec":
                return "**Spock Fixture:** `cleanupSpec()` — Runs **once** after the last feature method. "
                        + "Use for shared resource cleanup. Only `@Shared` fields are accessible.";
            default:
                return null;
        }
    }
}
