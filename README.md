# Risk Register - Monte Carlo Simulation Engine

A production-grade Monte Carlo risk simulation service built with category theory foundations, providing hierarchical risk aggregation, mitigation strategy composition, and full reproducibility through provenance capture.

## Features

### Core Simulation Engine
- **Hierarchical Risk Trees**: Model complex risk portfolios with arbitrary nesting (RiskLeaf + RiskPortfolio nodes)
- **Sparse Storage**: Memory-efficient representation storing only non-zero losses per trial
- **Deterministic Parallelism**: Reproducible parallel execution using HDR PRNG with seed hierarchy
- **Distribution Support**: Expert opinion (Metalog) and lognormal distributions with BCG confidence interval parameterization

### Risk Mitigation Strategies
- **Composable Transformations**: Pure functional risk transforms
- **Mitigation Primitives**: Reduction, deductible, cap, layered coverage
- **Policy Aggregation**: Combine multiple mitigation strategies with mathematical guarantees

### Provenance & Reproducibility
- **Complete Metadata Capture**: HDR seed hierarchy (counter, entityId, varId, globalSeeds)
- **Distribution Parameters**: Full capture of expert percentiles/quantiles or lognormal bounds
- **JSON Serialization**: Export/import provenance for exact result reproduction
- **Optional Feature**: Zero overhead when disabled (default behavior)

### REST API
- **Tree Management**: CRUD operations for risk tree configurations
- **LEC Computation**: Execute simulations with configurable trials, parallelism, and depth
- **Query Parameters**: Control nTrials, tree depth, provenance capture
- **Discriminator Support**: JSON polymorphism for RiskLeaf vs RiskPortfolio nodes
- **OpenAPI/Swagger**: Auto-generated API documentation

### Loss Exceedance Curves
- **Vega-Lite Visualization**: Interactive exceedance probability curves
- **Hierarchical Views**: Compute curves at any tree depth (root-only, with children, full tree)
- **Depth Clamping**: Safety limits prevent excessive computation

## Design Principles

### Sound Simulation Architecture
- **Trusted Dependencies**: Leverages the purpose-built [`risquanter/simulation-util`](https://github.com/risquanter/simulation-util) library (HDR PRNG, Metalog fitting)
- **Defensive Validation**: Iron refinement types on the API boundaries to ensure probabilistic constraints before simulations
- **Sparse by Default**: Stores only trial outcomes where loss > 0, critical for low-probability risks

### Category Theory Foundations
- **Risk Aggregates with Identity property**: `RiskResult` combines portfolio losses associatively with outer join semantics. Guarantees aggregation order doesn't affect results (parallel chunks merge correctly).
- **Mitigation Composition with Identity property**: `RiskTransform` implements policy application as function composition. Order matters (deductible then cap ≠ cap then deductible), but associativity guarantees that grouping doesn't—enables safe batching and intermediate result caching.
- **Referential Transparency**: Pure functional simulation core enables parallel execution without race conditions, simplifies testing (same inputs → identical outputs), and supports equational reasoning about risk calculations.

### Property-Based Testing
- **3,200+ Property Checks**: Identity laws verified across 16 property tests × 200 random examples
- **Semantic Validity**: Generators produce domain-correct instances (e.g., trialId < nTrials)
- **Determinism Verification**: Parallel vs sequential execution produces identical results
- **Distribution Validation**: Statistical tests for uniformity, percentile accuracy, monotonicity

### Engineering Excellence
- **313 Test Suite**: 190 common + 123 server tests covering all core functionality
- **Explicit Type Safety**: Avoid implicit resolution ambiguities with explicit `Ord[Loss].toScala`
- **ZIO Effect System**: Structured concurrency, resource safety, composable error handling
- **Modular Architecture**: Common domain model separated from server orchestration

## Technical Stack

- **Language**: Scala 3.6.3
- **Effect System**: ZIO 2.1.14 + ZIO Prelude 1.0.0-RC44
- **Type Refinement**: Iron 2.6.0 (compile-time probability validation)
- **HTTP**: Tapir 1.11.11 (type-safe endpoints)
- **Serialization**: ZIO JSON 0.7.3
- **Persistence**: Doobie 1.0.0-RC7 (PostgreSQL)
- **Testing**: ZIO Test with property-based generators
- **Build**: sbt 1.12.0 with cross-compilation (JVM)

## Outstanding Features

### Visualization & UI
- **Enhanced Vega-Lite**: Multi-curve overlays, confidence bands, interactive tooltips
- **Web Frontend**: React/TypeScript UI for tree construction and simulation management
- **Real-time Updates**: WebSocket-based simulation progress tracking

### Production Operations
- **Containerization**: Docker images with multi-stage builds
- **Orchestration**: Kubernetes manifests with horizontal pod autoscaling
- **Observability**: OpenTelemetry instrumentation, structured logging, Prometheus metrics
- **CI/CD**: GitHub Actions pipeline with automated testing and deployment

### Advanced Aggregation
- **Sketch-Based Storage**: t-digest and KLL aggregators for 1M+ trial simulations
- **Memory-Constrained Modes**: Configurable exact vs approximate storage
- **Distributed Aggregation**: Identity-based merge for distributed Monte Carlo

### Risk Query Language
- **First-Order Logic Extension**: Vague quantifier support for risk queries (see [`risquanter/vague-quantifier-logic`](https://github.com/risquanter/vague-quantifier-logic))
- **Semantic Queries**: "Most cyber risks exceed $100K loss" with probabilistic semantics
- **Query Evaluation**: Proportion-based satisfaction over risk tree domains
- **Parser Integration**: FOL formula parser with custom risk predicates

## Architecture

```
modules/
  common/          # Domain model, distributions, type classes
    domain/
      data/        # RiskNode, RiskResult, Provenance, RiskTransform
      simulation/  # HDRWrapper, MetalogDistribution, LognormalDistribution
    http/          # Tapir endpoint definitions
  
  server/          # Service orchestration, HTTP server
    services/
      helper/      # Simulator (recursive tree execution)
      execution/   # SimulationExecutionService
      risktree/    # RiskTreeService (CRUD + LEC computation)
    http/          # RiskTreeController, endpoint implementations
  
  app/             # Main entry point, ZIO app composition
```

## Quick Start

```bash
# Run tests
sbt test

# Start server (requires PostgreSQL)
sbt app/run

# Access API documentation
open http://localhost:8080/docs
```

## Example Usage

### Create Risk Tree
```bash
curl -X POST http://localhost:8080/api/risk-trees \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Cyber Portfolio",
    "root": {
      "type": "portfolio",
      "id": "cyber-root",
      "name": "All Cyber Risks",
      "children": [
        {
          "type": "leaf",
          "id": "ransomware",
          "name": "Ransomware Attack",
          "distributionType": "lognormal",
          "probability": 0.15,
          "minLoss": 50000,
          "maxLoss": 5000000,
          "confidenceInterval": 0.90
        },
        {
          "type": "leaf",
          "id": "data-breach",
          "name": "Data Breach",
          "distributionType": "expert",
          "probability": 0.25,
          "percentiles": [0.1, 0.5, 0.9],
          "quantiles": [10000, 75000, 500000]
        }
      ]
    },
    "nTrials": 10000
  }'
```

### Compute Loss Exceedance Curve
```bash
curl -X POST "http://localhost:8080/api/risk-trees/1/compute-lec?nTrials=50000&depth=1&includeProvenance=true"
```

## License

Licensed under AGPL-3.0. Commercial licensing available for proprietary use — contact danago@risquanter.com to discuss terms.

## Contributing

The codebase is in early stages and evolving rapidly. External contributions are not currently accepted while core architecture stabilizes.

## References

- **Metalog Distribution**: Keelin, T.W. (2016). "The Metalog Distributions"
- **BCG Lognormal Parameterization**: "Measuring and Managing Information Risk: A FAIR Approach"
- **HDR PRNG**: Hubbard Decision Research deterministic random number generation
- **Vague Quantifiers**: Fermüller et al. (2016). "Many-valued semantics for vague quantifiers"
- **ZIO Prelude**: Functional abstractions with lawful type classes
