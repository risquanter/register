# Risquanter Register — VQL Query Examples

The **Vague Query Language (VQL)** is Register's proportional first-order logic query dialect, inspired by Fermüller, Hofer & Ortiz, *"Vague Quantifiers in Query Languages"*, FQAS 2017. This document covers every operator, predicate, and quantifier form with annotated PASS/FAIL contrast pairs.

## Prerequisites & Assumptions

| Requirement | Detail |
|---|---|
| Running Register instance | The examples assume the **docker-compose stack** (`docker compose --profile frontend up -d`): `localhost:18080` serves the UI and proxies all API calls (the demo scripts default to it). In-memory mode is the default; no persistence backend needed. See [Getting Started (in-memory storage)](../README.md#getting-started-in-memory-storage) in the README. |
| Bootstrapped workspace & tree | You need a `workspaceKey` and `treeId`. Quickest path: `chmod +x examples/*.sh && ./examples/demo-simple-httpie.sh` — the script prints both values. Alternatively follow [API-TUTORIAL.md](API-TUTORIAL.md) step 1. |
| Exported variables | `export WS_KEY=<workspaceKey>` and `export TREE_ID=<treeId>`. |
| Running queries via UI | Navigate to `http://localhost:18080/w/<workspaceKey>`, open the **Analyze** view, select the tree from the dropdown, and type queries in the query pane. |
| Running queries via API | POST the query string to the tree's query endpoint. See [API-TUTORIAL.md](API-TUTORIAL.md) step 3 for the endpoint and response format. |
| Reference trees | The examples use two trees: the **simple 4-leaf operational risk tree** (Cyber Breach, Ransomware, Supply Chain Disruption, Regulatory Fine) and the **21-leaf enterprise financial services tree** — both are bootstrapped by the scripts in [`examples/`](../../examples/) and documented fully in [API-TUTORIAL.md](API-TUTORIAL.md). |

---

## Query Structure

Every VQL query has the shape:

```
Q[op]^{p/q} x (range(x), predicate(x))
```

where:
- `op` is `>=` (at least), `<=` (at most), or `~` (approximately)
- `p/q` is the proportion threshold
- `range(x)` restricts the domain (e.g. `leaf(x)`, `portfolio(x)`, `leaf_descendant_of(x, "Name")`)
- `predicate(x)` is the condition evaluated against the simulation cache

Query syntax errors are reported at parse time with position information.

In the examples below, the simple 4-leaf tree and the 21-leaf enterprise tree produce different outcomes for the same query, illustrating how the quantifier fraction controls stringency.

---

## Running the examples

The `examples/` directory contains ready-to-run scripts that bootstrap a tree and execute a selection of the queries below, printing a pass/fail summary of results. For working through the examples manually, you will only need the workspace key and tree ID they output. Register's first access layer is capability-based: the workspace key is a secret token embedded in the workspace URL. To view the tree in the application, navigate to `http://localhost:18080/w/<workspaceKey>` in a browser. After the app loads, switch to the Analyze view and select the tree from the dropdown. From there you can run VQL queries directly in the query pane.

---

## Basic leaf screening

The simplest queries check each individual risk against a single condition — for example, whether its P95 loss exceeds a threshold. The P95 is the loss you'd expect to see exceeded only once in twenty occurrences. The operator and fraction then ask: how many pass that check, and is that enough?

*"Do at least half of all leaves carry a loss above $2M in 1-in-20 occurrences?"* — only 1 of 4 qualifies in the simple tree, so this is **not satisfied**:

```
Q[>=]^{1/2} x (leaf(x), gt_loss(p95(x), 2000000))
```

Relaxing the proportion to 1/3 and targeting the more extreme 1-in-100 tail — *"Do at least a third of all leaves carry a loss above $5M in 1-in-100 occurrences?"* — is **satisfied** (2 of 4):

```
Q[>=]^{1/3} x (leaf(x), gt_loss(p99(x), 5000000))
```

Both queries test the same condition — only the fraction changes. Raising the bar from 1/3 to 1/2 is what tips the result from passing to failing.

---

## Upper-bound queries

The `<=` operator tests whether a proportion stays *below* a ceiling — useful for asserting that severe exposure is not too broadly concentrated.

*"Do at most half of all leaves have a greater-than-5% annual probability of generating a loss above $2M?"*:

```
Q[<=]^{1/2} x (leaf(x), gt_prob(lec(x, 2000000), 0.05))
```

`lec(x, threshold)` returns the Loss Exceedance Curve probability — the unconditional annual probability that node x generates a loss exceeding the threshold.

---

## Fuzzy "approximately" quantifier

The `~` operator matches proportions that are roughly equal to the stated fraction — think "about half" rather than "at least half".

*"Do about half of all leaves carry a loss above $5M in 1-in-20 occurrences?"* — only ~24% qualify across the enterprise tree, so this is **not satisfied** even with fuzzy tolerance:

```
Q[~]^{1/2} x (leaf(x), gt_loss(p95(x), 5000000))
```

Restating the same question at the proportion the data actually supports — *"Do about a fifth of all leaves carry a loss above $5M in 1-in-20 occurrences?"* — is **satisfied**:

```
Q[~]^{1/5} x (leaf(x), gt_loss(p95(x), 5000000))
```

This pair shows the key distinction from `>=`: `~` expects the proportion to be *close to* the stated fraction, not merely at or above it.

---

## Portfolio-level aggregation

Replacing `leaf(x)` with `portfolio(x)` shifts the quantifier range to aggregated portfolio nodes, each of which already incorporates the simulation of all its descendants.

*"Do at most a third of portfolio nodes carry an aggregate loss above $50M in 1-in-20 occurrences?"*:

```
Q[<=]^{1/3} x (portfolio(x), gt_loss(p95(x), 50000000))
```

---

## Sub-portfolio scoping with named nodes

`leaf_descendant_of(x, "Name")` and `child_of(x, "Name")` scope the quantifier range to a specific named branch. This is the primary mechanism for cross-branch comparison.

The following pair applies the same 1-in-20 loss bar and the same quantifier to IT Risk (heavy-tailed) and Third Party Risk (lighter-tailed) — one satisfies, the other does not:

*"Do at least half of IT Risk's leaves carry a loss above $2M in 1-in-20 occurrences?"*
*"Do at least half of Third Party Risk's leaves carry a loss above $2M in 1-in-20 occurrences?"*

```
Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"),          gt_loss(p95(x), 2000000))
Q[>=]^{1/2} x (leaf_descendant_of(x, "Third Party Risk"), gt_loss(p95(x), 2000000))
```

`child_of` restricts to direct children only:

```
Q[>=]^{1/2} x (child_of(x, "IT Risk"), gt_loss(p99(x), 5000000))
```

Swapping the named scope while holding the quantifier and predicate constant is a reliable technique for locating which branch drives a risk property.

---

## Existential and universal quantifiers in scope

The predicate position can contain a second quantified formula over a second variable, enabling structural questions about children of portfolio nodes.

**Existential** — *"Do at least two-thirds of portfolio nodes have at least one direct child carrying a loss above $1M in 1-in-20 occurrences?"*:

```
Q[>=]^{2/3} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 1000000)))
```

**Universal** — *"Do at least half of portfolio nodes have ALL their direct children carrying a loss above $1M in 1-in-20 occurrences?"*:

```
Q[>=]^{1/2} x (portfolio(x), forall y . (child_of(y, x) ==> gt_loss(p95(y), 1000000)))
```

The `exists` / `forall` forms are particularly useful for asserting structural properties — for example, that no portfolio is composed entirely of low-severity risks.

---

## Negation and cross-branch exclusion

The predicate can include a negation (`~`) to exclude nodes matching a named branch, enabling queries that scope to everything *outside* a named cluster.

*"Do about half of the non-Cyber leaves carry a loss above $1M in 1-in-20 occurrences?"*:

```
Q[~]^{1/2} x (leaf(x), ~descendant_of(x, "Technology & Cyber") /\ gt_loss(p95(x), 1000000))
```

---

## Ready-to-run scripts

Ready-to-run scripts for both the simple and enterprise trees — including coloured output, all FAIL / PASS contrast pairs, and `jq`-extracted results — are in the [`examples/`](../../examples/) directory:

| Script | Scenario |
|---|---|
| [`examples/demo-simple-httpie.sh`](../../examples/demo-simple-httpie.sh) / [`demo-simple-curl.sh`](../../examples/demo-simple-curl.sh) | Simple operational risk (4 leaves) |
| [`examples/demo-enterprise-httpie.sh`](../../examples/demo-enterprise-httpie.sh) / [`demo-enterprise-curl.sh`](../../examples/demo-enterprise-curl.sh) | Financial services enterprise risk (21 leaves, 11 portfolios) |
