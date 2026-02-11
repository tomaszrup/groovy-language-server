# Large Workspace Memory Profiling Guide

This guide explains how to use the memory profiling infrastructure to measure heap usage and identify memory issues in extreme-scale workspaces.

## Overview

The `LargeWorkspaceMemoryTests` class simulates enterprise-scale workspaces with:
- **50 projects** (configurable)
- **50,000 classpath entries per project** (configurable)
- **~2.5 million total classpath entries**

This enables:
- Memory footprint measurement
- Capacity planning
- Memory leak detection
- Scaling analysis

## Quick Start

### Run Full-Scale Memory Test (50 projects × 50k classpath)

```bash
./gradlew memoryTest
```

**Requirements:**
- 8GB heap minimum
- ~2.5GB disk space for dummy JARs
- ~15-30 minutes runtime
- Java 11+

**Outputs:**
- Heap dumps in `build/heap-dumps/` (if enabled)
- JFR recording in `build/jfr/memory-test.jfr`
- GC logs in `build/logs/gc-memory-test.log`
- Console metrics (peak heap, steady-state, per-project cost)

### Run Quick Memory Test (10 projects × 5k classpath)

For faster iteration during development:

```bash
./gradlew quickMemoryTest
```

**Requirements:**
- 2GB heap
- ~50MB disk space
- ~2-5 minutes runtime

## Configuration

Override test parameters via JVM system properties:

```bash
./gradlew memoryTest \
  -Dgroovyls.test.projectCount=100 \
  -Dgroovyls.test.classpathSize=10000 \
  -Dgroovyls.test.jarSize=2048 \
  -Dgroovyls.test.heapDump=true
```

### Available Properties

| Property | Default | Description |
|----------|---------|-------------|
| `groovyls.test.projectCount` | 50 | Number of projects to simulate |
| `groovyls.test.classpathSize` | 50000 | Classpath entries per project |
| `groovyls.test.jarSize` | 1024 | Size of each dummy JAR (bytes) |
| `groovyls.test.heapDump` | `true` (in memoryTest task) | Capture heap dumps at key points |
| `groovyls.test.disableEviction` | `false` | Disable scope eviction (worst-case retention) |

### Example: Small-Scale Test

```bash
./gradlew memoryTest \
  -Dgroovyls.test.projectCount=5 \
  -Dgroovyls.test.classpathSize=1000
```

### Example: Extreme-Scale Test

```bash
./gradlew memoryTest \
  -Dgroovyls.test.projectCount=100 \
  -Dgroovyls.test.classpathSize=100000 \
  --max-heap-size=16g
```

## Understanding the Output

### Console Metrics

The test outputs detailed metrics for each phase:

```
╔═══════════════════════════════════════════════════════════════════════╗
║  MEMORY PROFILING SUMMARY                                             ║
╠═══════════════════════════════════════════════════════════════════════╣
║  Projects:                          50                                  ║
║  Classpath per project:         50,000 entries                         ║
║  Total raw entries:          2,500,000                                  ║
║  Unique entries:             2,500,050                                  ║
║  ────────────────────────────────────────────────────────────────────  ║
║  Baseline heap:                    256 MB                               ║
║  Peak heap (compilation):        5,120 MB                               ║
║  Steady-state heap:              4,800 MB                               ║
║  Total growth:                   4,544 MB                               ║
║  Heap per project:                 ~100 MB (estimated)                   ║
╚═══════════════════════════════════════════════════════════════════════╝
```

### Key Metrics

- **Peak heap (compilation)**: Maximum heap during project compilation phase
- **Steady-state heap**: Heap after GC with all projects loaded
- **Total growth**: Difference between steady-state and baseline
- **Heap per project**: Estimated memory cost per project scope

## Heap Dump Analysis

### Capturing Heap Dumps

Heap dumps are automatically captured at:
1. **Baseline** (before test starts)
2. **After classpath resolution** (classpaths loaded, no compilation)
3. **After compilation** (all scopes created)
4. **Steady-state** (after GC)

Dumps are written to `build/heap-dumps/heap-<label>-<timestamp>.hprof`

### Analyzing with Eclipse Memory Analyzer (MAT)

1. **Download MAT**: https://eclipse.dev/mat/downloads.php

2. **Open heap dump**:
   ```bash
   mat build/heap-dumps/heap-03-steady-state-*.hprof
   ```

3. **Run Leak Suspects Report**:
   - MAT automatically suggests potential memory leaks
   - Check for:
     - Classloader retention
     - Large `String[]` or `HashMap` instances
     - AST node retention after scope eviction

4. **Dominator Tree**:
   - Shows retained heap by object
   - Sort by "Retained Heap" column
   - Look for `CompilationUnit`, `ClassNode`, `GroovyClassLoader`

5. **Histogram**:
   - View instance counts by class
   - Check for unexpected multiples of `ProjectScope` or `GroovyLSCompilationUnit`

### Analyzing with VisualVM

1. **Install VisualVM**: https://visualvm.github.io/

2. **Open heap dump**:
   ```bash
   visualvm --openfile build/heap-dumps/heap-03-steady-state-*.hprof
   ```

3. **OQL Console** (Object Query Language):
   ```javascript
   // Find all ProjectScope instances
   select s from com.tomaszrup.groovyls.ProjectScope s
   
   // Find classloaders with size > 100MB
   select cl from java.lang.ClassLoader cl where sizeof(cl) > 100*1024*1024
   
   // Find all compilation units
   select cu from com.tomaszrup.groovyls.compiler.control.GroovyLSCompilationUnit cu
   ```

4. **Compute Retained Sizes**:
   - Right-click classes → "Compute Retained Sizes"
   - Identify largest retained objects

### Analyzing with JProfiler / YourKit

1. **Attach profiler to running test**:
   ```bash
   # Modify memoryTest task in build.gradle:
   jvmArgs += '-agentpath:/path/to/jprofiler/bin/libjprofilerti.so'
   ```

2. **Live profiling**:
   - Allocation hot spots
   - Object allocation recording
   - Thread analysis

## JFR (Java Flight Recorder) Analysis

### View with JDK Mission Control

```bash
# Install JMC (bundled with JDK 11+)
jmc
```

1. Open `build/jfr/memory-test.jfr`
2. Navigate to **Memory** → **Garbage Collections**
3. Check:
   - GC frequency and pause times
   - Heap after GC trend (should stabilize)
   - Allocation pressure

### CLI Analysis

```bash
# Print GC events
jfr print --events jdk.GarbageCollection build/jfr/memory-test.jfr

# Print heap summary
jfr print --events jdk.GCHeapSummary build/jfr/memory-test.jfr

# Print allocation events
jfr print --events jdk.ObjectAllocationInNewTLAB build/jfr/memory-test.jfr
```

## Common Issues and Solutions

### OutOfMemoryError

**Symptom**: Test fails with `java.lang.OutOfMemoryError: Java heap space`

**Solutions**:
1. Increase heap in `build.gradle`:
   ```gradle
   maxHeapSize = '12g'  // or higher
   ```
2. Reduce scale:
   ```bash
   ./gradlew memoryTest -Dgroovyls.test.projectCount=25
   ```
3. Enable compressed oops (automatic on Java 11+):
   ```gradle
   jvmArgs += '-XX:+UseCompressedOops'
   ```

### Slow JAR Generation

**Symptom**: JAR generation takes > 10 minutes

**Root Causes**:
1. **ZIP compression (DEFLATE)** — 3-5x slower than uncompressed (now optimized to STORED)
2. **Windows Defender/antivirus** — Scans each new `.jar` file as it's created
3. **HDD vs SSD** — Mechanical drives are 10-100x slower for mass file creation
4. **NTFS overhead** — Windows filesystem has higher metadata overhead than ext4/XFS

**Solutions**:
1. **✅ Already optimized** — Code now uses `STORED` (uncompressed), reuses buffers, pre-calculates CRCs
   - Expected: ~20-30 seconds for 50k JARs on SSD, ~60s on HDD
2. **Exclude temp directory from antivirus** (biggest speedup for Windows):
   ```powershell
   # PowerShell (Run as Administrator)
   Add-MpPreference -ExclusionPath "$env:TEMP"
   ```
3. **Use SSD** — Mechanical drives add 2-5× overhead
4. **Reduce JAR size** — Smaller JARs = faster writes:
   ```bash
   -Dgroovyls.test.jarSize=256  # Instead of 1024
   ```
5. **Reduce scale** — Test with fewer JARs:
   ```bash
   -Dgroovyls.test.classpathSize=10000  # Instead of 50000
   ```

**Performance Benchmarks** (after optimization):

| Environment | 50k JARs (1KB each) | Hardware |
|-------------|---------------------|----------|
| Windows 11 + SSD + Defender ON | ~45-60 seconds | NVMe SSD |
| Windows 11 + SSD + Defender OFF | ~20-30 seconds | NVMe SSD |
| Windows 10 + HDD + Defender ON | ~120-180 seconds | 7200 RPM |
| Linux (WSL2) + ext4 | ~15-20 seconds | Same NVMe |
| Linux bare metal + ext4 | ~10-15 seconds | NVMe SSD |

### Heap Dumps Too Large

**Symptom**: Heap dumps exceed available disk space

**Solutions**:
1. Disable heap dumps:
   ```bash
   -Dgroovyls.test.heapDump=false
   ```
2. Capture only specific phases (modify test code)
3. Use live profilers instead (JProfiler, YourKit)

### Inconsistent Results

**Symptom**: Memory measurements vary between runs

**Solutions**:
1. Ensure GC runs before measurements (already done via `forceGC()`)
2. Close other applications (reduce background memory pressure)
3. Run multiple times and average results
4. Use JFR for more precise measurements

## Interpreting Results

### Expected Baselines (8GB heap, G1GC)

| Scale | Projects | Classpath/Project | Peak Heap | Per-Project Cost |
|-------|----------|-------------------|-----------|------------------|
| Small | 10 | 1,000 | ~500 MB | ~30 MB |
| Medium | 25 | 10,000 | ~2 GB | ~60 MB |
| Large | 50 | 50,000 | ~5 GB | ~100 MB |
| Extreme | 100 | 100,000 | ~10 GB | ~100 MB |

### Red Flags

⚠️ **Investigate if you see:**
- Per-project cost > 200 MB (possible classloader leak)
- Heap growth doesn't stabilize after GC (memory leak)
- Heap after eviction doesn't drop (eviction not working)
- OOM with plenty of heap remaining (metaspace exhaustion)
- Linear heap growth with constant project count (accumulator bug)

### Healthy Patterns

✅ **Good signs:**
- Steady-state heap < Peak heap (GC reclaimed temporary objects)
- Per-project cost relatively constant (50-150 MB range)
- Heap after GC stabilizes (no unbounded growth)
- Compilation units reused (warm path fast)

## Advanced Usage

### Disable Scope Eviction (Worst-Case Retention)

Test memory usage when scopes are never evicted:

```bash
./gradlew memoryTest \
  -Dgroovyls.test.disableEviction=true \
  -Dgroovyls.test.projectCount=100
```

This measures peak memory if all projects are simultaneously active.

### Profile with Async-Profiler

For production-grade flamegraphs:

```bash
# Download async-profiler
wget https://github.com/jvm-profiling-tools/async-profiler/releases/latest/download/async-profiler-linux-x64.tar.gz

# Run test with profiler attached
./gradlew memoryTest \
  -Dgroovyls.test.projectCount=50 &
PID=$!

# Start profiling (after JARs generated)
sleep 300
./async-profiler/profiler.sh -d 300 -e alloc -f build/flamegraph-alloc.html $PID
```

### Compare Memory with Different Groovy Versions

```bash
# Test with Groovy 4.0.30
./gradlew memoryTest -Dgroovyls.test.projectCount=20

# Modify build.gradle to use Groovy 4.0.31
# Re-run and compare results
```

## Integration with CI/CD

### GitHub Actions Example

```yaml
name: Memory Profiling

on:
  schedule:
    - cron: '0 2 * * 0'  # Weekly on Sunday 2 AM

jobs:
  memory-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
      
      - name: Run quick memory test
        run: ./gradlew quickMemoryTest
      
      - name: Upload heap dumps
        if: failure()
        uses: actions/upload-artifact@v3
        with:
          name: heap-dumps
          path: build/heap-dumps/*.hprof
      
      - name: Upload JFR recording
        uses: actions/upload-artifact@v3
        with:
          name: jfr-recording
          path: build/jfr/*.jfr
```

## References

- [Eclipse MAT Documentation](https://help.eclipse.org/latest/index.jsp?topic=%2Forg.eclipse.mat.ui.help%2Fwelcome.html)
- [VisualVM Heap Dump Analysis](https://visualvm.github.io/documentation.html)
- [JDK Flight Recorder Guide](https://docs.oracle.com/en/java/javase/11/troubleshoot/diagnostic-tools.html#GUID-D38849B6-61C7-4ED6-A395-EA4BC32A9FD6)
- [G1GC Tuning Guide](https://www.oracle.com/technical-resources/articles/java/g1gc.html)
- [Async-Profiler](https://github.com/jvm-profiling-tools/async-profiler)

## Troubleshooting

For additional help:
1. Check test output in `build/reports/tests/memoryTest/index.html`
2. Review GC logs in `build/logs/gc-memory-test.log`
3. Enable debug logging: `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`
4. Open an issue with:
   - Console output
   - GC log snippet
   - Heap dump (if < 100MB) or MAT leak suspects report
