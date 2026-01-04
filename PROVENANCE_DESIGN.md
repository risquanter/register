# Provenance Metadata Design

**Status**: Approved  
**Implementation**: After Phase 1 (ZIO Prelude Migration)  
**Date**: January 4, 2026

---

## Overview

Provenance metadata enables reproducible Monte Carlo simulations by capturing all parameters needed to regenerate identical random samples. Each risk node in the tree has its own provenance, allowing users to verify and reproduce simulation results.

---

## Random Seed Architecture

### Current 4-Layer HDR Seed Hierarchy

```
HDR.generate(counter, entityId, varId, seed3, seed4)
             ↓        ↓        ↓      ↓      ↓
             trial    entity   variable global global
```

#### Layer 1: Counter (Trial Number)
- **Value**: 0, 1, 2, ... up to nTrials
- **Purpose**: Advances random stream across simulation trials
- **Scope**: Per-trial iteration
- **Reproducibility**: Same trial index → same random value

#### Layer 2: EntityId
- **Value**: `leaf.id.hashCode.toLong`
- **Purpose**: Isolates random streams between different risk nodes
- **Scope**: Per risk node in tree
- **Example**: "cyber-attack" (entityId=42) vs "data-breach" (entityId=73)
- **Critical**: Different risks MUST have different entityIds to prevent stream collision

#### Layer 3: VarId (Variable ID)
- **Value**: Two variants per risk:
  - **Occurrence sampling**: `entityId.hashCode + 1000L`
  - **Loss sampling**: `entityId.hashCode + 2000L`
- **Purpose**: Prevents correlation between "did event occur?" and "how much loss?"
- **Scope**: Per risk node + per sampling purpose
- **Why critical**: Without offset, occurrence and loss would share random stream → biased correlation

#### Layer 4: Seed3 & Seed4 (Global Seeds)
- **Value**: Currently hardcoded to `0L, 0L` everywhere
- **Purpose**: User-controlled global seed for entire simulation
- **Scope**: Affects ALL risks, ALL trials across entire tree
- **Use case**: "Re-run same tree with different seed" → completely different outcomes
- **Future**: Expose as API parameters (currently not configurable)

---

## Provenance Data Structure

### Per-Node Provenance

Each `LECNode` (RiskTreeWithLEC response) includes provenance for reproducibility:

```scala
case class NodeProvenance(
  // HDR Configuration - Deterministic Random Number Generation
  riskId: String,              // Source risk identifier
  entityId: Long,              // Derived from riskId.hashCode.toLong
  occurrenceVarId: Long,       // entityId.hashCode + 1000L
  lossVarId: Long,             // entityId.hashCode + 2000L
  globalSeed3: Long,           // Global seed (currently 0, future: user-configurable)
  globalSeed4: Long,           // Global seed (currently 0, future: user-configurable)
  
  // Distribution Configuration - Loss Amount Modeling
  distributionType: String,    // "expert" or "lognormal"
  distributionParams: DistributionParams,
  
  // Execution Metadata
  timestamp: java.time.Instant,
  simulationUtilVersion: String = "0.8.0"  // simulation-util library version
)

sealed trait DistributionParams

case class MetalogParams(
  percentiles: Array[Double],   // Expert opinion: input percentiles (e.g., [0.1, 0.5, 0.9])
  quantiles: Array[Double],     // Expert opinion: input quantiles (e.g., [1000, 5000, 20000])
  terms: Int,                   // Metalog polynomial terms (3-9, typically 3 or 9)
  lowerBound: Option[Double],   // Semi-bounded: lower=0.0 (losses can't be negative)
  upperBound: Option[Double]    // Semi-bounded: upper limit (if applicable)
) extends DistributionParams

case class LognormalParams(
  minLoss: Long,                // 90% CI lower bound (P05)
  maxLoss: Long,                // 90% CI upper bound (P95)
  confidenceInterval: Double = 0.90  // Confidence level (BCG uses 90%)
) extends DistributionParams
```

---

## Storage Strategy

### Option B: Separate Companion Object (Approved)

Provenance is returned **alongside** results, not embedded in domain objects:

```scala
// HTTP Response DTO
case class RiskTreeWithLEC(
  riskTree: RiskTree,
  quantiles: Map[String, Double],
  vegaLiteSpec: Option[String],
  lecNode: Option[LECNode] = None,
  depth: Int = 0,
  provenance: Option[TreeProvenance] = None  // NEW: Tree-level + node provenances
)

case class TreeProvenance(
  treeId: Long,
  globalSeeds: (Long, Long),     // (seed3, seed4) - applies to entire tree
  nTrials: Int,
  parallelism: Int,
  nodeProvenances: Map[String, NodeProvenance]  // riskId → per-node provenance
)
```

**Rationale:**
- Clean separation: Domain objects (RiskResult, SimulationResult) remain pure
- Optional: Can omit provenance for lightweight responses
- Composable: Easy to add/remove from API responses

---

## Reproduction Workflow

### To Reproduce a Simulation:

1. **Extract Provenance** from API response
2. **For each risk node**:
   - Use `riskId` to look up `NodeProvenance`
   - Reconstruct `RiskSampler` with exact parameters:
     ```scala
     RiskSampler.fromDistribution(
       entityId = provenance.entityId,
       riskId = provenance.riskId,
       occurrenceProb = /* from RiskNode */,
       lossDistribution = /* rebuild from distributionParams */,
       seed3 = provenance.globalSeed3,
       seed4 = provenance.globalSeed4
     )
     ```
3. **Run Simulator.simulateTree** with same `nTrials` and `parallelism`
4. **Verify**: Outcomes match original simulation exactly

---

## Example: Single Risk Provenance

```json
{
  "riskId": "cyber-attack",
  "entityId": 42,
  "occurrenceVarId": 1042,
  "lossVarId": 2042,
  "globalSeed3": 0,
  "globalSeed4": 0,
  "distributionType": "lognormal",
  "distributionParams": {
    "minLoss": 1000000,
    "maxLoss": 50000000,
    "confidenceInterval": 0.90
  },
  "timestamp": "2026-01-04T12:34:56Z",
  "simulationUtilVersion": "0.8.0"
}
```

**Reproduction**:
1. Hash "cyber-attack" → entityId = 42
2. Occurrence stream: HDR(trial, 42, 1042, 0, 0)
3. Loss stream: HDR(trial, 42, 2042, 0, 0)
4. Distribution: Lognormal from 90% CI [1M, 50M]
5. Run 10,000 trials → identical results

---

## Future: Risk Type Instances

### Motivation

Support multiple instances of the same **risk type** with different configurations:

- **Example**: Two "data breach" risks:
  - `redis-db-breach` - 10M records, high probability
  - `postgres-db-breach` - 100M records, low probability

### Current Limitation

Each risk is defined **inline** in the tree:
```json
{
  "RiskLeaf": {
    "id": "redis-breach",
    "name": "Redis Data Breach",
    "distributionType": "lognormal",
    "probability": 0.25,
    "minLoss": 1000000,
    "maxLoss": 10000000
  }
}
```

### Future Design: Risk Templates + Instances

**Phase 1: Define Risk Types (Templates)**
```scala
case class RiskType(
  typeId: String,              // "data-breach"
  name: String,                // "Data Breach"
  distributionTemplate: DistributionTemplate,
  probabilityRange: (Double, Double)  // Valid range for instances
)
```

**Phase 2: Create Risk Instances**
```scala
case class RiskInstance(
  instanceId: String,          // "redis-db-breach"
  riskType: String,            // "data-breach" (foreign key)
  customName: String,          // "Redis Database Breach"
  probability: Double,         // 0.25 (within typeId's range)
  distributionOverrides: Map[String, Any]  // Optional param overrides
)
```

**Phase 3: Reference Instances in Trees**
```json
{
  "RiskLeaf": {
    "id": "redis-breach",
    "riskInstanceRef": "redis-db-breach"  // Reference to RiskInstance
  }
}
```

**Benefits**:
- **Reusability**: Define "data breach" once, instantiate many times
- **Consistency**: All data breaches share same distribution family
- **Governance**: Central risk library with approval workflow
- **Provenance**: Track both template version AND instance parameters

**Implementation Timeline**: Post-MVP (after database persistence)

---

## Implementation Plan

### Phase 1: Core Infrastructure (After ZIO Prelude Migration)

**Step 1.1**: Define provenance data structures
- `NodeProvenance` case class
- `TreeProvenance` case class
- `DistributionParams` sealed trait hierarchy

**Step 1.2**: Add ZIO Prelude instances
- `Equal[NodeProvenance]`
- `Show[NodeProvenance]`
- `Debug[DistributionParams]`

**Step 1.3**: Test provenance serialization
- JSON codec tests (zio-json)
- Round-trip serialization
- Schema validation

### Phase 2: Capture During Simulation

**Step 2.1**: Extend `Simulator.createSamplerFromLeaf`
- Capture entityId, varIds, seeds
- Capture distribution parameters
- Return `(RiskSampler, NodeProvenance)` tuple

**Step 2.2**: Aggregate provenances during tree simulation
- Collect node provenances bottom-up
- Create `TreeProvenance` at root
- Pass through service layer to controller

**Step 2.3**: Test provenance capture
- Verify all parameters captured
- Test hierarchical aggregation
- Validate completeness

### Phase 3: API Integration

**Step 3.1**: Extend `RiskTreeWithLEC` response
- Add optional `provenance` field
- Update JSON codec

**Step 3.2**: Add query parameter
- `?includeProvenance=true` (default: false)
- Lightweight responses when not needed

**Step 3.3**: Update API documentation
- Document provenance fields
- Add reproduction examples

### Phase 4: Reproduction Validation

**Step 4.1**: Add reproduction test
- Capture provenance from simulation
- Reconstruct RiskSampler from provenance
- Verify identical outcomes

**Step 4.2**: Performance test
- Measure overhead of provenance capture
- Ensure < 1% performance impact

---

## Dependencies

**Requires**:
- ZIO Prelude migration (Equal, Show, Debug instances)
- No changes to HDR/Metalog wrappers (already correct)

**Enables**:
- Reproducible research
- Audit trails for risk simulations
- Debugging simulation discrepancies
- Future: Risk template/instance architecture

---

**Status**: Design approved, implementation scheduled after Phase 1 (ZIO Prelude Migration)
