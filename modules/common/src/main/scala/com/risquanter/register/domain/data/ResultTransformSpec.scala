package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import com.risquanter.register.domain.data.iron.{NonNegativeLong, PositiveLong, RetentionFactor, ValidationMessages, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, ValidationError, ValidationErrorCode}

/**
 * Reified result-stage transform: pure data describing one atomic operation on
 * a simulation's trial outcomes — comparable, hashable, serializable. The
 * description is shared with the browser, which builds and displays it;
 * executing it belongs to the server, where `ResultTransformInterpreter` turns
 * a description into the function that carries it out.
 *
 * The tag field is the serialised form's only discriminator, so decoding is
 * unambiguous even where cases share field shapes — `InsurancePolicy` carries a
 * `deductible` like `ApplyDeductible`. Its value is the case name, derived
 * rather than declared.
 *
 * Each case supplies its own codec, which is what keeps the decode path's
 * refusals naming the field at fault: the parent derivation adds the tag and
 * delegates the fields to the case (ADR-001 §2, ADR-039 §3).
 */
@jsonDiscriminator(ResultTransformSpec.OpField)
sealed trait ResultTransformSpec

object ResultTransformSpec {

  /** The key naming which operation a step is. Read by this coproduct's decoder
    * and published in its schema, and stated here once so the two cannot
    * disagree. */
  final val OpField = "op"

  final case class ApplyDeductible(deductible: NonNegativeLong)     extends ResultTransformSpec
  /** The largest loss the entity bears. Strictly positive — a cap of zero would
    * leave it bearing nothing, which states that the transfer cannot fail. */
  final case class CapLosses(cap: PositiveLong)                     extends ResultTransformSpec
  /** The entity bears this fraction of each loss; the rest falls elsewhere, by
    * transfer or by an agreed share. The loss itself is unchanged — this scales
    * cached trial outcomes at the read edge rather than re-simulating, and it is
    * the only proportional scaling that can be ordered against a deductible or a
    * cap. Applies alongside `RelativeSeverity.ScaleSeverity` on the same
    * node — that one states the loss got smaller, this one states who pays for
    * it, and both factors apply. */
  final case class ScaleLosses(factor: RetentionFactor)             extends ResultTransformSpec
  final case class FilterBelowThreshold(threshold: NonNegativeLong) extends ResultTransformSpec

  /** Deductible-then-cap pair; cross-field rule `cap > deductible` (ADR-001). */
  final case class InsurancePolicy private (deductible: NonNegativeLong, cap: PositiveLong)
      extends ResultTransformSpec

  private def firstError(errors: List[ValidationError]): String =
    errors.map(_.message).mkString("; ")

  object ApplyDeductible {
    private case class Raw(deductible: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[ApplyDeductible] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.deductible)),
      JsonDecoder[Raw].mapOrFail(raw =>
        ValidationUtil.refineNonNegativeLong(
          raw.deductible, ErrorLocation.requestField(FieldName.Deductible))
          .map(ApplyDeductible(_)).left.map(firstError))
    )
  }

  object CapLosses {
    private case class Raw(cap: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[CapLosses] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.cap)),
      JsonDecoder[Raw].mapOrFail(raw =>
        ValidationUtil.refineLossCap(raw.cap, ErrorLocation.requestField(FieldName.Cap))
          .map(CapLosses(_)).left.map(firstError))
    )
  }

  object ScaleLosses {
    private case class Raw(factor: Double)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[ScaleLosses] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.factor.toFraction)),
      JsonDecoder[Raw].mapOrFail(raw =>
        ValidationUtil.refineRetentionFactor(raw.factor, ErrorLocation.requestField(FieldName.Factor))
          .map(ScaleLosses(_)).left.map(firstError))
    )
  }

  object FilterBelowThreshold {
    private case class Raw(threshold: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[FilterBelowThreshold] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.threshold)),
      JsonDecoder[Raw].mapOrFail(raw =>
        ValidationUtil.refineNonNegativeLong(
          raw.threshold, ErrorLocation.requestField(FieldName.Threshold))
          .map(FilterBelowThreshold(_)).left.map(firstError))
    )
  }

  object InsurancePolicy {
    def create(
      deductible: NonNegativeLong,
      cap: PositiveLong
    ): Validation[ValidationError, InsurancePolicy] =
      if (cap > deductible) Validation.succeed(InsurancePolicy(deductible, cap))
      else
        Validation.fail(ValidationError(
          location = ErrorLocation.requestField(FieldName.Cap),
          code = ValidationErrorCode.INVALID_COMBINATION,
          message = ValidationMessages.capMustExceedDeductible
        ))

    private case class Raw(deductible: Long, cap: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    /** Decodes through `create`, so the cross-field rule holds on the store-read
      * path as well as the request path (ADR-039 §3). Both refusals are
      * accumulated, because the two fields fail independently (ADR-001 §3). */
    given codec: JsonCodec[InsurancePolicy] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.deductible, s.cap)),
      JsonDecoder[Raw].mapOrFail { raw =>
        (ValidationUtil.refineNonNegativeLong(raw.deductible, ErrorLocation.requestField(FieldName.Deductible)),
         ValidationUtil.refineLossCap(raw.cap, ErrorLocation.requestField(FieldName.Cap))) match {
          case (Right(d), Right(c)) => create(d, c).toEither.left.map(e => firstError(e.toList))
          case (Left(e1), Left(e2)) => Left(firstError(e1 ++ e2))
          case (Left(e), _)         => Left(firstError(e))
          case (_, Left(e))         => Left(firstError(e))
        }
      }
    )
  }

  // Lawful: structural equality on scalar data (unlike the deleted
  // Equal[RiskTransform], whose function field compared by reference).
  given Equal[ResultTransformSpec] = Equal.default

  /** The tag comes from the case name and each case's fields from its own
    * codec, so no operation's serialised spelling is written as a string. */
  given codec: JsonCodec[ResultTransformSpec] = DeriveJsonCodec.gen[ResultTransformSpec]

  // Declared rather than left to a holder's derivation: auto-derivation driven
  // from a record holding this trait does not descend into it, so every holder
  // would publish an unconstrained object instead of the five operations. The
  // configuration names the discriminator, so the published description states
  // the key the decoder reads.
  given schema: Schema[ResultTransformSpec] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(OpField)
    Schema.derived[ResultTransformSpec]
}

/**
 * Ordered list of result-stage operations: position is application order;
 * interpretation folds front-to-back with `andThen`; combining pipelines is
 * list concatenation (appends, never reorders). Flat by design — a recursive
 * shape would give one sequence several representations and would need special
 * recursive serialization.
 */
final case class TransformPipeline(steps: List[ResultTransformSpec])

object TransformPipeline {

  val empty: TransformPipeline = TransformPipeline(Nil)

  /** Associative with `empty` as identity; deliberately NOT commutative (order matters). */
  given Identity[TransformPipeline] with {
    def identity: TransformPipeline = empty
    def combine(l: => TransformPipeline, r: => TransformPipeline): TransformPipeline =
      TransformPipeline(l.steps ++ r.steps)
  }

  given Equal[TransformPipeline] = Equal.default

  given JsonCodec[TransformPipeline] =
    JsonCodec[List[ResultTransformSpec]].transform(TransformPipeline(_), _.steps)
}
