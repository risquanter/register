# Plan: the proof token's bound is `Authority`, and the two lifecycle markers leave `Permission`

**Status:** Implemented on 2026-11-04. Every tier in the verification plan ran
green: the common, server and app unit suites, the integration suite, and BATS
suites C and A against a server image built from the changed code. The
negative compile probe failed as expected with `Required: Permission`.
**Date:** 2026-11-04.
**ADR reference:** ADR-030 (authorization enforcement at the orchestration
boundary), ADR-024 (the application as a pure policy enforcement point),
ADR-037 (closed sets as enums), ADR-011 (imports and short names in
signatures).
**Inventory:** `docs/dev/plans/PLAN-CA-CHECKED-AUTHORITY-INVENTORY.md`, written
by the user. It does not exist yet.

Single change scoped to the `server` module and its two test modules. No wire
shape, no endpoint, no serialised format and no figure changes. One
architecture decision record (ADR, the project's form for a binding design
constraint) is amended in place.

---

## Objective

`AuthorizationService.check` accepts two values it must never receive.
`Permission.Bootstrap` and `Permission.SystemMaintenance` are cases of the
`Permission` enum, and their scaladoc says "Never sent to SpiceDB", yet
`check[P <: Permission]` admits them. The call
`check(user, Permission.Bootstrap, resource)` compiles today and would send the
string `__bootstrap__` to SpiceDB, which can only answer no-permission. No
caller does this. The shape permits it, and the algebraic-design rule for that
shape is that the input type is too wide.

After this plan the two markers are cases of their own enum, `LifecycleMarker`.
`Permission` holds only what SpiceDB evaluates. A sealed parent, `Authority`,
is what a proof may witness, and the proof token's bound moves from
`Permission` to `Authority`. `check` keeps its bound of `Permission`, so the
mistaken call no longer compiles.

---

## Background

### The proof token

A **proof token** is a value whose only purpose is to be demanded by one
signature and supplied by another, so that the compiler refuses a call made
without it. In this project the token is `Checked[P]`, an opaque type over
`Unit` defined in
`modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala`.
Its constructor is private to the `auth` package. A service method that reads
or mutates workspace or tree data declares `(using Checked[Permission])`. A
handler obtains the proof from `AuthorizationService.check`, which asks SpiceDB
whether the user holds a permission on a resource and fails the effect when
the answer is no. Two further producers exist for the cases where no resource
or no user is present: `BootstrapProvisioner.bootstrapToken()` for workspace
creation, and `BootstrapProvisioner.systemMaintenanceToken()` for the
background reaper. ADR-030 §3 fixes the bound in service signatures at the
base type rather than a specific permission, so that which permission allows
which operation stays in the SpiceDB schema.

### Why the markers were put into `Permission`

`Checked[+P <: Permission]` is covariant, so a proof of any subtype of
`Permission` satisfies `using Checked[Permission]`. The two lifecycle tokens
needed to satisfy that bound, and the smallest change at the time was to add
two cases to the enum that already sat under the bound. ADR-030 §5 records
this: "It exists in the `Permission` enum solely to produce a
`Checked[Permission]` proof".

### The totality rule

`.claude/rules/scala-algebraic-design.md`, under "Totality and inverse-pair
recognition", states the test: "Does a function need an answer for a value of
its input type that has no correct answer? → The input type is too wide. Narrow
it to the values the function is defined on, and connect the narrow type to the
wide one by an injection or a coproduct case." `check` has no correct answer for
a lifecycle marker. The coproduct the rule asks for is `Authority`, with
`Permission` and `LifecycleMarker` as its two cases.

---

## Decisions

### CA-D-1 — Split the type. Ruled.

**Ruled 2026-11-04:** the markers leave `Permission`. A sealed trait
`Authority` is the proof token's bound. `Permission` and a new enum
`LifecycleMarker` extend it. `check` keeps `P <: Permission`. Service methods
declare `using Checked[Authority]`. The names `Authority` and `LifecycleMarker`
were in the recommendation the user accepted. `LifecycleMarker` is the phrase
ADR-030 §5 already uses for these two values, so it coins nothing.

The alternative, keeping the enum as it is, left the unreachable branch in
`check` guarded by a comment. It was presented and not chosen.

### CA-D-2 — The proof stays unbound from the resource. Ruled, no change.

**Ruled 2026-11-04:** a proof obtained for one resource continues to satisfy a
service call on another. Binding the proof to the resource by a dependent
method type was presented and not chosen, because the cascade-delete paths
would then need a rule that a workspace-level proof covers the workspace's
trees, and that rule is the policy ADR-030 §3 keeps out of Scala types. This
decision changes no code. It is recorded so the question is not reopened.

### Open decisions

None.

---

## Exact signatures

### The type family

Family search for the sealed parent: the repository holds sealed traits whose
cases are distinct nominal types, `StoreBranch` and `UserId` in
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`.
`UserId` is quoted as the existing member:

```scala
sealed trait UserId:
  override def toString: String = "UserId(***)"
```

Family search for an enum extending a sealed parent: `RequestFailure` and
`LocalProblem` in `modules/app/src/main/scala/app/core/ViewFailure.scala` both
read `enum X extends ViewFailure`. That is the pattern followed. No existing
type denotes "what a proof may witness", so `Authority` is new. The two markers
are an existing set moving to its own type, not a new set.

In `modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala`,
replacing the current `enum Permission` and `opaque type Checked`:

```scala
/** What a proof may witness: a SpiceDB permission that `check()` evaluated, or
  * a lifecycle marker standing in where no resource or no user exists to check.
  * `Checked` is bounded by this type; `check()` is bounded by `Permission`, so
  * a marker cannot be sent to SpiceDB (ADR-030).
  */
sealed trait Authority

/** Permission names — values match SpiceDB Zed schema `permission` names exactly.
  * A schema rename that doesn't update this enum fails CI at all check() call sites.
  */
enum Permission(val zedName: String) extends Authority:
  case DesignWrite         extends Permission("design_write")
  case AnalyzeRun          extends Permission("analyze_run")
  case ViewWorkspace       extends Permission("view_workspace")
  case AdminWorkspace      extends Permission("admin_workspace")
  case ViewTree            extends Permission("view_tree")
  case ViewOrg             extends Permission("view_org")
  case ViewTeam            extends Permission("view_team")
  case ManageTeam          extends Permission("manage_team")

/** A proof produced without consulting SpiceDB.
  *
  * `Bootstrap` covers workspace creation, where no resource exists yet to check.
  * `SystemMaintenance` covers the background reaper, where no user is present.
  * Produced only by `BootstrapProvisioner.bootstrapToken()` and
  * `BootstrapProvisioner.systemMaintenanceToken()` (ADR-030).
  */
enum LifecycleMarker extends Authority:
  case Bootstrap, SystemMaintenance

/** Compile-time proof that AuthorizationService.check() was called and succeeded,
  * or that a lifecycle token was issued.
  *
  * Opaque — no public constructor. Only constructible inside the auth package.
  * Erased to Unit at runtime; carries no runtime value.
  *
  * Service method `using` parameters take the base `Authority` — not a specific
  * case — to avoid duplicating SpiceDB policy in Scala types (ADR-024). Any
  * `Checked[P]` where `P <: Authority` satisfies `using Checked[Authority]` via
  * covariance.
  */
opaque type Checked[+P <: Authority] = Unit

object Checked:
  /** Package-private — only auth-package code may produce proofs. */
  private[auth] def apply[P <: Authority](): Checked[P] = ()
```

`zedName: String` is unchanged. It is a contract with the SpiceDB schema, which
is the declared-spelling case of ADR-037 §2, so no refined type replaces it.
`AuthorizationService.check` and `listAccessible` are unchanged.

### The provisioner

In `modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisioner.scala`,
and in each implementation (`BootstrapProvisionerNoOp.scala`,
`BootstrapProvisionerSpiceDB.scala`, `BootstrapProvisionerStub.scala`, and the
`noOp` value in the companion):

```scala
def bootstrapToken(): UIO[Checked[LifecycleMarker.Bootstrap.type]]
def systemMaintenanceToken(): UIO[Checked[LifecycleMarker.SystemMaintenance.type]]
```

Bodies become `ZIO.succeed(Checked[LifecycleMarker.Bootstrap.type]())` and
`ZIO.succeed(Checked[LifecycleMarker.SystemMaintenance.type]())`.

### The two lifecycle bindings

`modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`,
in `bootstrapWorkspace` and `evictExpired`:

```scala
given Checked[LifecycleMarker.Bootstrap.type] <- bootstrapProvisioner.bootstrapToken()
given Checked[LifecycleMarker.SystemMaintenance.type] <- bootstrapProvisioner.systemMaintenanceToken()
```

`modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceReaper.scala`,
in `reapLoop`:

```scala
given Checked[LifecycleMarker.SystemMaintenance.type] <- provisioner.systemMaintenanceToken()
```

Every `given Checked[Permission] <- authzService.check(...)` binding in a
controller is unchanged. A `Checked[Permission]` is a `Checked[Authority]` by
covariance.

### The service signatures

Every `(using Checked[Permission])` and every
`(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission])`
in production code becomes `(using Checked[Authority])`, written with the short
name. ADR-011 §3 forbids a fully qualified name in a signature, and the files
that carry one already import `Checked` and `Permission` at the top. The import
line in each touched file adds `Authority` and drops `Permission` where no other
use remains. The resulting signatures, verbatim:

`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`:

```scala
def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Authority]): IO[AppError, RiskTree]
def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Authority]): IO[AppError, RiskTree]
def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Authority]): IO[AppError, RiskTree]
def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using Checked[Authority]): IO[AppError, RiskTree]
def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Authority]): IO[AppError, Option[(RiskTree, CommitHash)]]
def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision)(using Checked[Authority]): IO[AppError, Double]
def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision, omitAbsent: Boolean = false)(using Checked[Authority]): IO[AppError, Map[NodeId, LECNodeCurve]]
def cascadeDeleteTrees(wsId: WorkspaceId, ids: Iterable[TreeId])(using Checked[Authority]): UIO[Unit]
```

`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
carries the `override` of each of the first seven with the same parameter
lists.

`modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala`,
trait and accessor object:

```scala
def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using Checked[Authority]): IO[AppError, Unit]
def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using Checked[Authority]): IO[AppError, Unit]
def listTrees(key: WorkspaceKeySecret)(using Checked[Authority]): IO[AppError, List[TreeId]]
def checkTreeCapacity(key: WorkspaceKeySecret)(using Checked[Authority]): IO[AppError, Unit]
def delete(key: WorkspaceKeySecret)(using Checked[Authority]): IO[AppError, Unit]
def rotate(key: WorkspaceKeySecret)(using Checked[Authority]): IO[AppError, WorkspaceKeySecret]

def checkTreeCapacity(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, AppError, Unit]
def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using p: Checked[Authority]): ZIO[WorkspaceStore, AppError, Unit]
def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using p: Checked[Authority]): ZIO[WorkspaceStore, AppError, Unit]
def listTrees(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, AppError, List[TreeId]]
def delete(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, AppError, Unit]
def rotate(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, AppError, WorkspaceKeySecret]
```

`WorkspaceStoreLive.scala` and `WorkspaceStorePostgres.scala` in the same
directory carry the `override` of each of the first six.

`modules/server/src/main/scala/com/risquanter/register/services/ScenarioService.scala`,
trait, accessor object and extension:

```scala
def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)(using Checked[Authority]): IO[AppError, BranchRef]
def list(wsId: WorkspaceId)(using Checked[Authority]): IO[AppError, List[ScenarioSummary]]
def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)(using Checked[Authority]): IO[AppError, Unit]
def cascadeDeleteScenarios(wsId: WorkspaceId)(using Checked[Authority]): UIO[Unit]
```

`ScenarioServiceLive.scala` and `ScenarioServiceNotSupported.scala` carry the
`override` of the first three.

`modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala`,
trait and accessor object:

```scala
def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, MergePreviewStatus]
def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, CommitHash]
```

`ScenarioMergeServiceNotSupported.scala` carries the `override` of both.

`modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala`,
trait and accessor object, and the `override` in `QueryServiceLive.scala`:

```scala
def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, seedEntityId: SeedEntityId.SeedEntityId, branch: BranchRef)(using Checked[Authority]): IO[AppError, QueryResponse]
```

`modules/server/src/main/scala/com/risquanter/register/services/ChangedNodesService.scala`,
`TreeHistoryService.scala` and `CascadeDelete.scala`: the one `using` clause in
each signature becomes `(using Checked[Authority])`, parameter lists unchanged.

### Test scope

`modules/server/src/test/scala/com/risquanter/register/auth/TestChecked.scala`:

```scala
val value: Checked[Authority] = Checked[LifecycleMarker.Bootstrap.type]()
```

Every test file that binds the test proof writes
`private given Checked[Authority] = TestChecked.value` in place of
`private given Checked[Permission] = TestChecked.value`. The stubs in
`CascadeTestStubs.scala` and the anonymous merge services in
`ScenarioControllerSpec.scala` carry `(using Checked[Authority])` in each
overridden signature, as the production traits do.

---

## File inventory

The inventory document is `docs/dev/plans/PLAN-CA-CHECKED-AUTHORITY-INVENTORY.md`,
beside this plan, written by the user. It lists every file named in "Exact
signatures" and "Test scope" above, and the three integration-test files
`SeedReproducibilityItSpec.scala`, `ScenarioMergeServiceItSpec.scala` and
`TreeRevertItSpec.scala` under `modules/server-it/src/test/scala/com/risquanter/register/`,
which bind the test proof.

Not touched: the five controllers other than `WorkspaceLifecycleController`
(`QueryController`, `ScenarioController`, `WorkspaceAnalysisController`,
`WorkspaceTreeController`, `SSEController`), because their bindings are
`Checked[Permission]` from `check()` and remain valid. Not touched: the three
`AuthorizationService` implementations, because `check` is unchanged.

---

## ADR alignment

Activity in the `adr-constraints` skill: "Designing or changing a domain type",
and "Touching authorization or a capability boundary".

| Record | Finding |
|---|---|
| ADR-001 | Compliant. No value crosses a boundary; `Checked` carries no runtime value and nothing is validated. |
| ADR-010 | Compliant. No error type changes. |
| ADR-011 | Compliant, and the plan brings the touched files into conformance: the fully qualified `com.risquanter.register.auth.Checked[...]` in `RiskTreeService`, `RiskTreeServiceLive`, `WorkspaceStore`, `WorkspaceStoreLive` and `WorkspaceStorePostgres` violates §3 today and becomes the short name. |
| ADR-012 | Compliant. No mesh assumption changes. |
| ADR-018 | Compliant. No identifier type changes. |
| ADR-021 | Compliant. §2's comment "Protected methods additionally require `using Checked[Permission]`" becomes stale and is swept to `Checked[Authority]`. |
| ADR-024 | Compliant. `check` keeps its signature. The application writes no authorization data. |
| ADR-029 | Compliant. No string reaches a parser. |
| ADR-030 | **Amended**, in place. §3 "Base Permission Type" becomes the base `Authority` type. §5 names `LifecycleMarker.Bootstrap` and no longer says the marker "exists in the `Permission` enum". The code smell "Specific Permission Type in Service Signature" and the Implementation table follow. No section is retired, so no retirement marker is needed. The amendment adds no record and no collision, so the `adr-constraints` skill's three layers are unchanged. |
| ADR-036 | Compliant. §1's example and §4's last sentence write `using Checked[Permission]` and are swept to `Checked[Authority]`. |
| ADR-037 | Compliant. A fixed set stays an enum in the file that owns it. `zedName` stays a declared spelling under §2. |

---

## Documents this plan makes stale

Swept in the same pass, because each is a current-state document or an ADR
whose wording the ruling overwrites with one defensible replacement:

- `docs/dev/decision-records/ADR-030-authorization-enforcement-orchestration-boundary.md`, as the table above states.
- `docs/dev/decision-records/ADR-021-capability-urls.md` §2, one comment line.
- `docs/dev/decision-records/ADR-036-confidential-internal-identifiers.md` §1 and §4, two occurrences.
- `.claude/rules/security.md`, the comment line in the authorization-boundary code block.
- `.claude/skills/code-quality-review/SKILL.md`, the two occurrences under API5 and API9.

Presented and waiting, because each is a plan not yet implemented and the
ruling was given for this task: `PLAN-TREE-WRITE-CONCURRENCY.md` (lines 720 and
829 to 834), `PLAN-WORKSPACE-CACHE-RELEASE.md` (line 181) and
`PLAN-RISKTRANSFORM.md` (line 8807) write service signatures with
`(using Checked[Permission])`. The one defensible replacement is
`(using Checked[Authority])`. Whether those plans are overwritten now is the
user's call.

Left as they are: `docs/dev/AUTH-PHASES.md`, `docs/dev/TODO.md` and the
archived plans, which record what was done or what was rejected at a point in
time.

---

## Terminology impact

`docs/user/TERMINOLOGY.md` has no term for the proof token, for a permission,
or for a lifecycle marker. No entry is affected.

---

## Verification plan

Tests to add: none. The change is a type split with no new behaviour, and the
existing suites exercise every producer and every consumer of the proof. A
negative compile check is the one new verification: after the change, a call
of `check` with a lifecycle marker must not compile. The REPL cannot be driven
from a pipe (register-dev skill), so the check is a probe line added to
`TestChecked.scala`, which is in the `auth` package and on the inventory:

```scala
def probe(a: AuthorizationService, u: UserId.Authenticated, r: ResourceRef) =
  a.check(u, LifecycleMarker.Bootstrap, r)
```

Expected: `sbt server/Test/compile` fails on that line with a type mismatch
naming `Permission`. The line is removed before the suite runs, and the file is
left without it.

Commands, in order, each green:

```bash
sbt 'commonJVM/clean; commonJVM/compile'
sbt 'server/compile; serverIt/Test/compile'
sbt 'commonJVM/test; server/test'
sbt app/test
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm; echo "--- remaining register_it_ containers/networks/volumes ---"; for k in "ps -a" "network ls" "volume ls"; do docker $k --filter name=register_it_ -q | wc -l; done
sbt serverIt/test
docker compose -p register down -v --remove-orphans 2>/dev/null || true
docker compose build --progress=plain register-server 2>&1 | tee /tmp/register-server-build.log | grep -E "Peak RSS|error|Error"
run_bats tests/bats/suite-c-in-memory.bats
run_bats tests/bats/suite-a-full-prod.bats
```

The server image is rebuilt because the shipped code changes and the image at
the current `APP_VERSION` tag was built from the old code. The frontend image is
not rebuilt, because no file under `modules/app` or `modules/common` changes.
`run_bats` is the shell function defined in the register-dev skill. Expected
output of each `sbt` command ends in `[success]`; expected output of each BATS
suite is every test line marked `ok` and no line marked `not ok`.
