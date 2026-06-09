package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ForEach}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.{Distribution, RiskTree, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{ValidationUtil, TreeId, SafeName, NodeId, Probability}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, RiskPortfolioUpdateRequest, RiskLeafUpdateRequest}
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
  probability:  Probability,
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

  def addLeaf(rawName: String, rawParent: Option[String], shape: Distribution, probability: Probability): Validation[ValidationError, LeafDraft] =
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

  /** Remove node by name; cascades removal of portfolio descendants and their leaves. */
  def removeNode(name: String): Unit =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val toRemove = TreeBuilderLogic.collectCascade(Set(name), portfolios.map(p => p.name.value.toString -> p.parent.map(_.value.toString)))
    portfoliosVar.set(portfolios.filterNot(p => toRemove.contains(p.name.value.toString)))
    leavesVar.set(leaves.filterNot(l => toRemove.contains(l.name.value.toString) || l.parent.exists(n => toRemove.contains(n.value.toString))))

  /** Build backend request with client-side validation. */
  def toRequest(): Validation[ValidationError, RiskTreeDefinitionRequest] =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val treeNameV = validateName(treeNameVar.now(), "tree.name")
    val portfoliosV = Validation.validateAll(portfolios.map(toPortfolioRequest))
    val leavesV = Validation.validateAll(leaves.map(toLeafRequest))
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
    val newPortfoliosV      = Validation.validateAll(newPortfolioDrafts.map(toPortfolioRequest))
    val existingLeavesV     = Validation.validateAll(existingLeaves.map(toLeafUpdateRequest))
    val newLeavesV          = Validation.validateAll(newLeafDrafts.map(toLeafRequest))

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

  /** True if the builder contains any unsaved content. */
  def isDirty: Boolean =
    treeNameVar.now().trim.nonEmpty || portfoliosVar.now().nonEmpty || leavesVar.now().nonEmpty

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

    currentDraftVar.set(None)
    treeNameVar.set(tree.name.value)
    portfoliosVar.set(portfolios)
    leavesVar.set(leaves)
    editingTreeId.set(Some(tree.id))
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


  private def toPortfolioRequest(draft: PortfolioDraft): Validation[ValidationError, RiskPortfolioDefinitionRequest] =
    Validation.succeed(RiskPortfolioDefinitionRequest(draft.name.value, draft.parent.map(_.value)))

  private def toLeafRequest(draft: LeafDraft): Validation[ValidationError, RiskLeafDefinitionRequest] =
    Validation.succeed(RiskLeafDefinitionRequest(
      name             = draft.name.value,
      parentName       = draft.parent.map(_.value),
      distributionType = draft.distribution.distributionType.toString,
      probability      = draft.probability,
      minLoss          = draft.distribution.minLoss.map(identity),
      maxLoss          = draft.distribution.maxLoss.map(identity),
      percentiles      = draft.distribution.percentiles,
      quantiles        = draft.distribution.quantiles,
      terms            = draft.distribution.terms.map(_.toInt)
    ))

  // Identity-preserving variant: emits an update DTO that carries the node's existing
  // id, so the server keeps the same NodeId (and Irmin path) instead of minting a new one.
  // Names/parents are already `SafeName`, so the portfolio variant cannot fail.
  private def toPortfolioUpdateRequest(entry: (NodeId, PortfolioDraft)): RiskPortfolioUpdateRequest =
    val (id, draft) = entry
    RiskPortfolioUpdateRequest(id.value, draft.name.value, draft.parent.map(_.value))

  private def toLeafUpdateRequest(entry: (NodeId, LeafDraft)): Validation[ValidationError, RiskLeafUpdateRequest] =
    val (id, draft) = entry
    Validation.succeed(RiskLeafUpdateRequest(
      id               = id.value,
      name             = draft.name.value,
      parentName       = draft.parent.map(_.value),
      distributionType = draft.distribution.distributionType.toString,
      probability      = draft.probability,
      minLoss          = draft.distribution.minLoss.map(identity),
      maxLoss          = draft.distribution.maxLoss.map(identity),
      percentiles      = draft.distribution.percentiles,
      quantiles        = draft.distribution.quantiles,
      terms            = draft.distribution.terms.map(_.toInt)
    ))

