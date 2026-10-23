package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.SeedVarId
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, SubmittedCollection, ValidationError}
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
    val treeNameV = refineNameField(req.name, ErrorLocation.requestField(FieldName.Name))
    val portfoliosV = refineExistingPortfolios(req.portfolios)
    val leavesV = refineExistingLeaves(req.leaves)
    val newPortfoliosV = refinePortfolioDefs(req.newPortfolios, SubmittedCollection.NewPortfolios)
    val newLeavesV = refineLeafDefs(req.newLeaves, SubmittedCollection.NewLeaves)

    Validation.validateWith(treeNameV, portfoliosV, leavesV, newPortfoliosV, newLeavesV) {
      (treeName, portfolios, leaves, newPortfolios, newLeaves) =>
        val providedSeeds = newLeaves.collect {
          case SubmittedLeaf(_, name, _, _, _, SeedVarIdChoice.Provided(seed)) => name -> seed
        }
        Validation.validateWith(
          validateTopologyUpdate(treeName, portfolios, leaves, newPortfolios, newLeaves),
          SeedVarId.requireDistinct(providedSeeds, ErrorLocation.requestField(FieldName.SeedVarId))
        ) { (rootName, _) =>
          val existingPortfolioNodes = portfolios.map { p =>
            p.name -> ResolvedNode(p.id, p.name, p.parent, NodeKind.Portfolio)
          }
          val existingLeafNodes = leaves.map { l =>
            l.name -> ResolvedNode(l.id, l.name, l.parent, NodeKind.Leaf)
          }
          val addedPortfolioNodes = newPortfolios.map { p =>
            p.name -> ResolvedNode(newId(), p.name, p.parent, NodeKind.Portfolio)
          }
          val addedLeafNodes = newLeaves.map { l =>
            l.name -> ResolvedNode(newId(), l.name, l.parent, NodeKind.Leaf)
          }

          val existingLeafOccurrenceAndShape = leaves.map(l => l.name -> (l.probability, l.shape)).toMap
          val addedLeafOccurrenceAndShape = newLeaves.map(l => l.name -> (l.probability, l.shape)).toMap

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
