# PLAN — Authorization: Role Configurability Without Recompiling

Status: **PROPOSED — NOT APPROVED.** Created 2026-07-20. This document is a proposal
only. Do not start implementation from this document until it has been reviewed and
explicitly approved — see [Approval checklist](#approval-checklist) at the end.

## 1. Origin

While picking the `Permission` case for milestone-2b Phase B's `ScenarioService`
(`docs/scratch/milestone-2b-cache-and-decisions.md`, DD-9-adjacent work), the review
surfaced a broader question: the app's role/permission model was originally meant to
serve three purposes at once —

1. `register`/`register-infra` as reference projects demonstrating ReBAC/PEP
   authorization technique (Zanzibar/XACML-style separation), not just a working app.
2. The role hierarchy should be redefinable by an enterprise deployer via infra-side
   config/schema files, without changing or recompiling the Scala app.
3. The hierarchy should be fine-grained enough to express real, distinct user groups
   (not collapsed to a flat user/admin split).

This plan is the write-up of that review: what's true today, what's missing, and one
small proposed fix — for goal 2 specifically. Goals 1 and 3 were reviewed and judged to
have aged well; see §2. **This document also corrects a claim made earlier in the same
review conversation** — see §4.

## 2. Goals 1 and 3 — no action proposed

The four-tier `viewer < analyst < editor < team_admin` hierarchy
(`infra/spicedb/schema.zed`) is a genuinely hierarchical ReBAC schema (org → team →
workspace → risk_tree, with relation inheritance via `owner_team->design_write`-style
arrows), not a toy example — it holds up for goal 1. It also matches how BI/analytics
tools are conventionally tiered (viewer/explorer/creator/admin), supports least-privilege
and separation-of-duties better than a flat model would (an analyst can run scenarios
without being able to edit the canonical tree) — it holds up for goal 3. **No change
proposed here.** Collapsing the hierarchy to fewer tiers would be a regression against
both goals, not an improvement.

## 3. Goal 2 — current state (verified against code, 2026-07-20)

Three layers exist or are planned. Citations are exact; this section replaces informal
claims made earlier in conversation with verified ones.

### Layer 1 — Permission vocabulary (fixed, compiled — not in scope)

`Permission` enum (`modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala:13-31`).
The set of capabilities the app's code paths can check for (`DesignWrite`, `AnalyzeRun`,
`ViewWorkspace`, …). This **cannot** become admin-configurable without a rebuild — the
app's own control flow decides where it calls `check(SomePermission, …)`, and no
external config can add a check the code doesn't make. This is inherent to being a pure
Policy Enforcement Point (`ADR-024`), not a gap. Not in scope.

### Layer 2 — Role → permission mapping (SpiceDB) — mostly already config-driven

`infra/spicedb/schema.zed` defines the relations (`editor`, `analyst`, `viewer`,
`team_admin`, `owner_user`, `owner_team`, …) and expresses each fixed Layer-1 permission
as a union over them (`permission design_write = editor + owner_user + owner_team->design_write`,
schema.zed:50). SpiceDB treats this file as a document applied at deploy time
(`zed schema write`, run by a separate CI job in `register-infra`), not compiled into
anything. `ADR-030` §6 (`docs/dev/ADR-030-authorization-enforcement-orchestration-boundary.md:191-197`)
already documents an **enterprise override**:

```hocon
register.authz.provisioning {
  schema-source = "classpath:/spicedb/schema.zed"  # default: in-repo
  # schema-source = "https://policy.internal/schemas/register/v2.zed"  # enterprise override
}
```

A deployer can already point at their own schema, defining their own relation graph
(rename roles, add tiers, restructure the hierarchy), and re-provision tuples
accordingly — **no Scala rebuild** — as long as the resulting permission set still
includes the fixed names Layer 1 checks for. This is close to the goal already.

**One confirmed leak:** `BootstrapProvisionerSpiceDB.scala:82` hardcodes the relation
name `"owner_user"` when writing the bootstrap ownership tuple at workspace creation:

```scala
relationship = SpiceDbRelationship(
  resource = SpiceDbObjectRef("workspace", workspaceId.value),
  relation = "owner_user",   // ← hardcoded
  subject  = SpiceDbSubjectRef(...)
)
```

If a deployer renames or restructures the ownership relation in their own `schema.zed`,
this call site still writes the old name — silently wrong, not caught anywhere (same
class of `PERMISSIONSHIP_NO_PERMISSION`-style silent failure `ADR-030` already warns
about for the permission vocabulary, §6). This is the one concrete, fixable gap in
Layer 2. See §5.

### Layer 3 — OPA coarse gate — not yet built; earlier conversation claim corrected

**Not implemented.** No `infra/opa/policies/` directory and no `.rego` file exists
anywhere in this repository (verified by search, 2026-07-20). What exists is a design in
`AUTHORIZATION-PLAN.md` Task L2.4 (lines 933-1006): OPA would run at the Istio waypoint
(`ext_authz`), evaluating raw JWT claims directly, **before the request reaches the ZIO
app at all** — a coarse "does this role claim exist / is this HTTP verb allowed for it"
pre-filter, entirely separate from SpiceDB's fine-grained per-resource check.

`UserContext.Role` / `Role.fromClaim` (`UserContext.scala:12-25`) exist in Scala today,
but **have zero call sites anywhere in `modules/server/src/main`** (verified by search).
`UserContext.roles` is populated by `UserContextExtractor` but nothing branches on it.
It is unconsumed scaffolding from Task L1.1, not an active second enforcement layer.

**Correction to this conversation:** earlier in this review I stated that
`UserContext.Role` "feeds OPA" and that this constituted the defense-in-depth-relevant
duplication worth deciding on. That was imprecise in an important way: OPA (per the
documented design) reads the JWT directly at the mesh layer — it does not consume
anything the Scala app computes via `UserContext.Role`. Today, with no OPA policy files
in this repo, **there is no second independent enforcement layer running at all** — only
SpiceDB. `UserContext.Role`'s existence doesn't currently provide defense-in-depth by
itself; it's inert. I'm flagging this correction explicitly rather than letting the
earlier framing stand uncorrected in the record.

**Second correction — the "make OPA data-driven" idea from this conversation should be
dropped.** `AUTHORIZATION-PLAN.md:979` already documents this as an anti-pattern to
avoid: *"Do not sync relationship data (workspace members, tree assignments) into OPA
data bundles. This creates staleness, sync complexity, and defeats SpiceDB's purpose. A
revoked tuple would remain 'allowed' by OPA until the next bundle push."* The documented
design for OPA is: policy stays purely claim-based (JWT only, no synced data), authored
directly in Rego by the security team, and — per `AUTHORIZATION-PLAN.md:973` — **already
deployable independently of an app build**, exactly the "redeploy, not recompile"
property being asked for, just achieved by editing a `.rego` file instead of a config
document. When Layer 3 is eventually built (a separate, larger, not-yet-scheduled task —
L2.4), no additional "make it config-driven" work is needed; the existing design already
has this property. **No plan item proposed for Layer 3 here.**

## 4. What remains for goal 2 — narrower than earlier discussion suggested

Once §5's single fix ships, an enterprise deployer can already redefine the entire role
hierarchy — who exists, what they're called, what they grant — via `schema.zed` +
`ADR-030`'s existing schema-source override, with no Scala rebuild. The gap this review
started with is real but small: one hardcoded relation-name string, not a missing
architecture layer.

## 5. Proposed fix — parametrize the bootstrap ownership relation name

**What:** make the relation name `BootstrapProvisionerSpiceDB.recordOwnership` writes a
config value instead of a literal, defaulting to today's `"owner_user"`.

```hocon
register.authz.provisioning {
  ownership-relation = "owner_user"  # must match a relation the deployer's schema
                                      # grants admin_workspace/design_write through
}
```

```scala
// BootstrapProvisionerSpiceDB.scala — sketch, not final signature
relation = config.ownershipRelation   // was: "owner_user"
```

This is small, mechanical, single-call-site, no behavior change under the default
config. **Not proposing** a general "every relation name is config-driven" system —
this is the one call site where Scala writes a tuple using a relation name; there's no
second concrete use case today that would justify more than this.

## 6. Open decision — what to do with `UserContext.Role`

Unlike §5, this is a genuine choice, not a mechanical fix, presented decision-guide
style.

**Options considered:**

- **A — Delete it now.** Zero call sites today; the project's own code-quality
  convention treats a zero-caller public type as dead code (MUST-FIX). Cost: if Task
  L1.1's "viewer" TODO (`AUTHORIZATION-PLAN.md:247-251` — `Role.fromClaim` is documented
  as missing a `"viewer"` case) or the eventual OPA task want this scaffolding, it has to
  be rebuilt later — likely differently, once Layer 3's real shape is known.
- **B — Leave it as-is, unfinished.** No action. Cost: dead code sits in the tree
  indefinitely with a known, documented gap (missing `"viewer"` case) that nothing is
  tracking toward resolution.
- **C — Finish it now** (add the missing `"viewer"` case, matching the TODO) but still
  wire it to nothing, since Layer 3 doesn't exist yet to consume it. Cost: completes a
  type that still has zero callers — arguably worse than A, since it's effort spent
  polishing code nothing uses yet.

**My recommendation: A — delete it now**, and let Layer 3's eventual design (L2.4)
introduce whatever role-claim representation it actually needs at that point, informed
by the real OPA policy shape rather than guessed ahead of time. Reasoning: `UserContext.Role`
was scaffolded before Layer 3's design was fully settled (the settled design has OPA
read the JWT directly, not through this type), so keeping it doesn't preserve useful
groundwork — it preserves a guess that the later design doc already superseded.

## 7. Approval checklist

Nothing in this document is approved. Before any implementation begins:

- [ ] Confirm §5's config key name/shape (`register.authz.provisioning.ownership-relation`
      is a placeholder, not final)
- [ ] Decide §6 (A, B, or C)
- [ ] Confirm no other hardcoded relation-name call sites exist beyond
      `BootstrapProvisionerSpiceDB.scala:82` (this review searched `modules/server/src/main`
      only; `modules/server-it` test helpers also hardcode `"owner_user"`/`"viewer"` —
      test-only, not proposed for change here, but worth confirming during review)
- [ ] Confirm this doesn't conflict with any in-flight `register-infra` work on the
      provisioning job (out of scope for this repo's review)
