package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{SafeName, NonNegativeLong}
import com.risquanter.register.domain.tree.TreeIndex
import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, JsonDecoder}
import io.github.iltotore.iron.*

/** Domain model for a risk tree configuration.
  * 
  * Stores the risk tree structure (RiskNode) AND its index for efficient operations.
  * LEC data is computed on-demand via GET /risk-trees/:id/lec
  * 
  * @param id Unique risk tree identifier (assigned by repository)
  * @param name Human-readable risk tree name
  * @param nTrials Number of Monte Carlo trials for execution (default: 10,000)
  * @param root Root node of the risk tree (can be RiskPortfolio or single RiskLeaf)
  * @param index Tree index for O(1) node lookup and O(depth) ancestor path (built from root)
  */
final case class RiskTree(
  id: NonNegativeLong,
  name: SafeName.SafeName,
  root: RiskNode,
  index: TreeIndex
)

object RiskTree {
  import sttp.tapir.Schema
  
  // Tapir schema: Use Schema.any to avoid recursive derivation issues with Iron types
  // TODO: Phase D - Replace with proper Schema derivation once Iron type schemas are implemented
  given schema: Schema[RiskTree] = Schema.any[RiskTree]
  
  // JSON codecs for Iron refined types
  given nonNegativeLongEncoder: JsonEncoder[NonNegativeLong] = 
    JsonEncoder[Long].contramap(identity)
  
  given nonNegativeLongDecoder: JsonDecoder[NonNegativeLong] = 
    JsonDecoder[Long].mapOrFail(l => l.refineEither[constraint.numeric.GreaterEqual[0L]].left.map(_.toString))
  
  given safeNameEncoder: JsonEncoder[SafeName.SafeName] = 
    JsonEncoder[String].contramap(_.value)
  
  given safeNameDecoder: JsonDecoder[SafeName.SafeName] = 
    JsonDecoder[String].mapOrFail(s => SafeName.fromString(s).left.map(_.mkString(", ")))
  
  // TreeIndex is NOT serialized (reconstructed from root on load)
  // Custom codec that omits index field and rebuilds it from root on deserialization
  given codec: JsonCodec[RiskTree] = {
    // Create temporary struct without index for serialization
    case class RiskTreeJson(
      id: NonNegativeLong,
      name: SafeName.SafeName,
      root: RiskNode
    )
    
    given jsonCodec: JsonCodec[RiskTreeJson] = DeriveJsonCodec.gen[RiskTreeJson]
    
    JsonCodec(
      // Encoder: Serialize without index
      JsonEncoder[RiskTreeJson].contramap[RiskTree] { tree =>
        RiskTreeJson(tree.id, tree.name, tree.root)
      },
      // Decoder: Deserialize and rebuild index from root
      JsonDecoder[RiskTreeJson].map { json =>
        RiskTree(
          id = json.id,
          name = json.name,
          root = json.root,
          index = TreeIndex.fromTree(json.root)
        )
      }
    )
  }
}
