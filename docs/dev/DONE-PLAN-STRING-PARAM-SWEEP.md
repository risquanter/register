# PLAN — Raw-String domain-parameter sweep (server internals)

Status: presented 2026-07-24, awaiting approval.
Commissioned by user ruling 2026-07-24 ("clean up those of the readNodes or
similar patterns, from() can stay") after the plan-D code review; rule basis
is the adr-constraints amendment of the same date: no raw `String`/`Int`
parameter may carry a domain value in ANY function, private helpers included.

## Goal

Remove the remaining internal signatures where an already-validated domain
value (branch, commit hash, storage path, count) travels as a raw primitive
and is re-derived or unsafe-refined downstream. Boundary parsers
(`from(s: String)` smart constructors), free text (messages, authors, JSON
payloads), and escaping helpers keep `String` — that is where raw strings
legitimately live.

## Scope — exact signatures

### 1. `IrminQueries` (query-string builder takes domain types; encoding moves inside)

New imports: `com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, PositiveInt}`.

```scala
private def branchSelector(branch: BranchRef): String   // Main → "main"; other → alias `main: branch(name: "...")`
private def branchArg(branch: BranchRef): String        // Main → ""; other → `branch: "...", `

def getValue(path: IrminPath, branch: BranchRef = BranchRef.Main): String
def listTree(path: IrminPath, branch: BranchRef = BranchRef.Main): String
def setValue(path: IrminPath, value: String, message: String, author: String, branch: BranchRef = BranchRef.Main): String
def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, author: String, branch: BranchRef = BranchRef.Main): String
def removeValue(path: IrminPath, message: String, author: String, branch: BranchRef = BranchRef.Main): String
def getBranchInfo(branch: BranchRef = BranchRef.Main): String
val getMainBranch: String                               // unchanged value, now = getBranchInfo(BranchRef.Main)
def mergeWithBranch(from: BranchRef, into: BranchRef, message: String, author: String): String
def revert(commitHash: CommitHash, branch: BranchRef): String
def testAndSetBranch(branch: BranchRef, test: Option[CommitHash], set: Option[CommitHash]): String
def getValueAtCommit(commitHash: CommitHash, path: IrminPath): String
def getCommit(commitHash: CommitHash): String
def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): String
def lca(branch: BranchRef, commitHash: CommitHash): String
private def escapeGraphQLString(s: String): String      // unchanged (escaping helper — legitimate String)
```

`value`, `message`, `author` stay `String`: free text / JSON payload, escaped
via `escapeGraphQLString`. The `Option[String]` branch encoding disappears
from every public signature; `BranchRef.Main` is the definite default.

### 2. `IrminClientLive` (stop unwrapping at call sites)

```scala
// DELETED — the Main-vs-named encoding now lives in IrminQueries:
private def branchName(branch: BranchRef): Option[String]
```

Every `IrminQueries.*` call site passes `branch` / `from` / `into` /
`commitHash` / `n` directly instead of `branchName(...)`, `.toBranchRef`,
`.value`. Log lines keep using `.toBranchRef` / `.value` (display text). No
public `IrminClient` signature changes.

### 3. `RiskTreeRepositoryIrmin.writeTree` (companion of the landed `readNodes` fix)

```scala
// before
private def writeTree(basePath: String, meta: TreeMetadata, nodes: Seq[RiskNode], message: String, branch: BranchRef): Task[Unit]
// after
private def writeTree(base: IrminPath, meta: TreeMetadata, nodes: Seq[RiskNode], message: String, branch: BranchRef): Task[Unit]
```

Callers `create`/`update`/`delete` refine once:
`val basePath = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeRoot(wsId, id))`
and pass `basePath` (delete passes it to `irmin.setTree` directly). Inside
`writeTree` the `IrminPath.unsafeFrom(basePath)` call disappears.

## Reviewed and excluded (stay `String`, with reasons)

- All smart constructors / parsers (`from(s: String)`, `fromString`,
  `fromClaim`, `parseInterval`, …): the boundary where the raw value arrives.
- `RiskTreeKnowledgeBase.lookupResult(assetName: String, ctx: String)`:
  vql-engine's typed representation is many-sorted over primitive payloads —
  `Value(sort: TypeId, raw: Any)`, `TypeCatalog.constants: Map[String, TypeId]`
  (verified in the sibling repo) — so the foreign API cannot carry Iron types
  through; register's `SafeName` proof is necessarily discarded when the
  catalog/domain is built and recovered as `String` by `extract`. The
  in-file boundary note (from PLAN-QUERY-NODE-NAME-LITERALS §10) documents
  why this is safe: the property travels with the value, and the dispatcher
  uses only `Map.get`/`Set.contains` — no interpolation. Re-refining inside
  the dispatcher would add per-evaluation cost to re-prove a property that
  cannot fail there. The only true elimination is making vql-engine generic
  in its payload type — a vql-engine API redesign, out of scope; candidate
  future work in the sibling repo. `ctx` is a log label (free text).
- `WorkspaceStoragePaths` members keep returning `String`: it is the layout
  serializer; inputs are already Iron types, consumers refine to `IrminPath`
  exactly once at the Irmin call.
- Free text and telemetry keys (`message`, `author`, `setAttribute(key, …)`).
- `RateLimiter.checkCreate(ip: String)` — no longer excluded; see OD-1
  (revised): scope item 4 below is the recommended form.

### 4. `RateLimiter` + `WorkspaceLifecycleController` (OD-1 = B′, recommended)

```scala
/** Client identity for rate-limiting. Nominal (ADR-018), deliberately NOT
  * format-refined: X-Forwarded-For values vary (IPv4/IPv6/proxy chains) and
  * the value is only ever a map key — never parsed, never interpolated. */
final case class ClientIp(value: String)          // in RateLimiter.scala

// RateLimiter — None = unidentifiable source (no/empty X-Forwarded-For):
def checkCreate(ip: Option[ClientIp]): IO[RateLimitExceeded, Unit]
// RateLimiterLive internal state keys by Option[ClientIp]:
ref: Ref[Map[Option[ClientIp], (Int, Instant)]]

// WorkspaceLifecycleController:
private def normaliseIp(xff: Option[String]): Option[ClientIp]
```

Behaviour-preserving: all unidentifiable sources share ONE rate-limit window
(exactly today's `"unknown"` bucket — deliberate: skipping the limit for
missing headers would let direct, non-proxied requests bypass it), but the
sentinel is now a typed `None` instead of a magic string. `RateLimitExceeded`
(common) keeps its `ip: String` display field, raised with
`ip.fold("unknown")(_.value)` — no common-module or wire changes.

## Open decisions

- **OD-1 (revised after user challenge) — `RateLimiter.checkCreate`.**
  B′ (recommended, assistant's): `Option[ClientIp]` per scope item 4 —
  nominal wrapper, no format refinement, typed absence instead of the
  `"unknown"` magic string, behaviour identical.
  A: exclude and leave `ip: String` as transport text.
  The original argument for A was cost (ripple into the common error type) —
  refuted: keeping the error's display field `String` confines the change to
  two server files. Rule with the approval; if A, the two rate-limiter files
  in the inventory are simply not touched.

No other open decisions.

## ADR alignment

- ADR-001 + adr-constraints amendment 2026-07-24: this plan is the
  enforcement pass; compliant.
- ADR-018: no new nominal types under OD-1 = A (one under OD-1 = B).
- ADR-020/ADR-032: untouched. No wire, DTO, or endpoint changes; no
  dependency changes.
- Injection note: branch names and commit hashes are currently interpolated
  into GraphQL unescaped as raw `String`s; after the change they are
  Iron-refined at the signature, so malformed values are unrepresentable at
  the builder. Behaviour for all currently-valid inputs is byte-identical.

## File inventory

- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/RateLimiter.scala` (OD-1 = B′ only)
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala` (OD-1 = B′ only)

Test files following the same signatures (amendment 2026-07-24, added with
the OD-1 = B′ ruling; no decisions of their own — they track the approved
signatures to keep the suites green per G5):

- `modules/server/src/test/scala/com/risquanter/register/services/workspace/RateLimiterSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerCascadeSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`

## Verification plan

- `sbt server/compile` — zero new warnings.
- `sbt 'commonJVM/test; server/test'` — pass.
- `sbt serverIt/test` — pass (exercises every rewritten query builder against
  live Irmin).
- Signature check: `grep -n 'Option\[String\]' modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala` → no hits;
  `grep -n ': String' IrminQueries.scala` hits only `value`/`message`/`author`/
  `escapeGraphQLString` and the `: String` return types.
- Versioning: **no bump** — internal refactoring, no behaviour change
  (per the versioning table).
