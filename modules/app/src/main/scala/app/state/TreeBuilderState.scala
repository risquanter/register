package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ForEach}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.{Distribution, RiskTree, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{ValidationUtil, TreeId, SafeName}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.frontend.TreeBuilderLogic

final case class PortfolioDraft(
  name: SafeName.SafeName,
  parent: Option[SafeName.SafeName]
)
final case class DistributionDraft(
  distributionType: String,
  minLoss:          Option[Long],
  maxLoss:          Option[Long],
  percentiles:      Option[Array[Double]],
  quantiles:        Option[Array[Double]],
  terms:            Option[Int] = None
)
final case class LeafDistributionDraft(
  shape:       DistributionDraft,
  probability: Double
)
final case class LeafDraft(
  name: SafeName.SafeName,
  parent: Option[SafeName.SafeName],
  distribution: LeafDistributionDraft
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
  val currentDraftVar: Var[Option[DistributionDraft]] = Var(None)
  val draftSignal: StrictSignal[Option[DistributionDraft]] = currentDraftVar.signal

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

  def addLeaf(rawName: String, rawParent: Option[String], dist: LeafDistributionDraft): Validation[ValidationError, LeafDraft] =
    val result = for
      name <- validateName(rawName, "leaf.name")
      parent <- validateParentName(rawParent, "leaf.parentName")
      _ <- validateDistribution(dist.shape, dist.probability)
      draft = LeafDraft(name, parent, dist)
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
    toRequest().map { createReq =>
      RiskTreeUpdateRequest(
        name = createReq.name,
        portfolios = Seq.empty,
        leaves = Seq.empty,
        newPortfolios = createReq.portfolios,
        newLeaves = createReq.leaves
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
        parent = p.parentId.flatMap(id => tree.index.nodes.get(id).map(_.name))
      )
    }.toList

    val leaves = tree.nodes.collect { case l: RiskLeaf => l }.map { l =>
      val shape = DistributionDraft(
        distributionType = l.distributionType.toString,
        minLoss          = l.minLoss.map(identity),
        maxLoss          = l.maxLoss.map(identity),
        percentiles      = l.percentiles,
        quantiles        = l.quantiles,
        terms            = l.terms.map(_.toInt)
      )
      val dist = LeafDistributionDraft(shape = shape, probability = l.probability)
      LeafDraft(
        name         = l.name,
        parent       = l.parentId.flatMap(id => tree.index.nodes.get(id).map(_.name)),
        distribution = dist
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

  private def validateDistribution(shape: DistributionDraft, probability: Double): Validation[ValidationError, Distribution] =
    Distribution.create(
      distributionType = shape.distributionType,
      probability = probability,
      minLoss = shape.minLoss,
      maxLoss = shape.maxLoss,
      percentiles = shape.percentiles,
      quantiles = shape.quantiles,
      fieldPrefix = "leaf",
      terms = shape.terms
    )

  private def toPortfolioRequest(draft: PortfolioDraft): Validation[ValidationError, RiskPortfolioDefinitionRequest] =
    Validation.succeed(RiskPortfolioDefinitionRequest(draft.name.value, draft.parent.map(_.value)))

  private def toLeafRequest(draft: LeafDraft): Validation[ValidationError, RiskLeafDefinitionRequest] =
    validateDistribution(draft.distribution.shape, draft.distribution.probability).map { dist =>
      RiskLeafDefinitionRequest(
        name             = draft.name.value,
        parentName       = draft.parent.map(_.value),
        distributionType = dist.distributionType.toString,
        probability      = dist.probability,
        minLoss          = dist.minLoss.map(identity),
        maxLoss          = dist.maxLoss.map(identity),
        percentiles      = dist.percentiles,
        quantiles        = dist.quantiles,
        terms            = dist.terms.map(_.toInt)
      )
    }

