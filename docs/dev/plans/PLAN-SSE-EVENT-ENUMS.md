# Plan: SSE event wire-string enums

**Status:** Awaiting approval. **Date:** 2026-07-27.
**ADR reference:** ADR-001 (validate-at-boundary / no raw domain-value strings).

Single self-contained, independently landable and revertable change, scoped to
the SSE event model in the `server` module. No wire-format change, no
cross-module impact (SSE events live only in `server`).

---

## Objective

Two fields on `SSEEvent` subtypes carry a fixed set of domain values as a free
`String`, constructed from bare string literals at call sites where a typo
compiles:

- `NodeChanged.changeType` — `"added"` / `"updated"` / `"removed"`
- `ConnectionStatus.status` — `"connected"` / `"heartbeat"` / `"disconnecting"`

Replace each with a Scala 3 `enum` carrying a `toWire`/`fromWire` mapping and
wire-preserving JSON codecs, following the existing `NodeChangeStatus.toWire`
convention. `eventType` is deliberately left as `String` — it is a per-subtype
constant discriminator (1:1 with the sealed hierarchy) used only outbound for
the SSE `event:` line and log strings, which is ADR-001's serialization-helper
exception, not a domain-value parameter.

---

## ADR alignment

- **ADR-001:** moves two domain-value fields off raw `String` onto refined
  types; construction now goes through named enum cases. Conforms.
- **Serialization:** custom `JsonEncoder`/`JsonDecoder` map each enum to the
  exact existing wire string via `toWire`/`fromWire`, so the emitted JSON and
  the SSE `event:`/`data:` lines are byte-identical to today. No API-shape
  change (no Decision Trigger #1).
- **Pattern:** mirrors `NodeChangeStatus` in
  `modules/server/src/main/scala/com/risquanter/register/services/ChangedNodesService.scala`
  and `DistributionMode.toApiString`.

---

## Exact signatures

Top-level in package `com.risquanter.register.http.sse` (colocated with
`SSEEvent`, as `NodeChangeStatus` is colocated with its service):

```scala
enum NodeChangeType:
  case Added, Updated, Removed
  def toWire: String  // "added" | "updated" | "removed"

object NodeChangeType:
  def fromWire(s: String): Either[String, NodeChangeType]
  given JsonEncoder[NodeChangeType]
  given JsonDecoder[NodeChangeType]

enum ConnectionState:
  case Connected, Heartbeat, Disconnecting
  def toWire: String  // "connected" | "heartbeat" | "disconnecting"

object ConnectionState:
  def fromWire(s: String): Either[String, ConnectionState]
  given JsonEncoder[ConnectionState]
  given JsonDecoder[ConnectionState]
```

Field retypes on the existing case classes (JSON key names unchanged):

```scala
final case class NodeChanged(nodeId: String, treeId: TreeId, changeType: NodeChangeType)
final case class ConnectionStatus(status: ConnectionState, message: Option[String] = None)
```

Call-site updates (no behaviour change): `ConnectionState.Connected` /
`ConnectionState.Heartbeat` in `SSEController`; the three `SSEHubSpec`
construction sites move to enum cases. The spec's `"test"`/`"broadcast"`
payloads are inert — no assertion reads their value (checks are on subscriber
counts, `isDefined`, and `result1 == result2`), so nothing is weakened.

---

## File inventory

The enforcement hook authorizes gated edits only from bullet lines in this H2
section (up to the next `## ` heading). Approving the plan (token → this
document) authorizes every file below.

- modules/server/src/main/scala/com/risquanter/register/http/sse/SSEEvent.scala
- modules/server/src/main/scala/com/risquanter/register/http/sse/SSEController.scala
- modules/server/src/test/scala/com/risquanter/register/services/sse/SSEHubSpec.scala

---

## Open decisions

None. `eventType`-stays-`String` is settled (see Objective). The field retypes
are a mechanical consequence of the approved recommendation.

---

## Verification plan

- `sbt 'commonJVM/test; server/test'` — green.
- `sbt app/test` — green (app does not reference `SSEEvent`; expected
  unaffected, run to confirm).
- `sbt "serverIt/test"` — green.
- Report pass/fail only; any red anywhere blocks done.

---

## Appendix — finding similar stringly-typed wire fields (reusable)

The reliable signal is a fixed set of **string literals clustered at
object-construction sites** (not in codecs or log keys — those are ADR-001's
legitimate exceptions). Grep the literal set, then the two established good
patterns to see where they are *not* yet applied:

```bash
# 1. Literal sets fed into constructors (the smell that found changeType/status):
grep -rn '"added"\|"updated"\|"removed"\|"connected"\|"heartbeat"\|"disconnecting"' \
  modules/*/src/main

# 2. Where the good pattern already lives (gaps nearby are candidates):
grep -rn 'toWire\|toApiString' modules/*/src/main

# 3. Raw String fields on wire/domain types to audit by eye:
grep -rn ': String' modules/*/src/main/scala/**/http

# 4. After an enum exists, catch wire-string drift — any literal match
#    outside its toWire/fromWire is a spot that should route through the enum.
```

For an exhaustive codebase-wide sweep of this pattern, dispatch the `Explore`
subagent rather than grepping by hand.
