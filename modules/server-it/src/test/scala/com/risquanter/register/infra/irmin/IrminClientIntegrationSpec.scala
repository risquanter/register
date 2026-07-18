package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.infra.irmin.model.IrminPath
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
