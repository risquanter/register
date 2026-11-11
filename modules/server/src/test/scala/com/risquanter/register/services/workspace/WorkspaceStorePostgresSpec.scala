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
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{NonNegativeInt, TreeId, SeedEntityId, WorkspaceId}
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, ErrorSubject, ExpectedInput, ExpiryDetail, Failure, FieldName, FieldProblem, OutageMode,
  PeerZone, TrustZone, ValidationError
}
import com.risquanter.register.http.errors.NotFoundKind
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

  /** The store, and the database it runs on for the tests that write a row the
    * store would not. */
  private val storeLayer: ZLayer[Scope, Throwable, WorkspaceStore & DataSource] =
    dataSourceLayer >>> (
      ZLayer.environment[DataSource] ++
        ((ZLayer.succeed(storeConfig) ++ Quill.Postgres.fromNamingStrategy(SnakeCase)) >>> WorkspaceStorePostgres.layer)
    )

  /** Rewrites one workspace's lifetime to one month, which Postgres renders as
    * `1 mon`, a form the store does not read. */
  private def storeMonthLongTtl(id: WorkspaceId): RIO[DataSource, Unit] =
    ZIO.serviceWithZIO[DataSource](ds => ZIO.attemptBlocking {
      val conn = ds.getConnection()
      try
        val stmt = conn.prepareStatement("UPDATE workspaces SET ttl = interval '1 month' WHERE id = ?")
        try
          stmt.setString(1, id.value)
          stmt.executeUpdate()
          ()
        finally stmt.close()
      finally conn.close()
    })

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
      ) && assert(oldExit)(fails(equalTo(WorkspaceStore.workspaceNotFound)))
    },

    test("an expired workspace reports expired, at its idle deadline, naming the workspace") {
      for
        store   <- ZIO.service[WorkspaceStore]
        key     <- store.create(None)
        ws      <- store.resolve(key)
        _       <- ZIO.sleep(2.seconds)
        failure <- store.resolve(key).flip
      yield
        // The deadline is read back through the database, which keeps
        // microseconds, so it is compared to the moment of last access within
        // that resolution rather than to the nanosecond.
        val expiredAt = failure match
          case Failure.Expired(Core(_, _, List(_, Diagnostic.ExpiredAt(at)))) => Some(at)
          case _                                                             => None
        assertTrue(
          expiredAt.exists(at =>
            failure == Failure.Expired(Core(
              TrustZone.Client,
              NonEmptyList(ErrorLocation.requestField(FieldName.WorkspaceKey) ->
                ExpiryDetail(ExpiryCause.Inactivity, storeConfig.ttl, storeConfig.idleTimeout)),
              List(Diagnostic.WorkspaceRef(ws.id), Diagnostic.ExpiredAt(at))
            ))),
          expiredAt.exists(at =>
            Duration.between(ws.lastAccessedAt.plus(storeConfig.idleTimeout), at).abs.compareTo(Duration.ofMillis(1)) < 0)
        )
    },

    test("delete reports not-found on the deleted key") {
      for
        store   <- ZIO.service[WorkspaceStore]
        key     <- store.create(None)
        _       <- store.delete(key)
        keyExit <- store.resolve(key).exit
      yield assert(keyExit)(fails(equalTo(Failure.NotFound(Core(
        TrustZone.Client,
        NonEmptyList(ErrorLocation.requestField(FieldName.WorkspaceKey) -> NotFoundKind.Unknown()),
        List(Diagnostic.KeyUnresolved)
      )))))
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
        assert(exit)(fails(equalTo(Failure.rejected(
          TrustZone.Client,
          NonEmptyList(ValidationError(
            ErrorLocation.Field(ErrorSubject.Workspace, FieldName.Trees),
            FieldProblem.MaxTreesReached(NonNegativeInt.count(1))
          )),
          Nil
        ))))
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

    test("a seedEntityId another workspace holds is the caller's refusal, read from the database's unique violation") {
      for
        store <- ZIO.service[WorkspaceStore]
        id    <- ZIO.fromEither(SeedEntityId.fromLong(900007L)).orDieWith(e => new AssertionError(e.toString))
        _     <- store.create(Some(id))
        exit  <- store.create(Some(id)).exit
      yield assert(exit)(fails(equalTo(Failure.rejected(
        TrustZone.Client,
        NonEmptyList(ValidationError(ErrorLocation.Field(ErrorSubject.Workspace, FieldName.SeedEntityId), FieldProblem.SeedEntityIdInUse(id))),
        Nil
      ))))
    },

    test("a row whose lifetime is in an interval form the store does not read is refused at the database") {
      for
        store <- ZIO.service[WorkspaceStore]
        key   <- store.create(None)
        ws    <- store.resolve(key)
        _     <- storeMonthLongTtl(ws.id)
        exit  <- store.resolve(key).exit
      yield assert(exit)(fails(equalTo(Failure.Rejected(Core(
        TrustZone.Peer(PeerZone.Database),
        NonEmptyList(ErrorLocation.Field(ErrorSubject.Workspace, FieldName.Ttl) -> FieldProblem.Unreadable(ExpectedInput.Interval)),
        List(Diagnostic.RawValue("1 mon"))
      )))))
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