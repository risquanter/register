package com.risquanter.register.services.workspace

import zio.*
import zio.prelude.{NonEmptyList, Validation}
import java.sql.SQLException
import java.time.{Duration, Instant, OffsetDateTime, ZoneOffset}
import io.getquill.*
import io.getquill.jdbczio.Quill
import org.postgresql.util.PSQLException

import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.configs.WorkspaceConfig
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeySecret, SeedEntityId, ValidationUtil}
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, ErrorSubject, ExpectedInput, Failure, FieldName, FieldProblem, OutageMode, PeerZone,
  TrustZone, ValidationError
}
import com.risquanter.register.http.errors.ConflictKind
import com.risquanter.register.infra.persistence.QuillMappings.given
import com.risquanter.register.services.workspace.WorkspaceStorePostgres.{
  ConnectionExceptionClass, DurationString, SeedEntityIdConstraint, UniqueViolation, WorkspaceRow, WorkspaceTreeRow, refineRow
}
import com.risquanter.register.util.IdGenerators

final class WorkspaceStorePostgres private (
  quill: Quill.Postgres[SnakeCase],
  config: WorkspaceConfig
) extends WorkspaceStore:

  import quill.*

  private inline given workspaceSchema: SchemaMeta[WorkspaceRow] =
    schemaMeta[WorkspaceRow](
      "workspaces",
      _.keyHash -> "key_hash",
      _.createdAt -> "created_at",
      _.lastAccess -> "last_access",
      _.idleTimeout -> "idle_timeout",
      _.seedEntityId -> "seed_entity_id"
    )

  private inline given workspaceTreeSchema: SchemaMeta[WorkspaceTreeRow] =
    schemaMeta[WorkspaceTreeRow](
      "workspace_trees",
      _.workspaceId -> "workspace_id",
      _.treeId -> "tree_id",
      _.addedAt -> "added_at"
    )

  /** seedEntityId: None lets the workspace_seed_entity_id_seq DEFAULT assign;
    * Some(v) inserts explicitly — the UNIQUE constraint rejects duplicates
    * race-free, as the caller's refusal of v, and the sequence is bumped past v
    * so later assignments cannot collide.
    */
  override def create(seedEntityId: Option[SeedEntityId.SeedEntityId]): IO[Failure, WorkspaceKeySecret] =
    for
      key <- WorkspaceKeyCrypto.generate
      sid <- IdGenerators.nextId.orDie
      now <- Clock.instant
      id       = WorkspaceId(sid)
      keyHash  = WorkspaceKeyCrypto.hash(key)
      created  = toOffsetDateTime(now)
      ttlS     = toIntervalString(config.ttl)
      idleS    = toIntervalString(config.idleTimeout)
      _ <- seedEntityId match
             case None =>
               db(
                 run(
                   quote {
                     infix"""
                       INSERT INTO workspaces (id, key_hash, created_at, last_access, ttl, idle_timeout)
                       VALUES (${lift(id)}, ${lift(keyHash)}, ${lift(created)}, ${lift(created)}, ${lift(ttlS)}::interval, ${lift(idleS)}::interval)
                     """.as[Action[Long]]
                   }
                 )
               ).unit
             case Some(entityId) =>
               dbRefusingSeedEntity(seedEntityIdInUse(entityId))(
                 run(
                   quote {
                     infix"""
                       INSERT INTO workspaces (id, key_hash, created_at, last_access, ttl, idle_timeout, seed_entity_id)
                       VALUES (${lift(id)}, ${lift(keyHash)}, ${lift(created)}, ${lift(created)}, ${lift(ttlS)}::interval, ${lift(idleS)}::interval, ${lift(entityId.value)})
                     """.as[Action[Long]]
                   }
                 )
               ).unit *> bumpSeedEntitySequence(entityId.value)
      _ <- logSecurity(WorkspaceStore.SecurityEvent.Created, WorkspaceStore.WorkspaceIdKey -> id.value)("Workspace created")
    yield key

  /** Advance the assignment sequence past a provided value (never backwards). */
  private def bumpSeedEntitySequence(value: Long): IO[Failure, Unit] =
    db(
      run(
        quote {
          infix"""
            SELECT setval('workspace_seed_entity_id_seq',
                          GREATEST((SELECT last_value FROM workspace_seed_entity_id_seq), ${lift(value)}))
          """.as[Query[Long]]
        }
      )
    ).unit

  /** Associate a tree with this workspace, refusing one beyond the tree ceiling.
    *
    * Counts and inserts in one transaction holding a row lock on the parent
    * `workspaces` row. The rows being counted do not exist yet, so the lock has
    * to be on the parent; it serialises concurrent associations for this
    * workspace. The refusal travels as a `Left` so the transaction commits,
    * keeping the typed error out of `db`'s classification of driver failures.
    */
  override def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using Checked[Authority]): IO[Failure, Unit] =
    for
      ws     <- resolveInternal(key)
      now    <- Clock.instant
      result <- db(transaction {
                  for
                    _        <- run(quote {
                                  infix"""SELECT 1 FROM workspaces WHERE id = ${lift(ws.id)} FOR UPDATE"""
                                    .as[Query[Int]]
                                })
                    existing <- run(query[WorkspaceTreeRow].filter(_.workspaceId == lift(ws.id.value)).map(_.treeId))
                    outcome  <- associate(ws.id, treeId, now, existing)
                  yield outcome
                })
      _      <- ZIO.fromEither(result)
    yield ()

  /** Already present: nothing to write. At the ceiling: the refusal. Otherwise
    * inserts the row. `existing` is the stored tree-id column, compared as the
    * text this service writes.
    */
  private def associate(wsId: WorkspaceId, treeId: TreeId, now: Instant, existing: Seq[String]) =
    (existing.contains(treeId.value), existing.size >= config.maxTreesPerWorkspace) match
      case (true, _) => ZIO.succeed(Right(()): Either[Failure, Unit])
      case (_, true) => ZIO.succeed(Left(WorkspaceStore.maxTreesReached(config.maxTreesPerWorkspace)))
      case _         =>
        run(query[WorkspaceTreeRow].insertValue(lift(WorkspaceTreeRow(wsId.value, treeId.value, toOffsetDateTime(now)))))
          .as(Right(()): Either[Failure, Unit])

  override def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using Checked[Authority]): IO[Failure, Unit] =
    for
      ws <- resolveInternal(key)
      _  <- db(
              run(
                query[WorkspaceTreeRow]
                  .filter(row => row.workspaceId == lift(ws.id.value) && row.treeId == lift(treeId.value))
                  .delete
              )
            ).unit
    yield ()

  override def listTrees(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, List[TreeId]] =
    resolveInternal(key).map(_.trees.toList)

  /** Refuse a creation that would exceed the tree ceiling, before the tree is
    * written.
    */
  override def checkTreeCapacity(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, Unit] =
    resolveInternal(key).flatMap(ws =>
      ZIO.fromEither(WorkspaceStore.treeCapacityForNew(ws, config.maxTreesPerWorkspace))
    )

  override def resolve(key: WorkspaceKeySecret): IO[Failure, WorkspaceRecord] =
    for
      ws  <- loadWorkspaceByKey(key)
      now <- Clock.instant
      _   <- db(
              run(
                query[WorkspaceRow]
                  .filter(_.id == lift(ws.id.value))
                  .update(_.lastAccess -> lift(toOffsetDateTime(now)))
              )
            ).unit
    yield ws.touch(now)

  override def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[Failure, Boolean] =
    resolveInternal(key).map(_.trees.contains(treeId))

  override def evictExpired: IO[Failure, List[WorkspaceRecord]] =
    for
      rows    <- db(run(query[WorkspaceRow]))
      now     <- Clock.instant
      evicted <- ZIO.foreach(rows.toList) { row =>
                   toRecord(row).either.map(_.toOption.filter(_.isExpired(now)))
                 }
      doomed   = evicted.flatten
      doomedIds = doomed.map(_.id)
      _       <- ZIO.foreachDiscard(doomedIds)(id =>
                   db(run(query[WorkspaceRow].filter(_.id == lift(id.value)).delete)).unit
                 )
      _       <- logSecurity(WorkspaceStore.SecurityEvent.Eviction, WorkspaceStore.EvictedCountKey -> doomed.size.toString)(
                   s"Evicted ${doomed.size} expired workspace(s)"
                 )
    yield doomed

  override def delete(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, Unit] =
    for
      ws <- resolveInternal(key)
      _  <- db(run(query[WorkspaceRow].filter(_.id == lift(ws.id.value)).delete)).unit
      _  <- logSecurity(WorkspaceStore.SecurityEvent.Deleted, WorkspaceStore.WorkspaceIdKey -> ws.id.value)("Workspace deleted")
    yield ()

  override def rotate(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, WorkspaceKeySecret] =
    for
      ws     <- resolveInternal(key)
      newKey <- WorkspaceKeyCrypto.generate
      now    <- Clock.instant
      _      <- db(
                  run(
                    query[WorkspaceRow]
                      .filter(_.id == lift(ws.id.value))
                      .update(
                        _.keyHash -> lift(WorkspaceKeyCrypto.hash(newKey).value: String),
                        _.createdAt -> lift(toOffsetDateTime(now)),
                        _.lastAccess -> lift(toOffsetDateTime(now))
                      )
                  )
                ).unit
      _      <- logSecurity(WorkspaceStore.SecurityEvent.Rotated, WorkspaceStore.WorkspaceIdKey -> ws.id.value)("Workspace key rotated")
    yield newKey

  private def resolveInternal(key: WorkspaceKeySecret): IO[Failure, WorkspaceRecord] =
    loadWorkspaceByKey(key)

  private def loadWorkspaceByKey(key: WorkspaceKeySecret): IO[Failure, WorkspaceRecord] =
    for
      row <- loadWorkspaceRowByHash(key)
      ws  <- toRecord(row)
      now <- Clock.instant
      // The record answers which deadline was passed, so the failure can carry
      // that moment for the log line without restating the two-deadline rule.
      _   <- ZIO.foreachDiscard(ws.expiry(now))(e => ZIO.fail(WorkspaceStore.workspaceExpired(ws, e)))
    yield ws

  private def loadWorkspaceRowByHash(key: WorkspaceKeySecret): IO[Failure, WorkspaceRow] =
    db(run(query[WorkspaceRow].filter(_.keyHash == lift(WorkspaceKeyCrypto.hash(key).value: String)))).flatMap(rows =>
      ZIO.fromOption(rows.headOption).orElseFail(WorkspaceStore.workspaceNotFound)
    )

  /** The stored tree-id column of one workspace, as text; `toRecord` refines it. */
  private def loadTrees(workspaceId: String): IO[Failure, List[String]] =
    db(
      run(
        query[WorkspaceTreeRow]
          .filter(_.workspaceId == lift(workspaceId))
          .map(_.treeId)
      )
    )

  /** The workspace a row describes, with every column refined and every refusal
    * across them accumulated. A column that does not refine is the database's
    * content refused, since this service writes only refined values (ADR-039). */
  private def toRecord(row: WorkspaceRow): IO[Failure, WorkspaceRecord] =
    loadTrees(row.id).flatMap(treeIds =>
      ZIO.fromEither(refineRow(row, treeIds).toEither).mapError(refused =>
        Failure.rejected(
          TrustZone.Peer(PeerZone.Database),
          NonEmptyList(refused.head._1, refused.tail.map(_._1)*),
          refused.toList.map(_._2)
        )
      )
    )

  /** The caller's refusal of a seed entity id another workspace holds. */
  private def seedEntityIdInUse(entityId: SeedEntityId.SeedEntityId): Failure =
    Failure.rejected(
      TrustZone.Client,
      NonEmptyList(ValidationError(ErrorLocation.Field(ErrorSubject.Workspace, FieldName.SeedEntityId), FieldProblem.SeedEntityIdInUse(entityId))),
      Nil
    )

  /** Runs a driver call; every driver failure is classified. */
  private def db[A](effect: Task[A]): IO[Failure, A] =
    effect.mapError(classify)

  /** Runs a driver call whose unique violation on the seed-entity constraint is
    * `inUse`, the caller's refusal. Every other driver failure is classified.
    */
  private def dbRefusingSeedEntity[A](inUse: Failure)(effect: Task[A]): IO[Failure, A] =
    effect.mapError(err =>
      violatedConstraint(err) match
        case Some(SeedEntityIdConstraint) => inUse
        case _                            => classify(err)
    )

  /** A driver failure as the database's answer. A unique violation is a
    * conflict with stored state. A connection-exception state is a refused or
    * dropped connection. Any other failure is an answer outside the protocol's
    * shape.
    */
  private def classify(err: Throwable): Failure =
    sqlState(err) match
      case Some(UniqueViolation) =>
        Failure.Conflict(Core(
          TrustZone.Peer(PeerZone.Database),
          NonEmptyList(ErrorLocation.Whole -> ConflictKind.Rejected()),
          List(Diagnostic.PeerMessage(messageOf(err)))
        ))
      case Some(state) if state.startsWith(ConnectionExceptionClass) => unanswered(OutageMode.ConnectionRefused, err)
      case _                                                         => unanswered(OutageMode.Protocol, err)

  private def unanswered(mode: OutageMode, err: Throwable): Failure =
    Failure.Unanswered(Core(
      PeerZone.Database,
      NonEmptyList((ErrorLocation.Whole, mode)),
      List(Diagnostic.PeerMessage(messageOf(err)))
    ))

  /** The SQL state a JDBC driver attaches to its failure, when it attaches one. */
  private def sqlState(err: Throwable): Option[String] = err match
    case e: SQLException => Option(e.getSQLState)
    case _               => None

  /** The constraint a unique violation names, when the Postgres driver reports
    * one. */
  private def violatedConstraint(err: Throwable): Option[String] = err match
    case e: PSQLException if sqlState(e).contains(UniqueViolation) =>
      Option(e.getServerErrorMessage).flatMap(message => Option(message.getConstraint))
    case _ => None

  private def messageOf(err: Throwable): String =
    Option(err.getMessage).getOrElse(err.toString)

  /** Structured security event log with arbitrary key-value annotations. */
  private def logSecurity(event: WorkspaceStore.SecurityEvent, fields: (String, String)*)(msg: String): UIO[Unit] =
    val allAnnotations = (WorkspaceStore.EventTypeKey -> event.eventType) +: fields
    allAnnotations.foldRight(ZIO.logInfo(msg): UIO[Unit]) { case ((k, v), effect) =>
      ZIO.logAnnotate(k, v)(effect)
    }

  private def toOffsetDateTime(instant: Instant): OffsetDateTime =
    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

  private def toIntervalString(duration: Duration): String =
    DurationString.renderInterval(duration)

object WorkspaceStorePostgres:
  /** The SQL standard's state for a unique-constraint violation. */
  private val UniqueViolation: String = "23505"

  /** The SQL standard's state class for a connection exception: the database
    * was not reached, or the connection dropped. */
  private val ConnectionExceptionClass: String = "08"

  /** The name Postgres gives the `UNIQUE` that migration V2 declares on
    * `workspaces.seed_entity_id`: its default `<table>_<column>_key`. */
  private val SeedEntityIdConstraint: String = "workspaces_seed_entity_id_key"

  /** A `workspaces` row as the database returns it. The identifier and
    * key-hash columns are text, so `toRecord` refines them with the rest. */
  private final case class WorkspaceRow(
    id: String,
    keyHash: String,
    createdAt: OffsetDateTime,
    lastAccess: OffsetDateTime,
    ttl: String,
    idleTimeout: String,
    seedEntityId: Long
  )

  /** A `workspace_trees` row as the database returns it. */
  private final case class WorkspaceTreeRow(
    workspaceId: String,
    treeId: String,
    addedAt: OffsetDateTime
  )

  /** The record a row describes, each refusal paired with the refused column's
    * text for the log. */
  private def refineRow(row: WorkspaceRow, treeIds: List[String]): Validation[(ValidationError, Diagnostic), WorkspaceRecord] =
    Validation.validateWith(
      column(row.id)(ValidationUtil.refineId(_, at(FieldName.Id)).map(WorkspaceId(_))),
      column(row.keyHash)(ValidationUtil.refineWorkspaceKeyHash(_, at(FieldName.WorkspaceKeyHash))),
      Validation.validateAll(treeIds.map(treeId => column(treeId)(ValidationUtil.refineTreeId(_, at(FieldName.Trees))))),
      column(row.ttl)(DurationString.parseInterval(_, at(FieldName.Ttl)).left.map(_.toList)),
      column(row.idleTimeout)(DurationString.parseInterval(_, at(FieldName.IdleTimeout)).left.map(_.toList)),
      column(row.seedEntityId)(ValidationUtil.refineSeedEntityId(_, at(FieldName.SeedEntityId)))
    )((id, keyHash, trees, ttl, idleTimeout, entityId) =>
      WorkspaceRecord(
        id = id,
        keyHash = keyHash,
        trees = trees.toSet,
        createdAt = row.createdAt.toInstant,
        lastAccessedAt = row.lastAccess.toInstant,
        ttl = ttl,
        idleTimeout = idleTimeout,
        seedEntityId = entityId
      )
    )

  private def at(field: FieldName): ErrorLocation = ErrorLocation.Field(ErrorSubject.Workspace, field)

  /** One column refined, each refusal carrying the column's text. */
  private def column[V, A](raw: V)(refine: V => Either[List[ValidationError], A]): Validation[(ValidationError, Diagnostic), A] =
    ValidationUtil.toValidation(refine(raw)).mapError(_ -> Diagnostic.RawValue(raw.toString))

  private object DurationString:
    private val VerboseIntervalPattern =
      raw"([+-]?\d+) days ([+-]?\d+) hours ([+-]?\d+) minutes ([+-]?\d+(?:\.\d+)?) seconds".r
    private val DayTimeIntervalPattern =
      raw"(?:(-?\d+) day[s]? ?)?(-?\d{1,2}):([0-5]?\d):([0-5]?\d(?:\.\d+)?)".r
    private val DayOnlyIntervalPattern =
      raw"(-?\d+) day[s]?".r

    /** Fixed by the calendar: a Postgres interval has no leap seconds. */
    private val SecondsPerDay: Long    = 86400L
    private val SecondsPerHour: Long   = 3600L
    private val SecondsPerMinute: Long = 60L
    private val NanosPerSecond: Long   = 1_000_000_000L

    def renderInterval(duration: Duration): String =
      val totalSeconds = duration.getSeconds
      val days = Math.floorDiv(totalSeconds, SecondsPerDay)
      val remAfterDays = Math.floorMod(totalSeconds, SecondsPerDay)
      val hours = Math.floorDiv(remAfterDays, SecondsPerHour)
      val remAfterHours = Math.floorMod(remAfterDays, SecondsPerHour)
      val minutes = Math.floorDiv(remAfterHours, SecondsPerMinute)
      val seconds = Math.floorMod(remAfterHours, SecondsPerMinute).toDouble + duration.getNano.toDouble / NanosPerSecond.toDouble
      f"$days%d days $hours%d hours $minutes%d minutes $seconds%.9f seconds"

    /** A Postgres interval's text as a duration, or one refusal at `location`
      * when the text is in none of the three forms Postgres renders. */
    def parseInterval(value: String, location: ErrorLocation): Either[NonEmptyList[ValidationError], Duration] =
      val parsed = value match
        case VerboseIntervalPattern(days, hours, minutes, seconds) =>
          buildDuration(days, hours, minutes, seconds)
        case DayTimeIntervalPattern(days, hours, minutes, seconds) =>
          buildDuration(Option(days).getOrElse("0"), hours, minutes, seconds)
        case DayOnlyIntervalPattern(days) =>
          buildDuration(days, "0", "0", "0")
        case _ => None
      parsed.toRight(NonEmptyList(ValidationError(location, FieldProblem.Unreadable(ExpectedInput.Interval))))

    private def buildDuration(days: String, hours: String, minutes: String, seconds: String): Option[Duration] =
      (days.toLongOption, hours.toLongOption, minutes.toLongOption) match
        case (Some(daysPart), Some(hoursPart), Some(minutesPart)) =>
          // Named coverage (ADR-033 §3): toLongExact / Duration.plus* overflow →
          // ArithmeticException; BigDecimal(seconds) → NumberFormatException
          // (regex-constrained input, kept for sound coverage).
          try
            val secondsPart = BigDecimal(seconds)
            val wholeSeconds = secondsPart.setScale(0, BigDecimal.RoundingMode.DOWN).toLongExact
            val nanos = ((secondsPart - BigDecimal(wholeSeconds)) * BigDecimal(NanosPerSecond))
              .setScale(0, BigDecimal.RoundingMode.HALF_UP)
              .toLongExact
            Some(
              Duration
                .ofDays(daysPart)
                .plusHours(hoursPart)
                .plusMinutes(minutesPart)
                .plusSeconds(wholeSeconds)
                .plusNanos(nanos)
            )
          catch case _: ArithmeticException | _: NumberFormatException => None
        case _ => None

  val layer: ZLayer[Quill.Postgres[SnakeCase] & WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.fromZIO {
      for
        quill  <- ZIO.service[Quill.Postgres[SnakeCase]]
        config <- ZIO.service[WorkspaceConfig]
      yield WorkspaceStorePostgres(quill, config)
    }
