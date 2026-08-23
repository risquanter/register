# PLAN — Phase F: Semantic (domain-level) merge conflict resolution

**Status: NEEDS REVIEW — not yet implementation-grade, does not confer G3 plan
coverage.** This document rehomes the Phase F requirements that were tracked in
the now-archived milestone-2b scratch doc
(`docs/archive/milestone-2b-cache-and-decisions.md`, "Deferred: Phase D Option-2
conflict resolution"). It validates the decisions already ruled and presents the
outstanding ones for ruling. Before any code, it must be elevated to
implementation-grade (exact signatures, `## File inventory` with full paths, ADR
alignment, verification plan) and approved — see "Elevation gate" below.

This is a **genuinely separate, prerequisite-gated epic**, not deferred
current-plan scope: milestone-2b Phases A–E shipped whole, and Phase F needs a
capability those phases do not have — a **domain-level** merge over `RiskLeaf`
parameters, not the byte-level Irmin three-way merge Phase D uses. Requirements 2
and 3 below are the reason it is a distinct phase: byte-level merge cannot average
parameters or produce a simulate-and-compare preview.

## 1. Context — what Phase D shipped (ruled, validated)

- **Phase D = Option 1 (edit-to-agree), RULED 2026-07-24.** Conflicts are
  detected byte-level (three-way vs LCA, ADR-032) and listed read-only in the
  merge modal; resolution is edit-the-nodes-to-agree then re-check. Merge is
  enabled only on a `"clean"` preview, and the server re-checks on merge so a
  stale preview degrades to 409, never a bad merge. Shipped:
  `ScenarioMerger` (Irmin native `merge_with_branch`, DD-2 image 3.11-p1
  surfaces conflicts), `previewScenarioMerge` + `mergeScenario` endpoints,
  `MergeModal`. `MergeConflictEntry` already carries `treeId`/`nodeId`.
- **Phase F = Option 2 (semantic resolution), RULED as the target phase
  2026-07-24.** The one-click and semantic resolution paths were explicitly
  deferred out of Phase D to here.
- **Per-branch tree-name uniqueness = Option A, RULED 2026-07-27**
  (`DONE-PLAN-PHASE-E-HISTORY.md` §C1). This is the *premise* of requirement 4:
  two branches can independently create same-name / different-ULID trees, and the
  write-time uniqueness check never runs on the merge path.

## 2. Motivating workflows (user, 2026-07-24)

- Fork `A@main` into `A@scen-1` and `A@scen-2`; each applies a mitigation
  (modelled as a direct edit until mitigation is first-class — `scen-1` dampens
  likelihood, `scen-2` narrows the loss range). Merge each back on its own merit,
  sequentially. **Already covered by the Phase D clean-merge path.**
- Two modellers build scenarios over overlapping node sets with differing loss
  ranges / likelihood estimates → genuine conflicts needing resolution. **This is
  Phase F's target.**

## 3. Scope — the four requirements (to design, then elevate)

1. **Take-A / take-B per conflicting path.** The `[keep main]` / `[keep scenario]`
   sketch. A byte-path conflict is a tree's `meta` or a single `nodes/<id>`; the
   UI maps those ULIDs to tree/node names. Mechanically an ordinary save of the
   chosen side via the branch-aware endpoints (`X-Active-Branch`).
2. **Parameter averaging as a resolution mode.** Domain-level, case-by-case,
   user-driven: conflicting numeric parameters resolved by averaging the two
   estimates (likelihood 5% and 9% → 7%; loss min-max midpoints). A domain-level
   merge of `RiskLeaf` fields, not a blob choice.
3. **Merge sneak-peek.** The resolved result (side-picked or averaged) is
   materialised as a previewable model that simulates, so the user can compare it
   in the existing comparison view against the source branches (`scen-1`,
   `scen-2`, `main`) before committing the merge.
4. **Tree-name collision detection and resolution at merge.** Detect distinct
   ULIDs sharing a name that would coexist post-merge (alongside the existing
   per-node byte-level check), then resolve at merge. The same mechanism applies
   to a future branch→branch merge (today merge targets `main` only).

## 4. Open decisions (present for ruling during review)

All four are genuine design decisions with trade-offs only the user can weigh.
Recommendations are **mine**.

### OD-F1 — Parameter-average semantics (requirement 2)

**Goal.** Define precisely what "average two conflicting `RiskLeaf` estimates"
means across the parameter kinds the model has (occurrence probability, loss
range, distribution shape). It matters because "average the parameters" is
ambiguous for a distribution — averaging metalog coefficients is not the same
operation as averaging the distributions they describe.

- **A — scalar fields only.** Average occurrence probability (arithmetic mean)
  and loss-range endpoints (min/max midpoints); a distribution-shape difference
  (metalog coefficients) stays a conflict resolved by take-A/take-B. *Pros:*
  simple, predictable, matches the fields the motivating workflows actually
  conflict on. *Cons:* cannot blend genuinely different distribution shapes.
- **B — distribution-level average.** Average in a representation-neutral space
  (mix the two distributions 50/50, i.e. average their quantile functions).
  Uniform across metalog and lognormal. *Pros:* statistically defensible,
  general. *Cons:* more work; depends on what `metalog-distribution` exposes;
  a 50/50 mixture is itself a modelling choice the user may not want silently.
- **C — per-representation average.** lognormal → average its params; metalog →
  average coefficients/quantiles. *Pros:* representation-specific. *Cons:*
  averaging coefficients is not a meaningful statistical operation; two
  metalogs of different order do not align; brittle.

**Recommendation (mine): A for the first cut, B (quantile-average) as a defined
follow-on if shape conflicts appear.** Probability and loss-range midpoints are
what the workflows conflict on; distribution-shape blending is a separate hard
sub-problem where the correct operation is quantile-function averaging (B), not
coefficient averaging (C). Elevation must check `metalog-distribution`'s API for
the quantile-mix operation before committing to B's timing.

### OD-F2 — Tree-name collision resolution policy (requirement 4)

**Goal.** When two branches created same-name / different-ULID trees, how does
merge resolve it? Detection (scan for distinct ULIDs sharing a name post-merge)
is required in all options; this decides the resolution.

- **A — reject as a conflict.** Block the merge; user renames on a branch, then
  re-merges. *Pros:* safe, explicit, no silent structural change. *Cons:*
  friction; a round-trip per collision.
- **B — auto-rename one side.** Deterministic suffix on one tree. *Pros:*
  no friction. *Cons:* silently changes the user's model naming; surprising.
- **C — prompt at merge preview.** Surface alongside byte-level conflicts; user
  chooses rename-one / keep-both / cancel. *Pros:* explicit and flexible; keeps
  the user in control. *Cons:* more UI.

**Recommendation (mine): C, with A as the fallback if C's UI is too much for a
first cut.** Tree identity is the ULID, not the name, so the collision is
resolvable rather than a hard conflict — surfacing it and letting the user rename
or keep-both is the honest treatment. Auto-rename (B) is a silent change to the
model and is rejected.

### OD-F3 — Merge sneak-peek materialisation (requirement 3)

**Goal.** Where does the resolved-but-uncommitted merged model live so it can
simulate and be compared against the source branches?

- **A — ephemeral scratch branch.** Materialise the resolved tree on a temporary
  Irmin branch, simulate via the normal path, compare via the existing view,
  discard on cancel. *Pros:* maximal reuse of the comparison + branch machinery
  (the compare view keys its slot coordinate on `(tree, revision)`). *Cons:*
  adds an ephemeral-branch orphan class (same accepted class as ContentCache /
  scenario delete).
- **B — in-memory only.** Build the resolved tree server-side, simulate without
  persisting, return curves; no branch. *Pros:* no storage growth. *Cons:* only
  works if the comparison view can accept curves without a branch coordinate.
- **C — extend `previewScenarioMerge`.** Carry the resolved model through the
  existing preview endpoint. *Pros:* minimal new surface. *Cons:* couples preview
  to domain-merge; endpoint shape change (Decision Trigger #1).

**Recommendation (mine): A if the comparison view requires a branch coordinate,
B if simulation+curve return can bypass it — resolve at elevation by checking the
compare view's slot coordinate.** Lean A for reuse, accepting the ephemeral-branch
orphan class.

### OD-F4 — Take-A / take-B granularity (requirement 1)

**Goal.** At what granularity does the user pick a side?

- **A — byte-path granularity.** A conflict is a tree `meta` or a single
  `nodes/<id>`; pick the whole side. An ordinary branch-aware save;
  `MergeConflictEntry` already carries `treeId`/`nodeId`. *Pros:* simplest;
  matches existing detection. *Cons:* cannot pick per-field within a node.
- **B — field granularity.** Pick per-field (probability from A, loss-range from
  B). *Pros:* finer control. *Cons:* more UI plus a domain-merge assembly step;
  overlaps heavily with parameter-averaging (OD-F1).

**Recommendation (mine): A.** It is an ordinary save of the chosen side via
existing endpoints, and per-field picking overlaps with OD-F1's averaging, which
already covers "blend the two." B only if users demand per-field picking beyond
averaging.

## 5. ADR alignment (to complete at elevation)

- **ADR-032 (content equality relations)** — Phase D's byte-level detection is
  ADR-032-compliant; the domain-level merge (req 2) operates above it and must
  not weaken the byte-level re-check gate.
- **ADR-017 (tree API design)** — take-A/take-B saves reuse the branch-aware
  update endpoints; no new identity-vs-new bucket semantics expected.
- **ADR-004a (notification-refetch)** — the sneak-peek's simulate+compare rides
  the existing analysis path.
- New endpoint shapes (if OD-F3 lands on C, or a domain-merge endpoint is added)
  → Decision Trigger #1, ADR review before code.

## 6. Elevation gate (before any implementation)

This document confers **no** G3 coverage. To build Phase F:

1. Rule OD-F1…OD-F4.
2. Add exact signatures for the domain-merge functions, the sneak-peek
   materialisation, and any endpoint/DTO changes.
3. Add a `## File inventory` section with full repo-relative paths.
4. Complete the ADR alignment (§5).
5. Add a verification plan (unit + Scala.js + serverIt commands that must be
   green).
6. Present the elevated plan and obtain an accepted signal.

Per the "plan = epic, shipped whole" rule, once elevated Phase F is implemented
and shipped as one complete delivery — its four requirements are not split across
passes.
