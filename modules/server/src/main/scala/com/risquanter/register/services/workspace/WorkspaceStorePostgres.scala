package com.risquanter.register.services.workspace

import zio.*
import java.time.{Duration, Instant, OffsetDateTime, ZoneOffset}
import io.getquill.*
import io.getquill.jdbczio.Quill

import com.risquanter.register.configs.WorkspaceConfig
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, WorkspaceExpired, WorkspaceNotFound}
import com.risquanter.register.infra.persistence.QuillMappings.given
import com.risquanter.register.util.IdGenerators

final class WorkspaceStorePostgres private (
  quill: Quill.Postgres[SnakeCase],
  config: WorkspaceConfig
) extends WorkspaceStore:

  import WorkspaceStorePostgres.*
  import quill.*

  private inline given workspaceSchema: SchemaMeta[WorkspaceRow] =
    schemaMeta[WorkspaceRow](
      "workspaces",
      _.keyHash -> "key_hash",
      _.createdAt -> "created_at",
      _.lastAccess -> "last_access",
      _.idleTimeout -> "idle_timeout"
    )

  private inline given workspaceTreeSchema: SchemaMeta[WorkspaceTreeRow] =
    schemaMeta[WorkspaceTreeRow](
      "workspace_trees",
      _.workspaceId -> "workspace_id",
      _.treeId -> "tree_id",
      _.addedAt -> "added_at"
    )

  override def create(): UIO[WorkspaceKeySecret] =
    (for
      key <- WorkspaceKeySecret.generate
      sid <- IdGenerators.nextId
      now <- Clock.instant
      row  = WorkspaceRow(
               id = WorkspaceId(sid),
               keyHash = WorkspaceKeyHash.fromSecret(key),
               createdAt = toOffsetDateTime(now),
               lastAccess = toOffsetDateTime(now),
               ttl = toIntervalLiteral(config.ttl),
               idleTimeout = toIntervalLiteral(config.idleTimeout)
             )
      _   <- db(run(query[WorkspaceRow].insertValue(lift(row))))
    yield key).orDie

  override def addTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit] =
    for
      ws  <- resolveInternal(key)
      now <- Clock.instant
      row  = WorkspaceTreeRow(ws.id, treeId, toOffsetDateTime(now))
      _   <- db(run(query[WorkspaceTreeRow].insertValue(lift(row)).onConflictIgnore)).unit
    yield ()

  override def removeTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit] =
    for
      ws <- resolveInternal(key)
      _  <- db(
              run(
                query[WorkspaceTreeRow]
                  .filter(row => row.workspaceId == lift(ws.id) && row.treeId == lift(treeId))
                  .delete
              )
            ).unit
    yield ()

  override def listTrees(key: WorkspaceKeySecret): IO[AppError, List[TreeId]] =
    resolveInternal(key).map(_.trees.toList)

  override def resolve(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    for
      ws  <- loadWorkspaceByKey(key)
      now <- Clock.instant
      _   <- db(
              run(
                query[WorkspaceRow]
                  .filter(_.id == lift(ws.id))
                  .update(_.lastAccess -> lift(toOffsetDateTime(now)))
              )
            ).unit
    yield ws.touch(now)

  override def resolveById(id: WorkspaceId): IO[AppError, WorkspaceRecord] =
    for
      row <- loadWorkspaceRowById(id)
      ws  <- toRecord(row)
      now <- Clock.instant
      _   <- ZIO.fail(RepositoryFailure(s"Workspace expired for id ${id.value}")).when(ws.isExpired(now))
      _   <- db(
              run(
                query[WorkspaceRow]
                  .filter(_.id == lift(id))
                  .update(_.lastAccess -> lift(toOffsetDateTime(now)))
              )
            ).unit
    yield ws.touch(now)

  override def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean] =
    resolveInternal(key).map(_.trees.contains(treeId))

  override def evictExpired: UIO[Map[WorkspaceId, WorkspaceRecord]] =
    (for
      rows    <- db(run(query[WorkspaceRow]))
      now     <- Clock.instant
      evicted <- ZIO.foreach(rows.toList) { row =>
                   toRecord(row).either.map(_.toOption.filter(_.isExpired(now)).map(ws => ws.id -> ws))
                 }
      doomed   = evicted.flatten
      doomedIds = doomed.map(_._2.id)
      _       <- ZIO.foreachDiscard(doomedIds)(id =>
                   db(run(query[WorkspaceRow].filter(_.id == lift(id)).delete)).unit
                 )
    yield doomed.toMap).orDie

  override def delete(key: WorkspaceKeySecret): IO[AppError, Unit] =
    for
      ws <- resolveInternal(key)
      _  <- db(run(query[WorkspaceRow].filter(_.id == lift(ws.id)).delete)).unit
    yield ()

  override def rotate(key: WorkspaceKeySecret): IO[AppError, WorkspaceKeySecret] =
    for
      ws     <- resolveInternal(key)
      newKey <- WorkspaceKeySecret.generate
      now    <- Clock.instant
      _      <- db(
                  run(
                    query[WorkspaceRow]
                      .filter(_.id == lift(ws.id))
                      .update(
                        _.keyHash -> lift(WorkspaceKeyHash.fromSecret(newKey)),
                        _.createdAt -> lift(toOffsetDateTime(now)),
                        _.lastAccess -> lift(toOffsetDateTime(now))
                      )
                  )
                ).unit
    yield newKey

  private def resolveInternal(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    loadWorkspaceByKey(key)

  private def loadWorkspaceByKey(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    for
      row <- loadWorkspaceRowByHash(key)
      ws  <- toRecord(row)
      now <- Clock.instant
      _   <- ZIO.fail(WorkspaceExpired(key, ws.createdAt, ws.ttl)).when(ws.isExpired(now))
    yield ws

  private def loadWorkspaceRowByHash(key: WorkspaceKeySecret): IO[AppError, WorkspaceRow] =
    db(run(query[WorkspaceRow].filter(_.keyHash == lift(WorkspaceKeyHash.fromSecret(key))))).flatMap(rows =>
      ZIO.fromOption(rows.headOption).orElseFail(WorkspaceNotFound(key))
    )

  private def loadWorkspaceRowById(id: WorkspaceId): IO[AppError, WorkspaceRow] =
    db(run(query[WorkspaceRow].filter(_.id == lift(id)))).flatMap(rows =>
      ZIO.fromOption(rows.headOption).orElseFail(RepositoryFailure(s"Workspace id not found: ${id.value}"))
    )

  private def loadTrees(workspaceId: WorkspaceId): IO[AppError, Set[TreeId]] =
    db(
      run(
        query[WorkspaceTreeRow]
          .filter(_.workspaceId == lift(workspaceId))
          .map(_.treeId)
      )
    ).map(_.toSet)

  private def toRecord(row: WorkspaceRow): IO[AppError, WorkspaceRecord] =
    for
      trees       <- loadTrees(row.id)
      ttl         <- parseInterval(row.ttl)
      idleTimeout <- parseInterval(row.idleTimeout)
    yield WorkspaceRecord(
      id = row.id,
      keyHash = row.keyHash,
      trees = trees,
      createdAt = row.createdAt.toInstant,
      lastAccessedAt = row.lastAccess.toInstant,
      ttl = ttl,
      idleTimeout = idleTimeout
    )

  private def db[A](effect: Task[A]): IO[AppError, A] =
    effect.mapError(err => RepositoryFailure(Option(err.getMessage).getOrElse(err.toString)))

  private def parseInterval(value: String): IO[AppError, Duration] =
    ZIO.attempt {
      val pg = new org.postgresql.util.PGInterval(value)
      if pg.getYears != 0 || pg.getMonths != 0 then
        throw new IllegalArgumentException(s"Interval contains unsupported years/months component: $value")
      val totalMillis =
        (((pg.getDays.toLong * 24 + pg.getHours.toLong) * 60 + pg.getMinutes.toLong) * 60 * 1000L) +
          math.round(pg.getSeconds * 1000d)
      Duration.ofMillis(totalMillis)
    }.mapError(err => RepositoryFailure(Option(err.getMessage).getOrElse(err.toString)))

  private def toOffsetDateTime(instant: Instant): OffsetDateTime =
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

  private def toIntervalLiteral(duration: Duration): String =
    s"${duration.toMillis} milliseconds"

object WorkspaceStorePostgres:
  private final case class WorkspaceRow(
    id: WorkspaceId,
    keyHash: WorkspaceKeyHash,
    createdAt: OffsetDateTime,
    lastAccess: OffsetDateTime,
    ttl: String,
    idleTimeout: String
  )

  private final case class WorkspaceTreeRow(
    workspaceId: WorkspaceId,
    treeId: TreeId,
    addedAt: OffsetDateTime
  )

  val layer: ZLayer[Quill.Postgres[SnakeCase] & WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.fromZIO {
      for
        quill  <- ZIO.service[Quill.Postgres[SnakeCase]]
        config <- ZIO.service[WorkspaceConfig]
      yield WorkspaceStorePostgres(quill, config)
    }
