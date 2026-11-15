# ADR-040: Analysis Read Contract

**Status:** Accepted (implemented)  
**Date:** 2026-11-15  
**Tags:** api, analysis, mitigation, wire

---

## Context

- An analysis figure belongs to one version of a tree. A branch head moves, and a commit does not.
- Two valuations of one node can be compared only if they are drawn on the same loss axis.
- A mitigation selection names mitigations. A tree may lack a named mitigation, or hold one whose scope reaches nothing.
- A selection is two lists with bounds. A query string cannot carry that shape and keep it typed.
- A response that holds two readings of one node must say which mitigations shaped each, or a caller cannot tell an unmitigated figure from a mitigated one that happens to match it.

---

## Decision

### 1. A Read Names Its Version

Both analysis endpoints take the required `X-Branch` header and an optional `at` commit pin. Without `at` the read is answered at the branch head; with it, at that commit. A stored version never changes, so the answer carries no revision or staleness field: there is nothing for a client's view to diverge from.

```scala
.in(branchHeader)
.in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
```

### 2. Both Endpoints Are POST With a JSON Body Carrying the Selection

The body carries a `MitigationSelection.Enumerated` as two lists. `fullScope` names mitigations applied across their whole resolved scope, and `nodesOnly` names per-node ticks. Both lists empty, or an absent `selection`, is the inherent reading. The decoder runs `MitigationSelection.create`, so a decoded selection is within its bounds on the server and in the browser (ADR-001, ADR-039).

```json
{ "nodeIds": ["01HQ…"], "selection": { "fullScope": ["01HM…"], "nodesOnly": [{ "node": "01HQ…", "mitigation": "01HN…" }] } }
```

```scala
def create(fullScope: List[MitigationId], nodesOnly: List[NodeMitigationEntry]): Validation[ValidationError, Enumerated]
```

A mitigation named in both lists applies across its whole scope. `Residual` is not an `Enumerated` value, so a body cannot state it.

### 3. A Named but Absent Mitigation Fails the Request; One That Applies to Nothing Does Not

Whether a named mitigation exists is a lookup against the loaded tree, so it runs in the service, not the decoder. Every absent mitigation is refused together, in identifier order, each located at that mitigation. A mitigation that exists and reaches no node is a valid request whose answer is "no effect".

```scala
ValidationError(ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Id), FieldProblem.MitigationNotFound)
```

### 4. Two Response Shapes, One Reading of `withMitigations`

The curve endpoint answers a list of readings per node: the mitigation-free reading first, and a second reading when the selection reaches the node. Every curve is sampled at the same loss ticks, so both valuations land on one axis. The exceedance endpoint answers one reading, because a single probability has no axis to share.

```scala
final case class LECCurvesResponse(curves: Map[NodeId, List[LECNodeSeries]])
final case class LECNodeSeries(curve: LECNodeCurve, withMitigations: List[MitigationId])
final case class ExceedanceSeries(probability: Probability, withMitigations: List[MitigationId])
```

`withMitigations` names every mitigation whose resolved scope covers the node or a node below it. It is decided by scope: it is neither the set the caller selected nor the set that moved the number. The mitigation-free reading is the one whose list is empty.

### 5. Payload Bounds Are the Tree's Own, Checked in the Decoder

A curve request names at most `RiskTree.MaxNodes` nodes. A selection names at most `RiskTree.MaxMitigations` mitigations and ticks one mitigation at no more than `RiskTree.MaxNodes` nodes. Every breach is reported together, and an oversized body is refused before the handler runs.

---

## Relation to ADR-038

ADR-038 §3 rules the curve answer's enclosing object: `LECCurvesResponse` stays an object rather than a bare map, and the exceedance figure is a `Probability`. This record describes what that object holds and how its readings are read. A change to the enclosing shape is ADR-038's; a change to what a reading means is this record's.

---

## Code Smells

### ❌ Selection in the Query String

```scala
// BAD: two bounded lists flattened into strings, decoded by hand in the handler
.in(query[List[String]]("mitigations"))

// GOOD: the domain type in the body, decoded through its smart constructor
.in(jsonBody[ProbOfExceedanceRequest])
```

### ❌ A Revision or Staleness Field in the Answer

```scala
// BAD: a stored version never changes, so this field states nothing
final case class LECCurvesResponse(curves: Map[NodeId, List[LECNodeSeries]], revision: CommitHash)

// GOOD: the caller names the version in the request
final case class LECCurvesResponse(curves: Map[NodeId, List[LECNodeSeries]])
```

### ❌ Refusing a Mitigation That Reaches Nothing

```scala
// BAD: an empty scope treated as a broken request
if scope.isEmpty then ZIO.fail(Failure.rejected(...))

// GOOD: only an absent mitigation is refused; an empty scope has no effect
requireKnownMitigations(tree, selection)
```

### ❌ A Separate Field for the Mitigation-Free Reading

```scala
// BAD: two fields for two readings of one node
final case class NodeCurves(inherent: LECNodeCurve, mitigated: Option[LECNodeCurve])

// GOOD: a list of readings, each naming what shaped it; the empty list is mitigation-free
List[LECNodeSeries]
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `WorkspaceAnalysisEndpoints` | Both routes are POST with a JSON body, `X-Branch` and optional `at` |
| `MitigationSelection.create` | The two-list form, its bounds, and the decoder that runs it |
| `LECCurvesMultiRequest.create` | The node-count bound |
| `RiskTreeServiceLive.requireKnownMitigations` | Absent mitigations refused together, in identifier order |
| `LECNodeSeries`, `ExceedanceSeries` | One reading of `withMitigations` across both responses |

---

## References

- ADR-038 — Response wire shapes; §3 rules the enclosing object
- ADR-034 — Mitigation valuation model: the two valuations
- ADR-017 — The HTTP surface table lists both routes
- ADR-001 — Validation in the codec through the smart constructor
