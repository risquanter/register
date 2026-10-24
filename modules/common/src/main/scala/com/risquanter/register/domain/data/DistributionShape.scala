package com.risquanter.register.domain.data

import io.github.iltotore.iron.refineUnsafe
import zio.json.{DeriveJsonCodec, JsonCodec, JsonEncoder, jsonDiscriminator}
import zio.prelude.Validation
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.iron.{
  MetalogTermCount, PositiveDouble, PositiveLong,
  Probability, ValidationMessages, ValidationUtil
}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode
}

/** One quantile anchor: the loss at a stated percentile. The percentile is
  * strictly inside the open unit interval, which is the domain the metalog
  * quantile function is defined on. Both fields carry their own constraint and
  * neither constrains the other, so the pair needs no construction gate.
  */
final case class QuantileAnchor(percentile: Probability, loss: PositiveDouble)

/** Between two and twenty anchors, strictly increasing in percentile and in
  * loss. Construction is the only way in, so the ordering holds for every value
  * that exists.
  */
final case class QuantileAnchors private (values: Vector[QuantileAnchor]):
  /** Anchors unzipped into the two parallel sequences the wire and the fitter
    * take. Immutable, so a holder cannot alter the anchors through them, and two
    * equal anchor sets yield equal sequences. The fitter's own interface takes
    * primitive arrays, and the conversion happens at that call rather than
    * here. */
  def percentiles: Vector[Double] = values.map(a => a.percentile: Double)
  def losses: Vector[Double]      = values.map(a => a.loss: Double)

  /** How many anchors there are, which is also the ceiling on a fitted term
    * count: a metalog fit cannot use more terms than it has points. */
  def size: Int = values.length

  /** The term count a fit uses when the author named none: one term per anchor.
    * That is the interpolating fit — it passes through every anchor the author
    * stated, which is what an author who states anchors and no count is asking
    * for. A lower count smooths the curve away from the anchors and is only
    * reachable by stating it.
    *
    * The `refineUnsafe` is sound here and nowhere else: the only constructor
    * bounds the count to [`MinAnchors`, `MaxAnchors`], which is `[2, 20]` and
    * therefore inside `MetalogTermCount`'s own range. ADR-001 names
    * `.refineUnsafe` in production as a smell because it crashes on invalid
    * input; it cannot be reached with invalid input from here.
    */
  def termsToFit: MetalogTermCount = values.length.refineUnsafe

object QuantileAnchors:
  /** The metalog fitter's valid term range is two to twenty, and a fit cannot
    * use more terms than it has anchors, so bounding the anchor count bounds the
    * term count transitively. Below two there is no curve to fit; above twenty
    * the fitter throws past the validation boundary. */
  private val MinAnchors = 2
  private val MaxAnchors = 20

  /** Refuses an anchor count outside [MinAnchors, MaxAnchors], and a sequence
    * that does not strictly increase in percentile or in loss. All three
    * refusals accumulate.
    *
    * Per-element range is not checked here: `Probability` and `PositiveDouble`
    * are the anchor's own field types, so an out-of-range element cannot reach
    * this call.
    *
    * `location` names the anchor sequence as a whole, which is what a count
    * refusal is about. An ordering refusal names the sequence that is out of
    * order, under the same subject.
    */
  def create(
    anchors:  Vector[QuantileAnchor],
    location: ErrorLocation
  ): Validation[ValidationError, QuantileAnchors] =
    val countV = Validation.fromPredicateWith[ValidationError, Vector[QuantileAnchor]](
      ValidationError(
        location = location,
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = ValidationMessages.anchorCountOutOfRange(anchors.length, MinAnchors, MaxAnchors)
      )
    )(anchors)(a => a.sizeIs >= MinAnchors && a.sizeIs <= MaxAnchors)

    val percentileOrderV = requireStrictlyIncreasing(
      anchors.map(a => a.percentile: Double),
      sibling(location, FieldName.Percentiles),
      ValidationMessages.percentilesMustBeStrictlyIncreasing
    )

    val lossOrderV = requireStrictlyIncreasing(
      anchors.map(a => a.loss: Double),
      sibling(location, FieldName.Quantiles),
      ValidationMessages.quantilesMustBeStrictlyIncreasing
    )

    Validation
      .validateWith(countV, percentileOrderV, lossOrderV)((_, _, _) => new QuantileAnchors(anchors))

  /** The two parallel sequences a boundary carries, zipped into anchors.
    *
    * This is the one place the pairing happens, so the rule that the two
    * sequences describe the same points is stated once: an unequal pair of
    * lengths is refused here, and nowhere else has to state it. Every boundary
    * that receives the shape as two sequences routes through this — the leaf's
    * codec on the store-read and request-decode paths, and the browser's form
    * parse.
    *
    * Per-element refusals accumulate, one per out-of-range value, and the
    * sequence's own count and ordering rules run after them.
    *
    * {{{
    * fromPairedSequences(Vector(0.1, 0.9), Vector(5.0), ErrorSubject.Request)
    * // Failure: 2 percentiles against 1 quantile
    * }}}
    */
  def fromPairedSequences(
    percentiles: Vector[Double],
    losses:      Vector[Double],
    subject:     ErrorSubject
  ): Validation[ValidationError, QuantileAnchors] =
    def at(field: FieldName): ErrorLocation = ErrorLocation.Field(subject, field)

    if percentiles.length != losses.length then
      Validation.fail(ValidationError(
        location = at(FieldName.PercentileQuantilePairing),
        code = ValidationErrorCode.INVALID_COMBINATION,
        message = ValidationMessages.metalogLengthMismatch(percentiles.length, losses.length)
      ))
    else
      val anchorVs = percentiles.zip(losses).zipWithIndex.map {
        case ((percentile, loss), index) =>
          Validation.validateWith(
            toValidation(ValidationUtil.refineProbability(percentile, at(FieldName.percentileAt(index)))),
            toValidation(ValidationUtil.refinePositiveDouble(loss, at(FieldName.quantileAt(index))))
          )(QuantileAnchor.apply)
      }

      Validation
        .validateAll(anchorVs)
        .flatMap(anchors => create(anchors.toVector, at(FieldName.Percentiles)))

  /** Refuses a sequence with two or more elements that do not strictly
    * increase. A sequence shorter than two is trivially increasing, and the
    * count rule is what refuses it. */
  private def requireStrictlyIncreasing(
    values:   Vector[Double],
    location: ErrorLocation,
    message:  String
  ): Validation[ValidationError, Unit] =
    Validation
      .fromPredicateWith[ValidationError, Vector[Double]](
        ValidationError(location, ValidationErrorCode.INVALID_COMBINATION, message)
      )(values)(ValidationUtil.isStrictlyIncreasing)
      .map(_ => ())

  /** The same subject's location for another field, so a refusal about one of
    * the two sequences reaches the control that renders it. A location with no
    * subject to carry over is returned unchanged. */
  private def sibling(location: ErrorLocation, field: FieldName): ErrorLocation =
    location match
      case ErrorLocation.Field(subject, _) => ErrorLocation.Field(subject, field)
      case other                           => other

/** The lognormal mode's 90% confidence interval: a strictly ordered pair of
  * positive loss bounds. The ordering is the pair's own rule — neither bound is
  * wrong on its own, and editing either satisfies it.
  */
final case class LossRange private (min: PositiveLong, max: PositiveLong)

object LossRange:
  /** Refuses a minimum that is not below the maximum. Positivity is not checked
    * here: `PositiveLong` is both fields' own type.
    *
    * Reports at the location given rather than at either bound, because neither
    * value is at fault on its own and editing either one satisfies the rule.
    */
  def create(
    min:      PositiveLong,
    max:      PositiveLong,
    location: ErrorLocation
  ): Validation[ValidationError, LossRange] =
    Validation
      .fromPredicateWith[ValidationError, (PositiveLong, PositiveLong)](
        ValidationError(
          location = location,
          code = ValidationErrorCode.INVALID_RANGE,
          message = ValidationMessages.lossRangeNotOrdered(min, max)
        )
      )((min, max)) { case (lo, hi) => lo < hi }
      .map(_ => new LossRange(min, max))

/** The loss distribution of one risk leaf, in the two modes an author can
  * declare. The case is the mode, so no value carries a metalog field and a
  * lognormal field together, and no rule has to state which fields a mode
  * populates.
  *
  * The mode field is the serialised form's only discriminator: the two cases are
  * distinguished by it rather than by which fields are present. Discriminating
  * by field presence makes a malformed record of one case report the other
  * case's complaint. Its value is the case name, derived rather than declared,
  * so the tag cannot disagree with the case it names. `LeafSimContent`'s
  * projection declares the same two spellings for its own hash preimage, which
  * is the one place the spelling is written out.
  */
@jsonDiscriminator(DistributionShape.TagField)
sealed trait DistributionShape

object DistributionShape:

  /** The key naming the mode. Read by every decoder that tags a mode, published
    * in every schema that does, and stated here once so no two of them can
    * disagree. The submitted form and the provenance record name it from here
    * rather than repeating the string. */
  final val TagField = "distributionType"

  /** Metalog: a quantile function fitted to the author's anchors. `terms`
    * absent means fit with `anchors.termsToFit`. */
  final case class Metalog private (
    anchors: QuantileAnchors,
    terms:   Option[MetalogTermCount]
  ) extends DistributionShape

  /** Lognormal: the BCG method fitted to a 90% confidence interval. */
  final case class Lognormal(bounds: LossRange) extends DistributionShape

  /** The confidence level the lognormal bounds are read at. Fixed by the BCG
    * method rather than configurable. */
  val LognormalConfidenceInterval: Double = 0.90

  /** Wire form: primitives only, one case per mode (ADR-038 §1). The metalog
    * case carries the two parallel sequences an author states rather than the
    * domain's sequence of pairs, so the pairing rule is applied on decode and is
    * not expressible here.
    *
    * Each case is named for its mode and nested so that name is free to be the
    * mode's. The published schema derives from this coproduct, and a derived tag
    * value is the case's own name, so a case named after its implementation role
    * would publish that role as the mode. */
  private sealed trait ShapeRaw

  private object ShapeRaw:
    case class Metalog(
      percentiles: Vector[Double],
      quantiles:   Vector[Double],
      terms:       Option[Int]
    ) extends ShapeRaw

    case class Lognormal(minLoss: Long, maxLoss: Long) extends ShapeRaw

    object Metalog:
      given rawCodec: JsonCodec[Metalog] = DeriveJsonCodec.gen[Metalog]

    object Lognormal:
      given rawCodec: JsonCodec[Lognormal] = DeriveJsonCodec.gen[Lognormal]

  object Metalog:
    /** Refuses a stated term count above the anchor count: a metalog fit cannot
      * use more terms than it has points. An absent count is always valid,
      * because `anchors.termsToFit` derives one that cannot exceed the count.
      *
      * This is the whole of the rule, because `MetalogTermCount` carries the
      * range [2, 20] and `QuantileAnchors` carries the count [2, 20]. What is
      * left is the one comparison no field type can hold.
      */
    def create(
      anchors:  QuantileAnchors,
      terms:    Option[MetalogTermCount],
      location: ErrorLocation
    ): Validation[ValidationError, Metalog] =
      terms match
        case Some(t) if t > anchors.size =>
          Validation.fail(ValidationError(
            location = location,
            code = ValidationErrorCode.INVALID_COMBINATION,
            message = ValidationMessages.termsOutOfRange
          ))
        case _ => Validation.succeed(new Metalog(anchors, terms))

    /** The gate from the primitives a boundary carries to the case. Two callers:
      * this case's JSON decoder, and the HTTP request type that reports against
      * a submitted node rather than the request as a whole — which is the whole
      * reason `subject` is a parameter.
      *
      * Only this case's own fields appear, so there is no other mode's field to
      * refuse and no mode string to disagree with the case.
      */
    def fromPrimitives(
      percentiles: Vector[Double],
      quantiles:   Vector[Double],
      terms:       Option[Int],
      subject:     ErrorSubject
    ): Validation[ValidationError, Metalog] =
      val termsV: Validation[ValidationError, Option[MetalogTermCount]] = terms match
        case Some(t) =>
          toValidation(ValidationUtil.refineMetalogTermCount(t, ErrorLocation.Field(subject, FieldName.Terms)))
            .map(Some(_))
        case None => Validation.succeed(None)

      Validation
        .validateWith(
          QuantileAnchors.fromPairedSequences(percentiles, quantiles, subject),
          termsV
        )((anchors, refinedTerms) => (anchors, refinedTerms))
        .flatMap { case (anchors, refinedTerms) =>
          create(anchors, refinedTerms, ErrorLocation.Field(subject, FieldName.Terms))
        }

    /** Decodes through `fromPrimitives`, so a decoded value is as trustworthy as
      * a constructed one (ADR-038 §1). The subject is the request because this
      * decoder is the request and store-read boundary both. */
    given codec: JsonCodec[Metalog] = JsonCodec(
      JsonEncoder[ShapeRaw.Metalog].contramap(metalog =>
        ShapeRaw.Metalog(metalog.anchors.percentiles, metalog.anchors.losses, metalog.terms.map(t => t: Int))
      ),
      ShapeRaw.Metalog.rawCodec.decoder.mapOrFail { raw =>
        fromPrimitives(raw.percentiles, raw.quantiles, raw.terms, ErrorSubject.Request)
          .toEither
          .left
          .map(_.map(_.message).mkString("; "))
      }
    )

  object Lognormal:
    /** The gate from the primitives a boundary carries to the case, with the
      * same two callers as `Metalog.fromPrimitives`. `LossRange` holds the only
      * rule relating the two values, so this adds nothing of its own. */
    def fromPrimitives(
      minLoss: Long,
      maxLoss: Long,
      subject: ErrorSubject
    ): Validation[ValidationError, Lognormal] =
      Validation
        .validateWith(
          toValidation(ValidationUtil.refinePositiveLong(minLoss, ErrorLocation.Field(subject, FieldName.MinLoss))),
          toValidation(ValidationUtil.refinePositiveLong(maxLoss, ErrorLocation.Field(subject, FieldName.MaxLoss)))
        )((min, max) => (min, max))
        .flatMap { case (min, max) =>
          LossRange.create(min, max, ErrorLocation.Field(subject, FieldName.LossRange))
        }
        .map(Lognormal(_))

    /** Decodes through `fromPrimitives`, so the ordering rule holds for every
      * decoded value. */
    given codec: JsonCodec[Lognormal] = JsonCodec(
      JsonEncoder[ShapeRaw.Lognormal].contramap(lognormal =>
        ShapeRaw.Lognormal(lognormal.bounds.min, lognormal.bounds.max)
      ),
      ShapeRaw.Lognormal.rawCodec.decoder.mapOrFail { raw =>
        fromPrimitives(raw.minLoss, raw.maxLoss, ErrorSubject.Request)
          .toEither
          .left
          .map(_.map(_.message).mkString("; "))
      }
    )

  given codec: JsonCodec[DistributionShape] = DeriveJsonCodec.gen[DistributionShape]

  /** The documented shape is the wire form, not the domain field list: the
    * domain holds anchors as a sequence of pairs and the wire as two parallel
    * sequences. Derived from the raw coproduct for the same reason `RiskTree`
    * derives its schema from `RiskTreeJson`. */
  given schema: Schema[DistributionShape] =
    import sttp.tapir.generic.auto.*
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[ShapeRaw].as[DistributionShape]

  /** The parameters a fit will actually run with: the author's shape, with every
    * value the system supplies filled in. Total, because every absence has a
    * derivation — `termsToFit` for the term count, and the BCG confidence level
    * for a lognormal, which no author states.
    */
  def resolve(shape: DistributionShape): DistributionParams = shape match
    case Metalog(anchors, terms) =>
      DistributionParams.Metalog(
        anchors.percentiles, anchors.losses, terms.getOrElse(anchors.termsToFit))
    case Lognormal(bounds) =>
      DistributionParams.Lognormal(bounds.min, bounds.max, LognormalConfidenceInterval)
