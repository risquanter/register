package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError
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
    val treeNameV = refineNameField(req.name, "request.name")
    val portfoliosV = refinePortfolioDefs(req.portfolios, "request.portfolios")
    val leavesV = refineLeafDefs(req.leaves, "request.leaves")

    Validation.validateWith(treeNameV, portfoliosV, leavesV) { (treeName, portfolios, leaves) =>
      val providedSeeds = leaves.collect { case (name, _, _, _, Some(seed)) => name -> seed }
      Validation.validateWith(
        validateTopologyCreate(treeName, portfolios, leaves.map { case (name, parent, _, _, _) => (name, parent) }),
        requireUniqueSeedVarIds(providedSeeds)
      ) { (rootName, _) =>
        val portfolioNodes = portfolios.map { case (name, parent) =>
          name -> ResolvedNode(newId(), name, parent, NodeKind.Portfolio)
        }
        val leafNodes = leaves.map { case (name, parent, _, _, _) =>
          name -> ResolvedNode(newId(), name, parent, NodeKind.Leaf)
        }
        val nodes = (portfolioNodes ++ leafNodes).toMap
        val leafOccurrenceAndShape = leaves.map { case (name, _, prob, dist, _) => name -> (prob, dist) }.toMap
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
