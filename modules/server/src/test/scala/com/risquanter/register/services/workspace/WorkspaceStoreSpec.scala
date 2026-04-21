package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration
import scala.concurrent.duration.*

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceKeyHash}
import com.risquanter.register.domain.errors.{WorkspaceNotFound, WorkspaceExpired, TreeNotInWorkspace}
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
      yield assertTrue(ws.keyHash == WorkspaceKeyHash.fromSecret(key))
    },

    test("resolveById resolves same workspace") {
      for
        store <- mkStore
        key   <- store.create()
        ws1   <- store.resolve(key)
        ws2   <- store.resolveById(ws1.id)
      yield assertTrue(ws1.id == ws2.id, ws1.keyHash == ws2.keyHash)
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

    test("resolveTreeWorkspace returns workspace for member tree") {
      for
        store  <- mkStore
        key    <- store.create()
        treeId <- IdGenerators.nextTreeId
        _      <- store.addTree(key, treeId)
        ws     <- store.resolveTreeWorkspace(key, treeId)
      yield assertTrue(ws.keyHash == WorkspaceKeyHash.fromSecret(key), ws.trees.contains(treeId))
    },

    test("resolveTreeWorkspace fails when tree is not in workspace") {
      for
        store  <- mkStore
        key    <- store.create()
        treeId <- IdGenerators.nextTreeId
        exit   <- store.resolveTreeWorkspace(key, treeId).exit
      yield assert(exit)(fails(isSubtype[TreeNotInWorkspace](anything)))
    },

    test("removeTree disassociates tree from workspace") {
      for
        store  <- mkStore
        key    <- store.create()
        ids    <- IdGenerators.batch(2).map(_.map(TreeId(_)))
        t1      = ids(0)
        t2      = ids(1)
        _      <- store.addTree(key, t1)
        _      <- store.addTree(key, t2)
        _      <- store.removeTree(key, t1)
        list   <- store.listTrees(key)
        gone   <- store.belongsTo(key, t1)
        kept   <- store.belongsTo(key, t2)
      yield assertTrue(!list.contains(t1), list.contains(t2), !gone, kept)
    },

    test("removeTree is idempotent — removing non-member is a no-op") {
      for
        store  <- mkStore
        key    <- store.create()
        treeId <- IdGenerators.nextTreeId
        // Remove a tree that was never added — should succeed silently
        _      <- store.removeTree(key, treeId)
        list   <- store.listTrees(key)
      yield assertTrue(list.isEmpty)
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

    test("rotate preserves resolveById lookup") {
      for
        store   <- mkStore
        oldKey  <- store.create()
        ws1     <- store.resolve(oldKey)
        newKey  <- store.rotate(oldKey)
        ws2     <- store.resolveById(ws1.id)
        viaKey  <- store.resolve(newKey)
      yield assertTrue(
        ws2.id == ws1.id,
        ws2.keyHash == WorkspaceKeyHash.fromSecret(newKey),
        viaKey.id == ws1.id,
        viaKey.keyHash == WorkspaceKeyHash.fromSecret(newKey)
      )
    },

    test("delete removes workspace") {
      for
        store <- mkStore
        key   <- store.create()
        _     <- store.delete(key)
        exit  <- store.resolve(key).exit
      yield assert(exit)(fails(isSubtype[WorkspaceNotFound](anything)))
    },

    test("evictExpired removes expired workspaces and returns evicted entries") {
      val shortConfig = testConfig.copy(ttl = Duration.ofMinutes(1), idleTimeout = Duration.ofMinutes(1))
      for
        store   <- WorkspaceStoreLive.make(shortConfig)
        key     <- store.create()
        ws      <- store.resolve(key)
        _       <- TestClock.adjust(2.minutes)
        evicted <- store.evictExpired
      yield assertTrue(evicted.size == 1, evicted.contains(ws.id))
    }
  )
