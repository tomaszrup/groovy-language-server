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

import com.tomaszrup.groovyls.JavadocResolver;
import com.tomaszrup.groovyls.ProjectScope;
import com.tomaszrup.groovyls.compiler.SharedClassGraphCache;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit;
import com.tomaszrup.groovyls.config.CompilationUnitFactory;
import com.tomaszrup.groovyls.config.ICompilationUnitFactory;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import org.codehaus.groovy.control.SourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyClassLoader;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Opt-in memory profiler that estimates per-project RAM usage and reports
 * the top 3 memory-consuming projects with their top 3 internal components,
 * plus global (process-wide) memory consumers and a JVM overhead estimate.
 *
 * <p><b>Disabled by default</b> to avoid any overhead for regular users.
 * Enable via system property: {@code -Dgroovyls.debug.memoryProfile=true}</p>
 *
 * <p>When enabled, this profiler is called at every existing memory-logging
 * trigger point (after OOM, after classpath backfill, during eviction sweeps)
 * and produces output like:</p>
 * <pre>
 * === Memory Profile: Top 3 projects by RAM ===
 *   1. /workspace/project-alpha (45.2 MB)
 *        ClassGraph ScanResult: 18.0 MB | GroovyClassLoader: 12.5 MB | AST: 8.1 MB
 *   2. /workspace/project-beta (38.7 MB)
 *        ClassGraph ScanResult: 15.2 MB | GroovyClassLoader: 10.3 MB | AST: 7.6 MB
 * Per-scope total: 83.9 MB (2 active, 0 evicted)
 * --- Global (process-wide) ---
 *   SharedClassGraphCache: 52.3 MB (2 entries, 3 refs)
 *   SharedSourceJarIndex: 1.2 MB | JavadocResolver: 0.4 MB
 *   JavaSourceLocator (all scopes): 0.8 MB
 * --- Summary ---
 *   Tracked:   83.9 MB (scopes) + 54.7 MB (global) = 138.6 MB
 *   JVM heap:  158 / 768 MB (21 %)
 *   Untracked: ~19 MB (JVM internals, thread stacks, LSP4J, GC overhead)
 * </pre>
 *
 * <h3>Estimation accuracy</h3>
 * <p>Estimation uses empirically-calibrated coefficients per data structure.
 * No deep heap walking is performed to keep cost low. The goal is to account
 * for &gt;80% of used heap so that the "Untracked" line is small and stable.</p>
 *
 * <h3>ScanResult sharing</h3>
 * <p>Per-scope ScanResult estimates reflect each scope's <em>view</em> of
 * the ScanResult (class count). The global section reports the actual unique
 * memory footprint via {@link SharedClassGraphCache} without double-counting.</p>
 */
public final class MemoryProfiler {

	private static final Logger logger = LoggerFactory.getLogger(MemoryProfiler.class);

	/** System property to enable the profiler. */
	private static final String PROP_ENABLED = "groovyls.debug.memoryProfile";

	/** Cached enabled flag — read once, never changes at runtime. */
	private static final boolean ENABLED = Boolean.getBoolean(PROP_ENABLED);

	// ---- Estimation coefficients (bytes) ----
	// These are calibrated against JVM heap dumps of real workspaces.

	/**
	 * Estimated bytes per ClassInfo in a ClassGraph ScanResult.
	 * Each ClassInfo holds: name, modifiers, superclass ref, interfaces list,
	 * MethodInfoList, FieldInfoList, AnnotationInfoList, type signature,
	 * ClasspathElement ref, and various interned strings.
	 * Empirical measurement: ~6 KB per ClassInfo on average.
	 */
	private static final long BYTES_PER_CLASSINFO = 6144;

	/** Base overhead of a ScanResult (internal indexes, ClasspathElement list). */
	private static final long SCANRESULT_BASE_BYTES = 2 * 1024 * 1024; // 2 MB

	/**
	 * Estimated bytes per AST node in the ASTNodeVisitor lookup map.
	 * Each node has: the ASTNode object itself (~200 bytes avg for various
	 * subclasses), ASTLookupKey wrapper (16 bytes), ASTNodeLookupData
	 * (parent ref + URI ref = 32 bytes), HashMap entry (32 bytes),
	 * plus the node's internal fields (type ClassNode refs, Token, etc.).
	 * Conservative: ~800 bytes per node.
	 */
	private static final long BYTES_PER_AST_NODE = 800;

	/** Estimated bytes per ClassNode entry (larger than avg node). */
	private static final long BYTES_PER_CLASS_NODE = 2048;

	/**
	 * Estimated bytes per source unit in a CompilationUnit.
	 * Each SourceUnit holds: the full AST subtree (ModuleNode → ClassNode →
	 * MethodNode → Statement trees), a ReaderSource, ErrorCollector,
	 * and CompileUnit metadata.  ~40 KB per source file on average.
	 */
	private static final long BYTES_PER_SOURCE_UNIT = 40 * 1024; // 40 KB

	/**
	 * Base overhead of a GroovyClassLoader instance.
	 * Includes: internal class cache, defineClass registry, transformation
	 * caches, parent classloader overhead, and URLClassPath with JAR handles.
	 */
	private static final long CLASSLOADER_BASE_BYTES = 5 * 1024 * 1024; // 5 MB

	/** Per-classpath-entry cost in the classloader (URL + JAR file handle). */
	private static final long BYTES_PER_CLASSPATH_ENTRY = 1024;

	/** Estimated bytes per dependency graph edge (URI + set entry). */
	private static final long BYTES_PER_DEP_EDGE = 192;

	/** Estimated bytes per diagnostic entry. */
	private static final long BYTES_PER_DIAGNOSTIC = 384;

	/** Estimated bytes per cached groovy file path. */
	private static final long BYTES_PER_CACHED_FILE = 160;

	/** Estimated bytes per resolved classpath cache entry. */
	private static final long BYTES_PER_CLASSPATH_CACHE_ENTRY = 256;

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
	 * each with their top 3 internal components, followed by global singletons
	 * and a JVM summary.  No-ops if the profiler is disabled.
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
				boolean isSharedScan = p.scope.getClassGraphClasspathFiles() != null;
				String sharedSuffix = isSharedScan ? " [shared scan]" : "";
				sb.append(String.format("  %d. %s (%.1f MB)%s%n", i + 1, projectName, p.totalMB, sharedSuffix));

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

				// Per-package breakdown of ScanResult memory
				Double srSize = p.breakdown.get("ClassGraph ScanResult");
				if (srSize != null && srSize > 0.0) {
					List<PackageMemoryEntry> pkgEntries = estimateScanResultByPackage(p.scope);
					if (!pkgEntries.isEmpty()) {
						int pkgLimit = Math.min(5, pkgEntries.size());
						sb.append("       Top packages: ");
						for (int j = 0; j < pkgLimit; j++) {
							if (j > 0) {
								sb.append(" | ");
							}
							PackageMemoryEntry pe = pkgEntries.get(j);
							sb.append(String.format("%s %.1f MB (%d classes)",
									pe.packagePrefix, pe.estimatedMB, pe.classCount));
						}
						sb.append('\n');
					}
				}
			}

			double scopeTotalMB = profiles.stream().mapToDouble(p -> p.totalMB).sum();
			sb.append(String.format("Per-scope total: %.1f MB (%d active, %d evicted)%n",
					scopeTotalMB, activeCount, evictedCount));

			// --- Global (process-wide) singletons ---
			sb.append("--- Global (process-wide) ---\n");

			// SharedClassGraphCache (unique ScanResult footprint, no double-counting)
			SharedClassGraphCache classGraphCache = SharedClassGraphCache.getInstance();
			long classGraphBytes = classGraphCache.estimateMemoryBytes();
			double classGraphMB = classGraphBytes / (1024.0 * 1024.0);
			sb.append(String.format("  SharedClassGraphCache: %.1f MB (%d entries, %d refs)%n",
					classGraphMB, classGraphCache.getEntryCount(), classGraphCache.getTotalRefCount()));

			// Per-package breakdown across all cached ScanResults
			List<PackageMemoryEntry> globalPkgs = classGraphCache.getTopPackagesByMemory(5);
			if (!globalPkgs.isEmpty()) {
				sb.append("    Top packages: ");
				for (int i = 0; i < globalPkgs.size(); i++) {
					if (i > 0) {
						sb.append(" | ");
					}
					PackageMemoryEntry pe = globalPkgs.get(i);
					sb.append(String.format("%s %.1f MB (%d classes)",
							pe.packagePrefix, pe.estimatedMB, pe.classCount));
				}
				sb.append('\n');
			}

			// SharedSourceJarIndex
			SharedSourceJarIndex sourceJarIndex = SharedSourceJarIndex.getInstance();
			long sourceJarBytes = sourceJarIndex.estimateMemoryBytes();
			double sourceJarMB = sourceJarBytes / (1024.0 * 1024.0);

			// JavadocResolver (static cache)
			long javadocBytes = JavadocResolver.estimateCacheMemoryBytes();
			double javadocMB = javadocBytes / (1024.0 * 1024.0);

			sb.append(String.format("  SharedSourceJarIndex: %.1f MB | JavadocResolver: %.1f MB%n",
					sourceJarMB, javadocMB));

			// JavaSourceLocator per-scope caches (aggregate across all scopes)
			double jslTotalMB = 0;
			for (ProjectScope scope : scopes) {
				if (!scope.isEvicted()) {
					JavaSourceLocator locator = scope.getJavaSourceLocator();
					if (locator != null) {
						jslTotalMB += locator.estimateMemoryBytes() / (1024.0 * 1024.0);
					}
				}
			}
			if (jslTotalMB > 0.05) {
				sb.append(String.format("  JavaSourceLocator (all scopes): %.1f MB%n", jslTotalMB));
			}

			double globalTotalMB = classGraphMB + sourceJarMB + javadocMB + jslTotalMB;

			// --- Summary ---
			sb.append("--- Summary ---\n");
			double trackedMB = scopeTotalMB + globalTotalMB;
			sb.append(String.format("  Tracked:   %.1f MB (scopes) + %.1f MB (global) = %.1f MB%n",
					scopeTotalMB, globalTotalMB, trackedMB));

			Runtime rt = Runtime.getRuntime();
			long usedBytes = rt.totalMemory() - rt.freeMemory();
			long usedMB = usedBytes / (1024 * 1024);
			long maxMB = rt.maxMemory() / (1024 * 1024);
			int pct = (int) (100.0 * usedBytes / rt.maxMemory());
			sb.append(String.format("  JVM heap:  %,d / %,d MB (%d %%)%n", usedMB, maxMB, pct));

			double untrackedMB = (usedBytes / (1024.0 * 1024.0)) - trackedMB;
			if (untrackedMB < 0) untrackedMB = 0;
			sb.append(String.format("  Untracked: ~%.0f MB (JVM internals, thread stacks, LSP4J, GC overhead)%n",
					untrackedMB));

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
		// ~6 KB per ClassInfo (method/field/annotation metadata, interned
		// strings, ClassGraph internal indexes) + base overhead.
		try {
			int classCount = sr.getAllClasses().size();
			long bytes = SCANRESULT_BASE_BYTES + (long) classCount * BYTES_PER_CLASSINFO;
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
		// Base overhead (internal class cache, defineClass registry,
		// transformation caches, URLClassPath with JAR handles)
		// + per-classpath-entry cost
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
		// 40 KB per source unit: full AST subtree (ModuleNode → ClassNode →
		// MethodNode → Statement/Expression trees), ReaderSource, ErrorCollector
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
			bytes += (long) resolvedCp.size() * BYTES_PER_CLASSPATH_CACHE_ENTRY;
		}

		// cachedGroovyFiles
		int cachedFileCount = cuf.getCachedGroovyFileCount();
		bytes += (long) cachedFileCount * BYTES_PER_CACHED_FILE;

		return bytes / (1024.0 * 1024.0);
	}

	// ---- Per-package memory estimation ----

	/**
	 * Truncates a fully-qualified package name to the first {@code depth}
	 * segments and appends ".*".
	 * <p>Examples (depth=2):</p>
	 * <ul>
	 *   <li>{@code java.util.concurrent.locks} → {@code java.util.*}</li>
	 *   <li>{@code com.google} → {@code com.google.*}</li>
	 *   <li>{@code java} → {@code java.*}</li>
	 *   <li>{@code ""} (default package) → {@code (default)}</li>
	 * </ul>
	 */
	public static String truncatePackage(String packageName, int depth) {
		if (packageName == null || packageName.isEmpty()) {
			return "(default)";
		}
		int segmentsFound = 0;
		int endIdx = -1;
		for (int i = 0; i < packageName.length(); i++) {
			if (packageName.charAt(i) == '.') {
				segmentsFound++;
				if (segmentsFound == depth) {
					endIdx = i;
					break;
				}
			}
		}
		if (endIdx > 0) {
			return packageName.substring(0, endIdx) + ".*";
		}
		// Fewer segments than depth — return as-is with wildcard
		return packageName + ".*";
	}

	/**
	 * Estimates ScanResult memory broken down by top-level package prefix
	 * (2-segment depth, e.g. {@code java.util.*}, {@code org.apache.*}).
	 * Returns entries sorted descending by estimated MB.
	 *
	 * @param scope the project scope to analyze
	 * @return sorted list of per-package memory entries, or empty if unavailable
	 */
	public static List<PackageMemoryEntry> estimateScanResultByPackage(ProjectScope scope) {
		ScanResult sr = scope.getClassGraphScanResult();
		if (sr == null) {
			return Collections.emptyList();
		}
		try {
			return groupClassesByPackage(sr, 2);
		} catch (Exception e) {
			// ScanResult may be closed
			return Collections.emptyList();
		}
	}

	/**
	 * Groups all classes in a ScanResult by truncated package prefix and
	 * returns per-group memory estimates sorted descending.
	 *
	 * @param sr    the scan result to analyze
	 * @param depth the package depth to truncate to (e.g. 2)
	 * @return sorted list of per-package memory entries
	 */
	static List<PackageMemoryEntry> groupClassesByPackage(ScanResult sr, int depth) {
		Map<String, Integer> countsMap = new LinkedHashMap<>();
		for (ClassInfo ci : sr.getAllClasses()) {
			String prefix = truncatePackage(ci.getPackageName(), depth);
			countsMap.merge(prefix, 1, Integer::sum);
		}

		List<PackageMemoryEntry> entries = new ArrayList<>(countsMap.size());
		for (Map.Entry<String, Integer> e : countsMap.entrySet()) {
			int count = e.getValue();
			double mb = (count * BYTES_PER_CLASSINFO) / (1024.0 * 1024.0);
			entries.add(new PackageMemoryEntry(e.getKey(), mb, count));
		}

		entries.sort((a, b) -> Double.compare(b.estimatedMB, a.estimatedMB));
		return entries;
	}

	// ---- Internal data holders ----

	/**
	 * Holds per-package memory estimation data.
	 */
	public static final class PackageMemoryEntry {
		/** Package prefix with wildcard, e.g. {@code java.util.*}. */
		public final String packagePrefix;
		/** Estimated memory in MB consumed by classes in this package. */
		public final double estimatedMB;
		/** Number of classes in this package prefix. */
		public final int classCount;

		public PackageMemoryEntry(String packagePrefix, double estimatedMB, int classCount) {
			this.packagePrefix = packagePrefix;
			this.estimatedMB = estimatedMB;
			this.classCount = classCount;
		}
	}

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
