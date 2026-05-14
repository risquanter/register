# Risquanter Register

[![Last commit](https://img.shields.io/github/last-commit/risquanter/register)](https://github.com/risquanter/register/commits/main) [![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE.md)

A quantitative risk analysis tool. Domain experts build hierarchical risk trees — each leaf is a parameterised risk event (occurrence probability + loss range expressed as a log-normal or metalog distribution), portfolio nodes aggregate their children. The server runs Monte Carlo simulations and reports Loss Exceedance Curves (LECs) and overview statistics at every level of the hierarchy, from individual risk events to the enterprise total.

---

## Features

### Risk Tree Modelling

Users describe their risk taxonomy as a tree. Interior nodes (**portfolios**) can be used to model organisational groupings such as business units, geographical regions, or operational categories. Most often they are used to represent aggregations of risk events, forming a hierarchical structure of risk portfolios. Leaf nodes represent individual risk events. Portfolios aggregate the losses of their children, so the tree structure encodes how risks combine and interact across the organisation.

The interface is intentionally non-statistical. A subject-matter expert who has never heard of a lognormal distribution or a metalog can fully parameterise a risk model. Each leaf node requires only two inputs from the expert's own vocabulary:

**Occurrence probability** — how likely is this event to occur in the modelling period? Expressed as a plain probability, e.g. `0.15` for a 15 % chance per year.

**Loss range** — in one of two modes, depending on how the expert best articulates their uncertainty:

- **Confidence interval mode** — *"When this risk occurs, I'm 90 % confident the financial impact falls somewhere between $X and $Y. There can be rare events below $X and in case of $Y even way above in very rare extreme cases, but the overwhelming majority of losses will fall within this range."* Supply the lower and upper bounds; the system derives the loss distribution internally. This is the natural language of insurance and risk committees: a best-case and a credible worst-case, with no assumptions beyond that losses of this type tend to be right-skewed and are always positive. Thus we will never get a "negative loss", and we expect a long tail describing the possibility of rare but severe events beyond the upper bound. 

- **Quantile mode** — *"Nine times out of ten **(P90)**, losses stay below $C. About half the time **(P50)**, they stay below $B. Only in one case out of ten **(P10)** would losses be as low as $A."* Supply as many or as few percentile-quantile pairs as the expert can reasonably support (minimum two, three are common). The system fits a flexible distribution that honours every stated point exactly, without imposing any parametric shape.

If you know roughly what a bad year looks like and what a catastrophic year looks like, confidence interval mode gets you running in seconds. If you have a richer picture of the loss landscape — a view on the median, a tail estimate, perhaps a regulatory threshold — quantile mode lets you encode all of it directly. Either way, no distribution theory is required.

### Monte Carlo Simulation & Loss Exceedance Curves

Each leaf is sampled independently across N trials (default 10 000). Portfolio results are composed by *trial-aligned summation*: the portfolio's loss on trial 7 equals the sum of every child's loss on trial 7. This is mathematically exact — it preserves the joint distribution without re-sampling or distribution-fitting, correctly modelling co-occurrence structure. The N trials can be thought of as simulations of N parallel worlds or simulating the occurances over N years. The server computes from this simulated data various statistics and so called Loss Exceedance Curves (LECs). These curves map loss-thresholds to probabilities, answering the question "what is the probability of losing more than $X?" considering a risk or a risk portfolio as a whole. The LEC is the central output of the system, and can be inspected at any level of the hierarchy — from individual risk events to the enterprise total. This allows analysts to identify dominant risk drivers and understand how risks combine across the organisation.

### Incremental Re-Simulation

Only the edited node and its ancestors are re-simulated when a parameter changes; siblings and unrelated subtrees are served from a content-addressed [Merkle tree](https://en.wikipedia.org/wiki/Merkle_tree) cache. Editing a single leaf's probability produces updated results within milliseconds, not minutes.


### Vague Quantifier Queries

Analysts can ask proportional screening questions across a live risk tree using a first-order logic query language with *vague quantifiers* (based on Fermüller, Hofer & Ortiz, FQAS 2017):

```
Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x), 5000000))
```

*"Do at least two-thirds of all leaf risks carry a loss above $5M in 1-in-20 occurrences?"*

Queries are evaluated server-side against the current simulation cache. Available terms include structural predicates (`leaf`, `portfolio`, `child_of`, `descendant_of`, `leaf_descendant_of`) and simulation-backed functions (`p95`, `p99`, `lec`). Query syntax errors are reported at parse time with position information.


### Scenario Branching (next planned feature)

The storage layer is [Irmin](https://irmin.org/) — a Git-like content-addressed store. Branch creation is O(1); two scenarios sharing 90 % of their tree share 90 % of their storage and their cached computation. Merging a scenario back into the main model is a supported operation with 3-way merge semantics. The complete edit history is preserved as immutable commits, enabling time travel and revert.

---


## Getting Started (in-memory storage)

Risquanter is a source-only project — there are no published binary releases or pre-built container images. Everything is built locally from source. This will change after the first stable release, but for now the quickest way to get up and running is to follow the instructions below to build the container images yourself. The resulting stack is production-equivalent — the same application binary as would run in a cloud deployment, just with a different configuration and without the orchestration layer.

Register runs in two modes: in-memory storage for quick local trials, and a persistent mode backed by Irmin. The instructions below cover in-memory; for Irmin persistence see [docs/user/PERSISTENT-SETUP.md](docs/user/PERSISTENT-SETUP.md).

### Prerequisites

- Docker 20.10+ and Docker Compose 2.0+
- Git

### 1. Check out the sources

```bash
git clone https://github.com/risquanter/register.git
git clone https://github.com/risquanter/vague-quantifier-logic.git
git clone https://github.com/risquanter/hdr-rng.git
cd register
```

### 2. Configure the environment

The server defaults to in-memory storage with no extra configuration. Copy the bundled in-memory templates for the default values for the environment variables:

```bash
cp .env.inmemory.example .env.inmemory
```

Review `.env.inmemory` — the defaults are usable as-is for a local trial. See `docs/user/DOCKER-DEVELOPMENT.md` and `docs/dev/ADR-016-config-management.md` for the full variable reference, including the Irmin-backed persistence option.

### 3. Build the container images

Builder base images install heavyweight toolchains (GraalVM, sbt) once and are reused for all subsequent application builds. Build in this order:

```bash
# GraalVM builder base — installs GraalVM native-image + sbt (~10-20 min)
# Context is the register projects parent directory — sibling repos vague-quantifier-logic/ and hdr-rng/ must be at ../
# If you used the above commands to clone the repos, you need to run a "cd .." to be in the correct directory before running this command
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 ..

# Register server — compiles GraalVM native binary (~5-10 min)
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:0.1.0 .

# Frontend — builds Scala.js + SPA, packages in nginx (~10-15 min first run)
# Context is the parent directory — sibling repos vague-quantifier-logic/ and hdr-rng/ must be at ../
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:0.1.0 ..
```

### 4. Start the stack

```bash
docker compose --profile frontend up -d
```

The application is available at **`http://localhost:18080`**.

> The `examples/` directory contains curl-based API scripts for direct backend testing. For advanced configuration, observability integration, and Kubernetes deployment, see `docs/user/DOCKER-DEVELOPMENT.md`.

---

## Using Register

Once the stack is running, open **`http://localhost:18080`** to access the application. Register has two main views.

### Design view

The Design view is where a risk expert builds and maintains the risk hierarchy. A risk tree is composed of:
- A root node representing the entire enterprise (unnamed and mandatory parent of the first node)
- **Portfolio nodes** — internal nodes that aggregate child risk categories. Each portfolio's loss distribution is the statistical convolution of all its descendants, computed via Monte Carlo simulation. If you want to model more than one risk you need at least one portfolio to hold them. Portfolios can be used to represent organisational groupings such as business units, geographical regions, or operational categories. They can also be used to represent aggregations of risk events, forming a hierarchical structure of risk portfolios. Portfolios have no parameters of their own — their loss distribution is entirely derived from their children.
- **Leaf nodes** — terminal risk items where the quantitative parameters are specified directly:
  - **Occurrence probability** — the annual probability that the risk event occurs
  - **Loss distribution** — the conditional severity given an event occurs, specified as either:
    - **Confidence interval mode** — parameterised by a lower and upper bound representing a 90 % credible range on conditional loss, underpinned by a log-normal distribution
    - **Quantile mode** (also called **expert opinion mode**) — parameterised by percentile–loss pairs, underpinned by a flexible metalog distribution

    See [Parameterising a leaf node](#parameterising-a-leaf-node) below for full details on each mode.

#### Parameterising a leaf node

Each leaf requires an **occurrence probability** — the annual chance the event occurs, entered as a number between 0 and 1 (e.g. `0.20` for a 20 % chance per year) — and a **loss characterisation** in one of two modes:

**Confidence interval mode** is the right choice when your expert can give you a credible range. You enter a lower bound and an upper bound representing a 90 % confidence interval on the loss conditional on the event occurring — *"if a breach happens, we're 90 % confident the financial damage falls somewhere between $500 K and $8 M."* The system derives a right-skewed loss distribution from those two numbers. This is the natural language of insurance and risk committees, and requires no statistical knowledge.

**Quantile mode** is the right choice when your expert can speak to several points on the loss landscape. You enter a list of percentile levels and the corresponding loss amounts; the system fits a flexible distribution that passes exactly through every stated point without imposing any parametric shape. *"Only one time in twenty **(P95)** would losses exceed $15 M. About half the time **(P50)** they stay below $1 M. Only in one case out of four **(P25)** are losses as low as $200 K."* translates directly into three pairs. The minimum is two pairs; a median and a 95th percentile is already enough to get started.

If you know roughly what a bad year and a catastrophic year look like, confidence interval mode gets you running in seconds. If you have a richer picture of the loss landscape — a view on the median, a tail estimate, perhaps a regulatory threshold — quantile mode lets you encode all of it directly.

Branches can be nested to arbitrary depth, enabling fine-grained decomposition (for example, *Third Party Risk → Supplier Concentration → Single-Source Critical Components*).

### Analyze view

The Analyze view is the primary workspace for risk quantification. It operates against Register's Monte Carlo simulation engine. The subject of an analysis session is a single tree from the Design view. Available trees can be selected from a dropdown menu. 

This view is centered around the concept of **Loss Exceedance Curves (LECs)**. Selecting (Ctrl + click) any node in the tree triggers a simulation run and renders the Loss Exceedance Curve (LEC) for that subtree: the curve describes the probability that aggregate annual loss from that branch exceeds any given threshold. The LEC can be inspected at any level of the hierarchy and compared across sibling branches to identify dominant risk drivers.

LECs can also be generated by executing **VQL Queries** (Vague Query Language; the Risquanter internal DSL based on first order logic). VQL queries return the set of nodes satisfying the expression and these get added to the LEC view. Nodes selected manually from the tree and nodes returned by VQL queries can be compared side-by-side in the LEC view, enabling analysts to understand the risk properties of the nodes returned by a query in the context of the overall tree. 

---

## VQL Query Examples

Register's query language is the **Vague Query Language (VQL)**, a proportional first-order logic dialect with fuzzy quantifiers. Every query has the shape `Q[op]^{p/q} x (range(x), predicate(x))` where `op` is `>=` (at least), `<=` (at most), or `~` (approximately).

*"Do at least a third of all leaves carry a loss above $5M in 1-in-100 occurrences?"* — satisfied (2 of 4 leaves in the simple tree):

```
Q[>=]^{1/3} x (leaf(x), gt_loss(p99(x), 5000000))
```

*"Do at least half of IT Risk's leaves carry a loss above $2M in 1-in-20 occurrences?"* — scoped to a named branch:

```
Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 2000000))
```

For the full query reference — all operators, predicates, fuzzy quantifier, nested quantifiers, and annotated PASS/FAIL contrast pairs — see [docs/user/VQL-QUERY-EXAMPLES.md](docs/user/VQL-QUERY-EXAMPLES.md).

---

## Development Setup

For the full local development workflow — Scala.js watch mode, Vite HMR, Irmin persistence during development, running tests, and the prod-equivalent nginx stack — see [docs/user/DEVELOPMENT-SETUP.md](docs/user/DEVELOPMENT-SETUP.md).

---

## Operations & Authorization

### Layered Authorization Model

Risquanter is designed to serve three distinct deployment contexts from one codebase, selected by a single configuration value (`register.auth.mode`). The authorization model is **additive** — each layer adds a gate on top of the previous one without replacing it.

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

Fail-closed by design: SpiceDB errors (timeout, 5xx, network failure) are mapped to 403 — never 503, which would reveal that SpiceDB is down. JWT validation at Layer 1 is enforced by the Istio service mesh before requests reach the application. Once a higher mode is configured (`identity` or `fine-grained`), the application does not fall back to `capability-only` at runtime if an expected upstream service is unavailable.

### Incremental Complexity

The system is designed to be deployed with as much or as little infrastructure as you need:

| Profile | Config | Required infrastructure |
|---|---|---|
| Free tier / demo | `capability-only` | Docker |
| Team | `identity` | Keycloak + Istio service mesh on K3S |
| Enterprise | `fine-grained` | Keycloak + Istio + SpiceDB on K3S|

**The currently open-sourced version of Risquanter ships with full Layer 0 support.** Layer 0 provides a complete, production-ready deployment profile with automatic workspace expiry, rate limiting, key rotation, and all simulation and query features.

Layer 1 (Keycloak identity) and Layer 2 (SpiceDB fine-grained ACL) infrastructure and application wiring are implemented in the codebase and documented in [docs/dev/AUTHORIZATION-PLAN.md](docs/dev/AUTHORIZATION-PLAN.md), but their supporting infrastructure components (Keycloak realm provisioning, Istio mesh configuration, SpiceDB schema and CI/CD provisioning) are planned to be open-sourced as a separate repository upon completion and release.

---

## Going Further

### Examples

Ready-to-run scripts for examples live in the [`examples/`](examples/) directory:

| Script | Tool | Scenario |
|---|---|---|
| [`examples/demo-simple-httpie.sh`](examples/demo-simple-httpie.sh) | httpie | Simple operational risk (4 leaves) |
| [`examples/demo-simple-curl.sh`](examples/demo-simple-curl.sh) | curl | Simple operational risk (4 leaves) |
| [`examples/demo-enterprise-httpie.sh`](examples/demo-enterprise-httpie.sh) | httpie | Financial services enterprise risk (21 leaves, 11 portfolios) |
| [`examples/demo-enterprise-curl.sh`](examples/demo-enterprise-curl.sh) | curl | Financial services enterprise risk (21 leaves, 11 portfolios) |

```bash
chmod +x examples/*.sh
./examples/demo-simple-httpie.sh          # or demo-simple-curl.sh
./examples/demo-enterprise-httpie.sh      # or demo-enterprise-curl.sh
```

> **Note:** The `workspaceKey` returned by the bootstrap step is a 128-bit capability token embedded in every subsequent URL. Treat it like a shared secret.

### Further Reading

| Document | Contents |
|---|---|
| [docs/user/API-TUTORIAL.md](docs/user/API-TUTORIAL.md) | Step-by-step HTTP API guide, leaf parameterisation reference, enterprise example |
| [docs/user/VQL-QUERY-EXAMPLES.md](docs/user/VQL-QUERY-EXAMPLES.md) | Full VQL query reference with annotated PASS/FAIL examples |
| [docs/user/PERSISTENT-SETUP.md](docs/user/PERSISTENT-SETUP.md) | Enabling Irmin-backed persistence |
| [docs/user/DEVELOPMENT-SETUP.md](docs/user/DEVELOPMENT-SETUP.md) | Local development workflow (Scala.js, Vite HMR, tests) |
| [docs/user/DOCKER-DEVELOPMENT.md](docs/user/DOCKER-DEVELOPMENT.md) | Container configuration, observability stack, and Kubernetes deployment |
| [docs/dev/AUTHORIZATION-PLAN.md](docs/dev/AUTHORIZATION-PLAN.md) | Layer 1 / Layer 2 authorisation infrastructure |
| [docs/dev/ARCHITECTURE.md](docs/dev/ARCHITECTURE.md) | System design and component overview |
| [docs/dev/](docs/dev/) | Architectural Decision Records (ADR-001 through ADR-028) |

---


## License

Licensed under AGPL-3.0. Commercial licensing available for proprietary use — contact danago@risquanter.com to discuss terms.
