package com.risquanter.register.services.workspace

import zio.*
import java.time.{Duration, Instant, OffsetDateTime, ZoneOffset}
import io.getquill.*
import io.getquill.jdbczio.Quill

import com.risquanter.register.configs.WorkspaceConfig
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, WorkspaceExpired, WorkspaceExpiredById, WorkspaceNotFound, WorkspaceNotFoundById}
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
               ttl = toIntervalString(config.ttl),
               idleTimeout = toIntervalString(config.idleTimeout)
             )
      _   <- db(
               run(
                 quote {
                   infix"""
                     INSERT INTO workspaces (id, key_hash, created_at, last_access, ttl, idle_timeout)
                     VALUES (${lift(row.id)}, ${lift(row.keyHash)}, ${lift(row.createdAt)}, ${lift(row.lastAccess)}, ${lift(row.ttl)}::interval, ${lift(row.idleTimeout)}::interval)
                   """.as[Action[Long]]
                 }
               )
             ).unit
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
      _   <- ZIO.fail(WorkspaceExpiredById(id, ws.createdAt, ws.ttl)).when(ws.isExpired(now))
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
      ZIO.fromOption(rows.headOption).orElseFail(WorkspaceNotFoundById(id))
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
    ZIO
      .fromEither(DurationString.parseInterval(value))
      .mapError(err => RepositoryFailure(err))

  private def toOffsetDateTime(instant: Instant): OffsetDateTime =
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

  private def toIntervalString(duration: Duration): String =
    DurationString.renderInterval(duration)

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

  private object DurationString:
    private val VerboseIntervalPattern =
      raw"([+-]?\d+) days ([+-]?\d+) hours ([+-]?\d+) minutes ([+-]?\d+(?:\.\d+)?) seconds".r
    private val DayTimeIntervalPattern =
      raw"(?:(-?\d+) day[s]? ?)?(-?\d{1,2}):([0-5]?\d):([0-5]?\d(?:\.\d+)?)".r
    private val DayOnlyIntervalPattern =
      raw"(-?\d+) day[s]?".r

    def renderInterval(duration: Duration): String =
      val totalSeconds = duration.getSeconds
      val days = Math.floorDiv(totalSeconds, 86400)
      val remAfterDays = Math.floorMod(totalSeconds, 86400)
      val hours = Math.floorDiv(remAfterDays, 3600)
      val remAfterHours = Math.floorMod(remAfterDays, 3600)
      val minutes = Math.floorDiv(remAfterHours, 60)
      val seconds = Math.floorMod(remAfterHours, 60).toDouble + duration.getNano.toDouble / 1_000_000_000d
      f"$days%d days $hours%d hours $minutes%d minutes $seconds%.9f seconds"

    def parseInterval(value: String): Either[String, Duration] =
      value match
        case VerboseIntervalPattern(days, hours, minutes, seconds) =>
          buildDuration(days, hours, minutes, seconds, value)
        case DayTimeIntervalPattern(days, hours, minutes, seconds) =>
          buildDuration(Option(days).getOrElse("0"), hours, minutes, seconds, value)
        case DayOnlyIntervalPattern(days) =>
          buildDuration(days, "0", "0", "0", value)
        case _ => Left(s"Unsupported interval format: $value")

    private def buildDuration(days: String, hours: String, minutes: String, seconds: String, original: String): Either[String, Duration] =
      scala.util.Try {
        val daysPart = days.toLong
        val hoursPart = hours.toLong
        val minutesPart = minutes.toLong
        val secondsPart = BigDecimal(seconds)
        val nanosPerSecond = BigDecimal(1000000000L)
        val wholeSeconds = secondsPart.setScale(0, BigDecimal.RoundingMode.DOWN).toLongExact
        val nanos = ((secondsPart - BigDecimal(wholeSeconds)) * nanosPerSecond)
          .setScale(0, BigDecimal.RoundingMode.HALF_UP)
          .toLongExact
        Duration
          .ofDays(daysPart)
          .plusHours(hoursPart)
          .plusMinutes(minutesPart)
          .plusSeconds(wholeSeconds)
          .plusNanos(nanos)
      }.toEither.left.map(err => Option(err.getMessage).getOrElse(s"Invalid interval: $original"))

  val layer: ZLayer[Quill.Postgres[SnakeCase] & WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.fromZIO {
      for
        quill  <- ZIO.service[Quill.Postgres[SnakeCase]]
        config <- ZIO.service[WorkspaceConfig]
      yield WorkspaceStorePostgres(quill, config)
    }
