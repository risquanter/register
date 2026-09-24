# ADR-036: Confidential Internal Identifiers

**Status:** Accepted  
**Date:** 2026-08-30  
**Tags:** security, tenancy, identifiers, bola

---

## Context

- Some server-side identifiers scope data (storage paths, branch names, authorization lookups) but are not bearer credentials — possessing one grants nothing; it only names a resource
- A guessable identifier (a ULID encodes a timestamp; it is not `SecureRandom`) is safe only while it never crosses the client boundary — its safety comes from absence, not from entropy it was never built to provide
- An endpoint that accepts such an identifier is an enumeration oracle even when it never echoes the value back: any observable difference between "belongs to someone else" and "does not exist" (status code, error shape, timing) lets an attacker script through the identifier space
- A value that embeds a confidential identifier (a branch name built from a workspace id) inherits the same exposure as the identifier itself
- This is a lighter confinement than credential hardening (ADR-022): the identifier is fine in server logs and internal paths; only the client boundary is closed, in both directions

---

## Decision

### 1. Confinement Rule — Never Accepted as Input, Never Returned as Output

A confidential internal identifier must not cross the client boundary in
**either** direction. `WorkspaceId` is the reference: a ULID that scopes
storage paths and authorization, never a credential.

- **Output:** never in a JSON response body, header, error message, or any
  other client-visible surface.
- **Input:** no endpoint accepts it as a path segment, query parameter, header,
  or body field — not even one that also checks a capability. The correct
  pattern is what every workspace endpoint already does: accept
  `WorkspaceKeySecret`, derive `WorkspaceId` server-side via
  `WorkspaceStore.resolve`.

```scala
// GOOD: capability in, internal id derived server-side, never on the wire
def listTrees(key: WorkspaceKeySecret)(using Checked[Permission]): IO[AppError, List[TreeId]] =
  workspaceStore.resolve(key).flatMap(ws => repo.listTrees(ws.id))
```

### 2. Embedding Values Inherit the Rule

A value that contains a confidential identifier is confined as if it were the
identifier. `BranchRef` embeds `WorkspaceId` in its scenario branch name, so a
client-facing type carries a safe substitute instead — made correct by
construction, not scrubbed after the fact:

```scala
// A conflicting path is reported workspace-relative — the WorkspaceId never appears
final case class MergeConflictPath(path: String, treeId: Option[TreeId], nodeId: Option[NodeId])

// A wire-facing failure names the scenario (client-safe), not the BranchRef
ZIO.fail(MergeConflict(name, s"${conflicts.size} conflicting path(s)"))
```

An internal storage error raised where only the `BranchRef` is in scope stays
branch-typed; it is caught and translated by its service-layer caller before
anything reaches the wire, and the encoder's branch-free fallback is the backup
for the path where that translation is skipped.

### 3. Lighter Than a Credential

The credential checklist (ADR-022 R1–R8) does **not** apply. A confidential
identifier may appear in server logs, internal storage paths, and merge commit
messages. The reasoning that closes it at the boundary is enumeration-oracle /
BOLA (Broken Object-Level Authorization), not secret-leakage:

- Authorization here is capability-based — a valid `WorkspaceKeySecret` grants
  access, never knowledge of a `WorkspaceId`. Nothing returns the id, so an
  attacker has no other workspace's id to target with.
- Exposing the id in even one legitimate-looking place (an audit log surfaced to
  a client, a "list my workspaces" field, telemetry) hands a future feature the
  missing ingredient to target another tenant. Unexposed data cannot be
  repurposed by a feature not yet designed; exposed data can.

A client-facing feature that needs a stable per-workspace reference exposes
something with no server-side scoping power — `WorkspaceKeySecret` itself
(already how the bootstrap and rotate responses work), never the raw
`WorkspaceId`. Any new response field, log line, or header that would return a
`WorkspaceId` to a client is a Decision Trigger — stop and ask.

### 4. Identifiers Not Confined by This Record

`TreeId`, `NodeId` and `MitigationId` are **client-facing**. They are returned in
responses, accepted as path segments and body fields, and none of that is a
violation of this record. This section states why, so the question is not
re-derived each time one of them appears on the wire.

**The property that decides it.** Confinement is warranted only when presenting
the identifier makes the server widen what it looks at. An identifier that
merely *names* something inside a set the caller already earned the right to
read has no scoping power of its own, and hiding it buys nothing.

`WorkspaceId` has that power. `WorkspaceStore.resolveById` looks one up in a
store spanning every workspace, with no accompanying capability check. A
`WorkspaceId` in a client's hands would therefore be an ingredient a future
feature could turn into cross-tenant access, which is what §1 forecloses.

A node id has no such power, and the reason is structural rather than a
convention anyone has to remember. Every lookup of a client-supplied node id
runs against `tree.index.nodes`, a `Map[NodeId, RiskNode]` belonging to one
`RiskTree` object. That object is only ever obtained after the caller's
`WorkspaceKeySecret` resolved to their own workspace and the requested `TreeId`
passed `ws.trees.contains(treeId)` in `WorkspaceStore.resolveTreeWorkspace`. No
code path anywhere takes a bare node id and searches across trees or across
workspaces for it.

**Worked through.** Suppose an attacker holds a valid `WorkspaceKeySecret` for
workspace A and has somehow obtained a real node id from workspace B's tree.

- If they name B's tree as well, `resolveTreeWorkspace` fails first: B's
  `TreeId` is not in A's tree set, and the request is refused before any node is
  looked at.
- If they name their own tree — the only tree they are allowed to name — the
  lookup `tree.index.nodes.get(nodeId)` runs against A's node map, which does
  not contain B's node id, and returns nothing.

The second case is not merely mapped to the same response as a node id that was
never issued to anyone; it *is* the same event, the same `None` from the same
lookup. There is no branch distinguishing them, so there is no status code,
response shape, or timing difference to enumerate against.

The same argument covers `TreeId`, which is looked up only inside the caller's
own resolved workspace record, and `MitigationId`, which is looked up only
inside an already-loaded tree's own mitigation list.

**The second half of the test.** Confinement is also cheap only where the
identifier has no legitimate client use. `WorkspaceId` has none — §1's point is
that no feature needs to return it. `TreeId`, `NodeId` and `MitigationId` are
the everyday way a client names what it is working on: a node id appears in
every tree read and every analysis response, and the interface cannot be built
without it. There is no unexposed state here to protect.

---

## Code Smells

### ❌ Accepting a Confidential Identifier as Input

```scala
// BAD: endpoint takes the internal id directly — an enumeration oracle
val ep = endpoint.get.in("workspaces" / path[WorkspaceId]("wsId") / "risk-trees")

// GOOD: capability in, id derived server-side
val ep = endpoint.get.in("w" / path[WorkspaceKeySecret]("key") / "risk-trees")
```

### ❌ Returning a Confidential Identifier

```scala
// BAD: response body leaks the internal scoping id
final case class TreeResponse(treeId: TreeId, workspaceId: WorkspaceId, ...)

// GOOD: nothing scoping-capable on the wire
final case class TreeResponse(treeId: TreeId, ...)
```

### ❌ Client-Facing Error Carrying an Embedding Value

```scala
// BAD: the wire-facing error carries a BranchRef, which embeds the WorkspaceId
ZIO.fail(MergeConflict(branch, s"conflict on ${branch.value}"))

// GOOD: carry the client-safe substitute (the scenario name)
ZIO.fail(MergeConflict(name, s"${conflicts.size} conflicting path(s)"))
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| Workspace endpoints | Accept `WorkspaceKeySecret`; derive `WorkspaceId` via `WorkspaceStore.resolve` |
| `MergeConflictPath` | Workspace-relative path; the `WorkspaceId` is parsed out, never carried |
| Wire-facing errors (`MergeConflict`, translated `DataConflict`) | Carry a `ScenarioName` substitute, not a `BranchRef` |
| `TreeId`, `NodeId`, `MitigationId` | Client-facing; each is resolved only inside a container the caller is already authorized for (§4) |

---

## References

- [ADR-024: Externalized Authorization](./ADR-024-externalized-authorization-pep-pattern.md) — capability-based access; possessing a key grants access, not knowledge of an id
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — `WorkspaceKeySecret` is the bearer capability. Its §1 phrase "`TreeId` remains internal" means `TreeId` is never the credential, not that it never appears on the wire: ADR-021 §3's own endpoint design puts `{treeId}` in the path beside the key. See §4 above
- [ADR-035: Error Leakage Prevention](./ADR-035-error-leakage-prevention.md) — a confidential identifier is one of the things an error message must not carry
