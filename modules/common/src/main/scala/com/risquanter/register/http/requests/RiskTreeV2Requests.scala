package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{ValidationUtil, SafeName, SafeId}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
  * ADR-017 DTOs (V2) with separated portfolios/leaves and ULID-based updates.
  * These coexist with the legacy flat-node DTOs until the service layer is migrated.
  */
final case class RiskTreeDefinitionRequestV2(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequestV2],
  leaves: Seq[RiskLeafDefinitionRequestV2]
)

final case class RiskPortfolioDefinitionRequestV2(
  name: String,
  parentName: Option[String]
)

final case class RiskLeafDefinitionRequestV2(
  name: String,
  parentName: Option[String],
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)

final case class RiskTreeUpdateRequestV2(
  name: String,
  portfolios: Seq[RiskPortfolioUpdateRequestV2],
  leaves: Seq[RiskLeafUpdateRequestV2],
  newPortfolios: Seq[RiskPortfolioDefinitionRequestV2],
  newLeaves: Seq[RiskLeafDefinitionRequestV2]
)

final case class RiskPortfolioUpdateRequestV2(
  id: String,
  name: String,
  parentName: Option[String]
)

final case class RiskLeafUpdateRequestV2(
  id: String,
  name: String,
  parentName: Option[String],
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)

final case class DistributionUpdateRequestV2(
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)

final case class NodeRenameRequestV2(
  name: String
)

object RiskTreeV2Requests {
  given riskTreeDefinitionCodec: JsonCodec[RiskTreeDefinitionRequestV2] = DeriveJsonCodec.gen
  given riskPortfolioDefinitionCodec: JsonCodec[RiskPortfolioDefinitionRequestV2] = DeriveJsonCodec.gen
  given riskLeafDefinitionCodec: JsonCodec[RiskLeafDefinitionRequestV2] = DeriveJsonCodec.gen

  given riskTreeUpdateCodec: JsonCodec[RiskTreeUpdateRequestV2] = DeriveJsonCodec.gen
  given riskPortfolioUpdateCodec: JsonCodec[RiskPortfolioUpdateRequestV2] = DeriveJsonCodec.gen
  given riskLeafUpdateCodec: JsonCodec[RiskLeafUpdateRequestV2] = DeriveJsonCodec.gen

  given distributionUpdateCodec: JsonCodec[DistributionUpdateRequestV2] = DeriveJsonCodec.gen
  given nodeRenameCodec: JsonCodec[NodeRenameRequestV2] = DeriveJsonCodec.gen

  type IdGenerator = () => SafeId.SafeId

  private[requests] enum NodeKind {
    case Portfolio
    case Leaf
  }

  private[requests] final case class ResolvedNode(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parentName: Option[SafeName.SafeName],
    kind: NodeKind
  )

  // Result of resolving a create request: all names refined, ids generated, topology validated, distributions validated.
  // `nodes` is keyed by name for easy parent resolution in the service layer; `leafDistributions` carry domain-validated params.
  private[requests] final case class ResolvedCreate(
    treeName: SafeName.SafeName,
    nodes: Map[SafeName.SafeName, ResolvedNode],
    leafDistributions: Map[SafeName.SafeName, Distribution],
    rootName: SafeName.SafeName
  )

  // Result of resolving an update request: existing nodes keep caller ids, new nodes get generated ids.
  // Distributions are validated here so the service does not repeat cross-field checks.
  private[requests] final case class ResolvedUpdate(
    treeName: SafeName.SafeName,
    existing: Map[SafeName.SafeName, ResolvedNode],
    added: Map[SafeName.SafeName, ResolvedNode],
    existingLeafDistributions: Map[SafeName.SafeName, Distribution],
    addedLeafDistributions: Map[SafeName.SafeName, Distribution],
    rootName: SafeName.SafeName
  )

  // Resolve a create request: refine names, validate distributions, generate ids, and validate topology.
  def resolveCreate(req: RiskTreeDefinitionRequestV2, newId: IdGenerator): Validation[ValidationError, ResolvedCreate] = {
    val treeNameV = refineNameField(req.name, "request.name")
    val portfoliosV = refinePortfolioDefs(req.portfolios, "request.portfolios")
    val leavesV = refineLeafDefs(req.leaves, "request.leaves")

    Validation.validateWith(treeNameV, portfoliosV, leavesV) { (treeName, portfolios, leaves) =>
      // Topology must be consistent before we allocate ids; leaves map strips payload for topology only.
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

  // Resolve an update request: refine names/ids, generate ids for new nodes, validate combined topology, carry leaf payloads.
  def resolveUpdate(req: RiskTreeUpdateRequestV2, newId: IdGenerator): Validation[ValidationError, ResolvedUpdate] = {
    val treeNameV = refineNameField(req.name, "request.name")
    val portfoliosV = refineExistingPortfolios(req.portfolios, "request.portfolios")
    val leavesV = refineExistingLeaves(req.leaves, "request.leaves")
    val newPortfoliosV = refinePortfolioDefs(req.newPortfolios, "request.newPortfolios")
    val newLeavesV = refineLeafDefs(req.newLeaves, "request.newLeaves")

    Validation.validateWith(treeNameV, portfoliosV, leavesV, newPortfoliosV, newLeavesV) {
      (treeName, portfolios, leaves, newPortfolios, newLeaves) =>
        // Validate topology over existing + new nodes using names/parents only.
        validateTopologyUpdate(treeName, portfolios, leaves.map { case (_, name, parent, _) => (name, parent) }, newPortfolios, newLeaves.map { case (name, parent, _) => (name, parent) }).map { rootName =>
          val existingPortfolioNodes = portfolios.map { case (id, name, parent) =>
            name -> ResolvedNode(id, name, parent, NodeKind.Portfolio)
          }
          val existingLeafNodes = leaves.map { case (id, name, parent, _) =>
            name -> ResolvedNode(id, name, parent, NodeKind.Leaf)
          }
          val addedPortfolioNodes = newPortfolios.map { case (name, parent) =>
            name -> ResolvedNode(newId(), name, parent, NodeKind.Portfolio)
          }
          val addedLeafNodes = newLeaves.map { case (name, parent, _) =>
            name -> ResolvedNode(newId(), name, parent, NodeKind.Leaf)
          }

          val existingLeafDistributions = leaves.collect { case (_, name, _, dist) => name -> dist }.toMap
          val addedLeafDistributions = newLeaves.map { case (name, _, dist) => name -> dist }.toMap

          ResolvedUpdate(
            treeName = treeName,
            existing = (existingPortfolioNodes ++ existingLeafNodes).toMap,
            added = (addedPortfolioNodes ++ addedLeafNodes).toMap,
            existingLeafDistributions = existingLeafDistributions,
            addedLeafDistributions = addedLeafDistributions,
            rootName = rootName
          )
        }
    }.flatMap(identity)
  }

  def validateDistributionUpdate(req: DistributionUpdateRequestV2): Validation[ValidationError, Distribution] =
    Distribution.create(
      distributionType = req.distributionType,
      probability = req.probability,
      minLoss = req.minLoss,
      maxLoss = req.maxLoss,
      percentiles = req.percentiles,
      quantiles = req.quantiles,
      fieldPrefix = "request"
    )

  def validateRename(req: NodeRenameRequestV2): Validation[ValidationError, SafeName.SafeName] =
    toValidation(ValidationUtil.refineName(req.name, "request.name"))

  private def refineParentName(raw: Option[String], field: String): Validation[ValidationError, Option[SafeName.SafeName]] = {
    raw.map(_.trim).filter(_.nonEmpty) match {
      case Some(value) => toValidation(ValidationUtil.refineName(value, field)).map(Some(_))
      case None => Validation.succeed(None)
    }
  }

  private def refineNameField(value: String, field: String): Validation[ValidationError, SafeName.SafeName] =
    toValidation(ValidationUtil.refineName(value, field))

  private def refinePortfolioDefs(
    portfolios: Seq[RiskPortfolioDefinitionRequestV2],
    baseLabel: String
  ): Validation[ValidationError, Seq[(SafeName.SafeName, Option[SafeName.SafeName])]] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        refineNameField(p.name, s"$base.name"),
        refineParentName(p.parentName, s"$base.parentName")
      )((name, parent) => (name, parent))
    }

  private def refineLeafDefs(
    leaves: Seq[RiskLeafDefinitionRequestV2],
    baseLabel: String
  ): Validation[ValidationError, Seq[(SafeName.SafeName, Option[SafeName.SafeName], Distribution)]] =
    collectAllWithIndex(leaves) { (l, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        refineNameField(l.name, s"$base.name"),
        refineParentName(l.parentName, s"$base.parentName"),
        Distribution.create(l.distributionType, l.probability, l.minLoss, l.maxLoss, l.percentiles, l.quantiles, base)
      )((name, parent, dist) => (name, parent, dist))
    }

  private def refineExistingPortfolios(
    portfolios: Seq[RiskPortfolioUpdateRequestV2],
    baseLabel: String
  ): Validation[ValidationError, Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])]] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        toValidation(ValidationUtil.refineId(p.id, s"$base.id")),
        refineNameField(p.name, s"$base.name"),
        refineParentName(p.parentName, s"$base.parentName")
      )((id, name, parent) => (id, name, parent))
    }

  private def refineExistingLeaves(
    leaves: Seq[RiskLeafUpdateRequestV2],
    baseLabel: String
  ): Validation[ValidationError, Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName], Distribution)]] =
    collectAllWithIndex(leaves) { (l, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        toValidation(ValidationUtil.refineId(l.id, s"$base.id")),
        refineNameField(l.name, s"$base.name"),
        refineParentName(l.parentName, s"$base.parentName"),
        Distribution.create(l.distributionType, l.probability, l.minLoss, l.maxLoss, l.percentiles, l.quantiles, base)
      )((id, name, parent, dist) => (id, name, parent, dist))
    }

  // Guard: ensure names are unique across all nodes so parent references are unambiguous; returns the deduped set.
  private def requireUniqueNames(allNames: Seq[SafeName.SafeName]): Validation[ValidationError, Set[SafeName.SafeName]] = {
    val duplicates = allNames.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
    if duplicates.nonEmpty then
      Validation.fail(ValidationError("request.names", ValidationErrorCode.AMBIGUOUS_REFERENCE, s"Duplicate names: ${duplicates.map(_.value).mkString(", ")}"))
    else Validation.succeed(allNames.toSet)
  }

  // Guard: pick exactly one root (prefer portfolio; allow lone leaf when no portfolios).
  private def requireSingleRootCreate(
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val rootCandidates =
      if portfolios.nonEmpty then portfolios.collect { case (name, None) => name }
      else leaves.collect { case (name, None) => name }

    rootCandidates match {
      case Seq(root) => Validation.succeed(root)
      case Seq() => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.REQUIRED_FIELD, "Exactly one root is required"))
      case _ => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple roots found"))
    }
  }

  // Guard: pick exactly one root in the combined (existing + new) topology.
  private def requireSingleRootUpdate(
    portfolios: Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newPortfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newLeaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val portNames = portfolios.map(_._2) ++ newPortfolios.map(_._1)
    val leafNames = leaves.map(_._1) ++ newLeaves.map(_._1)

    val rootCandidates =
      if portNames.nonEmpty then (portfolios.map(_._3) ++ newPortfolios.map(_._2)).zip(portNames).collect { case (None, name) => name }
      else (leaves.map(_._2) ++ newLeaves.map(_._2)).zip(leafNames).collect { case (None, name) => name }

    rootCandidates match {
      case Seq(root) => Validation.succeed(root)
      case Seq() => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.REQUIRED_FIELD, "Exactly one root is required"))
      case _ => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple roots found"))
    }
  }

  // Guard: leaves in create must point to portfolios as parents (unless lone leaf tree) and must exist.
  private def requireLeafParentsCreate(
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName],
    allNameSet: Set[SafeName.SafeName],
    totalNodes: Int
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(leaves) { (l, idx) =>
      l._2 match {
        case Some(parentName) if portfolioNames.contains(parentName) => Validation.succeed(())
        case Some(parentName) if allNameSet.contains(parentName) => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${parentName.value}' refers to a leaf; must reference a portfolio"))
        case Some(parentName) => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parentName.value}' not found in portfolios"))
        case None if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }.map(_ => ())

  // Guard: existing leaves must keep valid portfolio parents after update topology.
  private def requireLeafParentsExisting(
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName],
    allNameSet: Set[SafeName.SafeName],
    totalNodes: Int,
    labelPrefix: String
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(leaves) { (l, idx) =>
      l._2 match {
        case Some(p) if portfolioNames.contains(p) => Validation.succeed(())
        case Some(p) if allNameSet.contains(p) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${p.value}' refers to a leaf; must reference a portfolio"))
        case Some(p) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${p.value}' not found in portfolios"))
        case None if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }.map(_ => ())

  // Guard: new leaves must point to portfolios in the combined name set.
  private def requireLeafParentsNew(
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName],
    allNameSet: Set[SafeName.SafeName],
    totalNodes: Int,
    labelPrefix: String
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(leaves) { (l, idx) =>
      l._2 match {
        case Some(p) if portfolioNames.contains(p) => Validation.succeed(())
        case Some(p) if allNameSet.contains(p) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${p.value}' refers to a leaf; must reference a portfolio"))
        case Some(p) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${p.value}' not found in portfolios"))
        case None if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }.map(_ => ())

  // Guard: portfolios in create can only parent other portfolios (or be root).
  private def requirePortfolioParentsCreate(
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName]
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      p._2 match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"request.portfolios[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }.map(_ => ())

  // Guard: existing portfolios must keep valid portfolio parents after update topology.
  private def requirePortfolioParentsExisting(
    portfolios: Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName]
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      p._3 match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"request.portfolios[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }.map(_ => ())

  // Guard: new portfolios must point to portfolios in the combined set.
  private def requirePortfolioParentsNew(
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName]
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      p._2 match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"request.newPortfolios[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }.map(_ => ())

  private def validateTopologyCreate(
    treeName: SafeName.SafeName,
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val allNames = portfolios.map(_._1) ++ leaves.map(_._1)
    val portfolioNames = portfolios.map(_._1).toSet
    val totalNodes = portfolios.size + leaves.size

    for {
      allNameSet <- requireUniqueNames(allNames)
      root <- requireSingleRootCreate(portfolios, leaves)
      _ <- requireLeafParentsCreate(leaves, portfolioNames, allNameSet, totalNodes)
      _ <- requirePortfolioParentsCreate(portfolios, portfolioNames)
      _ <- requireNoCyclesCreate(portfolios, leaves)
      _ <- requireNonEmptyPortfoliosCreate(portfolios, leaves)
    } yield root
  }

  private def validateTopologyUpdate(
    treeName: SafeName.SafeName,
    portfolios: Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newPortfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newLeaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val portNames = portfolios.map(_._2) ++ newPortfolios.map(_._1)
    val leafNames = leaves.map(_._1) ++ newLeaves.map(_._1)
    val allNames = portNames ++ leafNames
    val portfolioNames = portNames.toSet
    val totalNodes = portfolios.size + newPortfolios.size + leaves.size + newLeaves.size

    for {
      allNameSet <- requireUniqueNames(allNames)
      root <- requireSingleRootUpdate(portfolios, leaves, newPortfolios, newLeaves)
      _ <- requireLeafParentsExisting(leaves, portfolioNames, allNameSet, totalNodes, "request.leaves")
      _ <- requireLeafParentsNew(newLeaves, portfolioNames, allNameSet, totalNodes, "request.newLeaves")
      _ <- requirePortfolioParentsExisting(portfolios, portfolioNames)
      _ <- requirePortfolioParentsNew(newPortfolios, portfolioNames)
      _ <- requireNoCyclesUpdate(portfolios, leaves, newPortfolios, newLeaves)
      _ <- requireNonEmptyPortfoliosUpdate(portfolios, leaves, newPortfolios, newLeaves)
    } yield root
  }

  // Guard: prevent cycles in the proposed create topology.
  private def requireNoCyclesCreate(
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, Unit] = {
    val parents = (portfolios ++ leaves).flatMap { case (name, parent) => parent.map(name -> _) }.toMap

    def isCycle(name: SafeName.SafeName, seen: Set[SafeName.SafeName]): Boolean = parents.get(name) match {
      case Some(p) if seen.contains(p) => true
      case Some(p) => isCycle(p, seen + p)
      case None => false
    }

    parents.keys.find(n => isCycle(n, Set(n))) match {
      case Some(cycle) => Validation.fail(ValidationError("request", ValidationErrorCode.CONSTRAINT_VIOLATION, s"Cycle detected at node '${cycle.value}'"))
      case None => Validation.succeed(())
    }
  }

  // Guard: prevent cycles when combining existing and new nodes.
  private def requireNoCyclesUpdate(
    portfolios: Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newPortfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newLeaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, Unit] = {
    val existing = portfolios.map(p => p._2 -> p._3) ++ leaves.map(l => l._1 -> l._2)
    val added = newPortfolios.map(p => p._1 -> p._2) ++ newLeaves.map(l => l._1 -> l._2)
    val parents = (existing ++ added).flatMap { case (name, parent) => parent.map(name -> _) }.toMap

    def isCycle(name: SafeName.SafeName, seen: Set[SafeName.SafeName]): Boolean = parents.get(name) match {
      case Some(p) if seen.contains(p) => true
      case Some(p) => isCycle(p, seen + p)
      case None => false
    }

    parents.keys.find(n => isCycle(n, Set(n))) match {
      case Some(cycle) => Validation.fail(ValidationError("request", ValidationErrorCode.CONSTRAINT_VIOLATION, s"Cycle detected at node '${cycle.value}'"))
      case None => Validation.succeed(())
    }
  }

  // Guard: portfolios must not be left without children (unless single-node tree).
  private def requireNonEmptyPortfoliosCreate(
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, Unit] = {
    val childrenByParent = scala.collection.mutable.Map.empty[SafeName.SafeName, Int].withDefaultValue(0)
    leaves.foreach { case (_, parent) => parent.foreach(p => childrenByParent.update(p, childrenByParent(p) + 1)) }
    portfolios.foreach { case (_, parent) => parent.foreach(p => childrenByParent.update(p, childrenByParent(p) + 1)) }

    val totalNodes = portfolios.size + leaves.size
    val emptyParents = portfolios.collect { case (name, _) if childrenByParent(name) == 0 && !(totalNodes == 1) => name }

    if emptyParents.nonEmpty then
      Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.CONSTRAINT_VIOLATION, s"Portfolios cannot be left empty: ${emptyParents.map(_.value).mkString(", ")}"))
    else Validation.succeed(())
  }

  // Guard: after update, portfolios must not be empty (unless single-node tree).
  private def requireNonEmptyPortfoliosUpdate(
    portfolios: Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newPortfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newLeaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, Unit] = {
    val childrenByParent = scala.collection.mutable.Map.empty[SafeName.SafeName, Int].withDefaultValue(0)
    leaves.foreach { case (_, parent) => parent.foreach(p => childrenByParent.update(p, childrenByParent(p) + 1)) }
    newLeaves.foreach { case (_, parent) => parent.foreach(p => childrenByParent.update(p, childrenByParent(p) + 1)) }
    portfolios.foreach { case (_, _, parent) => parent.foreach(p => childrenByParent.update(p, childrenByParent(p) + 1)) }
    newPortfolios.foreach { case (_, parent) => parent.foreach(p => childrenByParent.update(p, childrenByParent(p) + 1)) }

    val totalNodes = portfolios.size + newPortfolios.size + leaves.size + newLeaves.size
    val emptyParents = (portfolios.map(_._2) ++ newPortfolios.map(_._1)).collect {
      case name if childrenByParent(name) == 0 && !(totalNodes == 1) => name
    }

    if emptyParents.nonEmpty then
      Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.CONSTRAINT_VIOLATION, s"Portfolios cannot be left empty: ${emptyParents.map(_.value).mkString(", ")}"))
    else Validation.succeed(())
  }

  private def collectAllWithIndex[A, B](as: Seq[A])(f: (A, Int) => Validation[ValidationError, B]): Validation[ValidationError, Seq[B]] =
    as.zipWithIndex.foldLeft[Validation[ValidationError, List[B]]](Validation.succeed(Nil)) {
      case (acc, (a, idx)) => Validation.validateWith(acc, f(a, idx))((xs, b) => xs :+ b)
    }.map(_.toSeq)
}
