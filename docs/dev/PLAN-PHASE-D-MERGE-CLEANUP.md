# PLAN — Phase D merge cleanup (DD-2 patch + slice-one open items, one pass)

**Status:** awaiting user approval (this document is the G3 plan; no source
edit is covered until it is approved and the open decisions below are ruled).
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
  the target head untouched — but in the fast-forward case (main unmoved
  since fork) it creates a single-parent commit that does NOT record the
  scenario head as a parent, while `merge_with_branch` fast-forwards
  correctly. This is why we patch `merge_with_branch` rather than switch
  mutations.
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

### 1.4 Scala client: conflict becomes a typed failure

Current behaviour after the patch, with NO Scala change: the mutation returns
`merge_with_branch: null` + `errors`, so `IrminClientLive.mergeBranch` already
fails with `IrminGraphQLError(messages, path)` instead of returning a bogus
commit. The change needed is discrimination, per OD-2a (recommended):

`ScenarioMergeServiceLive.merge` — replace the post-merge ancestry
verification (lines with `irmin.lca(BranchRef.Main, scenarioHead)…` and the
`ZIO.unless(merged)` failure) with a catch on the merge call:

```scala
commit <- irmin.mergeBranch(branch, BranchRef.Main, mergeMessage(wsId, name))
            .catchSome {
              case e: IrminGraphQLError if e.messages.exists(_.startsWith("merge conflict: ")) =>
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
  "a conflicting `merge_with_branch` fails with a `merge conflict: ` GraphQL
  error and main's head is unchanged" (the regression gate for the patch;
  this test failing = unpatched image or patch drifted).
- **KEEP** unchanged: disjoint diverged merge produces a two-parent commit;
  equal bytes on both sides merges clean; `getAtCommit` reads at a commit;
  `lca` returns the fork point.
- **ADD** (comparison against the unpatched conflict-surfacing path):
  1. `merge_with_commit` on the same conflict also errors and leaves the
     head untouched — pins that our patch matches the upstream sibling's
     contract.
  2. fast-forward case (main unmoved since fork): `merge_with_branch`
     fast-forwards (new main head == scenario head); `merge_with_commit`
     produces a commit that does NOT carry the scenario head as a parent —
     pins the reason we patch `merge_with_branch` instead of switching
     mutations. (Requires a raw-GraphQL escape hatch for `merge_with_commit`
     in the spec — the spec already speaks GraphQL directly via the harness;
     no `IrminClient` surface is added for a mutation production code never
     calls.)

New unit spec `modules/server/src/test/…/services/ScenarioMergeServiceSpec.scala`:
FakeIrminClient whose `mergeBranch` fails with
`IrminGraphQLError(List("merge conflict: …"), Some(List("merge_with_branch")))`
→ `merge` fails `MergeConflict`; a non-conflict `IrminGraphQLError` passes
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

## Open decisions (rule these at plan approval)

- **OD-2 — conflict-error typing.**
  a) *(recommended)* No new error type: `ScenarioMergeServiceLive` catches
  `IrminGraphQLError` whose message starts with our patch's
  `"merge conflict: "` prefix (§1.4). Cost: string-prefix contract between
  the patch and the service (both ours, pinned by the unit spec).
  b) New `case class IrminMergeConflict(reason: String) extends IrminError` —
  fully typed, but a new `AppError` subtype ripples through every exhaustive
  `IrminError`/`AppError` match in the codebase (compile-error sweep).
- **OD-3 — patched image tags.**
  a) *(recommended)* Retag `3.11-p1` (builder + prod): the tag states the
  content is not pristine upstream; cost: reference sweep (§1.3).
  b) Keep `3.11`: zero churn; the tag silently means "patched" from now on
  (recorded only in LABEL + Dockerfile comment).
- **OD-4 — post-merge ancestry check.**
  a) *(recommended)* Remove it (§1.4): with conflicts surfaced, a refused
  merge is a typed failure and a succeeded merge advanced main — the check's
  only remaining trigger is the race, which the catch now handles at the
  same point. Cost: none identified; the `lca` round-trip disappears.
  b) Keep both: redundant second line of defence against an unpatched image
  being deployed; cost: one extra `lca` call per merge and a misleading
  implication that the swallow still exists.
- **OD-6 — F2 timing.**
  a) *(recommended)* Do the single-owner path extraction in this pass (it
  touches the same files and the commit is already a cleanup).
  b) Defer to backlog; slice lands with the duplication.

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

Server (hook-gated `modules/**`):
- `services/ScenarioMergeService.scala` (merge catch per §1.4, ancestry
  removal per OD-4, scaladoc; paths via `WorkspaceStoragePaths` per OD-6)
- `infra/irmin/IrminClient.scala` (mergeBranch scaladoc only)
- NEW `infra/irmin/WorkspaceStoragePaths.scala` (OD-6a)
- `repositories/RiskTreeRepositoryIrmin.scala` (OD-6a: inline paths → shared object; exact path verified at implementation)
- Tests: `server-it …/IrminMergeSemanticsSpec.scala` (invert + 2 additions),
  NEW `server …/services/ScenarioMergeServiceSpec.scala`,
  `server …/http/controllers/ScenarioControllerSpec.scala` (F5 test)

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
- Compile hygiene: OD-2a adds no `AppError` subtype, so no exhaustive-match
  ripple; OD-2b would require the sweep.
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

Landing this pass closes Phase D slice one at feature level → **MINOR bump
0.7.0 → 0.8.0** (autonomous under the 2026-07-24 policy), mirrored to `.env`
and `.env.irmin` — unless the user's 0.7.0 close-out sequencing dictates
otherwise; flagging because 0.7.0 was reserved for the user-owned Phase C
close-out and this would be the first version past it.

## Implementation order (one shot)

1. Patch file + builder Dockerfile + builds (verification 1–2).
2. Semantics spec inversion + additions; run against the new image (red→green
   on the patched behaviour).
3. Service change (catch, ancestry removal), unit spec, F2 extraction,
   F5 test, scaladocs.
4. Full verification 3–6, docs/status updates, version bump, memory update,
   commit (message proposed at landing).
