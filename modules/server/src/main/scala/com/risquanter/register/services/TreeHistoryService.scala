package com.risquanter.register.services

import zio.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Greater
import java.time.{DateTimeException, Instant}
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, PositiveInt, CommitHash}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, IrminError}
import com.risquanter.register.http.responses.TreeHistoryEntry
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.model.IrminCommit

/** Per-tree commit history on a branch. Returns typed entries; the raw
  * commit message (which embeds the WorkspaceId) and author never leave the
  * service — only the `HistoryOperation`, the commit hash, and the timestamp
  * cross the boundary.
  */
trait TreeHistoryService:
  def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Permission]): IO[AppError, List[TreeHistoryEntry]]

final case class TreeHistoryServiceLive(irmin: IrminClient) extends TreeHistoryService:

  override def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Permission]): IO[AppError, List[TreeHistoryEntry]] =
    // History is read from the tree's meta contents: every write (create,
    // update, revert, and a merge's final write) rewrites meta with a fresh
    // updatedAt, so its last_modified log is the tree's full commit history.
    // The tree root holds no contents, so last_modified on it returns nothing.
    // Irmin lists the newest commits oldest first, and lists a commit once for
    // every path it is reached by, so one reachable through both parents of a
    // merge commit appears twice. The entries are the newest `limit` distinct
    // commits the history shows, oldest first.
    val path = WorkspaceStoragePaths.treeMeta(wsId, treeId)
    handleIrmin(irmin.getHistory(path, TreeHistoryServiceLive.requested(limit), branch))
      .flatMap(commits => ZIO.foreach(commits.distinctBy(_.hash))(toEntry))
      .map(_.flatten.takeRight(limit))

  /** The entry for one commit, or `None` for a commit the history does not
    * show: a merge's preparation write is part of the merge commit after it. */
  private def toEntry(commit: IrminCommit): IO[AppError, Option[TreeHistoryEntry]] =
    CommitMessage.historyOperation(CommitMessage.parse(commit.info.message)) match
      case None => ZIO.none
      case Some(operation) =>
        ZIO.fromEither(CommitHash.fromString(commit.hash))
          .mapBoth(
            errs => RepositoryFailure(s"Invalid commit hash in history: ${errs.map(_.message).mkString("; ")}"),
            hash => Some(TreeHistoryEntry(hash, operation, TreeHistoryServiceLive.toIso(commit.info.date)))
          )

  private def handleIrmin[A](effect: IO[IrminError, A]): IO[AppError, A] =
    effect.mapError(err => RepositoryFailure(err.logMessage))

object TreeHistoryService:
  /** In-memory backend variant: there is no persistent commit log, so history
    * is always empty, and the history interface is disabled on that backend
    * anyway. A read with no data, not an error, unlike scenario operations. */
  val empty: TreeHistoryService = new TreeHistoryService:
    def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
      (using Checked[Permission]): IO[AppError, List[TreeHistoryEntry]] =
      ZIO.succeed(Nil)

object TreeHistoryServiceLive:
  val layer: ZLayer[IrminClient, Nothing, TreeHistoryService] =
    ZLayer.fromFunction(TreeHistoryServiceLive.apply)

  /** How many commits to ask Irmin for: twice `limit`, saturating at the
    * largest `Int`. Irmin's `last_modified` stops at `n` commits, and a merge's
    * preparation commit and a commit listed twice both count toward `n`, so
    * fewer than `limit` entries can remain.
    *
    * {{{
    * requested(50) // 100
    * }}}
    */
  private[services] def requested(limit: PositiveInt): PositiveInt =
    (limit.toLong * 2).min(Int.MaxValue.toLong).toInt.refineOption[Greater[0]].getOrElse(limit)

  /** Irmin renders the commit date as an epoch-seconds string; normalize to
    * ISO-8601. If it is already non-numeric (a future Irmin change) or outside
    * `Instant`'s range, pass it through unchanged rather than failing the
    * whole history read. */
  def toIso(date: String): String =
    date.trim.toLongOption.fold(date) { epoch =>
      try Instant.ofEpochSecond(epoch).toString
      catch case _: DateTimeException => date
    }
