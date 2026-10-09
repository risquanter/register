# Plan: three workspace-boundary fixes — rate-limit identity, tree ceiling, dead by-id lookup

**Status:** Awaiting approval. **Date:** 2026-10-09. **Plan code:** WSF.

Three independent fixes to the workspace access boundary, each ruled by the
user, grouped into one plan so the approval token moves once. They share no
code and can land in any order; the verification bar is the same for all three.

**ADR reference:** ADR-021 (capability URLs — the workspace key is the
credential, and §4 owns the creation rate limit); ADR-036 (confidential
internal identifiers — §4's confinement argument cites the method WSF-D-3
deletes); ADR-001 (validate at the boundary); ADR-030 (`Checked[Permission]`
on protected reads).

---

## Objective

**WSF-D-1 — the creation rate limit counts an identity the caller controls.**
`WorkspaceLifecycleController.normaliseIp` reads the `X-Forwarded-For` request
header and takes its leftmost comma-separated value. No proxy in front of the
application sets that header: `containers/prod/Dockerfile.frontend-prod`
contains no `proxy_set_header` directive, and nginx forwards a client's headers
unchanged through `proxy_pass` unless told otherwise. The value the controller
reads is therefore whatever the caller typed, so varying it per request gives
each spoofed value its own fresh window of `maxCreatesPerIpPerHour` (default 5,
`modules/server/src/main/resources/application.conf`). The limit does not hold.

Two consequences follow from the same input. The limiter's state is
`Ref[Map[Option[ClientIp], (Int, Instant)]]`; lapsed windows are ignored when
read and never removed, so one map entry per distinct spoofed value is retained
for the process lifetime — an unauthenticated memory-growth path. And the value
reaches a log annotation in `RateLimiter.checkCreate`, so caller-chosen text
enters log output (CWE-117).

**WSF-D-2 — the per-workspace tree ceiling is configured and enforced
nowhere.** `WorkspaceConfig.maxTreesPerWorkspace` (default 10, env
`REGISTER_WORKSPACE_MAX_TREES`) is read by no code. Neither `addTree`
implementation checks a count and neither does `createWorkspaceTree`. Two
documents rely on the bound: the `code-quality-review` skill lists it among the
resource limits that exist, and `PLAN-RISKTRANSFORM.md` §8 states it "is what
keeps the read bounded". A key holder can accumulate unbounded trees in one
workspace.

**WSF-D-3 — `WorkspaceStore.resolveById` has no production caller and is the
sole reader of three other mechanisms.** Every call site across the repository
is a test. It is the only reader of `WorkspaceStoreLive.State.byId`, which four
other code paths maintain, and the only construction site of
`WorkspaceNotFoundById` and `WorkspaceExpiredById`. Its own scaladoc concedes
that the absence of a capability check is guarded by convention: "NEVER call
this with a `WorkspaceId` that originated from client input."

---

## Decisions — all three ruled, none open

| Label | Decision | Ruled |
|---|---|---|
| WSF-D-1 | Have the proxy write `X-Forwarded-For` and have the application read the entry our outermost proxy wrote, selected by a configured hop count. Evict lapsed windows in the same change. An edge rate limit is **not** part of this fix (see ADR alignment). | user, 2026-10-09 |
| WSF-D-2 | Enforce `maxTreesPerWorkspace`, with the check also running before a tree is written so a refused association cannot orphan a stored tree. | user, 2026-10-09 |
| WSF-D-3 | Delete `resolveById`, both implementations, the `byId` index, and the two `*ById` error types. | user, 2026-10-09 |

---

## Exact signatures

### WSF-D-1 — rate-limit identity

The extraction moves into `RateLimiter`, which already holds the configuration
it needs. The controller then passes the raw header and keeps no opinion about
how a caller is identified.

`modules/server/src/main/scala/com/risquanter/register/services/workspace/RateLimiter.scala`:

```scala
trait RateLimiter:
  /** Counts one workspace creation against the caller's address.
    *
    * Takes the raw `X-Forwarded-For` header rather than an address: which part
    * of it identifies the caller depends on how many proxies sit in front of
    * this process, which is configuration this service holds and the caller
    * does not.
    */
  def checkCreate(forwardedFor: Option[String]): IO[RateLimitExceeded, Unit]
```

```scala
final class RateLimiterLive private (
  ref: Ref[Map[Option[ClientIp], (Int, Instant)]],
  maxPerHour: Int,
  trustedProxyHops: Int
) extends RateLimiter:

  /** The caller's address as our own proxies recorded it.
    *
    * Each proxy in front of this process appends the address of its immediate
    * downstream peer to `X-Forwarded-For`. With `trustedProxyHops` of them, the
    * outermost one's entry sits at index `size - trustedProxyHops`, and
    * everything to the left of it was written by the caller and is ignored. A
    * header shorter than that is an absent header or a wrong hop count, and
    * yields `None` — all unidentifiable requests share one window, so a missing
    * header cannot buy a fresh one.
    */
  private def callerAddress(forwardedFor: Option[String]): Option[ClientIp] =
    forwardedFor
      .map(_.split(",").toList.map(_.trim).filter(_.nonEmpty))
      .flatMap(entries => entries.lift(entries.size - trustedProxyHops))
      .map(ClientIp.apply)
```

`checkCreate`'s existing `ref.modify` gains one step, dropping windows that
have lapsed before writing the new state:

```scala
        val live = state.filter((_, entry) => entry._2.isAfter(oneHourAgo))
```

The sweep is linear in the number of live windows per request. That is
acceptable precisely because this change bounds that number: entries can now
only come from addresses our own proxies observed.

`modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceConfig.scala`:

```scala
final case class WorkspaceConfig(
  ttl: Duration = Duration.ofHours(72),
  idleTimeout: Duration = Duration.ofHours(1),
  reaperInterval: Duration = Duration.ofMinutes(5),
  maxCreatesPerIpPerHour: Int = 5,
  maxTreesPerWorkspace: Int = 10,
  trustedProxyHops: Int = 1
)
```

The default is 1 because the Docker Compose stack puts exactly one proxy in
front of the server — the nginx in the frontend image. The Kubernetes
deployment has two, the Istio ingress gateway and that same nginx, so it sets
`REGISTER_TRUSTED_PROXY_HOPS=2`.

`modules/server/src/main/resources/application.conf`, in the `workspace` block:

```
    trustedProxyHops = 1
    trustedProxyHops = ${?REGISTER_TRUSTED_PROXY_HOPS}
```

`modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`
— `normaliseIp` is deleted and the call becomes:

```scala
        _      <- rateLimiter.checkCreate(xff)
```

`containers/prod/Dockerfile.frontend-prod`, once at `server` level inside the
nginx configuration so every location inherits it:

```
        # X-Forwarded-For is infrastructure-owned. A client may send one;
        # appending our peer address means the entry this proxy wrote is the
        # one the application reads (REGISTER_TRUSTED_PROXY_HOPS).
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

`docker-compose.yml`, beside the existing rate-limit variable:

```yaml
      REGISTER_TRUSTED_PROXY_HOPS: "${REGISTER_TRUSTED_PROXY_HOPS:-1}"
```

### WSF-D-2 — tree ceiling

One pure function in the `WorkspaceStore` companion, so both store
implementations share one definition of the rule:

```scala
  /** Refuses a new tree association once the workspace holds the configured
    * maximum. Re-associating a tree the workspace already holds is always
    * allowed: the association is a set, so it changes nothing.
    */
  def treeCapacity(ws: WorkspaceRecord, treeId: TreeId, limit: Int): Either[AppError, Unit] =
    Either.cond(
      ws.trees.contains(treeId) || ws.trees.size < limit,
      (),
      ValidationFailed(List(ValidationError(
        field   = "workspace.trees",
        code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = ValidationMessages.maxTreesPerWorkspaceReached(limit)
      )))
    )
```

`ValidationErrorCode.CONSTRAINT_VIOLATION` already exists and reads "Business
rule or constraint violated", so no new code and no new status mapping are
introduced — `ValidationFailed` already routes through `makeValidationResponse`.

One new trait method, called before a tree is created:

```scala
  /** Fails when the workspace is at its tree limit, before any tree is written.
    *
    * `addTree` enforces the same rule, but by then the tree exists in storage
    * and refusing the association would leave it unreachable: absent from
    * `ws.trees`, so invisible to `listTrees`, refused by `resolveTreeWorkspace`,
    * and skipped by the reaper's cascade. Callers that create a tree check here
    * first.
    */
  def checkTreeCapacity(key: WorkspaceKeySecret)(using Checked[Permission]): IO[AppError, Unit]
```

Both implementations are one expression over the shared function, and `addTree`
in each gains the same check as a backstop. A check-then-act window remains
between `checkTreeCapacity` and `addTree`: two concurrent creations at exactly
the limit can both pass the check, and the backstop then refuses one
association, which is the orphan case. The window is a genuine race at the
boundary rather than the default path, and closing it entirely would require
reserving a slot.

`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`:

```scala
  def maxTreesPerWorkspaceReached(limit: Int): String =
    s"workspace already holds the maximum of $limit risk trees — " +
    "delete one before creating another"
```

`createWorkspaceTree` in `WorkspaceLifecycleController` gains the check between
the authorization bind and `riskTreeService.create`:

```scala
        _      <- workspaceStore.checkTreeCapacity(key)
```

`bootstrapWorkspace` needs no check: it creates the workspace, so the tree count
is zero.

### WSF-D-3 — delete the by-id lookup

Removals only; no new signature.

```
modules/server/.../services/workspace/WorkspaceStore.scala
  trait method resolveById and its scaladoc; the companion accessor;
  the WorkspaceId import, unused once both are gone

modules/server/.../services/workspace/WorkspaceStoreLive.scala
  override resolveById; the byId field of State; its four maintenance sites
  in create, evictExpired, delete and rotate; the two *ById error imports

modules/server/.../services/workspace/WorkspaceStorePostgres.scala
  override resolveById; private loadWorkspaceRowById; the two *ById error imports

modules/common/.../domain/errors/AppError.scala
  case class WorkspaceNotFoundById; case class WorkspaceExpiredById

modules/common/.../domain/errors/ErrorResponse.scala
  the two encode arms that map them to the opaque 404
```

`AppError` is sealed and inexhaustive matches are compile errors in this build,
so the compiler locates every arm that must go.

**Two test assertions survive the deletion and change only their vehicle.**
`WorkspaceStoreSpec.scala`'s "rotate preserves resolveById lookup" asserts
`ws2.id == ws1.id` — that rotation preserves the workspace's identity, which is
the invariant making rotation a constant-time operation and the storage paths
stable. `WorkspaceStorePostgresSpec.scala`'s equivalent asserts that plus
`ws2.keyHash == WorkspaceKeyCrypto.hash(newKey)`. Both are preserved by
replacing `store.resolveById(ws1.id)` with `store.resolve(newKey)` and comparing
against `ws1.id` as before. No assertion is removed or weakened. The remaining
six call sites pin `resolveById`'s own behaviour and go with it.

---

## ADR alignment

**ADR-021 §4 is amended by WSF-D-1**, because its current wording is what let
this defect stand. It reads "In production: Istio rate limiting at ingress; for
standalone: simple in-app `Ref`-based counter", which presents the two as
alternatives selected by deployment. Neither half is dispreferred and the
substitution never happened: the in-app counter runs in every deployment
including Kubernetes, and no edge rate limit has ever been implemented. What the
sentence omits is the precondition that makes the in-app counter sound at all —
that the address it counts is written by infrastructure and not by the caller.
The replacement states that, gives the correct default, and records the edge
limit as unimplemented defence in depth rather than as the production choice.
No new constraint reaches the `adr-constraints` distillation: ADR-021's row
there concerns `SecureRandom` for token generation, which is unchanged.

**ADR-036 §4 is amended by WSF-D-3.** Its sentence "`WorkspaceStore.resolveById`
looks one up in a store spanning every workspace, with no accompanying
capability check" becomes false. §1's confinement rule stands; its justification
is rewritten to rest on §3's design-discipline argument — unexposed data cannot
be repurposed by a feature not yet designed — rather than on a lookup that no
longer exists. Both `adr-constraints` mirrors carry an ADR-036 row and a
known-interaction row; the ADR-036 × ADR-022 interaction is unaffected, and the
rows are checked for references to the deleted method. `docs/dev/ADR-HOUSEKEEPING.md`
and both `code-quality-review` mirrors repeat the same claim and are corrected
in the same pass.

**ADR-001** — `trustedProxyHops` and `maxTreesPerWorkspace` are counts read from
configuration, not domain values arriving from a caller, so a raw `Int` is
correct for both. `callerAddress` takes a raw `String` because it is a parser at
the boundary where the raw header arrives, which ADR-001's smart-constructor
exception covers. `ClientIp` stays unrefined, and the reason for that improves: the
value it now wraps is infrastructure-supplied, so the log-injection concern that
its current comment does not account for no longer applies.

**ADR-030** — `checkTreeCapacity` reads workspace data, so it carries
`using Checked[Permission]` like every other protected method on the trait. Its
one call site binds the check earlier in the same handler.

**ADR-035 / ADR-036** — no error message gains a secret or a confidential
identifier. `maxTreesPerWorkspaceReached` renders only the configured limit.
Deleting the two `*ById` error types removes two types that carried a
`WorkspaceId`.

---

## Open decisions

None. All three fixes are ruled (see the decisions table). Two residual
behaviours are recorded above rather than left as choices: the check-then-act
window in WSF-D-2, and the linear window sweep in WSF-D-1.

---

## File inventory

The file inventory lives in its own document,
`PLAN-WORKSPACE-SECURITY-FIXES-INVENTORY.md`, which the approval hook reads and
only the user writes. It does not exist yet; the user creates it.

Files outside `modules/` and `build.sbt` are not hook-gated and are listed here
for completeness rather than for authorization: `containers/prod/Dockerfile.frontend-prod`,
`docker-compose.yml`, `docs/dev/decision-records/ADR-021-capability-urls.md`,
`docs/dev/decision-records/ADR-036-confidential-internal-identifiers.md`,
`docs/dev/ADR-HOUSEKEEPING.md`, `docs/user/DOCKER-DEVELOPMENT.md`,
`.github/skills/code-quality-review/SKILL.md` and its `.claude` mirror.

---

## Verification plan

Docker leaked-state cleanup first, then every tier. Pass or fail only.

```bash
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm

sbt 'commonJVM/test; server/test'
sbt app/test
sbt "serverIt/test"
```

Two structural checks, each of which must return nothing:

```bash
grep -rn 'resolveById' modules/
grep -rn 'normaliseIp' modules/
```

New test cases:

- `RateLimiterSpec` — with one trusted hop, a caller-sent `X-Forwarded-For`
  entry is ignored and the appended entry is counted, so two requests carrying
  different spoofed values share one window; an absent header shares the
  unidentifiable window; a header shorter than the hop count yields the
  unidentifiable window; a window that has lapsed is removed from the map rather
  than retained.
- `WorkspaceStoreSpec` and `WorkspaceStorePostgresSpec` — at the limit a new
  tree is refused; re-associating a tree the workspace already holds succeeds at
  the limit; below the limit succeeds.
- `WorkspaceLifecycleControllerSpec` — creation at the limit is refused before
  `riskTreeService.create` runs, so no tree is written.
- `WorkspaceStoreSpec` and `WorkspaceStorePostgresSpec` — the two rotation
  assertions re-expressed through `resolve(newKey)`.

Every file touched has its unused imports removed in the same pass.

---

## What this plan does not do

It does not add an edge rate limit. ADR-021 §4's unimplemented Istio limit is
defence in depth against flooding, not the fix for WSF-D-1, and it is left as a
backlog item rather than folded in.

It does not change the workspace key's shape, its lifetime, or where it travels.
The access-log leak and the single-instance affinity for the event hub belong to
`PLAN-NGINX-WORKSPACE-ROUTING.md`; the event stream's missing
re-authorization belongs to `IMPLEMENTATION-PLAN.md` Phase H. None of those is
in scope here.
