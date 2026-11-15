package com.risquanter.register.domain.data

import zio.NonEmptyChunk
import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import com.risquanter.register.domain.data.iron.{NonNegativeLong, PositiveLong, RetentionFactor, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

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

  /** The entity bears the first `deductible` of each loss and nothing beyond it.
    * Strictly positive — a deductible of zero would leave it bearing nothing,
    * which states that the transfer cannot fail. */
  final case class ApplyDeductible(deductible: PositiveLong)        extends ResultTransformSpec
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

  /** The entity bears the first `deductible`, the policy covers the next `limit`,
    * and the loss above both is the entity's again. A deductible of zero is
    * first-unit coverage. */
  final case class InsurancePolicy(deductible: NonNegativeLong, limit: PositiveLong)
      extends ResultTransformSpec

  /** Every refusal in one sentence, which is what a decoder's failure channel
    * carries. All of them rather than the first, because the fields each case
    * refines fail independently (ADR-001 §3).
    *
    * Each refusal names the field it is about. A body reports at the request as
    * a whole, so the sentence is the only place the field can be named, and
    * each case computed that location in order to say it. */
  private def describeAll(errors: NonEmptyChunk[ValidationError]): String =
    errors.map(ValidationError.describe).mkString("; ")

  object ApplyDeductible {
    /** The submitted deductible refined, refused at `subject`'s `Deductible`. */
    def refine(deductible: Long, subject: ErrorSubject): Validation[ValidationError, ApplyDeductible] =
      ValidationUtil.toValidation(
        ValidationUtil.refineDeductible(deductible, ErrorLocation.Field(subject, FieldName.Deductible)))
        .map(ApplyDeductible(_))

    private case class Raw(deductible: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[ApplyDeductible] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.deductible)),
      JsonDecoder[Raw].mapOrFail(raw => refine(raw.deductible, ErrorSubject.Request).toEither.left.map(describeAll))
    )
  }

  object CapLosses {
    /** The submitted cap refined, refused at `subject`'s `Cap`. */
    def refine(cap: Long, subject: ErrorSubject): Validation[ValidationError, CapLosses] =
      ValidationUtil.toValidation(ValidationUtil.refineLossCap(cap, ErrorLocation.Field(subject, FieldName.Cap)))
        .map(CapLosses(_))

    private case class Raw(cap: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[CapLosses] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.cap)),
      JsonDecoder[Raw].mapOrFail(raw => refine(raw.cap, ErrorSubject.Request).toEither.left.map(describeAll))
    )
  }

  object ScaleLosses {
    /** The submitted factor refined, refused at `subject`'s `Factor`. */
    def refine(factor: Double, subject: ErrorSubject): Validation[ValidationError, ScaleLosses] =
      ValidationUtil.toValidation(
        ValidationUtil.refineRetentionFactor(factor, ErrorLocation.Field(subject, FieldName.Factor)))
        .map(ScaleLosses(_))

    private case class Raw(factor: Double)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[ScaleLosses] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.factor.toFraction)),
      JsonDecoder[Raw].mapOrFail(raw => refine(raw.factor, ErrorSubject.Request).toEither.left.map(describeAll))
    )
  }

  object FilterBelowThreshold {
    /** The submitted threshold refined, refused at `subject`'s `Threshold`. */
    def refine(threshold: Long, subject: ErrorSubject): Validation[ValidationError, FilterBelowThreshold] =
      ValidationUtil.toValidation(
        ValidationUtil.refineNonNegativeLong(threshold, ErrorLocation.Field(subject, FieldName.Threshold)))
        .map(FilterBelowThreshold(_))

    private case class Raw(threshold: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    given codec: JsonCodec[FilterBelowThreshold] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.threshold)),
      JsonDecoder[Raw].mapOrFail(raw => refine(raw.threshold, ErrorSubject.Request).toEither.left.map(describeAll))
    )
  }

  object InsurancePolicy {
    /** Both submitted figures refined, their refusals accumulated because the
      * two fields fail independently (ADR-001 §3). */
    def refine(deductible: Long, limit: Long, subject: ErrorSubject): Validation[ValidationError, InsurancePolicy] =
      Validation.validateWith(
        ValidationUtil.toValidation(
          ValidationUtil.refineNonNegativeLong(deductible, ErrorLocation.Field(subject, FieldName.Deductible))),
        ValidationUtil.toValidation(
          ValidationUtil.refinePositiveLong(limit, ErrorLocation.Field(subject, FieldName.Limit)))
      )(InsurancePolicy(_, _))

    private case class Raw(deductible: Long, limit: Long)
    private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

    /** Decodes through `refine`, so the field rules hold on the store-read path
      * as well as the request path (ADR-039 §3). */
    given codec: JsonCodec[InsurancePolicy] = JsonCodec(
      JsonEncoder[Raw].contramap(s => Raw(s.deductible, s.limit)),
      JsonDecoder[Raw].mapOrFail(raw =>
        refine(raw.deductible, raw.limit, ErrorSubject.Request).toEither.left.map(describeAll))
    )
  }

  // Lawful: structural equality on scalar data.
  given Equal[ResultTransformSpec] = Equal.default

  /** The tag comes from the case name and each case's fields from its own
    * codec, so no operation's serialised spelling is written as a string. */
  given codec: JsonCodec[ResultTransformSpec] = DeriveJsonCodec.gen[ResultTransformSpec]

  // Declared rather than left to a holder's derivation: auto-derivation driven
  // from a case class holding this trait does not descend into it, so every holder
  // would publish an unconstrained object instead of the five operations. The
  // configuration names the discriminator, so the published description states
  // the key the decoder reads.
  given schema: Schema[ResultTransformSpec] =
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

  /** Published as the array the codec above writes. Every list of steps is a
    * valid pipeline at this level — the length bound belongs to the mitigation
    * spec that holds one — so the conversion cannot fail. */
  given schema: Schema[TransformPipeline] =
    summon[Schema[List[ResultTransformSpec]]]
      .map[TransformPipeline](steps => Some(TransformPipeline(steps)))(_.steps)
}
