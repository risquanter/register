package app.components

import com.raquo.laminar.api.L.*

import app.core.NumberFormat
import app.state.AbsoluteStatementRow
import com.risquanter.register.domain.data.{AbsoluteReplacement, DistributionShape}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, SafeName}

/** The loaded tree's absolute statements, under the Design view's tree
  * preview. Each row shows the statement's name, its anchor's name in this
  * tree or its identifier, its figures, the stale badge when the structure
  * read lists it, a mark when it is affirmed and unsaved, and an edit button.
  *
  * Stateless (ADR-019 Pattern 5): the rows are the builder's, and the edit
  * button reports the statement through `onEdit`. The list is locked while a
  * node form is mid-edit, as the forms lock each other.
  */
object AbsoluteStatementList:

  def apply(
    rows:     Signal[List[AbsoluteStatementRow]],
    stale:    Signal[Set[MitigationId]],
    nodeName: Signal[NodeId => Option[SafeName.SafeName]],
    onEdit:   MitigationId => Unit,
    locked:   Signal[Boolean]
  ): HtmlElement =
    div(
      cls := "absolute-statement-list",
      children <-- rows.combineWith(stale, nodeName).map { (rs, staleIds, name) =>
        rs match
          case Nil => Nil
          case _ =>
            h4(cls := "absolute-statement-title", "Absolute statements") ::
              rs.map(row => renderRow(row, staleIds.contains(row.mitigation.id), name(row.anchor), onEdit, locked))
      }
    )

  /** A statement's figures in one line: the probability as a percentage, the
    * loss distribution by its mode's own figures, or both.
    *
    * {{{
    * figuresText(Likelihood(0.02))                     // likelihood 2%
    * figuresText(Severity(Lognormal(1000, 5000)))      // loss 1000–5000
    * }}}
    */
  private[app] def figuresText(figures: AbsoluteReplacement): String = figures match
    case AbsoluteReplacement.Likelihood(p) => likelihoodText(p.toFraction)
    case AbsoluteReplacement.Severity(s)   => shapeText(s)
    case AbsoluteReplacement.Both(p, s)    => s"${likelihoodText(p.toFraction)}, ${shapeText(s)}"

  /** A probability's display precision, in decimal places of a percentage.
    * Chosen by this project to match the leaf form's entry precision. */
  private val PercentDecimals: Int = 2

  private def likelihoodText(p: Double): String =
    s"likelihood ${NumberFormat.percentValue(p, PercentDecimals)}%"

  private def shapeText(shape: DistributionShape): String = shape match
    case DistributionShape.Lognormal(bounds) =>
      s"loss ${bounds.min: Long}–${bounds.max: Long}"
    case DistributionShape.Metalog(anchors, _) =>
      s"loss by ${anchors.values.size} expert estimates"

  private def renderRow(
    row:        AbsoluteStatementRow,
    stale:      Boolean,
    anchorName: Option[SafeName.SafeName],
    onEdit:     MitigationId => Unit,
    locked:     Signal[Boolean]
  ): HtmlElement =
    val id = row.mitigation.id
    div(
      cls := "absolute-statement-row",
      span(cls := "absolute-statement-name", row.mitigation.name.value),
      span(cls := "absolute-statement-anchor", anchorName.fold(row.anchor.value)(_.value)),
      span(cls := "absolute-statement-figures", figuresText(row.figures)),
      Option.when(stale)(MitigationPanel.staleBadge(id, MitigationPanel.StaleBadgeAction.EditInPlace)),
      Option.when(row.affirmed)(span(cls := "absolute-statement-affirmed", title := AffirmedTooltip, "●")),
      button(
        cls := "btn btn-small absolute-statement-edit",
        tpe := "button",
        disabled <-- locked,
        "Edit",
        onClick --> (_ => onEdit(id))
      )
    )

  /** The affirmed mark's tooltip. */
  private val AffirmedTooltip = "Re-affirmed, not yet saved"
