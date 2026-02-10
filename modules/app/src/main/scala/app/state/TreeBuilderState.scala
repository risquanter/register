package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ForEach}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}

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
 */
final class TreeBuilderState:
  val treeNameVar: Var[String] = Var("")
  val portfoliosVar: Var[List[PortfolioDraft]] = Var(Nil)
  val leavesVar: Var[List[LeafDraft]] = Var(Nil)

  private val rootLabel = "(root)"

  /** Parent dropdown options: root sentinel + current portfolio names. */
  val parentOptions: Signal[List[String]] =
    portfoliosVar.signal.map(ps => rootLabel :: ps.map(_.name))

  def addPortfolio(rawName: String, rawParent: Option[String]): Validation[ValidationError, PortfolioDraft] =
    for
      name <- validateName(rawName, "portfolio.name")
      parent <- validateParentName(rawParent, "portfolio.parentName")
      draft = PortfolioDraft(name, parent)
      updatedPortfolios = portfoliosVar.now() :+ draft
      _ <- validateTopology(updatedPortfolios, leavesVar.now())
    yield
      portfoliosVar.set(updatedPortfolios)
      draft

  def addLeaf(rawName: String, rawParent: Option[String], dist: LeafDistributionDraft): Validation[ValidationError, LeafDraft] =
    for
      name <- validateName(rawName, "leaf.name")
      parent <- validateParentName(rawParent, "leaf.parentName")
      _ <- validateDistribution(dist)
      draft = LeafDraft(name, parent, dist)
      updatedLeaves = leavesVar.now() :+ draft
      _ <- validateTopology(portfoliosVar.now(), updatedLeaves)
    yield
      leavesVar.set(updatedLeaves)
      draft

  /** Remove node by name; cascades removal of portfolio descendants and their leaves. */
  def removeNode(name: String): Unit =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val toRemove = collectCascade(Set(name), portfolios)
    portfoliosVar.set(portfolios.filterNot(p => toRemove.contains(p.name)))
    leavesVar.set(leaves.filterNot(l => toRemove.contains(l.name) || l.parent.exists(toRemove.contains)))

  /** Build backend request with client-side validation. */
  def toRequest(): Validation[ValidationError, RiskTreeDefinitionRequest] =
    val portfolios = portfoliosVar.now()
    val leaves = leavesVar.now()
    val treeNameV = validateName(treeNameVar.now(), "tree.name")
    val portfoliosV = Validation.validateAll(portfolios.map(toPortfolioRequest))
    val leavesV = Validation.validateAll(leaves.map(toLeafRequest))
    val topologyV = validateTopology(portfolios, leaves)

    Validation.validateWith(treeNameV, portfoliosV, leavesV, topologyV) { (treeName, ports, leafs, _) =>
      RiskTreeDefinitionRequest(treeName, ports, leafs)
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

  private def validateTopology(portfolios: List[PortfolioDraft], leaves: List[LeafDraft]): Validation[ValidationError, Unit] =
    val names = portfolios.map(_.name) ++ leaves.map(_.name)
    val duplicates = names.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
    val portfolioNames = portfolios.map(_.name).toSet
    val validations = List(
      require(duplicates.isEmpty, "tree.names", ValidationErrorCode.DUPLICATE_VALUE, s"Duplicate names: ${duplicates.mkString(", ")}"),
      validateRoot(portfolios, leaves),
      validatePortfolioParents(portfolios, portfolioNames),
      validateLeafParents(leaves, portfolioNames)
    )

    Validation.validateAll(validations).as(())

  private def validateRoot(portfolios: List[PortfolioDraft], leaves: List[LeafDraft]): Validation[ValidationError, Unit] =
    if portfolios.isEmpty then
      leaves match
        case leaf :: Nil if leaf.parent.isEmpty => Validation.succeed(())
        case Nil => Validation.fail(ValidationError("tree", ValidationErrorCode.REQUIRED_FIELD, "Tree requires a root"))
        case _ => Validation.fail(ValidationError("tree.leaves", ValidationErrorCode.INVALID_COMBINATION, "Leaves require a portfolio parent unless lone-leaf tree"))
    else
      val roots = portfolios.filter(_.parent.isEmpty)
      roots match
        case Nil => Validation.fail(ValidationError("tree.portfolios", ValidationErrorCode.REQUIRED_FIELD, "Exactly one root portfolio required"))
        case _ :: Nil => Validation.succeed(())
        case _ => Validation.fail(ValidationError("tree.portfolios", ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple root portfolios found"))

  private def validatePortfolioParents(portfolios: List[PortfolioDraft], portfolioNames: Set[String]): Validation[ValidationError, Unit] =
    val checks = portfolios.flatMap { p =>
      p.parent.map { parentName =>
        require(portfolioNames.contains(parentName), s"portfolio[${p.name}].parentName", ValidationErrorCode.MISSING_REFERENCE, s"Parent portfolio '${parentName}' not found")
      }
    }
    Validation.validateAll(checks).as(())

  private def validateLeafParents(leaves: List[LeafDraft], portfolioNames: Set[String]): Validation[ValidationError, Unit] =
    val hasPortfolios = portfolioNames.nonEmpty
    val checks = leaves.map { leaf =>
      leaf.parent match
        case Some(parentName) if portfolioNames.contains(parentName) => Validation.succeed(())
        case Some(parentName) => Validation.fail(ValidationError(s"leaf[${leaf.name}].parentName", ValidationErrorCode.MISSING_REFERENCE, s"Parent portfolio '${parentName}' not found"))
        case None if hasPortfolios => Validation.fail(ValidationError(s"leaf[${leaf.name}].parentName", ValidationErrorCode.REQUIRED_FIELD, "Leaf must select a parent portfolio"))
        case None => Validation.succeed(()) // lone-leaf tree handled in root validation
    }
    Validation.validateAll(checks).as(())

  private def collectCascade(targets: Set[String], portfolios: List[PortfolioDraft]): Set[String] =
    val children = portfolios.collect { case p if p.parent.exists(targets.contains) => p.name }.toSet
    if children.isEmpty then targets else collectCascade(targets ++ children, portfolios)

  private def require(cond: Boolean, field: String, code: ValidationErrorCode, message: String): Validation[ValidationError, Unit] =
    if cond then Validation.succeed(()) else Validation.fail(ValidationError(field, code, message))
