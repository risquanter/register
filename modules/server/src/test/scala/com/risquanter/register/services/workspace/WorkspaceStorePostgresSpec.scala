package com.risquanter.register.services.workspace

import java.time.Duration

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.configs.{TestConfigs, WorkspaceConfig}
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash}
import com.risquanter.register.domain.errors.{WorkspaceExpired, WorkspaceExpiredById, WorkspaceNotFound, WorkspaceNotFoundById}
import com.risquanter.register.infra.persistence.RepositorySpec
import com.risquanter.register.util.IdGenerators

object WorkspaceStorePostgresSpec extends ZIOSpecDefault, RepositorySpec:

  private val storeConfig: WorkspaceConfig = TestConfigs.workspace.copy(
    ttl = Duration.ofHours(24),
    idleTimeout = Duration.ofSeconds(1)
  )

  private val storeLayer: ZLayer[Scope, Throwable, WorkspaceStore] =
    ZLayer.succeed(storeConfig) ++ quillLayer >>> WorkspaceStorePostgres.layer

  override def spec = suite("WorkspaceStorePostgres")(
    test("create + resolve succeeds") {
      for
        store <- ZIO.service[WorkspaceStore]
        key   <- store.create()
        ws    <- store.resolve(key)
      yield assertTrue(ws.keyHash == WorkspaceKeyHash.fromSecret(key))
    },

    test("resolveById resolves same workspace") {
      for
        store <- ZIO.service[WorkspaceStore]
        key   <- store.create()
        ws1   <- store.resolve(key)
        ws2   <- store.resolveById(ws1.id)
      yield assertTrue(ws1.id == ws2.id, ws1.keyHash == ws2.keyHash)
    },

    test("addTree/listTrees/removeTree roundtrip") {
      for
        store  <- ZIO.service[WorkspaceStore]
        key    <- store.create()
        treeId <- IdGenerators.nextTreeId
        _      <- store.addTree(key, treeId)
        listed <- store.listTrees(key)
        _      <- store.removeTree(key, treeId)
        after  <- store.listTrees(key)
      yield assertTrue(listed.contains(treeId), !after.contains(treeId))
    },

    test("rotate preserves resolveById and invalidates old key") {
      for
        store   <- ZIO.service[WorkspaceStore]
        oldKey  <- store.create()
        ws1     <- store.resolve(oldKey)
        newKey  <- store.rotate(oldKey)
        ws2     <- store.resolveById(ws1.id)
        oldExit <- store.resolve(oldKey).exit
      yield assertTrue(
        ws2.id == ws1.id,
        ws2.keyHash == WorkspaceKeyHash.fromSecret(newKey)
      ) && assert(oldExit)(fails(isSubtype[WorkspaceNotFound](anything)))
    },

    test("resolveById returns keyless expired error") {
      for
        store <- ZIO.service[WorkspaceStore]
        key   <- store.create()
        ws    <- store.resolve(key)
        _     <- ZIO.sleep(2.seconds)
        byKey <- store.resolve(key).exit
        byId  <- store.resolveById(ws.id).exit
      yield assert(byKey)(fails(isSubtype[WorkspaceExpired](anything))) &&
        assert(byId)(fails(isSubtype[WorkspaceExpiredById](anything)))
    },

    test("delete and unknown-id lookup report not-found") {
      for
        store   <- ZIO.service[WorkspaceStore]
        key     <- store.create()
        ws      <- store.resolve(key)
        rawId   <- IdGenerators.nextId
        _       <- store.delete(key)
        keyExit <- store.resolve(key).exit
        idExit  <- store.resolveById(WorkspaceId(rawId)).exit
      yield assert(keyExit)(fails(isSubtype[WorkspaceNotFound](anything))) &&
        assert(idExit)(fails(isSubtype[WorkspaceNotFoundById](anything))) &&
        assertTrue(ws.id != WorkspaceId(rawId))
    },

    test("evictExpired returns expired workspace records") {
      for
        store   <- ZIO.service[WorkspaceStore]
        key     <- store.create()
        ws      <- store.resolve(key)
        _       <- ZIO.sleep(2.seconds)
        evicted <- store.evictExpired
      yield assertTrue(evicted.contains(ws.id))
    }
  ).provideLayerShared(storeLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom