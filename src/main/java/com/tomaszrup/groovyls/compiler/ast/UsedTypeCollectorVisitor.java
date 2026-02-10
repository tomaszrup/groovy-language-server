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
package com.tomaszrup.groovyls.compiler.ast;

import java.util.Set;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.control.SourceUnit;

/**
 * Visits method bodies and collects all type names that are referenced
 * in expressions and statements — e.g., constructor calls, casts,
 * class literals, variable declarations, catch clauses, etc.
 */
class UsedTypeCollectorVisitor extends ClassCodeVisitorSupport {

	private final Set<String> usedNames;

	UsedTypeCollectorVisitor(Set<String> usedNames) {
		this.usedNames = usedNames;
	}

	@Override
	protected SourceUnit getSourceUnit() {
		return null; // not needed for visiting
	}

	@Override
	public void visitConstructorCallExpression(ConstructorCallExpression expression) {
		addClassName(expression.getType());
		super.visitConstructorCallExpression(expression);
	}

	@Override
	public void visitCastExpression(CastExpression expression) {
		addClassName(expression.getType());
		super.visitCastExpression(expression);
	}

	@Override
	public void visitClassExpression(ClassExpression expression) {
		addClassName(expression.getType());
		super.visitClassExpression(expression);
	}

	@Override
	public void visitVariableExpression(VariableExpression expression) {
		if (expression.getAccessedVariable() != null) {
			addClassName(expression.getAccessedVariable().getOriginType());
		}
		addClassName(expression.getOriginType());
		// Also collect the variable name itself — needed for static field imports
		// (e.g., import static java.lang.Math.PI → variable "PI")
		usedNames.add(expression.getName());
		super.visitVariableExpression(expression);
	}

	@Override
	public void visitDeclarationExpression(DeclarationExpression expression) {
		if (expression.getLeftExpression() instanceof VariableExpression) {
			addClassName(((VariableExpression) expression.getLeftExpression()).getOriginType());
		}
		super.visitDeclarationExpression(expression);
	}

	@Override
	public void visitCatchStatement(CatchStatement statement) {
		addClassName(statement.getExceptionType());
		super.visitCatchStatement(statement);
	}

	@Override
	public void visitForLoop(ForStatement forLoop) {
		addClassName(forLoop.getVariableType());
		super.visitForLoop(forLoop);
	}

	@Override
	public void visitMethodCallExpression(MethodCallExpression call) {
		usedNames.add(call.getMethodAsString());
		super.visitMethodCallExpression(call);
	}

	@Override
	public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
		usedNames.add(call.getMethod());
		super.visitStaticMethodCallExpression(call);
	}

	@Override
	public void visitPropertyExpression(PropertyExpression expression) {
		usedNames.add(expression.getPropertyAsString());
		super.visitPropertyExpression(expression);
	}

	private void addClassName(ClassNode classNode) {
		if (classNode == null) {
			return;
		}
		String name = classNode.getNameWithoutPackage();
		// Handle inner classes: Outer.Inner -> Outer
		int dotIndex = name.indexOf('.');
		if (dotIndex > 0) {
			name = name.substring(0, dotIndex);
		}
		usedNames.add(name);
		addGenericsTypes(classNode);
	}

	private void addGenericsTypes(ClassNode classNode) {
		if (classNode == null || classNode.getGenericsTypes() == null) {
			return;
		}
		for (GenericsType gt : classNode.getGenericsTypes()) {
			if (gt.getType() != null) {
				addClassName(gt.getType());
			}
			if (gt.getUpperBounds() != null) {
				for (ClassNode bound : gt.getUpperBounds()) {
					addClassName(bound);
				}
			}
			if (gt.getLowerBound() != null) {
				addClassName(gt.getLowerBound());
			}
		}
	}
}
