# ADR-040: Analysis Read Contract

**Status:** Accepted (implemented)  
**Date:** 2026-11-15  
**Tags:** api, analysis, mitigation, wire

---

## Context

- An analysis figure belongs to one version of a tree. A branch head moves, and a commit does not.
- Two valuations of one node can be compared only if they are drawn on the same loss axis.
- A mitigation selection names mitigations. A tree may lack a named mitigation, or hold one whose scope reaches nothing.
- A selection is a tagged value with bounds, and a curve request states several. A query string cannot carry that shape and keep it typed.
- An answer holding several valuations of one node must say, per curve, which mitigations shaped it, or a caller cannot tell an unmitigated figure from a mitigated one that happens to match it.

---

## Decision

### 1. A Read Names Its Version

Both analysis endpoints take the required `X-Branch` header and an optional `at` commit pin. Without `at` the read is answered at the branch head; with it, at that commit. A stored version never changes, so the answer carries no revision or staleness field: there is nothing for a client's view to diverge from.

```scala
.in(branchHeader)
.in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
```

### 2. Both Endpoints Are POST With a JSON Body Stating Selections

A selection is one of three alternatives, tagged by `kind`: `Inherent` applies no mitigation, `Residual` applies every mitigation the tree holds, and `Selected` maps each mitigation it names to how far it reaches. The reach is tagged by `scope`: `FullScope` is the mitigation's whole resolved scope, and `NodesOnly` carries the nodes it is restricted to. Each tag is the case name (ADR-037 §1). A selection is required: a body that omits it is refused, so one meaning has one spelling.

The exceedance body is one selection. The curve body is a list of valuations. Each valuation is a selection and the nodes whose curves are wanted under it, and no two valuations of one request hold equal selections.

```json
{ "valuations": [
    { "selection": { "kind": "Inherent" }, "nodeIds": ["01HQ…"] },
    { "selection": { "kind": "Selected",
                     "mitigations": { "01HM…": { "scope": "FullScope" },
                                      "01HN…": { "scope": "NodesOnly", "nodes": ["01HQ…"] } } },
      "nodeIds": ["01HQ…", "01HR…"] } ] }
```

The decoder runs `MitigationSelection.create` and `ScopeRestriction.nodesOnly`, so a decoded selection is within its bounds on the server and in the browser (ADR-001, ADR-039).

```scala
def create(entries: Map[MitigationId, ScopeRestriction]): Validation[ValidationError, Enumerated]
def nodesOnly(ids: Set[NodeId], location: ErrorLocation): Validation[ValidationError, ScopeRestriction]
```

A `Selected` value with an empty `mitigations` map is the inherent selection. A `NodesOnly` restriction with no node is refused. A node named in a restriction that lies outside the mitigation's resolved scope has no effect and is not an error.

### 3. A Named but Absent Mitigation Fails the Request; One That Applies to Nothing Does Not

Whether a named mitigation exists is a lookup against the loaded tree, so it runs in the service, not the decoder. Every absent mitigation is refused together, in identifier order, each located at that mitigation. A mitigation that exists and reaches no node is a valid request whose answer is "no effect".

```scala
ValidationError(ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Id), FieldProblem.MitigationNotFound)
```

### 4. One Curve Per Valuation and Node, One Meaning of `withMitigations`

The curve endpoint answers one entry per valuation, in request order. Each entry repeats its selection and maps each node the valuation names to one curve. The answer adds no curve that was not asked for and leaves out none that was: a node the selection does not reach has its curve, with an empty `withMitigations`. Every curve of the answer, across all valuations, is sampled at the same loss ticks, so any two of them land on one axis. The exceedance endpoint answers one figure, because a single probability has no axis to share.

```scala
final case class CurveValuationResponse(selection: MitigationSelection, curves: Map[NodeId, LECNodeSeries])
final case class LECCurvesResponse(valuations: List[CurveValuationResponse])
final case class LECNodeSeries(curve: LECNodeCurve, withMitigations: List[MitigationId])
final case class ExceedanceSeries(probability: Probability, withMitigations: List[MitigationId])
```

`withMitigations` names every mitigation the valuation's selection applies whose resolved scope covers the node or a node below it. It is decided by scope: it is neither the set the caller selected nor the set that moved the number.

### 5. Payload Bounds Are Checked in the Decoder

A curve request states between 1 and `LECCurvesMultiRequest.MaxValuations` valuations. That bound is 16 and is chosen by this project: each valuation whose selection is not inherent costs one mitigated fold of the tree. The other bounds are the tree's own. A valuation names at most `RiskTree.MaxNodes` nodes. A selection names at most `RiskTree.MaxMitigations` mitigations, and one restriction holds at most `RiskTree.MaxNodes` nodes. Every breach is reported together, and an oversized body is refused before the handler runs.

---

## Relation to ADR-038

ADR-038 §3 rules the curve answer's enclosing object: `LECCurvesResponse` stays an object rather than a bare map, and the exceedance figure is a `Probability`. This record describes what that object holds and how its readings are read. A change to the enclosing shape is ADR-038's; a change to what a reading means is this record's.

---

## Code Smells

### ❌ Selection in the Query String

```scala
// BAD: a tagged, bounded value flattened into strings, decoded by hand in the handler
.in(query[List[String]]("mitigations"))

// GOOD: the domain type in the body, decoded through its smart constructor
.in(jsonBody[ProbOfExceedanceRequest])
```

### ❌ A Revision or Staleness Field in the Answer

```scala
// BAD: a stored version never changes, so this field states nothing
final case class LECCurvesResponse(valuations: List[CurveValuationResponse], revision: CommitHash)

// GOOD: the caller names the version in the request
final case class LECCurvesResponse(valuations: List[CurveValuationResponse])
```

### ❌ Refusing a Mitigation That Reaches Nothing

```scala
// BAD: an empty scope treated as a broken request
if scope.isEmpty then ZIO.fail(Failure.rejected(...))

// GOOD: only an absent mitigation is refused; an empty scope has no effect
requireKnownMitigations(tree, selections)
```

### ❌ Adding a Curve the Request Did Not State

```scala
// BAD: the inherent curve of every requested node, added to an answer that asked for another selection
CurveValuationResponse(selection, curves) :: CurveValuationResponse(MitigationSelection.Inherent, inherentCurves) :: Nil

// GOOD: one entry per valuation the request states, and the inherent curve when a valuation asks for it
valuations.map(v => CurveValuationResponse(v.selection, curvesUnder(v)))
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `WorkspaceAnalysisEndpoints` | Both routes are POST with a JSON body, `X-Branch` and optional `at` |
| `MitigationSelection.create`, `ScopeRestriction.nodesOnly` | The selection's three alternatives, their bounds, and the decoder that runs both |
| `CurveValuationRequest.create` | One valuation and its node-count bound |
| `LECCurvesMultiRequest.create` | The valuation-count bound, and no selection stated twice |
| `RiskTreeServiceLive.requireKnownMitigations` | Absent mitigations refused together across valuations, in identifier order |
| `CurveValuationResponse` | One valuation's answer: its selection and one curve per node |
| `LECNodeSeries`, `ExceedanceSeries` | One meaning of `withMitigations` across both responses |

---

## References

- ADR-038 — Response wire shapes; §3 rules the enclosing object
- ADR-034 — Mitigation valuation model: the two valuations
- ADR-017 — The HTTP surface table lists both routes
- ADR-001 — Validation in the codec through the smart constructor
