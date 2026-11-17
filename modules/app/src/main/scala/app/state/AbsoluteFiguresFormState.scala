package app.state

import com.raquo.laminar.api.L.*
import zio.prelude.Validation

import com.risquanter.register.domain.data.{AbsoluteReplacement, DistributionShape}
import com.risquanter.register.domain.data.iron.{ResidualProbability, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, FieldProblem, ValidationError, ValueRange}

/** The override edit popup's display targets: the likelihood, one shape input,
  * or the popup's own line. */
enum AbsoluteFiguresField:
  case Probability

  /** One of the loss distribution's inputs. */
  case Shape(input: ShapeInput)

  /** The popup's own line, for a refusal about its inputs together. */
  case FormLevel

object AbsoluteFiguresField:

  /** The label an input carries, and the name a summary entry gives it. The
    * likelihood and the shape inputs read as they do on the leaf form. */
  def label(field: AbsoluteFiguresField): String = field match
    case Probability  => RiskLeafField.label(RiskLeafField.Probability)
    case Shape(input) => ShapeInput.label(input)
    case FormLevel    => "This statement"

  /** The element id of the input, unique on the page: the leaf form is on the
    * same page under the popup, so the prefix differs from its own. */
  def inputId(field: AbsoluteFiguresField): String = field match
    case Shape(input) => s"$IdPrefix-${input.toString.toLowerCase}"
    case other        => s"$IdPrefix-${other.toString.toLowerCase}"

  /** What every input id of the popup starts with. */
  private val IdPrefix = "figures"

  /** The targets a refusal belongs under: the leaf form's routing, kept to the
    * inputs the popup renders. A refusal the leaf form shows under the name or
    * the parent is about nothing the popup holds, so it shows on no input and
    * reaches the popup's own line through `unclaimed`. */
  private[app] def inputsFor(location: ErrorLocation): Set[AbsoluteFiguresField] =
    RiskLeafFormState.inputsFor(location).flatMap {
      case RiskLeafField.Probability                => Set(Probability)
      case RiskLeafField.Shape(input)               => Set(Shape(input))
      case RiskLeafField.FormLevel                  => Set(FormLevel)
      case RiskLeafField.Name | RiskLeafField.Parent => Set.empty
    }

/** The figures of one absolute statement, as the popup's fields hold them. The
  * fields start from `initial`, and the popup edits only the parts `initial`
  * replaces: the likelihood for `Likelihood`, the shape for `Severity`, both
  * for `Both`.
  *
  * The form runs no live checks. `toReplacement` refines every field the
  * statement holds and accumulates their refusals, and the popup stores them
  * with `setSubmitErrors`, so each shows under the input it names.
  */
final class AbsoluteFiguresFormState(val initial: AbsoluteReplacement, subject: ErrorSubject)
    extends SubmitRefusals[AbsoluteFiguresField], ShapeFieldsForm:
  import AbsoluteFiguresField.{FormLevel, Shape}

  val subjectVar: Var[ErrorSubject] = Var(subject)

  val probabilityVar: Var[String] = Var("")
  val distributionModeVar: Var[DistributionMode] = Var(DistributionMode.Metalog)
  val percentilesVar: Var[String] = Var("")
  val quantilesVar: Var[String] = Var("")
  val termsVar: Var[String] = Var("")
  val minLossVar: Var[String] = Var("")
  val maxLossVar: Var[String] = Var("")

  /** Filter for the likelihood: as the leaf form's probability input. */
  val probabilityFilter: String => Boolean = RiskLeafFormState.probabilityFilter

  initial match
    case AbsoluteReplacement.Likelihood(p) => probabilityVar.set(probabilityText(p))
    case AbsoluteReplacement.Severity(s)   => fillShape(FormMode.shapeToFieldText(s))
    case AbsoluteReplacement.Both(p, s) =>
      probabilityVar.set(probabilityText(p))
      fillShape(FormMode.shapeToFieldText(s))

  private def probabilityText(p: ResidualProbability): String =
    RiskLeafFormState.domainToDisplayPct(p.toFraction, RiskLeafFormState.probabilityDecimals)

  // ============================================================
  // Submit Refusals
  // ============================================================

  override protected def inputsFor(location: ErrorLocation): Set[AbsoluteFiguresField] =
    AbsoluteFiguresField.inputsFor(location)

  override protected def formLine: AbsoluteFiguresField = FormLevel

  override def inputIdOf(input: ShapeInput): String = AbsoluteFiguresField.inputId(Shape(input))

  override def markShapeTouched(input: ShapeInput): Unit = markTouched(Shape(input))

  /** No live checks: what blocks nothing is checked when Apply is pressed. */
  override def errorSignals: List[Signal[Option[String]]] = Nil

  private val probabilityRefusals = errorsFor(AbsoluteFiguresField.Probability, Val(None))
  private def shapeRefusals(input: ShapeInput) = errorsFor(Shape(input), Val(None))

  private val percentilesRefusals = shapeRefusals(ShapeInput.Percentiles)
  private val quantilesRefusals   = shapeRefusals(ShapeInput.Quantiles)
  private val termsRefusals       = shapeRefusals(ShapeInput.Terms)
  private val minLossRefusals     = shapeRefusals(ShapeInput.MinLoss)
  private val maxLossRefusals     = shapeRefusals(ShapeInput.MaxLoss)

  val probabilityError: Signal[List[String]] = messagesOf(probabilityRefusals)
  val percentilesError: Signal[List[String]] = messagesOf(percentilesRefusals)
  val quantilesError: Signal[List[String]]   = messagesOf(quantilesRefusals)
  val termsError: Signal[List[String]]       = messagesOf(termsRefusals)
  val minLossError: Signal[List[String]]     = messagesOf(minLossRefusals)
  val maxLossError: Signal[List[String]]     = messagesOf(maxLossRefusals)

  val impliedRatioWarning: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(percentilesVar.signal, quantilesVar.signal)
      .map(RiskLeafFormState.impliedRatioAdvisory)

  /** The inputs in the order the popup renders them. */
  override protected def displayed: List[(AbsoluteFiguresField, Signal[List[ValidationError]])] = List(
    AbsoluteFiguresField.Probability -> probabilityRefusals,
    Shape(ShapeInput.Percentiles)    -> percentilesRefusals,
    Shape(ShapeInput.Quantiles)      -> quantilesRefusals,
    Shape(ShapeInput.Terms)          -> termsRefusals,
    Shape(ShapeInput.MinLoss)        -> minLossRefusals,
    Shape(ShapeInput.MaxLoss)        -> maxLossRefusals
  )

  // ============================================================
  // Submission
  // ============================================================

  private def locatedNow(input: Var[String], field: FieldName): Located[String] =
    Located(input.now(), ErrorLocation.Field(subjectVar.now(), field))

  /** The likelihood on the 0–100 scale it is entered on, refined by the rule a
    * request's absolute likelihood is refined by. The range refusal is worded
    * on the entered scale. */
  private def probabilityValidation: Validation[ValidationError, ResidualProbability] =
    RiskLeafFormState.percentInput(locatedNow(probabilityVar, FieldName.Probability)).flatMap { pct =>
      Validation.fromEither(
        pct.map(RiskLeafFormState.pctToDomain)
          .refine(ValidationUtil.refineResidualProbability)
          .left.map(_ => pct.reject(FieldProblem.OutOfRange(ValueRange.ResidualPercent)))
      )
    }

  private def shapeValidation: Validation[ValidationError, DistributionShape] =
    RiskLeafFormState.shapeFrom(
      distributionModeVar.now(),
      percentilesVar.now(),
      quantilesVar.now(),
      locatedNow(termsVar, ShapeInput.Terms.field),
      locatedNow(minLossVar, ShapeInput.MinLoss.field),
      locatedNow(maxLossVar, ShapeInput.MaxLoss.field),
      subjectVar.now()
    )

  /** The figures, every field's refusal accumulated, keeping `initial`'s case. */
  def toReplacement: Validation[ValidationError, AbsoluteReplacement] =
    initial match
      case AbsoluteReplacement.Likelihood(_) => probabilityValidation.map(AbsoluteReplacement.Likelihood(_))
      case AbsoluteReplacement.Severity(_)   => shapeValidation.map(AbsoluteReplacement.Severity(_))
      case AbsoluteReplacement.Both(_, _) =>
        Validation.validateWith(probabilityValidation, shapeValidation)(AbsoluteReplacement.Both(_, _))
