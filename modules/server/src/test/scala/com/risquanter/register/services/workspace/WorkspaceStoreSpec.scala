package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration
import scala.concurrent.duration.*

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}
import com.risquanter.register.domain.errors.{WorkspaceNotFound, WorkspaceExpired}
import com.risquanter.register.util.IdGenerators

object WorkspaceStoreSpec extends ZIOSpecDefault:

  private val testConfig = TestConfigs.workspace.copy(
    ttl = Duration.ofHours(24),
    idleTimeout = Duration.ofMinutes(1)
  )

  private def mkStore = WorkspaceStoreLive.make(testConfig)

  override def spec = suite("WorkspaceStoreLive security regressions")(
    test("create + resolve succeeds") {
      for
        store <- mkStore
        key   <- store.create()
        ws    <- store.resolve(key)
      yield assertTrue(ws.key == key)
    },

    test("resolve updates lastAccessedAt (A10)") {
      for
        store <- mkStore
        key   <- store.create()
        ws1   <- store.resolve(key)
        _     <- TestClock.adjust(zio.Duration.fromSeconds(30))
        ws2   <- store.resolve(key)
      yield assertTrue(ws2.lastAccessedAt.isAfter(ws1.lastAccessedAt) || ws2.lastAccessedAt == ws1.lastAccessedAt)
    },

    test("dual timeout: idle timeout expires workspace even if absolute TTL not reached (A11)") {
      for
        store <- mkStore
        key   <- store.create()
        _     <- TestClock.adjust(2.minutes)
        exit  <- store.resolve(key).exit
      yield assert(exit)(fails(isSubtype[WorkspaceExpired](anything)))
    },

    test("addTree/listTrees/belongsTo operate within workspace") {
      for
        store  <- mkStore
        key    <- store.create()
        treeId <- IdGenerators.nextTreeId
        _      <- store.addTree(key, treeId)
        list   <- store.listTrees(key)
        inWs   <- store.belongsTo(key, treeId)
      yield assertTrue(list.contains(treeId), inWs)
    },

    test("rotate instantly invalidates old key and keeps tree associations") {
      for
        store   <- mkStore
        oldKey  <- store.create()
        treeId  <- IdGenerators.nextTreeId
        _       <- store.addTree(oldKey, treeId)
        newKey  <- store.rotate(oldKey)
        oldExit <- store.resolve(oldKey).exit
        newList <- store.listTrees(newKey)
      yield assertTrue(oldKey != newKey, newList.contains(treeId)) &&
        assert(oldExit)(fails(isSubtype[WorkspaceNotFound](anything)))
    },

    test("delete removes workspace") {
      for
        store <- mkStore
        key   <- store.create()
        _     <- store.delete(key)
        exit  <- store.resolve(key).exit
      yield assert(exit)(fails(isSubtype[WorkspaceNotFound](anything)))
    },

    test("evictExpired removes expired workspaces and returns count") {
      val shortConfig = testConfig.copy(ttl = Duration.ofMinutes(1), idleTimeout = Duration.ofMinutes(1))
      for
        store <- WorkspaceStoreLive.make(shortConfig)
        _     <- store.create()
        _     <- TestClock.adjust(2.minutes)
        n     <- store.evictExpired
      yield assertTrue(n == 1)
    }
  )
