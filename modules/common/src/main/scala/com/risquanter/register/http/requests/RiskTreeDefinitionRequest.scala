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
      validateTopologyCreate(treeName, portfolios, leaves.map { case (name, parent, _) => (name, parent) }).map { rootName =>
        val portfolioNodes = portfolios.map { case (name, parent) =>
          name -> ResolvedNode(newId(), name, parent, NodeKind.Portfolio)
        }
        val leafNodes = leaves.map { case (name, parent, _) =>
          name -> ResolvedNode(newId(), name, parent, NodeKind.Leaf)
        }
        val nodes = (portfolioNodes ++ leafNodes).toMap
        val leafDistributions = leaves.map { case (name, _, dist) => name -> dist }.toMap
        ResolvedCreate(treeName, nodes, leafDistributions, rootName)
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
  name: String,
  parentName: Option[String],
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)
object RiskLeafDefinitionRequest:
  given JsonCodec[RiskLeafDefinitionRequest] = DeriveJsonCodec.gen
