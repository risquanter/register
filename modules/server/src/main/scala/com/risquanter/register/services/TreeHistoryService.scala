package com.risquanter.register.services

import zio.*
import java.time.Instant
import scala.util.Try
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, PositiveInt, CommitHash}
import com.risquanter.register.domain.errors.{RepositoryFailure, IrminError}
import com.risquanter.register.http.responses.{TreeHistoryEntry, HistoryOperation}
import com.risquanter.register.infra.irmin.{IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminCommit}

/** Per-tree commit history on a branch (E1). Returns typed entries; the raw
  * commit message (which embeds the WorkspaceId) and author never leave the
  * service — only the parsed `HistoryOperation`, the commit hash, and the
  * timestamp cross the boundary.
  */
trait TreeHistoryService:
  def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Permission]): Task[List[TreeHistoryEntry]]

final case class TreeHistoryServiceLive(irmin: IrminClient) extends TreeHistoryService:

  override def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Permission]): Task[List[TreeHistoryEntry]] =
    val path = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeRoot(wsId, treeId))
    handleIrmin(irmin.getHistory(path, limit, branch)).flatMap(commits => ZIO.foreach(commits)(toEntry))

  private def toEntry(commit: IrminCommit): Task[TreeHistoryEntry] =
    ZIO.fromEither(CommitHash.fromString(commit.hash))
      .mapBoth(
        errs => RepositoryFailure(s"Invalid commit hash in history: ${errs.map(_.message).mkString("; ")}"),
        hash => TreeHistoryEntry(hash, TreeHistoryServiceLive.parseOperation(commit.info.message), TreeHistoryServiceLive.toIso(commit.info.date))
      )

  private def handleIrmin[A](effect: IO[IrminError, A]): Task[A] =
    effect.mapError { err =>
      val reason = Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.toString)
      RepositoryFailure(reason)
    }

object TreeHistoryService:
  /** In-memory backend variant: there is no persistent commit log, so history
    * is always empty (DD-9 — history UI is disabled on the in-memory backend
    * anyway). A read with no data, not an error, unlike scenario operations. */
  val empty: TreeHistoryService = new TreeHistoryService:
    def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
      (using Checked[Permission]): Task[List[TreeHistoryEntry]] =
      ZIO.succeed(Nil)

object TreeHistoryServiceLive:
  val layer: ZLayer[IrminClient, Nothing, TreeHistoryService] =
    ZLayer.fromFunction(TreeHistoryServiceLive.apply)

  /** Map the commit-message convention to a typed operation; anything
    * unrecognized is `Other`. Merge commits carry `:merge-scenario:` in the
    * middle, the tree mutations carry the operation as the final `:` segment. */
  def parseOperation(message: String): HistoryOperation = message match
    case m if m.contains(":merge-scenario:") => HistoryOperation.Merge
    case m if m.endsWith(":create")          => HistoryOperation.Create
    case m if m.endsWith(":update")          => HistoryOperation.Update
    case m if m.endsWith(":delete")          => HistoryOperation.Delete
    case m if m.endsWith(":revert")          => HistoryOperation.Revert
    case _                                   => HistoryOperation.Other

  /** Irmin renders the commit date as an epoch-seconds string; normalize to
    * ISO-8601. If it is already non-numeric (a future Irmin change), pass it
    * through unchanged rather than failing the whole history read. */
  def toIso(date: String): String =
    Try(Instant.ofEpochSecond(date.trim.toLong).toString).getOrElse(date)
