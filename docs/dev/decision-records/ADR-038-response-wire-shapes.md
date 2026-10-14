# ADR-038: Response Shapes at the Wire Boundary

**Status:** Accepted
**Date:** 2026-10-14
**Tags:** dto, wire-format, serialization, type-safety, confidential-identifiers

---

## Context

- Two properties are wanted from a response shape. The receiving side must know the admissible values of every field it reads. A confidential internal identifier must be unable to reach the wire at all.
- The first is a typing property. JSON carries no range, no length bound and no character-set restriction, so the only way the far side knows them is to decode into the same refined type the near side encoded from. A cross-compiled shared module is what makes that possible, because both sides then compile one definition.
- The second is not a property of the response type. A response that omits a field is a convention somebody follows; a field whose type has no serialiser is a compile error. Only the second holds without review.
- A response is one of two things: a view of a domain aggregate, or the outcome of an operation that has alternatives. One rule covering both produces a string-typed field wherever the shape does not fit, because the author has nowhere else to put the distinction.
- A hand-written projection is where refinement is lost. It is the point at which a field's type is chosen by hand, so it is the point at which a refined type becomes `String`.
- Validation belongs at the boundary and happens once (ADR-001). A decoder that routes through a smart constructor satisfies that on both sides of the wire at the same time, because the decoder is the boundary.

---

## Decision

### 1. A View of an Aggregate Declares Its Wire Shape Privately, in the Aggregate's Companion

The endpoint declares the domain type. The domain type owns a private case class whose fields are exactly the wire fields, projects onto it to encode, and decodes through its own smart constructor:

```scala
object RiskTree:
  // Wire-shape DTO: mirrors JSON wire format (omits non-serialized TreeIndex).
  private case class RiskTreeJson(
    id: TreeId, name: SafeName.SafeName, nodes: Seq[RiskNode],
    rootId: NodeId, seedVarHighWater: SeedVarId.SeedVarId,
    mitigations: Option[Seq[Mitigation]]
  )

  given codec: JsonCodec[RiskTree] = JsonCodec(
    JsonEncoder[RiskTreeJson].contramap[RiskTree](toJsonShape),
    JsonDecoder[RiskTreeJson].mapOrFail { json => fromNodes(...).toEither.left.map(...) }
  )
```

Three things follow, and they are the reason for this shape. The wire shape and the internal shape differ where they must — a derived index is not serialised, and an absent collection decodes to empty. There is no public twin class to keep in step. Every invariant the smart constructor enforces is enforced again on decode, so a decoded value is as trustworthy as a constructed one, in the browser as well as on the server.

A public parallel class projected field-for-field is not used. It duplicates the type without adding a guarantee, and it discards the invariants the aggregate's constructor holds.

### 2. An Operation Outcome Is a Public Sealed Enum With a Derived Discriminated Codec

Where the response reports which of several things happened, the alternatives are the type. The enum is public and lives in the cross-compiled shared module, the tag field is named by annotation, and the codec is derived:

```scala
@jsonDiscriminator("kind")
enum MergePreviewStatus:
  case Clean()
  case Conflicts(paths: List[MergeConflictEntry])
  case DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])
  case ScenarioMissing()

object MergePreviewStatus:
  given codec: JsonCodec[MergePreviewStatus] = DeriveJsonCodec.gen[MergePreviewStatus]
```

Each case carries the values that belong to it, so no case's payload sits in a sibling field that may or may not be populated. A match is exhaustive, which is what lets the reading side drop the wildcard arm that would otherwise render an unrecognised outcome as an empty result.

A server-sent event is an outcome, not a view, so it takes this shape. It is not an exception to this ADR and does not get a shape of its own: an event names which of several things happened, which is what clause 2 governs.

### 3. No Wire-Facing Field Is a Raw Primitive Where a Refined Type for That Concept Exists

A field's type is the only thing that tells the far side what values it may hold, so a concept with a defined valid-value subset carries the type that states it:

```scala
// BAD: the reader learns that it is a number
.out(jsonBody[Double])

// GOOD: the reader learns the closed interval, and decoding rejects anything outside it
.out(jsonBody[OccurrenceProbability])
```

This applies to the key type of a map as much as to a field. A map whose keys are a fixed set of names is an enum-keyed map or a case class, not `Map[String, _]`.

A response body is never a bare primitive and never a bare collection. A single figure is clause 1 with a one-field wire shape carrying its refined type.

### 4. A Confidential Internal Identifier Has No Serialiser

The identifiers ADR-036 confines carry no `JsonEncoder`, no `JsonDecoder`, and no Tapir `Schema` or `Codec`. Deriving a codec requires an instance for every field, so a wire-facing type that names one does not compile:

```scala
object WorkspaceId:
  // No JSON codec, and no Tapir Schema: a confidential internal identifier is
  // not serialisable, so a wire-facing type that names one fails to compile
  // rather than relying on review to catch the field (ADR-036).
```

The absence is deliberate and is documented where the instances would have been, because an unexplained absence invites restoration.

This clause names ADR-036's identifiers and not sensitive values in general. A bearer credential is the opposite case: `WorkspaceKeySecret` keeps its codec because the capability key is the one value the client must be given, and `WorkspaceBootstrapResponse` returns it. The distinction is what each value is for — an internal scoping key must never cross, a credential exists in order to cross.

The clause closes the accidental route, not deliberate widening. A field declared as the inner refined type, or populated from `.value`, still compiles; that is governed by the `.value`-at-a-boundary rule in the project's algebraic-design rules.

### 5. The Projection Is Named `toWire`, Owned by the Source, and Returns a Typed Value

The source type owns the conversion, its inverse is total and named `fromWire`, and neither widens to `String` unless the string is a contract with something other than the serialiser (ADR-037):

```scala
enum BranchChoice:
  case Main
  case Scenario(name: ScenarioName.ScenarioName)

  /** Wire shape: an absent value means main. Use only at wire/codec boundaries. */
  def toWire: Option[ScenarioName.ScenarioName] = this match
    case Main           => None
    case Scenario(name) => Some(name)

object BranchChoice:
  def fromWire(wire: Option[ScenarioName.ScenarioName]): BranchChoice =
    wire.fold(Main)(Scenario(_))
```

The projection lives on the source rather than as a factory on a destination type, so the shared module's wire types depend on nothing. A factory on the destination would make the wire type depend on the domain type, which is the coupling such a factory is usually adopted to avoid.

The conversion happens exactly once, at the boundary, and the scaladoc says so. No state past the boundary holds the wire form, so no later reader has to interpret an encoding.

---

## Code Smells

### ❌ String Standing In for a Closed Set on a Response

```scala
// BAD: four values, none of them stated; an unknown value is indistinguishable from a known one
final case class ChangedNodesResponse(status: String, entries: List[NodeChangeEntry])

// GOOD: clause 2
@jsonDiscriminator("kind")
enum ChangedNodesStatus:
  case Ok(entries: List[NodeChangeEntry])
  case MissingOnA()
  case MissingOnB()
  case MissingOnBoth()
```

### ❌ Bare Primitive or Map as a Response Body

```scala
// BAD: the reader learns nothing about range, keys, or meaning
.out(jsonBody[Double])
.out(jsonBody[Map[String, String]])
```

### ❌ `Schema.any` on a Wire Type

```scala
// BAD: erases the generated schema for the whole response, so the documented contract is empty
given schema: Schema[DistributionPreviewResponse] = Schema.any[DistributionPreviewResponse]
```

`Schema.any` is also a decision trigger in its own right. A response whose schema cannot be derived has a shape problem, and the schema is the symptom.

### ❌ Hand-Rolled Discriminator Beside a Derived Codec

```scala
// BAD: the tag is a method the serialiser does not see, so the payload and its name
// are produced by two different mechanisms and can disagree
sealed trait SSEEvent:
  def eventType: String
```

### ❌ Projection That Widens

```scala
// BAD: the refined type is discarded at the one point that decides the field's type
def toWire: String = this match
  case Added => "added"
```

Permitted only where the string is a contract with something other than the JSON encoder, which ADR-037 §2 defines.

---

## Implementation

| Location | Clause |
|----------|--------|
| `RiskTree.RiskTreeJson`, and the `Raw` projections in `Mitigation`, `Provenance`, `RiskLeafTransform`, `LeafSimContent`, `ResultTransformSpec` | 1 — private wire shape in the owner's companion |
| `MergePreviewStatus` | 2 — public sealed enum, derived discriminated codec |
| `WorkspaceId` | 4 — no serialiser |
| `WorkspaceKeySecret` | 4 — the credential exception, codec retained |
| `BranchChoice` | 5 — typed projection, total inverse, boundary-only |

Known non-conforming occurrences at the date of this record: `ChangedNodesResponse.status` and `NodeChangeEntry.status`/`nodeId` (clause 3); `HistoryOperation`'s hand-written codec with a catch-all arm (clause 3 and ADR-037); `SSEEvent`'s hand-rolled discriminator and the `NodeChangeType`/`ConnectionState` string projections (clauses 2 and 5); `DistributionPreviewResponse`'s `Schema.any`, `Array` field, `distributionType: String`, and its placement in `http/requests/` (clause 3); `SimulationResponse.name` and `quantiles` (clause 3); the probability-of-exceedance, health and reap-count endpoints returning bare primitives and maps (clause 3).

Cleanup of a non-conforming occurrence is an obligation of the `boyscout` skill: a file opened for any other reason is brought into conformance in the same pass. This ADR does not authorize opening a file to clean it.

---

## References

- ADR-001 — validation happens in the codec, once; a decoder routed through a smart constructor is how clause 1 satisfies it on both sides
- ADR-017 — request shapes, the inbound counterpart to this record
- ADR-018 — nominal wrappers for semantically distinct values sharing an encoding
- ADR-022 — credential types; the bearer credential is the one value clause 4 does not confine
- ADR-029 — the content domain a string-backed field may admit
- ADR-035 — what a failure may disclose; the error path's wire shape is governed there
- ADR-036 — which identifiers may cross the client boundary; clause 4 is its enforcement mechanism
- ADR-037 — closed sets are enums with derived codecs, and when a spelling is declared instead
