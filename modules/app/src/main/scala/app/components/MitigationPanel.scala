package app.components

import com.raquo.laminar.api.L.*

import app.state.MitigationReadout
import com.risquanter.register.domain.data.{Mitigation, MitigationSpec, MitigationTarget}
import com.risquanter.register.domain.data.iron.MitigationId
import com.risquanter.register.http.errors.BindProblem
import com.risquanter.register.http.responses.TreeStructureResponse

/** What a tree card shows of one mitigation beside its boxes: its name and
  * stage, a warning when its predicate does not bind, and the stale badge on
  * an absolute statement whose leaf has changed.
  *
  * Stateless (ADR-019 Pattern 5): it holds no selection and creates no `Var`.
  */
object MitigationPanel:

  /** What the stale badge offers where it is shown. */
  enum StaleBadgeAction:
    /** Opens the popup, disabled while `blockedBy` holds a reason. */
    case OpensPopup(open: MitigationId => Unit, blockedBy: Signal[List[String]])
    /** A comparand slot or a rewound baseline: nothing to open here. */
    case ReadOnly
    /** A Design list row, whose Edit button opens the popup. */
    case EditInPlace

  /** The words naming a mitigation's stage. */
  private def stageLabel(mitigation: Mitigation): String = mitigation.spec match
    case _: MitigationSpec.LeafRelative | _: MitigationSpec.LeafAbsolute => "parameter stage"
    case _: MitigationSpec.ResultStage                                   => "result stage"

  /** The predicate's source, then one line per node it names with that node's
    * name in this tree version, or a mark that the tree has no such node.
    *
    * {{{
    * // descendant_of(x, node_id("01HX…"))
    * // 01HX… = IT
    * }}}
    */
  private def predicateTooltip(response: TreeStructureResponse, mitigation: Mitigation): String =
    val source: String = mitigation.target match
      case MitigationTarget.Predicate(predicate) => predicate.source
    val named = MitigationReadout.namedNodes(response, mitigation.id).distinct.map { (id, name) =>
      s"${id.value} = ${name.fold(NotInThisTree)(_.value)}"
    }
    (source :: named).mkString("\n")

  /** The warning's tooltip: one sentence per binding problem. */
  private def bindTooltip(problems: Iterable[BindProblem]): String =
    problems.map(BindProblem.describe).mkString("\n")

  /** A mitigation's name, stage, binding warning and stale badge, with its
    * predicate as tooltip: the name cell of its line in a card's grid. The
    * tooltip shows the predicate's source and each node it names with that
    * node's name in this tree version. */
  def nameCell(response: TreeStructureResponse, mitigation: Mitigation, staleAction: StaleBadgeAction): HtmlElement =
    val id = mitigation.id
    val stale = response.staleMitigationIds.contains(id)
    div(
      cls := "mitigation-name-cell",
      title := predicateTooltip(response, mitigation),
      span(cls := "mitigation-name", mitigation.name.value),
      span(cls := "mitigation-stage", stageLabel(mitigation)),
      MitigationReadout.failuresOf(response, id).map { problems =>
        span(cls := "mitigation-warning", title := bindTooltip(problems), WarningMark)
      },
      Option.when(stale)(staleBadge(id, staleAction))
    )

  /** The stale badge: one label everywhere, and a tooltip naming what can be
    * done about it where it is shown. A link waiting on a reason is disabled,
    * and hovering over it shows the reason. */
  def staleBadge(id: MitigationId, action: StaleBadgeAction): HtmlElement =
    action match
      case StaleBadgeAction.OpensPopup(open, blockedBy) =>
        FormInputs.blockable(
          button(
            cls := "stale-badge stale-badge--link",
            tpe := "button",
            title := StaleOpensPopup,
            StaleLabel,
            onClick.stopPropagation --> (_ => open(id))
          ),
          blockedBy
        )
      case StaleBadgeAction.ReadOnly =>
        span(cls := "stale-badge", title := StaleReadOnly, StaleLabel)
      case StaleBadgeAction.EditInPlace =>
        span(cls := "stale-badge", title := StaleEditInPlace, StaleLabel)

  /** What the predicate tooltip shows for a named node the tree does not hold. */
  private val NotInThisTree = "(not in this tree)"

  /** The mark on a line whose predicate does not bind. */
  private val WarningMark = "⚠"

  /** The badge's text. */
  private val StaleLabel = "stale"

  /** What every tooltip of the badge opens with. */
  private val StaleStatement = "The leaf this statement was written against has changed."

  /** The badge's tooltip where it links to the popup. */
  private val StaleOpensPopup =
    s"$StaleStatement Click to open it in the Design view on this branch and review its figures."

  /** The badge's tooltip where it does not link. */
  private val StaleReadOnly =
    s"$StaleStatement Review it from the baseline card at the head of its branch."

  /** The badge's tooltip on a Design list row. */
  private val StaleEditInPlace =
    s"$StaleStatement Use Edit to review its figures."
