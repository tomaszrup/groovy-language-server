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

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a synthetic Java source representation from a Groovy {@link ClassNode}
 * loaded from bytecode. This is used as a last-resort fallback when no source or
 * source JAR is available, so that "Go to Definition" can at least show the class
 * structure (fields, methods, constructors) in a read-only view.
 *
 * <p>The generated source is <b>not</b> compilable — it shows declarations without
 * method bodies (using {@code { ... }} or {@code { throw new
 * UnsupportedOperationException(); }}).</p>
 */
public class ClassNodeDecompiler {

    /**
     * Generate a synthetic Java source for the given ClassNode.
     *
     * @param classNode the class to decompile
     * @return list of source lines
     */
    public static List<String> decompile(ClassNode classNode) {
        List<String> lines = new ArrayList<>();

        // Package declaration
        String packageName = classNode.getPackageName();
        if (packageName != null && !packageName.isEmpty()) {
            lines.add("package " + packageName + ";");
            lines.add("");
        }

        // File header comment
        lines.add("// Decompiled from bytecode — source not available");
        lines.add("");

        // Class declaration
        StringBuilder classDecl = new StringBuilder();
        classDecl.append(Modifier.toString(classNode.getModifiers() & ~Modifier.SYNCHRONIZED));
        if (classDecl.length() > 0) {
            classDecl.append(' ');
        }
        if (classNode.isInterface()) {
            classDecl.append("interface ");
        } else if (classNode.isEnum()) {
            classDecl.append("enum ");
        } else {
            classDecl.append("class ");
        }
        classDecl.append(classNode.getNameWithoutPackage());

        // Superclass
        ClassNode superClass = classNode.getSuperClass();
        if (superClass != null && !superClass.getName().equals("java.lang.Object")
                && !classNode.isInterface() && !classNode.isEnum()) {
            classDecl.append(" extends ").append(simpleTypeName(superClass));
        }

        // Interfaces
        ClassNode[] interfaces = classNode.getInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            classDecl.append(classNode.isInterface() ? " extends " : " implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) classDecl.append(", ");
                classDecl.append(simpleTypeName(interfaces[i]));
            }
        }

        classDecl.append(" {");
        lines.add(classDecl.toString());
        lines.add("");

        // Fields
        List<FieldNode> fields = classNode.getFields();
        if (fields != null) {
            for (FieldNode field : fields) {
                if (field.isSynthetic()) continue;
                String fieldLine = "    " + fieldToString(field) + ";";
                lines.add(fieldLine);
            }
            if (!fields.isEmpty()) {
                lines.add("");
            }
        }

        // Constructors
        List<ConstructorNode> constructors = classNode.getDeclaredConstructors();
        if (constructors != null) {
            for (ConstructorNode ctor : constructors) {
                if (ctor.isSynthetic()) continue;
                lines.add("    " + constructorToString(ctor, classNode) + " { }");
                lines.add("");
            }
        }

        // Methods
        List<MethodNode> methods = classNode.getMethods();
        if (methods != null) {
            for (MethodNode method : methods) {
                if (method.isSynthetic()) continue;
                if ("<init>".equals(method.getName()) || "<clinit>".equals(method.getName())) continue;
                lines.add("    " + methodToString(method) + " { }");
                lines.add("");
            }
        }

        lines.add("}");
        return lines;
    }

    /**
     * Get the line number (0-indexed) of the class declaration in the
     * decompiled output.
     */
    public static int getClassDeclarationLine(ClassNode classNode) {
        int line = 0;
        String packageName = classNode.getPackageName();
        if (packageName != null && !packageName.isEmpty()) {
            line += 2; // package + blank
        }
        line += 2; // comment + blank
        return line;
    }

    /**
     * Get the line number (0-indexed) of a method declaration in the
     * decompiled output.
     */
    public static int getMethodLine(ClassNode classNode, String methodName, int paramCount) {
        List<String> lines = decompile(classNode);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.contains(" " + methodName + "(") || line.startsWith(methodName + "(")) {
                if (paramCount < 0 || matchesParamCount(line, paramCount)) {
                    return i;
                }
            }
        }
        return getClassDeclarationLine(classNode);
    }

    /**
     * Get the line number (0-indexed) of a field declaration in the
     * decompiled output.
     */
    public static int getFieldLine(ClassNode classNode, String fieldName) {
        List<String> lines = decompile(classNode);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.contains(" " + fieldName + ";") || line.contains(" " + fieldName + " =")) {
                return i;
            }
        }
        return getClassDeclarationLine(classNode);
    }

    private static boolean matchesParamCount(String line, int expected) {
        int openParen = line.indexOf('(');
        int closeParen = line.lastIndexOf(')');
        if (openParen == -1 || closeParen == -1 || closeParen <= openParen) {
            return false;
        }
        String params = line.substring(openParen + 1, closeParen).trim();
        if (params.isEmpty()) {
            return expected == 0;
        }
        int count = 1;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count == expected;
    }

    private static String fieldToString(FieldNode field) {
        StringBuilder sb = new StringBuilder();
        int mods = field.getModifiers();
        String modStr = Modifier.toString(mods);
        if (!modStr.isEmpty()) {
            sb.append(modStr).append(' ');
        }
        sb.append(simpleTypeName(field.getType()));
        sb.append(' ').append(field.getName());
        return sb.toString();
    }

    private static String constructorToString(ConstructorNode ctor, ClassNode classNode) {
        StringBuilder sb = new StringBuilder();
        String modStr = Modifier.toString(ctor.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE));
        if (!modStr.isEmpty()) {
            sb.append(modStr).append(' ');
        }
        sb.append(classNode.getNameWithoutPackage());
        sb.append('(');
        appendParameters(sb, ctor.getParameters());
        sb.append(')');
        return sb.toString();
    }

    private static String methodToString(MethodNode method) {
        StringBuilder sb = new StringBuilder();
        String modStr = Modifier.toString(method.getModifiers()
                & ~Modifier.SYNCHRONIZED & ~Modifier.NATIVE
                & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE
                   | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT));
        if (!modStr.isEmpty()) {
            sb.append(modStr).append(' ');
        }
        sb.append(simpleTypeName(method.getReturnType()));
        sb.append(' ').append(method.getName());
        sb.append('(');
        appendParameters(sb, method.getParameters());
        sb.append(')');
        return sb.toString();
    }

    private static void appendParameters(StringBuilder sb, Parameter[] params) {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleTypeName(params[i].getType()));
                sb.append(' ').append(params[i].getName());
            }
        }
    }

    private static String simpleTypeName(ClassNode type) {
        if (type == null) return "Object";
        String name = type.getName();
        // Use simple name for common JDK types
        if (name.startsWith("java.lang.")) {
            return name.substring("java.lang.".length());
        }
        return type.getNameWithoutPackage();
    }
}
