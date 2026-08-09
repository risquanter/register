package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.{ContentHash, MitigationId, NodeId, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * What a mitigation applies to. Targets resolve to and store stable node ids,
 * never names (survives renames; ADR-017 identity preservation). The VQL
 * `Predicate` variant is added in M3 as an additive sealed-trait extension —
 * exhaustive matches surface every site that must then handle it.
 */
sealed trait MitigationTarget

object MitigationTarget {

  /** Explicit stable-id set (non-empty — enforced in `Mitigation.create`). */
  final case class Nodes(ids: Set[NodeId]) extends MitigationTarget

  given Equal[MitigationTarget] = Equal.default

  private case class Raw(kind: String, ids: List[String])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[MitigationTarget] = JsonCodec(
    JsonEncoder[Raw].contramap { case Nodes(ids) =>
      Raw("nodes", ids.map(_.value).toList.sorted)
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("nodes", rawIds) =>
        val (errors, ids) = rawIds.partitionMap(NodeId.fromString)
        if (errors.nonEmpty) Left(errors.flatten.map(_.message).mkString("; "))
        else Right(Nodes(ids.toSet))
      case other => Left(s"invalid mitigation target kind '${other.kind}'")
    }
  )
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
 *   the target leaf's `LeafSimContent` (DD-16 projection) at authoring time —
 *   staleness layer 1's comparison basis; renames/reparents do not change it
 *   by construction. Stamp presence iff an Override component is a
 *   `Mitigation.create` cross-field rule.
 * - `ResultStage` — ordered `TransformPipeline` on `TrialOutcomes`, any node.
 */
sealed trait MitigationSpec

object MitigationSpec {

  final case class LeafStage(
    transform: RiskLeafTransform,
    overrideBaseStamp: Option[ContentHash]
  ) extends MitigationSpec

  final case class ResultStage(pipeline: TransformPipeline) extends MitigationSpec

  given Equal[MitigationSpec] = Equal.default

  private case class Raw(
    stage: String,
    transform: Option[RiskLeafTransform],
    overrideBaseStamp: Option[String],
    pipeline: Option[TransformPipeline]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[MitigationSpec] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case LeafStage(t, stamp)  => Raw("leaf", Some(t), stamp.map(_.value), None)
      case ResultStage(p)       => Raw("result", None, None, Some(p))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("leaf", Some(t), stamp, None) =>
        stamp match {
          case None => Right(LeafStage(t, None))
          case Some(s) =>
            ContentHash.fromString(s, "overrideBaseStamp")
              .map(h => LeafStage(t, Some(h)))
              .left.map(_.map(_.message).mkString("; "))
        }
      case Raw("result", None, None, Some(p)) => Right(ResultStage(p))
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

  /** Cross-field rules (accumulated):
    *  - target node set non-empty
    *  - LeafStage with an Override component ⇒ single-node target AND overrideBaseStamp defined
    *  - LeafStage without an Override component ⇒ overrideBaseStamp empty
    */
  def create(
    id: MitigationId,
    name: SafeName.SafeName,
    target: MitigationTarget,
    spec: MitigationSpec,
    precedence: MitigationPrecedence,
    fieldPrefix: String = "mitigation"
  ): Validation[ValidationError, Mitigation] = {
    val targetIds = target match { case MitigationTarget.Nodes(ids) => ids }

    val nonEmptyTargetV: Validation[ValidationError, Unit] =
      Validation.fromPredicateWith[ValidationError, Set[NodeId]](
        ValidationError(
          field = s"$fieldPrefix.target",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Mitigation target must name at least one node"
        )
      )(targetIds)(_.nonEmpty).map(_ => ())

    val overrideRulesV: Validation[ValidationError, Unit] = spec match {
      case MitigationSpec.LeafStage(transform, stamp) =>
        val overriding = hasOverrideComponent(transform)
        (overriding, stamp) match {
          case (true, None) =>
            Validation.fail(ValidationError(
              field = s"$fieldPrefix.overrideBaseStamp",
              code = ValidationErrorCode.REQUIRED_FIELD,
              message = "An Override mitigation must carry the base stamp it was authored against"
            ))
          case (false, Some(_)) =>
            Validation.fail(ValidationError(
              field = s"$fieldPrefix.overrideBaseStamp",
              code = ValidationErrorCode.INVALID_COMBINATION,
              message = "Only an Override mitigation may carry a base stamp"
            ))
          case (true, Some(_)) if targetIds.size != 1 =>
            Validation.fail(ValidationError(
              field = s"$fieldPrefix.target",
              code = ValidationErrorCode.INVALID_COMBINATION,
              message = "An Override mitigation targets exactly one node (one absolute statement cannot fit a set)"
            ))
          case _ => Validation.succeed(())
        }
      case MitigationSpec.ResultStage(_) => Validation.succeed(())
    }

    Validation
      .validateWith(nonEmptyTargetV, overrideRulesV) { (_, _) => () }
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
