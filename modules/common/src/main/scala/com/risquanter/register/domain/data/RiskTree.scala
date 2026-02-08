package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{SafeName, TreeId, NodeId}
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
  */
final case class RiskTree(
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex
) {
  /** Get the root node */
  def root: RiskNode = index.nodes(rootId)
}

object RiskTree {
  import sttp.tapir.Schema
  import com.risquanter.register.domain.data.iron.SafeId
  
  // Tapir schema: Use Schema.any to avoid recursive derivation issues with Iron types
  // TODO: Phase D - Replace with proper Schema derivation once Iron type schemas are implemented
  given schema: Schema[RiskTree] = Schema.any[RiskTree]
  
  // JSON codecs for Iron refined types
  // TreeId and NodeId codecs are in their companion objects (OpaqueTypes.scala)
  
  given safeNameEncoder: JsonEncoder[SafeName.SafeName] = 
    JsonEncoder[String].contramap(_.value)
  
  given safeNameDecoder: JsonDecoder[SafeName.SafeName] = 
    JsonDecoder[String].mapOrFail(s => SafeName.fromString(s).left.map(_.mkString(", ")))
  
  // TreeIndex is NOT serialized (reconstructed from nodes on load)
  // Custom codec that omits index field and rebuilds it from nodes on deserialization
  given codec: JsonCodec[RiskTree] = {
    // Create temporary struct without index for serialization
    case class RiskTreeJson(
      id: TreeId,
      name: SafeName.SafeName,
      nodes: Seq[RiskNode],
      rootId: NodeId
    )
    
    given jsonCodec: JsonCodec[RiskTreeJson] = DeriveJsonCodec.gen[RiskTreeJson]
    
    JsonCodec(
      // Encoder: Serialize without index
      JsonEncoder[RiskTreeJson].contramap[RiskTree] { tree =>
        RiskTreeJson(tree.id, tree.name, tree.nodes, tree.rootId)
      },
      // Decoder: Deserialize and rebuild index from nodes
      JsonDecoder[RiskTreeJson].mapOrFail { json =>
        // Validate and build TreeIndex
        TreeIndex.fromNodeSeq(json.nodes).toEither match {
          case Left(errors) =>
            Left(s"Invalid tree structure: ${errors.map(_.message).mkString("; ")}")
          case Right(index) =>
            // Check rootId exists
            if (!index.nodes.contains(json.rootId)) {
              Left(s"rootId '${json.rootId.value}' not found in nodes")
            } else {
              Right(RiskTree(
                id = json.id,
                name = json.name,
                nodes = json.nodes,
                rootId = json.rootId,
                index = index
              ))
            }
        }
      }
    )
  }
  
  /** Create a RiskTree from a flat list of nodes.
    * 
    * Returns accumulated validation errors per ADR-010.
    */
  def fromNodes(
    id: TreeId,
    name: SafeName.SafeName,
    nodes: Seq[RiskNode],
    rootId: NodeId
  ): Validation[ValidationError, RiskTree] = {
    TreeIndex.fromNodeSeq(nodes).flatMap { index =>
      Validation
        .fromPredicateWith[ValidationError, TreeIndex](
          ValidationError(
            field = "rootId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"rootId '${rootId.value}' not found in nodes"
          )
        )(index)((idx: TreeIndex) => idx.nodes.contains(rootId))
        .as(RiskTree(id, name, nodes, rootId, index))
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
    rootId: NodeId
  ): RiskTree = {
    fromNodes(id, name, nodes, rootId).toEither match {
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
