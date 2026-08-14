package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.{ContentHash, MitigationId, NodeId, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * What a mitigation applies to: a targeting predicate that resolves,
 * server-side, to the scoped node set. Single-variant sealed trait — the
 * override anchor is a separate `overrideAnchor` field on
 * `MitigationSpec.LeafStage`, not a target variant.
 */
sealed trait MitigationTarget

object MitigationTarget {

  final case class Predicate(predicate: TargetingPredicate) extends MitigationTarget

  given Equal[MitigationTarget] = Equal.default

  /** Wire format is the predicate source string (see `TargetingPredicate`);
    * decode re-runs `TargetingPredicate.create`, so a stored predicate is
    * re-validated on every read. */
  given codec: JsonCodec[MitigationTarget] =
    summon[JsonCodec[TargetingPredicate]].transform(Predicate(_), { case Predicate(p) => p })
}

/**
 * Global cross-mitigation application order: ascending numeric key, with the
 * MitigationId string as the stable tiebreak. The key is the stored source of
 * truth (merge-stable); any UI ordering is a skin over it. Override placement
 * presets sit at the extremes: baseline overrides apply first (relative ops
 * blend on top), final overrides apply last (assert the mitigated state).
 */
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
 * - `LeafStage` — param-stage (`RiskLeafTransform`), leaves only. When either
 *   component is an Override, `overrideBaseStamp` carries the `ContentHash` of
 *   the target leaf's `LeafSimContent` (DD-16 projection) at authoring time,
 *   and `overrideAnchor` names the single leaf the override asserts against
 *   (rename-stable). Both are present iff a component is an Override — a
 *   `Mitigation.create` cross-field rule.
 * - `ResultStage` — ordered `TransformPipeline` on `TrialOutcomes`, any node.
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
 * First-class, explicit mitigation entity — tree-level content (`RiskTree.
 * mitigations`), versioned/diffed/merged with the tree, never baked into node
 * params. Application semantics live in `MitigationApplication`; staleness
 * detection in the server's `MitigationStaleness` (PLAN-RISKTRANSFORM OD-6).
 */
final case class Mitigation private (
  id: MitigationId,
  name: SafeName.SafeName,
  target: MitigationTarget,
  spec: MitigationSpec,
  precedence: MitigationPrecedence
)

object Mitigation {

  /** Upper bound on the length of a single ResultStage pipeline — the count of
    * atomic result-stage operations (`ResultTransformSpec`: deductible, cap,
    * scale, threshold, insurance-policy) chained inside one mitigation, not the
    * number of mitigations (that is `RiskTree.mitigations` ≤ 1000). A guard-rail
    * ceiling, not a modeling maximum: a realistic pipeline stacks at most one of
    * each of the five op types, so 10 leaves headroom while still rejecting an
    * abusive or malformed pipeline. A persisted-content bound, re-checked on
    * every read via decode == create. */
  private val MaxPipelineSteps = 10

  /** Cross-field rules (accumulated):
    *  - LeafStage with an Override component ⇒ overrideBaseStamp AND
    *    overrideAnchor both defined; without an Override ⇒ both empty;
    *  - a ResultStage pipeline has at most `MaxPipelineSteps` steps.
    * Predicate validity is enforced when the `TargetingPredicate` is built, so
    * the target needs no further check here.
    */
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
 * D-4 provenance layer: one record per applied mitigation and resolution,
 * stored beside the simulation provenance in responses — never inside
 * `NodeProvenance` (DD-19 stays identity-free). `resolvedScope` is the node
 * set the application actually touched under this tree version and selection,
 * making results reproducible and explainable under dynamic scope.
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
