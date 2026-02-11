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

import groovy.lang.GroovyClassLoader;
import io.github.classgraph.ScanResult;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;

/**
 * Immutable result of {@link CompilationOrchestrator#createOrUpdateCompilationUnit}.
 * Replaces the old array-holder pattern ({@code GroovyLSCompilationUnit[]},
 * {@code ScanResult[]}, {@code GroovyClassLoader[]}) with an explicit,
 * self-documenting data structure.
 *
 * <p>Callers inspect the result fields rather than reading back from
 * mutable arrays, making data flow explicit and the API less error-prone.</p>
 */
public final class CompilationResult {

    private final GroovyLSCompilationUnit compilationUnit;
    private final GroovyClassLoader classLoader;
    private final ScanResult scanResult;
    private final boolean sameUnit;

    public CompilationResult(GroovyLSCompilationUnit compilationUnit,
                             GroovyClassLoader classLoader,
                             ScanResult scanResult,
                             boolean sameUnit) {
        this.compilationUnit = compilationUnit;
        this.classLoader = classLoader;
        this.scanResult = scanResult;
        this.sameUnit = sameUnit;
    }

    /**
     * The new or reused compilation unit, or {@code null} if creation failed.
     */
    public GroovyLSCompilationUnit getCompilationUnit() {
        return compilationUnit;
    }

    /**
     * The classloader associated with the compilation unit.
     */
    public GroovyClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * The ClassGraph scan result, or {@code null} if it was released
     * (e.g. compilation unit creation failed).
     */
    public ScanResult getScanResult() {
        return scanResult;
    }

    /**
     * Whether the compilation unit is the same object as before
     * (i.e. it was reused rather than recreated).
     */
    public boolean isSameUnit() {
        return sameUnit;
    }
}
