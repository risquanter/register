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

Declare the set beside the type that owns it. Derive the codec, so each case's serialised spelling is its own name. The discriminator's key is a named constant in the file that declares the set, read by the annotation and by the published schema, so the two cannot disagree:

```scala
@jsonDiscriminator(ScenarioSourceDto.TagField)
sealed trait ScenarioSourceDto

object ScenarioSourceDto:
  final val TagField = "type"

  final case class Branch(name: BranchChoice) extends ScenarioSourceDto
  final case class Commit(hash: CommitHash) extends ScenarioSourceDto

  given codec: JsonCodec[ScenarioSourceDto] = DeriveJsonCodec.gen[ScenarioSourceDto]
```

Annotate a case's spelling only where §2 applies. The key names the field; the values come from the case names.

A set's definition lives with its owner, so it cannot outlive the type it describes. A shared object holds only shared constants, decided by the tests in §3.

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
object DistributionShape:
  /** The confidence level the lognormal bounds are read at. Fixed by the BCG
    * method rather than configurable. */
  val LognormalConfidenceInterval: Double = 0.90
```

Every constant is either owned or shared, and the two tests below decide which.

**Owned.** A constant belongs to a type when two things hold: that type's definition reads it, in its refinement, its smart constructor or its codec, and changing the value changes the behaviour of that type alone. It lives in that type's companion.

**Shared.** A constant is shared when it is held for use by several types rather than being about one of them. It is one of three kinds:

- a value of a refined type that many types are built from;
- a value that constrains several unrelated types equally;
- a contract fixed outside the code, such as a protocol limit.

It lives in the shared object for its kind and states its origin there. Its present readers do not move it. A shared value that one type reads today stays in the shared object, because that object is where the next type looks for an existing value before writing its own.

`IronConstants` in `OpaqueTypes.scala` is the complete example. It holds values of `PositiveInt`, `NonNegativeInt`, `NonNegativeLong` and `DistributionType`, the refined types the domain types are built from:

```scala
object IronConstants:
  val One: PositiveInt     = 1
  val Ten: PositiveInt     = 10
  val Zero: NonNegativeInt = 0
  val ZeroL: NonNegativeLong = 0L
  val Metalog: DistributionType = "Metalog"
```

`IronConstants.Zero` is read today only by `NonNegativeInt.count`, and it stays in `IronConstants`.

A value two types need is checked against the other patterns before it is called shared. It may be a set not yet recognised (§1), or it may belong to a third type neither site declares.

A value that can reasonably be assumed to change behaviour in a way that should be exposed as a user choice or as a best practice is not this pattern either. It is a setting in `application.conf`, read into its refined type by the config decoder, with any default listed in that file (ADR-016).

A default a shared endpoint publishes for an omitted request parameter is part of the wire contract, and it stays a named constant in that endpoint's companion. The shared module cannot read `application.conf`.

### 4. A Defaulted `Option` Is Forbidden

`Option[T]` states that absence is legitimate. A default states that omitting the argument is free, which makes dropped information invisible:

```scala
// BAD
final case class Versions[A](base: Option[A] = None, onMain: Option[A] = None, onScenario: Option[A] = None)

// GOOD
final case class Versions[A](base: Option[A], onMain: Option[A], onScenario: Option[A])
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
WireError.NotFound("unknown", trace)
WireError.NotFound("tree-not-at-commit", trace)

// GOOD: a closed set, matched exhaustively
WireError.NotFound(NotFoundKind.Unknown(), trace)
WireError.NotFound(NotFoundKind.TreeNotAtCommit(), trace)
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

### ❌ A Set's Members as Constants in a Shared Object

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
| `ScenarioSourceDto` | Closed set, derived codec, tag key as a named constant |
| `LeafSimContent`'s raw projection | Declared spellings — the bytes are a hash preimage, which owes stability rather than agreement with a decoder |
| `ValuationBasis` | Declared spelling — contract with the query engine |
| `DistributionShape.LognormalConfidenceInterval` | Single constant in its owner's companion, origin documented |
| `IronConstants` | Shared object of values of shared refined types, kept together for reuse |
| `WireError` | Closed set carrying the status per case |
| `ErrorMessages`, `MergeMessages` | Named user-facing text, parameterised as functions |

Cleanup of a non-conforming occurrence is an obligation of the `boyscout` skill: a file opened for any other reason is brought into conformance in the same pass. This ADR does not authorize opening a file to clean it.

---

## References

- ADR-016 — configuration management; the boundary is whether an operator can change the value without a release
- ADR-018 — nominal wrappers for semantically distinct values sharing an encoding
