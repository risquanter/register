# PLAN — Phase D merge cleanup (DD-2 patch + slice-one open items, one pass)

**Status:** decisions RULED by the user 2026-07-24 (see the Decisions
section); document updated to the rulings and awaiting the user's final
approval of the plan as a whole. No source edit is covered until that
approval (G3) and the approval token is refreshed (hook).
**Scope:** one implementation pass that (a) implements the DD-2 decision —
patch `irmin-graphql` so `merge_with_branch` reports conflicts instead of
swallowing them, (b) compares the patched behaviour against the unpatched
`merge_with_commit` path and pins both in regression tests, (c) resolves
every open decision and review finding attached to the uncommitted Phase D
slice-one code, so the whole stack can be committed clean.

Inputs: memory `phase-d-slice1-decisions-and-findings` (D1–D11, F1–F6),
memory `phase-d-merge-design` (upstream status, live probe results),
`docs/dev/VERSION-UPGRADE-PROTOCOL.md` (opam patch policy).

Facts this plan builds on (all live-verified 2026-07-24):

- irmin 3.11.0 is the latest release; upstream `main` has the identical
  `server.ml`. The swallow is one line: the `merge_with_branch` resolver
  discards the merge result (`let* _ = Store.merge_with_branch … in
  Store.Head.find t >|= Result.ok`).
- The sibling resolver `merge_with_commit` propagates conflicts as GraphQL
  errors (`data: null`, error message from `Irmin.Merge.conflict_t`), leaves
  the target head untouched. Its fast-forward-case ancestry proved
  STATE-DEPENDENT at implementation (2026-07-24): on one store it created a
  commit dropping the scenario parent, on a fresh store it fast-forwarded
  correctly — documented in
  `docs/dev/NOTE-irmin-merge-with-commit-ancestry.md`, deliberately not
  pinned in tests (decision: option A). The stable reasons for patching
  `merge_with_branch` rather than switching mutations: the production
  mutation and its upstream-native semantics stay unchanged, no extra
  resolve-head round-trip (and its race), and the patch matches
  `merge_with_commit`'s conflict contract.
- Irmin's conflict error carries no path information — the byte-level
  pre-check remains the only source of the per-path conflict list shown in
  the UI and the 409 body.

---

## Part 1 — DD-2: the irmin-graphql patch

### 1.1 Patch file (NEW `containers/builders/patches/irmin-graphql-3.11.0-merge-conflict.patch`)

Unified diff against the irmin 3.11.0 source root, mirroring the error
handling `merge_with_commit` already has five lines below:

```diff
--- a/src/irmin-graphql/server.ml
+++ b/src/irmin-graphql/server.ml
@@ -811,8 +811,13 @@
           ~resolve:(fun _ _src into from i max_depth n ->
             let* t = mk_branch s into in
             let* info, _, _, _ = txn_args s i in
-            let* _ = Store.merge_with_branch t from ~info ?max_depth ?n in
-            Store.Head.find t >|= Result.ok);
+            Store.merge_with_branch t from ~info ?max_depth ?n >>= function
+            | Ok _ -> Store.Head.find t >|= Result.ok
+            | Error e ->
+                Lwt.return
+                  (Error
+                     ("merge conflict: "
+                     ^ Irmin.Type.to_string Irmin.Merge.conflict_t e)));
```

The `"merge conflict: "` prefix is ours deliberately: it gives the Scala
client a stable discriminator that does not depend on Irmin's conflict
serialization format.

### 1.2 Builder image (`containers/builders/Dockerfile.irmin-builder`)

- `apk add` gains `patch`.
- Before the install step (verbatim commands; exact layer split settled at
  implementation, behaviour as specified):

```dockerfile
# ADR-020 §11 documented exception: irmin-graphql is compiled from locally
# patched source — upstream's merge_with_branch resolver discards the merge
# result, silently swallowing conflicts (present in 3.11.0 and upstream main,
# 2026-07-24). The patch propagates the conflict as a GraphQL error, matching
# merge_with_commit's handling. User-approved 2026-07-24. Source obtained via
# `opam source` (checksum-verified against opam-repository, ADR-020 §2).
COPY patches/irmin-graphql-3.11.0-merge-conflict.patch /home/opam/
RUN opam source irmin-graphql.3.11.0 --dir=/home/opam/irmin-graphql-src && \
    cd /home/opam/irmin-graphql-src && \
    patch -p1 < /home/opam/irmin-graphql-3.11.0-merge-conflict.patch && \
    grep -q '"merge conflict: "' src/irmin-graphql/server.ml && \
    opam pin add -y -n irmin-graphql.3.11.0 /home/opam/irmin-graphql-src
RUN opam install -y \
    irmin-cli.3.11.0 \
    irmin-graphql.3.11.0 \
    irmin-pack.3.11.0
```

The `grep -q` is a build-time assertion that the patch actually applied; a
future version bump where the hunk no longer matches fails the build instead
of silently shipping unpatched behaviour (VERSION-UPGRADE-PROTOCOL, opam
section).

### 1.3 Image tags — see OD-3

If OD-3a (recommended): builder/prod tags become `local/irmin-builder:3.11-p1`
/ `local/irmin-prod:3.11-p1`; every reference moves with them (grep-driven):
`containers/prod/Dockerfile.irmin-prod` (FROM + LABEL), `docker-compose.yml`,
`modules/server-it/…/docker-compose.server-it.yml` (exact filename verified at
implementation), BATS suites, register-dev skill (both copies),
`docs/user/IMAGE-BUILD-REFERENCE.md`.

### 1.4 Scala client: conflict becomes a typed failure (RULED 2026-07-24 — user's hybrid)

The string match happens exactly once, at the lowest level (the wire only
carries text); everything above it is typed. Verified: the Irmin error
hierarchy has exactly ONE exhaustive match in the codebase
(`ErrorResponse.encodeIrminError`), so the new subtype costs one `case` line
there and the compiler locates it.

New subtype in `modules/common/…/domain/errors/AppError.scala` (sibling style):

```scala
/** A merge refused by the (patched) Irmin backend — the two branches hold
  * conflicting values for at least one path. Raised only by
  * `IrminClient.mergeBranch`.
  */
case class IrminMergeConflict(reason: String) extends IrminError {
  override def getMessage: String = reason
}
```

`ErrorResponse.encodeIrminError` gains
`case IrminMergeConflict(reason) => …409 conflict response…` (exact maker
mirrors the existing domain `MergeConflict` mapping; confirmed at
implementation, along with whether `ErrorResponse.decode` needs a
counterpart — in the normal path the service converts before the wire, so
this mapping is a fallback).

`IrminClientLive.mergeBranch` — the `None` arm stops going straight to
`failWithError` and discriminates on our patch's prefix:

```scala
commit   <- response.data.flatMap(_.merge_with_branch) match
              case Some(c) => commitFromData(c)
              case None    =>
                val (messages, path) = collectGraphQl(response.errors)
                messages.find(_.startsWith("merge conflict: ")) match
                  case Some(m) => ZIO.fail(IrminMergeConflict(m.stripPrefix("merge conflict: ")))
                  case None    => ZIO.fail(IrminGraphQLError(messages, path))
```

`ScenarioMergeServiceLive.merge` — replace the post-merge ancestry
verification (the `irmin.lca(BranchRef.Main, scenarioHead)…` line and the
`ZIO.unless(merged)` failure) with a typed catch on the merge call:

```scala
commit <- irmin.mergeBranch(branch, BranchRef.Main, mergeMessage(wsId, name))
            .catchSome { case IrminMergeConflict(_) =>
              ZIO.fail(MergeConflict(
                branch,
                "merge was refused — main changed concurrently and now conflicts; re-run the preview and retry"
              ))
            }
newHead <- ScenarioBranchOps.refineCommitHash(commit.hash)
```

The pre-check stays exactly as is (it is the only path enumerator — see
facts above); its role changes from "authoritative detector compensating a
swallowed error" to "path-level enumerator and fast 409, with the patched
Irmin as the racing-write backstop". No trait signature changes:
`ScenarioMergeService.preview/merge`, `IrminClient.mergeBranch` keep their
exact current signatures.

Scaladoc updates (required, no behaviour): `ScenarioMergeService` trait doc —
swallow paragraph replaced by the patched-contract description;
`IrminClient.mergeBranch` doc — WARNING paragraph replaced by "patched image
raises a GraphQL error prefixed `merge conflict: ` on conflict; unpatched
upstream silently swallows (do not run this service against an unpatched
Irmin)".

### 1.5 Regression tests (user requirement: pin the fix AND the comparison)

`IrminMergeSemanticsSpec` (server-it) — runs against the patched image:

- **INVERT** "a conflicting merge is silently swallowed" →
  "a conflicting merge fails typed — `IrminClient.mergeBranch` fails with
  `IrminMergeConflict` and main's head is unchanged" (exercises the patched
  image AND the client's prefix discrimination in one test; this is the
  regression gate — it fails against an unpatched image or a drifted patch).
- **KEEP** unchanged: disjoint diverged merge produces a two-parent commit;
  equal bytes on both sides merges clean; `getAtCommit` reads at a commit;
  `lca` returns the fork point.
- **ADD** (comparison against the unpatched conflict-surfacing path):
  1. `merge_with_commit` on the same conflict also errors and leaves the
     head untouched — pins that our patch matches the upstream sibling's
     contract.
  2. fast-forward case (main unmoved since fork): `merge_with_branch`
     fast-forwards (new main head == scenario head). AMENDED at
     implementation (option A, 2026-07-24): the originally planned
     counter-assertion on `merge_with_commit` was removed — its
     fast-forward ancestry is state-dependent (see
     `docs/dev/NOTE-irmin-merge-with-commit-ancestry.md`) and pinning it
     makes the suite flaky. (The raw-GraphQL `merge_with_commit` helper
     lives in the spec — no `IrminClient` surface for a mutation production
     code never calls.)

New unit spec `modules/server/src/test/…/services/ScenarioMergeServiceSpec.scala`:
FakeIrminClient whose `mergeBranch` fails with `IrminMergeConflict("…")`
→ `merge` fails domain `MergeConflict`; a plain `IrminGraphQLError` passes
through unmapped.

---

## Part 2 — slice-one open items resolved in the same pass

- **F2 (SHOULD-FIX, single-owner paths):** extract the workspace storage-path
  construction into one owner used by both `RiskTreeRepositoryIrmin` and
  `ScenarioMergeServiceLive`:

  ```scala
  private[register] object WorkspaceStoragePaths:
    def workspaceRoot(wsId: WorkspaceId): String
    def treesRoot(wsId: WorkspaceId): String
    def treeMeta(wsId: WorkspaceId, treeId: TreeId): String
    def treeNodes(wsId: WorkspaceId, treeId: TreeId): String
    def node(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId): String
  ```

  Exact member set is finalized against `RiskTreeRepositoryIrmin`'s current
  inline strings at implementation; the constraint is: after this change
  `grep -rn "workspaces/" modules/server/src/main` hits exactly one file.
  Location: `infra/irmin/` (the repository's package), service imports it.
- **F5 (test gap):** add one HTTP-level test in `ScenarioControllerSpec`
  exercising `toPreviewResponse` over a stubbed conflict result (status,
  entry mapping incl. `treeId`/`nodeId` passthrough).
- **F1:** per OD-5a (recommended): `MergeConflictPath.path` stays `String`
  (display-only, never re-enters a write path) — recorded as accepted, not
  changed.
- **F3, F6:** unchanged; already tracked (Phase E filter; API6 backlog).
- **F4 (scan efficiency):** per OD-9a (recommended): deferred to the
  post-0.7.0 backlog (already listed there).
- **D1, D3, D5, D6, D7, D8, D9, D10, D11:** ratified as implemented unless
  the user objects at plan review (see Ratifications).
- **Docs:** `docs/dev/ADR-020-supply-chain-security.md` §12 opam row gains
  "irmin-graphql carried with a local source patch (see
  VERSION-UPGRADE-PROTOCOL, opam section)";
  `docs/dev/VERSION-UPGRADE-PROTOCOL.md` — both "implementation pending"
  qualifiers become "implemented"; memory files updated on landing.

---

## Decisions — RULED by the user 2026-07-24

- **OD-2 — conflict-error typing: user's hybrid.** String-match exactly once
  in `IrminClientLive.mergeBranch` on the patch's `"merge conflict: "`
  prefix, raise the new typed `IrminMergeConflict` there; the service and
  everything above are compiler-checked (§1.4). The subtype's only
  exhaustive-match cost is one `case` in `ErrorResponse.encodeIrminError`
  (verified — the sole exhaustive match over `IrminError`).
- **OD-3 — image tags: A.** Retag `3.11-p1` (builder + prod), reference
  sweep per §1.3.
- **OD-4 — ancestry check: A (remove), with the guard in the test stack.**
  The protection against accidental patch loss is maximal and automated, not
  runtime: (1) the Dockerfile `grep -q` build assertion (§1.2) fails the
  image build if the patch did not apply; (2) the inverted
  `IrminMergeSemanticsSpec` conflict test (§1.5) fails `serverIt` against
  any unpatched/drifted image; (3) BATS suite B exercises the built
  production image. All three run on every Irmin image change.
- **OD-6 — F2 extraction: A, sequenced LAST as its own commit.** The main
  pass (patch + tests + service change + F5 + docs) lands first with an
  explicit commit; the single-owner path extraction follows as a separate
  review-catch-style cleanup commit. The phase is closed only when this
  second commit is included.
- **Versioning: PATCH.** The user classified this pass as patch-alignment
  work → **0.7.0 → 0.7.1** (autonomous), bumped once, with the final commit.

### Ratifications (accepted as implemented unless you object here)

D1 endpoint shapes (`GET …/merge-preview`, `POST …/merge`, status-string
DTOs); D3 `IrminClient.getAtCommit`; D5 permissions (preview=ViewWorkspace,
merge=DesignWrite); D6 missing-scenario split (preview status vs merge 400);
D7 scenario kept after merge + Design switches to main; D8 `ScenarioBranchOps`
extraction; D9 widened constructor/param signatures (controller env,
`BranchBar.toolbar` `onMergeRequest`, `DesignView` `mergeState`); D10 modal
UI shape with raw paths (names come with slice two); D11 the semantics-spec
assertion inversion (re-inverted by this plan's §1.5); F1/F3/F6 as noted.

---

## File inventory

Infra/build:
- NEW `containers/builders/patches/irmin-graphql-3.11.0-merge-conflict.patch`
- `containers/builders/Dockerfile.irmin-builder` (apk `patch`, source+pin block, §11 comment)
- `containers/prod/Dockerfile.irmin-prod` (FROM/LABEL; tag per OD-3)
- OD-3a only: `docker-compose.yml`, server-it compose file, BATS suites,
  register-dev skill (`.github` + `.claude` mirror), `docs/user/IMAGE-BUILD-REFERENCE.md`

Server (hook-gated `modules/**`; full repo-relative paths — the plan-bound
hook matches edited files against this inventory) — commit 1:
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala` (`IrminMergeConflict`)
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala` (one `case` in `encodeIrminError`; decode counterpart checked at implementation)
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala` (mergeBranch prefix discrimination per §1.4)
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala` (mergeBranch scaladoc only)
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala` (typed catch, ancestry removal, scaladoc)
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminMergeSemanticsSpec.scala` (invert + 2 additions)
- NEW `modules/server/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/ScenarioControllerSpec.scala` (F5 test)
- `modules/server-it/src/test/scala/com/risquanter/register/testcontainers/IrminCompose.scala` (comment: image tag `3.11-p1`)

Server — commit 2 (F2 extraction, separate final cleanup commit):
- NEW `modules/server/src/main/scala/com/risquanter/register/infra/irmin/WorkspaceStoragePaths.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala` (inline paths → shared object)
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala` (workspaceRoot/pathsOn switch to the shared object)
- `build.sbt` + `.env` + `.env.irmin` (version bump 0.7.1, with commit 2)

Docs: ADR-020 §12 row note; VERSION-UPGRADE-PROTOCOL status qualifiers.

Not touched: `ScenarioEndpoints`, DTOs, controller routes, all frontend files
(D1/D10 ratified as-is), `build.sbt` (no dependency changes — the patch is
container-side).

## ADR alignment

- ADR-020: §1 version pin unchanged (still 3.11.0, now pinned to patched
  source); §2 satisfied via `opam source` checksum verification; §11
  exception comment at the pin site (§1.2); §12 row updated. Cooldown does
  not apply (no version change).
- ADR-026: builder → prod build order preserved; builder rebuild required.
- ADR-032: byte-level storage relation of the pre-check unchanged.
- ADR-017/-030: no endpoint or authorization changes.
- Compile hygiene: the new `IrminMergeConflict` subtype triggers the
  inexhaustive-match compile guard at exactly one site
  (`ErrorResponse.encodeIrminError`) — the compiler proves the mapping
  complete.
- DD-5/DD-16: unchanged.

## Verification plan (report pass/fail only)

1. `docker build -f containers/builders/Dockerfile.irmin-builder -t local/irmin-builder:<tag per OD-3> containers/builders/`
   — the in-Dockerfile `grep -q` asserts the patch applied.
2. `docker build -f containers/prod/Dockerfile.irmin-prod -t local/irmin-prod:<tag> containers/prod/`
3. `sbt "serverIt/testOnly *IrminMergeSemanticsSpec"` — the inverted conflict
   test is the patch's regression gate; then full `sbt serverIt/test`.
4. `sbt 'commonJVM/test; server/test'` and `sbt app/test`.
5. BATS suite B (Irmin image changed) then suite A.
6. Manual UI smoke (docker compose, use case A): conflicting scenario shows
   the path list with Merge disabled; clean scenario merges and switches to
   main.

## Versioning

**RULED: PATCH bump 0.7.0 → 0.7.1** (the user classified this pass as
patch-alignment work). Autonomous; bumped once with the final commit;
`APP_VERSION` mirrored to `.env` and `.env.irmin`.

## Implementation order (one shot, two commits)

1. Patch file + builder Dockerfile + image builds with `3.11-p1` tags +
   reference sweep (verification 1–2).
2. `IrminMergeConflict` + `ErrorResponse` case + `IrminClientLive`
   discrimination; semantics spec inversion + the two comparison additions;
   run against the new image (red→green on the patched behaviour).
3. Service change (typed catch, ancestry removal), unit spec, F5 test,
   scaladocs.
4. Full verification 3–6, docs/status updates, memory update →
   **commit 1** (message proposed at landing).
5. F2 single-owner path extraction (`WorkspaceStoragePaths`), grep check
   ("`workspaces/` prefix built in exactly one server file"), re-run unit +
   integration tests, version bump 0.7.1 → **commit 2** (separate
   review-catch-style cleanup; the phase closes only with this included).
