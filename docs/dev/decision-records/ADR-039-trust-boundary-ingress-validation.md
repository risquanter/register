# ADR-039: Trust-Boundary Ingress Validation

**Status:** Accepted (awaiting implementation)  
**Date:** 2026-10-18  
**Tags:** validation, boundary, security, iron, errors

---

## Context

- A running process controls how values in its own memory are constructed. It
  controls nothing about what another process hands it.
- A value held in process memory was built by code in this repository, so its
  type is proof that the constraints held at construction. A value arriving
  from outside carries bytes, and bytes are not proof.
- A value this service wrote and later reads back is not the same value. While
  it was outside the process, no type enforced its invariants.
- Crossing a boundary widens a refined type to a primitive, because a wire
  carries bytes. The proof does not cross with the value.
- Unrefined content that reaches an interpreter is an injection sink regardless
  of which side of the boundary wrote it.

---

## Decision

### 1. The trust region is the memory of one process; the boundary is its edge

Code running in one process, holding values that code in this repository
constructed, is **inside**. Everything else is outside, including a store this
same service wrote to. The border is where a value enters process memory from
outside, and that is the only place refinement belongs.

Two processes run this project's code and each has its own region: the server on
the Java Virtual Machine, and the single-page application in the browser. The
Tapir endpoint definitions are shared and interpreted on both sides, so the
browser's decode of a server response refines through the same types the server
used.

The server's borders:

| Crossing | Ingress point |
|---|---|
| Browser or API client → server | Tapir codec / JSON decoder |
| Irmin store → server | the repository's decode of a stored value |
| Postgres → server | the row-to-record conversion |
| SpiceDB → server | the response parse |
| Service mesh → server | the typed claim-header input |
| Environment / configuration → server | the config decoder |

Each refines into the refined and nominal types the domain declares, using the
same refinements the request path uses. A crossing that yields a raw primitive
where a refined type exists has not been validated. Every value that enters an
aggregate is refined, and a value that does not refine is refused: no site
admits a boundary value unrefined on the ground that the peer might hold
content this service did not write. The store holds only what this service
wrote, and the authorization service answers only about this service's own
identifiers, so that ground never holds, and a comment claiming it cannot be
checked.

Every border is crossed in both directions, and this record governs one of them.
What may be built into a document sent out to an external parser is ADR-029 §3.
What must be refined on the way in is here. A value that arrives and is then
interpolated into a new outgoing document is subject to both.

### 2. Inside the region, nothing validates

ADR-001 §2 states that controllers and services "receive already-validated Iron
types and perform no validation". "Service" there is the ZIO service: the
in-process implementation of business logic, not a network peer. That sentence
constrains code inside the region and does not say where the region ends. This
record supplies the other half, so the two compose: ADR-001 forbids validating
inside, and this one says where outside begins.

A component may straddle the border. A repository's signature side is inside —
it takes a `TreeId` from a caller and trusts it, and re-refining there is the
duplication ADR-001 forbids. Its decode of a stored value is the border, and
refining there is this record's requirement rather than a violation of ADR-001.
The test is never what the component is called; it is whether the value in hand
has just arrived from outside the process.

```scala
// Border — bytes just arrived from the store, so they are parsed into types.
// IngressRejection stands for the typed rejection named in clause 4.
def decodeStoredNode(json: String): Either[IngressRejection, RiskNode]

// Inside — the caller's value was built in this process, so it is trusted
def getById(wsId: WorkspaceId, id: TreeId): IO[Failure, Option[RiskTree]]
```

### 3. A composite goes through its smart constructor, not its field types alone

Refining each field proves each field. It does not prove the cross-field rules
the aggregate owns, and those are what a corrupted or tampered store would
violate.

```scala
// Store read reconstructs the aggregate through the same gate a request uses
RiskTree.fromNodes(meta.id, meta.name, nodes, meta.rootId,
                   Some(meta.seedVarHighWater), mitigations.toList)
```

### 4. An ingress rejection is a typed value, distinct from a transport failure

Two different things fail at a boundary. The peer could not be reached, and the
peer returned content this service refuses. One is retried or reported as a
dependency outage; the other never succeeds on retry, so collapsing them loses
the distinction the caller acts on.

An ingress rejection names the boundary, the location within the payload, and
carries the accumulated `ValidationError` list. It carries no composed sentence,
and no caller recovers a decision from its text. The concrete error type is
declared by the plan that implements this record.

---

## Code Smells

### ❌ A boundary response type declared over raw primitives

```scala
// BAD — the store's answer is held untyped
final case class IrminCommit(hash: String, key: String, parents: List[String], info: IrminInfo)

// GOOD — the raw answer is one type, the refined value another, and the
// refinement between them refuses what does not fit
final case class IrminCommit(hash: CommitHash, key: CommitHash, parents: List[CommitHash], info: IrminInfo)
object IrminCommit:
  final case class Raw(hash: String, key: String, parents: List[String], info: IrminInfo.Raw)
  def refine(raw: Raw): Either[NonEmptyChunk[ValidationError], IrminCommit]
```

### ❌ Flattening an accumulated validation at ingress

```scala
// BAD — the structure exists and is discarded where it is richest
.left.map(errors => new IllegalStateException(errors.map(ValidationError.describe).mkString("; ")))

// GOOD — the errors survive as values, refused at the zone they came from
.left.map(errors => Failure.rejected(TrustZone.Peer(PeerZone.Store), errors, Nil))
```

### ❌ Recovering a decision from a boundary error's text

```scala
// BAD — the branch depends on a message another system wrote
case e: PSQLException if e.getMessage.contains("seed_entity_id") => ...

// GOOD — the branch reads the constraint name the driver reports as a field
violatedConstraint(err) match
  case Some(SeedEntityIdConstraint) => inUse
  case _                            => classify(err)
```

---

## Implementation

| Location | Pattern |
|---|---|
| `BaseEndpoint.authedBaseEndpoint` | Mesh claim header typed `UserId.Authenticated` at ingress |
| `RiskTreeRepositoryIrmin.rebuildTree` | Store read routed through `RiskTree.fromNodes` |
| `IrminClientLive.childPaths` | Store-returned names refined to `IrminSegment`, failure typed |
| `IrminClientLive.refineCommit`, `refineBranch` | Store-returned commits and branch names refined to `IrminCommit` and `StoreBranch`, failure typed |
| `AuthorizationServiceSpiceDB.refineAnswer` | Authorization answer refined to `Permissionship`; a body or a spelling outside the protocol is refused |
| `WorkspaceStorePostgres.toRecord` | Database row refined to domain types on load |

---

## References

- [ADR-001: Validation Strategy](./ADR-001.md) — instantiates this rule for the HTTP request boundary, and owns what happens once a value crosses: smart constructors, accumulation, and no validation inside the region. Its "services perform no validation" binds in-process code, not the border; see §2 above
- [ADR-029: Input Injection Defence](./ADR-029-input-injection-defence.md) — the same boundaries in the outgoing direction. Its §3 table is egress, this record is ingress, and neither implies the other
- [ADR-010: Error Handling](./ADR-010.md) — the shape a failure takes once it exists. An ingress rejection is one of those typed failures, never a string another branch parses
