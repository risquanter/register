package com.risquanter.register.infra.irmin

import zio.prelude.{NonEmptyList, Validation}

import com.risquanter.register.domain.data.iron.{ScenarioName, TreeId, ValidationUtil, WorkspaceId}
import com.risquanter.register.domain.errors.{ErrorLocation, ExpectedInput, FieldProblem, ValidationError}
import com.risquanter.register.http.responses.{HistoryOperation, TreeWriteKind}

/** The message an Irmin commit carries, for every commit this server writes.
  * `render` and `parse` share each spelling, so the two cannot diverge, and
  * `parse(render(m)) == Right(m)` holds for every `m`. The spellings are the
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

  /** The message a stored commit carries, refined where it arrives (ADR-039).
    * Text that is not one of the three shapes `render` writes is refused as
    * the message the reader declares. A shape whose segments do not refine
    * accumulates each segment's own refusal, and a segment that refines to a
    * value `render` would spell differently is refused as declared, so that
    * `parse` stays the inverse of `render`.
    * {{{
    * parse("workspace:<ws>:risk-tree:<t>:update") // Right(TreeWritten(ws, t, Update))
    * parse("some external commit")                // Left(NonEmptyList(ValidationError(Whole, Unreadable(Declared))))
    * }}}
    */
  def parse(message: String): Either[NonEmptyList[ValidationError], CommitMessage] =
    val parsed: Validation[ValidationError, CommitMessage] =
      message.split(Separator, -1).toList match
        case WorkspaceTag :: ws :: TreeTag :: tree :: kind :: Nil =>
          Validation.validateWith(
            exact(ws)(WorkspaceId.fromString)(_.value),
            exact(tree)(TreeId.fromString)(_.value),
            Validation.fromOptionWith(unreadable)(kindsBySpelling.get(kind))
          )(TreeWritten.apply)
        case WorkspaceTag :: ws :: MergePrepTag :: name :: Nil =>
          scenarioMessage(ws, name, MergePrepared.apply)
        case WorkspaceTag :: ws :: MergedTag :: name :: Nil =>
          scenarioMessage(ws, name, ScenarioMerged.apply)
        case _ =>
          Validation.fail(unreadable)
    parsed.toEither.left.map(errors => NonEmptyList(errors.head, errors.tail*))

  private def scenarioMessage(
    ws: String,
    name: String,
    build: (WorkspaceId, ScenarioName.ScenarioName) => CommitMessage
  ): Validation[ValidationError, CommitMessage] =
    Validation.validateWith(
      exact(ws)(WorkspaceId.fromString)(_.value),
      exact(name)(ScenarioName.fromString)(_.value)
    )(build)

  /** The value a segment refines to, only where `render` spells that value as
    * the same segment. A constructor that normalises its input, as a scenario
    * name's lower-casing does, would otherwise accept spellings no commit
    * carries, and `parse` would stop being the inverse of `render`. */
  private def exact[A](segment: String)(refine: String => Either[List[ValidationError], A])(spell: A => String): Validation[ValidationError, A] =
    ValidationUtil.toValidation(refine(segment)).flatMap { value =>
      if spell(value) == segment then Validation.succeed(value) else Validation.fail(unreadable)
    }

  /** The whole message, or one of its segments, did not read as what the
    * convention declares there. */
  private val unreadable: ValidationError =
    ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(ExpectedInput.Declared))

  /** The operation a history entry shows for a commit. `None` for the
    * preparation write, which is part of the merge commit after it. */
  def historyOperation(message: CommitMessage): Option[HistoryOperation] =
    message match
      case TreeWritten(_, _, kind) => Some(HistoryOperation.TreeWrite(kind))
      case MergePrepared(_, _)     => None
      case ScenarioMerged(_, _)    => Some(HistoryOperation.Merge())
