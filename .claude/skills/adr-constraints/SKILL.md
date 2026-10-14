---
name: adr-constraints
description: "Three-layer reference to the register project's architecture decision records: which records every kind of work must honour whether or not the task mentions them, which record governs which subject, and where two records meet. It routes; it holds none of their content. Read the records it names in full — they are the authority. Load before planning or implementing new types, endpoints, services, or infrastructure changes."
user-invokable: true
---

# ADR Reference — Register

**This document routes. It holds no rules, no reasons and no code.** Having found the records, read them in full from `docs/dev/decision-records/` and work from them. They are the authority; this document can be wrong only by pointing at the wrong file.

It has three layers, and they answer three different questions:

1. **By activity** — what must I honour because of the *kind* of work this is, whether or not the task mentions it. **Start here.**
2. **By subject** — which record governs a named topic, and what each record is about.
3. **Where records meet** — the pairs whose combination neither record states alone.

Every record present is in force regardless of its `Status:` field. Deletion is the only archival, and the status labels have not been verified against the code (`docs/dev/TODO.md` item 56).

This document does not cover the domain concepts (`docs/user/TERMINOLOGY.md`), how the system is built (`docs/dev/ARCHITECTURE.md`), or what is being built (`docs/dev/plans/`).

---

## Layer 1 — By activity: what the kind of work demands

**Identify the activity, read its *always* column in full, then add any *also* row that applies.** The always column is not a suggestion and does not depend on the task's wording: a task that says "add a field to `RiskLeaf`" mentions no validation, no identifier and no constant, and all three bear on it. Searching the task's words finds what it mentions; this layer finds what it implies, which is why it comes first.

### Every edit to a source file, whatever the activity

**Always:** ADR-011 · ADR-037
Import placement and grouping (ADR-011) and named constants and closed sets (ADR-037) bear on a file because it is being edited at all, not because of what the edit is for. The `boyscout` skill is what makes them happen in the same pass rather than in a later sweep; these are the records behind two of its obligations.

### Designing or changing a domain type, class or field

**Always:** ADR-001 · ADR-018 · ADR-029 · ADR-037 · ADR-010
Every type here is correct-by-construction or it is a defect, so the question is never *whether* these bear but *how*: how the value is constructed and where it is validated (ADR-001), whether it needs a nominal wrapper to be distinguishable from a type with the same encoding (ADR-018), what content domain a string-backed field may admit (ADR-029), whether any of its values is one of a fixed set (ADR-037), and what failure it can produce as a value (ADR-010).

**Also:** ADR-022 if it wraps a secret · ADR-036 if it is, or embeds, a scoping identifier · ADR-038 if it crosses the wire, which decides where its wire shape is declared and whether it may have a serialiser at all · ADR-003 if it carries seed identity · ADR-032 if it enters a hash projection · ADR-034 if it is a mitigation parameter.

### Adding or changing an endpoint, a DTO, or anything else on the wire

**Always:** ADR-017 · ADR-038 · ADR-036 · ADR-035 · ADR-010 · ADR-001
The wire is the one surface where a mistake is published: the request shape and its bounds (ADR-017), the shape a response takes and which of the two kinds it is (ADR-038), what may cross in either direction (ADR-036), what a failure may disclose (ADR-035), how a typed error becomes a status (ADR-010), and that validation happens in the codec rather than the handler (ADR-001).

**Also:** ADR-021 if the route is workspace-scoped · ADR-030 and ADR-024 if it reads or mutates protected data · ADR-029 if any field carries user text · ADR-025 and ADR-027 if a browser navigates to the path · ADR-004a if the response carries a branch.

### Adding or changing a service or repository method

**Always:** ADR-001 · ADR-030 · ADR-010 · ADR-033
A service signature is where the project's two structural guarantees live: already-validated types in, never raw primitives (ADR-001), and the authorization proof in the signature rather than in a comment (ADR-030). Its failures are typed values (ADR-010), and whether it may catch anything at all is decided by ADR-033.

**Also:** ADR-024 if it would write authorization data · ADR-012 and ADR-031 if it retries, times out, or waits · ADR-004a if it writes storage · ADR-036 if it takes or returns an identifier.

### Touching storage, branches, commits or a merge

**Always:** ADR-004a · ADR-007 · ADR-032 · ADR-036
The storage layout and the single-writer rule (ADR-004a), what a scenario is and how a branch is created, renamed and cleaned up (ADR-007), which of the two hash relations answers the question being asked (ADR-032), and the fact that both branch-name types embed the workspace identifier (ADR-036).

**Also:** ADR-029 if a value is interpolated into a GraphQL document · ADR-017 if node identity is involved · ADR-014 if the change could affect cache keys.

### Touching simulation, caching or aggregation

**Always:** ADR-014 · ADR-015 · ADR-009 · ADR-003 · ADR-032
What is cached and under what key (ADR-014), the single simulation entry point (ADR-015), where the algebra lives and what licenses parallel resolution (ADR-009), how a draw is derived and what provenance it must carry (ADR-003), and which projection the key hashes (ADR-032).

**Also:** ADR-034 if mitigations are in play · ADR-016 if a simulation parameter would become configuration.

### Changing mitigation valuation or adding a transform

**Always:** ADR-034 · ADR-014 · ADR-009 · ADR-001
The two folds and where a transform may be applied (ADR-034), why the cached value must not be transformed (ADR-014), the aggregate claim a transform must not break (ADR-009), and the boundary type half of the no-elimination rule (ADR-001) — whose other half is a runtime check, which is why a new transform cannot be assumed covered by a type.

### Writing or changing a frontend component or its state

**Always:** ADR-019 · ADR-001 · ADR-033
Component shape, who owns state, when a value is a derived signal versus a state machine, and which tier renders a failure (ADR-019); the validation reused from the shared module (ADR-001); and the one sanctioned catch-all at the JavaScript boundary (ADR-033).

**Also:** ADR-025 if the browser URL is read or written · ADR-029 if user text is rendered · ADR-018 if an identifier is unwrapped for display · ADR-038 if the component holds a decoded response, since what it may rely on is decided there · ADR-010 if a server failure is classified.

### Touching authorization or a capability boundary

**Always:** ADR-024 · ADR-030 · ADR-021 · ADR-012 · ADR-036
The application is a read-only enforcement point (ADR-024), checks happen at the orchestration boundary and prove it in a signature (ADR-030), the capability credential and its lifetime (ADR-021), what the mesh is assumed to have done first (ADR-012), and which identifiers may never be accepted from a client (ADR-036).

**Also:** ADR-022 if a credential type is involved · ADR-035 if a denial is rendered.

### Handling an error, or adding a catch

**Always:** ADR-010 · ADR-035 · ADR-033 · ADR-002
How failures are represented and mapped (ADR-010), what may reach a client (ADR-035), where an exception may be caught and how wide (ADR-033), and where the diagnosis is logged instead (ADR-002).

**Also:** ADR-036 if the error names a resource · ADR-022 if a credential could appear in a message · ADR-019 if the browser must classify it.

### Adding or changing a dependency, a base image, or a container

**Always:** ADR-020 · ADR-026
Pinning, verification, cooldown, publisher trust and the per-ecosystem verification status (ADR-020); the builder-and-production tiering and what a deployed image may contain (ADR-026).

**Also:** ADR-027 if it is the frontend image · ADR-023 if local trust material or database roles are involved · ADR-INFRA-006 if credentials are delivered.

### Adding or changing configuration

**Always:** ADR-016 · ADR-031
The single canonical source and how tests load it (ADR-016), and the rule that a backoff is a code constant while only a budget is configuration (ADR-031).

**Also:** ADR-022 if the value is a secret · ADR-017 if it is a size or count bound, which belongs in a type rather than in configuration · ADR-003 if it is a simulation parameter.

### Adding logging, metrics or spans

**Always:** ADR-002 · ADR-035
Which signal answers which question and at what level (ADR-002), and what a message may carry (ADR-035) — noting that a confidential identifier is permitted server-side and forbidden on the wire.

**Also:** ADR-036 for which identifiers those are · ADR-028 if a query is instrumented.

### Working on the query language path

**Always:** ADR-028 · ADR-029
Where evaluation runs, how the model is built, and what is validated before evaluation (ADR-028); and that query text is user input reaching a parser (ADR-029).

**Also:** ADR-014 if simulation results are needed · ADR-034 if mitigation selections are referenced · ADR-017 if tree size bounds the work.

---

## Layer 2 — By subject: which record governs what

Use this when you already know the topic or the record's name. It answers "where is X ruled", not "what must I honour".

| Record | File | Governs |
|---|---|---|
| ADR-001 | `ADR-001.md` | Validation strategy: Iron refinements, smart constructors, where validation happens, private aggregate constructors |
| ADR-001 appendix | `ADR-001-appendix.md` | Constraints on values sent verbatim to an external system as a header or token |
| ADR-002 | `ADR-002.md` | Logging and telemetry: which signal answers which question, log levels, the typed error hierarchy's origin |
| ADR-003 | `ADR-003.md` | Seeding, reproducibility and provenance records |
| ADR-004a | `ADR-004a-proposal.md` | Persistence architecture: Irmin storage layout, scenarios as branches, the single writer, server-sent events |
| ADR-004a appendix | `ADR-004a-appendix.md` | The three meanings of "tree", and the domain-to-storage mapping |
| ADR-007 | `ADR-007-proposal.md` | Scenario branching: what a scenario is, branch naming, create, rename, cleanup |
| ADR-007 appendix | `ADR-007-appendix.md` | Branch reference type, commit granularity, and per-path merge mechanics |
| ADR-009 | `ADR-009.md` | Risk aggregation: where the algebra lives, the portfolio constructor, what licenses parallel resolution |
| ADR-010 | `ADR-010.md` | Error handling: the sealed hierarchy, validation errors, the channel per layer, mapping to HTTP |
| ADR-011 | `ADR-011.md` | Import conventions |
| ADR-012 | `ADR-012.md` | Service mesh: identity, policy layers, what resilience the application must not implement, mesh trust invariants |
| ADR-014 | `ADR-014.md` | Simulation result caching: what is cached, the content-hash key, no invalidation, per-workspace instances |
| ADR-015 | `ADR-015.md` | The cached-result resolver as the single simulation entry point. **Its storage layer is retired — read it with ADR-014** |
| ADR-016 | `ADR-016-config-management.md` | Configuration loading and the single canonical source |
| ADR-017 | `ADR-017-tree-api-design.md` | Tree API shape: create versus update DTOs, whole-tree submission, identity preservation, size limits |
| ADR-017 notes | `ADR-017-NOTES.md` | Why per-operation batch types are not adopted |
| ADR-018 | `ADR-018-nominal-wrappers.md` | Nominal wrappers over Iron types for identifiers that must not be interchangeable |
| ADR-019 | `ADR-019-frontend-component-architecture.md` | Frontend architecture: component shape, state ownership, derived signals, state machines, error presentation |
| ADR-020 | `ADR-020-supply-chain-security.md` | Supply chain: pinning, verification, install-time side effects, cooldown, publisher trust, per-ecosystem status |
| ADR-021 | `ADR-021-capability-urls.md` | Capability URLs: the workspace credential, the store and its expiry, the endpoint surface, where rate limiting belongs |
| ADR-022 | `ADR-022-secret-handling.md` | Credential types: the eight requirements, the boundary against configuration secrets, scoped wiping |
| ADR-023 | `ADR-023-local-dev-tls-and-trust-material-policy.md` | Local development TLS, trust material, and per-service database roles |
| ADR-024 | `ADR-024-externalized-authorization-pep-pattern.md` | Authorization: the application as a read-only enforcement point, where tuples are written, lifecycle writes |
| ADR-025 | `ADR-025-spa-routing-strategy.md` | Browser URL handling: path routing, history management, the reverse-proxy requirement |
| ADR-026 | `ADR-026-container-image-strategy.md` | Container images: builder and production tiers, naming, minimal runtimes, the frontend exception |
| ADR-027 | `ADR-027-frontend-nginx-serving.md` | The frontend image's nginx: routing rules, cache headers, runtime substitution, the boundary against `register-infra` |
| ADR-028 | `ADR-028-vague-quantifier-query-pane.md` | Query evaluation: where it runs, how the model is built, materialised relations, validation before evaluation |
| ADR-028 appendix | `ADR-028-appendix-technical-design.md` | The knowledge-base schema, scalability analysis, API contract, example queries |
| ADR-029 | `ADR-029-input-injection-defence.md` | Injection defence: content-domain restriction, the two sanctioned routes to an interpreter, the parser-boundary table |
| ADR-030 | `ADR-030-authorization-enforcement-orchestration-boundary.md` | Where authorization checks are made: the orchestration boundary, the proof token, the schema's single home |
| ADR-031 | `ADR-031-startup-readiness-vs-request-path-resilience.md` | Which retry loops are permitted in the application, and the shape a readiness gate must take |
| ADR-032 | `ADR-032-content-equality-relations.md` | The two hash relations over a node, and which question each answers |
| ADR-033 | `ADR-033-exception-boundaries.md` | Where a catch is permitted and how wide it may be |
| ADR-034 | `ADR-034-mitigation-valuation-model.md` | Mitigation valuation: the two folds, transform composition, the layer outside the aggregate, the no-elimination rule |
| ADR-035 | `ADR-035-error-leakage-prevention.md` | What may reach a client in an error: exhaustive encoding, the typed boundary, message discipline |
| ADR-036 | `ADR-036-confidential-internal-identifiers.md` | Which identifiers may cross the client boundary, in which direction, and the substitutes for those that may not |
| ADR-037 | `ADR-037-in-code-constants.md` | Constants and closed sets: enums in their owner's companion, declared versus derived spellings, forbidden defaults |
| ADR-038 | `ADR-038-response-wire-shapes.md` | Response shapes: the two kinds of response and the shape each takes, no raw primitive where a refined type exists, no serialiser on a confidential identifier, `toWire` owned by the source |
| ADR-INFRA-006 | `ADR-INFRA-006.md` | Database credential delivery. Binds `register-infra`'s deployment, not this repository's code |
| — | `ADR-00X.md` | The authoring template for a new record. Not a decision record |

---

## Layer 3 — Where records meet

Each row is a trap that lives between two records: what a reader who knows both separately would still get wrong. It is the one layer that cannot be produced by summarising the corpus, because the information is in neither record's own account of itself.

Read it as an index of collisions, not as a substitute for either record. Where a reconciliation has been written into the records themselves, the row says so; the row is how you learn the collision exists at all.

| Records | The trap |
|---|---|
| ADR-036 × ADR-022 | The credential checklist R1–R8 does **not** apply to a confidential internal identifier. Knowing ADR-022 well leads to over-applying it and reporting a server log line or a storage path as a leak. What closes a confidential identifier at the boundary is enumeration-oracle and BOLA risk, not secret leakage. *Stated in ADR-036 §3.* |
| ADR-036 × ADR-021 | ADR-021 §1's phrase "`TreeId` remains internal" means `TreeId` is never the credential, not that it never appears on the wire — ADR-021 §3's own endpoint design puts `{treeId}` in the path beside the key. Reading it as confinement contradicts ADR-036 §4. *Stated in ADR-036's references.* |
| ADR-035 × ADR-036 | The opaque-status rule and the expiry status look like a conflict and are not. The rule binds where the caller's entitlement is in question; an expired workspace answers `410 Gone` to the holder of its key because that discloses nothing the holder did not know. Applying the 404 rule there loses a correct status for no security gain. *Stated in ADR-035 §3.* |
| ADR-034 × ADR-014 | `raw` is cached; `mitigated` is never stored. Applying a transform to a cached `raw` total is **wrong, not approximate** — it cannot see a reduction already applied below it. Only folding the mitigated children attributes each reduction to the layer it happened at. *Worked example in ADR-034 §1.* |
| ADR-032 × ADR-014 | Two hashes exist over the same node. The cache key uses the **domain content** relation (`LeafSimContent` projection); the Irmin blob hash answers a different question. Picking the wrong one breaks caching silently — both are plausible and neither errors. |
| ADR-015 × ADR-014 | ADR-015 specifies an `invalidate` method and an ancestor-path eviction walkthrough that ADR-014 §2 forbids outright. A reader who takes ADR-015 at face value reintroduces the stale-entry window content addressing was adopted to remove. *Each such passage in ADR-015 now carries a retirement marker in place.* |
| ADR-017 × ADR-014 | ADR-017's endpoint table used to list a cache-invalidation route. No such endpoint exists and the concept is forbidden; the table now says so. |
| ADR-030 × ADR-024 × ADR-021 | `workspaceStore.resolve` is the Layer 0 capability gate and is the one call that legitimately precedes `authz.check()`. Not knowing this produces either a false bypass report against correct code, or a missed real bypass behind an `// exempt:` that looks like it. |
| ADR-030 × ADR-024 | `BootstrapProvisioner.recordOwnership` writes a SpiceDB tuple from application code, which reads as a violation of the read-only enforcement point. It is not: the gate is whether a user request drives the write. A system recording the creator at creation time is a lifecycle write; a user delegating access is administration. *Stated in ADR-024 §4.* |
| ADR-029 × ADR-028 | The query parser is a live boundary taking user-typed text. Node references resolve through per-sort literal validators and are never interpolated — a query built by interpolation is re-parsed and is the injection path. *Row in ADR-029 §3's table.* |
| ADR-031 × ADR-012 | A retry loop belongs to Istio or to application bootstrap, decided by one test: in-flight request, or process lifecycle. A readiness gate inside a request handler violates both records at once. |
| ADR-012 × ADR-021 | ADR-012 §4 forbids application-level rate limiting and ADR-021 §4 says workspace creation needs a limit. Both hold: the limit belongs at the gateway and does not exist yet. A per-**credential** limit is the one that would legitimately sit in the application. |
| ADR-025 × ADR-027 | Both describe the `/w/*` Accept-header rule. ADR-025 decides path routing and states the requirement; ADR-027 owns the nginx implementation, and `ADR-INFRA-007` in `register-infra` governs where they diverge. Editing the rule in one place only leaves two descriptions of one behaviour. |
| ADR-036 × ADR-004a | Two branch-name types embed the `WorkspaceId` — `BranchRef` and `MergeStagingRef` — so neither may appear in a wire-facing type. The containment is by type: the branch primitives take the sealed `StoreBranch`, everything that reads or writes tree data is declared over `BranchRef` alone, and `BranchChoice.fromBranchRef` takes `BranchRef` rather than `StoreBranch`, so a staging branch is excluded by a signature rather than by a check. *Stated in ADR-036 §2.* |
| ADR-034 × ADR-001 | The no-elimination rule has two enforcement points and ADR-001's validate-at-the-boundary rule accounts for only one. A type closes the cases the parameter decides alone; the cases the outcomes decide cannot be typed at all, because the same parameter is ordinary for one node and annihilating for another. A new transform is checked against the layer check, never assumed covered by a type. *Stated in ADR-034 §6.* |
| ADR-018 × ADR-022 | The two wrapper patterns are deliberately opposed. ADR-018 wants transparent serialisation and pattern matching; a credential type exists to prevent both, which is why R1 forbids the `case class` ADR-018 prescribes. Applying ADR-018's pattern to a credential reintroduces `copy`, `unapply` and an unredacted `toString`. *Stated in ADR-022 §1.* |
| ADR-038 × ADR-022 | ADR-038 §4 removes the serialiser from a confidential identifier, and ADR-022's credential keeps one. Reading §4 as "sensitive values are not serialisable" deletes the codec the capability key needs, and the bootstrap response stops compiling. The test is what the value is for: an internal scoping key must never cross, a bearer credential exists in order to cross. *Stated in ADR-038 §4.* |
| ADR-038 × ADR-001 | ADR-038 §1 puts the wire shape **inside** the domain type's companion, which reads as abandoning the separation ADR-001 is cited for. It is what delivers it: the decoder routes through the smart constructor, so validation happens in the codec exactly as ADR-001 requires, and on both sides of the wire at once because the module is cross-compiled. A public parallel class would satisfy the letter and lose the invariants. |
| ADR-038 × ADR-037 | ADR-037 §2 permits a declared spelling where the string contracts with something other than the serialiser, and that carve-out does **not** reach a server-sent event. An event is an operation outcome, so ADR-038 §2 governs it and the codec is derived; the hand-written `toWire` pair is non-conforming rather than a licensed exception. |
| ADR-003 × ADR-032 | `seedVarId` is in the hashed projection and the node's name is not. That is what makes a rename free — it changes no figure and invalidates no cache entry — and it is also why a seed identifier must never be re-issued: two different risks sharing one would collide in the cache. *Stated in ADR-003 §2.* |

A change that spans two rows of Layer 1 is where a new collision is likely. When one is found, write the reconciliation into both records and add a row here: the records carry the content, this table makes the collision findable.

---

## The review this skill exists to serve

Routing the records is step one of a two-step obligation, and both steps are
presentations that halt (`CLAUDE.md`, G4 and G6).

**Before any code.** Identify the activity in Layer 1, read every record its
*always* column names, and read the *also* rows that apply. Then state, for each
record, one of two things: the change complies with it, or it deviates and why.
A deviation is presented in the Decision Required format below and waits. A
record that bears on the change and is not mentioned in that statement has not
been reviewed, whatever the task's wording said.

**After the implementation.** Re-read the same set against what was actually
written, because an implementation drifts from its own plan in small ways that
each look harmless. Check specifically for a constraint satisfied in the plan and
lost in the code. Report what you find and wait before calling the phase
complete.

The structural checks that used to sit in this second pass — an ADT with a
data-carrying success case and no `map`, a public method with no call site, an
inline lambda long enough to deserve a name, a hand-written match that `map`
would collapse — are not repeated here. They live where they are applied:
`.claude/rules/scala-style.md` and the `code-quality-review` skill.

## Amending the corpus — what a change to a record owes

A plan that adds, amends or retires a record carries these in the same plan, not
as a follow-up. They are the reason this document can be trusted to route.

**A new record is placed in all three layers.** A row in Layer 2 by subject, an
entry in every Layer 1 activity it bears on, and a Layer 3 row for any collision
it creates. Layer 1 is the one that matters most and the one with no mechanical
check: it is what makes a record reach work whose wording never mentions it. A
renamed file gets its Layer 2 row corrected.

**An interaction is written into both records *and* indexed in Layer 3.** Where
the change makes two records bear on the same code in a way neither states
alone, the reconciliation goes into both records under a heading naming the
other, and the Layer 3 row is what makes the collision findable at all. The
records carry the content; the table carries only its existence.

**A retired passage is marked where it sits.** A record stays in force while one
of its sections is superseded, so the marker goes at that section — not only in
a note at the top, which a reader arriving at the section has already walked
past. Say what must not be implemented, and name what holds instead.

Then run the Layer 2 check below and leave it printing nothing.

## Escape-hatch triggers — stop and ask

Canonical list: `CLAUDE.md`, Decision Triggers (G2). This copy must stay verbatim-aligned with it. Any of the following require a Decision Trigger before proceeding:

1. Changing an API shape, Tapir endpoint signature, or OpenAPI output
2. Any `asInstanceOf`, `Schema.any`, or unsafe cast
3. Adding a new library dependency
4. Modifying an existing `case class` field, opaque type, or public method signature
5. Changing behaviour of existing code (not adding new code alongside it)
6. Any solution with tradeoffs or caveats
7. Any recursive or self-referential type requiring special serialization
8. Removing, weakening, or reframing any test assertion
9. Following any instruction rule appears to produce a demonstrably worse outcome, or conflicts with another instruction (including system/harness autonomy defaults) — name the rule and the concern; escalation is the only exit, silent deviation and silent compliance are both violations (G7)

Format:
```
⚠️ Decision Required
Context: [what was being implemented]
Issue: [what problem arose]
Options: A) … B) … C) …
Decision needed: [single specific closed question]
```

---

## Keeping this document correct

A new record gets a row in Layer 2, an entry in every Layer 1 activity it bears on, and a Layer 3 row for any collision it creates. A renamed file gets its Layer 2 row corrected. Nothing else here changes, because nothing else here carries content.

This check prints any record missing from Layer 2, and should print nothing:

```sh
cd docs/dev/decision-records
D=../../../.claude/skills/adr-constraints/SKILL.md
for f in ADR-*.md; do grep -q "\`$f\`" "$D" || echo "MISSING $f"; done
```

Layer 1 has no mechanical check, and that is its weakness: a record can be in force, listed in Layer 2, and absent from every activity that should name it. The only guard is that adding a record includes placing it, which is why the plan gate names all three layers.

