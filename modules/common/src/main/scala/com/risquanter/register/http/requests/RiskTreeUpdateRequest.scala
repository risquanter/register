package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.http.requests.RiskTreeRequests.*

final case class RiskTreeUpdateRequest(
  name: String,
  portfolios: Seq[RiskPortfolioUpdateRequest],
  leaves: Seq[RiskLeafUpdateRequest],
  newPortfolios: Seq[RiskPortfolioDefinitionRequest],
  newLeaves: Seq[RiskLeafDefinitionRequest]
)
object RiskTreeUpdateRequest:
  given JsonCodec[RiskTreeUpdateRequest] = DeriveJsonCodec.gen

  def resolve(req: RiskTreeUpdateRequest, newId: IdGenerator): Validation[ValidationError, ResolvedUpdate] = {
    val treeNameV = refineNameField(req.name, "request.name")
    val portfoliosV = refineExistingPortfolios(req.portfolios, "request.portfolios")
    val leavesV = refineExistingLeaves(req.leaves, "request.leaves")
    val newPortfoliosV = refinePortfolioDefs(req.newPortfolios, "request.newPortfolios")
    val newLeavesV = refineLeafDefs(req.newLeaves, "request.newLeaves")

    Validation.validateWith(treeNameV, portfoliosV, leavesV, newPortfoliosV, newLeavesV) {
      (treeName, portfolios, leaves, newPortfolios, newLeaves) =>
        val providedSeeds = newLeaves.collect { case (name, _, _, _, Some(seed)) => name -> seed }
        Validation.validateWith(
          validateTopologyUpdate(treeName, portfolios, leaves.map { case (_, name, parent, _, _) => (name, parent) }, newPortfolios, newLeaves.map { case (name, parent, _, _, _) => (name, parent) }),
          requireUniqueSeedVarIds(providedSeeds)
        ) { (rootName, _) =>
          val existingPortfolioNodes = portfolios.map { case (id, name, parent) =>
            name -> ResolvedNode(id, name, parent, NodeKind.Portfolio)
          }
          val existingLeafNodes = leaves.map { case (id, name, parent, _, _) =>
            name -> ResolvedNode(id, name, parent, NodeKind.Leaf)
          }
          val addedPortfolioNodes = newPortfolios.map { case (name, parent) =>
            name -> ResolvedNode(newId(), name, parent, NodeKind.Portfolio)
          }
          val addedLeafNodes = newLeaves.map { case (name, parent, _, _, _) =>
            name -> ResolvedNode(newId(), name, parent, NodeKind.Leaf)
          }

          val existingLeafOccurrenceAndShape = leaves.collect { case (_, name, _, prob, dist) => name -> (prob, dist) }.toMap
          val addedLeafOccurrenceAndShape = newLeaves.map { case (name, _, prob, dist, _) => name -> (prob, dist) }.toMap

          ResolvedUpdate(
            treeName = treeName,
            existing = (existingPortfolioNodes ++ existingLeafNodes).toMap,
            added = (addedPortfolioNodes ++ addedLeafNodes).toMap,
            existingLeafOccurrenceAndShape = existingLeafOccurrenceAndShape,
            addedLeafOccurrenceAndShape = addedLeafOccurrenceAndShape,
            providedSeedVarIds = providedSeeds.toMap,
            rootName = rootName
          )
        }
    }.flatMap(identity)
  }

final case class RiskPortfolioUpdateRequest(
  id: String,
  name: String,
  parentName: Option[String]
)
object RiskPortfolioUpdateRequest:
  given JsonCodec[RiskPortfolioUpdateRequest] = DeriveJsonCodec.gen

final case class RiskLeafUpdateRequest(
  id:                String,
  name:              String,
  parentName:        Option[String],
  probability:       Double,
  distributionShape: DistributionShapeRequest,
  seedVarId:         Option[Long] = None
)
object RiskLeafUpdateRequest:
  given JsonCodec[RiskLeafUpdateRequest] = DeriveJsonCodec.gen
