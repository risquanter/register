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
  leaves: Seq[RiskLeafDefinitionRequest],
  newMitigations: Seq[MitigationDefinitionRequest]
)
object RiskTreeDefinitionRequest:
  given JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen

  /** The request refined. A create has no stored leaf, so an absolute
    * statement among its mitigations is refused at its anchor. */
  def resolve(req: RiskTreeDefinitionRequest, newId: IdGenerator): Validation[ValidationError, ResolvedCreate] = {
    val treeNameV = refineNameField(req.name, ErrorLocation.requestField(FieldName.Name))
    val portfoliosV = refinePortfolioDefs(req.portfolios, SubmittedCollection.Portfolios)
    val leavesV = refineLeafDefs(req.leaves, SubmittedCollection.Leaves)
    val mitigationsV = refineMitigationDefs(req.newMitigations, newId)

    Validation.validateWith(treeNameV, portfoliosV, leavesV, mitigationsV) { (treeName, portfolios, leaves, mitigations) =>
      val providedSeeds = leaves.collect {
        case SubmittedLeaf(_, name, _, _, _, SeedVarIdChoice.Provided(seed)) => name -> seed
      }
      Validation.validateWith(
        validateTopologyCreate(treeName, portfolios, leaves),
        SeedVarId.requireDistinct(providedSeeds, ErrorLocation.requestField(FieldName.SeedVarId)),
        requireStoredAnchors(mitigations, Map.empty)
      ) { (rootName, _, _) =>
        val portfolioNodes = portfolios.map { p =>
          p.name -> ResolvedNode(newId(), p.name, p.parent, NodeKind.Portfolio)
        }
        val leafNodes = leaves.map { l =>
          l.name -> ResolvedNode(newId(), l.name, l.parent, NodeKind.Leaf)
        }
        val nodes = (portfolioNodes ++ leafNodes).toMap
        ResolvedCreate(treeName, nodes, leaves.map(l => l.name -> l).toMap, providedSeeds.toMap, rootName, mitigations)
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
  seedVarId:         Option[Long]
)
object RiskLeafDefinitionRequest:
  given JsonCodec[RiskLeafDefinitionRequest] = DeriveJsonCodec.gen
