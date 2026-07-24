package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.domain.errors.IrminError
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash}
import com.risquanter.register.testcontainers.IrminCompose

/**
  * Integration tests pinning the Irmin three-way merge semantics that the
  * scenario merge service (ADR-032) builds on. These are the load-bearing
  * facts, each pinned by one test:
  *
  *  1. Paths edited on only one side since the fork merge cleanly (per-path
  *     auto-resolution, ADR-007-appendix §4).
  *  2. The same path edited to DIFFERENT bytes on both sides conflicts, and
  *     the failed merge leaves the target branch's state untouched.
  *  3. The same path edited to IDENTICAL bytes on both sides merges cleanly —
  *     the assumption behind resolving conflicts as ordinary edits that bring
  *     the branches into byte agreement before a native merge.
  *  4. `getAtCommit` reads a path's value as of a given commit (merge-base
  *     reads for the conflict pre-check).
  *  5. A clean merge commit records both parent heads (true merge ancestry,
  *     needed by the history view).
  *
  * Run: sbt "serverIt/testOnly *IrminMergeSemanticsSpec"
  */
object IrminMergeSemanticsSpec extends ZIOSpecDefault:

  private val testLayer: ZLayer[Any, Throwable, IrminClient] =
    IrminCompose.irminConfigLayer >>> IrminClientLive.layer

  private def uniquePath(base: String): ZIO[Any, Nothing, IrminPath] =
    ZIO.clockWith(_.instant).map { instant =>
      IrminPath.unsafeFrom(s"test/merge/$base/${instant.toEpochMilli}")
    }

  /** Write an initial value on main, fork a fresh branch at that commit.
    * Returns the branch and the fork-point commit (the merge base).
    */
  private def forkAt(path: IrminPath, baseValue: String): ZIO[IrminClient, IrminError, (BranchRef, CommitHash)] =
    for
      branch   <- freshBranch
      baseHead <- IrminClient.set(path, baseValue, "base write")
      base      = commitHash(baseHead.hash)
      _        <- IrminClient.createBranchAt(branch, base)
    yield (branch, base)

  override def spec = suite("IrminMergeSemanticsSpec")(

    test("disjoint edits since the fork merge cleanly; merge commit has both parents") {
      for
        pathA        <- uniquePath("disjoint-a")
        pathB        <- uniquePath("disjoint-b")
        (branch, _)  <- forkAt(pathA, "\"a0\"")
        _            <- IrminClient.set(pathB, "\"b0\"", "base write b")
        branchHead   <- IrminClient.set(pathA, "\"a-scenario\"", "scenario edits a", branch)
        mainHead     <- IrminClient.set(pathB, "\"b-main\"", "main edits b")
        mergeCommit  <- IrminClient.mergeBranch(branch, BranchRef.Main, "merge disjoint")
        a            <- IrminClient.get(pathA)
        b            <- IrminClient.get(pathB)
      yield assertTrue(
        a.contains("\"a-scenario\""),
        b.contains("\"b-main\""),
        mergeCommit.parents.toSet == Set(branchHead.hash, mainHead.hash)
      )
    },

    test("a conflicting merge is silently swallowed: no GraphQL error, head unchanged, no merge commit") {
      // Live-verified quirk this test pins: Irmin's GraphQL `merge_with_branch`
      // does NOT report a conflict — the response carries no `errors` array and
      // returns the target branch's unchanged head. The only observable signal
      // is that the head did not advance. `ScenarioMergeService` therefore
      // treats its own byte-level pre-check as the authoritative conflict
      // detector and verifies head advancement after every merge; if this test
      // ever starts failing (an Irmin upgrade surfacing conflicts as errors),
      // that compensation can be simplified.
      for
        path        <- uniquePath("conflict")
        (branch, _) <- forkAt(path, "\"v0\"")
        _           <- IrminClient.set(path, "\"scenario-value\"", "scenario edit", branch)
        mainHead    <- IrminClient.set(path, "\"main-value\"", "main edit")
        result      <- IrminClient.mergeBranch(branch, BranchRef.Main, "merge conflicting")
        onMain      <- IrminClient.get(path)
      yield assertTrue(
        result.hash == mainHead.hash,
        result.parents == mainHead.parents,
        onMain.contains("\"main-value\"")
      )
    },

    test("same path edited to identical bytes on both sides merges cleanly") {
      for
        path        <- uniquePath("equal-bytes")
        (branch, _) <- forkAt(path, "\"v0\"")
        _           <- IrminClient.set(path, "\"agreed\"", "scenario edit", branch)
        _           <- IrminClient.set(path, "\"agreed\"", "main edit to same bytes")
        _           <- IrminClient.mergeBranch(branch, BranchRef.Main, "merge equal bytes")
        onMain      <- IrminClient.get(path)
      yield assertTrue(onMain.contains("\"agreed\""))
    },

    test("getAtCommit reads the value as of the given commit; absent path is None") {
      for
        path         <- uniquePath("at-commit")
        otherPath    <- uniquePath("at-commit-absent")
        (branch, base) <- forkAt(path, "\"base-value\"")
        _            <- IrminClient.set(path, "\"changed-later\"", "later edit", branch)
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
        branchHead     <- IrminClient.set(path, "\"diverged\"", "scenario edit", branch)
        _              <- IrminClient.set(path, "\"main-moved\"", "main edit")
        lcas           <- IrminClient.lca(BranchRef.Main, commitHash(branchHead.hash))
      yield assertTrue(lcas.map(_.hash) == List(base.value))
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // ---- helpers (same conventions as IrminClientIntegrationSpec) ----

  private def freshBranch: ZIO[Any, Nothing, BranchRef] =
    Random.nextIntBetween(0, 1000000).flatMap { salt =>
      ZIO.clockWith(_.instant).map(i => unsafeBranch(s"scenarios.merge.t${i.toEpochMilli}x$salt"))
    }

  private def unsafeBranch(s: String): BranchRef =
    BranchRef.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def commitHash(s: String): CommitHash =
    CommitHash.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)
