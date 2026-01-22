package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.domain.errors.IrminUnavailable
import com.risquanter.register.testcontainers.IrminCompose

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
    
    test("healthCheck returns true when Irmin is running") {
      for
        healthy <- IrminClient.healthCheck
      yield assertTrue(healthy)
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
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
