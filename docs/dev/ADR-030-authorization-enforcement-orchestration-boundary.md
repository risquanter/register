# ADR-030: Authorization Enforcement at the Orchestration Boundary

**Status:** Accepted (awaiting implementation)
**Date:** 2026-07-04
**Tags:** authorization, architecture, orchestration, scala3, pep

---

## Context

- An omitted `authz.check()` call silently bypasses SpiceDB — the runtime enforcement never
  runs; no error, no log, no test failure in capability-only or identity modes
- Authorization rules (which permissions apply to which operations) live exclusively in the
  SpiceDB Zed schema; encoding them in Scala types creates a second source of truth that
  diverges silently on schema changes
- HTTP handlers and background orchestrators compose service operations — they own the
  "what should happen and for whom" logic; services below them execute single-concern
  operations without knowledge of the caller's identity
- Compile-time enforcement catches omitted checks at build time; static analysis (SemGrep)
  catches them at CI time; both are complementary — neither replaces the other nor replaces
  SpiceDB's runtime authorization decision
- A compile-time proof that `check()` was called carries no runtime security semantics of its
  own — security is the SpiceDB evaluation; the proof is structural discipline enforced by
  the compiler

---

## Decision

### 1. Orchestration Boundary

HTTP handlers (`serverLogic` for-comprehensions) and background orchestrators (`WorkspaceReaper`
and equivalent) are the **only valid call sites** for `WorkspaceStore` and `RiskTreeService`
methods. Services do not call other services for cross-cutting orchestration; that responsibility
belongs to the handler.

```scala
// ✅ Handler orchestrates — authorization is visible, verifiable, compiler-enforced
val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic {
  case (maybeUserId, key) =>
    (for
      userId <- userCtx.requireAuthenticated(maybeUserId)
      ws     <- workspaceStore.resolve(key)   // exempt: Layer 0 capability gate
      given Checked[Permission] <-
                 authzService.check(userId, Permission.AdminWorkspace, ws.id.asResource)
      //    ↑ specific permission value passed to SpiceDB for runtime decision
      //      binding uses base Checked[Permission] — satisfies all using Checked[Permission] params
      ids    <- workspaceStore.listTrees(key)               // Checked[Permission] in scope ✓
      _      <- riskTreeService.cascadeDeleteTrees(ws.id, ids)
      _      <- workspaceStore.delete(key)
    yield ()).either
}

// ❌ Service calls service — authorization boundary is invisible; check() cannot be verified
class WorkspaceService(store: WorkspaceStore, trees: RiskTreeService):
  def deleteWithTrees(wsId: WorkspaceId, key: WorkspaceKeySecret): IO[AppError, Unit] =
    for
      ids <- store.listTrees(key)               // no check() in scope — silent bypass
      _   <- trees.cascadeDeleteTrees(wsId, ids)
      _   <- store.delete(key)
    yield ()
```

### 2. Authorization at the Boundary

Every call to a service method that reads or mutates workspace or tree data requires
`authz.check()` in scope earlier in the same handler, bound as a `given`. Call sites without
a preceding check must carry an `// exempt:` comment with an approved reason.

Approved exemption reasons:
- `// exempt: pre-resource-creation — no resource exists yet to check`
- `// exempt: Layer 0 capability gate — workspace key validation precedes authorization`
- `// exempt: system maintenance — no user context`
- `// exempt: stateless compute — no workspace resource`

```scala
val bootstrapWorkspace = bootstrapWorkspaceEndpoint.serverLogic { case (xff, req) =>
  (for
    _   <- rateLimiter.checkCreate(ip)
    key <- workspaceStore.create()       // exempt: pre-resource-creation
    ws  <- workspaceStore.resolve(key)   // exempt: Layer 0 capability gate
    given Checked[Permission.Bootstrap.type] <- bootstrapProvisioner.bootstrapToken()
    //    ↑ bootstrap lifecycle token — see Decision 5
    tree <- riskTreeService.create(ws.id, req)    // Checked[Permission] in scope ✓
    _    <- workspaceStore.addTree(key, tree.id)  // Checked[Permission] in scope ✓
  yield ...).either
}
```

### 3. `Checked[P]` Proof Token — Base Permission Type

`authz.check()` returns `IO[AuthError, Checked[P]]`. Service methods that require authorization
declare `(using Checked[Permission])`. A call site lacking a `given Checked[Permission]` in
scope is a **compile error** — the check cannot be silently omitted.

The `using` parameter carries the **base `Permission` type**, not a specific permission
subtype. The specific permission (`Permission.ViewWorkspace`, `Permission.DesignWrite`, etc.)
is passed to `check()` itself — that is what SpiceDB evaluates. Encoding specific permission
requirements in service method signatures would duplicate the SpiceDB schema's permission
hierarchy in Scala, creating a second source of truth for policy (violates ADR-024).

Via covariance (`+P`), any `Checked[P]` where `P <: Permission` satisfies
`using Checked[Permission]`:

```scala
opaque type Checked[+P <: Permission] = Unit

object Checked:
  private[auth] def apply[P <: Permission](): Checked[P] = ()
  // Only constructible inside the auth package — authz.check() is the sole production source

// Service method — base type; any prior check satisfies it
def listTrees(key: WorkspaceKeySecret)(using Checked[Permission]): IO[AppError, List[TreeId]]

// Handler — specific permission value passed to SpiceDB; binds as base Checked[Permission]
// Note: Scala 3 type inference widens to Checked[Permission] when binding given from check();
// the specific permission is in the check() argument, not the binding type.
given Checked[Permission] <-
    authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
ids <- workspaceStore.listTrees(key)   // Checked[Permission] in scope ✓
```

### 4. Erasure Is Correct — Services Are Not Authorization Decision Points

`Checked[P]` is intentionally erased to `Unit` at runtime. The service method performs no
authorization logic — it only demands structural proof that the handler called `check()`.
The runtime authorization decision was already made by SpiceDB inside `authz.check()`.

A service method inspecting the proof value at runtime would be re-implementing authorization
policy inside the service layer — a violation of Decision 1 (orchestration boundary) and
ADR-024 (application as pure PEP). Erasure enforces this: the proof value carries no
inspectable information.

```scala
// ❌ Service inspects proof — re-implements policy, violates PEP boundary
def listTrees(key: WorkspaceKeySecret)(using proof: Checked[Permission]) =
  if proof.asInstanceOf[SomeType] == ... then ...   // impossible and wrong

// ✅ Service ignores proof content — SpiceDB already decided; proof proves wiring only
def listTrees(key: WorkspaceKeySecret)(using Checked[Permission]) =
  repository.fetchTreeIds(key)
```

### 5. Bootstrap Lifecycle Token

`bootstrapWorkspace` creates a new resource — no prior resource exists to check against.
`BootstrapProvisioner.bootstrapToken()` returns `UIO[Checked[Permission.Bootstrap.type]]`,
declaring bootstrap mode without consulting SpiceDB.

`Permission.Bootstrap` is a lifecycle marker, not a SpiceDB permission. It exists in the
`Permission` enum solely to produce a `Checked[Permission]` proof that satisfies the service
method `using` requirement at bootstrap call sites. It is never passed to
`AuthorizationServiceSpiceDB.check()`.

```scala
case Bootstrap extends Permission("__bootstrap__")  // lifecycle marker only; never sent to SpiceDB

// BootstrapProvisionerNoOp and BootstrapProvisionerSpiceDB both:
def bootstrapToken(): UIO[Checked[Permission.Bootstrap.type]] =
  ZIO.succeed(Checked[Permission.Bootstrap.type]())

// Handler binds the proof before create/addTree:
given Checked[Permission.Bootstrap.type] <- bootstrapProvisioner.bootstrapToken()
tree <- riskTreeService.create(ws.id, req)    // Checked[Bootstrap.type] <: Checked[Permission] ✓
_    <- workspaceStore.addTree(key, tree.id)  // same given in scope ✓
```

### 6. Schema Authority — Single Source of Truth

> **Cardinal design decision.** `infra/spicedb/schema.zed` in the `register` repository is
> the **only authoritative source** for the SpiceDB Zed schema. `register-infra` reads this
> file from the `register` checkout when applying the schema — it does not maintain its own
> copy.

The constraint that makes this non-negotiable: `Permission.zedName` values in the Scala
`Permission` enum must exactly match `permission` names in the schema. A rename in one place
without the other produces silent authorization failures at runtime
(`PERMISSIONSHIP_NO_PERMISSION` on every check — no compile error, no log warning).

By keeping the schema co-located with the Scala code that references its permission names:
- A schema rename that doesn't update the Scala enum fails CI immediately (atomic PR)
- Commit signing (ADR-020) covers schema changes automatically
- The `register-infra` CI job applies schema via `zed schema write` from the `register`
  checkout — no separate schema maintenance, no drift

**For `register-infra`:** The provisioning job MUST read `infra/spicedb/schema.zed` from
the `register` repo checkout (git submodule, CI artifact, or shared pipeline step).
Maintaining a separate copy of the schema in `register-infra` is a prohibited pattern —
it creates two sources of truth with no enforcement that they stay in sync.

**Enterprise override** (for deployers with a dedicated policy team):
```hocon
register.authz.provisioning {
  schema-source = "classpath:/spicedb/schema.zed"  # default: in-repo
  # schema-source = "https://policy.internal/schemas/register/v2.zed"  # enterprise override
}
```

---

## Code Smells

### ❌ Service Calling Service

```scala
// BAD: orchestration inside service — authorization cannot be verified at this call site
class WorkspaceOrchestrator(store: WorkspaceStore, trees: RiskTreeService):
  def deleteAll(key: WorkspaceKeySecret): IO[AppError, Unit] =
    for
      ids <- store.listTrees(key)   // who checked? when? no way to know from here
      _   <- trees.cascadeDeleteTrees(wsId, ids)
    yield ()

// GOOD: handler composes service calls with authorization in scope
(for
  given Checked[Permission] <-
      authzService.check(userId, Permission.AdminWorkspace, ws.id.asResource)
  // specific permission passed to SpiceDB; base type bound as given
  ids <- workspaceStore.listTrees(key)
  _   <- riskTreeService.cascadeDeleteTrees(ws.id, ids)
yield ()).either
```

### ❌ Service Call Without Check or Exemption

```scala
// BAD: SpiceDB never consulted — silent authorization bypass
val getTree = getTreeEndpoint.serverLogic { case (maybeUserId, key, treeId) =>
  (for
    result <- riskTreeService.getById(ws.id, treeId)  // no check(), no // exempt:
  yield result).either
}

// GOOD: check() precedes service call; proof in scope
(for
  given Checked[Permission.ViewTree.type] <-
      authzService.check(userId, Permission.ViewTree, treeId.asResource)
  result <- riskTreeService.getById(ws.id, treeId)   // Checked[Permission] in scope ✓
yield result).either
```

### ❌ Specific Permission Type in Service Signature

```scala
// BAD: duplicates SpiceDB policy in Scala — breaks when two permissions are valid callers
def listTrees(key: WorkspaceKeySecret)
    (using Checked[Permission.ViewWorkspace.type]): IO[AppError, List[TreeId]]
// Fails to compile when called after AdminWorkspace check in deleteWorkspace

// GOOD: base type — policy lives in SpiceDB schema only
def listTrees(key: WorkspaceKeySecret)
    (using Checked[Permission]): IO[AppError, List[TreeId]]
```

### ❌ Discarding the Proof

```scala
// BAD: proof discarded with _ — compile error not triggered; service method cannot verify
_ <- authzService.check(userId, Permission.DesignWrite, resource)
_  <- riskTreeService.update(ws.id, treeId, req)  // no given Checked[Permission] in scope

// GOOD: proof bound as given — propagates to all subsequent service calls in the for-comp
given Checked[Permission] <-
    authzService.check(userId, Permission.DesignWrite, resource)
_ <- riskTreeService.update(ws.id, treeId, req)   // Checked[Permission] satisfied ✓
```

### ❌ Using `TestChecked` in Production Code

```scala
// BAD: test infrastructure in production — proof without check(); defeats the invariant
import com.risquanter.register.auth.TestChecked
given Checked[Permission] = TestChecked.value   // test-scope only — never in src/main

// GOOD: proof comes from authz.check() or bootstrapToken() only
given Checked[Permission] <-
    authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
```

---

## Implementation

| Pattern | Location |
|---|---|
| `Checked[P]` opaque type + `Permission.Bootstrap` | `auth/AuthorizationService.scala` |
| `bootstrapToken()` method | `auth/BootstrapProvisioner.scala` |
| `(using Checked[Permission])` on protected methods | `WorkspaceStore`, `RiskTreeService` trait signatures and impls |
| `given Checked[P] <-` bindings | All workspace-scoped controller `serverLogic` for-comprehensions |
| `TestChecked` (test scope, `src/test` only) | `src/test/scala/.../auth/TestChecked.scala` |
| `// exempt:` annotations | `bootstrapWorkspace`, `evictExpired`, `distributionPreview` call sites |
