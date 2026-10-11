# ADR-037: In-Code Constant and Closed-Set Handling

**Status:** Accepted (awaiting implementation)
**Date:** 2026-10-11
**Tags:** constants, enums, type-safety, serialization, code-hygiene

---

## Context

- A literal repeated at its use sites has no single definition, so two copies can diverge without any compiler or test objecting.
- A value naming one of a fixed set of alternatives carries an exhaustiveness guarantee only when the set is a type; matching over a `String` is always partial.
- Separating a value from the type that gives it meaning permits it to be used where it has none, because the carrier type admits every other value of the same encoding.
- A string crossing a boundary is a contract with whatever reads it; whether that reader is a serialiser or an external language decides whether the spelling may be derived or must be declared.
- Operator-tunable configuration and compiled-in constants answer different questions: ADR-016 governs values settable without a release, this ADR governs values whose change alters a contract or the logic.

---

## Decision

### 1. A Fixed Set Is an Enum in Its Owner's Companion

Declare the set beside the type that owns it. Derive the codec; annotate only where the serialised spelling must differ from the case name:

```scala
@jsonDiscriminator("type")
enum ScenarioSourceDto:
  @jsonHint("branch") case Branch(name: BranchChoice)
  @jsonHint("commit") case Commit(hash: CommitHash)

object ScenarioSourceDto:
  given codec: JsonCodec[ScenarioSourceDto] = DeriveJsonCodec.gen[ScenarioSourceDto]
```

No central constants file. A set's definition lives with its owner, so it cannot outlive the type it describes.

### 2. An Explicit Spelling Is Declared Only When the String Contracts With Something Else

A string that exists only as a case's serialised form is derived. A string that another system matches exactly is declared, so renaming a case cannot rename it:

```scala
/** `symbol` is the name the query engine binds: declared, not derived from the
  * case name, so a Scala rename cannot rename a keyword of the query language.
  */
enum ValuationBasis(val symbol: String):
  case Inherent extends ValuationBasis("inherent")
  case Residual extends ValuationBasis("residual")
```

### 3. A Single Constant Lives With Its Owner, and States Its Origin

A value that is not one of a set becomes a named value in the companion of the type that owns it, documenting where the value comes from:

```scala
object FolQueryFailure:
  /** The `TypeId.value` the engine crosses in `BindErrorDetail.UnparseableConstant.sortName`
    * for a node-sort literal. Bound to the catalog by a drift-guard assertion.
    */
  val NodeSortName: String = "Node"
```

Ownership must be unambiguous. A value two types need is not this pattern — it is a set not yet recognised, or it belongs to a third type neither site declares.

### 4. A Defaulted `Option` Is Forbidden

`Option[T]` states that absence is legitimate. A default states that omitting the argument is free, which makes dropped information invisible:

```scala
// BAD
final case class ErrorDetail(domain: String, requestId: Option[String] = None)

// GOOD
final case class ErrorDetail(domain: ErrorDomain, requestId: Option[RequestId])
```

A derived decoder reads an absent key as `None` without the default, so removing it does not make a serialised field required.

### 5. A User-Facing Sentence Is a Named Value

Text shown to a person is named where its set of messages lives. A sentence taking a value is a function of that value, so the two cannot drift:

```scala
object ErrorMessages:
  val Forbidden: String = "You do not have permission to do that."
  def workspaceExpired(lifetime: Duration): String =
    s"Workspaces last ${lifetime.toHours} hours. Create a new tree to start a fresh one."
```

---

## Code Smells

### ❌ Literal at the Use Site

```scala
// BAD: the same tag written at every construction site
response(StatusCode.NotFound, "resource", NOT_FOUND, "Not found", "workspaces")
response(StatusCode.Conflict, "scenario", MERGE_CONFLICT, msg, "scenarios")

// GOOD: a closed set, matched exhaustively
response(StatusCode.NotFound, Field.Resource, NOT_FOUND, "Not found", ErrorDomain.Workspaces)
```

### ❌ Hand-Written Codec Duplicating the Derived One

```scala
// BAD: an unknown wire value silently becomes a legitimate case
given JsonEncoder[HistoryOperation] = JsonEncoder[String].contramap(_.toString)
given JsonDecoder[HistoryOperation] = JsonDecoder[String].map {
  case "Create" => Create
  case _        => Other
}

// GOOD: derived; an unknown value is rejected
given codec: JsonCodec[HistoryOperation] = DeriveJsonCodec.gen[HistoryOperation]
```

### ❌ Central Constants Object

```scala
// BAD: assignable anywhere a String is wanted; no exhaustiveness at any match
object Constants:
  val Clean: String = "clean"
  val Conflicts: String = "conflicts"

// GOOD: a type, so the match is checked
enum MergePreviewStatus:
  case Clean, Conflicts, DuplicateNames, ScenarioMissing
```

### ❌ Spelling Derived From the Case Name Where It Is an External Contract

```scala
// BAD: renaming the case renames a query-language keyword
enum ValuationBasis:
  case Inherent, Residual
  def symbol: String = toString.toLowerCase

// GOOD: declared, so a rename cannot reach it
enum ValuationBasis(val symbol: String):
  case Inherent extends ValuationBasis("inherent")
```

### ❌ Sentence Written at the Rendering Site

```scala
// BAD: the same text in two components, free to diverge
div(cls := "banner", "Your previous workspace has expired and its data is no longer available.")

// GOOD
div(cls := "banner", ErrorMessages.workspaceExpired(lifetime))
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `ScenarioSourceDto` | Closed set, derived codec, `@jsonHint` for differing spellings |
| `ValuationBasis` | Declared spelling — contract with the query engine |
| `FolQueryFailure.NodeSortName` | Single constant in its owner's companion, origin documented |
| `WireError` | Closed set carrying the status per case |
| `ErrorMessages`, `MergeMessages` | Named user-facing text, parameterised as functions |

Cleanup of a non-conforming occurrence is an obligation of the `boyscout` skill: a file opened for any other reason is brought into conformance in the same pass. This ADR does not authorize opening a file to clean it.

---

## References

- ADR-016 — configuration management; the boundary is whether an operator can change the value without a release
- ADR-018 — nominal wrappers for semantically distinct values sharing an encoding
