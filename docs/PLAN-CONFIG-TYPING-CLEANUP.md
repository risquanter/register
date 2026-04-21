## Phase Plan: Option B — Structured Config Typing Cleanup

### Objective
Implement a **complete config typing cleanup by category**, not by blanket replacement:

1. **Closed finite choices** → typed enum / sealed ADT  
2. **Validated open text** → Iron type or equivalent validated type  
3. **Infrastructure secrets** → `Config.Secret` per ADR-022  
4. **Intentionally open text** → remain `String` unless a domain constraint is explicitly justified

This includes branch names, which must gain a **meaningful validation rule** and a typed representation.

---

### ADR References
- ADR-001 — validation at the boundary with Iron / smart constructors
- ADR-011 — import hygiene
- ADR-018 — nominal wrappers only where semantic distinction justifies them
- ADR-022 — infrastructure config secrets use `Config.Secret`
- ADR-016 — retain ZIO Config + canonical config loading pattern
- ADR-007-proposal — branch naming requires finalized validation rules

---

### ADR Compliance Review (Planning Phase)

**Reviewed ADRs:** accepted ADR set, with direct constraints from ADR-001, ADR-011, ADR-018, ADR-022, and ADR-016; relevant proposal constraints from ADR-007-proposal.

**Alignment notes**
- **ADR-001:** config decoding is a boundary; invalid values should fail during config load, not later in application wiring.
- **ADR-018:** not every string should become a nominal wrapper. Use wrappers only where there is real domain identity distinction. Closed selectors fit enums better than wrappers.
- **ADR-022:** passwords and similar infrastructure credentials loaded from config should move to `Config.Secret`.
- **ADR-016:** keep [modules/server/src/main/resources/application.conf](modules/server/src/main/resources/application.conf) as canonical source; keep `Configs.makeLayer` pattern.
- **ADR-007-proposal:** branch names require explicit validation rules before implementation.

### ⚠️ ADR Deviation / Decision Gate
**Affected ADR:** ADR-007-proposal  
**Deviation risk:** branch validation rules are not finalized in the ADR text.  
**Plan impact:** implementation must not invent a silent rule.  
**Required handling:** present a concrete branch validation proposal for approval before branch typing is implemented.

---

## Proposed Implementation Plan

### Phase 1 — Full Config Surface Inventory
Produce a field-by-field classification of **all current stringly typed config values** in server config models.

#### Output categories
- **Finite selector**
- **Validated URL / validated text**
- **Secret**
- **Open text kept as string**
- **Branch/domain text needing explicit rule**

#### Files in scope
- [modules/server/src/main/scala/com/risquanter/register/configs](modules/server/src/main/scala/com/risquanter/register/configs)
- [modules/server/src/main/resources/application.conf](modules/server/src/main/resources/application.conf)

#### Deliverable
A table like:

| Field | Current type | Category | Proposed target type | Why |
|---|---|---|---|---|

#### Notes
No edits yet. Classification only.

---

### Phase 2 — Branch Name Validation Proposal
Because ADR-007 leaves branch validation open, this phase is a **decision checkpoint**, not implementation.

#### Proposed branch constraints to present for approval
A conservative candidate:
- allowed chars: lowercase letters, digits, `_`, `-`, `/`
- must start with a letter or digit
- no empty path segments
- no leading `/`
- no trailing `/`
- no `//`
- max length cap
- reserve `main`

#### Candidate representation
Prefer:
- an **Iron-refined base type** for validated branch string
- plus a **nominal wrapper** only if branch references are used as a domain identity across services/repositories

#### Why this shape
- validation belongs to ADR-001 style boundary parsing
- nominal wrapper only if ADR-018 criteria are satisfied
- avoids arbitrary wrapper creation

#### Deliverable
A short branch-rule proposal with:
- exact regex / rule set
- examples accepted
- examples rejected
- chosen representation:
  - Iron-only
  - or Iron + nominal wrapper `BranchRef`

#### Hard gate
No branch implementation before approval of the rule set.

---

### Phase 3 — Closed-Choice Selector Typing
Convert finite selector config fields to typed enums / sealed ADTs with explicit config decoding.

#### Expected targets
- `repositoryType`
- `workspaceStore.backend`
- `auth.mode`
- any other finite selector discovered in Phase 1

#### Design rule
Use **Scala 3 enums** unless there is a strong reason otherwise.

#### Why
- finite closed domain
- exhaustive matching
- eliminates manual `.trim.toLowerCase` branching
- best fit for ADR-001 and type-safety goals
- does **not** require nominal wrappers per ADR-018

#### Tasks
1. add selector ADTs
2. add config decoders
3. update call sites in app wiring
4. add config-loading tests for valid/invalid cases

---

### Phase 4 — Secret Config Cleanup
Convert infrastructure-secret config fields to `Config.Secret` where appropriate.

#### Expected targets
- Flyway password
- DB password
- any other config-loaded credential discovered in Phase 1

#### ADR basis
ADR-022 explicitly says infrastructure config secrets should use `Config.Secret`.

#### Tasks
1. identify all password/credential-bearing config fields
2. convert config model fields
3. update consumption sites to use explicit extraction at the boundary
4. ensure no accidental logging / interpolation of secret values
5. add tests if decoding behavior changes materially

#### Constraint
This phase applies only to **config-loaded infrastructure secrets**, not request-lifecycle credentials.

---

### Phase 5 — Validated Non-Secret Text Cleanup
Apply typed validation only where the domain is clearly constrained and already justified.

#### Expected targets
- existing validated URL fields
- any other constrained text field discovered in Phase 1

#### Important rule
Do **not** generalize or centralize local validation helpers unless explicitly justified by:
- multiple concrete use sites
- no loss of file-local safety rationale
- no collision with existing derivation behavior

This is specifically meant to avoid repeating the earlier `IrminConfig` mistake.

#### Tasks
1. keep or strengthen existing local validated decoders where justified
2. only share codecs if there are multiple real use sites
3. add tests for invalid config rejection

---

### Phase 6 — Intentionally Open Text Review
For remaining string fields, explicitly decide to keep them as `String`.

#### Likely examples
- service name
- instrumentation scope
- hostnames if intentionally broad
- database name / user / server name if no accepted validation rule exists yet

#### Deliverable
A short “kept as string by design” note in the completion report so the cleanup is complete, not partial.

---

### Phase 7 — Tests and Compliance Review
Add / update tests for:
- valid selector decoding
- invalid selector rejection
- secret config loading shape
- validated text rejection
- branch name validation once approved

#### Compliance review checklist
- ADR-001: invalid config fails at load boundary
- ADR-011: import hygiene preserved
- ADR-018: wrappers used only where justified
- ADR-022: config secrets not modeled as raw strings
- ADR-016: canonical config + `Configs.makeLayer` pattern preserved
- ADR-007: branch rule implemented exactly as approved

---

## Branch Typing Strategy Proposal for Review

Because you explicitly require meaningful validation and an Iron type or similar alternative, the current planning recommendation is:

### Preferred branch design
- **Base validation:** Iron-refined branch string
- **Representation choice after approval:**
  - **Iron-only** if branch values stay mostly at config/client boundary
  - **Iron + nominal `BranchRef`** if branch references flow broadly through repository/service APIs as domain identity

### Reason
This is the cleanest reconciliation of:
- ADR-001 validation
- ADR-018 wrapper discipline
- the ADR-007 need for explicit branch constraints

---

## Validation Checklist
- [x] Compliant planning shape for ADR-001
- [x] Compliant planning shape for ADR-016
- [x] Compliant planning shape for ADR-018
- [x] Compliant planning shape for ADR-022
- [x] Branch naming identified as ADR-007 decision gate
- [ ] User approves the phased plan
- [ ] User approves branch validation proposal phase before implementation

---

## Approval Checkpoint

If approved, the next step should be:

**Phase 1 output only:** a complete field-by-field config classification table, with no edits.

Stopping here.
