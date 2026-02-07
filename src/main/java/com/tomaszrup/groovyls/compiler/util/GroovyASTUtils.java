////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
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
// Author: Tomasz Rup (originally Prominic.NET, Inc.)
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.compiler.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;

public class GroovyASTUtils {
    public static ASTNode getEnclosingNodeOfType(ASTNode offsetNode, Class<? extends ASTNode> nodeType,
            ASTNodeVisitor astVisitor) {
        ASTNode current = offsetNode;
        while (current != null) {
            if (nodeType.isInstance(current)) {
                return current;
            }
            current = astVisitor.getParent(current);
        }
        return null;
    }

    public static ASTNode getDefinition(ASTNode node, boolean strict, ASTNodeVisitor astVisitor) {
        if (node == null) {
            return null;
        }
        ASTNode parentNode = astVisitor.getParent(node);
        if (node instanceof ExpressionStatement) {
            ExpressionStatement statement = (ExpressionStatement) node;
            node = statement.getExpression();
        }
        if (node instanceof ClassNode) {
            return tryToResolveOriginalClassNode((ClassNode) node, strict, astVisitor);
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression callExpression = (ConstructorCallExpression) node;
            return GroovyASTUtils.getMethodFromCallExpression(callExpression, astVisitor);
        } else if (node instanceof DeclarationExpression) {
            DeclarationExpression declExpression = (DeclarationExpression) node;
            if (!declExpression.isMultipleAssignmentDeclaration()) {
                ClassNode originType = declExpression.getVariableExpression().getOriginType();
                return tryToResolveOriginalClassNode(originType, strict, astVisitor);
            }
        } else if (node instanceof ClassExpression) {
            ClassExpression classExpression = (ClassExpression) node;
            return tryToResolveOriginalClassNode(classExpression.getType(), strict, astVisitor);
        } else if (node instanceof ImportNode) {
            ImportNode importNode = (ImportNode) node;
            return tryToResolveOriginalClassNode(importNode.getType(), strict, astVisitor);
        } else if (node instanceof MethodNode) {
            return node;
        } else if (node instanceof ConstantExpression && parentNode != null) {
            if (parentNode instanceof MethodCallExpression) {
                MethodCallExpression methodCallExpression = (MethodCallExpression) parentNode;
                return GroovyASTUtils.getMethodFromCallExpression(methodCallExpression, astVisitor);
            } else if (parentNode instanceof PropertyExpression) {
                PropertyExpression propertyExpression = (PropertyExpression) parentNode;
                PropertyNode propNode = GroovyASTUtils.getPropertyFromExpression(propertyExpression, astVisitor);
                if (propNode != null) {
                    return propNode;
                }
                return GroovyASTUtils.getFieldFromExpression(propertyExpression, astVisitor);
            }
        } else if (node instanceof VariableExpression) {
            VariableExpression variableExpression = (VariableExpression) node;
            Variable accessedVariable = variableExpression.getAccessedVariable();
            if (accessedVariable instanceof ASTNode) {
                return (ASTNode) accessedVariable;
            }
            // DynamicVariable is not an ASTNode, so skip it
            return null;
        } else if (node instanceof Variable) {
            return node;
        }
        return null;
    }

    public static ASTNode getTypeDefinition(ASTNode node, ASTNodeVisitor astVisitor) {
        ASTNode definitionNode = getDefinition(node, false, astVisitor);
        if (definitionNode == null) {
            return null;
        }
        if (definitionNode instanceof MethodNode) {
            MethodNode method = (MethodNode) definitionNode;
            return tryToResolveOriginalClassNode(method.getReturnType(), true, astVisitor);
        } else if (definitionNode instanceof Variable) {
            Variable variable = (Variable) definitionNode;
            return tryToResolveOriginalClassNode(variable.getOriginType(), true, astVisitor);
        }
        return null;
    }

    public static List<ASTNode> getReferences(ASTNode node, ASTNodeVisitor ast) {
        ASTNode definitionNode = getDefinition(node, true, ast);
        if (definitionNode == null) {
            return Collections.emptyList();
        }
        return ast.getNodes().stream().filter(otherNode -> {
            ASTNode otherDefinition = getDefinition(otherNode, false, ast);
            return definitionNode.equals(otherDefinition) && node.getLineNumber() != -1 && node.getColumnNumber() != -1;
        }).collect(Collectors.toList());
    }

    private static ClassNode tryToResolveOriginalClassNode(ClassNode node, boolean strict, ASTNodeVisitor ast) {
        for (ClassNode originalNode : ast.getClassNodes()) {
            if (originalNode.equals(node)) {
                return originalNode;
            }
        }
        if (strict) {
            return null;
        }
        return node;
    }

    public static PropertyNode getPropertyFromExpression(PropertyExpression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node.getObjectExpression(), astVisitor);
        if (classNode != null) {
            return classNode.getProperty(node.getProperty().getText());
        }
        return null;
    }

    public static FieldNode getFieldFromExpression(PropertyExpression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node.getObjectExpression(), astVisitor);
        if (classNode != null) {
            return classNode.getField(node.getProperty().getText());
        }
        return null;
    }

    public static List<FieldNode> getFieldsForLeftSideOfPropertyExpression(Expression node, ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node, astVisitor);
        if (classNode != null) {
            List<ClassNode> classNodes = new ArrayList<>();
            classNodes.add(classNode);

            boolean statics = node instanceof ClassExpression;

            List<FieldNode> result = new ArrayList<>();
            int i = 0;
            while (i < classNodes.size()) {
                ClassNode current = classNodes.get(i);

                result.addAll(current.getFields().stream().filter(fieldNode -> {
                    return statics ? fieldNode.isStatic() : !fieldNode.isStatic();
                }).collect(Collectors.toList()));

                if (current.isInterface()) {
                    for (ClassNode interfaceNode : current.getInterfaces()) {
                        classNodes.add(interfaceNode);
                    }
                } else {
                    ClassNode superClassNode = null;
                    try {
                        superClassNode = current.getSuperClass();
                    } catch (NoClassDefFoundError e) {
                        // this is fine, we'll just treat it as null
                    }
                    if (superClassNode != null) {
                        classNodes.add(superClassNode);
                    }
                    for (ClassNode interfaceNode : current.getInterfaces()) {
                        classNodes.add(interfaceNode);
                    }
                }
                i++;
            }
            return result;
        }
        return Collections.emptyList();
    }

    public static List<PropertyNode> getPropertiesForLeftSideOfPropertyExpression(Expression node,
            ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node, astVisitor);
        if (classNode != null) {
            List<ClassNode> classNodes = new ArrayList<>();
            classNodes.add(classNode);

            boolean statics = node instanceof ClassExpression;

            List<PropertyNode> result = new ArrayList<>();
            int i = 0;
            while (i < classNodes.size()) {
                ClassNode current = classNodes.get(i);

                result.addAll(current.getProperties().stream().filter(propNode -> {
                    return statics ? propNode.isStatic() : !propNode.isStatic();
                }).collect(Collectors.toList()));

                if (current.isInterface()) {
                    for (ClassNode interfaceNode : current.getInterfaces()) {
                        classNodes.add(interfaceNode);
                    }
                } else {
                    ClassNode superClassNode = null;
                    try {
                        superClassNode = current.getSuperClass();
                    } catch (NoClassDefFoundError e) {
                        // this is fine, we'll just treat it as null
                    }
                    if (superClassNode != null) {
                        classNodes.add(superClassNode);
                    }
                    for (ClassNode interfaceNode : current.getInterfaces()) {
                        classNodes.add(interfaceNode);
                    }
                }
                i++;
            }
        }
        return Collections.emptyList();
    }

    public static List<MethodNode> getMethodsForLeftSideOfPropertyExpression(Expression node,
            ASTNodeVisitor astVisitor) {
        ClassNode classNode = getTypeOfNode(node, astVisitor);
        if (classNode != null) {
            List<ClassNode> classNodes = new ArrayList<>();
            classNodes.add(classNode);

            boolean statics = node instanceof ClassExpression;

            List<MethodNode> result = new ArrayList<>();
            int i = 0;
            while (i < classNodes.size()) {
                ClassNode current = classNodes.get(i);

                result.addAll(current.getMethods().stream().filter(methodNode -> {
                    return statics ? methodNode.isStatic() : !methodNode.isStatic();
                }).collect(Collectors.toList()));

                if (current.isInterface()) {
                    for (ClassNode interfaceNode : current.getInterfaces()) {
                        classNodes.add(interfaceNode);
                    }
                } else {
                    ClassNode superClassNode = null;
                    try {
                        superClassNode = current.getSuperClass();
                    } catch (NoClassDefFoundError e) {
                        // this is fine, we'll just treat it as null
                    }
                    if (superClassNode != null) {
                        classNodes.add(superClassNode);
                    }
                    for (ClassNode interfaceNode : current.getInterfaces()) {
                        classNodes.add(interfaceNode);
                    }
                }
                i++;
            }
            return result;
        }
        return Collections.emptyList();
    }

    public static ClassNode getTypeOfNode(ASTNode node, ASTNodeVisitor astVisitor) {
        if (node instanceof BinaryExpression) {
            BinaryExpression binaryExpr = (BinaryExpression) node;
            Expression leftExpr = binaryExpr.getLeftExpression();
            if (binaryExpr.getOperation().getText().equals("[") && leftExpr.getType().isArray()) {
                return leftExpr.getType().getComponentType();
            }
        } else if (node instanceof ClassExpression) {
            ClassExpression expression = (ClassExpression) node;
            // This means it's an expression like this: SomeClass.someProp
            return expression.getType();
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression expression = (ConstructorCallExpression) node;
            return expression.getType();
        } else if (node instanceof MethodCallExpression) {
            MethodCallExpression expression = (MethodCallExpression) node;
            MethodNode methodNode = GroovyASTUtils.getMethodFromCallExpression(expression, astVisitor);
            if (methodNode != null) {
                return methodNode.getReturnType();
            }
            return expression.getType();
        } else if (node instanceof PropertyExpression) {
            PropertyExpression expression = (PropertyExpression) node;
            PropertyNode propNode = GroovyASTUtils.getPropertyFromExpression(expression, astVisitor);
            if (propNode != null) {
                return getTypeOfNode(propNode, astVisitor);
            }
            FieldNode fieldNode = GroovyASTUtils.getFieldFromExpression(expression, astVisitor);
            if (fieldNode != null) {
                return getTypeOfNode(fieldNode, astVisitor);
            }
            return expression.getType();
        } else if (node instanceof Variable) {
            Variable var = (Variable) node;
            if (var.getName().equals("this")) {
                ClassNode enclosingClass = (ClassNode) getEnclosingNodeOfType(node, ClassNode.class, astVisitor);
                if (enclosingClass != null) {
                    return enclosingClass;
                }
            } else if (var.isDynamicTyped()) {
                ASTNode defNode = GroovyASTUtils.getDefinition(node, false, astVisitor);
                if (defNode instanceof Variable) {
                    Variable defVar = (Variable) defNode;
                    if (defVar.hasInitialExpression()) {
                        return getTypeOfNode(defVar.getInitialExpression(), astVisitor);
                    } else {
                        ASTNode declNode = astVisitor.getParent(defNode);
                        if (declNode instanceof DeclarationExpression) {
                            DeclarationExpression decl = (DeclarationExpression) declNode;
                            return getTypeOfNode(decl.getRightExpression(), astVisitor);
                        }
                    }
                }
            }
            if (var.getOriginType() != null) {
                return var.getOriginType();
            }
        }
        if (node instanceof Expression) {
            Expression expression = (Expression) node;
            return expression.getType();
        }
        return null;
    }

    public static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor) {
        if (node instanceof MethodCallExpression) {
            MethodCallExpression methodCallExpr = (MethodCallExpression) node;
            ClassNode leftType = getTypeOfNode(methodCallExpr.getObjectExpression(), astVisitor);
            if (leftType != null) {
                String methodName = methodCallExpr.getMethod().getText();
                List<MethodNode> methods = leftType.getMethods(methodName);
                if (!methods.isEmpty()) {
                    return methods;
                }
                // Direct lookup failed — walk the type hierarchy (traits/interfaces/superclasses)
                // to find the original method declarations. This is needed because at the
                // CANONICALIZATION phase, trait methods may not yet be mixed into the
                // implementing class.
                methods = getMethodsFromTypeHierarchy(leftType, methodName, astVisitor);
                if (!methods.isEmpty()) {
                    return methods;
                }
            }
        } else if (node instanceof ConstructorCallExpression) {
            ConstructorCallExpression constructorCallExpr = (ConstructorCallExpression) node;
            ClassNode constructorType = constructorCallExpr.getType();
            if (constructorType != null) {
                return constructorType.getDeclaredConstructors().stream().map(constructor -> (MethodNode) constructor)
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor) {
        return getMethodFromCallExpression(node, astVisitor, -1);
    }

    public static MethodNode getMethodFromCallExpression(MethodCall node, ASTNodeVisitor astVisitor, int argIndex) {
        List<MethodNode> possibleMethods = getMethodOverloadsFromCallExpression(node, astVisitor);
        if (!possibleMethods.isEmpty() && node.getArguments() instanceof ArgumentListExpression) {
            ArgumentListExpression actualArguments = (ArgumentListExpression) node.getArguments();
            MethodNode foundMethod = possibleMethods.stream().max(new Comparator<MethodNode>() {
                public int compare(MethodNode m1, MethodNode m2) {
                    Parameter[] p1 = m1.getParameters();
                    Parameter[] p2 = m2.getParameters();
                    int m1Value = calculateArgumentsScore(p1, actualArguments, argIndex);
                    int m2Value = calculateArgumentsScore(p2, actualArguments, argIndex);
                    if (m1Value > m2Value) {
                        return 1;
                    } else if (m1Value < m2Value) {
                        return -1;
                    }
                    return 0;
                }
            }).orElse(null);
            // If the resolved method has no source location (e.g., synthetic trait bridge
            // method), try to find the original method declaration in a trait or superclass.
            if (foundMethod != null && foundMethod.getLineNumber() == -1) {
                MethodNode original = resolveOriginalMethod(foundMethod, astVisitor);
                if (original != null) {
                    return original;
                }
            }
            return foundMethod;
        }
        return null;
    }

    private static int calculateArgumentsScore(Parameter[] parameters, ArgumentListExpression arguments, int argIndex) {
        int score = 0;
        int paramCount = parameters.length;
        int expressionsCount = arguments.getExpressions().size();
        int argsCount = expressionsCount;
        if (argIndex >= argsCount) {
            argsCount = argIndex + 1;
        }
        int minCount = Math.min(paramCount, argsCount);
        if (minCount == 0 && paramCount == argsCount) {
            score++;
        }
        for (int i = 0; i < minCount; i++) {
            ClassNode argType = (i < expressionsCount) ? arguments.getExpression(i).getType() : null;
            ClassNode paramType = (i < paramCount) ? parameters[i].getType() : null;
            if (argType != null && paramType != null) {
                if (argType.equals(paramType)) {
                    // equal types are preferred
                    score += 1000;
                } else if (argType.isDerivedFrom(paramType)) {
                    // subtypes are nice, but less important
                    score += 100;
                } else {
                    // if a type doesn't match at all, it's not worth much
                    score++;
                }
            } else if (paramType != null) {
                // extra parameters are like a type not matching
                score++;
            }
        }
        return score;
    }

    public static Range findAddImportRange(ASTNode offsetNode, ASTNodeVisitor astVisitor) {
        ModuleNode moduleNode = (ModuleNode) GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ModuleNode.class,
                astVisitor);
        if (moduleNode == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        ASTNode afterNode = null;
        if (afterNode == null) {
            List<ImportNode> importNodes = moduleNode.getImports();
            if (importNodes.size() > 0) {
                afterNode = importNodes.get(importNodes.size() - 1);
            }
        }
        if (afterNode == null) {
            afterNode = moduleNode.getPackage();
        }
        if (afterNode == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        Range nodeRange = GroovyLanguageServerUtils.astNodeToRange(afterNode);
        if (nodeRange == null) {
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        Position position = new Position(nodeRange.getEnd().getLine() + 1, 0);
        return new Range(position, position);
    }

    /**
     * Walks the type hierarchy (interfaces/traits and superclasses) of the given class
     * to find methods with the specified name. Resolves each ancestor to its original
     * ClassNode in the AST when possible, so that returned MethodNodes have source
     * locations pointing to the actual trait/class source.
     */
    private static List<MethodNode> getMethodsFromTypeHierarchy(ClassNode classNode, String methodName,
            ASTNodeVisitor astVisitor) {
        List<MethodNode> result = new ArrayList<>();
        List<ClassNode> toVisit = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(classNode.getName());

        // Seed with direct interfaces (which include traits) and superclass
        for (ClassNode iface : classNode.getInterfaces()) {
            toVisit.add(iface);
        }
        try {
            ClassNode superClass = classNode.getSuperClass();
            if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                toVisit.add(superClass);
            }
        } catch (NoClassDefFoundError e) {
            // ignore
        }

        int i = 0;
        while (i < toVisit.size()) {
            ClassNode current = toVisit.get(i);
            i++;
            if (!visited.add(current.getName())) {
                continue;
            }

            // Try to resolve to the original ClassNode in the AST (has source locations)
            ClassNode resolved = tryToResolveOriginalClassNode(current, false, astVisitor);
            List<MethodNode> methods = resolved.getDeclaredMethods(methodName);
            for (MethodNode method : methods) {
                if (method.getLineNumber() != -1) {
                    result.add(method);
                }
            }

            // Continue walking up
            for (ClassNode iface : resolved.getInterfaces()) {
                toVisit.add(iface);
            }
            try {
                ClassNode superClass = resolved.getSuperClass();
                if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                    toVisit.add(superClass);
                }
            } catch (NoClassDefFoundError e) {
                // ignore
            }
        }
        return result;
    }

    /**
     * Given a MethodNode that has no source location (e.g., a synthetic trait bridge
     * method or an inherited method stub), attempts to find the original method
     * declaration in a trait or superclass within the AST.
     */
    public static MethodNode resolveOriginalMethod(MethodNode method, ASTNodeVisitor astVisitor) {
        if (method == null || method.getLineNumber() != -1) {
            return method;
        }
        String methodName = method.getName();
        Parameter[] params = method.getParameters();
        ClassNode declaringClass = method.getDeclaringClass();
        if (declaringClass == null) {
            return null;
        }

        // Search through all class nodes in the AST for a matching method
        // with a source location, that is in the type hierarchy of the declaring class
        List<ClassNode> toVisit = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(declaringClass.getName());

        for (ClassNode iface : declaringClass.getInterfaces()) {
            toVisit.add(iface);
        }
        try {
            ClassNode superClass = declaringClass.getSuperClass();
            if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                toVisit.add(superClass);
            }
        } catch (NoClassDefFoundError e) {
            // ignore
        }

        int i = 0;
        while (i < toVisit.size()) {
            ClassNode current = toVisit.get(i);
            i++;
            if (!visited.add(current.getName())) {
                continue;
            }

            ClassNode resolved = tryToResolveOriginalClassNode(current, false, astVisitor);
            for (MethodNode candidate : resolved.getDeclaredMethods(methodName)) {
                if (candidate.getLineNumber() != -1 && parametersMatch(candidate.getParameters(), params)) {
                    return candidate;
                }
            }

            for (ClassNode iface : resolved.getInterfaces()) {
                toVisit.add(iface);
            }
            try {
                ClassNode superClass = resolved.getSuperClass();
                if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                    toVisit.add(superClass);
                }
            } catch (NoClassDefFoundError e) {
                // ignore
            }
        }
        return null;
    }

    private static boolean parametersMatch(Parameter[] a, Parameter[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].getType().getName().equals(b[i].getType().getName())) {
                return false;
            }
        }
        return true;
    }
}