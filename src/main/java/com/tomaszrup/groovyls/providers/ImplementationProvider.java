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
package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.GroovyLanguageServerUtils;

public class ImplementationProvider {
    private ASTNodeVisitor ast;

    public ImplementationProvider(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> provideImplementation(
            TextDocumentIdentifier textDocument, Position position) {
        if (ast == null) {
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        }
        URI uri = URI.create(textDocument.getUri());
        ASTNode offsetNode = ast.getNodeAtLineAndColumn(uri, position.getLine(), position.getCharacter());
        if (offsetNode == null) {
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        }

        ASTNode definitionNode = GroovyASTUtils.getDefinition(offsetNode, false, ast);
        if (definitionNode == null) {
            return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
        }

        List<Location> locations = new ArrayList<>();

        if (definitionNode instanceof ClassNode) {
            ClassNode targetClass = (ClassNode) definitionNode;
            locations.addAll(findClassImplementations(targetClass));
        } else if (definitionNode instanceof MethodNode) {
            MethodNode targetMethod = (MethodNode) definitionNode;
            ClassNode declaringClass = targetMethod.getDeclaringClass();
            if (declaringClass != null) {
                locations.addAll(findMethodImplementations(targetMethod, declaringClass));
            }
        }

        return CompletableFuture.completedFuture(Either.forLeft(locations));
    }

    private List<Location> findClassImplementations(ClassNode targetClass) {
        List<Location> locations = new ArrayList<>();
        for (ClassNode classNode : ast.getClassNodes()) {
            if (classNode.equals(targetClass)) {
                continue;
            }
            if (isImplementationOf(classNode, targetClass)) {
                URI implURI = ast.getURI(classNode);
                if (implURI != null) {
                    Location location = GroovyLanguageServerUtils.astNodeToLocation(classNode, implURI);
                    if (location != null) {
                        locations.add(location);
                    }
                }
            }
        }
        return locations;
    }

    private List<Location> findMethodImplementations(MethodNode targetMethod, ClassNode declaringClass) {
        List<Location> locations = new ArrayList<>();
        String methodName = targetMethod.getName();

        for (ClassNode classNode : ast.getClassNodes()) {
            if (classNode.equals(declaringClass)) {
                continue;
            }
            if (!isImplementationOf(classNode, declaringClass)) {
                continue;
            }
            List<MethodNode> methods = classNode.getMethods(methodName);
            for (MethodNode method : methods) {
                if (method.getParameters().length == targetMethod.getParameters().length) {
                    URI methodURI = ast.getURI(method);
                    if (methodURI != null && method.getLineNumber() != -1) {
                        Location location = GroovyLanguageServerUtils.astNodeToLocation(method, methodURI);
                        if (location != null) {
                            locations.add(location);
                        }
                    }
                }
            }
        }
        return locations;
    }

    private boolean isImplementationOf(ClassNode candidate, ClassNode target) {
        // Check direct interfaces
        ClassNode[] interfaces = candidate.getInterfaces();
        if (interfaces != null) {
            for (ClassNode iface : interfaces) {
                if (iface.equals(target) || iface.getName().equals(target.getName())) {
                    return true;
                }
            }
        }
        // Check superclass hierarchy
        ClassNode superClass = candidate.getSuperClass();
        if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
            if (superClass.equals(target) || superClass.getName().equals(target.getName())) {
                return true;
            }
            // Check if superclass transitively implements the target
            if (isImplementationOf(superClass, target)) {
                return true;
            }
        }
        // Check transitive interfaces
        if (interfaces != null) {
            for (ClassNode iface : interfaces) {
                if (isImplementationOf(iface, target)) {
                    return true;
                }
            }
        }
        return false;
    }
}
