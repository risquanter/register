package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.{ContentHash, MitigationId, NodeId, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

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
 * `LeafStage` — param-stage transform on leaves. An Override component requires
 * `overrideBaseStamp` (the target leaf's `LeafSimContent` hash at authoring time)
 * and `overrideAnchor` (the single leaf the override asserts against); both absent
 * when no Override is present — enforced by `Mitigation.create`.
 *
 * `ResultStage` — ordered `TransformPipeline` on trial outcomes, any node.
 */
sealed trait MitigationSpec

object MitigationSpec {

  final case class LeafStage(
    transform: RiskLeafTransform,
    overrideBaseStamp: Option[ContentHash],
    overrideAnchor: Option[NodeId]
  ) extends MitigationSpec

  final case class ResultStage(pipeline: TransformPipeline) extends MitigationSpec

  given Equal[MitigationSpec] = Equal.default

  private case class Raw(
    stage: String,
    transform: Option[RiskLeafTransform],
    overrideBaseStamp: Option[String],
    overrideAnchor: Option[String],
    pipeline: Option[TransformPipeline]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[MitigationSpec] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case LeafStage(t, stamp, anchor) => Raw("leaf", Some(t), stamp.map(_.value), anchor.map(_.value), None)
      case ResultStage(p)              => Raw("result", None, None, None, Some(p))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("leaf", Some(t), stamp, anchor, None) =>
        for {
          h <- stamp match {
            case None    => Right(None)
            case Some(s) => ContentHash.fromString(s, "overrideBaseStamp")
              .map(Some(_)).left.map(_.map(_.message).mkString("; "))
          }
          a <- anchor match {
            case None    => Right(None)
            case Some(s) => NodeId.fromString(s)
              .map(Some(_)).left.map(_.map(_.message).mkString("; "))
          }
        } yield LeafStage(t, h, a)
      case Raw("result", None, None, None, Some(p)) => Right(ResultStage(p))
      case other => Left(s"invalid mitigation spec: stage '${other.stage}' with mismatched fields")
    }
  )
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

  /** Maximum steps in one ResultStage pipeline. Realistic pipelines use at most one of each of the five op types. */
  private val MaxPipelineSteps = 10

  /** A pipeline with no steps has no effect and is rejected at construction. */
  private val MinPipelineSteps = 1

  /** Cross-field rules: an Override component requires both `overrideBaseStamp` and `overrideAnchor`;
    * a ResultStage pipeline must have between 1 and 10 steps. */
  def create(
    id: MitigationId,
    name: SafeName.SafeName,
    target: MitigationTarget,
    spec: MitigationSpec,
    precedence: MitigationPrecedence,
    fieldPrefix: String = "mitigation"
  ): Validation[ValidationError, Mitigation] = {

    val overrideRulesV: Validation[ValidationError, Unit] = spec match {
      case MitigationSpec.LeafStage(transform, stamp, anchor) =>
        (hasOverrideComponent(transform), stamp, anchor) match {
          case (true, Some(_), Some(_)) => Validation.succeed(())
          case (false, None, None)      => Validation.succeed(())
          case (true, _, _) =>
            val missing = List(
              Option.when(stamp.isEmpty)("base stamp"),
              Option.when(anchor.isEmpty)("override anchor")
            ).flatten
            Validation.fail(ValidationError(
              field = s"$fieldPrefix.spec",
              code = ValidationErrorCode.REQUIRED_FIELD,
              message = s"An Override mitigation must carry its ${missing.mkString(" and ")}"
            ))
          case (false, _, _) =>
            Validation.fail(ValidationError(
              field = s"$fieldPrefix.spec",
              code = ValidationErrorCode.INVALID_COMBINATION,
              message = "Only an Override mitigation may carry a base stamp or override anchor"
            ))
        }
      case MitigationSpec.ResultStage(_) => Validation.succeed(())
    }

    val stepsV: Validation[ValidationError, Unit] = spec match {
      case MitigationSpec.ResultStage(pipeline) if pipeline.steps.sizeIs < MinPipelineSteps =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.spec",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"result-stage pipeline is empty: at least $MinPipelineSteps step is required"
        ))
      case MitigationSpec.ResultStage(pipeline) if pipeline.steps.sizeIs > MaxPipelineSteps =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.spec",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"result-stage pipeline has too many steps: ${pipeline.steps.size} exceeds the limit of $MaxPipelineSteps"
        ))
      case _ => Validation.succeed(())
    }

    Validation
      .validateWith(overrideRulesV, stepsV) { (_, _) => () }
      .map(_ => new Mitigation(id, name, target, spec, precedence))
  }

  private def hasOverrideComponent(t: RiskLeafTransform): Boolean =
    (t.likelihood, t.distribution) match {
      case (_: LikelihoodTransform.Override, _)   => true
      case (_, _: DistributionTransform.Override) => true
      case _                                      => false
    }

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
      val nameV = ValidationUtil.toValidation(ValidationUtil.refineName(raw.name, "mitigation.name"))
      Validation
        .validateWith(idV, nameV) { (id, name) => (id, name) }
        .flatMap { case (id, name) =>
          create(id, name, raw.target, raw.spec, MitigationPrecedence(raw.precedence),
                 fieldPrefix = s"mitigation[id=${raw.id}]")
        }
        .toEither.left.map(_.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; "))
    }
  )

  given schema: Schema[Mitigation] = Schema.any[Mitigation]
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
  given schema: Schema[MitigationApplicationRecord] = Schema.any[MitigationApplicationRecord]
}
