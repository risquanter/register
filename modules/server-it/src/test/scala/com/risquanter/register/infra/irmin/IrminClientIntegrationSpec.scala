package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.prelude.NonEmptyList
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminTreeEntry}
import com.risquanter.register.domain.errors.{
  BranchAlreadyExists, BranchHeadStale, Core, ErrorLocation, Failure, OutageMode, PeerZone
}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, PositiveInt, Url, WorkspaceId}
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}
import io.github.iltotore.iron.refineUnsafe

/**
  * Integration tests for IrminClient.
  *
  * Startup: Uses docker compose CLI to launch docker-compose.yml with the `persistence` profile.
  * Requires: Docker + docker compose CLI available locally. Compose project name is randomized per run for isolation.
  * Run: sbt "serverIt/testOnly *IrminClientIntegrationSpec" (builds/starts compose automatically).
  * Note: Tests use unique paths with timestamps to avoid collisions from content-addressed storage.
  */
object IrminClientIntegrationSpec extends ZIOSpecDefault:

  private val testLayer: ZLayer[Any, Throwable, IrminClient] =
    IrminCompose.irminConfigLayer >>> IrminClientLive.layer

  /** Generate unique path with timestamp to avoid content-addressing collisions */
  private def uniquePath(base: String): ZIO[Any, Nothing, IrminPath] =
    ZIO.clockWith(_.instant).map { instant =>
      IrminPath.unsafeFrom(s"test/$base/${instant.toEpochMilli}")
    }

  override def spec = suite("IrminClientIntegrationSpec")(
    
    test("healthCheck succeeds when Irmin is running") {
      for
        _ <- IrminClient.healthCheck
      yield assertCompletes
    },

    test("a store that refuses the connection fails with an outage at the store") {
      // Port 1 is reserved and nothing listens on it, so the connection is refused.
      val deadConfig = IrminConfig(
        url                          = Url.fromString("http://localhost:1").toOption.get,
        conflictScanFetchParallelism = positiveInt(1)
      )
      ZIO.scoped {
        (ZLayer.succeed(deadConfig) >>> IrminClientLive.layer).build
          .flatMap(_.get[IrminClient].healthCheck.either)
      }.map {
        case Left(Failure.Unanswered(Core(PeerZone.Store, errors, _))) =>
          assertTrue(errors == NonEmptyList((ErrorLocation.Whole, OutageMode.ConnectionRefused)))
        case _ => assertTrue(false)
      }
    },

    test("branches returns main branch after first commit") {
      for
        // Irmin doesn't list 'main' in branches until it has at least one commit
        testPath   <- uniquePath("branches-test")
        _          <- IrminClient.set(testPath, "init", msg("Initialize for branches test"))
        branchList <- IrminClient.branches
      yield assertTrue(branchList.contains("main"))
    },

    test("set and get roundtrip works") {
      for
        testPath  <- uniquePath("roundtrip")
        testValue  = """{"name": "test", "value": 42}"""
        
        // Write value
        commit <- IrminClient.set(testPath, testValue, msg("Integration test write"))
        _      <- ZIO.logInfo(s"Committed: ${commit.hash}")
        
        // Read value back
        result <- IrminClient.get(testPath)
      yield assertTrue(
        commit.hash.nonEmpty,
        commit.info.message == CommitMessage.render(msg("Integration test write")),
        result.contains(testValue)
      )
    },

    test("get returns None for non-existent path") {
      for
        nonExistentPath <- uniquePath("does-not-exist")
        result          <- IrminClient.get(nonExistentPath)
      yield assertTrue(result.isEmpty)
    },

    test("set creates commit with author info") {
      for
        testPath  <- uniquePath("author-check")
        testValue  = "\"author test value\""
        
        commit <- IrminClient.set(testPath, testValue, msg("Author check test"))
      yield assertTrue(
        commit.info.author == "zio-client",
        commit.info.message == CommitMessage.render(msg("Author check test")),
        commit.info.date.nonEmpty
      )
    },

    test("mainBranch returns branch info") {
      for
        maybeBranch <- IrminClient.mainBranch
      yield assertTrue(maybeBranch.isDefined)
    },

    test("remove deletes value at path") {
      for
        testPath  <- uniquePath("to-delete")
        testValue  = "\"delete me\""
        
        // Create value
        _            <- IrminClient.set(testPath, testValue, msg("Create for deletion test"))
        beforeDelete <- IrminClient.get(testPath)
        
        // Remove value
        _           <- IrminClient.remove(testPath, msg("Deletion test"))
        afterDelete <- IrminClient.get(testPath)
      yield assertTrue(
        beforeDelete.contains(testValue),
        afterDelete.isEmpty
      )
    },

    test("multiple writes to same path creates different commits") {
      for
        testPath <- uniquePath("versioned")
        
        commit1 <- IrminClient.set(testPath, "\"v1\"", msg("Version 1"))
        commit2 <- IrminClient.set(testPath, "\"v2\"", msg("Version 2"))
        commit3 <- IrminClient.set(testPath, "\"v3\"", msg("Version 3"))
        
        current <- IrminClient.get(testPath)
      yield assertTrue(
        commit1.hash != commit2.hash,
        commit2.hash != commit3.hash,
        current.contains("\"v3\"")
      )
    },

    // ---- Branch operations (milestone 2b, DD-1/DD-2) ----

    test("set/get on a named branch is isolated from main") {
      for
        testPath <- uniquePath("branch-isolation")
        branch   <- freshBranch
        _        <- IrminClient.set(testPath, "\"main-value\"", msg("main write"))
        _        <- IrminClient.set(testPath, "\"branch-value\"", msg("branch write"), branch)
        onMain   <- IrminClient.get(testPath)
        onBranch <- IrminClient.get(testPath, branch)
        listed   <- IrminClient.branches
      yield assertTrue(
        onMain.contains("\"main-value\""),
        onBranch.contains("\"branch-value\""),
        listed.contains(branch.toBranchRef)
      )
    },

    test("getBranch returns head info for a named branch, None head for unknown") {
      for
        testPath <- uniquePath("branch-head")
        branch   <- freshBranch
        commit   <- IrminClient.set(testPath, "\"head-probe\"", msg("branch head write"), branch)
        known    <- IrminClient.getBranch(branch)
        unknown  <- IrminClient.getBranch(unsafeBranch("scenarios.never.created"))
      yield assertTrue(
        known.flatMap(_.head).map(_.hash).contains(commit.hash),
        unknown.flatMap(_.head).isEmpty
      )
    },

    // ---- CAS branch create/delete (Phase B, DD-5, A9 fact 2/3) ----

    test("createBranchAt forks main's content, unlike a bare first write (A9 fact 3)") {
      for
        testPath <- uniquePath("cas-fork")
        branch   <- freshBranch
        mainHead <- IrminClient.set(testPath, "\"main-value\"", msg("main write before fork"))
        _        <- IrminClient.createBranchAt(branch, commitHash(mainHead.hash))
        onBranch <- IrminClient.get(testPath, branch)
      yield assertTrue(onBranch.contains("\"main-value\""))
    },

    test("createBranchAt fails with BranchAlreadyExists on a name collision") {
      for
        testPath <- uniquePath("cas-collision")
        branch   <- freshBranch
        mainHead <- IrminClient.set(testPath, "\"v\"", msg("main write"))
        _        <- IrminClient.createBranchAt(branch, commitHash(mainHead.hash))
        exit     <- IrminClient.createBranchAt(branch, commitHash(mainHead.hash)).exit
      yield assert(exit)(fails(isSubtype[BranchAlreadyExists](anything)))
    },

    test("deleteBranch removes the pointer; the commit remains resolvable by hash (A9 fact 5)") {
      for
        testPath <- uniquePath("cas-delete")
        branch   <- freshBranch
        commit   <- IrminClient.set(testPath, "\"v\"", msg("branch write"), branch)
        _        <- IrminClient.deleteBranch(branch, commitHash(commit.hash))
        listed   <- IrminClient.branches
        found    <- IrminClient.getCommit(commitHash(commit.hash))
      yield assertTrue(
        !listed.contains(branch.toBranchRef),
        found.map(_.hash).contains(commit.hash)
      )
    },

    test("deleteBranch fails with BranchHeadStale when the expected head is out of date") {
      for
        testPath <- uniquePath("cas-stale-delete")
        branch   <- freshBranch
        c1       <- IrminClient.set(testPath, "\"v1\"", msg("branch write v1"), branch)
        _        <- IrminClient.set(testPath, "\"v2\"", msg("branch write v2"), branch)
        exit     <- IrminClient.deleteBranch(branch, commitHash(c1.hash)).exit
      yield assert(exit)(fails(isSubtype[BranchHeadStale](anything)))
    },

    test("mergeCommit folds a branch commit into main") {
      for
        testPath <- uniquePath("merge-test")
        branch   <- freshBranch
        written  <- IrminClient.set(testPath, "\"merged-value\"", msg("branch write"), branch)
        _        <- IrminClient.mergeCommit(commitHash(written.hash), BranchRef.Main, msg("merge branch into main"))
        onMain   <- IrminClient.get(testPath)
      yield assertTrue(onMain.contains("\"merged-value\""))
    },

    test("getCommit finds a commit by hash; revert restores an earlier state") {
      for
        testPath <- uniquePath("revert-test")
        branch   <- freshBranch
        c1       <- IrminClient.set(testPath, "\"before\"", msg("v1"), branch)
        _        <- IrminClient.set(testPath, "\"after\"", msg("v2"), branch)
        found    <- IrminClient.getCommit(commitHash(c1.hash))
        _        <- IrminClient.revert(commitHash(c1.hash), branch)
        value    <- IrminClient.get(testPath, branch)
      yield assertTrue(
        found.map(_.hash).contains(c1.hash),
        value.contains("\"before\"")
      )
    },

    test("getHistory lists every commit touching a path") {
      // last_modified gives no ordering guarantee for same-second commits
      // (dates have second resolution), so the contract pinned here is
      // completeness, not order.
      for
        testPath <- uniquePath("history-test")
        branch   <- freshBranch
        _        <- IrminClient.set(testPath, "\"h1\"", msg("history v1"), branch)
        _        <- IrminClient.set(testPath, "\"h2\"", msg("history v2"), branch)
        history  <- IrminClient.getHistory(testPath, positiveInt(10), branch)
      yield assertTrue(
        history.size == 2,
        history.map(_.info.message).toSet == Set(msg("history v1"), msg("history v2")).map(CommitMessage.render)
      )
    },

    // ---- set_tree (DD-7: one commit per write action) ----

    test("setTree writes several entries atomically in ONE commit") {
      for
        base    <- uniquePath("settree-atomic")
        branch  <- freshBranch
        commit  <- IrminClient.setTree(
                     base,
                     List(entry("meta", "\"m1\""), entry("nodes/n1", "\"v1\""), entry("nodes/n2", "\"v2\"")),
                     msg("settree atomic write"),
                     branch
                   )
        meta    <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/meta"), branch)
        n1      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n1"), branch)
        n2      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n2"), branch)
        head    <- IrminClient.getBranch(branch)
        // Same single commit touches every entry — meta and node histories coincide.
        hMeta   <- IrminClient.getHistory(IrminPath.unsafeFrom(s"${base.value}/meta"), positiveInt(10), branch)
        hNode   <- IrminClient.getHistory(IrminPath.unsafeFrom(s"${base.value}/nodes/n1"), positiveInt(10), branch)
      yield assertTrue(
        meta.contains("\"m1\""),
        n1.contains("\"v1\""),
        n2.contains("\"v2\""),
        head.flatMap(_.head).map(_.hash).contains(commit.hash),
        hMeta.map(_.hash) == List(commit.hash),
        hNode.map(_.hash) == List(commit.hash)
      )
    },

    test("setTree replaces the subtree: unlisted keys are deleted, parent chain has no extra commits") {
      for
        base   <- uniquePath("settree-replace")
        branch <- freshBranch
        c1     <- IrminClient.setTree(base, List(entry("nodes/a", "\"a1\""), entry("nodes/b", "\"b1\"")), msg("settree v1"), branch)
        c2     <- IrminClient.setTree(base, List(entry("nodes/b", "\"b2\""), entry("nodes/c", "\"c1\"")), msg("settree v2"), branch)
        a      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/a"), branch)
        b      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/b"), branch)
        c      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/c"), branch)
      yield assertTrue(
        a.isEmpty,
        b.contains("\"b2\""),
        c.contains("\"c1\""),
        c1.parents.isEmpty,             // first write on a fresh branch is a root commit
        c2.parents == List(c1.hash)     // sole parent = previous head: exactly one commit per mutation
      )
    },

    test("listAtCommit lists a subtree as of an older commit after the path has since changed") {
      for
        base   <- uniquePath("list-at-commit")
        branch <- freshBranch
        c1     <- IrminClient.setTree(base, List(entry("nodes/a", "\"a1\""), entry("nodes/b", "\"b1\"")), msg("list at commit v1"), branch)
        _      <- IrminClient.setTree(base, List(entry("nodes/b", "\"b2\""), entry("nodes/c", "\"c1\"")), msg("list at commit v2"), branch)
        nodes   = IrminPath.unsafeFrom(s"${base.value}/nodes")
        atC1   <- IrminClient.listAtCommit(commitHash(c1.hash), nodes)
        atHead <- IrminClient.list(nodes, branch)
      yield assertTrue(
        atC1.map(_.value).toSet == Set("a", "b"),   // older commit's listing, unaffected by the later replace
        atHead.map(_.value).toSet == Set("b", "c")  // current head after the subtree replace
      )
    },

    test("setTree with empty entries removes the whole subtree") {
      for
        base   <- uniquePath("settree-delete")
        branch <- freshBranch
        _      <- IrminClient.setTree(base, List(entry("meta", "\"m\""), entry("nodes/n1", "\"v\"")), msg("settree create"), branch)
        _      <- IrminClient.setTree(base, Nil, msg("settree delete"), branch)
        meta   <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/meta"), branch)
        n1     <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n1"), branch)
        rest   <- IrminClient.list(base, branch)
      yield assertTrue(
        meta.isEmpty,
        n1.isEmpty,
        rest.isEmpty
      )
    },

    test("setTree handles a large tree in one commit") {
      val nodeCount = 2000
      val payload   = "x" * 300
      for
        base    <- uniquePath("settree-large")
        branch  <- freshBranch
        entries  = entry("meta", "\"large\"") ::
                     (0 until nodeCount).toList.map(i => entry(s"nodes/n$i", s"""{"id":"n$i","pad":"$payload"}"""))
        commit  <- IrminClient.setTree(base, entries, msg("settree large write"), branch)
        first   <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n0"), branch)
        last    <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n${nodeCount - 1}"), branch)
        head    <- IrminClient.getBranch(branch)
      yield assertTrue(
        first.isDefined,
        last.isDefined,
        head.flatMap(_.head).map(_.hash).contains(commit.hash)
      )
    },

    test("lca finds the merge base of a branch and a main commit") {
      for
        basePath   <- uniquePath("lca-base")
        branchPath <- uniquePath("lca-branch")
        branch     <- freshBranch
        base       <- IrminClient.set(basePath, "\"common\"", msg("common base"))
        // Fork: branch starts from main's head, then diverges
        _          <- IrminClient.mergeCommit(commitHash(base.hash), branch, msg("fork from main"))
        _          <- IrminClient.set(branchPath, "\"diverged\"", msg("branch diverges"), branch)
        lcas       <- IrminClient.lca(branch, commitHash(base.hash))
      yield assertTrue(lcas.map(_.hash).contains(base.hash))
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // ---- branch test helpers ----

  /** Unique BranchRef per test (constraint: main | scenarios.<a>.<b>). */
  private def freshBranch: ZIO[Any, Nothing, BranchRef] =
    ZIO.clockWith(_.instant).map(i => unsafeBranch(s"scenarios.it.t${i.toEpochMilli}"))

  private def unsafeBranch(s: String): BranchRef =
    BranchRef.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def commitHash(s: String): CommitHash =
    CommitHash.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def positiveInt(n: Int): PositiveInt = n.refineUnsafe

  private def entry(relPath: String, value: String): IrminTreeEntry =
    IrminTreeEntry(IrminPath.unsafeFrom(relPath), value)

  private val itWorkspace = WorkspaceId(safeId("irmin-client-it"))

  /** A commit message of this server's own kind, distinct per `label`, so a
    * test can tell its commits apart by message. */
  private def msg(label: String): CommitMessage =
    CommitMessage.TreeWritten(itWorkspace, treeId(label), TreeWriteKind.Update)
