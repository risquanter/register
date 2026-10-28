package app.state

import com.raquo.laminar.api.L.*
import zio.prelude.Validation
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.domain.data.{DistributionShape, RiskTree, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{ValidationUtil, TreeId, SafeName, NodeId, OccurrenceProbability}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, RiskPortfolioUpdateRequest, RiskLeafUpdateRequest, DistributionShapeRequest}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef
import com.risquanter.register.frontend.TreeBuilderLogic

final case class PortfolioDraft(
  name: SafeName.SafeName,
  parent: ParentRef,
  id: Option[NodeId]
)
final case class LeafDraft(
  name:        SafeName.SafeName,
  parent:      ParentRef,
  shape:       DistributionShape,
  probability: OccurrenceProbability,
  id:          Option[NodeId]
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
  val currentDraftVar: Var[Option[DistributionShape]] = Var(None)
  val draftSignal: StrictSignal[Option[DistributionShape]] = currentDraftVar.signal

  /** Each form's own dirty flag, written ONLY by that form's own
    * `isDirty --> ...writer` binding — see [[app.views.PortfolioFormView]] /
    * [[app.views.RiskLeafFormView]]. Kept separate (not both writing into
    * `isEditDirtyVar` directly) because a form's own dirty check goes false
    * the moment its target is Locked (nothing to save), and a shared flag
    * would then overwrite whatever the *other*, untouched form had set —
    * typing into a blank portfolio form, then clicking to view an unrelated
    * leaf, would clear the portfolio draft's dirty flag.
    */
  val portfolioFormDirtyVar: Var[Boolean] = Var(false)
  val leafFormDirtyVar: Var[Boolean] = Var(false)

  /** Whether EITHER node-editing form (leaf or portfolio) has unsaved
    * content — i.e. whether `FormMode.isFormDirty` is currently true for
    * that form. Derived from `portfolioFormDirtyVar`/`leafFormDirtyVar` (see
    * above), never written directly by either view. Read by
    * [[app.views.TreePreview]]'s navigate-away confirm gate, `DesignView`'s
    * branch/tree-switch guard, and `TreeBuilderView`'s submit guard.
    */
  val isEditDirtyVar: Var[Boolean] = Var(false)

  // App-lifetime subscription (TreeBuilderState lives for the app lifetime,
  // like LECChartState's userSelectionBus) — keeps isEditDirtyVar as the OR
  // of both forms' own flags, so neither form's "not dirty" can clobber the
  // other's "dirty".
  portfolioFormDirtyVar.signal.combineWith(leafFormDirtyVar.signal).foreach { (p, l) =>
    isEditDirtyVar.set(p || l)
  }(using unsafeWindowOwner)

  val rootLabel = "(root)"

  // ── Node selection (Design view in-place editing) ────────────

  /** The node-editing form's current state — nothing selected, viewing a saved
    * node read-only, editing one in place, or templating a new node from one.
    * Only one target can be represented at all, so mutual exclusivity is
    * structural rather than hand-maintained.
    */
  val activeForm: Var[FormMode] = Var(FormMode.Blank)

  /** Fired by `startNewTree`/`loadFromTree` alongside `activeForm.set(Blank)`,
    * telling both node-editing forms to clear their own fields regardless of
    * mode. `activeForm.set(Blank)` alone isn't enough when a form was
    * *already* Blank with untouched typed content: `portfolioMode`/`leafMode`
    * are `.distinct`-filtered (so an irrelevant transition elsewhere doesn't
    * spuriously reset an unrelated draft — see `forPortfolio`/`forLeaf`), so
    * a same-to-same Blank transition produces no emission there for the
    * populate/reset subscription to react to. This bus is the explicit
    * "clear regardless" signal that path can't provide.
    */
  val resetFormFieldsBus: EventBus[Unit] = new EventBus[Unit]

  // ── Tree-name validation ──────────────────────────────────────
  /** The tree's own name belongs to the request being assembled, not to any
    * one draft node, so this form's subject is the request and never changes. */
  val subjectVar: Var[ErrorSubject] = Var(ErrorSubject.Request)

  /** The field a refusal about the tree name names. Declared once; the two
    * located readers below refer to it. */
  private val treeNameField: FieldName = FieldName.Name

  /** The tree-name input's live value, located. */
  private def treeNameLocated: Signal[Located[String]] =
    treeNameVar.signal.combineWith(subjectVar.signal).map((v, s) => Located(v, ErrorLocation.Field(s, treeNameField)))

  /** The tree-name input's current value, located, for the submit read. */
  private def treeNameLocatedNow: Located[String] =
    Located(treeNameVar.now(), ErrorLocation.Field(subjectVar.now(), treeNameField))

  private val treeNameErrorRaw: Signal[Option[ValidationError]] =
    treeNameLocated.map(_.refine(ValidationUtil.refineName).left.toOption.flatMap(_.headOption))

  /** Display-controlled tree-name error (only shows after blur or submit trigger). */
  val treeNameError: Signal[Option[String]] = withDisplayControl(TreeName, treeNameErrorRaw.map(_.map(_.message)))

  /** Raw errors for hasErrors check — tree-name is the only builder-level field. */
  override def errorSignals: List[Signal[Option[String]]] = List(treeNameErrorRaw.map(_.map(_.message)))

  /** Parent dropdown options: root sentinel (if unclaimed) + current portfolio
    * names, excluding `excludeSelf` — a node can never be its own parent, and
    * its own occupancy of root must not count as "taken" when it's the node
    * currently being viewed. Without this, viewing/editing/templating the
    * one node (portfolio OR leaf) that already holds root sees root missing
    * from its own option list (taken — by itself); a lone leaf at root is a
    * valid topology (`TreeBuilderLogic.validateRoot`), so leaves need the
    * same self-exclusion portfolios do, not just a portfolio-only check.
    *
    * Callers pass the name of the node currently Locked/Editing/Templating
    * (its true identity, or the identity it was templated from) — `None` for
    * a Blank draft, which has no identity of its own and correctly cannot
    * claim root while another node already holds it.
    */
  def parentOptions(excludeSelf: Signal[Option[String]] = Val(None)): Signal[List[String]] =
    portfoliosVar.signal.combineWith(leavesVar.signal, excludeSelf).map { (ps, ls, excl) =>
      val others = ps.filterNot(p => excl.contains(p.name.value))
      val otherLeaves = ls.filterNot(l => excl.contains(l.name.value))
      val rootTaken = (others.map(_.parent) ++ otherLeaves.map(_.parent)).contains(ParentRef.Root)
      val base = others.map(_.name.value)   // .value at UI-string boundary: parentSelect takes Signal[List[String]]
      if rootTaken then base else rootLabel :: base
    }

  /** Every portfolio name currently in the tree, unfiltered by `excludeSelf` —
    * deliberately NOT the same list `parentOptions` renders as selectable
    * `<option>`s. `parentOptions` shrinks (loses `rootLabel`, loses the
    * viewed/edited node's own name) purely because *which node is asking*
    * changed, on every Locked/Editing/Templating target switch — that's not
    * evidence the CURRENTLY HELD selection stopped being real. This list
    * only changes when a portfolio is actually added, renamed, or removed,
    * so `FormInputs.parentSelect`'s auto-correct can use it to catch a
    * genuinely stale selection (the parent it pointed at got renamed/deleted
    * by the other sub-form) without also firing — and clobbering a
    * still-good selection — on a plain target switch. See `parentSelect`'s
    * own doc comment.
    */
  val allPortfolioNames: Signal[Set[String]] = portfoliosVar.signal.map(_.map(_.name.value).toSet)

  def addPortfolio(name: SafeName.SafeName, parent: ParentRef): Validation[ValidationError, PortfolioDraft] =
    val draft = PortfolioDraft(name, parent, None)
    val result = TreeBuilderLogic.preValidateTopology(
      (portfoliosVar.now() :+ draft).map(p => p.name -> p.parent),
      leavesVar.now().map(l => l.name -> l.parent)
    ).map(_ => draft)
    result match
      case Validation.Success(_, _) =>
        portfoliosVar.update(_ :+ draft)
        activeForm.set(FormMode.Locked(FormTarget.Portfolio(draft.name)))
      case _ => ()
    result

  def addLeaf(
    name:        SafeName.SafeName,
    parent:      ParentRef,
    shape:       DistributionShape,
    probability: OccurrenceProbability
  ): Validation[ValidationError, LeafDraft] =
    val draft = LeafDraft(name, parent, shape, probability, None)
    val result = TreeBuilderLogic.preValidateTopology(
      portfoliosVar.now().map(p => p.name -> p.parent),
      (leavesVar.now() :+ draft).map(l => l.name -> l.parent)
    ).map(_ => draft)
    result match
      case Validation.Success(_, _) =>
        leavesVar.update(_ :+ draft)
        activeForm.set(FormMode.Locked(FormTarget.Leaf(draft.name)))
      case _ => ()
    result

  /** Populate a [[RiskLeafFormState]] from a loaded [[LeafDraft]], via the one
    * shared domain-to-display mapping ([[FormMode.leafDraftToFieldSnapshot]])
    * also used by `isFormDirty` — so populating and dirty-checking can never
    * disagree about what a saved leaf looks like as raw field values.
    *
    * Sets every field unconditionally (not just the active distribution
    * mode's), so switching from viewing one mode to viewing the other never
    * leaves the previous node's stale text sitting in the now-hidden fields.
    */
  def populateLeafForm(state: RiskLeafFormState, leaf: LeafDraft): Unit =
    val snapshot = FormMode.leafDraftToFieldSnapshot(leaf)
    state.subjectVar.set(subjectOf(leaf.id))
    state.distributionModeVar.set(snapshot.mode)
    state.nameVar.set(snapshot.name)
    state.probabilityVar.set(snapshot.probability)
    state.parentVar.set(snapshot.parent)
    state.percentilesVar.set(snapshot.percentiles)
    state.quantilesVar.set(snapshot.quantiles)
    state.termsVar.set(snapshot.terms)
    state.minLossVar.set(snapshot.minLoss)
    state.maxLossVar.set(snapshot.maxLoss)

  /** Populate a [[PortfolioFormState]] from a loaded [[PortfolioDraft]], via
    * the shared mapping also used by `isFormDirty`.
    */
  def populatePortfolioForm(state: PortfolioFormState, portfolio: PortfolioDraft): Unit =
    val snapshot = FormMode.portfolioDraftToFieldSnapshot(portfolio)
    state.subjectVar.set(subjectOf(portfolio.id))
    state.nameVar.set(snapshot.name)
    state.parentVar.set(snapshot.parent)

  /** Update an existing leaf in-place. Preserves the node's server identity (`id`).
    *
    * Validates the new name/parent, then runs `preValidateTopology` on the post-substitution
    * node set. On success, replaces the draft in `leavesVar` and locks `activeForm` on it.
    * Mirrors the `addLeaf` pattern: match on result, then update Var.
    */
  def updateLeaf(
    originalName: SafeName.SafeName,
    name:         SafeName.SafeName,
    parent:       ParentRef,
    shape:        DistributionShape,
    probability:  OccurrenceProbability
  ): Validation[ValidationError, LeafDraft] =
    val existing = leavesVar.now().find(_.name == originalName)
    val draft = LeafDraft(name, parent, shape, probability, existing.flatMap(_.id))
    val result = TreeBuilderLogic.preValidateTopology(
      portfoliosVar.now().map(p => p.name -> p.parent),
      (leavesVar.now().filterNot(_.name == originalName) :+ draft).map(l => l.name -> l.parent)
    ).map(_ => draft)
    result match
      case Validation.Success(_, _) =>
        leavesVar.update(_.map(l => if l.name == originalName then draft else l))
        activeForm.set(FormMode.Locked(FormTarget.Leaf(draft.name)))
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
    newParent:    ParentRef
  ): Validation[ValidationError, PortfolioDraft] =
    val existing = portfoliosVar.now().find(_.name == originalName)
    val draft = PortfolioDraft(newName, newParent, existing.flatMap(_.id))
    val renamedParent = ParentRef.Under(newName)
    val updatedPortfolios = portfoliosVar.now().map { p =>
      (p.name == originalName, p.parent) match
        case (true, _)                                => draft
        case (false, ParentRef.Under(`originalName`))  => p.copy(parent = renamedParent)
        case _                                        => p
    }
    val updatedLeaves = leavesVar.now().map { l =>
      l.parent match
        case ParentRef.Under(`originalName`) => l.copy(parent = renamedParent)
        case _                               => l
    }
    val result = TreeBuilderLogic.preValidateTopology(
      updatedPortfolios.map(p => p.name -> p.parent),
      updatedLeaves.map(l => l.name -> l.parent)
    ).map(_ => draft)
    result match
      case Validation.Success(_, _) =>
        portfoliosVar.set(updatedPortfolios)
        leavesVar.set(updatedLeaves)
        activeForm.set(FormMode.Locked(FormTarget.Portfolio(draft.name)))
      case _ => ()
    result

  /** Remove node by name; cascades removal of portfolio descendants and their leaves. */
  def removeNode(name: SafeName.SafeName): Unit =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val toRemove = TreeBuilderLogic.collectCascade(Set(name), portfolios.map(p => p.name -> p.parent))
    portfoliosVar.set(portfolios.filterNot(p => toRemove.contains(p.name)))
    leavesVar.set(leaves.filterNot(l =>
      toRemove.contains(l.name) || ParentRef.nameOf(l.parent).exists(toRemove.contains)
    ))
    // Clear the active form if it was pointing at a removed node — otherwise it
    // stays locked/mid-edit on a ghost target pointing at a deleted leaf/portfolio.
    if activeForm.now().currentTarget.exists(t => toRemove.contains(t.name)) then
      activeForm.set(FormMode.Blank)

  /** Build backend request with client-side validation. */
  def toRequest(): Validation[ValidationError, RiskTreeDefinitionRequest] =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val treeNameV = refinedTreeName
    val portfoliosV = Validation.succeed(portfolios.map(toPortfolioRequest))
    val leavesV = Validation.succeed(leaves.map(toLeafRequest))
    val topologyV = TreeBuilderLogic.fullValidateTopology(
      portfolios.map(p => p.name -> p.parent),
      leaves.map(l => l.name -> l.parent)
    )

    Validation.validateWith(treeNameV, portfoliosV, leavesV, topologyV) { (treeName, ports, leafs, _) =>
      RiskTreeDefinitionRequest(treeName.value, ports, leafs)
    }

  /** Build update request — the whole tree, with each loaded node submitted
    * under its own identifier and each node added this session submitted as
    * new. The tree ID is preserved.
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

    val treeNameV = refinedTreeName
    // Topology is validated over the full node set (existing + new), exactly as in the
    // create flow — correctness-by-construction is whole-graph, independent of bucketing.
    val topologyV = TreeBuilderLogic.fullValidateTopology(
      portfolios.map(p => p.name -> p.parent),
      leaves.map(l => l.name -> l.parent)
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

  /** Reactive mirror of `isDirty`, for live debugging (`TreeBuilderView`'s
    * debug bar) — recomputes on any change to the tracked fields *or*
    * `loadedSnapshotVar` itself (e.g. after `markJustSaved`), which the
    * plain `isDirty` def above can't reflect on its own since it's
    * pull-based (read via `.now()` on demand) rather than push-based.
    */
  val isDirtySignal: Signal[Boolean] =
    treeNameVar.signal.combineWith(portfoliosVar.signal, leavesVar.signal, loadedSnapshotVar.signal)
      .map { _ => isDirty }

  /** Mark the current draft as matching what the server just confirmed
    * saving — call this right after a create/update submission succeeds.
    *
    * A successful submit doesn't, by itself, update `loadedSnapshotVar` —
    * only `loadFromTree`'s own follow-up reload does, once its separate GET
    * request completes. `editingTreeId` is set to the new tree's id
    * immediately on success (before that GET even starts), so there's a real
    * window — one full round trip — where the tree is already safely
    * persisted server-side but `isDirty` still falls through to the
    * create-mode ("no tree loaded yet") case and reports dirty for any
    * non-empty draft. Anyone reading `isDirty` in that window (e.g.
    * switching scenario branches right after creating a tree) would then see
    * a spurious "unsaved changes" confirm for data that was already saved a
    * moment earlier. `loadFromTree`'s own snapshot, once the reload lands,
    * naturally supersedes this one — same tree, same branch, no discrepancy.
    */
  def markJustSaved(): Unit =
    loadedSnapshotVar.set(Some((treeNameVar.now(), portfoliosVar.now(), leavesVar.now())))

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
    activeForm.set(FormMode.Blank)
    resetFormFieldsBus.emit(())
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
        parent = parentRefOf(tree, p.parentId),
        id     = Some(p.id)
      )
    }.toList

    val leaves = tree.nodes.collect { case l: RiskLeaf => l }.map { l =>
      LeafDraft(
        name        = l.name,
        parent      = parentRefOf(tree, l.parentId),
        shape       = l.shape,
        probability = l.probability,
        id          = Some(l.id)
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
    activeForm.set(FormMode.Blank)
    resetFormFieldsBus.emit(())
    resetTouched()

  // ------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------

  /** The tree name, refined from its current value. */
  private def refinedTreeName: Validation[ValidationError, SafeName.SafeName] =
    toValidation(treeNameLocatedNow.refine(ValidationUtil.refineName))

  /** The subject a form's refusals name for a draft: a node the server has
    * stored is named by its identifier, and one added in this session has none
    * yet. */
  private def subjectOf(id: Option[NodeId]): ErrorSubject =
    id.fold(ErrorSubject.DraftNode)(ErrorSubject.ExistingNode(_))

  /** A loaded node's parent as the drafts hold it: the name of the node its
    * `parentId` points at, or the root when it has no parent. An identifier
    * the index does not resolve also reads as the root; a tree that passed
    * `RiskTree.fromNodes` has none. */
  private def parentRefOf(tree: RiskTree, parentId: Option[NodeId]): ParentRef =
    parentId.flatMap(id => tree.index.nodes.get(id).map(_.name)) match
      case Some(name) => ParentRef.Under(name)
      case None       => ParentRef.Root


  /** The parent as the wire carries it: an absent name means the root. The
    * `Option` belongs to the request type and goes no further inward. */
  private def parentNameOf(parent: ParentRef): Option[String] =
    ParentRef.nameOf(parent).map(_.value.toString)

  // Total conversions: every field is already a refined domain type (`SafeName`,
  // `DistributionShape`, `OccurrenceProbability`), so construction cannot fail.
  // Returning the plain request type keeps totality visible; callers wrap the whole
  // collection once with `Validation.succeed` where a `Validation` is needed for
  // `validateWith`.
  private def toPortfolioRequest(draft: PortfolioDraft): RiskPortfolioDefinitionRequest =
    RiskPortfolioDefinitionRequest(draft.name.value, parentNameOf(draft.parent))

  private def toLeafRequest(draft: LeafDraft): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name              = draft.name.value,
      parentName        = parentNameOf(draft.parent),
      probability       = draft.probability,
      distributionShape = DistributionShapeRequest.fromShape(draft.shape)
    )

  // Identity-preserving variants: emit an update DTO carrying the node's existing id,
  // so the server keeps the same NodeId (and Irmin path) instead of minting a new one.
  // Total for the same reason as the definition variants above.
  private def toPortfolioUpdateRequest(entry: (NodeId, PortfolioDraft)): RiskPortfolioUpdateRequest =
    val (id, draft) = entry
    RiskPortfolioUpdateRequest(id.value, draft.name.value, parentNameOf(draft.parent))

  private def toLeafUpdateRequest(entry: (NodeId, LeafDraft)): RiskLeafUpdateRequest =
    val (id, draft) = entry
    RiskLeafUpdateRequest(
      id                = id.value,
      name              = draft.name.value,
      parentName        = parentNameOf(draft.parent),
      probability       = draft.probability,
      distributionShape = DistributionShapeRequest.fromShape(draft.shape)
    )

