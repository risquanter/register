# Risquanter Register — API Tutorial

This guide walks through the HTTP API step by step: parameterising leaf risks, bootstrapping a workspace, fetching simulation results, and running vague quantifier queries.

## Prerequisites & Assumptions

| Requirement | Detail |
|---|---|
| Running Register instance | The examples target the **docker-compose stack** started with `docker compose --profile frontend up -d` — the nginx frontend on `localhost:18080` proxies all API paths through to the backend. In-memory mode is the default; no persistence backend is required. See [Getting Started (in-memory storage)](../README.md#getting-started-in-memory-storage) in the README. |
| API endpoint | `http://localhost:18080` throughout (the frontend container). If you run the bare backend instead (`docker compose up -d register-server`, or Vite dev mode), substitute `localhost:8090`. |
| HTTP client | Examples use [httpie](https://httpie.io/) (`http` command) and [curl](https://curl.se/). Install either one — both produce the same results. |
| `jq` | Used in curl examples for pretty-printing JSON responses. Install via your OS package manager (`brew install jq`, `apt install jq`, etc.). |
| `workspaceKey` | The 128-bit capability token returned by the bootstrap step. It is embedded in every subsequent URL. **Treat it like a shared secret** — anyone who holds it can read and modify that workspace. |
| Ready-to-run scripts | The [`examples/`](../examples/) directory contains fully self-contained scripts for all scenarios shown here. `chmod +x examples/*.sh` and run directly. |

---

## Leaf Risk Parameters: A Modeller's Guide

Every leaf node requires three things: a name, an occurrence probability, and a loss characterisation. The occurrence probability is the top-level `probability` field; the loss characterisation is a nested `distributionShape` object carrying `distributionType` and the parameters of that mode alone (`minLoss`/`maxLoss` for `Lognormal`, `percentiles`/`quantiles`/`terms` for `Metalog`). The loss characterisation comes in two forms — pick whichever matches how your expert articulates their knowledge.

### Confidence interval mode (`distributionType: "Lognormal"`)

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
  "probability": 0.2,
  "distributionShape": {
    "distributionType": "Lognormal",
    "minLoss": 500000,
    "maxLoss": 8000000
  }
}
```

The system constructs a right-skewed loss distribution from those two bounds. Nothing else is needed. The simulation will reflect not just the expected loss but the full spread — including the tail scenarios that matter for capital planning.

### Quantile mode (`distributionType: "Metalog"`)

Use this when your expert can speak to several points on the loss landscape, not just the credible range. The two fields are:

| Field | What it means |
|---|---|
| `probability` | The chance this risk event occurs, same as above. |
| `percentiles` | The probability levels your expert is making statements at, as decimal fractions. `[0.25, 0.50, 0.75, 0.95]` means the 25th, 50th, 75th, and 95th percentiles of the conditional loss distribution. |
| `quantiles` | The loss amounts at each of those percentiles, in the same order. |

Concretely: a supply chain analyst says *"there's a 10 % chance of a major disruption this year; if it happens, only one time in twenty **(P95)** would losses exceed $15 M, three-quarters of the time **(P75)** they stay below $4 M, about half the time **(P50)** below $1 M, and only in one case out of four **(P25)** are losses as low as $200 K."*

```json
{
  "name": "Ransomware",
  "probability": 0.1,
  "distributionShape": {
    "distributionType": "Metalog",
    "percentiles": [
      0.25,
      0.5,
      0.75,
      0.95
    ],
    "quantiles": [
      200000,
      1000000,
      4000000,
      15000000
    ],
    "terms": null
  }
}
```

The system fits a flexible distribution that passes exactly through every stated point, honouring the expert's full picture of the loss landscape without imposing any parametric assumption.

**Minimum requirement:** two percentile-quantile pairs. A view on the median and the 95th percentile is already enough. Add more pairs wherever the expert has a well-formed opinion — each additional point sharpens the fitted shape.

---

## 1. Bootstrap a Workspace & First Tree

Create a workspace with a two-level risk tree: one portfolio ("Operations") containing four leaf risks — a cyber breach (lognormal), ransomware (metalog quantiles), a supply chain disruption (lognormal), and a regulatory fine (lognormal):

```bash
http POST localhost:18080/workspaces \
  name="Operational Risk Model" \
  portfolios:='[
    {
      "name": "Operations",
      "parentName": null
    },
    {
      "name": "IT Risk",
      "parentName": "Operations"
    },
    {
      "name": "Third Party Risk",
      "parentName": "Operations"
    }
  ]' \
  leaves:='[
    {
      "name": "Cyber Breach",
      "parentName": "IT Risk",
      "probability": 0.2,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 500000,
        "maxLoss": 8000000
      }
    },
    {
      "name": "Ransomware",
      "parentName": "IT Risk",
      "probability": 0.1,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          200000,
          1000000,
          4000000,
          15000000
        ],
        "terms": null
      }
    },
    {
      "name": "Supply Chain Disruption",
      "parentName": "Third Party Risk",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 300000,
        "maxLoss": 3000000
      }
    },
    {
      "name": "Regulatory Fine",
      "parentName": "Third Party Risk",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 100000,
        "maxLoss": 2000000
      }
    }
  ]' \
  newMitigations:='[]'
```

`newMitigations` lists the [mitigations](TERMINOLOGY.md#31-mitigation) the request adds, and the empty list adds none. The key is required, so a body without it is refused with `400`.

A request that replaces a stored tree, `PUT /w/{key}/risk-trees/{treeId}`, carries mitigations in two lists, the way it carries nodes. `mitigations` lists each stored mitigation to keep, by its `id`. `newMitigations` lists each one to add, with no `id`, and the server assigns one. A stored mitigation that neither list names is deleted. So to keep a tree's mitigations, send back every one its structure read returned.

An absolute statement is the one exception to sending it back as read. Its `target` is written by the server from its `anchor`, and the read shows it, but a request that sends a `target` with an absolute statement is refused with `422` at that entry. Send an absolute statement back without its `target`. Every other mitigation needs its `target`, and one sent without it is refused with `422` as required. A `target` holds at most 2 048 characters, a limit chosen by this project, and a longer one is refused with `422` at that entry. The `query` of a screening query sent to `POST /w/{key}/risk-trees/{treeId}/query` has the same limit, and a longer one is refused with `400`, because the query is checked while the body is read.

A `400` means the body could not be read at all. A `422` means the body was read and one or more of its entries was refused. A `422` body lists every refused entry together, each with its location in the request, so one answer reports all of them.

An [absolute statement](TERMINOLOGY.md#36-relative-and-absolute-statements) names the stored leaf it was written against in its `anchor`. A tree being created has no stored leaf yet, so an absolute statement can be added only by a replacing request.

The server answers `201 Created`. The `Location` header is `w/{key}`, a reference relative to `/workspaces` that resolves to the new workspace's address. The body carries your workspace key and the new tree's id and name:

```json
{
  "workspaceKey": "aB3x7kLm2Pq9RwZvNsYt8u",
  "tree": { "id": "01J...", "name": "Operational Risk Model" },
  "expiresAt": "2026-04-22T10:00:00Z",
  "seedEntityId": 1
}
```

`seedEntityId` is the number that keeps this workspace's random streams apart from every other workspace's, which is what makes its figures reproducible (see [Random stream](TERMINOLOGY.md#16-random-stream)).

Store both values — every subsequent request uses them:

```bash
export WS_KEY=aB3x7kLm2Pq9RwZvNsYt8u
export TREE_ID=01J...
```

## 2. Fetch Tree Summary

**httpie:**
```bash
http GET "localhost:18080/w/$WS_KEY/risk-trees/$TREE_ID" "X-Branch: main"
```

**curl:**
```bash
curl -s -H "X-Branch: main" "http://localhost:18080/w/$WS_KEY/risk-trees/$TREE_ID" | jq .
```

Returns the tree's identity: its id and its name. It does not return figures — for those, read a node's exceedance probability or request several nodes' curves. A tree the workspace holds but the named branch lacks answers `404` with a body whose `kind` is `TreeNotOnBranch`.

## 3. Run a Vague Quantifier Query

Queries are submitted via HTTP POST to the tree's query endpoint. The response reports whether the quantifier is satisfied, the exact proportion, and the IDs of matching nodes (useful for tree highlighting in the frontend):

```json
{
  "satisfied": true,
  "proportion": 0.667,
  "satisfyingNodeIds": ["<Cyber Breach node id>", "<Ransomware node id>"],
  "rangeSize": 3,
  "satisfyingCount": 2,
  "sampleSize": 3,
  "queryEcho": "Q[>=]^{1/3} x (leaf(x), gt_loss(p99(x, \"inherent\"), 5000000))"
}
```

### Query Language Reference

| Syntax element | Meaning |
|---|---|
| `Q[>=]^{2/3} x (range(x), pred(x))` | True when at least 2/3 of elements in `range` satisfy `pred` |
| `Q[<=]^{1/2} x (range(x), pred(x))` | True when at most 1/2 of elements in `range` satisfy `pred` |
| `Q[~]^{1/2} x (range(x), pred(x))` | True when approximately 1/2 of elements satisfy `pred` (fuzzy) |
| `leaf(x)` | x is a leaf risk node |
| `portfolio(x)` | x is a portfolio node |
| `node_named("Name")` | the one node that has this name; stands wherever a node is expected |
| `node_id("<node id>")` | the one node that has this identifier; stands wherever a node is expected |
| `child_of(x, node_named("Parent Name"))` | x is a direct child of the named node |
| `descendant_of(x, node_named("Name"))` | x is any descendant of the named node |
| `leaf_descendant_of(x, node_named("Name"))` | x is a leaf anywhere under the named node |
| `eq(x, node_named("Name"))` | x is the one node that has this name |
| `eq(x, node_id("<node id>"))` | x is the one node that has this identifier |
| `p95(x, "inherent")`, `p99(x, "inherent")` | P95 / P99 loss value for node x (returns Loss) |
| `lec(x, 1000000, "inherent")` | Exceedance probability at \$1 M for node x (returns Probability) |
| `gt_loss(p95(x, "inherent"), 5000000)` | P95 loss exceeds \$5 M (Loss comparison) |
| `gt_prob(lec(x, 1000000, "inherent"), 0.05)` | Exceedance probability at \$1 M exceeds 5 % (Probability comparison) |

## 4. Mitigations

A [mitigation](TERMINOLOGY.md#31-mitigation) is a risk reduction measure stored on the tree and applied to the nodes it targets. The nodes keep their own figures. A read then asks for the tree's figures with mitigations applied or without them.

This section adds mitigations to the tree from step 1, reads which nodes each one reaches, and reads a node's figures under them. [MITIGATION-WALKTHROUGH.md](MITIGATION-WALKTHROUGH.md) runs the same features as one scripted sequence on a larger tree, with a commit history and a scenario. Every request and answer below was run against a server. Identifiers are shown as `<IT Risk id>` and similar, because each workspace gets its own.

### What a mitigation holds

Each entry in `newMitigations` has four fields:

| Field | What it holds |
|---|---|
| `name` | A display name, unique within the tree. |
| `target` | The [targeting predicate](TERMINOLOGY.md#38-targeting-predicate): a condition over one node, `x`, written in the query language. It names nodes by identifier only, with `node_id("…")`. |
| `spec` | What the mitigation does, with `stage` naming one of three kinds (below). |
| `precedence` | An integer fixing the order mitigations apply in at one node. Lower applies first. `-1000`, `0` and `1000` are the three values this project names. |

The `spec` is one of three kinds, named by `stage`:

- `LeafRelative` changes a leaf's figures by a fraction before it is simulated. `likelihood` is `{"op": "Keep"}` or `{"op": "Scale", "factor": f}`. `severity` is `{"op": "Keep"}` or `{"op": "ScaleSeverity", "factor": f}`. A factor is greater than 0 and at most 1, because a mitigation reduces a figure and cannot remove it.
- `ResultStage` changes the losses already simulated, at any node. `pipeline` lists between one and ten steps, applied in order. Each step is one of `{"op": "CapLosses", "cap": n}`, `{"op": "ApplyDeductible", "deductible": n}`, `{"op": "ScaleLosses", "factor": f}`, `{"op": "FilterBelowThreshold", "threshold": n}` and `{"op": "InsurancePolicy", "deductible": n, "limit": n}`. What each step leaves you bearing is in [the five result-stage operations](TERMINOLOGY.md#34-the-five-result-stage-operations).
- `LeafAbsolute` is an [absolute statement](TERMINOLOGY.md#36-relative-and-absolute-statements): it states one leaf's figures after the control. It is described under "Absolute statements" below.

### Add two mitigations

Send the tree back with `PUT`, keeping every node by its `id`, and list the new mitigations in `newMitigations`:

```json
"mitigations": [],
"newMitigations": [
  {
    "name": "Endpoint hardening",
    "target": "leaf(x) /\\ descendant_of(x, node_id(\"<IT Risk id>\"))",
    "spec": {
      "stage": "LeafRelative",
      "likelihood": { "op": "Scale", "factor": 0.5 },
      "severity": { "op": "Keep" }
    },
    "precedence": 0
  },
  {
    "name": "Cyber policy",
    "target": "eq(x, node_id(\"<IT Risk id>\"))",
    "spec": {
      "stage": "ResultStage",
      "pipeline": [ { "op": "InsurancePolicy", "deductible": 250000, "limit": 2000000 } ]
    },
    "precedence": 0
  }
]
```

"Endpoint hardening" halves the likelihood of every leaf under IT Risk. "Cyber policy" is an insurance policy on the IT Risk portfolio's total: you bear the first 250 thousand of a loss, the policy covers the next 2 million, and anything beyond that is yours again.

The server answers `200` with the tree's id and name, and gives each mitigation an `id`.

### Read which nodes each mitigation reaches

The structure read returns the stored mitigations under `tree.mitigations`, and beside the tree it returns `mitigationScopes`:

```bash
curl -s -H "X-Branch: main" "http://localhost:18080/w/$WS_KEY/risk-trees/$TREE_ID/structure" | jq '.mitigationScopes'
```

```json
[
  {
    "mitigationId": "<Endpoint hardening id>",
    "outcome": { "kind": "Resolved", "scope": [ "<Ransomware id>", "<Cyber Breach id>" ] }
  },
  {
    "mitigationId": "<Cyber policy id>",
    "outcome": { "kind": "Resolved", "scope": [ "<IT Risk id>" ] }
  }
]
```

`scope` is the mitigation's [resolved scope](TERMINOLOGY.md#39-resolved-scope): the nodes its predicate holds for in this version of the tree, kept to leaves for a `LeafRelative` mitigation.

The scope is worked out again for every version. Add a leaf "Phishing" under IT Risk in the next `PUT`, keeping both mitigations by sending each one back in `mitigations` as the read returned it. The structure read then lists three leaves in Endpoint hardening's scope, and nothing about the mitigation had to change.

Two more changes show what the predicate depends on:

- Rename IT Risk. Every scope stays the same, because the predicate names the portfolio by its identifier, and a rename keeps the identifier.
- Delete a node a predicate names. The node leaves the scope, the rest of the predicate still applies, and nothing is reported. A mitigation targeting `leaf(x) /\ descendant_of(x, node_id("<Third Party Risk id>"))` reaches no node once Third Party Risk and its leaves are deleted. Its scope is `[]`, and it stays stored.

### Read figures under a selection

A [selection](TERMINOLOGY.md#45-selection) says which mitigations a read applies. Both analysis reads state it in the body. It is an object whose `kind` field names one of three cases:

| `kind` | Applies |
|---|---|
| `Inherent` | no mitigation. This is [inherent risk](TERMINOLOGY.md#44-inherent-risk-and-residual-risk). |
| `Residual` | every mitigation the tree holds, each at every node in its scope. This is residual risk. |
| `Selected` | the mitigations named in `mitigations`, an object keyed by mitigation id. |

Each entry of `mitigations` carries a `scope`. `{"scope": "FullScope"}` applies the mitigation at every node in its scope. `{"scope": "NodesOnly", "nodes": [...]}` applies it at the listed nodes and at no other, and the list holds at least one node.

```bash
curl -s -X POST -H "X-Branch: main" -H 'Content-Type: application/json' \
  "http://localhost:18080/w/$WS_KEY/risk-trees/$TREE_ID/nodes/<IT Risk id>/prob-of-exceedance?threshold=1000000" \
  -d '{"selection": {"kind": "Residual"}}' | jq .
```

IT Risk's chance of a loss of at least 1 million under three selections, after Phishing was added:

| `selection` | Answer |
|---|---|
| `{"kind": "Inherent"}` | `{"probability": 0.204, "withMitigations": []}` |
| `{"kind": "Residual"}` | `{"probability": 0.0354, "withMitigations": ["<Endpoint hardening id>", "<Cyber policy id>"]}` |
| `{"kind": "Selected", "mitigations": {"<Endpoint hardening id>": {"scope": "NodesOnly", "nodes": ["<Cyber Breach id>"]}}}` | `{"probability": 0.1276, "withMitigations": ["<Endpoint hardening id>"]}` |

`withMitigations` lists the selected mitigations whose scope covers this node or a node below it. The exact probabilities depend on the workspace's [seed entity id](TERMINOLOGY.md#16-random-stream), so another workspace gives other figures for the same tree.

The curve read, `POST /w/{key}/risk-trees/{treeId}/nodes/lec-multi`, takes a list named `valuations`. Each entry holds one selection and the nodes to draw under it:

```json
{
  "valuations": [
    { "selection": { "kind": "Inherent" }, "nodeIds": ["<IT Risk id>"] },
    { "selection": { "kind": "Selected", "mitigations": { "<Cyber policy id>": { "scope": "FullScope" } } },
      "nodeIds": ["<IT Risk id>"] }
  ]
}
```

The answer mirrors the request. It holds one entry per valuation, in the request's order, and each entry holds its selection and one curve per node:

```json
{
  "valuations": [
    { "selection": { "kind": "Inherent" },
      "curves": { "<IT Risk id>": { "curve": { "…": "…" }, "withMitigations": [] } } },
    { "selection": { "kind": "Selected", "mitigations": { "<Cyber policy id>": { "scope": "FullScope" } } },
      "curves": { "<IT Risk id>": { "curve": { "…": "…" }, "withMitigations": ["<Cyber policy id>"] } } }
  ]
}
```

Every curve in one answer is sampled at the same loss thresholds, so curves from different valuations can be drawn on one chart. A node that no selected mitigation reaches still has its curve under that selection, with an empty `withMitigations`.

Three rules bound a curve request. It holds between 1 and 16 valuations, a limit this project chose. No two valuations state the same selection: to draw more nodes under one selection, list them all in that valuation's `nodeIds`. Every valuation lists at least one node.

A request that breaks one of the first two rules, or that leaves out `selection`, is answered `400`. A valuation that lists no node is answered `422`. A `Selected` selection naming a mitigation the tree does not hold is answered `422`, and the body names every such mitigation.

### Absolute statements

An absolute statement says "after this control, this leaf *is* these figures". It is written against one stored leaf, named in `anchor`, and it carries no `target`. The server writes the target from the anchor:

```json
"newMitigations": [
  {
    "name": "Cyber Breach after the policy",
    "spec": {
      "stage": "LeafAbsolute",
      "replacement": {
        "replaces": "Severity",
        "shape": { "distributionType": "Lognormal", "minLoss": 100000, "maxLoss": 1000000 }
      },
      "anchor": "<Cyber Breach id>",
      "stamp": { "kind": "KeepIfUnchanged" }
    },
    "precedence": 1000
  }
]
```

`replacement` replaces the likelihood, the loss distribution, or both, named by `replaces`:

- `{"replaces": "Likelihood", "probability": p}`, where `p` is greater than 0 and at most 1;
- `{"replaces": "Severity", "shape": { … }}`, with a shape as a leaf's `distributionShape`;
- `{"replaces": "Both", "probability": p, "shape": { … }}`.

The structure read shows the statement with the target the server wrote, `eq(x, node_id("<Cyber Breach id>"))`, and with a `baseStamp`. The base stamp is the fingerprint of the leaf as it stood when the statement was written.

Now re-estimate Cyber Breach to a loss range of 2 to 30 million and save the tree, sending the statement back unchanged. The structure read then lists the statement's id in `staleMitigationIds`. Its figures were stated for a leaf that no longer has those figures, and it is [stale](TERMINOLOGY.md#36-relative-and-absolute-statements) until its author re-affirms it.

To re-affirm it, send it back with `"stamp": {"kind": "Restamp"}`. The server records the leaf's current fingerprint, and `staleMitigationIds` is empty again. `KeepIfUnchanged` keeps the stored stamp while the replacement and the anchor are unchanged, which is what a save that does not touch the statement sends.

Deleting the anchor leaf leaves the statement stored with an empty scope. It is not reported as stale, because there is no leaf left for its figures to disagree with.

### Choosing what a mitigation says

"This control dampens the losses" can mean several different things about the world. Each one is written as a different `spec`, and each gives different figures. Find the row that states what you believe the control does:

| What you believe | What to write |
|---|---|
| Every loss is proportionally smaller. | `LeafRelative`, with `severity` `{"op": "ScaleSeverity", "factor": f}` |
| The event happens less often. | `LeafRelative`, with `likelihood` `{"op": "Scale", "factor": f}` |
| The bad case is much better, and the typical case is a little better. | `LeafAbsolute`, replacing `Severity` with the range you now expect |
| No loss above a figure reaches you. | `ResultStage`, with `{"op": "CapLosses", "cap": n}` |
| You bear a fraction of every loss, and the rest is transferred. | `ResultStage`, with `{"op": "ScaleLosses", "factor": f}` |
| You bear the first part of every loss, and nothing beyond it. | `ResultStage`, with `{"op": "ApplyDeductible", "deductible": n}` |
| You bear the first part, a counterparty covers the next part up to a limit, and the rest is yours again. | `ResultStage`, with `{"op": "InsurancePolicy", "deductible": n, "limit": n}` |
| Every loss is smaller, and there is also a hard maximum. | Two mitigations: one `LeafRelative` with `ScaleSeverity`, and one `ResultStage` with `CapLosses` |
| You understand the loss better, and it is no smaller. | No mitigation. Re-estimate the leaf's bounds. |
| The risk no longer exists. | No mitigation. Delete the node. |

A mitigation cannot remove a risk. Every factor is greater than 0, and an absolute likelihood is greater than 0, because a control can fail. A risk that has gone, because the system was retired or the business line exited, is deleted from the tree.

Two pairs of rows are the ones most often confused.

#### A measurement is not a mitigation

An assessment, a monitoring tool or an audit tells you more about a risk. It does not make the loss smaller when the event happens. Recorded as a mitigation, for example as a `ScaleSeverity` because the range now looks tighter, it lowers the residual figures. The control is then credited with a reduction that came from learning.

The question that decides it: after this control, if the event happens, is the loss smaller? If the answer is "no, but we are more confident about how large it is", re-estimate the leaf's bounds with a `PUT`. The leaf then states what is now known, and no control is credited with it.

#### A lower upper bound and a cap are different claims

A `LeafAbsolute` statement with a lower `maxLoss` says that losses above the new bound are less likely. They are still possible, one time in twenty, and larger ones beyond that. A `CapLosses` step says that no loss above the cap reaches you. Every trial whose loss would exceed the cap records the cap instead.

Both lower the figures, and usually only one is true of a given control. An engineering change that makes bad outcomes rarer is a lower bound. A contract that limits what you owe is a cap.

### Narrowing a leaf's range

A control can make an outcome more predictable as well as smaller: the worst case improves more than the typical case. This is a change to the leaf's spread, and `ScaleSeverity` does not make it.

A lognormal leaf's [loss distribution](TERMINOLOGY.md#14-loss-distribution) has a spread, written σ (sigma, the standard deviation of the loss measured on a logarithmic scale). The fit computes it from the two bounds:

```
σ = (ln maxLoss − ln minLoss) / 3.29
```

The 3.29 is fixed by the fitting method. It is the distance, in standard deviations, between the 5th and the 95th percentile of a normal distribution, which is 2 × 1.645. The formula depends on the bounds only through their ratio, `maxLoss / minLoss`. So:

- `ScaleSeverity` multiplies both bounds by one factor. The ratio stays the same, so the spread stays the same. Every loss moves down in the same proportion.
- Narrowing the range means changing the ratio. Write a `LeafAbsolute` statement that keeps `minLoss` and lowers `maxLoss` to the upper bound you believe after the control.

Here is one illustrative leaf, bounded at 1 000 and 50 000, under each:

| | bounds | σ | median | mean |
|---|---|---|---|---|
| the leaf as stated | 1 000 to 50 000 | 1.19 | 7 071 | 14 339 |
| `ScaleSeverity` with factor 0.6 | 600 to 30 000 | 1.19 | 4 243 | 8 603 |
| `LeafAbsolute`, `Severity` 1 000 to 7 071 | 1 000 to 7 071 | 0.59 | 2 659 | 3 173 |

The scaled leaf has the same spread at a lower level. The absolute statement keeps the lower bound where it was and halves the spread.

Narrowing a range does not lower every loss. The fit places the 5th percentile at `minLoss`, so keeping `minLoss` and lowering `maxLoss` turns the whole distribution about that point. Losses above the 5th percentile fall, and the smallest losses below it rise:

| percentile | the leaf as stated | after the statement |
|---|---|---|
| 0.1st | 179 | 423 |
| 1st | 445 | 667 |
| 5th | 1 000 | 1 000 |
| 10th | 1 541 | 1 241 |
| 50th | 7 071 | 2 659 |
| 95th | 49 991 | 7 070 |

Any change of spread behaves this way. The one way to reduce the spread without raising any loss is to clip the simulated losses, which is what `CapLosses` does.

An absolute statement applies to the one leaf it names. To narrow fifty leaves, write fifty statements, each with that leaf's own bounds. Each one becomes [stale](TERMINOLOGY.md#36-relative-and-absolute-statements) if its leaf is later re-estimated, as described under "Absolute statements" above.

---

## Enterprise Risk Model Example

The following example builds a realistic 4-domain financial services risk tree (32 nodes: 1 root + 10 portfolios + 21 leaves) to demonstrate queries at enterprise complexity.

```
Enterprise Risk  (root)
├── Operational Risk
│   ├── Technology and Cyber
│   │   ├── Ransomware Attack             metalog 15%  P25=$500K P50=$2M P75=$8M  P95=$25M
│   │   ├── Cloud Provider Outage         lognorm 30%  $200K–$4M
│   │   ├── Data Breach - PII               lognorm 10%  $1M–$15M
│   │   └── Insider Threat                lognorm  5%  $2M–$20M
│   ├── Process and People
│   │   ├── Key Person Departure          lognorm 20%  $100K–$800K
│   │   ├── Internal Fraud                metalog  8%  P25=$200K P50=$1M P75=$4M  P95=$18M
│   │   └── Process Failure               lognorm 25%  $50K–$500K
│   └── Third-Party and Supply Chain
│       ├── Critical Vendor Failure       lognorm 12%  $500K–$5M
│       ├── Outsourcing SLA Breach        lognorm 20%  $100K–$1.5M
│       └── Concentration Risk            metalog  8%  P25=$1M   P50=$4M          P95=$18M
├── Financial Risk
│   ├── Market Risk
│   │   ├── Equity Portfolio Drawdown     metalog 35%  P25=$1M P50=$4M P75=$12M  P95=$28M
│   │   └── FX Adverse Move               lognorm 40%  $500K–$8M
│   ├── Credit Risk
│   │   ├── Counterparty Default          lognorm  5%  $3M–$30M
│   │   └── Credit Downgrade Wave         metalog 15%  P25=$800K P50=$3M          P95=$20M
│   └── Liquidity Risk
│       └── Funding Squeeze               lognorm  8%  $2M–$25M
├── Compliance and Legal Risk
│   ├── Regulatory Action                 lognorm 12%  $2M–$50M
│   ├── Litigation                        metalog  8%  P25=$300K P50=$2M P75=$8M  P95=$40M
│   └── GDPR / Data Protection Fine       lognorm 15%  $500K–$10M
└── Strategic and Reputational Risk
    ├── ESG Controversy                   lognorm 10%  $1M–$12M
    ├── M and A Integration Failure      lognorm  5%  $5M–$40M
    └── Product Recall / Liability        metalog  6%  P25=$1M   P50=$5M          P95=$35M
```

### Bootstrap (httpie)

```bash
http POST localhost:18080/workspaces \
  name="Financial Services Enterprise Risk" \
  portfolios:='[
    {
      "name": "Enterprise Risk",
      "parentName": null
    },
    {
      "name": "Operational Risk",
      "parentName": "Enterprise Risk"
    },
    {
      "name": "Technology and Cyber",
      "parentName": "Operational Risk"
    },
    {
      "name": "Process and People",
      "parentName": "Operational Risk"
    },
    {
      "name": "Third-Party and Supply Chain",
      "parentName": "Operational Risk"
    },
    {
      "name": "Financial Risk",
      "parentName": "Enterprise Risk"
    },
    {
      "name": "Market Risk",
      "parentName": "Financial Risk"
    },
    {
      "name": "Credit Risk",
      "parentName": "Financial Risk"
    },
    {
      "name": "Liquidity Risk",
      "parentName": "Financial Risk"
    },
    {
      "name": "Compliance and Legal Risk",
      "parentName": "Enterprise Risk"
    },
    {
      "name": "Strategic and Reputational Risk",
      "parentName": "Enterprise Risk"
    }
  ]' \
  leaves:='[
    {
      "name": "Ransomware Attack",
      "parentName": "Technology and Cyber",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          500000,
          2000000,
          8000000,
          25000000
        ],
        "terms": null
      }
    },
    {
      "name": "Cloud Provider Outage",
      "parentName": "Technology and Cyber",
      "probability": 0.3,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 200000,
        "maxLoss": 4000000
      }
    },
    {
      "name": "Data Breach - PII",
      "parentName": "Technology and Cyber",
      "probability": 0.1,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 1000000,
        "maxLoss": 15000000
      }
    },
    {
      "name": "Insider Threat",
      "parentName": "Technology and Cyber",
      "probability": 0.05,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 2000000,
        "maxLoss": 20000000
      }
    },
    {
      "name": "Key Person Departure",
      "parentName": "Process and People",
      "probability": 0.2,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 100000,
        "maxLoss": 800000
      }
    },
    {
      "name": "Internal Fraud",
      "parentName": "Process and People",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          200000,
          1000000,
          4000000,
          18000000
        ],
        "terms": null
      }
    },
    {
      "name": "Process Failure",
      "parentName": "Process and People",
      "probability": 0.25,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 50000,
        "maxLoss": 500000
      }
    },
    {
      "name": "Critical Vendor Failure",
      "parentName": "Third-Party and Supply Chain",
      "probability": 0.12,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 500000,
        "maxLoss": 5000000
      }
    },
    {
      "name": "Outsourcing SLA Breach",
      "parentName": "Third-Party and Supply Chain",
      "probability": 0.2,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 100000,
        "maxLoss": 1500000
      }
    },
    {
      "name": "Concentration Risk",
      "parentName": "Third-Party and Supply Chain",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.95
        ],
        "quantiles": [
          1000000,
          4000000,
          18000000
        ],
        "terms": null
      }
    },
    {
      "name": "Equity Portfolio Drawdown",
      "parentName": "Market Risk",
      "probability": 0.35,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          1000000,
          4000000,
          12000000,
          28000000
        ],
        "terms": null
      }
    },
    {
      "name": "FX Adverse Move",
      "parentName": "Market Risk",
      "probability": 0.4,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 500000,
        "maxLoss": 8000000
      }
    },
    {
      "name": "Counterparty Default",
      "parentName": "Credit Risk",
      "probability": 0.05,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 3000000,
        "maxLoss": 30000000
      }
    },
    {
      "name": "Credit Downgrade Wave",
      "parentName": "Credit Risk",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.95
        ],
        "quantiles": [
          800000,
          3000000,
          20000000
        ],
        "terms": null
      }
    },
    {
      "name": "Funding Squeeze",
      "parentName": "Liquidity Risk",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 2000000,
        "maxLoss": 25000000
      }
    },
    {
      "name": "Regulatory Action",
      "parentName": "Compliance and Legal Risk",
      "probability": 0.12,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 2000000,
        "maxLoss": 50000000
      }
    },
    {
      "name": "Litigation",
      "parentName": "Compliance and Legal Risk",
      "probability": 0.08,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.75,
          0.95
        ],
        "quantiles": [
          300000,
          2000000,
          8000000,
          40000000
        ],
        "terms": null
      }
    },
    {
      "name": "GDPR / Data Protection Fine",
      "parentName": "Compliance and Legal Risk",
      "probability": 0.15,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 500000,
        "maxLoss": 10000000
      }
    },
    {
      "name": "ESG Controversy",
      "parentName": "Strategic and Reputational Risk",
      "probability": 0.1,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 1000000,
        "maxLoss": 12000000
      }
    },
    {
      "name": "M and A Integration Failure",
      "parentName": "Strategic and Reputational Risk",
      "probability": 0.05,
      "distributionShape": {
        "distributionType": "Lognormal",
        "minLoss": 5000000,
        "maxLoss": 40000000
      }
    },
    {
      "name": "Product Recall / Liability",
      "parentName": "Strategic and Reputational Risk",
      "probability": 0.06,
      "distributionShape": {
        "distributionType": "Metalog",
        "percentiles": [
          0.25,
          0.5,
          0.95
        ],
        "quantiles": [
          1000000,
          5000000,
          35000000
        ],
        "terms": null
      }
    }
  ]' \
  newMitigations:='[]'
```

### Bootstrap (curl)

```bash
curl -s -X POST http://localhost:18080/workspaces \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Financial Services Enterprise Risk",
    "portfolios": [
      {
        "name": "Enterprise Risk",
        "parentName": null
      },
      {
        "name": "Operational Risk",
        "parentName": "Enterprise Risk"
      },
      {
        "name": "Technology and Cyber",
        "parentName": "Operational Risk"
      },
      {
        "name": "Process and People",
        "parentName": "Operational Risk"
      },
      {
        "name": "Third-Party and Supply Chain",
        "parentName": "Operational Risk"
      },
      {
        "name": "Financial Risk",
        "parentName": "Enterprise Risk"
      },
      {
        "name": "Market Risk",
        "parentName": "Financial Risk"
      },
      {
        "name": "Credit Risk",
        "parentName": "Financial Risk"
      },
      {
        "name": "Liquidity Risk",
        "parentName": "Financial Risk"
      },
      {
        "name": "Compliance and Legal Risk",
        "parentName": "Enterprise Risk"
      },
      {
        "name": "Strategic and Reputational Risk",
        "parentName": "Enterprise Risk"
      }
    ],
    "leaves": [
      {
        "name": "Ransomware Attack",
        "parentName": "Technology and Cyber",
        "probability": 0.15,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            500000,
            2000000,
            8000000,
            25000000
          ],
          "terms": null
        }
      },
      {
        "name": "Cloud Provider Outage",
        "parentName": "Technology and Cyber",
        "probability": 0.3,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 200000,
          "maxLoss": 4000000
        }
      },
      {
        "name": "Data Breach - PII",
        "parentName": "Technology and Cyber",
        "probability": 0.1,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 1000000,
          "maxLoss": 15000000
        }
      },
      {
        "name": "Insider Threat",
        "parentName": "Technology and Cyber",
        "probability": 0.05,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 2000000,
          "maxLoss": 20000000
        }
      },
      {
        "name": "Key Person Departure",
        "parentName": "Process and People",
        "probability": 0.2,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 100000,
          "maxLoss": 800000
        }
      },
      {
        "name": "Internal Fraud",
        "parentName": "Process and People",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            200000,
            1000000,
            4000000,
            18000000
          ],
          "terms": null
        }
      },
      {
        "name": "Process Failure",
        "parentName": "Process and People",
        "probability": 0.25,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 50000,
          "maxLoss": 500000
        }
      },
      {
        "name": "Critical Vendor Failure",
        "parentName": "Third-Party and Supply Chain",
        "probability": 0.12,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 500000,
          "maxLoss": 5000000
        }
      },
      {
        "name": "Outsourcing SLA Breach",
        "parentName": "Third-Party and Supply Chain",
        "probability": 0.2,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 100000,
          "maxLoss": 1500000
        }
      },
      {
        "name": "Concentration Risk",
        "parentName": "Third-Party and Supply Chain",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.95
          ],
          "quantiles": [
            1000000,
            4000000,
            18000000
          ],
          "terms": null
        }
      },
      {
        "name": "Equity Portfolio Drawdown",
        "parentName": "Market Risk",
        "probability": 0.35,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            1000000,
            4000000,
            12000000,
            28000000
          ],
          "terms": null
        }
      },
      {
        "name": "FX Adverse Move",
        "parentName": "Market Risk",
        "probability": 0.4,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 500000,
          "maxLoss": 8000000
        }
      },
      {
        "name": "Counterparty Default",
        "parentName": "Credit Risk",
        "probability": 0.05,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 3000000,
          "maxLoss": 30000000
        }
      },
      {
        "name": "Credit Downgrade Wave",
        "parentName": "Credit Risk",
        "probability": 0.15,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.95
          ],
          "quantiles": [
            800000,
            3000000,
            20000000
          ],
          "terms": null
        }
      },
      {
        "name": "Funding Squeeze",
        "parentName": "Liquidity Risk",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 2000000,
          "maxLoss": 25000000
        }
      },
      {
        "name": "Regulatory Action",
        "parentName": "Compliance and Legal Risk",
        "probability": 0.12,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 2000000,
          "maxLoss": 50000000
        }
      },
      {
        "name": "Litigation",
        "parentName": "Compliance and Legal Risk",
        "probability": 0.08,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.75,
            0.95
          ],
          "quantiles": [
            300000,
            2000000,
            8000000,
            40000000
          ],
          "terms": null
        }
      },
      {
        "name": "GDPR / Data Protection Fine",
        "parentName": "Compliance and Legal Risk",
        "probability": 0.15,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 500000,
          "maxLoss": 10000000
        }
      },
      {
        "name": "ESG Controversy",
        "parentName": "Strategic and Reputational Risk",
        "probability": 0.1,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 1000000,
          "maxLoss": 12000000
        }
      },
      {
        "name": "M and A Integration Failure",
        "parentName": "Strategic and Reputational Risk",
        "probability": 0.05,
        "distributionShape": {
          "distributionType": "Lognormal",
          "minLoss": 5000000,
          "maxLoss": 40000000
        }
      },
      {
        "name": "Product Recall / Liability",
        "parentName": "Strategic and Reputational Risk",
        "probability": 0.06,
        "distributionShape": {
          "distributionType": "Metalog",
          "percentiles": [
            0.25,
            0.5,
            0.95
          ],
          "quantiles": [
            1000000,
            5000000,
            35000000
          ],
          "terms": null
        }
      }
    ],
    "newMitigations": []
  }' | jq '{workspaceKey: .workspaceKey, treeId: .tree.id, expiresAt: .expiresAt}'
```
