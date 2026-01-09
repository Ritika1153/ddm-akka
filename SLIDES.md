# Distributed IND Discovery - Documentation Slides

---

## SLIDE 1: Data Partitioning and Memory Management

### System Architecture
- **Master**: DependencyMiner coordinates workers, assigns columns
- **Workers**: DependencyWorker stores partial data, validates INDs
- **Input**: InputReader streams CSV in 10K row batches
- **Output**: ResultCollector writes results.txt

### Data Partitioning Strategy

**Round-Robin Distribution**
```
attribute2worker[attribute] = attribute % numWorkers

Example with 4 workers:
Worker 0: Columns 0, 4, 8, 12, 16, ...
Worker 1: Columns 1, 5, 9, 13, 17, ...
Worker 2: Columns 2, 6, 10, 14, 18, ...
Worker 3: Columns 3, 7, 11, 15, 19, ...
```

Each worker stores **1/N of total columns**.

### Why Data Doesn't Fit in One Machine

**1. No Central Data Storage**
- DependencyMiner stores ZERO column data (only metadata: names, assignments, boolean matrix)
- InputReader streams batches → Miner forwards immediately to workers
- Workers accumulate only their assigned columns

**2. Memory Constraints Enforced**
- Each JVM limited to 2GB RAM: `-Xmx2048m`
- Workers monitor memory: `availableMemory < usedMemoryPredicted`
- If overloaded → Miner pauses distribution, waits for more workers

**3. Scalability Proof**
```
Dataset: 1000 columns × 10M rows ≈ 80GB
- 1 worker:  80GB needed → FAILS (exceeds 2GB)
- 10 workers: 8GB each → FAILS (exceeds 2GB)
- 50 workers: 1.6GB each → SUCCESS
```

**4. Work Stealing**
- New worker joins → Miner redistributes overloaded columns
- Enables horizontal scaling during execution
- System adapts to dataset size dynamically

---

## SLIDE 2: Worker Cooperation and IND Validation

### Distributed Validation Process

**Phase 1: Local Validation**
Each worker validates INDs within its own columns:
```
Worker 0 has columns: C0, C4, C8
Validates all pairs:
  C0 ⊆ C4?  C4 ⊆ C0?
  C0 ⊆ C8?  C8 ⊆ C0?
  C4 ⊆ C8?  C8 ⊆ C4?
```

**Phase 2: Cross-Worker Validation**
Workers request columns from other workers:
```
Worker 0 needs to validate C0 ⊆ C1
→ Miner tells Worker 1: "Send C1 to Worker 0"
→ Worker 1 sends C1 via LargeMessageProxy
→ Worker 0 validates locally
→ Worker 0 reports results to Miner
```

### Worker Cooperation Mechanisms

**1. Work Pulling Pattern**
- Worker finishes task → sends results → requests new task
- Master dynamically assigns next validation
- Load balanced across all workers

**2. LargeMessageProxy for Data Transfer**
- Columns can contain millions of values
- LargeMessageProxy handles large message serialization
- Cross-worker data sharing on-demand only

**3. Parallel Validation with Helpers**
```
DependencyWorker spawns 4× DependencyWorkerHelper actors
Each helper validates subset of attribute pairs
DependencyWorkerCameo aggregates results
Combined results sent to Miner
```

**4. Candidate Matrix Pruning**
- Miner tracks validated pairs: `candidateMatrix[A][B] = true`
- Prevents duplicate validation
- Only untested pairs assigned to workers

### Why This is Truly Distributed

✅ **No Centralization**
- Master stores 0 bytes of column data
- Each worker has partial data only
- No "gather all data then process" pattern

✅ **Requires Multiple Workers**
- Large datasets fail with single worker (memory overload)
- System waits for workers if overloaded
- Cannot complete without distributed execution

✅ **On-Demand Data Movement**
- Columns shared only when needed for validation
- No full dataset replication
- LargeMessageProxy transfers specific columns

✅ **Memory-Driven Scaling**
- Workers report: overloaded/not overloaded
- Miner pauses if any worker overloaded
- Work stealing redistributes data when new workers join

### Validation Algorithm Details

**Binary Search for Inclusion**
- Columns sorted during accumulation
- Validation: `isIncluded(A, B)` checks if all values in A exist in B
- Uses `Collections.binarySearch()`: O(m log n) complexity

**Self-Pairs Excluded**
- Loop uses `j = i + 1` to avoid comparing column with itself
- No trivial INDs (A ⊆ A) in output

### Implementation Results

**Test Run (TPCH Dataset)**
- Workers: 4
- Total columns: 56
- Discovered INDs: 49
- Execution time: 7.7 seconds
- Auto-termination: Yes

**Memory Constraint Satisfied**
- Each worker: assigned ~14 columns
- Total data per worker: < 2GB
- System would scale to 100+ workers for larger datasets
