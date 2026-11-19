package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.Mitigation
import com.risquanter.register.domain.data.iron.{NonNegativeInt, SeedVarId}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, SubmittedCollection, ValidationError}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.http.requests.RiskTreeRequests.*

final case class RiskTreeUpdateRequest(
  name: String,
  portfolios: Seq[RiskPortfolioUpdateRequest],
  leaves: Seq[RiskLeafUpdateRequest],
  newPortfolios: Seq[RiskPortfolioDefinitionRequest],
  newLeaves: Seq[RiskLeafDefinitionRequest],
  mitigations: Seq[MitigationUpdateRequest],
  newMitigations: Seq[MitigationDefinitionRequest]
)
object RiskTreeUpdateRequest:
  given JsonCodec[RiskTreeUpdateRequest] = DeriveJsonCodec.gen

  /** The request refined and checked against the stored tree it updates:
    * each `portfolios` entry must name a stored portfolio, each `leaves` entry
    * a stored leaf, each `mitigations` entry a stored mitigation, and each
    * absolute statement's anchor a stored leaf. Those checks accumulate with
    * every other. The request lists the whole tree, so its counts are the
    * resulting tree's, and a submission above a tree ceiling is refused by its
    * count alone, before any entry is refined (ADR-017 §3). */
  def resolve(
    req: RiskTreeUpdateRequest,
    stored: TreeIndex,
    storedMitigations: Seq[Mitigation],
    newId: IdGenerator
  ): Validation[ValidationError, ResolvedUpdate] =
    requireWithinCeilings(
      NonNegativeInt.count(req.portfolios.size + req.leaves.size + req.newPortfolios.size + req.newLeaves.size),
      NonNegativeInt.count(req.mitigations.size + req.newMitigations.size)
    ).flatMap(_ => refine(req, stored, storedMitigations, newId))

  private def refine(
    req: RiskTreeUpdateRequest,
    stored: TreeIndex,
    storedMitigations: Seq[Mitigation],
    newId: IdGenerator
  ): Validation[ValidationError, ResolvedUpdate] = {
    val treeNameV = refineNameField(req.name, ErrorLocation.requestField(FieldName.Name))
    val portfoliosV = refineExistingPortfolios(req.portfolios)
    val leavesV = refineExistingLeaves(req.leaves)
    val newPortfoliosV = refinePortfolioDefs(req.newPortfolios, SubmittedCollection.NewPortfolios)
    val newLeavesV = refineLeafDefs(req.newLeaves, SubmittedCollection.NewLeaves)
    val storedNodesV = requireStoredNodes(req.portfolios, req.leaves, stored)
    val mitigationsV = refineExistingMitigations(req.mitigations, storedMitigations)
    val newMitigationsV = refineMitigationDefs(req.newMitigations, newId)
    val storedMitigationsV = requireStoredMitigations(req.mitigations, storedMitigations)

    Validation.validateWith(
      treeNameV, portfoliosV, leavesV, newPortfoliosV, newLeavesV, storedNodesV,
      mitigationsV, newMitigationsV, storedMitigationsV
    ) {
      (treeName, portfolios, leaves, newPortfolios, newLeaves, _, kept, added, _) =>
        val providedSeeds = newLeaves.collect {
          case SubmittedLeaf(_, name, _, _, _, SeedVarIdChoice.Provided(seed)) => name -> seed
        }
        val mitigations = kept ++ added
        Validation.validateWith(
          validateTopologyUpdate(treeName, portfolios, leaves, newPortfolios, newLeaves),
          SeedVarId.requireDistinct(providedSeeds, ErrorLocation.requestField(FieldName.SeedVarId)),
          requireStoredAnchors(mitigations, stored.nodes)
        ) { (rootName, _, _) =>
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

          ResolvedUpdate(
            treeName = treeName,
            existing = (existingPortfolioNodes ++ existingLeafNodes).toMap,
            added = (addedPortfolioNodes ++ addedLeafNodes).toMap,
            existingLeaves = leaves.map(l => l.name -> l).toMap,
            addedLeaves = newLeaves.map(l => l.name -> l).toMap,
            providedSeedVarIds = providedSeeds.toMap,
            rootName = rootName,
            mitigations = mitigations
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
  seedVarId:         Option[Long]
)
object RiskLeafUpdateRequest:
  given JsonCodec[RiskLeafUpdateRequest] = DeriveJsonCodec.gen
