package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.SeedVarId
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, SubmittedCollection, ValidationError}
import com.risquanter.register.http.requests.RiskTreeRequests.*

/** Create request payloads for hierarchical risk trees. */
final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest]
)
object RiskTreeDefinitionRequest:
  given JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen

  def resolve(req: RiskTreeDefinitionRequest, newId: IdGenerator): Validation[ValidationError, ResolvedCreate] = {
    val treeNameV = refineNameField(req.name, ErrorLocation.requestField(FieldName.Name))
    val portfoliosV = refinePortfolioDefs(req.portfolios, SubmittedCollection.Portfolios)
    val leavesV = refineLeafDefs(req.leaves, SubmittedCollection.Leaves)

    Validation.validateWith(treeNameV, portfoliosV, leavesV) { (treeName, portfolios, leaves) =>
      val providedSeeds = leaves.collect {
        case SubmittedLeaf(_, name, _, _, _, SeedVarIdChoice.Provided(seed)) => name -> seed
      }
      Validation.validateWith(
        validateTopologyCreate(treeName, portfolios, leaves),
        SeedVarId.requireDistinct(providedSeeds, ErrorLocation.requestField(FieldName.SeedVarId))
      ) { (rootName, _) =>
        val portfolioNodes = portfolios.map { p =>
          p.name -> ResolvedNode(newId(), p.name, p.parent, NodeKind.Portfolio)
        }
        val leafNodes = leaves.map { l =>
          l.name -> ResolvedNode(newId(), l.name, l.parent, NodeKind.Leaf)
        }
        val nodes = (portfolioNodes ++ leafNodes).toMap
        val leafOccurrenceAndShape = leaves.map(l => l.name -> (l.probability, l.distribution)).toMap
        ResolvedCreate(treeName, nodes, leafOccurrenceAndShape, providedSeeds.toMap, rootName)
      }
    }.flatMap(identity)
  }

final case class RiskPortfolioDefinitionRequest(
  name: String,
  parentName: Option[String]
)
object RiskPortfolioDefinitionRequest:
  given JsonCodec[RiskPortfolioDefinitionRequest] = DeriveJsonCodec.gen

final case class RiskLeafDefinitionRequest(
  name:              String,
  parentName:        Option[String],
  probability:       Double,
  distributionShape: DistributionShapeRequest,
  seedVarId:         Option[Long] = None
)
object RiskLeafDefinitionRequest:
  given JsonCodec[RiskLeafDefinitionRequest] = DeriveJsonCodec.gen
