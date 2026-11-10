package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import com.risquanter.register.infra.irmin.model.{IrminCommit, IrminPath}
import com.risquanter.register.domain.errors.{Failure, IrminError, IrminMergeConflict}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, WorkspaceId}
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/**
  * Integration tests pinning the Irmin three-way merge semantics that the
  * scenario merge service (ADR-032) builds on. Irmin's `merge_with_branch` and
  * `merge_with_commit` both run `Head.merge`, which fast-forwards when the
  * target's head is the common ancestor and otherwise writes a merge commit
  * whose parents are both heads; they differ only in how they find the source.
  * These are the load-bearing facts, each pinned by one test:
  *
  *  1. Paths edited on only one side since the fork merge cleanly (per-path
  *     auto-resolution, ADR-007-appendix §4).
  *  2. The same path edited to DIFFERENT bytes on both sides is refused as
  *     `IrminMergeConflict` — the patched-image contract (regression gate
  *     for the local irmin-graphql patch) — leaving main untouched.
  *  3. A merge into an unmoved main fast-forwards to the merged commit, and
  *     still does after a refused merge.
  *  4. The same path edited to IDENTICAL bytes on both sides merges cleanly —
  *     the assumption behind resolving conflicts as ordinary edits that bring
  *     the branches into byte agreement before a native merge.
  *  5. `getAtCommit` reads a path's value as of a given commit (merge-base
  *     reads for the conflict pre-check).
  *  6. A clean merge commit records both parent heads (true merge ancestry,
  *     needed by the history view).
  *
  * Run: sbt "serverIt/testOnly *IrminMergeSemanticsSpec"
  */
object IrminMergeSemanticsSpec extends ZIOSpecDefault:

  private val testLayer: ZLayer[Any, Throwable, IrminClient] =
    IrminCompose.irminConfigLayer >>> IrminClientLive.layer

  private val itWorkspace = WorkspaceId(safeId("irmin-merge-semantics-it"))

  /** A commit message of this server's own kind, distinct per `label`. */
  private def msg(label: String): CommitMessage =
    CommitMessage.TreeWritten(itWorkspace, treeId(label), TreeWriteKind.Update)

  private def uniquePath(base: String): ZIO[Any, Nothing, IrminPath] =
    ZIO.clockWith(_.instant).map { instant =>
      IrminPath.unsafeFrom(s"test/merge/$base/${instant.toEpochMilli}")
    }

  /** Write an initial value on main, fork a fresh branch at that commit.
    * Returns the branch and the fork-point commit (the merge base).
    */
  private def forkAt(path: IrminPath, baseValue: String): ZIO[IrminClient, IrminError | Failure, (BranchRef, CommitHash)] =
    for
      branch   <- freshBranch
      baseHead <- IrminClient.set(path, baseValue, msg("base write"))
      base      = baseHead.hash
      _        <- IrminClient.createBranchAt(branch, base)
    yield (branch, base)

  /** Edit `path` on a fresh fork of main and merge that edit back while main
    * has not moved, returning the edit's commit and the merge's result. */
  private def fastForward(label: String): ZIO[IrminClient, IrminError | Failure, (IrminCommit, IrminCommit)] =
    for
      path        <- uniquePath(label)
      (branch, _) <- forkAt(path, "\"v0\"")
      edit        <- IrminClient.set(path, "\"scenario-only\"", msg("scenario edit"), branch)
      merged      <- IrminClient.mergeCommit(edit.hash, BranchRef.Main, msg("merge ff"))
    yield (edit, merged)

  override def spec = suite("IrminMergeSemanticsSpec")(

    test("disjoint edits since the fork merge cleanly; merge commit has both parents") {
      for
        pathA        <- uniquePath("disjoint-a")
        pathB        <- uniquePath("disjoint-b")
        (branch, _)  <- forkAt(pathA, "\"a0\"")
        _            <- IrminClient.set(pathB, "\"b0\"", msg("base write b"))
        branchHead   <- IrminClient.set(pathA, "\"a-scenario\"", msg("scenario edits a"), branch)
        mainHead     <- IrminClient.set(pathB, "\"b-main\"", msg("main edits b"))
        mergeCommit  <- IrminClient.mergeCommit(branchHead.hash, BranchRef.Main, msg("merge disjoint"))
        a            <- IrminClient.get(pathA)
        b            <- IrminClient.get(pathB)
      yield assertTrue(
        a.contains("\"a-scenario\""),
        b.contains("\"b-main\""),
        mergeCommit.parents.toSet == Set(branchHead.hash, mainHead.hash)
      )
    },

    test("a conflicting merge fails typed (IrminMergeConflict) and leaves main untouched") {
      // Regression gate for the local irmin-graphql patch
      // (containers/builders/patches/): upstream's `merge_with_commit`
      // resolver reports a conflict as an unprefixed GraphQL error; the
      // patched image prefixes it with "merge conflict: ", which
      // IrminClientLive raises as IrminMergeConflict. This test failing with an
      // `Unanswered` at the store means the running image is unpatched or the patch
      // no longer applies — do not weaken it; rebuild the image
      // (VERSION-UPGRADE-PROTOCOL, opam section).
      for
        path        <- uniquePath("conflict")
        (branch, _) <- forkAt(path, "\"v0\"")
        scenHead    <- IrminClient.set(path, "\"scenario-value\"", msg("scenario edit"), branch)
        mainHead    <- IrminClient.set(path, "\"main-value\"", msg("main edit"))
        result      <- IrminClient.mergeCommit(scenHead.hash, BranchRef.Main, msg("merge conflicting")).either
        headAfter   <- IrminClient.mainBranch.map(_.flatMap(_.head))
        onMain      <- IrminClient.get(path)
      yield assertTrue(
        result match
          case Left(IrminMergeConflict(_)) => true
          case _                           => false,
        headAfter.map(_.hash).contains(mainHead.hash),
        onMain.contains("\"main-value\"")
      )
    },

    test("a merge into an unmoved main fast-forwards to the merged commit") {
      for
        (edit, merged) <- fastForward("ff-commit")
      yield assertTrue(merged.hash == edit.hash)
    },

    test("a merge into an unmoved main still fast-forwards after a refused merge") {
      for
        path        <- uniquePath("ff-after-refusal")
        (branch, _) <- forkAt(path, "\"v0\"")
        scenHead    <- IrminClient.set(path, "\"scenario-value\"", msg("scenario edit"), branch)
        _           <- IrminClient.set(path, "\"main-value\"", msg("main edit"))
        refused     <- IrminClient.mergeCommit(scenHead.hash, BranchRef.Main, msg("merge conflicting")).either
        (edit, merged) <- fastForward("ff-after-refusal-edit")
      yield assertTrue(refused.isLeft, merged.hash == edit.hash)
    },

    test("same path edited to identical bytes on both sides merges cleanly") {
      for
        path        <- uniquePath("equal-bytes")
        (branch, _) <- forkAt(path, "\"v0\"")
        scenHead    <- IrminClient.set(path, "\"agreed\"", msg("scenario edit"), branch)
        _           <- IrminClient.set(path, "\"agreed\"", msg("main edit to same bytes"))
        _           <- IrminClient.mergeCommit(scenHead.hash, BranchRef.Main, msg("merge equal bytes"))
        onMain      <- IrminClient.get(path)
      yield assertTrue(onMain.contains("\"agreed\""))
    },

    test("getAtCommit reads the value as of the given commit; absent path is None") {
      for
        path         <- uniquePath("at-commit")
        otherPath    <- uniquePath("at-commit-absent")
        (branch, base) <- forkAt(path, "\"base-value\"")
        _            <- IrminClient.set(path, "\"changed-later\"", msg("later edit"), branch)
        atBase       <- IrminClient.getAtCommit(base, path)
        absent       <- IrminClient.getAtCommit(base, otherPath)
        current      <- IrminClient.get(path, branch)
      yield assertTrue(
        atBase.contains("\"base-value\""),
        absent.isEmpty,
        current.contains("\"changed-later\"")
      )
    },

    test("lca of main and the scenario head is the fork-point commit") {
      for
        path           <- uniquePath("lca-forkpoint")
        (branch, base) <- forkAt(path, "\"v0\"")
        branchHead     <- IrminClient.set(path, "\"diverged\"", msg("scenario edit"), branch)
        _              <- IrminClient.set(path, "\"main-moved\"", msg("main edit"))
        lcas           <- IrminClient.lca(BranchRef.Main, branchHead.hash)
      yield assertTrue(lcas.map(_.hash) == List(base))
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // ---- helpers (same conventions as IrminClientIntegrationSpec) ----

  private def freshBranch: ZIO[Any, Nothing, BranchRef] =
    Random.nextIntBetween(0, 1000000).flatMap { salt =>
      ZIO.clockWith(_.instant).map(i => unsafeBranch(s"scenarios.merge.t${i.toEpochMilli}x$salt"))
    }

  private def unsafeBranch(s: String): BranchRef =
    BranchRef.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)
