# Working Instructions

This document defines the working protocol for implementing the ADR proposals.

---

## Governance

### Progress Control

- **User is in charge** of progress approval, review, and decisions
- Each phase requires **explicit approval** before proceeding to the next
- Agent will **ask questions** when facing ambiguity rather than making assumptions
- Agent will **not proceed** to the next phase without user's "proceed" confirmation

### Decision Protocol

1. Agent presents options or proposed approach
2. User reviews and either approves, requests changes, or asks questions
3. Agent implements only after approval
4. Agent presents results for review before marking phase complete

---

## Code Standards

### Scala / ZIO

- Code must be **idiomatic Scala 3** with ZIO 2.x patterns
- Follow existing codebase conventions (check existing files for style)
- Use ZIO Prelude types where applicable (`Identity`, `Validation`, etc.)
- Prefer `for` comprehensions for ZIO effect composition
- Use Iron refined types for domain validation (per ADR-001)

### OCaml

- **Minimize OCaml code** — prefer using Irmin as an external service
- Only write OCaml if absolutely necessary for Irmin integration
- **Notify user before generating OCaml** — will establish OCaml-specific preferences at that time
- Goal: Irmin exposes GraphQL API; ZIO consumes it as a client

---

## ADR Compliance

### Validation Requirements

At each phase, validate implementation against:

1. **Accepted ADRs** (currently implemented):
   - ADR-001: Validation with Iron types & smart constructors
   - ADR-002: Logging strategy (ZIO logging + OpenTelemetry)
   - ADR-003: Provenance & reproducibility (HDR seeds)
   - ADR-009: Compositional Risk Aggregation via Identity

2. **Proposals being implemented** (validate as they're accepted):
   - ADR-004a-proposal: Persistence Architecture (SSE)
   - ADR-004b-proposal: Persistence Architecture (WebSocket)
   - ADR-005-proposal: Cached Subtree Aggregates
   - ADR-006-proposal: Real-Time Collaboration
   - ADR-007-proposal: Scenario Branching
   - ADR-008-proposal: Error Handling & Resilience

### ADR Lifecycle

```
Proposal → Implementation → Review → Accepted
                ↓
         (rename -proposal.md to .md)
         (update Status: Accepted)
```

When a phase completes and its ADR is validated:
1. Remove `-proposal` suffix from filename
2. Update status from "Proposed" to "Accepted"
3. Add acceptance date
4. Include in validation set for subsequent phases

---

## Implementation Principles

### Incremental Approach

- Small, reviewable changes per phase
- Each phase produces **working, testable code**
- Tests accompany implementation (not deferred)
- Compile and test before presenting for review

### Dependency Order

Implement in order of dependencies:
1. Foundation (error types, data models)
2. Infrastructure (clients, connections)
3. Services (business logic)
4. API layer (endpoints, SSE/WebSocket)
5. Frontend integration

### Irmin Strategy

- Treat Irmin as **external service** with GraphQL API
- ZIO backend is a **GraphQL client** to Irmin
- Avoid embedding OCaml in Scala build
- If Irmin requires custom schema or resolvers → notify user for OCaml discussion

---

## Communication Format

### Phase Presentation

```markdown
## Phase X: [Title]

### Objective
[What this phase accomplishes]

### ADR References
[Which proposals this implements]

### Validation Checklist
- [ ] Compliant with ADR-001 (Iron types)
- [ ] Compliant with ADR-002 (Logging)
- [ ] Compliant with ADR-003 (Provenance)
- [ ] Compliant with ADR-009 (Identity aggregation)
- [ ] [Additional validations as ADRs are accepted]

### Tasks
1. [Specific task]
2. [Specific task]
...

### Questions for User (if any)
- [Question about ambiguity]

### Approval Checkpoint
- [ ] Code compiles
- [ ] Tests pass
- [ ] User approves
```

### Completion Report

```markdown
## Phase X Complete

### Implemented
- [List of what was built]

### Files Changed
- `path/to/file.scala` — [description]

### Tests Added
- [Test file and coverage]

### ADR Status
- [Proposal name]: Ready for acceptance / Needs more work

### Ready for Review
[Summary for user to review]
```

---

## Questions Protocol

When agent encounters ambiguity:

1. **Stop implementation** at the unclear point
2. **Present context** — what was being attempted
3. **List options** — if applicable
4. **Ask specific question** — not open-ended
5. **Wait for answer** — do not assume

Example:
> I'm implementing the cache invalidation logic and need clarification:
> 
> **Context:** ADR-005 mentions cache key could use `nodeId` or `contentHash`
> 
> **Options:**
> - A) Use `nodeId` only (simpler, explicit invalidation)
> - B) Use `contentHash` (content-addressed, auto-dedupe)
> 
> **Question:** Which approach should I implement?

---

## Checkpoints

User will confirm at these points:

- [ ] Working instructions reviewed and approved
- [ ] Implementation plan reviewed and approved
- [ ] Each phase completion approved
- [ ] Each ADR acceptance approved
- [ ] Final integration approved

---

*Document created: 2026-01-17*  
*Status: Awaiting user approval*
