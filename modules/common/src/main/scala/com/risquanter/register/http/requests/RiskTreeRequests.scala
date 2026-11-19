package com.risquanter.register.http.requests

import zio.prelude.Validation
import com.risquanter.register.common.FolSymbols
import com.risquanter.register.domain.data.{
  AbsoluteReplacement, DistributionShape, Mitigation, MitigationPrecedence, MitigationSpec, MitigationTarget, RiskLeaf,
  RiskNode, RiskPortfolio, RiskTree, TargetingPredicate
}
import com.risquanter.register.domain.data.iron.{ValidationUtil, MitigationId, NonNegativeInt, SafeName, SafeId, SeedVarId, NodeId, Probability}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, FieldProblem, SubmittedCollection, SubmittedMitigations, ValidationError
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
    probability: Probability,
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
    probability: Probability,
    shape: DistributionShape
  )

  final case class ResolvedNode(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    parent: ParentRef,
    kind: NodeKind
  )

  /** A submitted mitigation, refined and checked against the stored tree. Its
    * subject names it in a later error. */
  final case class ResolvedMitigation(
    subject:    ErrorSubject,
    id:         MitigationId,
    name:       SafeName.SafeName,
    target:     MitigationTarget,
    spec:       ResolvedSpec,
    precedence: MitigationPrecedence
  )

  /** A spec that is final, or an absolute statement whose base stamp the
    * service computes, because the stamp hashes the anchor leaf as the request
    * builds it.
    *
    * {{{
    * // a new absolute statement on stored leaf L
    * ResolvedSpec.Unstamped(AbsoluteReplacement.Likelihood(p), L)
    * // the same statement kept unchanged, with KeepIfUnchanged
    * ResolvedSpec.Complete(MitigationSpec.LeafAbsolute(AbsoluteReplacement.Likelihood(p), L, storedStamp))
    * }}}
    */
  enum ResolvedSpec:
    case Complete(spec: MitigationSpec)
    case Unstamped(replacement: AbsoluteReplacement, anchor: NodeId)

  /** A resolved create request: names refined, ids generated, topology
    * validated, shapes gated. `nodes` and `leaves` are keyed by name for parent
    * resolution in the service. Each leaf keeps the subject that names it in a
    * later error. */
  final case class ResolvedCreate(
    treeName: SafeName.SafeName,
    nodes: Map[SafeName.SafeName, ResolvedNode],
    leaves: Map[SafeName.SafeName, SubmittedLeaf],
    providedSeedVarIds: Map[SafeName.SafeName, SeedVarId.SeedVarId],
    rootName: SafeName.SafeName,
    mitigations: Seq[ResolvedMitigation]
  )

  /** A resolved update request: existing nodes keep the caller's ids and new
    * nodes get generated ones. Shapes are gated here, so the service does not
    * repeat the cross-field checks. Each leaf keeps the subject that names it
    * in a later error. Kept and added mitigations meet in one collection,
    * because each already carries its id. */
  final case class ResolvedUpdate(
    treeName: SafeName.SafeName,
    existing: Map[SafeName.SafeName, ResolvedNode],
    added: Map[SafeName.SafeName, ResolvedNode],
    existingLeaves: Map[SafeName.SafeName, ExistingLeaf],
    addedLeaves: Map[SafeName.SafeName, SubmittedLeaf],
    providedSeedVarIds: Map[SafeName.SafeName, SeedVarId.SeedVarId],
    rootName: SafeName.SafeName,
    mitigations: Seq[ResolvedMitigation]
  )

  // Entry points that delegate to the request companions.
  def resolveCreate(req: RiskTreeDefinitionRequest, newId: IdGenerator): Validation[ValidationError, ResolvedCreate] =
    RiskTreeDefinitionRequest.resolve(req, newId)

  def resolveUpdate(
    req: RiskTreeUpdateRequest,
    stored: TreeIndex,
    storedMitigations: Seq[Mitigation],
    newId: IdGenerator
  ): Validation[ValidationError, ResolvedUpdate] =
    RiskTreeUpdateRequest.resolve(req, stored, storedMitigations, newId)

  /** Refuses a submission whose node count or mitigation count is above its
    * tree ceiling, before any entry is refined, so the work and the refusal
    * stay bounded by the ceilings rather than by the request body (ADR-017 §6).
    * Both counts are checked, and each over its ceiling is one refusal. */
  private[requests] def requireWithinCeilings(
    nodeCount: NonNegativeInt,
    mitigationCount: NonNegativeInt
  ): Validation[ValidationError, Unit] =
    Validation.validateWith(
      RiskTree.requireNodeCount(nodeCount, ErrorLocation.Whole),
      RiskTree.requireMitigationCount(mitigationCount, ErrorLocation.requestField(FieldName.Mitigations))
    )((_, _) => ())

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
        toValidation(ValidationUtil.refineProbability(l.probability, ErrorLocation.Field(subject, FieldName.Probability))),
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
      case Some(_) => Validation.fail(ValidationError(location, FieldProblem.SeedVarIdImmutable))
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
        toValidation(ValidationUtil.refineProbability(l.probability, ErrorLocation.Field(subject, FieldName.Probability))),
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
        FieldProblem.ReservedNodeNames(collisions.toList)
      ))
    else Validation.succeed(())
  }

  // Guard: pick exactly one root (a portfolio root when any portfolios exist; otherwise a lone leaf tree).
  private[requests] def requireSingleRoot(
    nodes: Seq[(SafeName.SafeName, ParentRef)]
  ): Validation[ValidationError, SafeName.SafeName] = {
    val roots = nodes.collect { case (name, ParentRef.Root) => name }

    roots match {
      case Seq()     => Validation.fail(ValidationError(ErrorLocation.requestField(FieldName.RootId), FieldProblem.RootRequired))
      case Seq(root) => Validation.succeed(root)
      case _         => Validation.fail(ValidationError(ErrorLocation.requestField(FieldName.RootId), FieldProblem.MultipleRoots))
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
          Validation.fail(ValidationError(location, FieldProblem.ParentIsLeaf(parent)))
        case ParentRef.Under(parent) =>
          Validation.fail(ValidationError(location, FieldProblem.ParentNotFound(parent)))
        case ParentRef.Root if portfolioNames.isEmpty && totalNodes == 1 => Validation.succeed(())
        case ParentRef.Root =>
          Validation.fail(ValidationError(location, FieldProblem.ParentPortfolioRequired))
      }
    }).as(())

  /** Every saved-node entry names a node of `stored` of the matching type, and
    * no two entries name the same one. An identifier that does not refine is
    * skipped here, because the entry's own refinement reports it.
    *
    * An entry keeps a stored node's identifier, and only the server mints a new
    * one (ADR-017 §4), so an identifier the stored tree does not hold, or holds
    * under the other type, is refused rather than stored. An identifier listed
    * a second time, in either collection, is refused at that second entry. The
    * check reads the raw entries, so its refusals accumulate with every refusal
    * of the entries' other fields.
    *
    * {{{
    * // `leaves` lists the id of a stored portfolio, and a second entry's id is "not-an-id"
    * requireStoredNodes(Nil, Seq(entry, malformed), stored) // one INVALID_NODE_TYPE, at the first entry's Id
    * // `leaves` lists one stored leaf's id twice
    * requireStoredNodes(Nil, Seq(entry, entry), stored)     // one DUPLICATE_VALUE, at that id
    * }}}
    */
  private[requests] def requireStoredNodes(
    portfolios: Seq[RiskPortfolioUpdateRequest],
    leaves: Seq[RiskLeafUpdateRequest],
    stored: TreeIndex
  ): Validation[ValidationError, Unit] =
    def location(id: NodeId): ErrorLocation = ErrorLocation.Field(ErrorSubject.ExistingNode(id), FieldName.Id)
    val listed: Seq[(NodeId, NodeKind)] =
      (portfolios.map(p => (p.id, NodeKind.Portfolio)) ++ leaves.map(l => (l.id, NodeKind.Leaf)))
        .flatMap((rawId, kind) =>
          ValidationUtil.refineId(rawId, ErrorLocation.requestField(FieldName.Id)).toOption.map(NodeId(_) -> kind))
    // The kind an id is checked against is the one its first listing gives.
    val firstKind: Map[NodeId, NodeKind] = listed.reverse.toMap
    checkFirstListings(listed.map(_._1))(
      id => requireStoredNodeOfKind(id, firstKind(id), stored.nodes, location(id)),
      id => ValidationError(location(id), FieldProblem.StoredNodeListedTwice)
    )

  /** Refuses each key at its second and later listing; every first listing
    * goes to `check`. The refusals accumulate.
    *
    * {{{
    * checkFirstListings(Seq(a, b, a))(check, twice) // check(a), check(b), and twice(a) for the third
    * }}}
    */
  private[requests] def checkFirstListings[K](listed: Seq[K])(
    check: K => Validation[ValidationError, Unit],
    listedTwice: K => ValidationError
  ): Validation[ValidationError, Unit] =
    val (_, checks) =
      listed.foldLeft((Set.empty[K], Vector.empty[Validation[ValidationError, Unit]])) {
        case ((seen, acc), key) =>
          val result = if seen.contains(key) then Validation.fail(listedTwice(key)) else check(key)
          (seen + key, acc :+ result)
      }
    Validation.validateAll(checks).as(())

  /** The id names a stored node of `kind`, refused at `location` otherwise. */
  private[requests] def requireStoredNodeOfKind(
    id: NodeId,
    kind: NodeKind,
    stored: Map[NodeId, RiskNode],
    location: ErrorLocation
  ): Validation[ValidationError, Unit] =
    (stored.get(id), kind) match
      case (Some(_: RiskPortfolio), NodeKind.Portfolio) | (Some(_: RiskLeaf), NodeKind.Leaf) =>
        Validation.succeed(())
      case (Some(_: RiskLeaf), NodeKind.Portfolio) =>
        Validation.fail(ValidationError(location, FieldProblem.StoredNodeIsLeaf))
      case (Some(_: RiskPortfolio), NodeKind.Leaf) =>
        Validation.fail(ValidationError(location, FieldProblem.StoredNodeIsPortfolio))
      case (None, _) =>
        Validation.fail(ValidationError(location, FieldProblem.StoredNodeNotInTree))

  /** The fields every submitted mitigation refines, at `subject`. A name that
    * refines is then checked against the reserved query symbols. An absolute
    * statement's target is derived from its refined anchor; every other
    * statement's is parsed. */
  private def refineMitigationFields(
    name: String,
    target: Option[String],
    spec: MitigationSpecRequest,
    subject: ErrorSubject
  ): Validation[ValidationError, (SafeName.SafeName, MitigationTarget, ResolvedSpec)] =
    val nameLocation = ErrorLocation.Field(subject, FieldName.Name)
    val nameV = refineNameField(name, nameLocation).flatMap(requireUnreservedMitigationName(_, nameLocation))
    spec match
      case absolute: MitigationSpecRequest.LeafAbsolute =>
        Validation.validateWith(
          nameV,
          requireAbsentTarget(target, ErrorLocation.Field(subject, FieldName.TargetingSource)),
          absolute.toUnstamped(subject)
        )((n, _, u) => (n, MitigationTarget.Predicate(TargetingPredicate.ofNode(u.anchor)), u))
      case other =>
        Validation.validateWith(nameV, refineTargetField(target, subject), other.toSpec(subject))((n, t, s) => (n, t, s))

  /** An absolute statement's target is derived from its anchor, so a submitted
    * one is refused at `location`. */
  private[requests] def requireAbsentTarget(raw: Option[String], location: ErrorLocation): Validation[ValidationError, Unit] =
    raw match
      case Some(_) => Validation.fail(ValidationError(location, FieldProblem.TargetingDerivedFromAnchor))
      case None    => Validation.succeed(())

  /** Every other statement's target, parsed at `subject`; absent is refused as
    * required. */
  private[requests] def refineTargetField(raw: Option[String], subject: ErrorSubject): Validation[ValidationError, MitigationTarget] =
    raw match
      case Some(source) => TargetingPredicate.create(source, subject).map(MitigationTarget.Predicate(_))
      case None =>
        Validation.fail(ValidationError(ErrorLocation.Field(subject, FieldName.TargetingSource), FieldProblem.Required))

  /** A mitigation name spelled like a reserved query symbol, such as
    * `residual`, is refused at `location`. The knowledge base leaves such a
    * name out of its name lookup, so `mitigation_named` could never find it. */
  private def requireUnreservedMitigationName(
    name: SafeName.SafeName,
    location: ErrorLocation
  ): Validation[ValidationError, SafeName.SafeName] =
    if FolSymbols.reservedNames.contains(name.value) then
      Validation.fail(ValidationError(location, FieldProblem.ReservedMitigationName(name)))
    else Validation.succeed(name)

  /** The mitigations a request adds, each with an id minted by `newId`. */
  private[requests] def refineMitigationDefs(
    mitigations: Seq[MitigationDefinitionRequest],
    newId: IdGenerator
  ): Validation[ValidationError, Seq[ResolvedMitigation]] =
    collectAllWithIndex(mitigations) { (m, idx) =>
      val subject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.NewMitigations, idx)
      refineMitigationFields(m.name, m.target, m.spec, subject).map((name, target, spec) =>
        ResolvedMitigation(subject, MitigationId(newId()), name, target, spec, m.precedence))
    }

  /** The mitigations a request keeps, by id. A kept absolute statement becomes
    * `Complete` with the stored spec, stamp included, when its replacement and
    * anchor equal the stored ones and its choice is `KeepIfUnchanged`.
    * Otherwise it stays `Unstamped`, and the service stamps it. */
  private[requests] def refineExistingMitigations(
    mitigations: Seq[MitigationUpdateRequest],
    stored: Seq[Mitigation]
  ): Validation[ValidationError, Seq[ResolvedMitigation]] =
    val storedById: Map[MitigationId, Mitigation] = stored.map(m => m.id -> m).toMap
    collectAllWithIndex(mitigations) { (m, idx) =>
      val subject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.Mitigations, idx)
      Validation.validateWith(
        toValidation(ValidationUtil.refineId(m.id, ErrorLocation.Field(subject, FieldName.Id))).map(MitigationId(_)),
        refineMitigationFields(m.name, m.target, m.spec, subject)
      ) { case (id, (name, target, spec)) =>
        ResolvedMitigation(
          ErrorSubject.Mitigation(id), id, name, target, keptSpec(m.spec, spec, storedById.get(id)), m.precedence)
      }
    }

  /** The stored spec, stamp included, for an absolute statement resubmitted
    * unchanged with `KeepIfUnchanged`; the resolved spec otherwise. */
  private def keptSpec(submitted: MitigationSpecRequest, resolved: ResolvedSpec, stored: Option[Mitigation]): ResolvedSpec =
    (submitted, resolved, stored.map(_.spec)) match
      case (
            MitigationSpecRequest.LeafAbsolute(_, _, BaseStampChoice.KeepIfUnchanged),
            ResolvedSpec.Unstamped(replacement, anchor),
            Some(storedSpec @ MitigationSpec.LeafAbsolute(storedReplacement, storedAnchor, _))
          ) if replacement == storedReplacement && anchor == storedAnchor =>
        ResolvedSpec.Complete(storedSpec)
      case _ => resolved

  /** Every kept entry names a stored mitigation, and none is named twice. An
    * id that does not refine is skipped here, because the entry's own
    * refinement reports it. */
  private[requests] def requireStoredMitigations(
    mitigations: Seq[MitigationUpdateRequest],
    stored: Seq[Mitigation]
  ): Validation[ValidationError, Unit] =
    val storedIds: Set[MitigationId] = stored.map(_.id).toSet
    def location(id: MitigationId): ErrorLocation = ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Id)
    val listed: Seq[MitigationId] =
      mitigations.flatMap(m =>
        ValidationUtil.refineId(m.id, ErrorLocation.requestField(FieldName.Id)).toOption.map(MitigationId(_)))
    checkFirstListings(listed)(
      id =>
        if storedIds.contains(id) then Validation.succeed(())
        else Validation.fail(ValidationError(location(id), FieldProblem.StoredMitigationNotInTree)),
      id => ValidationError(location(id), FieldProblem.StoredMitigationListedTwice)
    )

  /** Every unstamped statement's anchor names a stored leaf. Two mitigations
    * may anchor the same leaf. */
  private[requests] def requireStoredAnchors(
    mitigations: Seq[ResolvedMitigation],
    stored: Map[NodeId, RiskNode]
  ): Validation[ValidationError, Unit] =
    Validation.validateAll(mitigations.collect {
      case ResolvedMitigation(subject, _, _, _, ResolvedSpec.Unstamped(_, anchor), _) =>
        val location = ErrorLocation.Field(subject, FieldName.Anchor)
        stored.get(anchor) match
          case Some(_: RiskLeaf)      => Validation.succeed(())
          case Some(_: RiskPortfolio) => Validation.fail(ValidationError(location, FieldProblem.AnchorIsPortfolio))
          case None                   => Validation.fail(ValidationError(location, FieldProblem.AnchorNotInTree))
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
            FieldProblem.ParentNotFound(parent)
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
        FieldProblem.CycleDetected(cycle)
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
    * '''Defence-in-depth:''' `RiskPortfolio.create` refuses an empty child list
    * at construction time, accumulated with its other field refusals. This
    * guard catches the same condition earlier, against the submitted names,
    * so the refusal can say which portfolio is childless.
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
        FieldProblem.PortfolioHasNoChildren(emptyParents.toList)
      ))
    else Validation.succeed(())
  }

  private[requests] def collectAllWithIndex[A, B](as: Seq[A])(f: (A, Int) => Validation[ValidationError, B]): Validation[ValidationError, Seq[B]] =
    as.zipWithIndex.foldLeft[Validation[ValidationError, Vector[B]]](Validation.succeed(Vector.empty)) {
      case (acc, (a, idx)) => Validation.validateWith(acc, f(a, idx))((xs, b) => xs :+ b)
    }.map(_.toSeq)
}
