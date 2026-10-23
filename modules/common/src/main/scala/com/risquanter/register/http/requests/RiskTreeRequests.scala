package com.risquanter.register.http.requests

import zio.prelude.Validation
import com.risquanter.register.common.FolSymbols
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{ValidationUtil, SafeName, SafeId, SeedVarId, NodeId, OccurrenceProbability}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, SubmittedCollection, ValidationError, ValidationErrorCode
}

/** ADR-017 DTO validators for hierarchical risk tree operations. */
object RiskTreeRequests {
  type IdGenerator = () => SafeId.SafeId

  enum NodeKind {
    case Portfolio
    case Leaf
  }

  /** A node's parent: the tree's root has none, every other node names one.
    *
    * {{{
    * ParentRef.Root                        // the top portfolio
    * ParentRef.Under(SafeName("IT"))       // a leaf sitting under "IT"
    * }}}
    */
  enum ParentRef {
    case Root
    case Under(parent: SafeName.SafeName)
  }

  object ParentRef {
    /** The parent's name, or `None` for the root — for the places that build a
      * map keyed by parent or look an identifier up by name. */
    def nameOf(ref: ParentRef): Option[SafeName.SafeName] = ref match
      case Root           => None
      case Under(parent)  => Some(parent)
  }

  /** Where a leaf's seed identifier comes from. Absent in the request means the
    * server assigns one. */
  enum SeedVarIdChoice {
    case ServerAssigns
    case Provided(seedVarId: SeedVarId.SeedVarId)
  }

  /** A submitted node's parent together with the subject that names that node
    * in an error about it.
    *
    * The topology checks report against the node whose parent reference is
    * wrong, so they need both halves and nothing else.
    *
    * {{{
    * // the second submitted portfolio names a parent that does not exist
    * SubmittedParent(ErrorSubject.NodeAtIndex(SubmittedCollection.Portfolios, 1), ParentRef.Under("Typo"))
    * }}}
    */
  final case class SubmittedParent(subject: ErrorSubject, parent: ParentRef)

  /** A portfolio of the submitted body, refined, with the subject that names it
    * in any later error.
    *
    * {{{
    * // "portfolios": [ { "name": "IT", "parentName": null } ]
    * SubmittedPortfolio(ErrorSubject.nodeAtIndex(SubmittedCollection.Portfolios, 0), "IT", ParentRef.Root)
    * }}}
    */
  final case class SubmittedPortfolio(
    subject: ErrorSubject,
    name: SafeName.SafeName,
    parent: ParentRef
  )

  final case class SubmittedLeaf(
    subject: ErrorSubject,
    name: SafeName.SafeName,
    parent: ParentRef,
    probability: OccurrenceProbability,
    shape: DistributionShape,
    seedVarId: SeedVarIdChoice
  )

  final case class ExistingPortfolio(
    subject: ErrorSubject,
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parent: ParentRef
  )

  final case class ExistingLeaf(
    subject: ErrorSubject,
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parent: ParentRef,
    probability: OccurrenceProbability,
    shape: DistributionShape
  )

  final case class ResolvedNode(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parent: ParentRef,
    kind: NodeKind
  )

  // Result of resolving a create request: all names refined, ids generated, topology validated, shapes gated.
  // `nodes` is keyed by name for easy parent resolution in the service layer; `leafOccurrenceAndShape` carries the
  // domain-validated occurrence probability paired with the loss distribution shape for each leaf.
  final case class ResolvedCreate(
    treeName: SafeName.SafeName,
    nodes: Map[SafeName.SafeName, ResolvedNode],
    leafOccurrenceAndShape: Map[SafeName.SafeName, (OccurrenceProbability, DistributionShape)],
    providedSeedVarIds: Map[SafeName.SafeName, SeedVarId.SeedVarId],
    rootName: SafeName.SafeName
  )

  // Result of resolving an update request: existing nodes keep caller ids, new nodes get generated ids.
  // Shapes are gated here so the service does not repeat cross-field checks.
  final case class ResolvedUpdate(
    treeName: SafeName.SafeName,
    existing: Map[SafeName.SafeName, ResolvedNode],
    added: Map[SafeName.SafeName, ResolvedNode],
    existingLeafOccurrenceAndShape: Map[SafeName.SafeName, (OccurrenceProbability, DistributionShape)],
    addedLeafOccurrenceAndShape: Map[SafeName.SafeName, (OccurrenceProbability, DistributionShape)],
    providedSeedVarIds: Map[SafeName.SafeName, SeedVarId.SeedVarId],
    rootName: SafeName.SafeName
  )

  // Backwards-compatible entry points delegate to DTO companions.
  def resolveCreate(req: RiskTreeDefinitionRequest, newId: IdGenerator): Validation[ValidationError, ResolvedCreate] =
    RiskTreeDefinitionRequest.resolve(req, newId)

  def resolveUpdate(req: RiskTreeUpdateRequest, newId: IdGenerator): Validation[ValidationError, ResolvedUpdate] =
    RiskTreeUpdateRequest.resolve(req, newId)

  /** The single place the wire's absent parent and the domain's named case meet.
    * A JSON `null`, an absent key and a blank string all mean the root.
    */
  private[requests] def refineParentName(raw: Option[String], location: ErrorLocation): Validation[ValidationError, ParentRef] =
    raw.map(_.trim).filter(_.nonEmpty) match {
      case Some(value) => toValidation(ValidationUtil.refineName(value, location)).map(ParentRef.Under(_))
      case None => Validation.succeed(ParentRef.Root)
    }

  private[requests] def refineNameField(value: String, location: ErrorLocation): Validation[ValidationError, SafeName.SafeName] =
    toValidation(ValidationUtil.refineName(value, location))

  private[requests] def refinePortfolioDefs(
    portfolios: Seq[RiskPortfolioDefinitionRequest],
    collection: SubmittedCollection
  ): Validation[ValidationError, Seq[SubmittedPortfolio]] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      val subject = ErrorSubject.nodeAtIndex(collection, idx)
      Validation.validateWith(
        refineNameField(p.name, ErrorLocation.Field(subject, FieldName.Name)),
        refineParentName(p.parentName, ErrorLocation.Field(subject, FieldName.ParentName))
      )((name, parent) => SubmittedPortfolio(subject, name, parent))
    }

  private[requests] def refineLeafDefs(
    leaves: Seq[RiskLeafDefinitionRequest],
    collection: SubmittedCollection
  ): Validation[ValidationError, Seq[SubmittedLeaf]] =
    collectAllWithIndex(leaves) { (l, idx) =>
      val subject = ErrorSubject.nodeAtIndex(collection, idx)
      Validation.validateWith(
        refineNameField(l.name, ErrorLocation.Field(subject, FieldName.Name)),
        refineParentName(l.parentName, ErrorLocation.Field(subject, FieldName.ParentName)),
        toValidation(ValidationUtil.refineOccurrenceProbability(l.probability, ErrorLocation.Field(subject, FieldName.Probability))),
        l.distributionShape.toShape(subject),
        refineSeedVarIdChoice(l.seedVarId, ErrorLocation.Field(subject, FieldName.SeedVarId))
      )((name, parent, prob, shape, seed) => SubmittedLeaf(subject, name, parent, prob, shape, seed))
    }

  /** A caller-provided seedVarId on a leaf definition, range-refined. Absent
    * means the server assigns one. */
  private[requests] def refineSeedVarIdChoice(raw: Option[Long], location: ErrorLocation): Validation[ValidationError, SeedVarIdChoice] =
    raw match {
      case Some(value) => toValidation(ValidationUtil.refineSeedVarId(value, location)).map(SeedVarIdChoice.Provided(_))
      case None => Validation.succeed(SeedVarIdChoice.ServerAssigns)
    }

  /** An existing node's seedVarId is never accepted on update: a mutable seed
    * identifier would let an edit silently re-roll the leaf's stochastic
    * stream. */
  private[requests] def requireAbsentSeedVarId(raw: Option[Long], location: ErrorLocation): Validation[ValidationError, Unit] =
    raw match {
      case Some(_) => Validation.fail(ValidationError(location, ValidationErrorCode.CONSTRAINT_VIOLATION, ValidationMessages.seedVarIdImmutable))
      case None => Validation.succeed(())
    }

  private[requests] def refineExistingPortfolios(
    portfolios: Seq[RiskPortfolioUpdateRequest]
  ): Validation[ValidationError, Seq[ExistingPortfolio]] =
    collectAllWithIndex(portfolios) { (p, idx) =>
      val subject = ErrorSubject.nodeAtIndex(SubmittedCollection.Portfolios, idx)
      Validation.validateWith(
        toValidation(ValidationUtil.refineId(p.id, ErrorLocation.Field(subject, FieldName.Id))),
        refineNameField(p.name, ErrorLocation.Field(subject, FieldName.Name)),
        refineParentName(p.parentName, ErrorLocation.Field(subject, FieldName.ParentName))
      )((id, name, parent) => ExistingPortfolio(ErrorSubject.ExistingNode(NodeId(id)), id, name, parent))
    }

  private[requests] def refineExistingLeaves(
    leaves: Seq[RiskLeafUpdateRequest]
  ): Validation[ValidationError, Seq[ExistingLeaf]] =
    collectAllWithIndex(leaves) { (l, idx) =>
      val subject = ErrorSubject.nodeAtIndex(SubmittedCollection.Leaves, idx)
      Validation.validateWith(
        toValidation(ValidationUtil.refineId(l.id, ErrorLocation.Field(subject, FieldName.Id))),
        refineNameField(l.name, ErrorLocation.Field(subject, FieldName.Name)),
        refineParentName(l.parentName, ErrorLocation.Field(subject, FieldName.ParentName)),
        toValidation(ValidationUtil.refineOccurrenceProbability(l.probability, ErrorLocation.Field(subject, FieldName.Probability))),
        l.distributionShape.toShape(subject),
        requireAbsentSeedVarId(l.seedVarId, ErrorLocation.Field(subject, FieldName.SeedVarId))
      )((id, name, parent, prob, shape, _) => ExistingLeaf(ErrorSubject.ExistingNode(NodeId(id)), id, name, parent, prob, shape))
    }

  // Guard: ensure no node name collides with a reserved FOL query symbol (e.g. "leaf", "p95").
  private[requests] def requireNoReservedNames(allNames: Seq[SafeName.SafeName]): Validation[ValidationError, Unit] = {
    val collisions = allNames.filter(n => FolSymbols.reservedNames.contains(n.value))
    if collisions.nonEmpty then
      Validation.fail(ValidationError(
        ErrorLocation.requestField(FieldName.Name),
        ValidationErrorCode.RESERVED_NAME,
        ValidationMessages.reservedNodeNames(collisions.map(_.value.toString))
      ))
    else Validation.succeed(())
  }

  // Guard: pick exactly one root (a portfolio root when any portfolios exist; otherwise a lone leaf tree).
  private[requests] def requireSingleRoot(
    nodes: Seq[(SafeName.SafeName, ParentRef)]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val roots = nodes.collect { case (name, ParentRef.Root) => name }

    roots match {
      case Seq()     => Validation.fail(ValidationError(ErrorLocation.requestField(FieldName.RootId), ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.exactlyOneRootRequired))
      case Seq(root) => Validation.succeed(root)
      case _         => Validation.fail(ValidationError(ErrorLocation.requestField(FieldName.RootId), ValidationErrorCode.AMBIGUOUS_REFERENCE, ValidationMessages.multipleRootsFound))
    }
  }

  // Guard: leaves must point to portfolios as parents (unless lone leaf tree) and must exist.
  private[requests] def requireLeafParents(
    leaves: Seq[SubmittedParent],
    portfolioNames: Set[SafeName.SafeName],
    allNameSet: Set[SafeName.SafeName],
    totalNodes: Int
  ): Validation[ValidationError, Unit] =
    Validation.validateAll(leaves.map { l =>
      val location = ErrorLocation.Field(l.subject, FieldName.ParentName)
      l.parent match {
        case ParentRef.Under(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case ParentRef.Under(parent) if allNameSet.contains(parent) =>
          Validation.fail(ValidationError(location, ValidationErrorCode.INVALID_NODE_TYPE, ValidationMessages.parentNameIsLeaf(parent.value.toString)))
        case ParentRef.Under(parent) =>
          Validation.fail(ValidationError(location, ValidationErrorCode.MISSING_REFERENCE, ValidationMessages.parentNameNotFound(parent.value.toString)))
        case ParentRef.Root if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case ParentRef.Root =>
          Validation.fail(ValidationError(location, ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.leafNeedsPortfolioParent))
      }
    }).as(())

  // Guard: portfolios must point to portfolios in the provided set (or be root).
  private[requests] def requirePortfolioParents(
    portfolios: Seq[SubmittedParent],
    portfolioNames: Set[SafeName.SafeName]
  ): Validation[ValidationError, Unit] =
    Validation.validateAll(portfolios.map { p =>
      p.parent match {
        case ParentRef.Under(parent) if portfolioNames.contains(parent) => Validation.succeed(())
        case ParentRef.Under(parent) =>
          Validation.fail(ValidationError(
            ErrorLocation.Field(p.subject, FieldName.ParentName),
            ValidationErrorCode.MISSING_REFERENCE,
            ValidationMessages.parentNameNotFound(parent.value.toString)
          ))
        case ParentRef.Root => Validation.succeed(())
      }
    }).as(())

  private[requests] def validateTopologyCreate(
    treeName: SafeName.SafeName,
    portfolios: Seq[SubmittedPortfolio],
    leaves: Seq[SubmittedLeaf]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val allNames = portfolios.map(_.name) ++ leaves.map(_.name)
    val portfolioNames = portfolios.map(_.name).toSet
    val totalNodes = portfolios.size + leaves.size

    for {
      _ <- requireNoReservedNames(allNames)
      allNameSet <- SafeName.requireDistinct(allNames, ErrorLocation.requestField(FieldName.Name)).map(_ => allNames.toSet)
      root <- requireSingleRoot(portfolios.map(p => p.name -> p.parent) ++ leaves.map(l => l.name -> l.parent))
      _ <- requireLeafParents(leaves.map(l => SubmittedParent(l.subject, l.parent)), portfolioNames, allNameSet, totalNodes)
      _ <- requirePortfolioParents(portfolios.map(p => SubmittedParent(p.subject, p.parent)), portfolioNames)
      _ <- requireNoCycles(portfolios.map(p => p.name -> p.parent) ++ leaves.map(l => l.name -> l.parent))
      _ <- requireNonEmptyPortfolios(
        portfolioNames = portfolios.map(_.name),
        portfolioParents = portfolios.map(_.parent),
        leafParents = leaves.map(_.parent)
      )
    } yield root
  }

  private[requests] def validateTopologyUpdate(
    treeName: SafeName.SafeName,
    portfolios: Seq[ExistingPortfolio],
    leaves: Seq[ExistingLeaf],
    newPortfolios: Seq[SubmittedPortfolio],
    newLeaves: Seq[SubmittedLeaf]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val portNames = portfolios.map(_.name) ++ newPortfolios.map(_.name)
    val leafNames = leaves.map(_.name) ++ newLeaves.map(_.name)
    val allNames = portNames ++ leafNames
    val portfolioNames = portNames.toSet
    val totalNodes = portfolios.size + newPortfolios.size + leaves.size + newLeaves.size

    val allParents =
      portfolios.map(p => p.name -> p.parent) ++ leaves.map(l => l.name -> l.parent) ++
      newPortfolios.map(p => p.name -> p.parent) ++ newLeaves.map(l => l.name -> l.parent)

    for {
      _ <- requireNoReservedNames(allNames)
      allNameSet <- SafeName.requireDistinct(allNames, ErrorLocation.requestField(FieldName.Name)).map(_ => allNames.toSet)
      root <- requireSingleRoot(allParents)
      _ <- requireLeafParents(leaves.map(l => SubmittedParent(l.subject, l.parent)), portfolioNames, allNameSet, totalNodes)
      _ <- requireLeafParents(newLeaves.map(l => SubmittedParent(l.subject, l.parent)), portfolioNames, allNameSet, totalNodes)
      _ <- requirePortfolioParents(portfolios.map(p => SubmittedParent(p.subject, p.parent)), portfolioNames)
      _ <- requirePortfolioParents(newPortfolios.map(p => SubmittedParent(p.subject, p.parent)), portfolioNames)
      _ <- requireNoCycles(allParents)
      _ <- requireNonEmptyPortfolios(
        portfolioNames = portNames,
        portfolioParents = portfolios.map(_.parent) ++ newPortfolios.map(_.parent),
        leafParents = leaves.map(_.parent) ++ newLeaves.map(_.parent)
      )
    } yield root
  }

  // Guard: prevent cycles in a topology described by name -> parent pairs.
  private[requests] def requireNoCycles(
    parents: Seq[(SafeName.SafeName, ParentRef)]
  ): Validation[ValidationError, Unit] = {
    val parentMap = parents.flatMap { case (name, parent) => ParentRef.nameOf(parent).map(name -> _) }.toMap

    def isCycle(name: SafeName.SafeName, seen: Set[SafeName.SafeName]): Boolean = parentMap.get(name) match {
      case Some(p) if seen.contains(p) => true
      case Some(p) => isCycle(p, seen + p)
      case None => false
    }

    parentMap.keys.find(n => isCycle(n, Set(n))) match {
      case Some(cycle) => Validation.fail(ValidationError(
        ErrorLocation.requestField(FieldName.ParentName),
        ValidationErrorCode.CONSTRAINT_VIOLATION,
        ValidationMessages.cycleDetected(cycle.value.toString)
      ))
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
    * invariant (ADR-033 §5) that catches this at construction time. This guard
    * catches it earlier with a typed `ValidationError`.
    *
    * @see ADR-017 §Topology validation
    */
  private[requests] def requireNonEmptyPortfolios(
    portfolioNames: Seq[SafeName.SafeName],
    portfolioParents: Seq[ParentRef],
    leafParents: Seq[ParentRef]
  ): Validation[ValidationError, Unit] = {
    val childParents = (portfolioParents.flatMap(ParentRef.nameOf) ++ leafParents.flatMap(ParentRef.nameOf))
      .groupBy(identity).view.mapValues(_.size).toMap
    val emptyParents = portfolioNames.filterNot(childParents.contains)

    if emptyParents.nonEmpty then
      Validation.fail(ValidationError(
        ErrorLocation.requestField(FieldName.ChildIds),
        ValidationErrorCode.EMPTY_COLLECTION,
        ValidationMessages.portfoliosWithoutChildren(emptyParents.map(_.value.toString))
      ))
    else Validation.succeed(())
  }

  private[requests] def collectAllWithIndex[A, B](as: Seq[A])(f: (A, Int) => Validation[ValidationError, B]): Validation[ValidationError, Seq[B]] =
    as.zipWithIndex.foldLeft[Validation[ValidationError, List[B]]](Validation.succeed(Nil)) {
      case (acc, (a, idx)) => Validation.validateWith(acc, f(a, idx))((xs, b) => xs :+ b)
    }.map(_.toSeq)
}
