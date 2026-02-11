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

import com.tomaszrup.groovyls.ProjectScope;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import io.github.classgraph.ScanResult;
import org.codehaus.groovy.control.SourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyClassLoader;

import java.net.URI;
import java.util.*;

/**
 * Opt-in memory profiler that estimates per-project RAM usage and reports
 * the top 3 memory-consuming projects with their top 3 internal components.
 *
 * <p><b>Disabled by default</b> to avoid any overhead for regular users.
 * Enable via system property: {@code -Dgroovyls.debug.memoryProfile=true}</p>
 *
 * <p>When enabled, this profiler is called at every existing memory-logging
 * trigger point (after OOM, after classpath backfill, during eviction sweeps)
 * and produces output like:</p>
 * <pre>
 * === Memory Profile: Top 3 projects by RAM ===
 *   1. /workspace/project-alpha (312.4 MB)
 *        ClassGraph ScanResult: 180.2 MB | GroovyClassLoader: 85.1 MB | AST: 47.1 MB
 *   2. /workspace/project-beta (287.9 MB)
 *        ClassGraph ScanResult: 165.0 MB | GroovyClassLoader: 78.3 MB | AST: 44.6 MB
 *   3. /workspace/project-gamma (201.5 MB)
 *        GroovyClassLoader: 92.0 MB | ClassGraph ScanResult: 70.4 MB | CompilationUnit: 39.1 MB
 * Total tracked: 12 scopes (8 active, 4 evicted) | JVM heap: 1,847 / 4,096 MB
 * </pre>
 *
 * <p>Estimation is shallow (reference counting + coefficients) to keep cost
 * low even when active. No deep heap walking is performed.</p>
 */
public final class MemoryProfiler {

	private static final Logger logger = LoggerFactory.getLogger(MemoryProfiler.class);

	/** System property to enable the profiler. */
	private static final String PROP_ENABLED = "groovyls.debug.memoryProfile";

	/** Cached enabled flag — read once, never changes at runtime. */
	private static final boolean ENABLED = Boolean.getBoolean(PROP_ENABLED);

	// ---- Estimation coefficients (bytes) ----

	/** Estimated bytes per AST node (ASTNode + lookup data + map entries). */
	private static final long BYTES_PER_AST_NODE = 320;

	/** Estimated bytes per class node entry in the visitor. */
	private static final long BYTES_PER_CLASS_NODE = 512;

	/** Estimated bytes per source unit in a compilation unit. */
	private static final long BYTES_PER_SOURCE_UNIT = 4096;

	/** Estimated bytes per classpath entry string in a classloader. */
	private static final long BYTES_PER_CLASSPATH_ENTRY = 256;

	/** Estimated bytes per dependency graph edge (URI + set entry). */
	private static final long BYTES_PER_DEP_EDGE = 192;

	/** Estimated bytes per diagnostic entry. */
	private static final long BYTES_PER_DIAGNOSTIC = 384;

	/** Estimated bytes per cached groovy file path. */
	private static final long BYTES_PER_CACHED_FILE = 160;

	/** Base overhead of a GroovyClassLoader instance (internal tables, etc.). */
	private static final long CLASSLOADER_BASE_BYTES = 2 * 1024 * 1024; // 2 MB

	/** Base overhead of a ScanResult instance (indexes, metadata). */
	private static final long SCANRESULT_BASE_BYTES = 8 * 1024 * 1024; // 8 MB

	private MemoryProfiler() {
		// utility class
	}

	/**
	 * Returns whether the memory profiler is enabled.
	 *
	 * @return {@code true} if {@code -Dgroovyls.debug.memoryProfile=true}
	 */
	public static boolean isEnabled() {
		return ENABLED;
	}

	/**
	 * Profiles all given scopes and logs the top 3 projects by estimated RAM,
	 * each with their top 3 internal components. No-ops if the profiler is
	 * disabled.
	 *
	 * @param scopes the list of project scopes to profile
	 */
	public static void logProfile(List<ProjectScope> scopes) {
		if (!ENABLED || scopes == null || scopes.isEmpty()) {
			return;
		}

		try {
			List<ScopeProfile> profiles = new ArrayList<>();
			int activeCount = 0;
			int evictedCount = 0;

			for (ProjectScope scope : scopes) {
				if (scope.isEvicted()) {
					evictedCount++;
					continue;
				}
				activeCount++;
				Map<String, Double> breakdown = estimateComponentSizes(scope);
				double totalMB = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();
				profiles.add(new ScopeProfile(scope, totalMB, breakdown));
			}

			// Sort descending by total estimated MB
			profiles.sort((a, b) -> Double.compare(b.totalMB, a.totalMB));

			// Build log message
			StringBuilder sb = new StringBuilder();
			sb.append("\n=== Memory Profile: Top 3 projects by RAM ===\n");

			int limit = Math.min(3, profiles.size());
			for (int i = 0; i < limit; i++) {
				ScopeProfile p = profiles.get(i);
				String projectName = p.scope.getProjectRoot() != null
						? p.scope.getProjectRoot().toString() : "<default>";
				sb.append(String.format("  %d. %s (%.1f MB)%n", i + 1, projectName, p.totalMB));

				// Top 3 components for this project
				List<Map.Entry<String, Double>> sorted = new ArrayList<>(p.breakdown.entrySet());
				sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
				int compLimit = Math.min(3, sorted.size());

				sb.append("       ");
				for (int j = 0; j < compLimit; j++) {
					Map.Entry<String, Double> entry = sorted.get(j);
					if (j > 0) {
						sb.append(" | ");
					}
					sb.append(String.format("%s: %.1f MB", entry.getKey(), entry.getValue()));
				}
				sb.append('\n');
			}

			Runtime rt = Runtime.getRuntime();
			long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
			long maxMB = rt.maxMemory() / (1024 * 1024);
			sb.append(String.format("Total tracked: %d scopes (%d active, %d evicted) | JVM heap: %,d / %,d MB%n",
					scopes.size(), activeCount, evictedCount, usedMB, maxMB));

			logger.info(sb.toString());
		} catch (Exception e) {
			// Profiling must never crash the server
			logger.debug("Memory profiler failed: {}", e.getMessage(), e);
		}
	}

	/**
	 * Estimates memory usage per component for a single project scope.
	 *
	 * @param scope the project scope to analyze
	 * @return map of component name → estimated size in MB
	 */
	public static Map<String, Double> estimateComponentSizes(ProjectScope scope) {
		Map<String, Double> sizes = new LinkedHashMap<>();

		// 1. ClassGraph ScanResult
		sizes.put("ClassGraph ScanResult", estimateScanResultMB(scope));

		// 2. GroovyClassLoader
		sizes.put("GroovyClassLoader", estimateClassLoaderMB(scope));

		// 3. AST (ASTNodeVisitor)
		sizes.put("AST (ASTNodeVisitor)", estimateAstVisitorMB(scope));

		// 4. CompilationUnit
		sizes.put("CompilationUnit", estimateCompilationUnitMB(scope));

		// 5. Diagnostics cache
		sizes.put("Diagnostics", estimateDiagnosticsMB(scope));

		// 6. DependencyGraph
		sizes.put("DependencyGraph", estimateDependencyGraphMB(scope));

		// 7. Classpath caches (in factory)
		sizes.put("Classpath caches", estimateClasspathCachesMB(scope));

		return sizes;
	}

	// ---- Component estimators ----

	private static double estimateScanResultMB(ProjectScope scope) {
		ScanResult sr = scope.getClassGraphScanResult();
		if (sr == null) {
			return 0.0;
		}
		// ScanResult base overhead + per-class info.
		// ClassGraph stores ClassInfo for every discovered class.
		try {
			int classCount = sr.getAllClasses().size();
			long bytes = SCANRESULT_BASE_BYTES + (long) classCount * 512;
			return bytes / (1024.0 * 1024.0);
		} catch (Exception e) {
			// ScanResult may be closed
			return SCANRESULT_BASE_BYTES / (1024.0 * 1024.0);
		}
	}

	private static double estimateClassLoaderMB(ProjectScope scope) {
		GroovyClassLoader cl = scope.getClassLoader();
		if (cl == null) {
			return 0.0;
		}
		// Base overhead + per-classpath-entry cost
		ICompilationUnitFactory factory = scope.getCompilationUnitFactory();
		int cpSize = 0;
		if (factory != null) {
			List<String> cp = factory.getAdditionalClasspathList();
			if (cp != null) {
				cpSize += cp.size();
			}
			List<String> testCp = factory.getTestOnlyClasspathList();
			if (testCp != null) {
				cpSize += testCp.size();
			}
		}
		long bytes = CLASSLOADER_BASE_BYTES + (long) cpSize * BYTES_PER_CLASSPATH_ENTRY;
		return bytes / (1024.0 * 1024.0);
	}

	private static double estimateAstVisitorMB(ProjectScope scope) {
		ASTNodeVisitor visitor = scope.getAstVisitor();
		if (visitor == null) {
			return 0.0;
		}
		// Count nodes across all URIs
		int totalNodes = 0;
		int totalClassNodes = 0;
		try {
			List<org.codehaus.groovy.ast.ASTNode> allNodes = visitor.getNodes();
			totalNodes = allNodes.size();
			totalClassNodes = visitor.getClassNodes().size();
		} catch (Exception e) {
			// best effort
		}

		long bytes = (long) totalNodes * BYTES_PER_AST_NODE
				+ (long) totalClassNodes * BYTES_PER_CLASS_NODE;

		// Reference index (soft, may be null or reclaimed)
		Map<?, ?> refIndex = visitor.getReferenceIndex();
		if (refIndex != null) {
			bytes += (long) refIndex.size() * BYTES_PER_AST_NODE;
		}

		return bytes / (1024.0 * 1024.0);
	}

	private static double estimateCompilationUnitMB(ProjectScope scope) {
		GroovyLSCompilationUnit cu = scope.getCompilationUnit();
		if (cu == null) {
			return 0.0;
		}
		// Count source units
		int sourceUnitCount = 0;
		try {
			Iterator<SourceUnit> it = cu.iterator();
			while (it.hasNext()) {
				it.next();
				sourceUnitCount++;
			}
		} catch (Exception e) {
			// best effort
		}
		long bytes = (long) sourceUnitCount * BYTES_PER_SOURCE_UNIT;
		return bytes / (1024.0 * 1024.0);
	}

	private static double estimateDiagnosticsMB(ProjectScope scope) {
		Map<URI, ?> diags = scope.getPrevDiagnosticsByFile();
		if (diags == null || diags.isEmpty()) {
			return 0.0;
		}
		int totalDiags = 0;
		for (Object value : diags.values()) {
			if (value instanceof List) {
				totalDiags += ((List<?>) value).size();
			}
		}
		long bytes = (long) totalDiags * BYTES_PER_DIAGNOSTIC;
		return bytes / (1024.0 * 1024.0);
	}

	private static double estimateDependencyGraphMB(ProjectScope scope) {
		com.tomaszrup.groovyls.compiler.DependencyGraph graph = scope.getDependencyGraph();
		if (graph == null) {
			return 0.0;
		}
		// DependencyGraph uses ConcurrentHashMaps for forward and reverse edges.
		// We estimate based on the size of the forward map (each entry has
		// URI key + Set<URI> value).
		int edgeCount = graph.getEdgeCount();
		long bytes = (long) edgeCount * BYTES_PER_DEP_EDGE;
		return bytes / (1024.0 * 1024.0);
	}

	private static double estimateClasspathCachesMB(ProjectScope scope) {
		ICompilationUnitFactory factory = scope.getCompilationUnitFactory();
		if (!(factory instanceof CompilationUnitFactory)) {
			return 0.0;
		}
		CompilationUnitFactory cuf = (CompilationUnitFactory) factory;

		long bytes = 0;

		// resolvedClasspathCache
		List<String> resolvedCp = cuf.getResolvedClasspathCache();
		if (resolvedCp != null) {
			bytes += (long) resolvedCp.size() * BYTES_PER_CLASSPATH_ENTRY;
		}

		// cachedGroovyFiles
		int cachedFileCount = cuf.getCachedGroovyFileCount();
		bytes += (long) cachedFileCount * BYTES_PER_CACHED_FILE;

		return bytes / (1024.0 * 1024.0);
	}

	// ---- Internal data holder ----

	private static final class ScopeProfile {
		final ProjectScope scope;
		final double totalMB;
		final Map<String, Double> breakdown;

		ScopeProfile(ProjectScope scope, double totalMB, Map<String, Double> breakdown) {
			this.scope = scope;
			this.totalMB = totalMB;
			this.breakdown = breakdown;
		}
	}
}
