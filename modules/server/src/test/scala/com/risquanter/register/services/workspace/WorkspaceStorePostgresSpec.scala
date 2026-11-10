package com.risquanter.register.services.workspace

import java.time.Duration
import javax.sql.DataSource
import io.github.iltotore.iron.autoRefine
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import org.postgresql.ds.PGSimpleDataSource

import zio.*
import zio.prelude.NonEmptyList
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.configs.{TestConfigs, WorkspaceConfig}
import com.risquanter.register.domain.data.iron.{TreeId, SeedEntityId}
import com.risquanter.register.domain.errors.{Core, ErrorLocation, Failure, FieldProblem, OutageMode, PeerZone, ValidationFailed, ValidationErrorCode, WorkspaceExpired, WorkspaceNotFound}
import com.risquanter.register.infra.persistence.RepositorySpec
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.auth.{Authority, Checked, TestChecked}

object WorkspaceStorePostgresSpec extends ZIOSpecDefault, RepositorySpec:
  private given Checked[Authority] = TestChecked.value

  // One tree per workspace, so the ceiling is reachable in a test. Every test
  // creates its own workspace, so the low ceiling constrains none of the others.
  private val storeConfig: WorkspaceConfig = TestConfigs.workspace.copy(
    ttl = Duration.ofHours(24),
    idleTimeout = Duration.ofSeconds(1),
    maxTreesPerWorkspace = 1
  )

  private val storeLayer: ZLayer[Scope, Throwable, WorkspaceStore] =
    ZLayer.succeed(storeConfig) ++ quillLayer >>> WorkspaceStorePostgres.layer

  /** Port 1 is reserved by IANA and has no listener here, so every connection
    * attempt is refused. */
  private val UnreachablePort = 1

  private val unreachableStoreLayer: ZLayer[Any, Nothing, WorkspaceStore] =
    val ds = new PGSimpleDataSource()
    ds.setServerNames(Array("localhost"))
    ds.setPortNumbers(Array(UnreachablePort))
    ds.setDatabaseName("register")
    ZLayer.succeed(storeConfig) ++
      (ZLayer.succeed[DataSource](ds) >>> Quill.Postgres.fromNamingStrategy(SnakeCase)) >>>
      WorkspaceStorePostgres.layer

  override def spec = suite("WorkspaceStorePostgres")(
    test("create + resolve succeeds") {
      for
        store <- ZIO.service[WorkspaceStore]
        key   <- store.create(None)
        ws    <- store.resolve(key)
      yield assertTrue(ws.keyHash == WorkspaceKeyCrypto.hash(key))
    },

    test("addTree/listTrees/removeTree roundtrip") {
      for
        store  <- ZIO.service[WorkspaceStore]
        key    <- store.create(None)
        treeId <- IdGenerators.nextTreeId
        _      <- store.addTree(key, treeId)
        listed <- store.listTrees(key)
        _      <- store.removeTree(key, treeId)
        after  <- store.listTrees(key)
      yield assertTrue(listed.contains(treeId), !after.contains(treeId))
    },

    test("rotate preserves the workspace's identity and invalidates old key") {
      for
        store   <- ZIO.service[WorkspaceStore]
        oldKey  <- store.create(None)
        ws1     <- store.resolve(oldKey)
        newKey  <- store.rotate(oldKey)
        viaKey  <- store.resolve(newKey)
        oldExit <- store.resolve(oldKey).exit
      yield assertTrue(
        viaKey.id == ws1.id,
        viaKey.keyHash == WorkspaceKeyCrypto.hash(newKey)
      ) && assert(oldExit)(fails(isSubtype[WorkspaceNotFound](anything)))
    },

    test("an expired workspace reports expired") {
      for
        store <- ZIO.service[WorkspaceStore]
        key   <- store.create(None)
        _     <- ZIO.sleep(2.seconds)
        byKey <- store.resolve(key).exit
      yield assert(byKey)(fails(isSubtype[WorkspaceExpired](anything)))
    },

    test("delete reports not-found on the deleted key") {
      for
        store   <- ZIO.service[WorkspaceStore]
        key     <- store.create(None)
        _       <- store.delete(key)
        keyExit <- store.resolve(key).exit
      yield assert(keyExit)(fails(isSubtype[WorkspaceNotFound](anything)))
    },

    test("the tree ceiling refuses a new tree and allows re-association") {
      for
        store  <- ZIO.service[WorkspaceStore]
        key    <- store.create(None)
        first  <- IdGenerators.nextTreeId
        second <- IdGenerators.nextTreeId
        _      <- store.addTree(key, first)
        again  <- store.addTree(key, first).either
        exit   <- store.addTree(key, second).exit
        listed <- store.listTrees(key)
      yield assertTrue(again.isRight, listed == List(first)) &&
        assert(exit)(fails(isSubtype[ValidationFailed](anything)))
    },

    test("concurrent associations cannot exceed the ceiling between them") {
      // Eight callers, one slot, one winner.
      for
        store    <- ZIO.service[WorkspaceStore]
        key      <- store.create(None)
        trees    <- ZIO.foreach(1 to 8)(_ => IdGenerators.nextTreeId)
        outcomes <- ZIO.foreachPar(trees.toList)(t => store.addTree(key, t).either)
        listed   <- store.listTrees(key)
      yield assertTrue(
        outcomes.count(_.isRight) == 1,
        outcomes.count(_.isLeft) == 7,
        listed.size == 1
      )
    },

    test("evictExpired returns expired workspace records") {
      for
        store   <- ZIO.service[WorkspaceStore]
        key     <- store.create(None)
        ws      <- store.resolve(key)
        _       <- ZIO.sleep(2.seconds)
        evicted <- store.evictExpired
      yield assertTrue(evicted.exists(_.id == ws.id))
    },

    // ── Seed identity ───────────────────────────────────────────────────
    // DB is shared across the suite, so these assert relative properties
    // (distinctness, monotonicity), not absolute counter values.

    test("sequence assigns distinct, strictly increasing seedEntityIds") {
      for
        store <- ZIO.service[WorkspaceStore]
        k1    <- store.create(None)
        k2    <- store.create(None)
        e1    <- store.resolve(k1).map(_.seedEntityId.value)
        e2    <- store.resolve(k2).map(_.seedEntityId.value)
      yield assertTrue(e2 > e1)
    },

    test("provided seedEntityId is stored and visible on resolve") {
      for
        store <- ZIO.service[WorkspaceStore]
        id    <- ZIO.fromEither(SeedEntityId.fromLong(900042L)).orDieWith(e => new AssertionError(e.toString))
        key   <- store.create(Some(id))
        ws    <- store.resolve(key)
      yield assertTrue(ws.seedEntityId.value == 900042L)
    },

    test("duplicate provided seedEntityId is rejected with DUPLICATE_VALUE") {
      for
        store <- ZIO.service[WorkspaceStore]
        id    <- ZIO.fromEither(SeedEntityId.fromLong(900007L)).orDieWith(e => new AssertionError(e.toString))
        _     <- store.create(Some(id))
        exit  <- store.create(Some(id)).exit
      yield assert(exit)(fails(isSubtype[ValidationFailed](
        hasField("errors", _.errors.map(e => FieldProblem.code(e.problem)), contains(ValidationErrorCode.DUPLICATE_VALUE))
      )))
    },

    test("a database that refuses the connection is an outage at the database") {
      ZIO.serviceWithZIO[WorkspaceStore](_.create(None).either)
        .provideLayer(unreachableStoreLayer)
        .map(outcome => assertTrue(outcome match
          case Left(Failure.Unanswered(Core(PeerZone.Database, errors, _))) =>
            errors == NonEmptyList((ErrorLocation.Whole, OutageMode.ConnectionRefused))
          case _ => false
        ))
    },

    test("assignment after a provided value skips past it, bumping the sequence") {
      for
        store <- ZIO.service[WorkspaceStore]
        id    <- ZIO.fromEither(SeedEntityId.fromLong(900100L)).orDieWith(e => new AssertionError(e.toString))
        _     <- store.create(Some(id))
        key   <- store.create(None)
        ws    <- store.resolve(key)
      yield assertTrue(ws.seedEntityId.value > 900100L)
    }
  ).provideLayerShared(storeLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom