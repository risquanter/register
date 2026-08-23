# Argumentation-Engine — exploration note

**Status: exploratory scratch. Not a plan, not an ADR, not a decision.** Nothing
here is codified or specified as a commitment. Captured 2026-08-20 for later
detailed evaluation. No option below is recommended over another; trade-offs are
recorded neutrally.

Related: `PLAN-RISKTRANSFORM.md` §6 (asset / knowledge-graph transferability,
scope), `docs/archive/MITIGATION-PRE-PLANNING.md` (follow-ups / asset-scope).

---

## 1. Purpose

Preserve the reasoning from an exploration of how a future asset / knowledge-graph
extension might model reachability and attack-chain exposure, and whether an
argumentation graph is warranted. Meant to survive across sessions and machines
(it lives in the repo) and to be evaluated in detail later.

## 2. Stated goal and framing (as stated during the session)

**Final goal.** Ingest tool output from disconnected tool sources and data
formats — parsed threat models, SBOM, architecture diagrams — and lossy-transform
them, via heuristics, into one common, probably simplified scheme: a single
amalgamated knowledge base.

**Framing and interests stated along the way:**

- The current mitigation-targeting query approach can "tag" — at evaluation time —
  assets that are directly in scope of a mitigation. Those tagged assets can then
  be stored in an asset graph.
- Interest in capturing reachability from one node to another. Motivating case:
  perimeter-based defence, where two assets in the same segment can reach each
  other because nothing explicitly blocks it, so an attacker who controls one can
  reach the other (lateral movement).
- Interest in expressing *implicit* one-hop reachability from properties, e.g.
  "both speak http + both have internet + both on the same segment ⇒ they can talk
  to each other."
- Interest in whether argumentation graphs can model more complex scenarios:
  attack chains and indirect exposure — A compromised, A reaches B, B has an
  unmitigated vulnerability Y, Y matters under lateral movement, so Y is counted
  in the attack surface.
- Interest in whether the argumentation model requires administering *less* or
  *simpler* knowledge than a monotonic model.
- Questions raised: is an argumentation graph a good solution; is it needed; is
  Datalog needed or helpful for the argumentation logic.

## 3. Problem decomposition

The worked example bundles three logically distinct problems. They have different
expressiveness requirements.

1. **One-hop implicit reachability.** A derived relation defined as a conjunction
   of existing facts, e.g.
   `can_talk(x,y) := speaks_http(x) ∧ speaks_http(y) ∧ has_internet(x) ∧ has_internet(y) ∧ same_segment(x,y)`.
   Monotonic, non-recursive, first-order-definable. The current VQL targeting
   fragment admits predicate atoms; a range-formula extension (P-4 in
   `MITIGATION-PRE-PLANNING.md`) would let this be expressed. Provide
   `same_segment`, `speaks_http`, `has_internet` as asset-graph facts (the
   additive "second KB source" of `PLAN-RISKTRANSFORM.md` §6).

2. **Multi-hop reachability / attack chains.** The transitive closure of the
   one-hop relation. **Transitive closure is not expressible in first-order
   logic** (a hard result, independent of this engine), and therefore not in the
   current VQL fragment, which is FOL exact-mode with closed-world negation over a
   finite domain and no recursion. Requires either recursion (Datalog/fixpoint) or
   precomputation of the closure in host code, injected back as `reachable/2`
   facts. Precomputation reuses the "precompute the expensive predicate at KB
   build" pattern already noted for `has_unmitigated_risk` (`MITIGATION-PRE-PLANNING.md` §P-3).

3. **Defeasible reasoning under conflict.** Non-monotonic reasoning: adding a fact
   (a mitigation, an exception) *retracts* a previously-derived conclusion, and
   rules can disagree. This is what argumentation graphs address. The worked
   attack-surface example as stated is monotonic (nothing retracts "Y counts";
   "no mitigation ⇒ exposed" is closed-world negation, already native), so it does
   not by itself require argumentation.

## 4. Two worked knowledge bases (same scenario, different mitigation model)

Shared scenario: assets `a`,`b`; `a` internet-facing and compromised; both speak
http, both have internet, both in segment `seg1`; `b` has vulnerability `y` with
no patch; a segment firewall `m` can block http lateral movement; optionally a
misconfiguration `c` on the firewall. Question: is `y` in the attack surface?

### 4.1 Monotonic — Datalog + negation-as-failure, reachability precomputed

A mitigation is a **fact**; exposure is derived by a rule reading the *absence* of
that fact. "Unless C reinstates" is handled by arranging negations into strata,
designed by hand.

```prolog
compromised(a).
speaks_http(a).  speaks_http(b).
has_internet(a). has_internet(b).
same_segment(a, b).
has_vuln(b, y).
firewall(m, seg1).  covers(m, a, b).
% misconfig(m).     <- present or absent: the only knob

bypassed(M)    :- misconfig(M).                                    % stratum 0
effective(M)   :- firewall(M, _), not bypassed(M).                 % stratum 1
blocked(X, Y)  :- firewall(M, _), covers(M, X, Y), effective(M).   % stratum 2

can_talk(X, Y) :- speaks_http(X), speaks_http(Y),
                  has_internet(X), has_internet(Y),
                  same_segment(X, Y),
                  not blocked(X, Y).                               % stratum 3

reachable(X, Y) :- can_talk(X, Y).                                 % transitive
reachable(X, Z) :- reachable(X, Y), can_talk(Y, Z).               % closure (precompute in host code)

exposed(A)           :- compromised(C0), reachable(C0, A),
                        has_vuln(A, V), not has_mitigation(V, _).
in_attack_surface(V) :- has_vuln(A, V), exposed(A).
```

Deterministic bottom-up fixpoint. No `misconfig`: firewall effective → edge
blocked → `b` unreachable → `y` not in surface. Add `misconfig(m)`: firewall
bypassed → edge open → `b` reachable → no patch → `y` in surface. The
reinstatement works because the negations stratify cleanly
(`bypassed < effective < blocked < can_talk`). Observation: stratified Datalog
already covers one or two clean layers of "unless"; the mitigation never argues,
it flips a lower stratum.

### 4.2 Non-monotonic — ASPIC+-style structured argumentation

A mitigation is an **argument that attacks another argument**. Defeats and
reinstatements are computed by the semantics, not wired by hand.

```
r1: speaks_http(X), speaks_http(Y), same_segment(X,Y)  ⇒  can_talk(X, Y)
r2: compromised(X), can_talk(X, Y)                     ⇒  reachable(Y)
r3: reachable(Y), has_vuln(Y, V)                       ⇒  exposed(V)
r4: exposed(V)                                         ⇒  in_attack_surface(V)

m1: firewall(M, seg1), covers(M, a, b)  ⇒  ¬can_talk(a, b)   % rebuts r1
m2: patched(V)                          ⇒  ¬exposed(V)       % rebuts r3
c1: misconfig(M)                        ⇒  ¬applicable(m1)   % undercuts m1

Attacks:  Arg[can_talk(a,b)] <— m1 ;  m1 <— c1
```

Pick a semantics (grounded = skeptical, unique, is the usual default) and compute
justified arguments. No misconfig: `m1` undefeated, defeats `can_talk` → `y` not
in surface. Add misconfig: `c1` defeats `m1`; `can_talk` is reinstated → chain
flows → `y` in surface. Same outcomes as 4.1, but reinstatement falls out of the
semantics rather than a hand-built stratification.

## 5. Options landscape (neutral)

Both reachability options require the closure substrate; argumentation always sits
*on top of* a reachability substrate and never replaces it (argumentation does not
compute transitive closure for you).

- **A — Derived predicates in the existing FOL/VQL fragment.** One-hop reachability
  and monotonic "unmitigated ⇒ counts." Cannot express multi-hop chains at all.
- **B — FOL fragment + precomputed transitive closure injected as facts.** A graph
  algorithm computes reachability at KB build; FOL queries run over it. Covers
  chains and the monotonic attack-surface count without new query-language
  semantics. Closure computed imperatively in host code; recomputed when the graph
  changes (fits the Merkle re-sim model).
- **C — Datalog / recursive-rule layer.** Same coverage as B, but chains stay
  declarative rules. Larger engine commitment.
- **D — Argumentation / defeasible-logic layer.** Adds conflict resolution:
  mitigations that defeat exposure arguments, reinstatement, contradictory
  evidence, "which exposures survive." Sits on top of B or C. Justified when the
  model contains genuine *defeat*, not merely absence-of-mitigation.

## 6. Substrate / engine questions (neutral synthesis)

Four distinct jobs, only the last is argumentation-specific:

1. Facts — the ingested asset graph.
2. Derived predicates + reachability closure — needs recursion (not FOL). Datalog,
   or host-code precompute.
3. Instantiating defeasible rules into concrete arguments + attacks — a
   forward-chaining fixpoint. Datalog-shaped, but a hand-written chainer or ASP
   does it too.
4. Computing which arguments are justified (extensions) — non-monotonic; plain
   Datalog cannot express it.

**Do you need Datalog for argumentation?** No. Argumentation is independent of
Datalog. It needs *some* rule/fixpoint engine for jobs 2–3 and a *non-monotonic*
engine for job 4.

**Does Datalog help?** For jobs 2–3, yes (recursion, efficient grounding). It does
not reach job 4 alone. Job 4 needs one of:
- Stratified / well-founded Datalog¬ — enough for the **grounded** (skeptical)
  semantics; the grounded extension corresponds to the well-founded model.
- **ASP (Answer Set Programming)** — the standard vehicle for the full semantics
  (preferred/stable); Dung frameworks have well-known ASP encodings (e.g.
  ASPARTIX). ASP also provides jobs 2–3 in the same language (recursion +
  non-monotonic negation + preferences).
- A dedicated AF solver.

Complexity note: grounded is polynomial; preferred/stable are NP-/coNP-hard.

**Less / simpler knowledge with argumentation?** Partly.
- More local and modular: state "if X then plausibly Y" and "P contradicts Q"
  independently, and delegate global conflict resolution to the semantics. In
  stratified Datalog the layering is hand-designed and a new exception can force
  re-threading the strata.
- Not less total knowledge: you trade hand-designed stratification for an explicit
  attack/contrariness relation and rule preferences that must be declared. Abstract
  argumentation assumes attacks are given; structured argumentation derives them
  but needs contrariness/preferences stated. It still requires the reachability
  substrate underneath.

## 7. Fit for the multi-source lossy-ingestion goal (neutral synthesis)

Parsed threat models + SBOM + architecture diagrams, heuristically lossy-transformed
into one simplified scheme, tend to produce knowledge that is multi-source,
incomplete, of varying trust, and mutually contradictory (SBOM says a component is
on a vulnerable version; the parsed diagram implies isolation; the threat model
asserts a path). Considerations argumentation raises for this shape of data:

- **Inconsistency tolerance.** Classical logic and Datalog assume a globally
  consistent KB; contradictions cause explosion or wrong answers, so reconciliation
  must precede reasoning. Argumentation reasons over inconsistency, localizing
  conflicts and resolving them by defeat instead of requiring the data be cleaned
  first.
- **Trust / provenance as preferences.** "SBOM outranks the parsed architecture
  diagram" is a rule preference resolving conflicts. Natural in argumentation,
  awkward as Datalog strata.
- **Heuristic claims are defeasible by nature.** A lossy transform yields plausible
  claims, not certainties; defeasible rules model "plausibly, unless contradicted"
  directly.
- **Modular ingestion.** Each source contributes rules/facts independently; adding
  a source does not force a global re-stratification.

Cautions recorded alongside:
- Argumentation resolves *declared* conflicts; it does not fix bad data. Garbage
  facts or spurious contradictions produce garbage extensions with an audit trail.
  Most effort stays in the lossy-transform quality and in modelling
  contrariness/preferences.
- If, after normalization, sources rarely actually contradict each other,
  stratified Datalog/ASP with trust-ranked overrides ("last trusted writer wins")
  is simpler to operate and may suffice.

## 8. Register grounding

None of Datalog, ASP, or argumentation is in the current vql-engine. It is FOL
exact-mode with closed-world negation over a finite domain, no recursion. All of
the above would be a new reasoning layer over the additive "second KB source"
described in `PLAN-RISKTRANSFORM.md` §6 — a build, not an existing capability.
§6 also states the risk-tree mitigation design does not include asset scope, and
that node identity should move from name-based to stable-id-based before the asset
extension.

## 9. Open questions for later detailed evaluation

- Does the amalgamated, normalized data actually contain conflicts that need
  principled resolution, or do sources mostly agree after transformation? (This is
  the hinge between "stratified overrides" and "full argumentation.")
- If defeasibility is needed: grounded (skeptical, unique, polynomial) vs
  preferred/stable (credulous options, NP-hard). What acceptance mode does the
  attack-surface question want?
- Reachability: precomputed closure injected as facts (B) vs recursive rule layer
  (C).
- Substrate: FOL fragment + host-code precompute vs ASP as a single substrate for
  recursion + non-monotonic negation + preferences (and AF-semantics encoding).
- Identity: reconciling the name-keyed VQL domain to stable-id identity (needed
  before multi-instance asset elements), per `PLAN-RISKTRANSFORM.md` §6.
- What trust/priority ordering over sources (SBOM, threat model, architecture
  diagram) the ingestion would assert, and where it comes from.

## 10. First, unverified understanding of the high-level approach

First-pass mental model from a walkthrough. Not verified against an
implementation, not a design commitment. Records how a minimal grounded engine
would work and which parts already exist in register vs would be new.

### 10.1 The grounded engine core

Operates on arguments (nodes) + attacks (directed edges `X → Y` = "X, if
accepted, defeats Y"). Labels every argument IN (accepted) / OUT (defeated) /
UNDEC (unbroken standoff). Algorithm — a least fixpoint:

1. IN every argument with no attackers.
2. OUT every argument attacked by something IN.
3. IN every argument whose every attacker is now OUT (reinstatement).
4. Repeat 2–3 until a full pass changes no labels.
5. Anything still unlabelled → UNDEC.

Runs in time roughly proportional to (arguments + attacks); single answer, no
search, no backtracking. Chain `C→B→A` settles to C=IN, B=OUT, A=IN.

Trust ranking is preprocessing, not part of the loop: it orients a symmetric
conflict (`A↔B`) into a one-way edge by dropping the lower-trust side, then the
same loop runs. Trust ranking is what keeps a model on this cheap engine; the
NP-hard preferred/stable solver is only for conflicts left deliberately
unbroken.

### 10.2 Reuse vs. new

Already in register (reusable):
- ZIO fiber runtime — run the fixpoint interruptibly, like simulation.
- Irmin — persist the labelling, content-addressed.
- VQL/FOL engine + `RuntimeDispatcher` — one-hop predicate evaluation for the
  rules that build arguments.
- SSE — push results to the SPA.
- id-based node identity (per `PLAN-RISKTRANSFORM.md` §6).

New pieces (roughly by effort):
1. Ingestion + normalization of SBOM / threat model / diagram into a common
   vocabulary — largest effort, pure data plumbing and mapping judgement.
2. Argument construction — rules that emit arguments + attack edges
   (rebuttal / undercut); domain modelling.
3. The attack-graph data structure — a general directed graph, vs register's
   trees.
4. The grounded fixpoint solver — small, pure Scala, cross-compiles JVM+JS.
5. Trust-ranking preprocessing.
6. General-graph reachability precompute — conditional; only if the argument
   rules use multi-hop reach.
7. Result mapping back to domain meaning + a Tapir endpoint.

### 10.3 Transitive closure is handled in host code — on demand, not materialized

Register already answers a transitive-closure question through host-code recursion
rather than through the query language: `descendant_of(x, y)` /
`leaf_descendant_of(x, y)` in `RiskTreeKnowledgeBase` are implemented by calling
`TreeIndex.descendants`, which recursively walks the subtree and checks
membership. So "transitive closure is not FOL" (§3.2) is a statement about the
*query language* — you cannot write multi-hop reachability as a VQL formula over
`child_of` — not about the platform.

How it actually works (verified against the code):
- It is **not precomputed or materialized**. `TreeIndex.descendants` is a plain
  method with no cache; the subtree walk runs fresh on each predicate call, at
  query-evaluation time, inside the host closure. There is no stored closure and
  no all-pairs table. A single `descendant_of(desc, ancestor)` call walks the
  ancestor's whole subtree (cost O(subtree size)) and membership-checks `desc`.
- Under quantification the FOL semantics enumerates the finite domain and calls
  the predicate once per binding, so `forall x. descendant_of(x, root)` triggers
  many independent subtree walks with **no sharing** between them. This is the
  current behaviour and a performance consideration for larger domains.

This is a third shape, distinct from Option B (§5): Option B materializes the
closure at KB build and injects `reachable/2` facts; register's tree uses a
recursive host predicate evaluated lazily per call. For the asset graph — a
general directed graph with cycles (lateral movement) and multiple paths — the
lazy-recursion approach would need cycle handling the tree walk has none of, which
is where materializing the closure once (Option B) becomes attractive on cost
grounds, and it must be rebuilt on graph change (fits the Merkle re-sim /
closure-on-change model).

### 10.4 Fixpoint, in one place

The reachability closure and the grounded labelling are the same construction — a
least fixpoint. Start from empty, apply a step that only ever adds, repeat until
a full pass adds nothing; that stable point is the answer. `TreeIndex.descendants`
is a degenerate case: a tree has no cycles, so one downward walk reaches the
fixed point without iterating. General-graph reachability and the grounded loop
need genuine iterate-to-stable, because paths reconverge and labels depend on
each other.

## 11. Standing preference — do not fold `descendant_of` into a general closure

**Status: current preference, not a decision. Re-verify before acting on it.**

If/when a general-graph transitive-closure capability is built for the asset
graph, the current tree predicates `descendant_of` / `leaf_descendant_of` should
**not** be replaced by it.

Relationship (context, not disputed): a general closure operator subsumes these
predicates mathematically — `descendant_of = transitiveClosure(child_of)`
restricted to a tree, with a strict/irreflexive projection, and
`leaf_descendant_of` adds a leaf filter. So they *could* become thin projections
over a shared operator.

Why keep them separate anyway:
- The current implementation is **on demand** — `TreeIndex.descendants` walks the
  subtree fresh per call, nothing materialized (§10.3).
- It **exploits the tree structure**: a tree is acyclic, so the walk needs no
  visited-set / cycle guard and terminates in a single downward pass. A
  general-graph closure must carry cycle handling the tree provably never needs,
  and would more likely materialize (heavier build cost + memory) to avoid
  repeated re-walking on a cyclic graph.

Net: replacing the tree walk with the general engine trades a zero-ceremony,
cycle-free, on-demand walk for a heavier shared engine plus a materialization
decision the tree does not need. Preference is to leave the tree predicates as
they are and let the asset-graph closure be its own thing.

Re-verify at the point the asset-graph closure is actually designed: whether the
duplication cost then outweighs these arguments, and whether the on-demand vs
materialized profiles have shifted.
