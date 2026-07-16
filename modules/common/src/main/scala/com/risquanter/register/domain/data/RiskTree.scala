package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{SafeName, TreeId, NodeId, SeedVarId}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, JsonDecoder}
import zio.prelude.Validation
import io.github.iltotore.iron.*

/** Domain model for a risk tree configuration.
  * 
  * Stores risk nodes as a flat collection with references (childIds, parentId).
  * The TreeIndex provides O(1) node lookup and O(depth) ancestor path.
  * LEC data is computed on-demand via GET /risk-trees/:id/lec
  * 
  * **Note:** Simulation parameters (nTrials, parallelism, seeds) come from
  * SimulationConfig, not the RiskTree itself. This enables service-wide
  * reproducibility control via configuration.
  * 
  * @param id Unique risk tree identifier (assigned by repository)
  * @param name Human-readable risk tree name
  * @param nodes Flat collection of all nodes in the tree
  * @param rootId ID of the root node
  * @param index Tree index for O(1) node lookup and O(depth) ancestor path (built from nodes)
  * @param seedVarHighWater Highest seedVarId ever assigned in this tree — never
  *        decreases, so deleted leaves' stream IDs are never reused by
  *        auto-assignment (PLAN-SEED-IDENTITY §5.1). Merges as max across
  *        branches. Invariant: >= every leaf's seedVarId (checked in fromNodes).
  */
final case class RiskTree(
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex,
  seedVarHighWater: SeedVarId.SeedVarId
) {
  /** Get the root node */
  def root: RiskNode = index.nodes(rootId)
}

object RiskTree {
  import sttp.tapir.Schema
  import sttp.tapir.generic.auto.*
  import com.risquanter.register.http.codecs.IronTapirCodecs.given
  import com.risquanter.register.domain.data.iron.SafeId

  // Wire-shape DTO: mirrors JSON wire format (omits non-serialized TreeIndex)
  private case class RiskTreeJson(
    id: TreeId,
    name: SafeName.SafeName,
    nodes: Seq[RiskNode],
    rootId: NodeId,
    seedVarHighWater: SeedVarId.SeedVarId
  )
  private object RiskTreeJson:
    given jsonCodec: JsonCodec[RiskTreeJson] = DeriveJsonCodec.gen[RiskTreeJson]

  given schema: Schema[RiskTree] =
    Schema.derived[RiskTreeJson]
      .map(w => RiskTree.fromNodes(w.id, w.name, w.nodes, w.rootId, Some(w.seedVarHighWater)).toEither.toOption)(
        t => RiskTreeJson(t.id, t.name, t.nodes, t.rootId, t.seedVarHighWater)
      )

  // JSON codecs for Iron refined types
  // TreeId and NodeId codecs are in their companion objects (OpaqueTypes.scala)

  given safeNameEncoder: JsonEncoder[SafeName.SafeName] = 
    JsonEncoder[String].contramap(_.value)
  
  given safeNameDecoder: JsonDecoder[SafeName.SafeName] = 
    JsonDecoder[String].mapOrFail(s => SafeName.fromString(s).left.map(_.mkString(", ")))
  
  // TreeIndex is NOT serialized (reconstructed from nodes on load)
  // Custom codec that omits index field and rebuilds it from nodes on deserialization.
  // Decoding routes through fromNodes so every tree invariant (structure, rootId,
  // seedVarId distinctness) is defined exactly once and holds for stored trees too.
  given codec: JsonCodec[RiskTree] = {
    JsonCodec(
      // Encoder: Serialize without index
      JsonEncoder[RiskTreeJson].contramap[RiskTree] { tree =>
        RiskTreeJson(tree.id, tree.name, tree.nodes, tree.rootId, tree.seedVarHighWater)
      },
      // Decoder: Deserialize via the smart constructor (rebuilds index, re-validates)
      JsonDecoder[RiskTreeJson].mapOrFail { json =>
        fromNodes(json.id, json.name, json.nodes, json.rootId, Some(json.seedVarHighWater)).toEither.left.map(errors =>
          s"Invalid tree structure: ${errors.map(e => s"[${e.field}] ${e.message}").mkString("; ")}"
        )
      }
    )
  }

  /** Defense in depth: no two leaves in a tree may share a seedVarId.
    *
    * The request boundary enforces the same rule pre-assignment
    * (RiskTreeRequests); the rule itself is defined once on the SeedVarId
    * companion. This layer contributes the domain-scoped field path and covers
    * programmatically-built and store-loaded trees (correct-by-construction
    * layering, PLAN-SEED-IDENTITY §5.4).
    */
  private def requireDistinctSeedVarIds(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] =
    SeedVarId.requireDistinct(
      nodes.collect { case leaf: RiskLeaf => leaf.name.value -> leaf.seedVarId },
      field = "nodes.seedVarId"
    )
  
  /** Create a RiskTree from a flat list of nodes.
    *
    * Returns accumulated validation errors per ADR-010.
    *
    * @param seedVarHighWater `None` derives the always-valid floor — the highest
    *        seedVarId currently in the tree. Pass `Some` to preserve a persisted
    *        watermark (which may exceed the current max when leaves were deleted);
    *        a value below the current max is rejected, since it would let
    *        auto-assignment re-issue an in-use stream ID.
    */
  def fromNodes(
    id: TreeId,
    name: SafeName.SafeName,
    nodes: Seq[RiskNode],
    rootId: NodeId,
    seedVarHighWater: Option[SeedVarId.SeedVarId] = None
  ): Validation[ValidationError, RiskTree] = {
    Validation
      .validateWith(
        TreeIndex.fromNodeSeq(nodes),
        requireDistinctSeedVarIds(nodes),
        resolveSeedVarHighWater(nodes, seedVarHighWater)
      ) { (index, _, highWater) => (index, highWater) }
      .flatMap { (index, highWater) =>
        Validation
          .fromPredicateWith[ValidationError, TreeIndex](
            ValidationError(
              field = "rootId",
              code = ValidationErrorCode.CONSTRAINT_VIOLATION,
              message = s"rootId '${rootId.value}' not found in nodes"
            )
          )(index)((idx: TreeIndex) => idx.nodes.contains(rootId))
          .as(RiskTree(id, name, nodes, rootId, index, highWater))
      }
  }

  /** Resolve and validate the tree's seed watermark (see fromNodes scaladoc). */
  private def resolveSeedVarHighWater(
    nodes: Seq[RiskNode],
    provided: Option[SeedVarId.SeedVarId]
  ): Validation[ValidationError, SeedVarId.SeedVarId] = {
    val maxAssigned = nodes.collect { case leaf: RiskLeaf => leaf.seedVarId }.maxByOption(_.value)
    (provided, maxAssigned) match {
      case (Some(hw), Some(mx)) if hw.value < mx.value =>
        Validation.fail(ValidationError(
          field = "seedVarHighWater",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"seedVarHighWater ${hw.value} is below the highest assigned seedVarId ${mx.value}"
        ))
      case (Some(hw), _) => Validation.succeed(hw)
      case (None, Some(mx)) => Validation.succeed(mx)
      case (None, None) =>
        // Unreachable for valid trees (every path terminates at a leaf), but
        // total: a leafless node collection has no derivable watermark.
        Validation.fail(ValidationError(
          field = "seedVarHighWater",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = "tree has no leaves — seedVarHighWater cannot be derived"
        ))
    }
  }
  
  /** Unsafe version for internal use where validity is guaranteed.
    * 
    * Use only when nodes come from trusted sources (e.g., already validated).
    * 
    * @throws IllegalArgumentException if validation fails
    */
  def fromNodesUnsafe(
    id: TreeId,
    name: SafeName.SafeName,
    nodes: Seq[RiskNode],
    rootId: NodeId,
    seedVarHighWater: Option[SeedVarId.SeedVarId] = None
  ): RiskTree = {
    fromNodes(id, name, nodes, rootId, seedVarHighWater).toEither match {
      case Right(tree) => tree
      case Left(errors) =>
        throw new IllegalArgumentException(
          s"RiskTree invariant violated: ${errors.map(_.message).mkString("; ")}"
        )
    }
  }
  
  /** Create a RiskTree with a single root node (convenience for tests and simple cases) */
  def singleNode(
    id: TreeId,
    name: SafeName.SafeName,
    root: RiskNode
  ): Validation[ValidationError, RiskTree] = {
    fromNodes(id, name, Seq(root), root.id)
  }
  
  /** Unsafe version of singleNode for tests where validity is guaranteed.
    * 
    * @throws IllegalArgumentException if validation fails
    */
  def singleNodeUnsafe(
    id: TreeId,
    name: SafeName.SafeName,
    root: RiskNode
  ): RiskTree = {
    fromNodesUnsafe(id, name, Seq(root), root.id)
  }
}
