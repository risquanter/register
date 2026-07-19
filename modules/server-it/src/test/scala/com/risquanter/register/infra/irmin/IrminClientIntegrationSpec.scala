package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminTreeEntry}
import com.risquanter.register.domain.errors.IrminUnavailable
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, PositiveInt}
import com.risquanter.register.testcontainers.IrminCompose
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

    test("branches returns main branch after first commit") {
      for
        // Irmin doesn't list 'main' in branches until it has at least one commit
        testPath   <- uniquePath("branches-test")
        _          <- IrminClient.set(testPath, "init", "Initialize for branches test")
        branchList <- IrminClient.branches
      yield assertTrue(branchList.contains("main"))
    },

    test("set and get roundtrip works") {
      for
        testPath  <- uniquePath("roundtrip")
        testValue  = """{"name": "test", "value": 42}"""
        
        // Write value
        commit <- IrminClient.set(testPath, testValue, "Integration test write")
        _      <- ZIO.logInfo(s"Committed: ${commit.hash}")
        
        // Read value back
        result <- IrminClient.get(testPath)
      yield assertTrue(
        commit.hash.nonEmpty,
        commit.info.message.contains("Integration test"),
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
        
        commit <- IrminClient.set(testPath, testValue, "Author check test")
      yield assertTrue(
        commit.info.author == "zio-client",
        commit.info.message.contains("Author check"),
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
        _            <- IrminClient.set(testPath, testValue, "Create for deletion test")
        beforeDelete <- IrminClient.get(testPath)
        
        // Remove value
        _           <- IrminClient.remove(testPath, "Deletion test")
        afterDelete <- IrminClient.get(testPath)
      yield assertTrue(
        beforeDelete.contains(testValue),
        afterDelete.isEmpty
      )
    },

    test("multiple writes to same path creates different commits") {
      for
        testPath <- uniquePath("versioned")
        
        commit1 <- IrminClient.set(testPath, "\"v1\"", "Version 1")
        commit2 <- IrminClient.set(testPath, "\"v2\"", "Version 2")
        commit3 <- IrminClient.set(testPath, "\"v3\"", "Version 3")
        
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
        _        <- IrminClient.set(testPath, "\"main-value\"", "main write")
        _        <- IrminClient.set(testPath, "\"branch-value\"", "branch write", Some(branch))
        onMain   <- IrminClient.get(testPath)
        onBranch <- IrminClient.get(testPath, Some(branch))
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
        commit   <- IrminClient.set(testPath, "\"head-probe\"", "branch head write", Some(branch))
        known    <- IrminClient.getBranch(branch)
        unknown  <- IrminClient.getBranch(unsafeBranch("scenarios.never.created.here"))
      yield assertTrue(
        known.flatMap(_.head).map(_.hash).contains(commit.hash),
        unknown.flatMap(_.head).isEmpty
      )
    },

    test("mergeBranch folds branch writes into main") {
      for
        testPath <- uniquePath("merge-test")
        branch   <- freshBranch
        _        <- IrminClient.set(testPath, "\"merged-value\"", "branch write", Some(branch))
        _        <- IrminClient.mergeBranch(branch, None, "merge branch into main")
        onMain   <- IrminClient.get(testPath)
      yield assertTrue(onMain.contains("\"merged-value\""))
    },

    test("getCommit finds a commit by hash; revert restores an earlier state") {
      for
        testPath <- uniquePath("revert-test")
        branch   <- freshBranch
        c1       <- IrminClient.set(testPath, "\"before\"", "v1", Some(branch))
        _        <- IrminClient.set(testPath, "\"after\"", "v2", Some(branch))
        found    <- IrminClient.getCommit(commitHash(c1.hash))
        _        <- IrminClient.revert(commitHash(c1.hash), Some(branch))
        value    <- IrminClient.get(testPath, Some(branch))
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
        _        <- IrminClient.set(testPath, "\"h1\"", "history v1", Some(branch))
        _        <- IrminClient.set(testPath, "\"h2\"", "history v2", Some(branch))
        history  <- IrminClient.getHistory(testPath, positiveInt(10), Some(branch))
      yield assertTrue(
        history.size == 2,
        history.map(_.info.message).toSet == Set("history v1", "history v2")
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
                     "settree atomic write",
                     Some(branch)
                   )
        meta    <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/meta"), Some(branch))
        n1      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n1"), Some(branch))
        n2      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n2"), Some(branch))
        head    <- IrminClient.getBranch(branch)
        // Same single commit touches every entry — meta and node histories coincide.
        hMeta   <- IrminClient.getHistory(IrminPath.unsafeFrom(s"${base.value}/meta"), positiveInt(10), Some(branch))
        hNode   <- IrminClient.getHistory(IrminPath.unsafeFrom(s"${base.value}/nodes/n1"), positiveInt(10), Some(branch))
      yield assertTrue(
        meta.contains("\"m1\""),
        n1.contains("\"v1\""),
        n2.contains("\"v2\""),
        head.flatMap(_.head).map(_.hash).contains(commit.hash),
        hMeta.map(_.hash) == List(commit.hash),
        hNode.map(_.hash) == List(commit.hash)
      )
    },

    test("setTree replaces the subtree: unlisted keys are deleted") {
      for
        base   <- uniquePath("settree-replace")
        branch <- freshBranch
        _      <- IrminClient.setTree(base, List(entry("nodes/a", "\"a1\""), entry("nodes/b", "\"b1\"")), "settree v1", Some(branch))
        _      <- IrminClient.setTree(base, List(entry("nodes/b", "\"b2\""), entry("nodes/c", "\"c1\"")), "settree v2", Some(branch))
        a      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/a"), Some(branch))
        b      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/b"), Some(branch))
        c      <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/c"), Some(branch))
      yield assertTrue(
        a.isEmpty,
        b.contains("\"b2\""),
        c.contains("\"c1\"")
      )
    },

    test("setTree with empty entries removes the whole subtree") {
      for
        base   <- uniquePath("settree-delete")
        branch <- freshBranch
        _      <- IrminClient.setTree(base, List(entry("meta", "\"m\""), entry("nodes/n1", "\"v\"")), "settree create", Some(branch))
        _      <- IrminClient.setTree(base, Nil, "settree delete", Some(branch))
        meta   <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/meta"), Some(branch))
        n1     <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n1"), Some(branch))
        rest   <- IrminClient.list(base, Some(branch))
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
        commit  <- IrminClient.setTree(base, entries, "settree large write", Some(branch))
        first   <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n0"), Some(branch))
        last    <- IrminClient.get(IrminPath.unsafeFrom(s"${base.value}/nodes/n${nodeCount - 1}"), Some(branch))
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
        base       <- IrminClient.set(basePath, "\"common\"", "common base")
        // Fork: branch starts from main's head, then diverges
        _          <- IrminClient.mergeBranch(unsafeBranch("main"), Some(branch), "fork from main")
        _          <- IrminClient.set(branchPath, "\"diverged\"", "branch diverges", Some(branch))
        lcas       <- IrminClient.lca(Some(branch), commitHash(base.hash))
      yield assertTrue(lcas.map(_.hash).contains(base.hash))
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  // ---- branch test helpers ----

  /** Unique BranchRef per test (constraint: main | scenarios.<a>.<b>.<c>). */
  private def freshBranch: ZIO[Any, Nothing, BranchRef] =
    ZIO.clockWith(_.instant).map(i => unsafeBranch(s"scenarios.it.test.t${i.toEpochMilli}"))

  private def unsafeBranch(s: String): BranchRef =
    BranchRef.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def commitHash(s: String): CommitHash =
    CommitHash.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def positiveInt(n: Int): PositiveInt = n.refineUnsafe

  private def entry(relPath: String, value: String): IrminTreeEntry =
    IrminTreeEntry(IrminPath.unsafeFrom(relPath), value)
