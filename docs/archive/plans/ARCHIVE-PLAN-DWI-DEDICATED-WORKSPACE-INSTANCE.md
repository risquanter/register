# PLAN-DWI — Dedicated workspace instance: one register server process serves exactly one workspace

**Status:** Draft. Three decisions are open (DWI-D-1 to DWI-D-3) and one placement question is unanswered (§9). It confers no implementation coverage until they are ruled and the signatures of §5 are confirmed against the rulings.
**Unruled premise, recorded 2026-11-13:** this draft assumes pairs are started in advance and assigned at creation. That was never ruled. The user's stated design is that a pair is started when its workspace is created. The choice is decision WSS-D-15 of the counterpart plan in `register-infra`, and it is prior to DWI-D-1. The slot, the `pool` table, the claim, §4.2, §4.3 and all three options of DWI-D-1 hold only if that decision selects the pool. If it selects starting on creation, this plan is redrafted from §4 on.
**Discussion record, 2026-11-13:** §13 holds what the user stated about Docker Compose, about where key resolution and routing are done, and about the direction of this design. It is not a ruling. The recommendations on DWI-D-1 and DWI-D-2 are withdrawn, because each rested on an unverified premise.
**Written:** 2026-11-13
**Decision labels:** `DWI-D-<n>`, numbered from 1 in this document.
**Counterpart:** `register-infra/docs/plans/PLAN-WSS-WORKSPACE-SEPARATION.md`, stage A, in the sibling repository `register-infra`. That plan owns the cluster objects. This plan owns the register server's code. §7 lists every point where the two must agree.
**Inventory document:** `docs/dev/plans/PLAN-DWI-DEDICATED-WORKSPACE-INSTANCE-INVENTORY.md`. It does not exist yet. The user creates it after the decisions are ruled, from the provisional table in §11.

---

## 1. Goal

Today one register server process serves every workspace. After this plan the same server image can also run in a second way, in which one process serves exactly one workspace and refuses every other workspace's key. The cluster then runs one such process per workspace, each beside its own Irmin store. The existing way of running stays, because Docker Compose, the integration tests and the BATS suites use it.

The security purpose is tenant separation. A request that reaches the wrong instance must be refused by that instance, whatever routed it there.

## 2. Terms

- **Workspace.** One tenant's unit of data: a capability key, a row in the `workspaces` table and a set of risk trees. Defined in `docs/user/TERMINOLOGY.md`.
- **Instance.** One running register server process. The glossary has no term for it.
- **Pair.** One instance together with the one Irmin store it talks to. PLAN-WSS in `register-infra` creates pairs.
- **Slot.** The identity of one pair. It exists before any workspace uses the pair and never changes. The glossary has no term for it.
- **Pool.** The set of pairs that exist and have no workspace yet. PLAN-WSS keeps the pool at a target size, so that creating a workspace does not wait for a pod and a volume.
- **Claim.** The one-time step that binds a slot to a workspace.
- **Tenancy.** Which of the two ways an instance runs. *Shared* is today's behaviour: the instance serves every workspace in its store. *Dedicated* is the new one: the instance serves the workspace its slot is claimed for, and no other.
- **Resolver.** The component in `register-infra` that maps a workspace key to a slot for the gateway. It is specified in PLAN-WSS §3.3.
- **Controller.** The component in `register-infra` that creates and deletes pairs. It is specified in PLAN-WSS §3.4.

## 3. What the code does today

Four properties of the current server assume that one process holds all workspaces. Each is quoted, because each is what this plan changes.

**The key is looked up among all workspaces.** `WorkspaceStore.resolve` in `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala` takes only the key:

```scala
def resolve(key: WorkspaceKeySecret): IO[Failure, WorkspaceRecord]
```

Any valid key resolves on any instance that can reach the table.

**Bootstrap creates the workspace and its first tree in one request.** `bootstrapWorkspace` in `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`:

```scala
key    <- workspaceStore.create(seedEntityId)
ws     <- workspaceStore.resolve(key)  // exempt: Layer 0 capability gate
tree   <- riskTreeService.create(ws.id, req, BranchRef.Main)
_      <- workspaceStore.addTree(key, tree.id)
_      <- bootstrapProvisioner.recordOwnership(userId, ws.id)
```

The response, `WorkspaceBootstrapResponse` in `modules/common/src/main/scala/com/risquanter/register/http/responses/WorkspaceResponse.scala`, carries `workspaceKey`, `tree`, `expiresAt` and `seedEntityId`. The tree is written to the Irmin store of whichever instance handled the request.

**Expiry removes every expired workspace.** `evictExpired` in `WorkspaceStorePostgres.scala` reads all rows and deletes the expired ones:

```scala
rows    <- db(run(query[WorkspaceRow]))
```

`WorkspaceReaper` then deletes each evicted workspace's trees through `CascadeDelete.workspace`, using the instance's own Irmin store.

**Every instance holds one database login.** `application.conf` gives a single `register.db.dataSource.user`. That login can read and write every row of `workspaces` and `workspace_trees`.

## 4. Design

The design below was drafted on option C of DWI-D-1 and option B of DWI-D-2. Neither is recommended any longer (§8). A ruling for another option changes the passages marked with that decision's label. The direction recorded in §13 changes where the two ways of running differ, and this section is redrafted if that direction is ruled.

### 4.1 Two tenancies, one image

The instance reads its tenancy from configuration at startup. In shared tenancy nothing changes. In dedicated tenancy the instance is given its slot, and every store operation is limited to the workspace that slot is claimed for.

Dedicated tenancy requires the Postgres workspace store, because the claim is a database row. The configuration decoder refuses dedicated tenancy with the in-memory store, so that combination cannot start.

### 4.2 The `pool` table

A new table records each slot and the workspace it is claimed for. Register's Flyway migrations own the schema, as they own `workspaces`. The controller inserts a row when it creates a pair. The resolver reads it.

```sql
-- V3__workspace_pool.sql
CREATE TABLE pool (
  slot         TEXT PRIMARY KEY CHECK (slot ~ '^[0-9A-HJKMNP-TV-Z]{26}$'),
  workspace_id TEXT UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  claimed_at   TIMESTAMPTZ,
  released_at  TIMESTAMPTZ,
  CHECK ((workspace_id IS NULL) = (claimed_at IS NULL)),
  CHECK (released_at IS NULL OR claimed_at IS NOT NULL)
);
```

The `slot` pattern is the pattern of `SafeIdStr` in `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`, a 26-character ULID. `workspace_id` has no foreign key, because the row keeps the identifier after the workspace row is deleted. The controller needs it then to find the pair and its volume.

A slot passes through three states. *Unclaimed*: `workspace_id` is null. *Claimed*: `workspace_id` is set and `released_at` is null. *Released*: `released_at` is set. A released slot is never claimed again, because its Irmin store still holds the previous workspace's data.

### 4.3 Bootstrap on an unclaimed instance (DWI-D-1, option C)

The gateway sends `POST /workspaces` to an unclaimed instance. That instance runs today's bootstrap sequence unchanged. The one difference is inside `workspaceStore.create`: in the same transaction that inserts the workspace row, it claims the instance's own slot.

```sql
UPDATE pool SET workspace_id = $1, claimed_at = now()
WHERE slot = $2 AND workspace_id IS NULL AND released_at IS NULL
```

If the update changes no row, the slot was claimed by an earlier request, and the transaction is rolled back. The instance then refuses the bootstrap. The first tree is written to the instance's own store, so the response keeps its present shape.

The `Failure` case and HTTP status of that refusal are not designed here. They are chosen from ADR-010 and `WireError` once DWI-D-1 is ruled, and the gateway's retry rule in PLAN-WSS depends on the status.

### 4.4 Key resolution in dedicated tenancy

`resolve` finds a row only when its key hash matches and its identifier is the one the instance's slot is claimed for. Any other key gets the same opaque `404` an unknown key gets today. An unclaimed or released instance therefore refuses every key.

The instance never takes its workspace from a request. The resolver's routing header is not read by the server. ADR-036 §1 requires this: "no endpoint accepts it as a path segment, query parameter, header, or body field".

### 4.5 Expiry and deletion in dedicated tenancy

`evictExpired` considers only the instance's own workspace. When it evicts, and when `delete` removes the workspace, the same transaction sets `released_at` on the slot. The controller sees the released slot and removes the pair.

The reaper and `deleteWorkspace` do not delete the trees from Irmin in dedicated tenancy. PLAN-WSS ruled, as its decision on volume retention, that a deleted workspace's volume is kept for a period as the recovery path. Deleting the trees first would empty what is retained. This is a consequence I derived from that ruling, and it is reversible by one branch in two call sites.

### 4.6 What does not change

- The `WorkspaceStore` trait. No method is added, removed or re-typed.
- Every endpoint and every DTO, under DWI-D-1 option C.
- Key rotation. It changes the key hash of the instance's own row, and the resolver finds the same slot by the new hash.
- The cache registries. `ContentCacheRegistry` and `MitigationScopeResolverRegistry` hold one entry in dedicated tenancy. `docs/dev/plans/PLAN-WORKSPACE-CACHE-RELEASE.md` stays needed for shared tenancy.
- Simulation settings. `REGISTER_TRIAL_PARALLELISM` and `REGISTER_MAX_CONCURRENT_SIMULATIONS` already exist. PLAN-WSS sets them per pod.

## 5. Signatures

Every signature below is new unless marked changed. All assume DWI-D-1 option C.

**Slot identifier.** Family: nominal wrappers over an Iron type (ADR-018), in `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`. Existing member: `case class WorkspaceId(toSafeId: SafeId.SafeId)`. A slot has the same encoding as a workspace identifier and must not be interchangeable with it, which is the case ADR-018 names. No existing type denotes a slot. It gets no JSON codec, because it is an internal scoping identifier (ADR-036, ADR-038 §4).

```scala
case class PoolSlotId(toSafeId: SafeId.SafeId)
```

**Tenancy.** Family: configuration enums in `modules/server/src/main/scala/com/risquanter/register/configs/`. Existing member: `enum WorkspaceStoreBackend: case InMemory, Postgres`, decoded by a hand-written `Config.string.mapOrFail`. No existing type denotes tenancy. The slot is carried by the dedicated case, so dedicated tenancy without a slot cannot be constructed.

```scala
enum WorkspaceTenancy:
  case Shared
  case Dedicated(slot: PoolSlotId)

object WorkspaceTenancy:
  given DeriveConfig[WorkspaceTenancy]
```

**Configuration, changed.** `WorkspaceConfig` in `modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceConfig.scala` gains one field with no default.

```scala
final case class WorkspaceConfig(
  ttl: Duration = Duration.ofHours(72),
  idleTimeout: Duration = Duration.ofHours(1),
  reaperInterval: Duration = Duration.ofMinutes(5),
  maxTreesPerWorkspace: PositiveInt = 10,
  cascadeDeleteParallelism: PositiveInt,
  tenancy: WorkspaceTenancy
)
```

```hocon
workspace {
  tenancy {
    mode = "shared"
    mode = ${?REGISTER_WORKSPACE_TENANCY}
    slot = ${?REGISTER_WORKSPACE_SLOT}
  }
}
```

The spellings `shared` and `dedicated` are a contract with the deployment's environment variables, so they are declared and not derived (ADR-037 §2). The decoder refuses `dedicated` with no slot, `shared` with a slot, and a slot that is not a ULID. The slot string is a raw primitive only inside the decoder, which is the boundary that refines it (ADR-039 §1).

**Store, changed behaviour, unchanged signatures.** `WorkspaceStorePostgres` already receives `WorkspaceConfig`, so its constructor and its `layer` keep their types:

```scala
val layer: ZLayer[Quill.Postgres[SnakeCase] & WorkspaceConfig, Nothing, WorkspaceStore]
```

Its `create`, `resolve`, `evictExpired`, `delete` and `rotate` match on `config.tenancy`. `WorkspaceStoreLive`, the in-memory store, is not changed.

**Startup check.** `Application.chooseWorkspaceStore` refuses `WorkspaceTenancy.Dedicated` with `WorkspaceStoreBackend.InMemory`. Its type is unchanged.

## 6. Decision-record alignment

Records read in full for this draft: ADR-016, ADR-021, ADR-024, ADR-031. Records read by the section the design touches: ADR-012 §4 and §7, ADR-030 §1 and §5, ADR-036 §1. The other records the `adr-constraints` skill names for these activities are ADR-001, ADR-004a, ADR-010, ADR-017, ADR-018, ADR-033, ADR-035, ADR-037, ADR-038 and ADR-039. They have not been read in full for this draft. That reading is owed before approval, together with the planning-phase review the skill requires.

| Record | Finding |
|---|---|
| ADR-021 §2 | Compliant. "There is no by-identifier lookup" still holds: every store method keeps taking the key. The record describes one store for all workspaces. This plan owes it a section on dedicated tenancy and the pool. |
| ADR-021 §4 | Changed in effect. Workspace creation is unbounded today. Under DWI-D-1 option C the pool size bounds it. The gateway rate limit the record asks for is still owed by `register-infra`. |
| ADR-024 §4 | Compliant. `recordOwnership` stays a lifecycle write in the bootstrap handler. |
| ADR-030 §1 | Compliant. The handler and the reaper stay the only call sites of the store. |
| ADR-036 §1 | Compliant. The server reads no workspace or slot identifier from a request (§4.4). `PoolSlotId` gets no codec. |
| ADR-012 §4 | Compliant. The server does not retry a refused bootstrap. The retry belongs to the gateway. |
| ADR-016 | Compliant. Tenancy is a setting in `application.conf` with an environment override. |
| ADR-031 | Compliant. No new wait or retry is added. |

Amendments this plan owes, in its own scope: the new section in ADR-021, and the placement of that change in the three layers of `.claude/skills/adr-constraints/SKILL.md`.

## 7. Agreement with PLAN-WSS in `register-infra`

| # | Point | What this plan fixes | What PLAN-WSS must match |
|---|---|---|---|
| I-1 | Slot format | A 26-character ULID in upper case, stored in `pool.slot` | Kubernetes object names must be lower case. Object names and labels use the lower-case form of the slot. |
| I-2 | Who writes `pool` | Register's migration creates the table. The instance claims and releases. | The controller inserts a row per pair and deletes it with the pair. |
| I-3 | Resolver lookup | The key hash is SHA-256 over the UTF-8 key, in lower-case hex (`WorkspaceKeyCrypto.hash`) | The resolver returns the slot of the claimed, unreleased row whose `key_hash` matches. |
| I-4 | Bootstrap routing | An unclaimed instance accepts `POST /workspaces`. A claimed one refuses it. | `/workspaces` is routed to unclaimed instances, and a refusal is retried on another instance. |
| I-5 | Removal | `released_at` is set when the workspace is evicted or deleted. | The controller removes pairs whose slot is released, and applies volume retention. |
| I-6 | Environment | `REGISTER_WORKSPACE_TENANCY`, `REGISTER_WORKSPACE_SLOT`, `REGISTER_WORKSPACE_STORE_BACKEND=postgres`, `IRMIN_URL` | The pod template sets all four. The slot value is fixed for the life of the pair. |
| I-7 | Database logins | Per DWI-D-2 | The controller creates and delivers a login per pair under option B. |

## 8. Open decisions

**DWI-D-1 — how a workspace is created when each workspace has its own instance.**
Bootstrap today creates the workspace row and the first tree in one process, and answers with both. With one store per workspace, the tree has to be written by the instance that will own the workspace.

- **A) A dedicated bootstrap instance, which then calls the claimed instance.** One extra instance serves `/workspaces`. It creates the row, claims a slot, calls that slot's instance to create the tree, and returns today's response. The wire shape is kept. It costs a second role for the image, a call from one instance to another, and a path in the network policy between them. Example: a user creates a workspace, and the request crosses two register processes before the answer returns.
- **B) Two steps for the client.** Bootstrap returns the key only. The client then creates the first tree with `POST /w/{key}/risk-trees`. This is the simplest server. `WorkspaceBootstrapResponse` loses its `tree` field, which is a wire change, and the frontend's bootstrap flow changes. Example: the browser makes two requests where it makes one today.
- **C) An unclaimed instance claims itself.** The gateway routes `/workspaces` to any unclaimed instance. That instance runs today's sequence and claims its own slot in the same transaction (§4.3). The wire shape and the handler are kept, and no instance calls another. It needs the gateway to route to unclaimed instances and to retry when a just-claimed one refuses. Example: a user creates a workspace and one process handles it, as today.

Recommendation: none. An earlier recommendation of C was withdrawn on 2026-11-13. Option C is the only one that changes neither the wire nor the handler. It works only if the gateway retries a refused bootstrap on a different instance, and that is unverified. The check is one request against two unclaimed pods with the first forced to refuse, on Envoy Gateway at the version the cluster pins. Nothing is ranked until it has run. PLAN-WSS §3.4 currently describes option A's dedicated bootstrap instance.

**DWI-D-2 — whether each instance gets its own database login.**
All instances share one login today, and it can write every workspace's row.

- **A) Keep the shared login and record the exposure.** No work beyond a threat-catalog entry in `register-infra`. Example of what stays possible: an attacker who controls the instance of workspace X writes a key hash of their choosing into workspace Y's row. The resolver then routes that key to Y's instance, and Y's instance accepts it. The attacker reads Y's trees.
- **B) One login per pair, with row-level security.** Row-level security is the Postgres feature that limits which rows a login can see and change. Each login may touch only the `workspaces` and `workspace_trees` rows of the workspace its slot is claimed for, and may claim only its own slot. The example above fails at the database. It costs a migration with the policies, a login created by the controller for every pair, and delivery of its password to the pod.

Recommendation: none. An earlier recommendation of B was withdrawn on 2026-11-13. Separation at the network leaves the database as the one place where instances still meet, and the example under A shows what that permits. Option B works only if the controller can create database logins, and that is unverified. The check is PLAN-WSS's: whether the controller's own login can be granted the privilege to create roles under the cluster's PostgreSQL setup. Nothing is ranked until it has run. The policies and the migration are designed after the ruling.

**DWI-D-3 — what happens to the routing half of `docs/dev/plans/PLAN-NGINX-WORKSPACE-ROUTING.md`.**
That plan is ruled and not implemented. It does two things in the frontend's nginx. It stops the workspace key from being written to the access log. It also routes each workspace to one server instance by hashing the key. In the cluster, PLAN-WSS gives that second job to the resolver. In Docker Compose there is one instance.

- **A) Withdraw the routing half and keep the logging half.** The plan shrinks to the credential-in-logs fix. Example: nginx keeps proxying to one backend address and no longer logs the key.
- **B) Leave the plan as it is.** The hash routing is built and has no deployment that uses it.

Recommendation (mine): A. It is your plan to change, so this plan does not edit it.

## 9. Unanswered placement question

PLAN-WSS ruled that the resolver is written in OCaml and "built in the register repository". The folder `~/projects/register-ocaml/` was created after that ruling, for OCaml projects. This plan does not cover the resolver. Where its source lives decides which repository plans it.

## 10. Phases and verification

Each phase ends with every tier green. The commands are the ones in the `register-dev` skill.

| Phase | Work | Tests added |
|---|---|---|
| 1 | `PoolSlotId`, `WorkspaceTenancy`, the configuration field and its decoder, the startup check | Decoder cases: shared, dedicated with a slot, dedicated without one, shared with one, a malformed slot, dedicated with the in-memory store |
| 2 | Migration `V3__workspace_pool.sql`. `create`, `resolve`, `evictExpired`, `delete` and `rotate` in dedicated tenancy | Store tests against Postgres: a second claim of one slot fails, a foreign key resolves to not found, eviction touches only the own row, release is set on delete and on eviction, a released slot cannot be claimed |
| 3 | The reaper and `deleteWorkspace` skip the Irmin cascade in dedicated tenancy. The refusal of §4.3 gets its `Failure` case | Handler tests for a bootstrap on a claimed instance and on a released one |
| 4 | The ADR-021 section and its placement in the `adr-constraints` skill | none |
| 5 | Under DWI-D-2 option B only: logins and row-level security | Designed after the ruling |

| Command | Expected |
|---|---|
| `sbt 'commonJVM/test; server/test'` | pass |
| `sbt app/test` | pass |
| `sbt "serverIt/test"` | pass |
| `tests/bats/run-suites.sh all` | pass, run at the end of each phase |

Shared tenancy is the default, so every existing test runs unchanged and must stay green without edits.

## 11. Provisional file scope

This table is not the inventory. A table row grants no edit.

| File | Change |
|---|---|
| `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala` | `PoolSlotId` |
| `modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceConfig.scala` | `tenancy` field |
| `modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceTenancy.scala` | new |
| `modules/server/src/main/resources/application.conf` | `workspace.tenancy` |
| `modules/server/src/main/resources/db/migration/V3__workspace_pool.sql` | new |
| `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala` | dedicated tenancy |
| `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceReaper.scala` | cascade skipped in dedicated tenancy |
| `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala` | cascade skipped in dedicated tenancy |
| `modules/server/src/main/scala/com/risquanter/register/Application.scala` | startup check |
| `docs/dev/decision-records/ADR-021-capability-urls.md` | dedicated tenancy section |
| `.claude/skills/adr-constraints/SKILL.md` | placement of the ADR-021 change |
| test files for phases 1 to 3 | named when the inventory is written |

Not touched: the `WorkspaceStore` trait, `WorkspaceStoreLive`, every endpoint definition, every DTO, the frontend.

## 12. Terminology impact

No entry in `docs/user/TERMINOLOGY.md` becomes stale yet. "Workspace" keeps its meaning. The glossary has no entry for instance, pair, slot, pool, claim or tenancy. This plan uses the definitions of §2 and proposes no entries for them until it lands.

Part 9 of the glossary, written 2026-11-13 at the user's request, defines key resolution, routing, request handling, resolver, router and creating a workspace. Its section 9.5 says one server process does all three jobs, and its section 9.6 says the server that receives a creation request does the whole of it. Both stay true for Docker Compose after this plan. Both become incomplete when the cluster of PLAN-WSS runs, because key resolution and routing are then done in front of the servers. The replacement wording for 9.5 and 9.6 is proposed when this plan's design is ruled.

## 13. Discussion record, 2026-11-13

This section records what the user stated and the direction the discussion reached. None of it is a ruling. Each item names what it bears on.

**What Docker Compose is for.** The user stated that the compose setup "was never meant to be production ready or production equvialent. it was meant to test docker packaged services". It checks the UI and runs the integration tests. It gets no per-workspace networks, no pairs and no gateway. Development and its tests run on compose today.

**The requirement that follows.** Creating a workspace is the first step on a clean instance. After separation, one server on compose must still be able to create a workspace and serve it. A design in which creation depends on cluster parts makes compose unusable and forces development onto Kubernetes. The user named these as the two alternatives: a design that keeps compose working, or Kubernetes only. This bears on §4.1, where keeping today's way of running in the image was the assistant's drafting and was never ruled.

**Compose cannot start a pair on demand.** Compose starts the services its file lists. Nothing in it creates a container in response to a request, and no service in `docker-compose.yml` holds the Docker socket. This is a statement about the current setup. Neither plan asks compose to start pairs. WSS-D-15 is therefore a question about the cluster only.

**Three jobs, and where each is done.** Part 9 of `docs/user/TERMINOLOGY.md` defines key resolution, routing and request handling. The direction the user described, and called "heading in the right direction", places them as follows.

| Job | Docker Compose | Kubernetes, PLAN-WSS stage A |
|---|---|---|
| Key resolution | inside the server process | the resolver, asked by Envoy Gateway (WSS-D-1 A, WSS-D-2 B) |
| Routing | inside the server process, to itself | Envoy Gateway |
| Request handling | the server process, for every workspace | one server process per workspace |
| Creating a workspace | the server process, by itself | open: WSS-D-15 and DWI-D-1 |

On compose the in-process routing is a ZIO service wired by a layer at startup. On Kubernetes that service does not start, because Envoy Gateway and the resolver do its job. WSS-D-1 and WSS-D-2 are untouched by this.

**What the direction changes in this draft.** §4.1 places the difference between the two ways of running inside `WorkspaceStorePostgres`, as shared and dedicated tenancy. The direction places it in front of the request handlers, as a routing service that is present or absent. Two differences remain in the server under either placement. On compose it accepts every workspace's key, and on Kubernetes only its own. On compose it creates a workspace by itself, and on Kubernetes creation involves a pair. The re-check of the key by each workspace's server is the assistant's drafting in PLAN-WSS §2 and was not ruled.

**What only Kubernetes can run.** The forwarding path, the per-workspace objects and a server limited to one workspace are run by no test under compose. A Kubernetes development environment is a prerequisite for verifying them. The user stated that the current k3d cluster is under development and that the infra plan's first step tears it down.
