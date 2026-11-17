package app.views

import com.raquo.laminar.api.L.*
import zio.prelude.Validation

import app.components.{AbsoluteStatementList, FormInputs, ShapeFields}
import app.state.{AbsoluteFiguresField, AbsoluteFiguresFormState, AbsoluteStatementRow, DistributionMode, OverrideEdit, ShapeInput}
import com.risquanter.register.domain.data.AbsoluteReplacement
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, SafeName}
import com.risquanter.register.domain.errors.ErrorSubject

/** The override edit popup: the figures of one absolute statement, edited and
  * re-affirmed. Shown while `edit` is `Open` on a statement `rows` holds.
  *
  * It shows the statement, the leaf it was asserted against by its name in
  * this tree, or by its identifier when the tree holds no such node, and its
  * current figures, the inputs for the parts the statement replaces, and Apply and
  * Cancel. Apply reads the inputs; on a refusal each refusal shows under the
  * input it names, and on success the figures go to `onApply`. A form state is
  * created each time the popup opens, so an opening starts from the figures
  * the statement holds then.
  */
object OverridePopup:

  def apply(
    edit:     Signal[OverrideEdit],
    rows:     Signal[List[AbsoluteStatementRow]],
    nodeName: Signal[NodeId => Option[SafeName.SafeName]],
    onApply:  (MitigationId, AbsoluteReplacement) => Unit,
    onCancel: () => Unit
  ): HtmlElement =
    val openRow: Signal[Option[AbsoluteStatementRow]] =
      edit.combineWith(rows).map {
        case (OverrideEdit.Open(id), rs) => rs.find(_.mitigation.id == id)
        case (OverrideEdit.Closed | OverrideEdit.Pending(_, _), _) => None
      }.distinct
    div(
      child.maybe <-- openRow.splitOption((row, _) => modal(row, nodeName.map(_(row.anchor)), onApply, onCancel))
    )

  private def modal(
    row:        AbsoluteStatementRow,
    anchorName: Signal[Option[SafeName.SafeName]],
    onApply:    (MitigationId, AbsoluteReplacement) => Unit,
    onCancel:   () => Unit
  ): HtmlElement =
    val id   = row.mitigation.id
    val form = new AbsoluteFiguresFormState(row.figures, ErrorSubject.Mitigation(id))

    def apply(): Unit =
      form.triggerValidation()
      form.toReplacement match
        case Validation.Success(_, figures) =>
          form.setSubmitErrors(Nil)
          onApply(id, figures)
        case Validation.Failure(_, errs) =>
          form.setSubmitErrors(errs.toList)

    val notLocked: Signal[Boolean] = Val(false)

    div(
      cls := "merge-modal-overlay",
      div(
        cls := "merge-modal override-popup",
        div(cls := "merge-modal-title", titleText(row.mitigation.name)),
        div(
          cls := "merge-modal-body",
          p(cls := "override-popup-anchor", child.text <-- anchorName.map(n => anchorText(n.fold(row.anchor.value)(_.value)))),
          p(cls := "override-popup-current", currentText(AbsoluteStatementList.figuresText(row.figures))),

          // An edit drops the stored refusals naming that input, and those on
          // the popup's own line.
          form.probabilityVar.signal.changes --> (_ => form.clearFor(AbsoluteFiguresField.Probability)),
          form.distributionModeVar.signal.changes --> (_ => form.clearFor(AbsoluteFiguresField.FormLevel)),
          shapeClears(form),

          Option.when(holdsLikelihood(row.figures))(
            FormInputs.textInput(
              labelText = AbsoluteFiguresField.label(AbsoluteFiguresField.Probability),
              inputId = AbsoluteFiguresField.inputId(AbsoluteFiguresField.Probability),
              valueVar = form.probabilityVar,
              errorSignal = form.probabilityError,
              filter = form.probabilityFilter,
              onBlurCallback = () => form.markTouched(AbsoluteFiguresField.Probability),
              placeholderText = ProbabilityPlaceholder,
              inputModeAttr = ProbabilityInputMode
            )
          ),
          Option.when(holdsShape(row.figures))(
            div(
              FormInputs.radioGroup(
                labelText = ModeLabel,
                options = DistributionMode.options,
                selectedVar = form.distributionModeVar
              ),
              child <-- form.distributionModeVar.signal.map {
                case DistributionMode.Metalog   => ShapeFields.metalog(form, notLocked, notLocked)
                case DistributionMode.Lognormal => ShapeFields.lognormal(form, notLocked, notLocked)
              }
            )
          ),
          FormInputs.summaryList(form.summary, AbsoluteFiguresField.label, AbsoluteFiguresField.inputId)
        ),
        div(
          cls := "merge-modal-actions",
          button(tpe := "button", cls := "merge-modal-merge-btn", "Apply", onClick --> (_ => apply())),
          button(tpe := "button", "Cancel", onClick --> (_ => onCancel()))
        )
      )
    )

  /** An edit of a shape input drops the stored refusals naming it. */
  private def shapeClears(form: AbsoluteFiguresFormState): Modifier[HtmlElement] =
    List(
      form.percentilesVar -> ShapeInput.Percentiles,
      form.quantilesVar   -> ShapeInput.Quantiles,
      form.termsVar       -> ShapeInput.Terms,
      form.minLossVar     -> ShapeInput.MinLoss,
      form.maxLossVar     -> ShapeInput.MaxLoss
    ).map((text, input) => text.signal.changes --> (_ => form.clearFor(AbsoluteFiguresField.Shape(input))))

  private def holdsLikelihood(figures: AbsoluteReplacement): Boolean = figures match
    case AbsoluteReplacement.Likelihood(_) | AbsoluteReplacement.Both(_, _) => true
    case AbsoluteReplacement.Severity(_)                                   => false

  private def holdsShape(figures: AbsoluteReplacement): Boolean = figures match
    case AbsoluteReplacement.Severity(_) | AbsoluteReplacement.Both(_, _) => true
    case AbsoluteReplacement.Likelihood(_)                               => false

  private def titleText(statement: SafeName.SafeName): String = s"Review the figures of '${statement.value}'"

  /** The anchor line, given the leaf's name or, failing that, its identifier. */
  private def anchorText(leaf: String): String = s"Asserted against the leaf $leaf"

  private def currentText(figures: String): String = s"Current figures: $figures"

  /** The mode switch's label. It differs from the leaf form's, because a radio
    * group is named after its label and the leaf form is on the same page. */
  private val ModeLabel = "Distribution type of the borne loss"

  private val ProbabilityPlaceholder = "e.g., 2 (= 2%)"

  /** The HTML `inputmode` attribute's value for a number with a decimal point. */
  private val ProbabilityInputMode = "decimal"
