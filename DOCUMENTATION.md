# Distributed Inclusion Dependency Discovery
## Implementation Documentation

---

## Slide 1: System Architecture and Data Partitioning

### Overview
This implementation discovers unary inclusion dependencies (INDs) across distributed datasets using Akka actors and clustering. The solution is designed for datasets that **cannot fit in a single machine's memory**.

### Data Partitioning Strategy

**Round-Robin Column Distribution**
```
Columns: C0, C1, C2, C3, C4, C5, C6, C7, C8, C9
Workers: W0, W1, W2, W3

Distribution:
W0: C0, C4, C8
W1: C1, C5, C9
W2: C2, C6
W3: C3, C7
```

Each worker stores only **1/N of total columns** (N = number of workers).

### Why Data Doesn't Fit in One Machine

1. **Streaming Input Processing**
   - CSV files read in batches of 10,000 rows
   - InputReader streams data → DependencyMiner forwards immediately
   - **No central accumulation** of data

2. **Worker-Specific Storage**
   - Each DependencyWorker stores **only assigned columns**
   - DependencyMiner stores **zero column data** (only metadata)
   - Memory constraint: each worker limited to 2GB RAM (Pi cluster)

3. **Memory Overload Detection**
   - Workers continuously monitor memory usage
   - If predicted usage > available memory → report overload
   - System pauses data distribution until more workers join

4. **Scalability Example**
   - Dataset: 1000 columns × 10M rows ≈ 80GB total
   - 1 worker: impossible (exceeds 2GB limit)
   - 10 workers: 8GB per worker
   - 50 workers: 1.6GB per worker ✓ (fits within 2GB limit)

### Key Implementation Details

**DependencyMiner (Master)**
- Assigns columns to workers using: `attribute2worker[attribute] = attribute % numWorkers`
- Forwards batches directly to assigned workers
- Tracks validation progress via candidate matrix
- **Does NOT store column data**

**DependencyWorker**
- Receives batches and accumulates assigned columns
- Stores columns as sorted lists for binary search
- Creates helper actors for parallel validation
- Reports memory status after each batch

---

## Slide 2: Worker Cooperation and IND Validation

### Distributed IND Validation Process

**Phase 1: Local Validation**
Each worker validates INDs between its own columns:
```
Worker 0 has: C0, C4, C8
Validates: C0⊆C4, C4⊆C0, C0⊆C8, C8⊆C0, C4⊆C8, C8⊆C4
```

**Phase 2: Cross-Worker Validation**
Workers share columns to validate inter-worker INDs:
```
Worker 0 needs to check: C0 ⊆ C1
→ Worker 1 sends C1 to Worker 0 via LargeMessageProxy
→ Worker 0 validates C0 ⊆ C1 locally
```

### Worker Cooperation Mechanisms

1. **Work Pulling Pattern**
   - Worker completes validation → sends results to Miner
   - Miner assigns next validation task
   - Dynamic load balancing across workers

2. **LargeMessageProxy Usage**
   - Columns can be large (millions of values)
   - LargeMessageProxy serializes efficiently
   - Prevents message size limits in Akka

3. **Cameo Pattern for Aggregation**
   - Each worker spawns helper actors for parallel validation
   - DependencyWorkerCameo aggregates helper results
   - Sends combined results to miner

4. **Work Stealing**
   - New worker joins → Miner redistributes columns
   - Overloaded workers offload data to new workers
   - Enables horizontal scaling during execution

### Validation Algorithm

**Binary Search for Inclusion**
```java
private boolean isIncluded(List<String> dependent, List<String> referenced) {
    for (String value : dependent)
        if (Collections.binarySearch(referenced, value) < 0)
            return false;
    return true;
}
```
- Columns are sorted during accumulation
- Binary search: O(log n) per value lookup
- Total complexity: O(m log n) vs O(m×n) for containsAll

**Candidate Matrix Pruning**
- Master maintains boolean matrix: `candidateMatrix[attr1][attr2]`
- Once pair validated → mark as true
- Prevents duplicate validation
- Reduces validation from O(n²) to O(k) where k = actual INDs

### Why This is Truly Distributed

1. **No Central Repository**
   - Master never stores column data
   - Each worker has partial data only
   - No single point of data collection

2. **Required Multi-Worker Execution**
   - If dataset > 2GB, single worker fails with overload
   - System requires multiple workers to proceed
   - Work stealing automatically redistributes load

3. **On-Demand Data Movement**
   - Data shared only when needed for validation
   - LargeMessageProxy transfers specific columns
   - No replication of entire dataset

4. **Memory-Constrained Design**
   - 2GB limit enforced per JVM (-Xmx2048m)
   - Workers monitor and report memory usage
   - Algorithm adapts to available resources

### Results
- Discovered: 49 unary INDs
- Execution time: ~7.7 seconds (4 workers, TPCH dataset)
- Auto-termination: System shuts down when validation complete
- Output: results.txt via ResultCollector only
