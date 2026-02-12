package com.risquanter.register.http.requests

import zio.prelude.Validation
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{ValidationUtil, SafeName, SafeId}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/** ADR-017 DTO validators for hierarchical risk tree operations. */
object RiskTreeRequests {
  type IdGenerator = () => SafeId.SafeId

  enum NodeKind {
    case Portfolio
    case Leaf
  }

  final case class ResolvedNode(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parentName: Option[SafeName.SafeName],
    kind: NodeKind
  )

  // Result of resolving a create request: all names refined, ids generated, topology validated, distributions validated.
  // `nodes` is keyed by name for easy parent resolution in the service layer; `leafDistributions` carry domain-validated params.
  final case class ResolvedCreate(
    treeName: SafeName.SafeName,
    nodes: Map[SafeName.SafeName, ResolvedNode],
    leafDistributions: Map[SafeName.SafeName, Distribution],
    rootName: SafeName.SafeName
  )

  // Result of resolving an update request: existing nodes keep caller ids, new nodes get generated ids.
  // Distributions are validated here so the service does not repeat cross-field checks.
  final case class ResolvedUpdate(
    treeName: SafeName.SafeName,
    existing: Map[SafeName.SafeName, ResolvedNode],
    added: Map[SafeName.SafeName, ResolvedNode],
    existingLeafDistributions: Map[SafeName.SafeName, Distribution],
    addedLeafDistributions: Map[SafeName.SafeName, Distribution],
    rootName: SafeName.SafeName
  )

  // Backwards-compatible entry points delegate to DTO companions.
  def resolveCreate(req: RiskTreeDefinitionRequest, newId: IdGenerator): Validation[ValidationError, ResolvedCreate] =
    RiskTreeDefinitionRequest.resolve(req, newId)

  def resolveUpdate(req: RiskTreeUpdateRequest, newId: IdGenerator): Validation[ValidationError, ResolvedUpdate] =
    RiskTreeUpdateRequest.resolve(req, newId)

  def validateDistributionUpdate(req: DistributionUpdateRequest): Validation[ValidationError, Distribution] =
    DistributionUpdateRequest.validate(req)

  def validateRename(req: NodeRenameRequest): Validation[ValidationError, SafeName.SafeName] =
    NodeRenameRequest.validate(req)

  private[requests] def refineParentName(raw: Option[String], field: String): Validation[ValidationError, Option[SafeName.SafeName]] = {
    raw.map(_.trim).filter(_.nonEmpty) match {
      case Some(value) => toValidation(ValidationUtil.refineName(value, field)).map(Some(_))
      case None => Validation.succeed(None)
    }
  }

  private[requests] def refineNameField(value: String, field: String): Validation[ValidationError, SafeName.SafeName] =
    toValidation(ValidationUtil.refineName(value, field))

  private[requests] def refinePortfolioDefs(
    portfolios: Seq[RiskPortfolioDefinitionRequest],
    baseLabel: String
  ): Validation[ValidationError, Seq[(SafeName.SafeName, Option[SafeName.SafeName])]] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      val base = s"$baseLabel[$idx]"
      Validation.validateWith(
        refineNameField(p.name, s"$base.name"),
        refineParentName(p.parentName, s"$base.parentName")
      )((name, parent) => (name, parent))
    }

  private[requests] def refineLeafDefs(
    leaves: Seq[RiskLeafDefinitionRequest],
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

  private[requests] def refineExistingPortfolios(
    portfolios: Seq[RiskPortfolioUpdateRequest],
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

  private[requests] def refineExistingLeaves(
    leaves: Seq[RiskLeafUpdateRequest],
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
  private[requests] def requireUniqueNames(allNames: Seq[SafeName.SafeName]): Validation[ValidationError, Set[SafeName.SafeName]] = {
    val duplicates = allNames.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
    if duplicates.nonEmpty then
      Validation.fail(ValidationError("request.names", ValidationErrorCode.AMBIGUOUS_REFERENCE, s"Duplicate names: ${duplicates.map(_.value).mkString(", ")}"))
    else Validation.succeed(allNames.toSet)
  }

  // Guard: pick exactly one root (prefer a portfolio root when any portfolios exist; otherwise allow lone leaf tree).
  private[requests] def requireSingleRoot(
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    labelPrefix: String
  ): Validation[ValidationError, SafeName.SafeName] = {
    val portfolioRoots = portfolios.collect { case (name, None) => name }
    val leafRoots = leaves.collect { case (name, None) => name }

    val roots =  portfolioRoots ++leafRoots

    roots match {
      case Seq() => Validation.fail(ValidationError(labelPrefix, ValidationErrorCode.REQUIRED_FIELD, "Exactly one root is required"))
      case Seq(root) => Validation.succeed(root)
      case _ => Validation.fail(ValidationError(labelPrefix, ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple roots found"))
    }
  }

  // Guard: leaves must point to portfolios as parents (unless lone leaf tree) and must exist.
  private[requests] def requireLeafParents(
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    portfolioNames: Set[SafeName.SafeName],
    allNameSet: Set[SafeName.SafeName],
    totalNodes: Int,
    labelPrefix: String
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(leaves) { (l, idx) =>
      l._2 match {
        case Some(parentName) if portfolioNames.contains(parentName) => Validation.succeed(())
        case Some(parentName) if allNameSet.contains(parentName) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.INVALID_NODE_TYPE, s"parentName '${parentName.value}' refers to a leaf; must reference a portfolio"))
        case Some(parentName) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parentName.value}' not found in portfolios"))
        case None if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case None => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.REQUIRED_FIELD, "leaf must have a parent portfolio"))
      }
    }.map(_ => ())

  // Guard: portfolios must point to portfolios in the provided set (or be root).
  private[requests] def requirePortfolioParents[A](
    portfolios: Seq[A],
    portfolioNames: Set[SafeName.SafeName],
    labelPrefix: String,
    parentOf: A => Option[SafeName.SafeName]
  ): Validation[ValidationError, Unit] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      parentOf(p) match {
        case Some(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case Some(parent) => Validation.fail(ValidationError(s"$labelPrefix[$idx].parentName", ValidationErrorCode.MISSING_REFERENCE, s"parentName '${parent.value}' not found in portfolios"))
        case None => Validation.succeed(())
      }
    }.map(_ => ())

  private[requests] def validateTopologyCreate(
    treeName: SafeName.SafeName,
    portfolios: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    leaves: Seq[(SafeName.SafeName, Option[SafeName.SafeName])]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val allNames = portfolios.map(_._1) ++ leaves.map(_._1)
    val portfolioNames = portfolios.map(_._1).toSet
    val totalNodes = portfolios.size + leaves.size

    for {
      allNameSet <- requireUniqueNames(allNames)
      root <- requireSingleRoot(portfolios, leaves, "request.portfolios")
      _ <- requireLeafParents(leaves, portfolioNames, allNameSet, totalNodes, "request.leaves")
      _ <- requirePortfolioParents(portfolios, portfolioNames, "request.portfolios", _._2)
      _ <- requireNoCycles(portfolios ++ leaves, "request")
      _ <- requireNonEmptyPortfolios(
        portfolioNames = portfolios.map(_._1),
        portfolioParents = portfolios.map(_._2),
        leafParents = leaves.map(_._2),
        labelPrefix = "request.portfolios"
      )
    } yield root
  }

  private[requests] def validateTopologyUpdate(
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
      root <- requireSingleRoot(
        portfolios = portfolios.map(p => p._2 -> p._3) ++ newPortfolios.map(p => p._1 -> p._2),
        leaves = leaves.map(l => l._1 -> l._2) ++ newLeaves.map(l => l._1 -> l._2),
        labelPrefix = "request.portfolios"
      )
      _ <- requireLeafParents(leaves, portfolioNames, allNameSet, totalNodes, "request.leaves")
      _ <- requireLeafParents(newLeaves, portfolioNames, allNameSet, totalNodes, "request.newLeaves")
      _ <- requirePortfolioParents(portfolios, portfolioNames, "request.portfolios", _._3)
      _ <- requirePortfolioParents(newPortfolios, portfolioNames, "request.newPortfolios", _._2)
      _ <- requireNoCycles(
        parents = (portfolios.map(p => p._2 -> p._3) ++ leaves.map(l => l._1 -> l._2) ++ newPortfolios.map(p => p._1 -> p._2) ++ newLeaves.map(l => l._1 -> l._2)),
        labelPrefix = "request"
      )
      _ <- requireNonEmptyPortfolios(
        portfolioNames = portNames,
        portfolioParents = portfolios.map(_._3) ++ newPortfolios.map(_._2),
        leafParents = leaves.map(_._2) ++ newLeaves.map(_._2),
        labelPrefix = "request.portfolios"
      )
    } yield root
  }

  // Guard: prevent cycles in a topology described by name -> parent pairs.
  private[requests] def requireNoCycles(
    parents: Seq[(SafeName.SafeName, Option[SafeName.SafeName])],
    labelPrefix: String
  ): Validation[ValidationError, Unit] = {
    val parentMap = parents.flatMap { case (name, parent) => parent.map(name -> _) }.toMap

    def isCycle(name: SafeName.SafeName, seen: Set[SafeName.SafeName]): Boolean = parentMap.get(name) match {
      case Some(p) if seen.contains(p) => true
      case Some(p) => isCycle(p, seen + p)
      case None => false
    }

    parentMap.keys.find(n => isCycle(n, Set(n))) match {
      case Some(cycle) => Validation.fail(ValidationError(labelPrefix, ValidationErrorCode.CONSTRAINT_VIOLATION, s"Cycle detected at node '${cycle.value}'"))
      case None => Validation.succeed(())
    }
  }

  /** Guard: every portfolio must have at least one child (portfolio or leaf).
    *
    * This rule, together with `requireNoCycles`, enforces the structural invariant
    * that '''every path from the root terminates at a RiskLeaf'''. The well-formed
    * tree corresponds to the recursive ADT:
    *
    * {{{Tree = Leaf(distribution) | Portfolio(children: NonEmptyList[Tree])}}}
    *
    * '''Proof (by induction on tree depth):'''
    *   - '''Base case:''' A lone `RiskLeaf` (no portfolios) trivially satisfies the
    *     invariant — the only terminal node is a leaf.
    *   - '''Inductive step:''' Assume every portfolio at depth < k has a child.
    *     A portfolio P at depth k has ≥1 child (by this guard). Each child is
    *     either a leaf (path terminates ✓) or a portfolio at depth k+1 which
    *     also has ≥1 child (by induction). Since the tree is finite and acyclic
    *     (`requireNoCycles`), every descending path must eventually reach a leaf.
    *
    * '''Valid examples:'''
    *   - `L`                       — lone leaf, no portfolios to check
    *   - `P ← L`                   — P has 1 child (leaf)
    *   - `P0 ← P1 ← L`            — P0 has child P1, P1 has child L
    *   - `P0 ← {P1 ← L1, P2 ← L2}` — both child portfolios have leaves
    *
    * '''Invalid example:'''
    *   - `P0 ← {P1 ← L1, P2}`     — P2 is a childless portfolio (terminal
    *     but not a leaf), so it can never contribute simulation results.
    *
    * '''Why no `totalNodes` exemption is needed:''' When `portfolioNames` is empty
    * (the lone-leaf case), there are zero portfolios to check, so this method
    * trivially succeeds. Any exemption based on `totalNodes == 1` would
    * incorrectly allow a single childless portfolio through.
    *
    * '''Defence-in-depth:''' `RiskPortfolio` has a `require(childIds.nonEmpty)`
    * invariant (ADR-010) that catches this at construction time. This guard
    * catches it earlier with a typed `ValidationError`.
    *
    * @see ADR-017 §Topology validation
    */
  private[requests] def requireNonEmptyPortfolios(
    portfolioNames: Seq[SafeName.SafeName],
    portfolioParents: Seq[Option[SafeName.SafeName]],
    leafParents: Seq[Option[SafeName.SafeName]],
    labelPrefix: String
  ): Validation[ValidationError, Unit] = {
    val childParents = (portfolioParents.flatten ++ leafParents.flatten)
      .groupBy(identity).view.mapValues(_.size).toMap
    val emptyParents = portfolioNames.filterNot(childParents.contains)

    if emptyParents.nonEmpty then
      Validation.fail(ValidationError(labelPrefix, ValidationErrorCode.EMPTY_COLLECTION, s"Every portfolio must have at least one child: ${emptyParents.map(_.value).mkString(", ")}"))
    else Validation.succeed(())
  }

  private[requests] def collectAllWithIndex[A, B](as: Seq[A])(f: (A, Int) => Validation[ValidationError, B]): Validation[ValidationError, Seq[B]] =
    as.zipWithIndex.foldLeft[Validation[ValidationError, List[B]]](Validation.succeed(Nil)) {
      case (acc, (a, idx)) => Validation.validateWith(acc, f(a, idx))((xs, b) => xs :+ b)
    }.map(_.toSeq)
}
