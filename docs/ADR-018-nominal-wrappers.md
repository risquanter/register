# ADR-018: Nominal Case Class Wrappers over Iron Types for Domain Identity Distinction

**Status:** Accepted  
**Date:** 2026-02-06  
**Tags:** type-safety, iron, domain-ids, case-class, parse-dont-validate

---

## Context

- Iron opaque types ensure **validated** domain primitives (ADR-001)
- Some domains have multiple identifiers sharing the same validation constraints but with **distinct semantics** (e.g., tree ID vs node ID — both are ULIDs)
- Transparent type aliases (`type TreeId = SafeId.SafeId`) provide documentation but **no compile-time distinction** — the compiler treats them as identical types
- Ambiguous implicit/given instances arise when multiple aliases resolve to the same underlying type (e.g., duplicate Tapir codecs)
- Opaque types wrapping opaque types add boilerplate and complexity for a problem that doesn't require zero-cost abstraction (IDs flow through HTTP/service layers, not hot loops)

---

## Decision

### 1. Case Class Wrapper Pattern

When two or more domain concepts share the same Iron-refined base type but must not be interchangeable, wrap the base type in a **case class**:

```scala
// Base: Iron-refined opaque type (validated ULID)
object SafeId:
  opaque type SafeId = SafeIdStr  // Iron refinement

// Wrappers: Compiler-distinct, delegate validation to base
case class TreeId(toSafeId: SafeId.SafeId):
  export toSafeId.value

case class NodeId(toSafeId: SafeId.SafeId):
  export toSafeId.value
```

### 2. Smart Constructor via Delegation

Each wrapper provides a `fromString` factory that delegates to the base type's smart constructor:

```scala
object TreeId:
  def fromString(s: String): Either[List[ValidationError], TreeId] =
    SafeId.fromString(s).map(TreeId(_))

object NodeId:
  def fromString(s: String): Either[List[ValidationError], NodeId] =
    SafeId.fromString(s).map(NodeId(_))
```

No additional validation logic — the wrapper adds **identity**, not constraints.

### 3. Separate Codecs, No Ambiguity

Each wrapper gets its own Tapir codec, JSON codec, and schema. Since the types are distinct, no ambiguous-given conflicts arise:

```scala
// Tapir
given Codec[String, TreeId, CodecFormat.TextPlain] =
  Codec.string.mapDecode(raw =>
    TreeId.fromString(raw).fold(
      errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
      DecodeResult.Value(_)
    )
  )(_.value.toString)

// JSON
given JsonEncoder[TreeId] = JsonEncoder[String].contramap(_.value.toString)
given JsonDecoder[TreeId] = JsonDecoder[String].mapOrFail(s =>
  TreeId.fromString(s).left.map(_.mkString(", ")))
```

### 4. Domain Model Stays on Base Type

Domain data classes (`RiskNode.id`, `RiskLeaf.safeId`) continue to use `SafeId.SafeId` — the raw validated ULID. Wrapping happens at structural boundaries:

```scala
// RiskNode stores the base type
sealed trait RiskNode:
  def id: SafeId.SafeId

// TreeIndex wraps at construction
private def extractNodeId(node: RiskNode): NodeId = NodeId(node.id)
```

---

## When to Apply

**Use this pattern when:**
- Two+ domain concepts share identical validation constraints
- Accidentally using one in place of the other is a semantic error
- The values flow through service/HTTP layers (allocation cost is noise)

**Do NOT use this pattern when:**
- Values appear in hot loops (use opaque types instead, e.g., `PRNGCounter`)
- There is only one semantic use of the base type (no distinction needed)
- The wrapper would add validation beyond the base type (use a separate Iron constraint)

---

## Code Smells

### ❌ Transparent Type Alias for Distinction

```scala
// BAD: Compiler sees same type — no protection, ambiguous givens
type TreeId = SafeId.SafeId
type NodeId = SafeId.SafeId

// Compiles without error — tree/node IDs freely interchangeable:
def getTree(id: TreeId): Task[RiskTree] = ???
val nodeId: NodeId = ???
getTree(nodeId)  // No compiler error!
```

### ✅ Case Class Wrapper

```scala
// GOOD: Compiler rejects — distinct types
case class TreeId(toSafeId: SafeId.SafeId)
case class NodeId(toSafeId: SafeId.SafeId)

def getTree(id: TreeId): Task[RiskTree] = ???
val nodeId: NodeId = ???
getTree(nodeId)  // Compiler error: found NodeId, expected TreeId
```

### ❌ Duplicating Validation in Wrapper

```scala
// BAD: Wrapper re-implements ULID validation
object TreeId:
  def fromString(s: String): Either[..., TreeId] =
    ULID(s.toUpperCase) match  // Duplicate logic!
      case Right(parsed) => ...

// GOOD: Wrapper delegates to base type
object TreeId:
  def fromString(s: String): Either[..., TreeId] =
    SafeId.fromString(s).map(TreeId(_))  // Single source of truth
```

---

## Relationship to ADR-001

ADR-001 rejects "custom wrapper types" as an **alternative to Iron** — i.e., hand-rolled validation wrappers that replace Iron's compile-time refinement. This ADR defines wrappers **over** Iron types for nominal distinction. The validation is still Iron-powered; the case class adds only a type tag.

---

## Implementation

| Location | Pattern |
|----------|---------|
| `OpaqueTypes.scala` | `case class TreeId`, `case class NodeId` definitions |
| `IronTapirCodecs.scala` | Separate Tapir `Codec` and `Schema` per wrapper |
| `TreeIdCodecs.scala` | JSON encoder/decoder for `TreeId` |
| `RiskTree.scala` | JSON codecs for `TreeId`, `NodeId` |
| `IdGenerators.scala` | `nextTreeId`, `nextNodeId` wrap `nextId` result |
| `TreeIndex.scala` | Maps keyed by `NodeId` |
| `RiskTreeService.scala` | Signatures use `TreeId`, `NodeId` |

---

## References

- [ADR-001: Validation Strategy with Iron Types](ADR-001.md) — base Iron type system
- [Parse, Don't Validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/) — boundary parsing principle
