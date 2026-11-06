package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{NonNegativeInt, SafeName, TreeId, NodeId, SeedVarId}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.http.responses.TreeSummaryResponse
import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, JsonDecoder}
import zio.prelude.Validation
import io.github.iltotore.iron.autoRefine

/** Domain model for a risk tree configuration.
  * 
  * Stores risk nodes as a flat collection with references (childIds, parentId).
  * The TreeIndex provides O(1) node lookup and O(depth) ancestor path.
  * Loss exceedance curves are computed on demand by the workspace analysis
  * endpoints and are not held here.
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
  *        auto-assignment. Invariant: >= every leaf's seedVarId (checked in
  *        fromNodes).
  * @param mitigations Tree-level mitigation collection — explicit first-class
  *        entities scoping nodes by stable id, versioned with the tree content
  *        Application semantics: the server's MitigationApplication.
  */
final case class RiskTree private (
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex,
  seedVarHighWater: SeedVarId.SeedVarId,
  mitigations: Seq[Mitigation]
) {
  /** Get the root node */
  def root: RiskNode = index.nodes(rootId)

  /** The tree's identity for a list, a write acknowledgement or a bootstrap.
    * The projection is owned here, by the source (ADR-038 §5).
    */
  def toWire: TreeSummaryResponse = TreeSummaryResponse(id, name)
}

object RiskTree {
  import sttp.tapir.Schema
  import sttp.tapir.generic.auto.*
  import com.risquanter.register.http.codecs.IronTapirCodecs.given

  /** Wire form: the tree's serialised fields, omitting the index, which is
    * rebuilt from the nodes on decode (ADR-038 §1).
    *
    * `mitigations` is optional on the wire. Absent decodes to `Nil`, which is
    * what a mitigation-less tree and a payload predating mitigations both send,
    * and `Nil` encodes as absent so the two round-trip to the same bytes. */
  private case class RiskTreeRaw(
    id: TreeId,
    name: SafeName.SafeName,
    nodes: Seq[RiskNode],
    rootId: NodeId,
    seedVarHighWater: SeedVarId.SeedVarId,
    mitigations: Option[Seq[Mitigation]]
  )
  private object RiskTreeRaw:
    given jsonCodec: JsonCodec[RiskTreeRaw] = DeriveJsonCodec.gen[RiskTreeRaw]

  private def toRawShape(t: RiskTree): RiskTreeRaw =
    RiskTreeRaw(t.id, t.name, t.nodes, t.rootId, t.seedVarHighWater,
      Option.when(t.mitigations.nonEmpty)(t.mitigations))

  given schema: Schema[RiskTree] =
    Schema.derived[RiskTreeRaw]
      .map(w => RiskTree.fromNodes(w.id, w.name, w.nodes, w.rootId, Some(w.seedVarHighWater), w.mitigations.getOrElse(Nil)).toEither.toOption)(
        toRawShape
      )

  // TreeIndex is NOT serialized (reconstructed from nodes on load)
  // Custom codec that omits index field and rebuilds it from nodes on deserialization.
  // Decoding routes through fromNodes so every tree invariant (structure, rootId,
  // seedVarId distinctness) is defined exactly once and holds for stored trees too.
  given codec: JsonCodec[RiskTree] = {
    JsonCodec(
      // Encoder: Serialize without index
      JsonEncoder[RiskTreeRaw].contramap[RiskTree](toRawShape),
      // Decoder: Deserialize via the smart constructor (rebuilds index, re-validates)
      JsonDecoder[RiskTreeRaw].mapOrFail { json =>
        fromNodes(json.id, json.name, json.nodes, json.rootId, Some(json.seedVarHighWater), json.mitigations.getOrElse(Nil)).toEither.left.map(errors =>
          s"Invalid tree structure: ${errors.map(ValidationError.describe).mkString("; ")}"
        )
      }
    )
  }

  /** Upper bound on a tree's node count — a resource limit against oversized
    * trees on merges, store-loads, and programmatic construction. */
  private val MaxNodes: NonNegativeInt = 10000

  private def validateNodeCount(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] =
    if (nodes.sizeIs <= MaxNodes) Validation.succeed(())
    else Validation.fail(ValidationError(
      ErrorLocation.Whole,
      FieldProblem.TooManyNodes(NonNegativeInt.count(nodes.size), MaxNodes)
    ))

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
    seedVarHighWater: Option[SeedVarId.SeedVarId],
    mitigations: Seq[Mitigation]
  ): Validation[ValidationError, RiskTree] = {
    // Both uniqueness rules are defined once in their type's companion and
    // enforced twice: at the request boundary, which can name the field that
    // carried the offending value, and here, which covers merges, store reads
    // and programmatic construction. Here there is no field to name — on three
    // of this function's call sites there is no request at all — so both report
    // at the whole, and the offending values are named in the message.
    Validation
      .validateWith(
        TreeIndex.fromNodeSeq(nodes),
        SeedVarId.requireDistinct(
          nodes.collect { case leaf: RiskLeaf => leaf.name -> leaf.seedVarId },
          ErrorLocation.Whole
        ),
        resolveSeedVarHighWater(nodes, seedVarHighWater),
        validateNodeCount(nodes),
        SafeName.requireDistinct(nodes.map(_.name), ErrorLocation.Whole)
      ) { (index, _, highWater, _, _) => (index, highWater) }
      .flatMap { (index, highWater) =>
        Validation
          .validateWith(
            Validation
              .fromPredicateWith[ValidationError, TreeIndex](
                ValidationError(ErrorLocation.requestField(FieldName.RootId), FieldProblem.RootIdNotFound(rootId))
              )(index)((idx: TreeIndex) => idx.nodes.contains(rootId)),
            validateMitigations(mitigations)
          ) { (_, _) => () }
          .as(RiskTree(id, name, nodes, rootId, index, highWater, mitigations))
      }
  }

  /** Persisted-content bound on the mitigation collection. */
  private val MaxMitigations: NonNegativeInt = 1000

  /** Tree-level mitigation invariants: unique ids and names, and a bound on the
    * collection size. Predicate validity is enforced when each
    * `TargetingPredicate` is built; resolving a predicate to a node set, and
    * the stage-domain scope restriction, are server-side concerns
    * (`MitigationScopeResolver`), not tree invariants.
    */
  private def validateMitigations(
    mitigations: Seq[Mitigation]
  ): Validation[ValidationError, Unit] = {
    val mitigationsLocation = ErrorLocation.requestField(FieldName.Mitigations)

    val countV =
      if (mitigations.sizeIs <= MaxMitigations) Validation.succeed(())
      else Validation.fail(ValidationError(
        mitigationsLocation,
        FieldProblem.TooManyMitigations(NonNegativeInt.count(mitigations.size), MaxMitigations)
      ))

    Validation.validateWith(
      Mitigation.requireDistinctIds(mitigations, mitigationsLocation),
      Mitigation.requireDistinctNames(mitigations, mitigationsLocation),
      countV
    ) { (_, _, _) => () }
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
          ErrorLocation.requestField(FieldName.SeedVarHighWater),
          FieldProblem.SeedVarHighWaterBelowMax(hw, mx)
        ))
      case (Some(hw), _) => Validation.succeed(hw)
      case (None, Some(mx)) => Validation.succeed(mx)
      case (None, None) =>
        // Unreachable for valid trees (every path terminates at a leaf), but
        // total: a leafless node collection has no derivable watermark.
        Validation.fail(ValidationError(
          ErrorLocation.requestField(FieldName.SeedVarHighWater),
          FieldProblem.SeedVarHighWaterNotDerivable
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
    seedVarHighWater: Option[SeedVarId.SeedVarId],
    mitigations: Seq[Mitigation]
  ): RiskTree = {
    fromNodes(id, name, nodes, rootId, seedVarHighWater, mitigations).toEither match {
      case Right(tree) => tree
      case Left(errors) =>
        throw new IllegalArgumentException(
          s"RiskTree invariant violated: ${errors.map(ValidationError.describe).mkString("; ")}"
        )
    }
  }
  
}
