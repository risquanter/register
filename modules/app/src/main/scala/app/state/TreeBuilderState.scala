package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ForEach}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.{Distribution, RiskTree, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{ValidationUtil, TreeId, SafeName, NodeId, OccurrenceProbability, IronConstants}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, RiskPortfolioUpdateRequest, RiskLeafUpdateRequest, DistributionShapeRequest}
import com.risquanter.register.frontend.TreeBuilderLogic

final case class PortfolioDraft(
  name: SafeName.SafeName,
  parent: Option[SafeName.SafeName],
  id: Option[NodeId] = None
)
final case class LeafDraft(
  name:         SafeName.SafeName,
  parent:       Option[SafeName.SafeName],
  distribution: Distribution,
  probability: OccurrenceProbability,
  id:           Option[NodeId] = None
)

/** Type-safe field identifiers for the tree builder form. */
enum TreeBuilderField:
  case TreeName

/**
 * Builder state for constructing a risk tree on the client.
 * Performs client-side validation aligned with backend rules (no ID generation).
 *
 * Extends FormState for the tree-name field's error display timing
 * (same touched / triggerValidation / withDisplayControl infrastructure
 * used by RiskLeafFormState and PortfolioFormState).
 */
final class TreeBuilderState extends FormState[TreeBuilderField]:
  import TreeBuilderField.*
  val treeNameVar: Var[String] = Var("")
  val portfoliosVar: Var[List[PortfolioDraft]] = Var(Nil)
  val leavesVar: Var[List[LeafDraft]] = Var(Nil)

  /** When set, subsequent submits update the existing tree instead of creating. */
  val editingTreeId: Var[Option[TreeId]] = Var(None)

  /** Whether the builder is in update mode (previously created tree). */
  val isUpdateMode: Signal[Boolean] = editingTreeId.signal.map(_.isDefined)

  /** Current in-flight distribution draft from the active leaf form, or None.
    *
    * Written by [[app.views.RiskLeafFormView]] via its `state.draftSignal` subscription.
    * Reset to None on form unmount. Read by [[app.state.DistributionChartState]] for
    * debounced preview fetches.
    */
  val currentDraftVar: Var[Option[Distribution]] = Var(None)
  val draftSignal: StrictSignal[Option[Distribution]] = currentDraftVar.signal

  val rootLabel = "(root)"

  // ── Node selection (Design view in-place editing) ────────────

  /** Name of the currently selected leaf node, or None when in add-leaf mode.
    * Selecting a leaf clears `selectedPortfolioName` (mutual exclusivity).
    */
  val selectedLeafName: Var[Option[SafeName.SafeName]] = Var(None)

  /** Name of the currently selected portfolio node, or None when in add-portfolio mode.
    * Selecting a portfolio clears `selectedLeafName` (mutual exclusivity).
    */
  val selectedPortfolioName: Var[Option[SafeName.SafeName]] = Var(None)

  // ── Tree-name validation ──────────────────────────────────────
  private val treeNameErrorRaw: Signal[Option[String]] = treeNameVar.signal.map { v =>
    ValidationUtil.refineName(v, "tree.name") match
      case Right(_) => None
      case Left(errors) => Some(errors.head.message)
  }

  /** Display-controlled tree-name error (only shows after blur or submit trigger). */
  val treeNameError: Signal[Option[String]] = withDisplayControl(TreeName, treeNameErrorRaw)

  /** Raw errors for hasErrors check — tree-name is the only builder-level field. */
  override def errorSignals: List[Signal[Option[String]]] = List(treeNameErrorRaw)

  /** Parent dropdown options: root sentinel (if unclaimed) + current portfolio names. */
  val parentOptions: Signal[List[String]] =
    portfoliosVar.signal.combineWith(leavesVar.signal).map { (ps, ls) =>
      val rootTaken = ps.exists(_.parent.isEmpty) || ls.exists(_.parent.isEmpty)
      val base = ps.map(_.name.value)   // .value at UI-string boundary: parentSelect takes Signal[List[String]]
      if rootTaken then base else rootLabel :: base
    }

  def addPortfolio(name: SafeName.SafeName, parent: Option[SafeName.SafeName]): Validation[ValidationError, PortfolioDraft] =
    val draft = PortfolioDraft(name, parent)
    val result = TreeBuilderLogic.preValidateTopology(
      (portfoliosVar.now() :+ draft).map(p => p.name.value.toString -> p.parent.map(_.value.toString)),
      leavesVar.now().map(l => l.name.value.toString -> l.parent.map(_.value.toString))
    ).map(_ => draft)
    result match
      case Validation.Success(_, _) => portfoliosVar.update(_ :+ draft)
      case _ => ()
    result

  def addLeaf(rawName: String, rawParent: Option[String], shape: Distribution, probability: OccurrenceProbability): Validation[ValidationError, LeafDraft] =
    val result = for
      name <- validateName(rawName, "leaf.name")
      parent <- validateParentName(rawParent, "leaf.parentName")
      draft = LeafDraft(name, parent, shape, probability)
      _ <- TreeBuilderLogic.preValidateTopology(
        portfoliosVar.now().map(p => p.name.value.toString -> p.parent.map(_.value.toString)),
        (leavesVar.now() :+ draft).map(l => l.name.value.toString -> l.parent.map(_.value.toString))
      )
    yield draft
    result match
      case Validation.Success(_, draft) => leavesVar.update(_ :+ draft)
      case _ => ()
    result

  /** Populate a [[RiskLeafFormState]] from a loaded [[LeafDraft]].
    *
    * Sets all field vars from the draft, including `parentVar`. Probability and
    * percentiles are rescaled from the 0-1 domain representation to the 0-100
    * form display scale.
    */
  def populateLeafForm(state: RiskLeafFormState, leaf: LeafDraft): Unit =
    val mode = leaf.distribution.distributionType match
      case IronConstants.Expert    => DistributionMode.Expert
      case IronConstants.Lognormal => DistributionMode.Lognormal
      case other => throw new IllegalStateException(s"Unrecognised DistributionType: $other — update populateLeafForm")
    state.distributionModeVar.set(mode)
    state.nameVar.set(leaf.name.value)
    // Form field is on the 0-100 (percent) scale; domain value is 0-1.
    state.probabilityVar.set(RiskLeafFormState.domainToDisplayPct(leaf.probability, 2))
    state.parentVar.set(leaf.parent.map(_.value))
    mode match
      case DistributionMode.Expert =>
        val pcts   = leaf.distribution.percentiles.fold("")(arr => arr.map(RiskLeafFormState.domainToDisplayPct(_, 0)).mkString(", "))
        val quants = leaf.distribution.quantiles.fold("")(arr => arr.map(_.toString).mkString(", "))
        state.percentilesVar.set(pcts)
        state.quantilesVar.set(quants)
        state.termsVar.set(leaf.distribution.terms.fold("")(_.toString))
      case DistributionMode.Lognormal =>
        state.minLossVar.set(leaf.distribution.minLoss.fold("")(_.toString))
        state.maxLossVar.set(leaf.distribution.maxLoss.fold("")(_.toString))

  /** Populate a [[PortfolioFormState]] from a loaded [[PortfolioDraft]]. */
  def populatePortfolioForm(state: PortfolioFormState, portfolio: PortfolioDraft): Unit =
    state.nameVar.set(portfolio.name.value)
    state.parentVar.set(portfolio.parent.map(_.value))

  /** Update an existing leaf in-place. Preserves the node's server identity (`id`).
    *
    * Validates the new name/parent, then runs `preValidateTopology` on the post-substitution
    * node set. On success, replaces the draft in `leavesVar` and clears `selectedLeafName`.
    * Mirrors the `addLeaf` pattern: match on result, then update Var.
    */
  def updateLeaf(
    originalName: SafeName.SafeName,
    rawName:      String,
    rawParent:    Option[String],
    shape:        Distribution,
    probability:  OccurrenceProbability
  ): Validation[ValidationError, LeafDraft] =
    val existing = leavesVar.now().find(_.name == originalName)
    val result = for
      name   <- validateName(rawName, "leaf.name")
      parent <- validateParentName(rawParent, "leaf.parentName")
      draft  = LeafDraft(name, parent, shape, probability, existing.flatMap(_.id))
      _      <- TreeBuilderLogic.preValidateTopology(
        portfoliosVar.now().map(p => p.name.value.toString -> p.parent.map(_.value.toString)),
        (leavesVar.now().filterNot(_.name == originalName) :+ draft).map(l => l.name.value.toString -> l.parent.map(_.value.toString))
      )
    yield draft
    result match
      case Validation.Success(_, draft) =>
        leavesVar.update(_.map(l => if l.name == originalName then draft else l))
        selectedLeafName.set(None)
      case _ => ()
    result

  /** Update an existing portfolio in-place. Cascade-renames every child whose `parent`
    * references `originalName`. Preserves the node's server identity (`id`).
    *
    * Both Vars are updated sequentially — effectively atomic in single-threaded JS;
    * no observer can interleave within the same synchronous event handler.
    */
  def updatePortfolio(
    originalName: SafeName.SafeName,
    newName:      SafeName.SafeName,
    newParent:    Option[SafeName.SafeName]
  ): Validation[ValidationError, PortfolioDraft] =
    val existing = portfoliosVar.now().find(_.name == originalName)
    val draft = PortfolioDraft(newName, newParent, existing.flatMap(_.id))
    val updatedPortfolios = portfoliosVar.now().map { p =>
      if p.name == originalName then draft
      else if p.parent == Some(originalName) then p.copy(parent = Some(newName))
      else p
    }
    val updatedLeaves = leavesVar.now().map { l =>
      if l.parent == Some(originalName) then l.copy(parent = Some(newName))
      else l
    }
    val result = TreeBuilderLogic.preValidateTopology(
      updatedPortfolios.map(p => p.name.value.toString -> p.parent.map(_.value.toString)),
      updatedLeaves.map(l => l.name.value.toString -> l.parent.map(_.value.toString))
    ).map(_ => draft)
    result match
      case Validation.Success(_, _) =>
        portfoliosVar.set(updatedPortfolios)
        leavesVar.set(updatedLeaves)
        selectedPortfolioName.set(None)
      case _ => ()
    result

  /** Remove node by name; cascades removal of portfolio descendants and their leaves. */
  def removeNode(name: String): Unit =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val toRemove = TreeBuilderLogic.collectCascade(Set(name), portfolios.map(p => p.name.value.toString -> p.parent.map(_.value.toString)))
    portfoliosVar.set(portfolios.filterNot(p => toRemove.contains(p.name.value.toString)))
    leavesVar.set(leaves.filterNot(l => toRemove.contains(l.name.value.toString) || l.parent.exists(n => toRemove.contains(n.value.toString))))
    // Clear selection if the selected node was removed — otherwise the form
    // stays in "Update" mode with a ghost selection pointing at a deleted leaf/portfolio.
    if selectedLeafName.now().exists(n => toRemove.contains(n.value.toString)) then
      selectedLeafName.set(None)
    if selectedPortfolioName.now().exists(n => toRemove.contains(n.value.toString)) then
      selectedPortfolioName.set(None)

  /** Build backend request with client-side validation. */
  def toRequest(): Validation[ValidationError, RiskTreeDefinitionRequest] =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val treeNameV = validateName(treeNameVar.now(), "tree.name")
    val portfoliosV = Validation.succeed(portfolios.map(toPortfolioRequest))
    val leavesV = Validation.succeed(leaves.map(toLeafRequest))
    val topologyV = TreeBuilderLogic.fullValidateTopology(portfolios.map(p => p.name.value.toString -> p.parent.map(_.value.toString)), leaves.map(l => l.name.value.toString -> l.parent.map(_.value.toString)))

    Validation.validateWith(treeNameV, portfoliosV, leavesV, topologyV) { (treeName, ports, leafs, _) =>
      RiskTreeDefinitionRequest(treeName.value, ports, leafs)
    }

  /** Build update request — full replacement with all nodes as "new"
    * (server regenerates IDs; tree ID is preserved).
    */
  def toUpdateRequest(): Validation[ValidationError, RiskTreeUpdateRequest] =
    val portfolios = portfoliosVar.now()
    val leaves     = leavesVar.now()

    // Partition drafts on server identity: a loaded node (id = Some) routes to the
    // identity-preserving "existing" bucket so its NodeId — and therefore its Irmin
    // path — survives the update; a node added this session (id = None) routes to the
    // "new" bucket and receives a server-assigned id.
    val existingPortfolios: List[(NodeId, PortfolioDraft)] = portfolios.flatMap(d => d.id.map(_ -> d))
    val newPortfolioDrafts: List[PortfolioDraft]           = portfolios.filter(_.id.isEmpty)
    val existingLeaves: List[(NodeId, LeafDraft)]          = leaves.flatMap(d => d.id.map(_ -> d))
    val newLeafDrafts: List[LeafDraft]                     = leaves.filter(_.id.isEmpty)

    val treeNameV = validateName(treeNameVar.now(), "tree.name")
    // Topology is validated over the full node set (existing + new), exactly as in the
    // create flow — correctness-by-construction is whole-graph, independent of bucketing.
    val topologyV = TreeBuilderLogic.fullValidateTopology(
      portfolios.map(p => p.name.value.toString -> p.parent.map(_.value.toString)),
      leaves.map(l => l.name.value.toString -> l.parent.map(_.value.toString))
    )
    val headerV = Validation.validateWith(treeNameV, topologyV)((name, _) => name)

    val existingPortfoliosV = Validation.succeed(existingPortfolios.map(toPortfolioUpdateRequest))
    val newPortfoliosV      = Validation.succeed(newPortfolioDrafts.map(toPortfolioRequest))
    val existingLeavesV     = Validation.succeed(existingLeaves.map(toLeafUpdateRequest))
    val newLeavesV          = Validation.succeed(newLeafDrafts.map(toLeafRequest))

    Validation.validateWith(headerV, existingPortfoliosV, newPortfoliosV, existingLeavesV, newLeavesV) {
      (treeName, existPorts, newPorts, existLeaves, newLeaves) =>
        RiskTreeUpdateRequest(
          name = treeName.value,
          portfolios = existPorts,
          leaves = existLeaves,
          newPortfolios = newPorts,
          newLeaves = newLeaves
        )
    }

  /** Snapshot of (name, portfolios, leaves) taken right after `loadFromTree` —
    * the baseline `isDirty` compares against. `None` in create-mode (no tree
    * loaded yet), where "dirty" simply means "has any content".
    */
  private val loadedSnapshotVar: Var[Option[(String, List[PortfolioDraft], List[LeafDraft])]] = Var(None)

  /** True if the builder differs from what's actually saved.
    *
    * In create-mode (`loadedSnapshotVar` empty, no tree loaded yet), any
    * content at all counts as dirty. Once a tree is loaded, dirty means the
    * fields have changed *since that load* — merely having loaded a
    * non-empty tree does not count, otherwise every switch away from any
    * loaded tree would look "dirty" even with zero edits.
    */
  def isDirty: Boolean =
    val current = (treeNameVar.now(), portfoliosVar.now(), leavesVar.now())
    loadedSnapshotVar.now() match
      case Some(baseline) => current != baseline
      case None           => current._1.trim.nonEmpty || current._2.nonEmpty || current._3.nonEmpty

  /** Reset the builder to a blank, create-mode draft. The only way to leave
    * update mode — `editingTreeId` is otherwise only ever set, never cleared,
    * so without this a session that has loaded or created one tree can never
    * start a second one.
    */
  def startNewTree(): Unit =
    treeNameVar.set("")
    portfoliosVar.set(Nil)
    leavesVar.set(Nil)
    editingTreeId.set(None)
    selectedLeafName.set(None)
    selectedPortfolioName.set(None)
    currentDraftVar.set(None)
    loadedSnapshotVar.set(None)
    resetTouched()

  /** Populate builder vars from a server-loaded tree.
    *
    * Partitions the tree's nodes into portfolios and leaves, resolves each
    * node's parent name via the tree index, and sets all vars atomically.
    * Clears any in-flight leaf-form preview and resets touched/error state.
    */
  def loadFromTree(tree: RiskTree): Unit =
    val portfolios = tree.nodes.collect { case p: RiskPortfolio => p }.map { p =>
      PortfolioDraft(
        name   = p.name,
        parent = p.parentId.flatMap(id => tree.index.nodes.get(id).map(_.name)),
        id     = Some(p.id)
      )
    }.toList

    val leaves = tree.nodes.collect { case l: RiskLeaf => l }.map { l =>
      LeafDraft(
        name         = l.name,
        parent       = l.parentId.flatMap(id => tree.index.nodes.get(id).map(_.name)),
        distribution = Distribution(
          distributionType = l.distributionType,
          minLoss          = l.minLoss,
          maxLoss          = l.maxLoss,
          percentiles      = l.percentiles,
          quantiles        = l.quantiles,
          terms            = l.terms
        ),
        probability  = l.probability,
        id           = Some(l.id)
      )
    }.toList

    // currentDraftVar is intentionally NOT cleared here. Ownership belongs to
    // RiskLeafFormView's draftSignal subscription. When loading the same tree after
    // a successful submit the form fields are still valid — clearing the draft would
    // prevent the preview checkbox from working. When switching to a different tree,
    // the caller (DesignView) clears currentDraftVar before calling loadFromTree.
    treeNameVar.set(tree.name.value)
    portfoliosVar.set(portfolios)
    leavesVar.set(leaves)
    editingTreeId.set(Some(tree.id))
    loadedSnapshotVar.set(Some((tree.name.value, portfolios, leaves)))
    resetTouched()

  // ------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------

  private def validateName(value: String, field: String): Validation[ValidationError, SafeName.SafeName] =
    toValidation(ValidationUtil.refineName(value, field))

  private def validateParentName(raw: Option[String], field: String): Validation[ValidationError, Option[SafeName.SafeName]] =
    raw match
      case Some(v) if v.trim.nonEmpty => validateName(v, field).map(Some(_))
      case _ => Validation.succeed(None)


  // Total conversions: every field is already a refined domain type (`SafeName`,
  // `Distribution`, `Probability`), so construction cannot fail. Returning the plain
  // request type keeps totality visible; callers wrap the whole collection once with
  // `Validation.succeed` where a `Validation` is needed for `validateWith`.
  private def toPortfolioRequest(draft: PortfolioDraft): RiskPortfolioDefinitionRequest =
    RiskPortfolioDefinitionRequest(draft.name.value, draft.parent.map(_.value))

  private def toLeafRequest(draft: LeafDraft): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name              = draft.name.value,
      parentName        = draft.parent.map(_.value),
      probability       = draft.probability,
      distributionShape = DistributionShapeRequest(
        distributionType = draft.distribution.distributionType.toString,
        percentiles      = draft.distribution.percentiles,
        quantiles        = draft.distribution.quantiles,
        terms            = draft.distribution.terms.map(_.toInt),
        minLoss          = draft.distribution.minLoss.map(identity),
        maxLoss          = draft.distribution.maxLoss.map(identity)
      )
    )

  // Identity-preserving variants: emit an update DTO carrying the node's existing id,
  // so the server keeps the same NodeId (and Irmin path) instead of minting a new one.
  // Total for the same reason as the definition variants above.
  private def toPortfolioUpdateRequest(entry: (NodeId, PortfolioDraft)): RiskPortfolioUpdateRequest =
    val (id, draft) = entry
    RiskPortfolioUpdateRequest(id.value, draft.name.value, draft.parent.map(_.value))

  private def toLeafUpdateRequest(entry: (NodeId, LeafDraft)): RiskLeafUpdateRequest =
    val (id, draft) = entry
    RiskLeafUpdateRequest(
      id                = id.value,
      name              = draft.name.value,
      parentName        = draft.parent.map(_.value),
      probability       = draft.probability,
      distributionShape = DistributionShapeRequest(
        distributionType = draft.distribution.distributionType.toString,
        percentiles      = draft.distribution.percentiles,
        quantiles        = draft.distribution.quantiles,
        terms            = draft.distribution.terms.map(_.toInt),
        minLoss          = draft.distribution.minLoss.map(identity),
        maxLoss          = draft.distribution.maxLoss.map(identity)
      )
    )

