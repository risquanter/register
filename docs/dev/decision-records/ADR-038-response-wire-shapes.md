# ADR-038: Response Shape Selection

**Status:** Accepted
**Date:** 2026-10-14
**Tags:** dto, wire-format, serialization, type-safety, confidential-identifiers

---

## Context

- JSON carries no range, no length bound and no character-set restriction, so a receiving side knows a field's admissible values only by decoding into the same refined type the sending side encoded from; one cross-compiled definition is what makes that possible.
- A response that omits a confidential field is a convention somebody follows; a field whose type has no serialiser is a compile error. Only the second holds without review.
- A response is one of two things: a view of a domain aggregate, or the outcome of an operation that has alternatives. One rule covering both yields a string-typed field wherever the shape does not fit, because the author has nowhere else to put the distinction.
- A hand-written projection is the point at which a field's type is chosen by hand, so it is the point at which a refined type becomes `String`.
- Validation belongs at a boundary and happens once. A decoder routed through a smart constructor satisfies that on both sides of the wire at once, because the decoder is the boundary.

---

## Decision

### 1. A View of an Aggregate Declares Its Wire Shape Privately

The endpoint declares the domain type. That type owns a private case class whose fields are the wire fields, and decodes through its own smart constructor:

```scala
object RiskTree:
  private case class RiskTreeRaw(
    id: TreeId, name: SafeName.SafeName, nodes: Seq[RiskNode],
    rootId: NodeId, seedVarHighWater: SeedVarId.SeedVarId,
    mitigations: Option[Seq[Mitigation]]   // absent decodes to Nil; TreeIndex is not serialised
  )

  given codec: JsonCodec[RiskTree] = JsonCodec(
    JsonEncoder[RiskTreeRaw].contramap[RiskTree](toRawShape),
    JsonDecoder[RiskTreeRaw].mapOrFail(json => fromNodes(...).toEither.left.map(...))
  )
```

A decoded value is then as trustworthy as a constructed one, on both sides, and there is no public twin class to keep in step.

### 2. An Operation Outcome Is a Public Sealed Enum With a Derived Codec

The alternatives are the type, the tag field is named by annotation, and the codec is derived:

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

Each case carries its own payload, so a match is exhaustive and the reader needs no wildcard arm. A server-sent event is an outcome, not a view, and takes this shape.

### 3. No Raw Primitive Where a Refined Type Exists

```scala
// BAD: the reader learns that it is a number
.out(jsonBody[Double])

// GOOD: the reader learns the closed interval, and decoding rejects anything outside it
.out(jsonBody[OccurrenceProbability])
```

This covers a map's key type as much as a field, and a response body is never a bare primitive or a bare collection.

### 4. A Confidential Internal Identifier Has No Serialiser

Deriving a codec requires an instance per field, so a wire-facing type naming one does not compile:

```scala
object WorkspaceId:
  // No JSON codec, and no Tapir Schema: a confidential internal identifier is
  // not serialisable, so a wire-facing type that names one fails to compile
  // rather than relying on review to catch the field (ADR-036).
```

This binds ADR-036's identifiers, never a bearer credential: `WorkspaceKeySecret` keeps its codec because the capability key is the one value a client must be given.

### 5. The Projection Is `toWire`, Owned by the Source, Returning a Typed Value

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

A factory on the destination would make the wire type depend on the domain type, which is the coupling such a factory is adopted to avoid.

---

## Code Smells

### ❌ String Standing In for a Closed Set

```scala
// BAD: four values, none stated; an unknown value is indistinguishable from a known one
final case class ChangedNodesResponse(status: String, entries: List[NodeChangeEntry])

// GOOD
@jsonDiscriminator("kind")
enum ChangedNodesStatus:
  case Ok(entries: List[NodeChangeEntry])
  case MissingOnA(), MissingOnB(), MissingOnBoth()
```

### ❌ Bare Primitive or Map as a Response Body

```scala
// BAD
.out(jsonBody[Map[String, String]])

// GOOD
.out(jsonBody[HealthReport])
```

### ❌ Schema Erased on a Wire Type

```scala
// BAD: the documented contract for the whole response becomes empty
given schema: Schema[DistributionPreviewResponse] = Schema.any[DistributionPreviewResponse]

// GOOD
given schema: Schema[DistributionPreviewResponse] = Schema.derived[DistributionPreviewResponse]
```

### ❌ Hand-Rolled Discriminator Beside a Derived Codec

```scala
// BAD: the tag is a method the serialiser never sees
sealed trait SSEEvent:
  def eventType: String

// GOOD
@jsonDiscriminator("event")
enum SSEEvent:
  case NodeChanged(nodeId: NodeId, treeId: TreeId, change: NodeChangeType)
```

### ❌ Projection That Widens

```scala
// BAD
def toWire: String = this match
  case Added => "added"

// GOOD — derived, unless the string contracts with something other than the encoder (ADR-037 §2)
given JsonCodec[NodeChangeType] = DeriveJsonCodec.gen[NodeChangeType]
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `RiskTree.RiskTreeRaw`, and the `Raw` projections in `Mitigation`, `Provenance`, `RiskLeafTransform` | Private wire shape in the owner's companion |
| `MergePreviewStatus` | Public sealed enum, derived discriminated codec |
| `WorkspaceId` | No serialiser |
| `WorkspaceKeySecret` | The credential exception — codec retained |
| `BranchChoice` | Typed projection, total inverse, boundary-only |

Cleanup of a non-conforming occurrence is an obligation of the `boyscout` skill: a file opened for any other reason is brought into conformance in the same pass. This ADR does not authorize opening a file to clean it.

---

## References

- ADR-001 — validation happens in the codec, once; clause 1 satisfies it on both sides at the same time
- ADR-022 — credential types; the bearer credential is the one value clause 4 does not confine
- ADR-036 — which identifiers may cross the client boundary; clause 4 is its enforcement mechanism
- ADR-037 — closed sets are enums with derived codecs, and when a spelling is declared instead
