package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import zio.prelude.Validation
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.{
  AbsoluteReplacement, Mitigation, MitigationPrecedence, MitigationSpec, MitigationTarget, RelativeLikelihood,
  RelativeSeverity, ResultTransformSpec, RiskLeafTransform, TransformPipeline
}
import com.risquanter.register.domain.data.iron.{NodeId, ResidualProbability, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.http.requests.RiskTreeRequests.ResolvedSpec

/** A submitted mitigation effect, mirroring `MitigationSpec` case for case with
  * primitives in place of refined values. `toSpec` is the gate.
  *
  * The values are refined here rather than in the decoder, so that every
  * refusal in a tree request is reported together and each at its entry
  * (ADR-001 §2). An absolute statement names a stored leaf by its identifier
  * and carries no base stamp: the server computes the stamp from that leaf.
  *
  * The tag field and every nested tag are the stored spec's, so a stored
  * relative or result-stage spec's JSON is also a valid submission.
  */
@jsonDiscriminator(MitigationSpec.StageField)
sealed trait MitigationSpecRequest:
  /** Refine and gate this submission, reporting every refusal against `subject`. */
  def toSpec(subject: ErrorSubject): Validation[ValidationError, ResolvedSpec]

object MitigationSpecRequest:

  final case class LeafRelative(
    likelihood: RelativeLikelihoodRequest,
    severity:   RelativeSeverityRequest
  ) extends MitigationSpecRequest:
    def toSpec(subject: ErrorSubject): Validation[ValidationError, ResolvedSpec] =
      Validation.validateWith(likelihood.toLikelihood(subject), severity.toSeverity(subject))(
        (l, s) => ResolvedSpec.Complete(MitigationSpec.LeafRelative(l, s)))

  /** `anchor` is the identifier of a leaf the stored tree holds. */
  final case class LeafAbsolute(
    replacement: AbsoluteReplacementRequest,
    anchor:      String,
    stamp:       BaseStampChoice
  ) extends MitigationSpecRequest:
    def toSpec(subject: ErrorSubject): Validation[ValidationError, ResolvedSpec] =
      Validation.validateWith(
        replacement.toReplacement(subject),
        ValidationUtil.toValidation(
          ValidationUtil.refineId(anchor, ErrorLocation.Field(subject, FieldName.Anchor)).map(NodeId(_)))
      )(ResolvedSpec.Unstamped(_, _))

  /** The step-count rule is `MitigationSpec.ResultStage.create`'s, refused at
    * `subject`'s `Spec`. */
  final case class ResultStage(pipeline: List[ResultTransformSpecRequest]) extends MitigationSpecRequest:
    def toSpec(subject: ErrorSubject): Validation[ValidationError, ResolvedSpec] =
      Validation.validateAll(pipeline.map(_.toStep(subject))).flatMap(steps =>
        MitigationSpec.ResultStage.create(TransformPipeline(steps), ErrorLocation.Field(subject, FieldName.Spec))
          .map(ResolvedSpec.Complete(_)))

  /** The submitted form of a stored spec, and the inverse of `toSpec`. An
    * absolute statement keeps its stored stamp when it is resubmitted unchanged. */
  def fromSpec(spec: MitigationSpec): MitigationSpecRequest = spec match
    case MitigationSpec.LeafRelative(likelihood, severity) =>
      LeafRelative(RelativeLikelihoodRequest.fromLikelihood(likelihood), RelativeSeverityRequest.fromSeverity(severity))
    case MitigationSpec.LeafAbsolute(replacement, anchor, _) =>
      LeafAbsolute(AbsoluteReplacementRequest.fromReplacement(replacement), anchor.value, BaseStampChoice.KeepIfUnchanged)
    case MitigationSpec.ResultStage(pipeline) =>
      ResultStage(pipeline.steps.map(ResultTransformSpecRequest.fromStep))

  given codec: JsonCodec[MitigationSpecRequest] = DeriveJsonCodec.gen[MitigationSpecRequest]

  given schema: Schema[MitigationSpecRequest] =
    given Configuration = Configuration.default.withDiscriminator(MitigationSpec.StageField)
    Schema.derived[MitigationSpecRequest]

/** Whether a kept absolute statement keeps the stamp it was stored with. */
@jsonDiscriminator(BaseStampChoice.TagField)
enum BaseStampChoice:
  /** Keep the stored stamp when the replacement and the anchor are unchanged;
    * otherwise, and on a new mitigation, stamp the anchor leaf now. */
  case KeepIfUnchanged

  /** Stamp the anchor leaf now: the author re-affirms the statement after the
    * leaf changed. */
  case Restamp

object BaseStampChoice:
  /** The key naming which case a value is. Read by the decoder and published
    * in the schema, and stated here once so the two cannot disagree. */
  final val TagField = "kind"

  given codec: JsonCodec[BaseStampChoice] = DeriveJsonCodec.gen[BaseStampChoice]

  given schema: Schema[BaseStampChoice] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[BaseStampChoice]

/** A submitted relative likelihood change, mirroring `RelativeLikelihood`. */
@jsonDiscriminator(RiskLeafTransform.OpField)
sealed trait RelativeLikelihoodRequest:
  def toLikelihood(subject: ErrorSubject): Validation[ValidationError, RelativeLikelihood]

object RelativeLikelihoodRequest:
  case object Keep extends RelativeLikelihoodRequest:
    def toLikelihood(subject: ErrorSubject): Validation[ValidationError, RelativeLikelihood] =
      Validation.succeed(RelativeLikelihood.Keep)

  final case class Scale(factor: Double) extends RelativeLikelihoodRequest:
    def toLikelihood(subject: ErrorSubject): Validation[ValidationError, RelativeLikelihood] =
      ValidationUtil.toValidation(
        ValidationUtil.refineRetentionFactor(factor, ErrorLocation.Field(subject, FieldName.Factor)))
        .map(RelativeLikelihood.Scale(_))

  def fromLikelihood(likelihood: RelativeLikelihood): RelativeLikelihoodRequest = likelihood match
    case RelativeLikelihood.Keep          => Keep
    case RelativeLikelihood.Scale(factor) => Scale(factor.toFraction)

  given codec: JsonCodec[RelativeLikelihoodRequest] = DeriveJsonCodec.gen[RelativeLikelihoodRequest]

  given schema: Schema[RelativeLikelihoodRequest] =
    given Configuration = Configuration.default.withDiscriminator(RiskLeafTransform.OpField)
    Schema.derived[RelativeLikelihoodRequest]

/** A submitted relative severity change, mirroring `RelativeSeverity`. */
@jsonDiscriminator(RiskLeafTransform.OpField)
sealed trait RelativeSeverityRequest:
  def toSeverity(subject: ErrorSubject): Validation[ValidationError, RelativeSeverity]

object RelativeSeverityRequest:
  case object Keep extends RelativeSeverityRequest:
    def toSeverity(subject: ErrorSubject): Validation[ValidationError, RelativeSeverity] =
      Validation.succeed(RelativeSeverity.Keep)

  final case class ScaleSeverity(factor: Double) extends RelativeSeverityRequest:
    def toSeverity(subject: ErrorSubject): Validation[ValidationError, RelativeSeverity] =
      ValidationUtil.toValidation(
        ValidationUtil.refineRetentionFactor(factor, ErrorLocation.Field(subject, FieldName.Factor)))
        .map(RelativeSeverity.ScaleSeverity(_))

  def fromSeverity(severity: RelativeSeverity): RelativeSeverityRequest = severity match
    case RelativeSeverity.Keep                  => Keep
    case RelativeSeverity.ScaleSeverity(factor) => ScaleSeverity(factor.toFraction)

  given codec: JsonCodec[RelativeSeverityRequest] = DeriveJsonCodec.gen[RelativeSeverityRequest]

  given schema: Schema[RelativeSeverityRequest] =
    given Configuration = Configuration.default.withDiscriminator(RiskLeafTransform.OpField)
    Schema.derived[RelativeSeverityRequest]

/** A submitted absolute replacement, mirroring `AbsoluteReplacement`. */
@jsonDiscriminator(RiskLeafTransform.ReplacesField)
sealed trait AbsoluteReplacementRequest:
  def toReplacement(subject: ErrorSubject): Validation[ValidationError, AbsoluteReplacement]

object AbsoluteReplacementRequest:
  final case class Likelihood(probability: Double) extends AbsoluteReplacementRequest:
    def toReplacement(subject: ErrorSubject): Validation[ValidationError, AbsoluteReplacement] =
      residual(probability, subject).map(AbsoluteReplacement.Likelihood(_))

  final case class Severity(shape: DistributionShapeRequest) extends AbsoluteReplacementRequest:
    def toReplacement(subject: ErrorSubject): Validation[ValidationError, AbsoluteReplacement] =
      shape.toShape(subject).map(AbsoluteReplacement.Severity(_))

  final case class Both(probability: Double, shape: DistributionShapeRequest) extends AbsoluteReplacementRequest:
    def toReplacement(subject: ErrorSubject): Validation[ValidationError, AbsoluteReplacement] =
      Validation.validateWith(residual(probability, subject), shape.toShape(subject))(AbsoluteReplacement.Both(_, _))

  private def residual(probability: Double, subject: ErrorSubject): Validation[ValidationError, ResidualProbability] =
    ValidationUtil.toValidation(
      ValidationUtil.refineResidualProbability(probability, ErrorLocation.Field(subject, FieldName.Probability)))

  def fromReplacement(replacement: AbsoluteReplacement): AbsoluteReplacementRequest = replacement match
    case AbsoluteReplacement.Likelihood(probability) => Likelihood(probability.toFraction)
    case AbsoluteReplacement.Severity(shape)         => Severity(DistributionShapeRequest.fromShape(shape))
    case AbsoluteReplacement.Both(probability, shape) =>
      Both(probability.toFraction, DistributionShapeRequest.fromShape(shape))

  given codec: JsonCodec[AbsoluteReplacementRequest] = DeriveJsonCodec.gen[AbsoluteReplacementRequest]

  given schema: Schema[AbsoluteReplacementRequest] =
    given Configuration = Configuration.default.withDiscriminator(RiskLeafTransform.ReplacesField)
    Schema.derived[AbsoluteReplacementRequest]

/** A submitted result-stage step, mirroring `ResultTransformSpec`. Each gate
  * calls the case companion's own refinement. */
@jsonDiscriminator(ResultTransformSpec.OpField)
sealed trait ResultTransformSpecRequest:
  def toStep(subject: ErrorSubject): Validation[ValidationError, ResultTransformSpec]

object ResultTransformSpecRequest:
  final case class ApplyDeductible(deductible: Long) extends ResultTransformSpecRequest:
    def toStep(subject: ErrorSubject): Validation[ValidationError, ResultTransformSpec] =
      ResultTransformSpec.ApplyDeductible.refine(deductible, subject)

  final case class CapLosses(cap: Long) extends ResultTransformSpecRequest:
    def toStep(subject: ErrorSubject): Validation[ValidationError, ResultTransformSpec] =
      ResultTransformSpec.CapLosses.refine(cap, subject)

  final case class ScaleLosses(factor: Double) extends ResultTransformSpecRequest:
    def toStep(subject: ErrorSubject): Validation[ValidationError, ResultTransformSpec] =
      ResultTransformSpec.ScaleLosses.refine(factor, subject)

  final case class FilterBelowThreshold(threshold: Long) extends ResultTransformSpecRequest:
    def toStep(subject: ErrorSubject): Validation[ValidationError, ResultTransformSpec] =
      ResultTransformSpec.FilterBelowThreshold.refine(threshold, subject)

  final case class InsurancePolicy(deductible: Long, limit: Long) extends ResultTransformSpecRequest:
    def toStep(subject: ErrorSubject): Validation[ValidationError, ResultTransformSpec] =
      ResultTransformSpec.InsurancePolicy.refine(deductible, limit, subject)

  def fromStep(step: ResultTransformSpec): ResultTransformSpecRequest = step match
    case ResultTransformSpec.ApplyDeductible(deductible)     => ApplyDeductible(deductible)
    case ResultTransformSpec.CapLosses(cap)                  => CapLosses(cap)
    case ResultTransformSpec.ScaleLosses(factor)             => ScaleLosses(factor.toFraction)
    case ResultTransformSpec.FilterBelowThreshold(threshold) => FilterBelowThreshold(threshold)
    case ResultTransformSpec.InsurancePolicy(deductible, limit) => InsurancePolicy(deductible, limit)

  given codec: JsonCodec[ResultTransformSpecRequest] = DeriveJsonCodec.gen[ResultTransformSpecRequest]

  given schema: Schema[ResultTransformSpecRequest] =
    given Configuration = Configuration.default.withDiscriminator(ResultTransformSpec.OpField)
    Schema.derived[ResultTransformSpecRequest]

/** Create bucket: a mitigation the request adds. No id; the server mints it
  * (ADR-017 §1). `target` is the predicate's source text. */
final case class MitigationDefinitionRequest(
  name:       String,
  target:     String,
  spec:       MitigationSpecRequest,
  precedence: MitigationPrecedence
)

object MitigationDefinitionRequest:
  given codec: JsonCodec[MitigationDefinitionRequest] = DeriveJsonCodec.gen
  given schema: Schema[MitigationDefinitionRequest] = Schema.derived

/** Identity-preserving bucket: a stored mitigation the request keeps, by id. */
final case class MitigationUpdateRequest(
  id:         String,
  name:       String,
  target:     String,
  spec:       MitigationSpecRequest,
  precedence: MitigationPrecedence
)

object MitigationUpdateRequest:
  given codec: JsonCodec[MitigationUpdateRequest] = DeriveJsonCodec.gen
  given schema: Schema[MitigationUpdateRequest] = Schema.derived

  /** A stored mitigation as the entry that keeps it unchanged. The tree
    * builder sends one for every mitigation it loaded, so a save from it keeps
    * them. */
  def fromMitigation(m: Mitigation): MitigationUpdateRequest =
    val source = m.target match
      case MitigationTarget.Predicate(predicate) => predicate.source
    MitigationUpdateRequest(m.id.value, m.name.value, source, MitigationSpecRequest.fromSpec(m.spec), m.precedence)
