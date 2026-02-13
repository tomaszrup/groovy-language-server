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
package com.tomaszrup.groovyls.compiler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Lightweight, immutable classpath symbol index used by completion and
 * unresolved-import code actions.
 *
 * <p>Compared to retaining a full ClassGraph {@link ScanResult}, this index
 * stores only data needed by language features, reducing live heap usage.
 */
public final class ClasspathSymbolIndex {

	public enum SymbolKind {
		CLASS,
		INTERFACE,
		ENUM,
		ANNOTATION
	}

	public static final class Symbol {
		private final String name;
		private final String simpleName;
		private final String packageName;
		private final SymbolKind kind;
		/**
		 * Canonical classpath element path or {@code null} for JDK module classes.
		 */
		private final String classpathElementPath;

		Symbol(String name, String simpleName, String packageName,
				SymbolKind kind, String classpathElementPath) {
			this.name = name;
			this.simpleName = simpleName;
			this.packageName = packageName;
			this.kind = kind;
			this.classpathElementPath = classpathElementPath;
		}

		public String getName() {
			return name;
		}

		public String getSimpleName() {
			return simpleName;
		}

		public String getPackageName() {
			return packageName;
		}

		public SymbolKind getKind() {
			return kind;
		}

		public String getClasspathElementPath() {
			return classpathElementPath;
		}
	}

	private final List<Symbol> allSymbols;
	private final Set<String> packageNames;

	private ClasspathSymbolIndex(List<Symbol> allSymbols, Set<String> packageNames) {
		this.allSymbols = allSymbols;
		this.packageNames = packageNames;
	}

	public static ClasspathSymbolIndex fromScanResult(ScanResult scanResult) {
		if (scanResult == null) {
			return empty();
		}
		List<ClassInfo> classes = scanResult.getAllClasses();
		List<Symbol> symbols = new ArrayList<>(classes.size());
		Set<String> packages = new LinkedHashSet<>();
		for (ClassInfo classInfo : classes) {
			String packageName = classInfo.getPackageName();
			if (packageName != null && !packageName.isEmpty()) {
				packages.add(packageName);
			}
			File classpathElementFile = null;
			try {
				classpathElementFile = classInfo.getClasspathElementFile();
			} catch (IllegalArgumentException ignored) {
				// Some ClassGraph entries (e.g. synthetic/module edge cases) may
				// not expose a classpath element. Treat as module/JDK-style entry.
			}
			symbols.add(new Symbol(
					classInfo.getName(),
					classInfo.getSimpleName(),
					packageName,
					toKind(classInfo),
					toCanonicalClasspathElementPath(classpathElementFile)));
		}
		return new ClasspathSymbolIndex(
				Collections.unmodifiableList(symbols),
				Collections.unmodifiableSet(packages));
	}

	public static ClasspathSymbolIndex empty() {
		return new ClasspathSymbolIndex(Collections.emptyList(), Collections.emptySet());
	}

	public List<Symbol> getAllSymbols() {
		return allSymbols;
	}

	public Set<String> getPackageNames() {
		return packageNames;
	}

	/**
	 * Returns symbols filtered to the given classpath element set.
	 *
	 * @param classpathElementPaths canonical classpath element paths belonging
	 *                              to a scope; {@code null} means no filtering
	 */
	public List<Symbol> getSymbols(Set<String> classpathElementPaths) {
		if (classpathElementPaths == null || classpathElementPaths.isEmpty()) {
			return allSymbols;
		}
		List<Symbol> filtered = new ArrayList<>(allSymbols.size());
		for (Symbol symbol : allSymbols) {
			String cpElement = symbol.classpathElementPath;
			if (cpElement == null || classpathElementPaths.contains(cpElement)) {
				filtered.add(symbol);
			}
		}
		return filtered;
	}

	private static SymbolKind toKind(ClassInfo classInfo) {
		if (classInfo.isAnnotation()) {
			return SymbolKind.ANNOTATION;
		}
		if (classInfo.isInterface()) {
			return SymbolKind.INTERFACE;
		}
		if (classInfo.isEnum()) {
			return SymbolKind.ENUM;
		}
		return SymbolKind.CLASS;
	}

	private static String toCanonicalClasspathElementPath(File file) {
		if (file == null) {
			return null;
		}
		try {
			return file.getCanonicalFile().getPath();
		} catch (IOException e) {
			return file.getAbsolutePath();
		}
	}
}
