package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
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

  private[requests] final case class LeafPayload(
    distributionType: String,
    probability: Double,
    minLoss: Option[Long],
    maxLoss: Option[Long],
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]]
  )

  private[requests] final case class ResolvedNode(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parentName: Option[SafeName.SafeName],
    kind: NodeKind
  )

  // Result of resolving a create request: all names refined, ids generated, topology validated.
  // `nodes` is keyed by name for easy parent resolution in the service layer; `leafPayloads` carry raw distribution params.
  private[requests] final case class ResolvedCreate(
    treeName: SafeName.SafeName,
    nodes: Map[SafeName.SafeName, ResolvedNode],
    leafPayloads: Map[SafeName.SafeName, LeafPayload],
    rootName: SafeName.SafeName
  )

  // Result of resolving an update request: existing nodes keep caller ids, new nodes get generated ids.
  // Payload maps let the service build validated Distributions per leaf after topology is confirmed.
  private[requests] final case class ResolvedUpdate(
    treeName: SafeName.SafeName,
    existing: Map[SafeName.SafeName, ResolvedNode],
    added: Map[SafeName.SafeName, ResolvedNode],
    existingLeafPayloads: Map[SafeName.SafeName, LeafPayload],
    addedLeafPayloads: Map[SafeName.SafeName, LeafPayload],
    rootName: SafeName.SafeName
  )

  // Resolve a create request: refine names, generate ids, validate topology, and pass through leaf payloads unvalidated.
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
        val leafPayloads = leaves.map { case (name, _, payload) => name -> payload }.toMap
        ResolvedCreate(treeName, nodes, leafPayloads, rootName)
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

          val existingLeafPayloads = leaves.collect { case (_, name, _, payload) => name -> payload }.toMap
          val addedLeafPayloads = newLeaves.map { case (name, _, payload) => name -> payload }.toMap

          ResolvedUpdate(
            treeName = treeName,
            existing = (existingPortfolioNodes ++ existingLeafNodes).toMap,
            added = (addedPortfolioNodes ++ addedLeafNodes).toMap,
            existingLeafPayloads = existingLeafPayloads,
            addedLeafPayloads = addedLeafPayloads,
            rootName = rootName
          )
        }
    }.flatMap(identity)
  }

  def validateDistributionUpdate(req: DistributionUpdateRequestV2): Validation[ValidationError, DistributionUpdateRequestV2] = {
    val base = "request"
    val distTypeV = toValidation(ValidationUtil.refineDistributionType(req.distributionType, s"$base.distributionType"))
    val probV = toValidation(ValidationUtil.refineProbability(req.probability, s"$base.probability"))
    val minV = req.minLoss match {
      case Some(v) => toValidation(ValidationUtil.refineNonNegativeLong(v, s"$base.minLoss")).map(Some(_))
      case None    => Validation.succeed(None)
    }
    val maxV = req.maxLoss match {
      case Some(v) => toValidation(ValidationUtil.refineNonNegativeLong(v, s"$base.maxLoss")).map(Some(_))
      case None    => Validation.succeed(None)
    }

    val crossV: Validation[ValidationError, Unit] = distTypeV.map(_.toString).flatMap {
      case "expert" =>
        (req.percentiles, req.quantiles) match {
          case (Some(pct), Some(q)) if pct.nonEmpty && q.nonEmpty && pct.length == q.length => Validation.succeed(())
          case (Some(_), Some(q)) if q.isEmpty => Validation.fail(ValidationError(s"$base.quantiles", ValidationErrorCode.REQUIRED_FIELD, "Expert mode requires quantiles"))
          case (Some(pct), Some(q)) if pct.length != q.length =>
            Validation.fail(ValidationError(s"$base.distributionType", ValidationErrorCode.INVALID_COMBINATION, s"percentiles and quantiles length mismatch (${pct.length} vs ${q.length})"))
          case (None, _) => Validation.fail(ValidationError(s"$base.percentiles", ValidationErrorCode.REQUIRED_FIELD, "Expert mode requires percentiles"))
          case (_, None) => Validation.fail(ValidationError(s"$base.quantiles", ValidationErrorCode.REQUIRED_FIELD, "Expert mode requires quantiles"))
          case _ => Validation.fail(ValidationError(s"$base.distributionType", ValidationErrorCode.INVALID_COMBINATION, "Expert mode requires percentiles and quantiles"))
        }
      case "lognormal" =>
        (minV, maxV) match {
          case (Validation.Success(_, Some(minv)), Validation.Success(_, Some(maxv))) if minv < maxv => Validation.succeed(())
          case (Validation.Success(_, Some(_)), Validation.Success(_, Some(_))) =>
            Validation.fail(ValidationError(s"$base.minLoss", ValidationErrorCode.INVALID_LOGNORMAL_PARAMS, "minLoss must be < maxLoss"))
          case _ => Validation.fail(ValidationError(s"$base.distributionType", ValidationErrorCode.REQUIRED_FIELD, "Lognormal mode requires minLoss and maxLoss"))
        }
      case other => Validation.fail(ValidationError(s"$base.distributionType", ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE, s"Unsupported distribution type: $other"))
    }

    Validation.validateWith(distTypeV, probV, minV, maxV, crossV)((_, _, _, _, _) => req)
  }

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
  ): Validation[ValidationError, Seq[(SafeName.SafeName, Option[SafeName.SafeName], LeafPayload)]] =
    collectAllWithIndex(leaves) { (l, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        refineNameField(l.name, s"$base.name"),
        refineParentName(l.parentName, s"$base.parentName")
      )((name, parent) => (name, parent, LeafPayload(l.distributionType, l.probability, l.minLoss, l.maxLoss, l.percentiles, l.quantiles)))
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
  ): Validation[ValidationError, Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName], LeafPayload)]] =
    collectAllWithIndex(leaves) { (l, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        toValidation(ValidationUtil.refineId(l.id, s"$base.id")),
        refineNameField(l.name, s"$base.name"),
        refineParentName(l.parentName, s"$base.parentName")
      )((id, name, parent) => (id, name, parent, LeafPayload(l.distributionType, l.probability, l.minLoss, l.maxLoss, l.percentiles, l.quantiles)))
    }

  private def validateTopologyCreate(
    treeName: SafeName.SafeName,
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    // Enforce name uniqueness across all nodes so parent references are unambiguous.
    val allNames = portfolios.map(_._1) ++ leaves.map(_._1)
    val duplicates = allNames.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
    val dupV = if duplicates.nonEmpty then
      Validation.fail(ValidationError("request.names", ValidationErrorCode.AMBIGUOUS_REFERENCE, s"Duplicate names: ${duplicates.map(_.value).mkString(", ")}"))
    else Validation.succeed(())

    // Decide the single root: prefer a portfolio root; allow a lone leaf tree when no portfolios exist.
    val rootCandidates =
      if portfolios.nonEmpty then portfolios.collect { case (name, None) => name }
      else leaves.collect { case (name, None) => name }

    val rootV = rootCandidates match {
      case Seq(root) => Validation.succeed(root)
      case Seq() => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.REQUIRED_FIELD, "Exactly one root is required"))
      case _ => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple roots found"))
    }

    // Fast membership checks used by parent validations.
    val portfolioNames = portfolios.map(_._1).toSet
    val allNameSet = allNames.toSet

    val totalNodes = portfolios.size + leaves.size

    // Leaf parents must point to portfolios; a lone leaf with no portfolios is allowed to be root.
    val parentLeafV = collectAllWithIndex(leaves) { (l, idx) =>
      l._2 match {
        case Some(p) if portfolioNames.contains(p) => Validation.succeed(())
        case Some(p) if allNameSet.contains(p) => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${p.value}' refers to a leaf; must reference a portfolio"))
        case Some(p) => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${p.value}' not found in portfolios"))
        case None if portfolios.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }

    // Portfolio parents must point to other portfolios (or be root).
    val parentPortV = collectAllWithIndex(portfolios) { (p, idx) =>
      p._2 match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"request.portfolios[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }

    // Guard against cycles and empty portfolios (no children) when the tree has more than one node.
    val cycleV = detectCycles(portfolios, leaves)
    val nonEmptyV = validateNonEmptyPortfolios(portfolios, leaves)

    Validation.validateWith(dupV, rootV, parentLeafV, parentPortV, cycleV, nonEmptyV)((_, root, _, _, _, _) => root)
  }

  private def validateTopologyUpdate(
    treeName: SafeName.SafeName,
    portfolios: Seq[(SafeId.SafeId, SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newPortfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    newLeaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    // Enforce name uniqueness across existing and newly added nodes.
    val portNames = portfolios.map(_._2) ++ newPortfolios.map(_._1)
    val leafNames = leaves.map(_._1) ++ newLeaves.map(_._1)
    val allNames = portNames ++ leafNames

    val duplicates = allNames.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
    val dupV = if duplicates.nonEmpty then
      Validation.fail(ValidationError("request.names", ValidationErrorCode.AMBIGUOUS_REFERENCE, s"Duplicate names: ${duplicates.map(_.value).mkString(", ")}"))
    else Validation.succeed(())

    // Single root: prefer any portfolio roots if portfolios exist; otherwise allow a single leaf root.
    val rootCandidates =
      if portNames.nonEmpty then (portfolios.map(_._3) ++ newPortfolios.map(_._2)).zip(portNames).collect { case (None, name) => name }
      else (leaves.map(_._2) ++ newLeaves.map(_._2)).zip(leafNames).collect { case (None, name) => name }

    val rootV = rootCandidates match {
      case Seq(root) => Validation.succeed(root)
      case Seq() => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.REQUIRED_FIELD, "Exactly one root is required"))
      case _ => Validation.fail(ValidationError("request.portfolios", ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple roots found"))
    }

    // Fast membership sets for parent validation.
    val portfolioNames = portNames.toSet
    val allNameSet = allNames.toSet

    val totalNodes = portfolios.size + newPortfolios.size + leaves.size + newLeaves.size

    // Existing leaves: parent must be a portfolio; allow lone leaf root only when no portfolios and single node.
    val parentLeafV = collectAllWithIndex(leaves) { (l, idx) =>
      l._2 match {
        case Some(p) if portfolioNames.contains(p) => Validation.succeed(())
        case Some(p) if allNameSet.contains(p) => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${p.value}' refers to a leaf; must reference a portfolio"))
        case Some(p) => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${p.value}' not found in portfolios"))
        case None if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"request.leaves[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }

    // New leaves: same constraints as existing leaves.
    val parentLeafNewV = collectAllWithIndex(newLeaves) { (l, idx) =>
      l._2 match {
        case Some(p) if portfolioNames.contains(p) => Validation.succeed(())
        case Some(p) if allNameSet.contains(p) => Validation.fail(ValidationError(s"request.newLeaves[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${p.value}' refers to a leaf; must reference a portfolio"))
        case Some(p) => Validation.fail(ValidationError(s"request.newLeaves[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${p.value}' not found in portfolios"))
        case None if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"request.newLeaves[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }

    // Existing portfolios: parent must be a portfolio or root.
    val parentPortExistingV = collectAllWithIndex(portfolios) { (p, idx) =>
      p._3 match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"request.portfolios[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }

    // New portfolios: same constraint as existing portfolios.
    val parentPortNewV = collectAllWithIndex(newPortfolios) { (p, idx) =>
      p._2 match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"request.newPortfolios[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }

    // Guard against cycles and empty portfolios after applying updates.
    val cycleV = detectCyclesUpdate(portfolios, leaves, newPortfolios, newLeaves)
    val nonEmptyV = validateNonEmptyPortfoliosUpdate(portfolios, leaves, newPortfolios, newLeaves)

    Validation.validateWith(dupV, rootV, parentLeafV, parentLeafNewV, parentPortExistingV, parentPortNewV, cycleV, nonEmptyV)((_, root, _, _, _, _, _, _) => root)
  }

  private def detectCycles(
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

  private def detectCyclesUpdate(
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

  private def validateNonEmptyPortfolios(
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

  private def validateNonEmptyPortfoliosUpdate(
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
