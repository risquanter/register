package app.state

import com.raquo.laminar.api.L.*
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{NonNegativeInt, Probability, SafeName, ValidationMessages, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, ExpectedInput, FieldName, FieldProblem, ValidationError, ValueRange}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef
import zio.prelude.Validation
import app.core.NumberFormat
import app.state.RiskLeafField.*

/** Type-safe field identifiers for the risk leaf form. */
enum RiskLeafField:
  case Name, Parent, Probability

  /** One of the loss distribution's inputs. */
  case Shape(input: ShapeInput)

  /** The form's own line, for a refusal about a combination of its inputs
    * rather than about one of them. */
  case FormLevel

object RiskLeafField:
  /** The label an input carries, and the name a summary entry gives it. */
  def label(field: RiskLeafField): String = field match
    case Name         => "Name"
    case Parent       => ParentSelection.Label
    case Probability  => "Probability"
    case Shape(input) => ShapeInput.label(input)
    case FormLevel    => "This leaf"

  /** The element id of the input, unique on the page. */
  def inputId(field: RiskLeafField): String = field match
    case Shape(input) => s"$IdPrefix-${input.toString.toLowerCase}"
    case other        => s"$IdPrefix-${other.toString.toLowerCase}"

  /** What every input id of this form starts with. */
  private val IdPrefix = "leaf"

/**
 * Reactive form state for creating a RiskLeaf.
 *
 * Features:
 * - Input filters: prevent invalid characters from being typed
 * - Reactive validation signals: auto-update on field changes (using ValidationUtil from common)
 * - Error display timing: controlled by showErrors flag (set on blur/submit)
 *
 * Validation rules use Iron types from common module (same as backend):
 * - SafeName: a node name; see `SafeNameConstraint`
 * - Probability: 0 ≤ p ≤ 1 (closed interval), entered on the 0–100 scale
 * - Metalog mode: requires percentiles + quantiles of equal length
 * - Lognormal mode: requires minLoss < maxLoss (both positive)
 *
 * Every single-field check reads a located accessor, so a refusal names the
 * field its value came from and the subject `subjectVar` holds. The two
 * cross-field checks locate their refusal at the pair they constrain.
 */
final class RiskLeafFormState extends SubmitRefusals[RiskLeafField], ShapeFieldsForm:

  // ============================================================
  // Form Field Vars (mutable reactive state)
  // ============================================================

  /** Distribution mode toggle */
  val distributionModeVar: Var[DistributionMode] = Var(DistributionMode.Metalog)

  // Common fields
  val nameVar: Var[String] = Var("")
  val probabilityVar: Var[String] = Var("")

  /** Parent selection. Not reset by [[resetFields]] — callers explicitly set
    * it to `ParentSelection.Unset` at clear/reset call sites (see
    * [[app.views.RiskLeafFormView]]); also auto-corrected to `Unset` by
    * [[app.components.FormInputs.parentSelect]] if the option list moves out
    * from under an already-chosen value.
    */
  val parentVar: Var[ParentSelection] = Var(ParentSelection.Unset)

  // Metalog mode fields
  val percentilesVar: Var[String] = Var("")  // Placeholder text "e.g., 10, 50, 90" is on the input element
  val quantilesVar: Var[String] = Var("")              // User must provide quantiles

  // Lognormal mode fields
  val minLossVar: Var[String] = Var("")
  val maxLossVar: Var[String] = Var("")

  // Metalog terms (metalog mode only; blank = one term per anchor)
  val termsVar: Var[String] = Var("")

  /** The node this form's refusals are about. Set when the form is opened:
    * `DraftNode` for a new leaf, `ExistingNode(id)` for one the server has
    * stored. Like `parentVar`, it is not reset by [[resetFields]] — the view
    * sets it at each call site that opens or clears the form. */
  val subjectVar: Var[ErrorSubject] = Var(ErrorSubject.DraftNode)
  private def subject: StrictSignal[ErrorSubject] = subjectVar.signal

  /** Reset all form fields and error display to initial state. */
  def resetFields(): Unit =
    nameVar.set("")
    probabilityVar.set("")
    distributionModeVar.set(DistributionMode.Metalog)
    percentilesVar.set("")
    quantilesVar.set("")
    minLossVar.set("")
    maxLossVar.set("")
    termsVar.set("")
    setSubmitErrors(Nil)
    resetTouched()

  // ============================================================
  // Input Filters (prevent invalid characters - UX only)
  // ============================================================

  /** Filter for name field: any printable characters */
  val nameFilter: String => Boolean = _ => true

  /** Filter for the probability field: the companion's. */
  val probabilityFilter: String => Boolean = RiskLeafFormState.probabilityFilter

  // ============================================================
  // Located Accessors
  // ============================================================

  /** The field a refusal about each input names. Declared once; the located
    * readers refer to these and no other site in this form names a field.
    * The two pair fields name a rule over two inputs rather than either one. */
  private val nameField: FieldName        = FieldName.Name
  private val parentField: FieldName      = FieldName.ParentName
  private val probabilityField: FieldName = FieldName.Probability
  private val percentilesField: FieldName = ShapeInput.Percentiles.field
  private val quantilesField: FieldName   = ShapeInput.Quantiles.field
  private val minLossField: FieldName     = ShapeInput.MinLoss.field
  private val maxLossField: FieldName     = ShapeInput.MaxLoss.field
  private val termsField: FieldName       = ShapeInput.Terms.field
  private val pairingField: FieldName     = FieldName.PercentileQuantilePairing
  private val lossRangeField: FieldName   = FieldName.LossRange

  /** An input's live value, located at `field` of the current subject. Every
    * single-field check reads its input through this. */
  private def located[A](input: Var[A], field: FieldName): Signal[Located[A]] =
    input.signal.combineWith(subject).map((v, s) => Located(v, ErrorLocation.Field(s, field)))

  /** An input's current value, located, for the submit read. */
  private def locatedNow[A](input: Var[A], field: FieldName): Located[A] =
    Located(input.now(), ErrorLocation.Field(subjectVar.now(), field))

  /** The first refusal a check reported, or none if it accepted. */
  private def firstRefusal[B](result: Validation[ValidationError, B]): Option[ValidationError] =
    result.toEither.left.toOption.map(_.head)

  /** A comma-separated field read as a list, readable when at least one entry
    * parses as a number. */
  private val parseNonEmptyList: String => Option[List[Double]] =
    s => Some(parseDoubleList(s)).filter(_.nonEmpty)

  /** A located check's sentence, for the cross-field gates and `errorSignals`,
    * which carry text. */
  private def messageOf(check: Signal[Option[ValidationError]]): Signal[Option[String]] =
    check.map(_.map(e => ValidationMessages.render(e.problem)))

  // ============================================================
  // Submit Refusals
  // ============================================================

  override protected def inputsFor(location: ErrorLocation): Set[RiskLeafField] =
    RiskLeafFormState.inputsFor(location)

  override protected def formLine: RiskLeafField = FormLevel

  override def inputIdOf(input: ShapeInput): String = RiskLeafField.inputId(Shape(input))

  override def markShapeTouched(input: ShapeInput): Unit = markTouched(Shape(input))

  // ============================================================
  // Raw Validation Signals (using ValidationUtil from common)
  // ============================================================

  /** Name validation using Iron SafeName rules */
  private val nameErrorRaw: Signal[Option[ValidationError]] =
    located(nameVar, nameField).map(input =>
      firstRefusal(ValidationUtil.toValidation(input.refine(ValidationUtil.refineName)))
    )

  /** The parent selection's live refusal, for blocking submit only. The
    * dropdown's own display shows only a submit's refusals (`parentError`). */
  private val parentErrorRaw: Signal[Option[ValidationError]] =
    located(parentVar, parentField).map(input => firstRefusal(ParentSelection.toParentRef(input)))

  /** The probability field's check, on the 0–100 scale it is entered on:
    * present, a number, and inside the closed interval 0 ≤ p ≤ 1 once divided
    * by 100. The range refusal is worded on the entered scale. The live check
    * and the submit both call it, so they refuse the same input with the same
    * sentence. */
  private def refineProbabilityInput(input: Located[String]): Validation[ValidationError, Probability] =
    RiskLeafFormState.percentInput(input).flatMap { pct =>
      Validation.fromEither(
        pct.map(RiskLeafFormState.pctToDomain)
          .refine(ValidationUtil.refineProbability)
          .left.map(_ => pct.reject(FieldProblem.OutOfRange(ValueRange.Percent)))
      )
    }

  private val probabilityErrorRaw: Signal[Option[ValidationError]] =
    located(probabilityVar, probabilityField).map(input => firstRefusal(refineProbabilityInput(input)))

  /** Metalog mode: percentiles validation (0-100, but Metalog needs 0 < p < 100) */
  private val percentilesErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(percentilesVar, percentilesField)).map {
      case (DistributionMode.Metalog, input) =>
        parseRequired(input, parseNonEmptyList, ExpectedInput.NumberList)
          .toEither
          .fold(
            refusals => Some(refusals.head),
            percentiles =>
              if percentiles.value.exists(p => p <= 0 || p >= RiskLeafFormState.percentScale) then
                Some(percentiles.reject(FieldProblem.OutOfRange(ValueRange.Percentile)))
              else if !ValidationUtil.isStrictlyIncreasing(percentiles.value) then
                Some(percentiles.reject(FieldProblem.PercentilesNotIncreasing))
              else None
          )
      case _ => None
    }

  /** Metalog mode: quantiles validation (positive loss amounts) */
  private val quantilesErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(quantilesVar, quantilesField)).map {
      case (DistributionMode.Metalog, input) =>
        parseRequired(input, parseNonEmptyList, ExpectedInput.NumberList)
          .toEither
          .fold(
            refusals => Some(refusals.head),
            quantiles =>
              if quantiles.value.exists(_ <= 0) then
                Some(quantiles.reject(FieldProblem.OutOfRange(ValueRange.PositiveLoss)))
              else if !ValidationUtil.isStrictlyIncreasing(quantiles.value) then
                Some(quantiles.reject(FieldProblem.QuantilesNotIncreasing))
              else None
          )
      case _ => None
    }

  /** Metalog mode: cross-field validation (equal length). Located at the pair,
    * as the shared shape factory reports the same rule. */
  private val metalogCrossFieldErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal
      .combineWith(percentilesVar.signal, quantilesVar.signal, subject)
      .map {
        case (DistributionMode.Metalog, pStr, qStr, s) =>
          val pair  = Located((pStr, qStr), ErrorLocation.Field(s, pairingField))
          val pList = parseDoubleList(pStr)
          val qList = parseDoubleList(qStr)
          Option.when(pList.nonEmpty && qList.nonEmpty && pList.length != qList.length)(
            pair.reject(FieldProblem.PercentileQuantileLengthMismatch(
              NonNegativeInt.count(pList.length), NonNegativeInt.count(qList.length)))
          )
        case _ => None
      }

  /** Lognormal mode: minLoss validation using Iron PositiveLong */
  private val minLossErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(minLossVar, minLossField)).map {
      case (DistributionMode.Lognormal, input) => boundError(input)
      case _                                   => None
    }

  /** Lognormal mode: maxLoss validation using Iron PositiveLong */
  private val maxLossErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(maxLossVar, maxLossField)).map {
      case (DistributionMode.Lognormal, input) => boundError(input)
      case _                                   => None
    }

  /** One lognormal bound's own check: present, a whole number, and positive.
    * The submit parses the bound the same way, so both refuse the same text
    * with the same problem. */
  private def boundError(input: Located[String]): Option[ValidationError] =
    firstRefusal(
      parseRequired(input, parseLong, ExpectedInput.WholeNumber)
        .flatMap(bound => ValidationUtil.toValidation(bound.refine(ValidationUtil.refinePositiveLong)))
    )

  /** A stated term count checked as the shape's own gate checks it: first the
    * fit's range, by the shared refinement, then against the anchor count, with
    * the same codes and sentences. `anchors` is empty while the percentile and
    * loss counts differ, because no anchors form then and the gate reports the
    * pairing instead. */
  private def termsWithinFit(input: Located[String], terms: Int, anchors: Option[Int]): Validation[ValidationError, Unit] =
    ValidationUtil.toValidation(input.as(terms).refine(ValidationUtil.refineMetalogTermCount)).flatMap { count =>
      if anchors.exists(count > _) then
        Validation.fail(input.reject(FieldProblem.TermsAboveAnchorCount))
      else Validation.unit
    }

  /** Metalog mode: terms validation. The anchors are counted as the shape
    * counts them: an entry that is not a number is not counted, and there are
    * anchors only when the percentile and loss counts are equal. */
  private val termsErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal
      .combineWith(located(termsVar, termsField), percentilesVar.signal, quantilesVar.signal)
      .map {
        case (DistributionMode.Metalog, input, pStr, qStr) =>
          val percentiles = parseDoubleList(pStr).length
          val anchors     = Option.when(percentiles == parseDoubleList(qStr).length)(percentiles)
          firstRefusal(RiskLeafFormState.readTerms(input).flatMap(_.fold(Validation.unit)(t => termsWithinFit(input, t, anchors))))
        case _ => None
      }

  /** Lognormal mode: cross-field validation (minLoss < maxLoss). Located at
    * the pair, as the shared shape factory reports the same rule, and over the
    * same positive bounds that factory compares: a bound that is not positive
    * is each input's own refusal, and the pair rule is not evaluated for it. */
  private val lognormalCrossFieldErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal
      .combineWith(minLossVar.signal, maxLossVar.signal, subject)
      .map {
        case (DistributionMode.Lognormal, minStr, maxStr, s) =>
          val pair  = Located((minStr, maxStr), ErrorLocation.Field(s, lossRangeField))
          val minLoss = parseLong(minStr).flatMap(ValidationUtil.refinePositiveLong(_, pair.location).toOption)
          val maxLoss = parseLong(maxStr).flatMap(ValidationUtil.refinePositiveLong(_, pair.location).toOption)
          (minLoss, maxLoss) match
            case (Some(min), Some(max)) if min >= max =>
              Some(pair.reject(FieldProblem.LossRangeNotOrdered(min, max)))
            case _ => None
        case _ => None
      }

  // ============================================================
  // Display-controlled Error Signals (for UI binding)
  // ============================================================

  // Each input's refusals, then the sentences the input components bind to.
  // The refusals are what the summary is built from.
  private val nameRefusals: Signal[List[ValidationError]] = errorsFor(Name, nameErrorRaw)
  val nameError: Signal[List[String]] = messagesOf(nameRefusals)

  /** The dropdown shows only submit-time refusals (`ParentSelection.toParentRef`
    * refusing the selection in `toDraft`, or the builder's topology check),
    * from the stored refusals. Its live check, `parentErrorRaw`, blocks the
    * submit and is not shown here. */
  private val parentRefusals: Signal[List[ValidationError]] = errorsFor(Parent, Val(None))
  val parentError: Signal[List[String]] = messagesOf(parentRefusals)

  private val probabilityRefusals: Signal[List[ValidationError]] = errorsFor(Probability, probabilityErrorRaw)
  val probabilityError: Signal[List[String]] = messagesOf(probabilityRefusals)

  // A cross-field error has its own gate, separate from the per-field touch gate:
  // the constraint is evaluable once both fields of the pair have content, so it
  // shows after submit, or when both are filled and this field is touched and was
  // the one last edited (see `crossFieldGated`). Own-field errors use the per-field touch gate
  // through `errorsFor`. Each pair field shows its own and stored refusals, or
  // else the pair's refusal (see `withCrossField`).

  // Track which field in each cross-field pair was most recently edited.
  // Starts with an arbitrary default (true) which is irrelevant until both fields
  // have content — the bothFilled gate suppresses the error until then.
  private val lastMetalogEdited: Signal[Boolean] =  // true = percentiles last, false = quantiles last
    EventStream
      .merge(
        percentilesVar.signal.changes.mapTo(true),
        quantilesVar.signal.changes.mapTo(false)
      )
      .toSignal(true)

  private val lastLognormalEdited: Signal[Boolean] = // true = minLoss last, false = maxLoss last
    EventStream
      .merge(
        minLossVar.signal.changes.mapTo(true),
        maxLossVar.signal.changes.mapTo(false)
      )
      .toSignal(true)

  /** Cross-field error gated to show only under the most-recently-edited field of the pair.
    *
    * `isThisFieldLast` is true when this field (not its partner) was the last edited.
    * Gate: error is shown when both fields have content AND this was last edited,
    * OR when form-wide validation has been triggered (submit).
    */
  private def crossFieldGated(
    thisField:       RiskLeafField,
    fieldA:          Var[String],
    fieldB:          Var[String],
    raw:             Signal[Option[ValidationError]],
    isThisFieldLast: Signal[Boolean]
  ): Signal[Option[ValidationError]] =
    val bothFilled =
      fieldA.signal.map(_.nonEmpty)
        .combineWith(fieldB.signal.map(_.nonEmpty))
        .map { (a, b) => a && b }
    // Gate: show when form-wide errors are forced (submit), or when this specific
    // field has been blurred AND was the last one edited AND both fields have content.
    // isTouched prevents firing on every keystroke; isThisFieldLast pins the error
    // to only one of the two fields at a time.
    showErrorsSignal
      .combineWith(isTouched(thisField), bothFilled, isThisFieldLast)
      .map { (showAll, touched, filled, isLast) => showAll || (touched && isLast && filled) }
      .combineWith(raw)
      .map { (show, err) => err.filter(_ => show) }

  private val metalogCrossFieldGated_Pct: Signal[Option[ValidationError]] =
    crossFieldGated(Shape(ShapeInput.Percentiles), percentilesVar, quantilesVar, metalogCrossFieldErrorRaw, lastMetalogEdited)

  private val metalogCrossFieldGated_Qt: Signal[Option[ValidationError]] =
    crossFieldGated(Shape(ShapeInput.Quantiles), percentilesVar, quantilesVar, metalogCrossFieldErrorRaw, lastMetalogEdited.map(!_))

  private val lognormalCrossFieldGated_Min: Signal[Option[ValidationError]] =
    crossFieldGated(Shape(ShapeInput.MinLoss), minLossVar, maxLossVar, lognormalCrossFieldErrorRaw, lastLognormalEdited)

  private val lognormalCrossFieldGated_Max: Signal[Option[ValidationError]] =
    crossFieldGated(Shape(ShapeInput.MaxLoss), minLossVar, maxLossVar, lognormalCrossFieldErrorRaw, lastLognormalEdited.map(!_))

  /** An input's own and stored refusals, or the pair rule's refusal when the
    * input shows none of its own. */
  private def withCrossField(
    own:   Signal[List[ValidationError]],
    cross: Signal[Option[ValidationError]]
  ): Signal[List[ValidationError]] =
    own.combineWith(cross).map {
      case (Nil, pair) => pair.toList
      case (shown, _)  => shown
    }

  /** Percentiles: own refusals (touch-gated) ∪ metalog cross-field (last-edited-gated) */
  private val percentilesRefusals: Signal[List[ValidationError]] =
    withCrossField(errorsFor(Shape(ShapeInput.Percentiles), percentilesErrorRaw), metalogCrossFieldGated_Pct)
  val percentilesError: Signal[List[String]] = messagesOf(percentilesRefusals)

  /** Quantiles: own refusals (touch-gated) ∪ metalog cross-field (last-edited-gated) */
  private val quantilesRefusals: Signal[List[ValidationError]] =
    withCrossField(errorsFor(Shape(ShapeInput.Quantiles), quantilesErrorRaw), metalogCrossFieldGated_Qt)
  val quantilesError: Signal[List[String]] = messagesOf(quantilesRefusals)

  /** Min loss: own refusals (touch-gated) ∪ lognormal cross-field (last-edited-gated) */
  private val minLossRefusals: Signal[List[ValidationError]] =
    withCrossField(errorsFor(Shape(ShapeInput.MinLoss), minLossErrorRaw), lognormalCrossFieldGated_Min)
  val minLossError: Signal[List[String]] = messagesOf(minLossRefusals)

  /** Max loss: own refusals (touch-gated) ∪ lognormal cross-field (last-edited-gated) */
  private val maxLossRefusals: Signal[List[ValidationError]] =
    withCrossField(errorsFor(Shape(ShapeInput.MaxLoss), maxLossErrorRaw), lognormalCrossFieldGated_Max)
  val maxLossError: Signal[List[String]] = messagesOf(maxLossRefusals)

  private val termsRefusals: Signal[List[ValidationError]] = errorsFor(Shape(ShapeInput.Terms), termsErrorRaw)
  val termsError: Signal[List[String]] = messagesOf(termsRefusals)

  // ============================================================
  // FormState Implementation
  // ============================================================

  /** The raw checks behind `blockingReasons`, ignoring display timing, in the
   *  order the inputs appear, so a blocked submit's hover box and the summary
   *  list run in the same order. The standalone cross-field checks are listed
   *  too, so a pair refusal blocks the submit even when neither of its fields
   *  has an error of its own. */
  override def errorSignals: List[Signal[Option[String]]] = List(
    nameErrorRaw,
    probabilityErrorRaw,
    parentErrorRaw,
    percentilesErrorRaw,
    quantilesErrorRaw,
    metalogCrossFieldErrorRaw,
    termsErrorRaw,
    minLossErrorRaw,
    maxLossErrorRaw,
    lognormalCrossFieldErrorRaw
  ).map(messageOf)

  /** The inputs in the order `RiskLeafFormView` renders them. The fields of
    * the mode not shown show nothing, because their checks pass outside it. */
  override protected def displayed: List[(RiskLeafField, Signal[List[ValidationError]])] = List(
    Name        -> nameRefusals,
    Probability -> probabilityRefusals,
    Parent      -> parentRefusals,
    Shape(ShapeInput.Percentiles) -> percentilesRefusals,
    Shape(ShapeInput.Quantiles)   -> quantilesRefusals,
    Shape(ShapeInput.Terms)       -> termsRefusals,
    Shape(ShapeInput.MinLoss)     -> minLossRefusals,
    Shape(ShapeInput.MaxLoss)     -> maxLossRefusals
  )

  // ============================================================
  // Form Submission
  // ============================================================

  /** The shape the fields currently hold, read as a snapshot for the submit. */
  def currentShapeValidation(): Validation[ValidationError, DistributionShape] =
    RiskLeafFormState.shapeFrom(
      distributionModeVar.now(),
      percentilesVar.now(),
      quantilesVar.now(),
      locatedNow(termsVar, termsField),
      locatedNow(minLossVar, minLossField),
      locatedNow(maxLossVar, maxLossField),
      subjectVar.now()
    )

  /** Every value a submission needs, refined, with every field's refusal
    * accumulated rather than only the first.
    *
    * This is the form's one refinement boundary: the `Var`s above hold what the
    * user is typing, and everything past this point is a refined type. Each
    * failure names the field it came from, so once the view stores it with
    * `setSubmitErrors`, the input it names selects it.
    */
  def toDraft: Validation[ValidationError, (SafeName.SafeName, ParentRef, Probability, DistributionShape)] =
    val nameV = ValidationUtil.toValidation(locatedNow(nameVar, nameField).refine(ValidationUtil.refineName))
    val parentV = ParentSelection.toParentRef(locatedNow(parentVar, parentField))
    val probabilityV = refineProbabilityInput(locatedNow(probabilityVar, probabilityField))
    Validation.validateWith(nameV, parentV, probabilityV, currentShapeValidation()) {
      (name, parent, probability, shape) => (name, parent, probability, shape)
    }

  /** Reactive signal of the current shape-only draft.
    *
    * Emits `Some(shape)` when the mode's own fields gate into a
    * [[DistributionShape]], `None` when incomplete or invalid. Does not include
    * probability — that is a leaf-level field separate from the shape. Built
    * from the values the signals carry, so it reads no snapshot. An emission
    * equal to the last one is dropped, so rewriting a field with its own value
    * or moving the subject does not refetch the preview.
    *
    * Used by [[app.state.DistributionChartState]] to trigger debounced preview fetches.
    */
  val draftSignal: Signal[Option[DistributionShape]] =
    distributionModeVar.signal
      .combineWith(percentilesVar.signal, quantilesVar.signal, located(termsVar, termsField))
      .combineWith(located(minLossVar, minLossField), located(maxLossVar, maxLossField), subject)
      .map { (mode, percentiles, quantiles, terms, minLoss, maxLoss, s) =>
        RiskLeafFormState.shapeFrom(mode, percentiles, quantiles, terms, minLoss, maxLoss, s).toOption
      }
      .distinct

  /** Signal emitting an advisory warning when the implied P90/P10 loss ratio is
    * unusually high (above `impliedRatioLimit`), suggesting the expert
    * estimates encode extreme tail behaviour. Visible only in metalog mode;
    * None in lognormal mode or when insufficient data is available to compute
    * the ratio.
    */
  val impliedRatioWarning: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(percentilesVar.signal, quantilesVar.signal)
      .map(RiskLeafFormState.impliedRatioAdvisory)


object RiskLeafFormState:
  /** The scale the form enters probabilities and percentiles on: out of 100,
    * where the domain uses 0–1. */
  val percentScale: Double = 100.0

  /** The most decimal places the probability field accepts. Chosen by this
    * project: a likelihood is stated to a hundredth of a percent at most. */
  val probabilityDecimals: Int = 2

  /** Filter for a probability entered on the 0–100 scale: digits and one
    * decimal point, at most `probabilityDecimals` places. Enforced as the user
    * types, so a further decimal place cannot be entered. */
  val probabilityFilter: String => Boolean = { s =>
    val hasSingleDot = s.count(_ == '.') <= 1
    val allValidChars = s.forall(c => c.isDigit || c == '.')
    val withinDecimals = s.indexOf('.') match
      case -1  => true
      case idx => s.length - idx - 1 <= probabilityDecimals
    hasSingleDot && allValidChars && withinDecimals
  }

  /** The decimal places a percentile is shown with: none, because percentiles
    * are entered as whole numbers (see `ShapeInput.filter`). */
  val percentileDecimals: Int = 0

  /** The anchors the implied-ratio advisory compares, on the form's percent
    * scale: the lowest at or below P10 against the highest at or above P90. */
  val lowerTailPercentile: Double = 10.0
  val upperTailPercentile: Double = 90.0

  /** The P90/P10 loss ratio above which the advisory is shown. Chosen by this
    * project as the point where a spread suggests extreme tail behaviour. */
  val impliedRatioLimit: Double = 100.0

  /** Convert a 0–100 percent-scale value (as entered in the form) to the
    * 0–1 domain scale used in [[DistributionShape]].
    *
    * Used for scalar probability (`pctToDomain(pct)`) and per-element
    * percentile arrays (`arr.map(pctToDomain)`).
    */
  def pctToDomain(pct: Double): Double = pct / percentScale

  /** Convert a 0–1 domain value to its 0–100 display string — the shared
    * [[app.core.NumberFormat.percentValue]] under this form-facing name. */
  def domainToDisplayPct(p: Double, decimals: Int): String =
    NumberFormat.percentValue(p, decimals)

  /** The advisory shown when the implied P90/P10 loss ratio exceeds 100×.
    *
    * {{{
    * impliedRatioTooHigh(250.0)
    * // P90/P10 ratio is 250× — very high. Review whether this reflects expert judgment.
    * }}}
    */
  def impliedRatioTooHigh(ratio: Double): String =
    f"P90/P10 ratio is $ratio%.0f× — very high. Review whether this reflects expert judgment."

  /** The implied-ratio advisory for a form's shape text: in metalog mode, the
    * loss at the highest percentile at or above P90 against the loss at the
    * lowest at or below P10. None in lognormal mode, or while the two lists
    * differ in length or hold no such pair.
    *
    * {{{
    * impliedRatioAdvisory(DistributionMode.Metalog, "10, 90", "1, 250")  // Some(impliedRatioTooHigh(250.0))
    * impliedRatioAdvisory(DistributionMode.Metalog, "10, 90", "1, 50")   // None
    * }}}
    */
  def impliedRatioAdvisory(mode: DistributionMode, percentiles: String, quantiles: String): Option[String] =
    mode match
      case DistributionMode.Metalog =>
        val qs = FormState.parseDoubleList(quantiles)
        val ps = FormState.parseDoubleList(percentiles)
        (ps.indexWhere(_ <= lowerTailPercentile), ps.lastIndexWhere(_ >= upperTailPercentile)) match
          case (p10, p90) if qs.length == ps.length && p10 >= 0 && p90 >= 0 && p10 != p90 && qs(p10) > 0 =>
            val ratio = qs(p90) / qs(p10)
            Option.when(ratio > impliedRatioLimit)(impliedRatioTooHigh(ratio))
          case _ => None
      case DistributionMode.Lognormal => None

  /** A required input entered on the 0–100 scale, read as a number on that
    * scale. Each form then refines it into the probability type it holds and
    * words a range refusal on the entered scale. */
  def percentInput(input: Located[String]): Validation[ValidationError, Located[Double]] =
    FormState.parseRequired(input, FormState.parseDouble, ExpectedInput.Number)

  /** The terms input read as a count. Blank text means one term per anchor and
    * reads as no count; text that is not a whole number is refused as
    * unreadable. The live check and the shape builder both read terms through
    * this, so they refuse the same text with the same problem. */
  private[state] def readTerms(input: Located[String]): Validation[ValidationError, Option[Int]] =
    if input.value.isBlank then Validation.succeed(None)
    else
      input.value.toIntOption.fold[Validation[ValidationError, Option[Int]]](
        Validation.fail(input.reject(FieldProblem.Unreadable(ExpectedInput.WholeNumber)))
      )(t => Validation.succeed(Some(t)))

  /** A form's shape fields parsed, zipped and gated into one shape. A pure
    * function of its arguments, so every form with shape fields builds the
    * shape the same way, and a form's live preview and its submit agree.
    *
    * The mode selects which case is built, so the fields of the other mode are
    * not read at all and cannot reach the shape. For metalog mode the zip is
    * where a percentile count unequal to the loss count is refused, which is the
    * only place in the browser that rule can be stated.
    *
    * Percentiles from the form are on the 0–100 scale and are converted to 0–1
    * before gating.
    *
    * An entry that is not a number is dropped rather than refused, so only the
    * surviving entries reach the gate and only their count is checked. A field
    * of `0.1, abc, 0.9` therefore arrives as two percentiles, and pairs
    * successfully with a two-entry loss field.
    */
  def shapeFrom(
    mode:        DistributionMode,
    percentiles: String,
    quantiles:   String,
    terms:       Located[String],
    minLoss:     Located[String],
    maxLoss:     Located[String],
    subject:     ErrorSubject
  ): Validation[ValidationError, DistributionShape] =
    mode match
      case DistributionMode.Metalog =>
        readTerms(terms).flatMap { count =>
          DistributionShape.Metalog.fromPrimitives(
            FormState.parseDoubleList(percentiles).map(pctToDomain).toVector,
            FormState.parseDoubleList(quantiles).toVector,
            count,
            subject
          )
        }

      case DistributionMode.Lognormal =>
        Validation
          .validateWith(
            FormState.parseRequired(minLoss, FormState.parseLong, ExpectedInput.WholeNumber),
            FormState.parseRequired(maxLoss, FormState.parseLong, ExpectedInput.WholeNumber)
          )((min, max) => (min.value, max.value))
          .flatMap { case (min, max) =>
            DistributionShape.Lognormal.fromPrimitives(min, max, subject)
          }

  /** No display target of this form. A refusal at such a location is shown on
    * the form's own line, through `unclaimed`. */
  private val noTarget: Set[RiskLeafField] = Set.empty

  private def shape(input: ShapeInput): RiskLeafField = RiskLeafField.Shape(input)

  /** The targets a refusal belongs under. One mapping for every location, so
    * every case is assigned by name and a location added later fails the
    * build until it is given one.
    *
    * An element failure and a collection failure share one input, because the
    * form edits the whole list as one comma-separated value. A rule over a pair
    * of inputs belongs under both. A rule over the mode's inputs together, and
    * the builder's topology check of this form's submit, belong on the form's
    * own line. The subject does not change the routing.
    *
    * Visible to the application so the mapping is testable without rendering
    * the form.
    */
  private[app] def inputsFor(location: ErrorLocation): Set[RiskLeafField] =
    location match
      case ErrorLocation.Field(_, field) =>
        field match
          case FieldName.Name                                  => Set(RiskLeafField.Name)
          case FieldName.ParentName                            => Set(RiskLeafField.Parent)
          case FieldName.Probability                           => Set(RiskLeafField.Probability)
          case FieldName.Percentiles | FieldName.PercentileAt(_) => Set(shape(ShapeInput.Percentiles))
          case FieldName.Quantiles | FieldName.QuantileAt(_)   => Set(shape(ShapeInput.Quantiles))
          case FieldName.MinLoss                               => Set(shape(ShapeInput.MinLoss))
          case FieldName.MaxLoss                               => Set(shape(ShapeInput.MaxLoss))
          case FieldName.Terms                                 => Set(shape(ShapeInput.Terms))
          case FieldName.LossRange                             => Set(shape(ShapeInput.MinLoss), shape(ShapeInput.MaxLoss))
          case FieldName.PercentileQuantilePairing             => Set(shape(ShapeInput.Percentiles), shape(ShapeInput.Quantiles))
          case FieldName.DistributionType                      => Set(RiskLeafField.FormLevel)
          case FieldName.Id | FieldName.ParentId | FieldName.ChildIds | FieldName.RootId |
               FieldName.Nodes | FieldName.Trees | FieldName.SeedVarHighWater |
               FieldName.SeedVarId | FieldName.SeedEntityId | FieldName.Spec | FieldName.ChildAt(_) |
               FieldName.Mitigations | FieldName.Precedence | FieldName.Cap | FieldName.Factor |
               FieldName.TargetingSource | FieldName.Deductible | FieldName.Limit | FieldName.Threshold |
               FieldName.Anchor |
               FieldName.Branch | FieldName.Scenario | FieldName.At |
               FieldName.Source | FieldName.ToCommit | FieldName.CommitHash | FieldName.ContentHash |
               FieldName.WorkspaceKey | FieldName.WorkspaceKeyHash | FieldName.UserId |
               FieldName.Email | FieldName.Url | FieldName.TraceId |
               FieldName.Ttl | FieldName.IdleTimeout |
               FieldName.AggregatedLoss | FieldName.MitigatedResult | FieldName.Unknown =>
            noTarget
      case ErrorLocation.Whole             => Set(RiskLeafField.FormLevel)
      case ErrorLocation.Header(_)         => noTarget
      case ErrorLocation.QueryParameter(_) => noTarget
