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

**WSF-D-1 — the application's per-address rate limit is removed, not repaired.**
`WorkspaceLifecycleController` identified a caller by the leftmost
`X-Forwarded-For` entry, a value the caller writes, so varying it per request
gave each value its own fresh window. Two repairs were attempted and both failed
on the same structural fact: only the component holding the connection knows who
is calling, and anything further in has to be told, through a convention kept in
agreement with the deployment's topology by hand. A configured count of proxy
hops silently selected a caller-controlled entry whenever the count was wrong,
and it was set nowhere in `register-infra`, so Kubernetes would have run the
wrong value. Having nginx overwrite the header removed the forgery but made the
gateway's own address the identity in Kubernetes, which caps workspace creation
at the limit for the whole deployment.

`ADR-021` §4 had already placed this control at the ingress gateway for
production and scoped the in-application counter to standalone use. So the whole
mechanism is removed from the application and from the development stack.

A per-address limit at the gateway is required work in `register-infra` and does
not exist, which leaves `POST /workspaces` unbounded. That is recorded in
`ADR-021` §4 as a known gap rather than left implicit.

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
| WSF-D-1 | Remove per-address rate limiting from the application and the development stack entirely. It is an edge concern; the gateway limit is required work in `register-infra` and unbuilt. | user, 2026-10-09 |
| WSF-D-2 | Enforce `maxTreesPerWorkspace`, with the check also running before a tree is written so a refused association cannot orphan a stored tree. | user, 2026-10-09 |
| WSF-D-3 | Delete `resolveById`, both implementations, the `byId` index, and the two `*ById` error types. | user, 2026-10-09 |

---

## Exact signatures

### WSF-D-1 — what is removed

Nothing is added. The whole mechanism goes:

```
modules/server/.../services/workspace/RateLimiter.scala       whole file
modules/server/.../services/workspace/RateLimiterSpec.scala   whole file
   (trait RateLimiter, RateLimiterLive, case class ClientIp)

configs/WorkspaceConfig.scala              maxCreatesPerIpPerHour
main/resources/application.conf            its two lines
test/resources/application.conf            its line
test/.../configs/TestConfigs.scala         its field
Application.scala                          RateLimiterLive import + layer
WorkspaceLifecycleController.scala         the field, the checkCreate call,
                                           the import, the environment type
domain/errors/AppError.scala               case class RateLimitExceeded
domain/errors/ValidationErrorCode.scala    RATE_LIMIT_EXCEEDED
domain/errors/ErrorResponse.scala          the encode arm, the case 429 decode
                                           arm, makeRateLimitExceededResponse
ErrorResponseSpec.scala                    the encode and decode cases
WorkspaceLifecycleControllerSpec.scala, ...CascadeSpec.scala,
server-it HttpTestHarness.scala, StubHttpTestHarness.scala
                                           the layer from each fixture
```

The endpoint loses its now-unread header input, which changes its arity:

```scala
  val bootstrapWorkspaceEndpoint =
    baseEndpoint
      .in("workspaces")
      .post
      .in(header[Option[UserId.Authenticated]]("x-user-id"))
      .in(query[Option[SeedEntityId.SeedEntityId]]("seedEntityId"))
      .in(jsonBody[RiskTreeDefinitionRequest])
      .out(jsonBody[WorkspaceBootstrapResponse])
```

`WorkspaceState.bootstrap` in the single-page application passes a three-element
tuple to match. That is a wire-shape change (Decision Trigger #1), ruled
together with the removal.

Development-stack and documentation removals: the `docker-compose.yml`
environment entry; the `export` in `tests/bats/suite-a-full-prod.bats` and
`suite-c-in-memory.bats` that raised the limit so those suites could run; the
`proxy_set_header X-Forwarded-For` line in
`containers/prod/Dockerfile.frontend-prod`; the `docs/user/DOCKER-DEVELOPMENT.md`
configuration row; the capability list in `README.md`; and the stale references
in `AUTHORIZATION-PLAN.md` and `IMPLEMENTATION-PLAN.md` (items A27, A28, A32).

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
in each enforces the ceiling atomically as the backstop. The in-memory store
resolves, checks and writes in one `Ref.modify`. The Postgres store counts and
inserts in one transaction holding a row lock on the parent `workspaces` row;
the lock is on the parent because the rows being counted do not exist yet, so
there is nothing in `workspace_trees` to lock. `checkTreeCapacity` stays a
separate earlier read, so a creation refused at the ceiling is refused before a
tree is written; it is an early exit, not the enforcement point.

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

**ADR-021 §4 is amended by WSF-D-1.** Its wording — "In production: Istio rate
limiting at ingress; for standalone: simple in-app `Ref`-based counter" —
presented the two as alternatives selected by deployment, which is what let an
application-level per-address limit be treated as the thing to repair rather than
the thing to remove. Neither substitution ever happened: the in-application
counter ran in every deployment including Kubernetes, and no edge limit has ever
been built.

The replacement states that a per-address limit belongs at the gateway, that the
application implements none, that `POST /workspaces` is anonymous in every
authorization mode and therefore currently unbounded, and that building the
gateway limit is required work in `register-infra`. It also records that a
per-**credential** limit would legitimately sit in the application, because the
workspace key is a value the application holds and the edge cannot see
(`docs/dev/TODO.md` item 42).

One constraint is added to both `adr-constraints` mirrors: never implement a
per-address rate limit in the application, and never derive any identity from a
caller-settable header. Both `code-quality-review` mirrors stop citing the
removed limiter as the model for guarding a new creation flow. ADR-021's existing
distillation row concerns `SecureRandom` for token generation and is unchanged.

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

**ADR-001** — `maxTreesPerWorkspace` is a count read from configuration, not a
domain value arriving from a caller, so a raw `Int` is correct. `callerAddress`
takes a raw `String` because it sits at the boundary where the raw header
arrives, which ADR-001's smart-constructor exception covers. `ClientIp` stays
unrefined, and the reason for that improves: the value it now wraps is written
by the proxy, so the log-injection concern its previous comment did not account
for no longer applies.

**ADR-030** — `checkTreeCapacity` reads workspace data, so it carries
`using Checked[Permission]` like every other protected method on the trait. Its
one call site binds the check earlier in the same handler.

**ADR-035 / ADR-036** — no error message gains a secret or a confidential
identifier. `maxTreesPerWorkspaceReached` renders only the configured limit.
Deleting the two `*ById` error types removes two types that carried a
`WorkspaceId`.

---

## Open decisions

None. All three changes are ruled (see the decisions table).

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

It does not add an edge rate limit. ADR-021 §4's gateway limit is required work
in the `register-infra` repository, outside this plan, and until it exists
`POST /workspaces` has no limit at all.

It does not change the workspace key's shape, its lifetime, or where it travels.
The access-log leak and the single-instance affinity for the event hub belong to
`PLAN-NGINX-WORKSPACE-ROUTING.md`; the event stream's missing
re-authorization belongs to `IMPLEMENTATION-PLAN.md` Phase H. None of those is
in scope here.
