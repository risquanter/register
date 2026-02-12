# Risk Register - Monte Carlo Simulation Engine

A production-grade Monte Carlo risk simulation service built with category theory foundations, providing hierarchical risk aggregation, mitigation strategy composition, and full reproducibility through provenance capture.

## Current Features

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

### REST API & Real-Time Events
- **Tree Management**: CRUD operations for risk tree configurations
- **LEC Computation**: Execute simulations with configurable trials, parallelism, and depth
- **Server-Sent Events**: Real-time cache invalidation notifications via SSE
- **Discriminator Support**: JSON polymorphism for RiskLeaf vs RiskPortfolio nodes
- **OpenAPI/Swagger**: Auto-generated API documentation at `/docs`

### Loss Exceedance Curves
- **Hierarchical Views**: Compute curves at any tree depth (root-only, with children, full tree)
- **Depth Clamping**: Safety limits prevent excessive computation

### Web Frontend (In Progress)
- **Scala.js + Laminar**: Type-safe, reactive single-page application
- **Shared Endpoint Definitions**: Frontend and backend share Tapir endpoint types via cross-compiled `common` module
- **Risk Tree Builder**: Interactive form for constructing risk tree configurations
- **Backend Health Indicator**: Real-time connection status in the header

### Containerisation
- **GraalVM Native Image**: ~118 MB distroless image, ~9ms startup, ~53MB RAM
- **Docker Compose**: Single-command deployment with optional Irmin persistence
- **Non-root / Distroless**: Minimal attack surface — no shell, no package manager

## Design Principles

### Sound Simulation Architecture
- **Trusted Dependencies**: Leverages the purpose-built [`risquanter/simulation-util`](https://github.com/risquanter/simulation-util) library (HDR PRNG, Metalog fitting)
- **Defensive Validation**: Iron refinement types enforce constraints at service boundaries — raw DTOs validated before internal processing with refined types (`Probability`, `PositiveInt`, `SafeName`)
- **Sparse by Default**: Stores only trial outcomes where loss > 0, critical for low-probability risks

### Category Theory Foundations
- **Risk Aggregates with Identity property**: `RiskResult` combines portfolio losses associatively with outer join semantics. Guarantees aggregation order doesn't affect results (parallel chunks merge correctly).
- **Mitigation Composition with Identity property**: `RiskTransform` implements policy application as function composition. Order matters (deductible then cap ≠ cap then deductible), but associativity guarantees that grouping doesn't — enables safe batching and intermediate result caching.
- **Referential Transparency**: Pure functional simulation core enables parallel execution without race conditions, simplifies testing (same inputs → identical outputs), and supports equational reasoning about risk calculations.

### Testing
- **296+ Unit Tests**: Covering domain model, services, and server wiring
- **3,200+ Property Checks**: Identity laws verified across 16 property tests × 200 random examples
- **Determinism Verification**: Parallel vs sequential execution produces identical results
- **Distribution Validation**: Statistical tests for uniformity, percentile accuracy, monotonicity

## Technical Stack

- **Language**: Scala 3.6.4
- **Effect System**: ZIO 2.1.24 + ZIO Prelude 1.0.0-RC44
- **Type Refinement**: Iron 3.2.2 (compile-time constraint validation)
- **HTTP**: Tapir 1.13.4 (type-safe endpoints, shared between client and server)
- **Serialization**: ZIO JSON 0.8.0
- **Frontend**: Scala.js 1.20.0 + Laminar 17.2.0 + Vite 6.4.1
- **Persistence**: In-memory (default) or Irmin (versioned, content-addressable store)
- **Observability**: ZIO Telemetry 3.1.13 + OpenTelemetry (optional)
- **Testing**: ZIO Test with property-based generators
- **Build**: sbt with cross-compilation (JVM + JS via `common` crossProject)

## Planned Features

### Visualization
- **Enhanced Vega-Lite**: Multi-curve overlays, confidence bands, interactive tooltips
- **Split-Pane Layout**: Side-by-side tree builder and simulation results

### Advanced Aggregation
- **Sketch-Based Storage**: t-digest and KLL aggregators for 1M+ trial simulations
- **Memory-Constrained Modes**: Configurable exact vs approximate storage
- **Distributed Aggregation**: Identity-based merge for distributed Monte Carlo

### Risk Query Language
- **First-Order Logic Extension**: Vague quantifier support for risk queries (see [`risquanter/vague-quantifier-logic`](https://github.com/risquanter/vague-quantifier-logic))
- **Semantic Queries**: "Most cyber risks exceed $100K loss" with probabilistic semantics
- **Query Evaluation**: Proportion-based satisfaction over risk tree domains

## Architecture

```
modules/
  common/          # Shared domain model (cross-compiled JVM + JS)
    domain/
      data/        # RiskNode, RiskResult, Provenance, RiskTransform
      simulation/  # HDRWrapper, MetalogDistribution, LognormalDistribution
    http/          # Tapir endpoint definitions (shared between server & frontend)

  server/          # Backend — ZIO HTTP server (JVM only)
    services/      # Simulator, RiskTreeService, caching, invalidation
    http/          # Controllers, SSE, cache management

  app/             # Frontend — Scala.js SPA (JS only)
    core/          # ZJS bridge, BackendClient, constants
    views/         # TreeBuilderView, PortfolioFormView, RiskLeafFormView
    components/    # Header, Layout, FormInputs
    state/         # Reactive state (Laminar Var-based)
```

## Getting Started

For setup, development workflow, and Docker commands, see the **[Docker & Development Guide](docs/DOCKER-DEVELOPMENT.md)**.

Quick smoke test:

```bash
# Start the backend
docker compose up -d

# Check health
curl http://localhost:8080/health

# API docs
open http://localhost:8080/docs
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
