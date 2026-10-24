package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import com.risquanter.register.domain.data.iron.{
  ContentHash, MitigationId, NodeId, OccurrenceProbability, ResidualProbability,
  SafeName, ValidationMessages, ValidationUtil
}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode}

/** What a mitigation applies to; resolves server-side to the scoped node set. */
sealed trait MitigationTarget

object MitigationTarget {

  final case class Predicate(predicate: TargetingPredicate) extends MitigationTarget

  given Equal[MitigationTarget] = Equal.default

  /** Wire format is the predicate source string; decode re-runs `TargetingPredicate.create`. */
  given codec: JsonCodec[MitigationTarget] =
    summon[JsonCodec[TargetingPredicate]].transform(Predicate(_), { case Predicate(p) => p })
}

/** Application order: ascending key, MitigationId tiebreak. Override presets sit at the extremes. */
final case class MitigationPrecedence(key: Int)

object MitigationPrecedence {
  val overrideBaseline: MitigationPrecedence = MitigationPrecedence(-1000)
  val default: MitigationPrecedence          = MitigationPrecedence(0)
  val overrideFinal: MitigationPrecedence    = MitigationPrecedence(1000)

  given Equal[MitigationPrecedence] = Equal.default
  given codec: JsonCodec[MitigationPrecedence] =
    JsonCodec[Int].transform(MitigationPrecedence(_), _.key)
}

/**
 * The mitigation's effect, by stage.
 *
 * The two param-stage cases are split by whether the statement is relative to
 * whatever the leaf currently says or absolute. A relative statement stays true
 * when the leaf changes, so it records nothing about the leaf it was written
 * against. An absolute statement asserts figures against one leaf as it stood,
 * so it carries that leaf and its content hash at authoring time — as plain
 * fields, which is what makes an absolute statement without them not
 * representable rather than refused.
 *
 * `ResultStage` is an ordered `TransformPipeline` on trial outcomes, applicable
 * to any node.
 *
 * The stage field is the serialised form's only discriminator: the cases are
 * distinguished by it rather than by which fields are present. Its value is the
 * case name, derived rather than declared.
 */
@jsonDiscriminator(MitigationSpec.StageField)
sealed trait MitigationSpec

/** The two param-stage specs, grouped so a caller that collects applicable
  * leaf-stage specs stays a single pattern. */
sealed trait LeafStageSpec extends MitigationSpec

object LeafStageSpec {

  /** A leaf-stage spec as a Kleisli arrow on the parameters it can change:
    * `LeafParams => Validation[ValidationError, LeafParams]`. Composition is
    * `flatMap`, and the identity arrow is `params => Validation.succeed(params)`,
    * which `LeafRelative(Keep, Keep)` is. An absolute replacement is the
    * constant arrow on the components it names: `Both` absorbs the whole arrow
    * that precedes it, and `Likelihood` or `Severity` absorbs one component and
    * passes the other through.
    *
    * `subject` names the leaf being transformed. `LeafParams` carries no
    * identity — identity, name, parent and seed identity are outside a
    * transform's reach — so a refusal has nothing of its own to report against.
    */
  def applyTo(
    spec:    LeafStageSpec,
    params:  LeafParams,
    subject: ErrorSubject
  ): Validation[ValidationError, LeafParams] = spec match {
    case MitigationSpec.LeafRelative(likelihood, severity) =>
      val probabilityV: Validation[ValidationError, OccurrenceProbability] = likelihood match {
        case RelativeLikelihood.Keep => Validation.succeed(params.probability)
        case RelativeLikelihood.Scale(factor) =>
          ValidationUtil.toValidation(ValidationUtil.refineOccurrenceProbability(
            params.probability * factor.toFraction,
            ErrorLocation.Field(subject, FieldName.Probability)
          ))
      }

      val shapeV: Validation[ValidationError, DistributionShape] = severity match {
        case RelativeSeverity.Keep => Validation.succeed(params.shape)
        case RelativeSeverity.ScaleSeverity(factor) =>
          RiskLeafTransform.scaleSeverity(params.shape, factor, subject)
      }

      Validation.validateWith(probabilityV, shapeV)(LeafParams.apply)

    // A replacement states post-control figures outright, so each case is the
    // constant arrow on the components it names.
    case MitigationSpec.LeafAbsolute(AbsoluteReplacement.Likelihood(probability), _, _) =>
      residualAsOccurrence(probability, subject).map(p => LeafParams(p, params.shape))

    case MitigationSpec.LeafAbsolute(AbsoluteReplacement.Severity(shape), _, _) =>
      Validation.succeed(LeafParams(params.probability, shape))

    case MitigationSpec.LeafAbsolute(AbsoluteReplacement.Both(probability, shape), _, _) =>
      residualAsOccurrence(probability, subject).map(p => LeafParams(p, shape))
  }

  /** A mitigation's residual probability read as a leaf's occurrence
    * probability. The residual interval (0, 1] sits inside the leaf's closed
    * [0, 1], so this never fails; it is refined rather than cast because the two
    * constraints are different types. */
  private def residualAsOccurrence(
    probability: ResidualProbability,
    subject:     ErrorSubject
  ): Validation[ValidationError, OccurrenceProbability] =
    ValidationUtil.toValidation(ValidationUtil.refineOccurrenceProbability(
      probability.toFraction,
      ErrorLocation.Field(subject, FieldName.Probability)
    ))
}

object MitigationSpec {

  /** The key naming which stage a spec belongs to. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. */
  final val StageField = "stage"

  /** A relative param-stage change. The statement stays true when the leaf
    * changes, so there is nothing to stamp and nothing to anchor. */
  final case class LeafRelative(
    likelihood: RelativeLikelihood,
    severity: RelativeSeverity
  ) extends LeafStageSpec

  /** An absolute param-stage replacement, with the leaf it was asserted against
    * and that leaf's content hash at authoring time. */
  final case class LeafAbsolute(
    replacement: AbsoluteReplacement,
    anchor: NodeId,
    baseStamp: ContentHash
  ) extends LeafStageSpec

  /** A result-stage pipeline. The step-count rule is this case's own relation
    * over its one field, so it is held here rather than one level up. */
  final case class ResultStage private (pipeline: TransformPipeline) extends MitigationSpec

  object ResultStage {

    /** A pipeline with no steps has no effect. */
    private val MinPipelineSteps = 1

    /** Above ten steps a pipeline is restating operations: a realistic one uses
      * at most one of each of the five operation types. */
    private val MaxPipelineSteps = 10

    def create(
      pipeline: TransformPipeline,
      location: ErrorLocation
    ): Validation[ValidationError, ResultStage] =
      pipeline.steps.size match {
        case n if n < MinPipelineSteps =>
          Validation.fail(ValidationError(
            location = location,
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = ValidationMessages.resultStagePipelineEmpty(MinPipelineSteps)
          ))
        case n if n > MaxPipelineSteps =>
          Validation.fail(ValidationError(
            location = location,
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = ValidationMessages.resultStagePipelineTooLong(n, MaxPipelineSteps)
          ))
        case _ => Validation.succeed(new ResultStage(pipeline))
      }

    /** Wire form: an object, so the discriminated parent has a field set to add
      * the stage tag to. */
    private case class Raw(pipeline: TransformPipeline)
    private object Raw { given rawCodec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw] }

    /** Decodes through `create`, so the step-count rule holds on the store-read
      * path as well as the request path (ADR-039 §3). */
    given codec: JsonCodec[ResultStage] = JsonCodec(
      JsonEncoder[Raw].contramap(stage => Raw(stage.pipeline)),
      Raw.rawCodec.decoder.mapOrFail(raw =>
        create(raw.pipeline, ErrorLocation.requestField(FieldName.Spec))
          .toEither.left.map(_.map(_.message).mkString("; "))
      )
    )
  }

  given Equal[MitigationSpec] = Equal.default

  given codec: JsonCodec[MitigationSpec] = DeriveJsonCodec.gen[MitigationSpec]

  // The published description of the spec coproduct. Auto-derivation cannot
  // reach it from a holder, because `LeafStageSpec` sits between this trait and
  // its two leaf cases and a derivation driven from the holder does not descend
  // through an intermediate sealed trait. Declaring it here is what lets every
  // record holding a spec describe it as the coproduct it is. The configuration
  // names the discriminator, so the published description states the key the
  // decoder reads.
  given schema: Schema[MitigationSpec] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(StageField)
    Schema.derived[MitigationSpec]
}

/**
 * A tree-level mitigation entity, versioned and merged with its tree.
 * Application semantics live in `MitigationApplication`; staleness detection in `MitigationStaleness`.
 */
final case class Mitigation private (
  id: MitigationId,
  name: SafeName.SafeName,
  target: MitigationTarget,
  spec: MitigationSpec,
  precedence: MitigationPrecedence
)

object Mitigation {

  /** The sole gate of the private constructor (ADR-001 §5). It holds no rule of
    * its own: the step-count rule belongs to `MitigationSpec.ResultStage`, which
    * owns the field it is about, and the spec's two param-stage cases carry
    * exactly the fields their statement needs, so there is no combination left
    * to forbid.
    */
  def create(
    id: MitigationId,
    name: SafeName.SafeName,
    target: MitigationTarget,
    spec: MitigationSpec,
    precedence: MitigationPrecedence
  ): Validation[ValidationError, Mitigation] =
    Validation.succeed(new Mitigation(id, name, target, spec, precedence))

  /** Mitigation identifiers are unique within one tree. One error per repeated
    * identifier, carrying how many mitigations hold it. */
  def requireDistinctIds(
    mitigations: Seq[Mitigation],
    location: ErrorLocation
  ): Validation[ValidationError, Unit] =
    ValidationUtil.requireDistinctBy(
      mitigations,
      _.id.value,
      (id, group) =>
        ValidationError(
          location = location,
          code = ValidationErrorCode.DUPLICATE_VALUE,
          message = ValidationMessages.duplicateMitigationIds(id.toString, group.size)
        )
    )

  /** Mitigation names are unique within one tree, so a user naming one in a
    * selection cannot mean two. */
  def requireDistinctNames(
    mitigations: Seq[Mitigation],
    location: ErrorLocation
  ): Validation[ValidationError, Unit] =
    ValidationUtil.requireDistinctBy(
      mitigations,
      _.name.value,
      (name, group) =>
        ValidationError(
          location = location,
          code = ValidationErrorCode.AMBIGUOUS_REFERENCE,
          message = ValidationMessages.duplicateMitigationNames(name.toString, group.size)
        )
    )

  given Equal[Mitigation] = Equal.default

  private case class Raw(
    id: String,
    name: String,
    target: MitigationTarget,
    spec: MitigationSpec,
    precedence: Int
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[Mitigation] = JsonCodec(
    JsonEncoder[Raw].contramap(m =>
      Raw(m.id.value, m.name.value, m.target, m.spec, m.precedence.key)),
    JsonDecoder[Raw].mapOrFail { raw =>
      val idV   = ValidationUtil.toValidation(MitigationId.fromString(raw.id))
      val nameV = ValidationUtil.toValidation(ValidationUtil.refineName(raw.name, ErrorLocation.requestField(FieldName.Name)))
      Validation
        .validateWith(idV, nameV) { (id, name) => (id, name) }
        .flatMap { case (id, name) =>
          create(id, name, raw.target, raw.spec, MitigationPrecedence(raw.precedence))
        }
        .toEither.left.map(_.toChunk.map(e => s"[${ErrorLocation.describe(e.location)}] ${e.message}").mkString("; "))
    }
  )

  // The published description is the record's real field list, with the spec
  // described as the coproduct it is. The auto-derivation import is scoped to
  // this body, so it reaches the nominal wrappers over Iron identifiers without
  // widening implicit scope across the file.
  given schema: Schema[Mitigation] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[Mitigation]
}

/**
 * One record per applied mitigation, stored in `LossDistribution.applied` in precedence order.
 * `resolvedScope` is the node set the application actually touched under this tree version and selection.
 */
final case class MitigationApplicationRecord(
  mitigationId: MitigationId,
  spec: MitigationSpec,
  resolvedScope: Set[NodeId],
  precedence: MitigationPrecedence
)

object MitigationApplicationRecord {
  given codec: JsonCodec[MitigationApplicationRecord] = DeriveJsonCodec.gen[MitigationApplicationRecord]
  // Derived against a body-scoped import, for the same reason as `Mitigation`'s.
  given schema: Schema[MitigationApplicationRecord] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[MitigationApplicationRecord]
}
