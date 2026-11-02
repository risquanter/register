package com.risquanter.register.infra.irmin

import com.risquanter.register.domain.data.iron.{ScenarioName, TreeId, WorkspaceId}
import com.risquanter.register.http.responses.{HistoryOperation, TreeWriteKind}

/** The message an Irmin commit carries, for every commit this server writes.
  * `render` and `parse` share each spelling, so the two cannot diverge, and
  * `parse(render(m)) == Some(m)` holds for every `m`. The spellings are the
  * ones already in the store, so earlier commits parse.
  *
  * {{{
  * render(TreeWritten(ws, t, TreeWriteKind.Update)) // "workspace:<ws>:risk-tree:<t>:update"
  * render(ScenarioMerged(ws, s))                    // "workspace:<ws>:merge-scenario:<s>"
  * render(MergePrepared(ws, s))                     // "workspace:<ws>:merge-prepare:<s>"
  * }}}
  */
enum CommitMessage:
  case TreeWritten(wsId: WorkspaceId, treeId: TreeId, kind: TreeWriteKind)
  case MergePrepared(wsId: WorkspaceId, scenario: ScenarioName.ScenarioName)
  case ScenarioMerged(wsId: WorkspaceId, scenario: ScenarioName.ScenarioName)

object CommitMessage:
  /** The segments of the convention, each spelled once for both directions. */
  private val Separator      = ":"
  private val WorkspaceTag   = "workspace"
  private val TreeTag        = "risk-tree"
  private val MergePrepTag   = "merge-prepare"
  private val MergedTag      = "merge-scenario"

  /** Declared rather than derived from the case names: the lower-case
    * spellings are in commits already stored, so they must not follow a
    * rename of a case (ADR-037). */
  private def kindSpelling(kind: TreeWriteKind): String = kind match
    case TreeWriteKind.Create => "create"
    case TreeWriteKind.Update => "update"
    case TreeWriteKind.Delete => "delete"
    case TreeWriteKind.Revert => "revert"

  private val kindsBySpelling: Map[String, TreeWriteKind] =
    TreeWriteKind.values.map(k => kindSpelling(k) -> k).toMap

  def render(message: CommitMessage): String =
    val segments = message match
      case TreeWritten(wsId, treeId, kind) =>
        List(WorkspaceTag, wsId.value, TreeTag, treeId.value, kindSpelling(kind))
      case MergePrepared(wsId, scenario) =>
        List(WorkspaceTag, wsId.value, MergePrepTag, scenario.value)
      case ScenarioMerged(wsId, scenario) =>
        List(WorkspaceTag, wsId.value, MergedTag, scenario.value)
    segments.mkString(Separator)

  /** `None` for a message this server did not write, or one whose identifier
    * segments do not refine. */
  def parse(message: String): Option[CommitMessage] =
    message.split(Separator, -1).toList match
      case WorkspaceTag :: ws :: TreeTag :: tree :: kind :: Nil =>
        for
          wsId   <- exact(ws)(WorkspaceId.fromString)(_.value)
          treeId <- exact(tree)(TreeId.fromString)(_.value)
          k      <- kindsBySpelling.get(kind)
        yield TreeWritten(wsId, treeId, k)
      case WorkspaceTag :: ws :: MergePrepTag :: name :: Nil =>
        scenarioMessage(ws, name, MergePrepared.apply)
      case WorkspaceTag :: ws :: MergedTag :: name :: Nil =>
        scenarioMessage(ws, name, ScenarioMerged.apply)
      case _ =>
        None

  private def scenarioMessage(
    ws: String,
    name: String,
    build: (WorkspaceId, ScenarioName.ScenarioName) => CommitMessage
  ): Option[CommitMessage] =
    for
      wsId     <- exact(ws)(WorkspaceId.fromString)(_.value)
      scenario <- exact(name)(ScenarioName.fromString)(_.value)
    yield build(wsId, scenario)

  /** The value a segment refines to, only where `render` spells that value as
    * the same segment. A constructor that normalises its input, as a scenario
    * name's lower-casing does, would otherwise accept spellings no commit
    * carries, and `parse` would stop being the inverse of `render`. */
  private def exact[A](segment: String)(refine: String => Either[?, A])(spell: A => String): Option[A] =
    refine(segment).toOption.filter(a => spell(a) == segment)

  /** The operation a history entry shows for a commit. `None` for the
    * preparation write, which is part of the merge commit after it. A message
    * this server did not write is `Other`. */
  def historyOperation(message: Option[CommitMessage]): Option[HistoryOperation] =
    message match
      case Some(TreeWritten(_, _, kind)) => Some(HistoryOperation.TreeWrite(kind))
      case Some(MergePrepared(_, _))     => None
      case Some(ScenarioMerged(_, _))    => Some(HistoryOperation.Merge())
      case None                          => Some(HistoryOperation.Other())
