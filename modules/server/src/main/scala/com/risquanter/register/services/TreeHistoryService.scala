package com.risquanter.register.services

import zio.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Greater
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, PositiveInt}
import com.risquanter.register.domain.errors.{AppError, Failure}
import com.risquanter.register.http.responses.TreeHistoryEntry
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.model.IrminCommit

/** Per-tree commit history on a branch. Returns typed entries; the commit
  * message (which embeds the WorkspaceId) never leaves the service — only the
  * `HistoryOperation`, the commit hash, and the timestamp cross the boundary.
  */
trait TreeHistoryService:
  def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Authority]): IO[AppError | Failure, List[TreeHistoryEntry]]

final case class TreeHistoryServiceLive(irmin: IrminClient) extends TreeHistoryService:

  override def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Authority]): IO[AppError | Failure, List[TreeHistoryEntry]] =
    // History is read from the tree's meta contents: every write (create,
    // update, revert, and a merge's final write) rewrites meta with a fresh
    // updatedAt, so its last_modified log is the tree's full commit history.
    // The tree root holds no contents, so last_modified on it returns nothing.
    // Irmin lists the newest commits oldest first, and lists a commit once for
    // every path it is reached by, so one reachable through both parents of a
    // merge commit appears twice. The entries are the newest `limit` distinct
    // commits the history shows, oldest first.
    val path = WorkspaceStoragePaths.treeMeta(wsId, treeId)
    irmin.getHistory(path, TreeHistoryServiceLive.requested(limit), branch)
      .map(commits => commits.distinctBy(_.hash).flatMap(toEntry).takeRight(limit))

  /** The entry for one commit, or `None` for a commit the history does not
    * show: a merge's preparation write is part of the merge commit after it. */
  private def toEntry(commit: IrminCommit): Option[TreeHistoryEntry] =
    CommitMessage.historyOperation(commit.info.message).map(operation =>
      TreeHistoryEntry(commit.hash, operation, commit.info.date.toString))

object TreeHistoryService:
  /** In-memory backend variant: there is no persistent commit log, so history
    * is always empty, and the history interface is disabled on that backend
    * anyway. A read with no data, not an error, unlike scenario operations. */
  val empty: TreeHistoryService = new TreeHistoryService:
    def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
      (using Checked[Authority]): IO[AppError | Failure, List[TreeHistoryEntry]] =
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
