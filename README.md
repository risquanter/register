# Risquanter Register

A quantitative risk analysis tool. Domain experts build hierarchical risk trees — each leaf is a parameterised risk event (occurrence probability + loss range expressed as a lognormal or metalog distribution), portfolio nodes aggregate their children. The server runs Monte Carlo simulations and reports Loss Exceedance Curves (LECs) and overview statistics at every level of the hierarchy, from individual risk events to the enterprise total.

---

## Features

### Risk Tree Modelling

Users describe their risk taxonomy as a tree. Interior nodes (**portfolios**) correspond to organisational groupings such as business units, geographical regions, or operational categories. Leaf nodes represent individual risk events.

The interface is intentionally non-statistical. A subject-matter expert who has never heard of a lognormal distribution or a metalog can fully parameterise a risk model. Each leaf node requires only two inputs from the expert's own vocabulary:

**Occurrence probability** — how likely is this event to occur in the modelling period? Expressed as a plain probability, e.g. `0.15` for a 15 % chance per year.

**Loss range** — in one of two modes, depending on how the expert best articulates their uncertainty:

- **Confidence interval mode** — *"When this risk occurs, I'm 90 % confident the financial impact falls somewhere between $X and $Y."* Supply the lower and upper bounds; the system derives the loss distribution internally. This is the natural language of insurance and risk committees: a best-case and a credible worst-case, with no assumptions beyond that losses of this type tend to be right-skewed.

- **Quantile mode** — *"I can tell you that in a quarter of occurrences losses stay below $A, in half below $B, and only one time in ten would I expect to see losses above $C."* Supply as many or as few percentile-quantile pairs as the expert can reasonably support (minimum two). The system fits a flexible distribution that honours every stated point exactly, without imposing any parametric shape.

If you know roughly what a bad year looks like and what a catastrophic year looks like, confidence interval mode gets you running in seconds. If you have a richer picture of the loss landscape — a view on the median, a tail estimate, perhaps a regulatory threshold — quantile mode lets you encode all of it directly. Either way, no distribution theory is required.

### Monte Carlo Simulation & Loss Exceedance Curves

Each leaf is sampled independently across N trials (default 10 000). Portfolio results are composed by *trial-aligned summation*: the portfolio's loss on trial 7 equals the sum of every child's loss on trial 7. This is mathematically exact — it preserves the joint distribution without re-sampling or distribution-fitting, correctly modelling co-occurrence structure. The server computes Loss Exceedance Curves (LECs) — "what is the probability of losing more than $X?" — and P50/P90/P95/P99 quantile statistics for every node in the tree.

### Incremental Re-Simulation

Only the edited node and its ancestors are re-simulated when a parameter changes; siblings and unrelated subtrees are served from a content-addressed [Merkle tree](https://en.wikipedia.org/wiki/Merkle_tree) cache. Editing a single leaf's probability produces updated results within seconds, not minutes.

### Scenario Branching

The storage layer is [Irmin](https://irmin.org/) — a Git-like content-addressed store. Branch creation is O(1); two scenarios sharing 90 % of their tree share 90 % of their storage and their cached computation. Merging a scenario back into the main model is a supported operation with 3-way merge semantics. The complete edit history is preserved as immutable commits, enabling time travel and revert.

### Vague Quantifier Queries

Analysts can ask proportional screening questions across a live risk tree using a first-order logic query language with *vague quantifiers* (based on Fermüller, Hofer & Ortiz, FQAS 2017):

```
Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))
```

*"Do at least two-thirds of leaf risks have a P95 loss above $5 M?"*

Queries are evaluated server-side against the current simulation cache. Available terms include structural predicates (`leaf`, `portfolio`, `child_of`, `descendant_of`) and simulation-backed functions (`p50`, `p90`, `p95`, `p99`, `lec`). Query syntax errors are reported at parse time with position information.


---

## How It Works

### Technology Stack

| Layer | Technology |
|---|---|
| Backend language | Scala 3 / JVM |
| Effect system | ZIO 2 |
| HTTP framework | Tapir + ZIO-HTTP (Netty) |
| Storage | Irmin 3.11 (OCaml, accessed via GraphQL) |
| Frontend | Scala.js + Laminar + Vite |
| Simulation | `risquanter/simulation-util` (HDR counter PRNG, QPFitter metalog) |
| Serialisation | zio-json + Iron refinement types |
| Production image | GraalVM Native Image on distroless (~118 MB, ~9 ms startup) |

### Module Layout

```
modules/
  common/     — shared domain types, DTOs, endpoint definitions (cross-compiled JVM + JS)
  server/     — JVM backend: simulation, storage, HTTP API
  app/        — Scala.js frontend (Vite)
  server-it/  — integration test suite (in-process stub server)
containers/
  prod/       — Dockerfiles for production images (register server, Irmin, nginx frontend)
  dev/        — Dockerfiles for local development images
  builders/   — OCaml/Irmin builder base image (build once, cache locally)
```

### Key Architectural Decisions

- **Flat Irmin storage, JVM Merkle cache**: Risk nodes are stored at flat paths (`risk-trees/{id}/nodes/{nodeId}`) so that reparenting is O(1). A separate JVM-side [Merkle tree](https://en.wikipedia.org/wiki/Merkle_tree) computes hierarchy-aware content hashes for cache keying — these are independent of Irmin's internal hashes.
- **Counter-based PRNG (HDR / Splitmix64)**: `f(trial_id, risk_id, seeds) → uniform` — fully deterministic, order-independent, parallelisable without mutable shared state.
- **Sparse trial storage**: A risk with 1 % occurrence probability stores ~100 non-zero entries per 10 000 trials, not 10 000.
- **PEP-only authorization (Policy Enforcement Point)**: The application asks external policy systems whether an action is allowed, then enforces allow/deny outcomes. It does not issue grants/revocations or manage relationship tuples itself (see [Operations & Authorization](#operations--authorization)).
- **Cross-compiled API contracts**: The frontend and backend share endpoint/type definitions via `modules/common`, keeping request/response contracts in one place and reducing drift between client and server.
- **Computation-heavy backend, reactive frontend**: Simulation, aggregation, query evaluation, and authorization remain server-side; the frontend is not "thin" in UX terms — it manages reactive chart/query/tree state and builds Vega-Lite specs client-side from structured LEC data.
- **API-first operations and observability**: REST endpoints are documented via OpenAPI/Swagger (`/docs`), health/readiness probes are exposed on port `8091`, and tracing/metrics are instrumented with OpenTelemetry and configurable OTLP export.

---

## Running an Instance

### Prerequisites

- Docker 20.10+ and Docker Compose 2.0+
- At least 4 GB RAM available to Docker

### One-Time: Build the Irmin Builder Image

Irmin is a Git-like, content-addressed, versioned data store. Register uses it as the domain content store for risk trees so each change is auditable and historical states remain queryable.

The Irmin base image is not published to any registry and must be built locally before the first production build. This compiles the OCaml toolchain and several opam packages from source — allow 15–40 minutes on first run:

```bash
docker build \
  -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 \
  containers/builders/
```

The resulting image is cached locally and survives `docker builder prune`.

### Build Production Images

```bash
# Build both the register server (GraalVM native image) and Irmin images
docker compose \
  --profile persistence \
  --profile frontend \
  build
```

### Start the Full Stack

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  up -d
```

This starts:
- **Irmin** (port 9080) — content-addressed persistence layer
- **register-server** (port 8090 API, 8091 health probes) — the simulation backend
- **nginx** (port 18080) — serves the compiled SPA and proxies API calls

Access the application at **`http://localhost:18080`**.

API documentation (Swagger UI) is available at **`http://localhost:8090/docs`**.

### Configuration

All configuration is driven by environment variables. The key variables with their defaults:

| Variable | Default | Description |
|---|---|---|
| `REGISTER_REPOSITORY_TYPE` | `in-memory` | Set to `irmin` for persistent storage |
| `IRMIN_URL` | `http://irmin:8080` | Irmin GraphQL endpoint |
| `REGISTER_AUTH_MODE` | `capability-only` | Authorization layer: `capability-only`, `identity`, or `fine-grained` |
| `REGISTER_DEFAULT_NTRIALS` | `10000` | Monte Carlo trial count |
| `REGISTER_WORKSPACE_TTL` | `72h` | Workspace absolute expiry |
| `REGISTER_WORKSPACE_IDLE_TIMEOUT` | `1h` | Workspace idle expiry |
| `REGISTER_CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed origins |

To start the server without persistent storage (all state is in-memory and lost on restart):

```bash
docker compose up -d register-server
```

---

## Development Setup

### Prerequisites

- JDK 21
- sbt (Scala Build Tool)
- Node.js 18+ and npm
- Docker 20.10+ and Docker Compose 2.0+

### Local environment templates

Use one of the checked-in env templates, copy it to a local file, and adjust values for your machine before starting services:

```bash
# In-memory backend (default local workflow)
cp .env.inmemory.example .env.inmemory

# Irmin-backed backend (persistent local workflow)
cp .env.irmin.example .env.irmin
```

Template files:
- `.env.inmemory.example` → sets `REGISTER_REPOSITORY_TYPE=in-memory`
- `.env.irmin.example` → sets `REGISTER_REPOSITORY_TYPE=irmin` and `IRMIN_URL=http://irmin:8080`

Run Compose with the selected env file:

```bash
# In-memory
docker compose --env-file .env.inmemory up -d register-server

# Irmin + PostgreSQL persistence services
docker compose --env-file .env.irmin --profile persistence up -d register-server irmin postgres
```

You can additionally set local values such as `REGISTER_CORS_ORIGINS` in these files when running the frontend from a non-default host/origin.

### Backend (JVM, watch mode)

The fastest local development loop runs the backend container from a pre-built production image while recompiling the frontend with Vite HMR:

```bash
# Terminal 1 — start the backend (in-memory storage, no Irmin needed)
docker compose up -d register-server

# Terminal 2 — Scala.js watch compiler
sbt '~app/fastLinkJS'

# Terminal 3 — Vite dev server with HMR
cd modules/app && npm run dev
```

Access at **`http://localhost:5173`**.

Note that in Vite mode the browser makes API calls directly to `http://localhost:8090` (two origins), so CORS is enforced. The default `REGISTER_CORS_ORIGINS` already includes `localhost:5173`. If your backend runs on a remote machine, export the Vite origin explicitly before starting the server:

```bash
REGISTER_CORS_ORIGINS=http://<your-machine>:5173 docker compose up -d register-server
```

### With Irmin Persistence

To use the persistent Irmin backend during development, first build the Irmin builder image (see [Running an Instance](#running-an-instance)), then:

```bash
docker compose --profile persistence up -d register-server irmin postgres
```

To enable the PostgreSQL-backed workspace store as well, export:

```bash
export REGISTER_WORKSPACE_STORE_BACKEND=postgres
```

### Running Tests

```bash
# Unit and integration tests (JVM)
sbt test

# Server integration tests
sbt serverIt/test
```

### Full prod-equivalent stack locally (nginx)

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  up -d

# Nginx serves the SPA and proxies API — no CORS, no Vite
open http://localhost:18080
```

---

## Operations & Authorization

### Layered Authorization Model

Register is designed to serve three distinct deployment contexts from one codebase, selected by a single configuration value (`register.auth.mode`). The authorization model is **additive** — each layer adds a gate on top of the previous one without replacing it.

```
Layer 0 — Workspace Capability (free tier / demo)
  The workspace URL contains a 128-bit SecureRandom token.
  Knowledge of the URL = access. No login required.
  Workspaces expire automatically (absolute TTL + idle timeout).
  Rate limiting prevents enumeration.

Layer 1 — Identity (team tier)
  Key + valid JWT from a Keycloak realm = access.
  Pattern: "anyone with the link who is signed in" (like Google Docs).
  A leaked URL is useless without a valid session.
  JWT validation is handled by the service mesh (Istio); the application
  only reads injected headers — no JWT parsing code in the app.

Layer 2 — Fine-Grained Authorization (enterprise tier)
  Key + JWT + explicit SpiceDB relationship = access.
  Per-resource roles (editor, analyst, viewer) with org → team → workspace
  → tree inheritance.
  Relationships are administered externally (CI/CD provisioning or the
  zed CLI) — the application is a pure Policy Enforcement Point (PEP)
  and contains no grant/revoke endpoints.
```

Fail-closed by design: if the identity service or SpiceDB are unreachable, access is denied. There is no fallback to a less restrictive mode.

### Incremental Complexity

The system is designed to be deployed with as much or as little infrastructure as you need:

| Profile | Config | Required infrastructure |
|---|---|---|
| Free tier / demo | `capability-only` | None beyond the application itself |
| Team | `identity` | Keycloak + Istio service mesh |
| Enterprise | `fine-grained` | Keycloak + Istio + SpiceDB |

**The currently open-sourced version of Register ships with full Layer 0 support.** Layer 0 provides a complete, production-ready deployment profile with automatic workspace expiry, rate limiting, key rotation, and all simulation and query features.

Layer 1 (Keycloak identity) and Layer 2 (SpiceDB fine-grained ACL) infrastructure and application wiring are implemented in the codebase and documented in [docs/AUTHORIZATION-PLAN.md](docs/AUTHORIZATION-PLAN.md), but their supporting infrastructure components (Keycloak realm provisioning, Istio mesh configuration, SpiceDB schema and CI/CD provisioning) are planned to be open-sourced as a separate repository upon completion and release.

---

## API Quick-Start (httpie)

The following example bootstraps a workspace containing a risk tree that models a simple operational risk portfolio, then runs a vague quantifier query against the simulation results. Replace `localhost:8090` with your server address.

> **Note:** The `workspaceKey` returned by the bootstrap step is a 128-bit capability token embedded in every subsequent URL. Treat it like a shared secret.

### Leaf Risk Parameters: A Modeller's Guide

Every leaf node requires three things: a name, an occurrence probability, and a loss characterisation. The loss characterisation comes in two forms — pick whichever matches how your expert articulates their knowledge.

#### Confidence interval mode (`distributionType: "lognormal"`)

Use this when your expert can bound the loss outcome with a confidence statement. The two fields are:

| Field | What it means |
|---|---|
| `probability` | The chance this risk event occurs in the modelling period. `0.20` means a 1-in-5 chance per year. |
| `minLoss` | The lower end of a **90 % confidence interval** on loss size — "when this event occurs, I'd be surprised if losses were below this." |
| `maxLoss` | The upper end of that same interval — "when this event occurs, I'd be surprised if losses exceeded this." |

Concretely: a cyber breach team says *"we think there's a 20 % chance of a breach this year; if one happens, we're 90 % confident the financial damage falls somewhere between $500 K and $8 M."*

```json
{
  "name": "Cyber Breach",
  "distributionType": "lognormal",
  "probability": 0.20,
  "minLoss": 500000,
  "maxLoss": 8000000,
  "percentiles": null,
  "quantiles": null
}
```

The system constructs a right-skewed loss distribution from those two bounds. Nothing else is needed. The simulation will reflect not just the expected loss but the full spread — including the tail scenarios that matter for capital planning.

#### Quantile mode (`distributionType: "expert"`)

Use this when your expert can speak to several points on the loss landscape, not just the credible range. The two fields are:

| Field | What it means |
|---|---|
| `probability` | The chance this risk event occurs, same as above. |
| `percentiles` | The probability levels your expert is making statements at, as decimal fractions. `[0.25, 0.50, 0.75, 0.95]` means the 25th, 50th, 75th, and 95th percentiles of the conditional loss distribution. |
| `quantiles` | The loss amounts at each of those percentiles, in the same order. |

Concretely: a supply chain analyst says *"there's a 10 % chance of a major disruption this year; if it happens, a quarter of the time losses stay below $200 K, half the time below $1 M, three-quarters of the time below $4 M, and only one time in twenty would I expect to see losses beyond $15 M."*

```json
{
  "name": "Ransomware",
  "distributionType": "expert",
  "probability": 0.10,
  "minLoss": null,
  "maxLoss": null,
  "percentiles": [0.25, 0.50, 0.75, 0.95],
  "quantiles":   [200000, 1000000, 4000000, 15000000]
}
```

The system fits a flexible distribution that passes exactly through every stated point, honouring the expert's full picture of the loss landscape without imposing any parametric assumption.

**Minimum requirement:** two percentile-quantile pairs. A view on the median and the 95th percentile is already enough. Add more pairs wherever the expert has a well-formed opinion — each additional point sharpens the fitted shape.

---

### 1. Bootstrap a Workspace & First Tree

Create a workspace with a two-level risk tree: one portfolio ("Operations") containing three leaf risks — a cyber breach (lognormal distribution), a supply chain disruption (expert quantiles), and a regulatory fine (lognormal distribution):

```bash
http POST localhost:8090/workspaces \
  name="Operational Risk Model" \
  portfolios:='[
    {"name": "Operations", "parentName": null},
    {"name": "IT Risk",    "parentName": "Operations"},
    {"name": "Third Party Risk", "parentName": "Operations"}
  ]' \
  leaves:='[
    {
      "name": "Cyber Breach",
      "parentName": "IT Risk",
      "distributionType": "lognormal",
      "probability": 0.20,
      "minLoss": 500000,
      "maxLoss": 8000000,
      "percentiles": null,
      "quantiles": null
    },
    {
      "name": "Ransomware",
      "parentName": "IT Risk",
      "distributionType": "expert",
      "probability": 0.10,
      "minLoss": null,
      "maxLoss": null,
      "percentiles": [0.25, 0.50, 0.75, 0.95],
      "quantiles":   [200000, 1000000, 4000000, 15000000]
    },
    {
      "name": "Supply Chain Disruption",
      "parentName": "Third Party Risk",
      "distributionType": "lognormal",
      "probability": 0.15,
      "minLoss": 300000,
      "maxLoss": 3000000,
      "percentiles": null,
      "quantiles": null
    },
    {
      "name": "Regulatory Fine",
      "parentName": "Third Party Risk",
      "distributionType": "lognormal",
      "probability": 0.08,
      "minLoss": 100000,
      "maxLoss": 2000000,
      "percentiles": null,
      "quantiles": null
    }
  ]'
```

The response contains your workspace key and the auto-assigned tree ID:

```json
{
  "workspaceKey": "aB3x7kLm2Pq9RwZvNsYt8u",
  "tree": { "id": "01J...", "name": "Operational Risk Model", ... },
  "expiresAt": "2026-04-22T10:00:00Z"
}
```

Store both values — every subsequent request uses them:

```bash
export WS_KEY=aB3x7kLm2Pq9RwZvNsYt8u
export TREE_ID=01J...
```

### 2. Fetch Loss Exceedance Curves

```bash
http GET "localhost:8090/w/$WS_KEY/risk-trees/$TREE_ID/lec"
```

Returns pre-computed LEC curve points and P50/P90/P95/P99 quantile statistics for every node in the tree.

### 3. Run a Vague Quantifier Query

Ask whether most of the leaf risks in the tree have a tail (P95) loss exceeding $2 M:

```bash
http POST "localhost:8090/w/$WS_KEY/risk-trees/$TREE_ID/query" \
  query='Q[>=]^{1/2} x (leaf(x), >(p95(x), 2000000))'
```

This query reads: *"Do at least half of all leaf risks have a P95 loss above \$2 M?"*

A more targeted variant scoped to IT risks only:

```bash
http POST "localhost:8090/w/$WS_KEY/risk-trees/$TREE_ID/query" \
  query='Q[>=]^{2/3} x (leaf_descendant_of(x, "IT Risk"), >(p95(x), 1000000))'
```

*"Do at least two-thirds of IT Risk leaf descendants have P95 above \$1 M?"*

The response includes the quantifier satisfaction result, the proportion of matching elements, and the set of node IDs that satisfy the predicate (for frontend tree highlighting):

```json
{
  "satisfied": true,
  "proportion": 0.667,
  "satisfyingNodeIds": ["<Cyber Breach node id>", "<Ransomware node id>"],
  "rangeSize": 3,
  "query": "Q[>=]^{2/3} x (leaf_descendant_of(x, \"IT Risk\"), >(p95(x), 1000000))"
}
```

#### Query Language Reference

| Syntax element | Meaning |
|---|---|
| `Q[>=]^{2/3} x (range(x), pred(x))` | True when at least 2/3 of elements in `range` satisfy `pred` |
| `Q[>]^{1/2}`, `Q[<=]^{3/4}` | Other proportional thresholds |
| `leaf(x)` | x is a leaf risk node |
| `portfolio(x)` | x is a portfolio node |
| `child_of(x, "Parent Name")` | x is a direct child of the named node |
| `leaf_descendant_of(x, "Name")` | x is a leaf anywhere under the named node |
| `p50(x)`, `p95(x)`, `p99(x)` | Quantile loss value for node x |
| `lec(x, 1000000)` | Exceedance probability at \$1 M for node x |
| `>(p95(x), 5000000)` | P95 loss exceeds \$5 M |

---

## License

Licensed under AGPL-3.0. Commercial licensing available for proprietary use — contact danago@risquanter.com to discuss terms.
