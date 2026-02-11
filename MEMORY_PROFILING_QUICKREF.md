# Memory Profiling Quick Reference

## Running Memory Tests

The project includes comprehensive memory profiling tests for large-scale workspaces.

### Full-Scale Test (50 projects × 50k classpath entries)
```bash
./gradlew memoryTest
```
- **Heap Required:** 8GB
- **Disk Space:** ~2.5GB
- **Runtime:** 15-30 minutes
- **Outputs:** Heap dumps, JFR recordings, GC logs

### Quick Test (10 projects × 5k classpath entries)
```bash
./gradlew quickMemoryTest
```
- **Heap Required:** 2GB
- **Disk Space:** ~50MB
- **Runtime:** 2-5 minutes
- **Outputs:** Console metrics only

## Custom Configuration

```bash
./gradlew memoryTest \
  -Dgroovyls.test.projectCount=25 \
  -Dgroovyls.test.classpathSize=10000 \
  -Dgroovyls.test.heapDump=false
```

## Output Locations

- **Heap dumps:** `build/heap-dumps/*.hprof`
- **JFR recordings:** `build/jfr/memory-test.jfr`
- **GC logs:** `build/logs/gc-memory-test.log`
- **Test reports:** `build/reports/tests/memoryTest/`

## Analyzing Results

### Console Output
The test prints detailed metrics including:
- Peak heap during compilation
- Steady-state heap after GC
- Memory per project (for scaling estimates)
- Total unique classpath entries

### Heap Dump Analysis
Use Eclipse MAT, VisualVM, or JProfiler:
```bash
# Eclipse MAT
mat build/heap-dumps/heap-03-steady-state-*.hprof

# VisualVM
visualvm --openfile build/heap-dumps/heap-03-steady-state-*.hprof
```

### JFR Analysis
Use JDK Mission Control:
```bash
jmc  # Then open build/jfr/memory-test.jfr
```

## Configuration Options

| Property | Default | Description |
|----------|---------|-------------|
| `groovyls.test.projectCount` | 50 | Number of projects |
| `groovyls.test.classpathSize` | 50000 | Entries per project |
| `groovyls.test.jarSize` | 1024 | JAR size in bytes |
| `groovyls.test.heapDump` | true | Capture heap dumps |

## Complete Documentation

For detailed analysis techniques, troubleshooting, and CI/CD integration, see:
**[MEMORY_PROFILING.md](MEMORY_PROFILING.md)**
