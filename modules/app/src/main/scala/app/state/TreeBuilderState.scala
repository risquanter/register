package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ForEach}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{ValidationUtil, TreeId}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.frontend.TreeBuilderLogic

final case class PortfolioDraft(name: String, parent: Option[String])
final case class LeafDistributionDraft(
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)
final case class LeafDraft(name: String, parent: Option[String], distribution: LeafDistributionDraft)

/**
 * Builder state for constructing a risk tree on the client.
 * Performs client-side validation aligned with backend rules (no ID generation).
 *
 * Extends FormState for the tree-name field's error display timing
 * (same touched / triggerValidation / withDisplayControl infrastructure
 * used by RiskLeafFormState and PortfolioFormState).
 */
final class TreeBuilderState extends FormState:
  val treeNameVar: Var[String] = Var("")
  val portfoliosVar: Var[List[PortfolioDraft]] = Var(Nil)
  val leavesVar: Var[List[LeafDraft]] = Var(Nil)

  /** When set, subsequent submits update the existing tree instead of creating. */
  val editingTreeId: Var[Option[TreeId]] = Var(None)

  /** Whether the builder is in update mode (previously created tree). */
  val isUpdateMode: Signal[Boolean] = editingTreeId.signal.map(_.isDefined)

  val rootLabel = "(root)"

  // ── Tree-name validation ──────────────────────────────────────
  private val treeNameErrorRaw: Signal[Option[String]] = treeNameVar.signal.map { v =>
    ValidationUtil.refineName(v, "tree.name") match
      case Right(_) => None
      case Left(errors) => Some(errors.head.message)
  }

  /** Display-controlled tree-name error (only shows after blur or submit trigger). */
  val treeNameError: Signal[Option[String]] = withDisplayControl("treeName", treeNameErrorRaw)

  /** Raw errors for hasErrors check — tree-name is the only builder-level field. */
  override def errorSignals: List[Signal[Option[String]]] = List(treeNameErrorRaw)

  /** Parent dropdown options: root sentinel (if unclaimed) + current portfolio names. */
  val parentOptions: Signal[List[String]] =
    portfoliosVar.signal.combineWith(leavesVar.signal).map { (ps, ls) =>
      val rootTaken = ps.exists(_.parent.isEmpty) || ls.exists(_.parent.isEmpty)
      val base = ps.map(_.name)
      if rootTaken then base else rootLabel :: base
    }

  def addPortfolio(rawName: String, rawParent: Option[String]): Validation[ValidationError, PortfolioDraft] =
    val result = for
      name <- validateName(rawName, "portfolio.name")
      parent <- validateParentName(rawParent, "portfolio.parentName")
      draft = PortfolioDraft(name, parent)
      _ <- TreeBuilderLogic.preValidateTopology(
        (portfoliosVar.now() :+ draft).map(p => p.name -> p.parent),
        leavesVar.now().map(l => l.name -> l.parent)
      )
    yield draft
    result match
      case Validation.Success(_, draft) => portfoliosVar.update(_ :+ draft)
      case _ => ()
    result

  def addLeaf(rawName: String, rawParent: Option[String], dist: LeafDistributionDraft): Validation[ValidationError, LeafDraft] =
    val result = for
      name <- validateName(rawName, "leaf.name")
      parent <- validateParentName(rawParent, "leaf.parentName")
      _ <- validateDistribution(dist)
      draft = LeafDraft(name, parent, dist)
      _ <- TreeBuilderLogic.preValidateTopology(
        portfoliosVar.now().map(p => p.name -> p.parent),
        (leavesVar.now() :+ draft).map(l => l.name -> l.parent)
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
    val toRemove = TreeBuilderLogic.collectCascade(Set(name), portfolios.map(p => p.name -> p.parent))
    portfoliosVar.set(portfolios.filterNot(p => toRemove.contains(p.name)))
    leavesVar.set(leaves.filterNot(l => toRemove.contains(l.name) || l.parent.exists(toRemove.contains)))

  /** Build backend request with client-side validation. */
  def toRequest(): Validation[ValidationError, RiskTreeDefinitionRequest] =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val treeNameV = validateName(treeNameVar.now(), "tree.name")
    val portfoliosV = Validation.validateAll(portfolios.map(toPortfolioRequest))
    val leavesV = Validation.validateAll(leaves.map(toLeafRequest))
    val topologyV = TreeBuilderLogic.fullValidateTopology(portfolios.map(p => p.name -> p.parent), leaves.map(l => l.name -> l.parent))

    Validation.validateWith(treeNameV, portfoliosV, leavesV, topologyV) { (treeName, ports, leafs, _) =>
      RiskTreeDefinitionRequest(treeName, ports, leafs)
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

  // ------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------

  private def validateName(value: String, field: String): Validation[ValidationError, String] =
    toValidation(ValidationUtil.refineName(value, field)).map(_.value)

  private def validateParentName(raw: Option[String], field: String): Validation[ValidationError, Option[String]] =
    raw match
      case Some(v) if v.trim.nonEmpty => validateName(v, field).map(Some(_))
      case _ => Validation.succeed(None)

  private def validateDistribution(dist: LeafDistributionDraft): Validation[ValidationError, Distribution] =
    Distribution.create(
      distributionType = dist.distributionType,
      probability = dist.probability,
      minLoss = dist.minLoss,
      maxLoss = dist.maxLoss,
      percentiles = dist.percentiles,
      quantiles = dist.quantiles,
      fieldPrefix = "leaf"
    )

  private def toPortfolioRequest(draft: PortfolioDraft): Validation[ValidationError, RiskPortfolioDefinitionRequest] =
    Validation.succeed(RiskPortfolioDefinitionRequest(draft.name, draft.parent))

  private def toLeafRequest(draft: LeafDraft): Validation[ValidationError, RiskLeafDefinitionRequest] =
    validateDistribution(draft.distribution).map { _ =>
      RiskLeafDefinitionRequest(
        name = draft.name,
        parentName = draft.parent,
        distributionType = draft.distribution.distributionType,
        probability = draft.distribution.probability,
        minLoss = draft.distribution.minLoss,
        maxLoss = draft.distribution.maxLoss,
        percentiles = draft.distribution.percentiles,
        quantiles = draft.distribution.quantiles
      )
    }

