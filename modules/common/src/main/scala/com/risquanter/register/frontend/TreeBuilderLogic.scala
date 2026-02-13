package com.risquanter.register.frontend

import zio.prelude.Validation
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * Pure validation helpers for frontend tree building (shared so we can test on JVM).
 */
object TreeBuilderLogic:

  type Name = String
  type Parent = Option[String]

  /** Structural topology rules safe for incremental construction:
    * uniqueness, single root, valid parent references.
    * Does '''not''' check for childless portfolios — that is a valid
    * mid-construction state (e.g. adding a portfolio before its leaves).
    */
  def preValidateTopology(
    portfolios: List[(Name, Parent)],
    leaves: List[(Name, Parent)]
  ): Validation[ValidationError, Unit] =
    val names = portfolios.map(_._1) ++ leaves.map(_._1)
    val duplicates = names.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
    val portfolioNames = portfolios.map(_._1).toSet
    val validations = List(
      requireCond(duplicates.isEmpty, "tree.names", ValidationErrorCode.DUPLICATE_VALUE, s"Duplicate names: ${duplicates.mkString(", ")}"),
      validateRoot(portfolios, leaves),
      validatePortfolioParents(portfolios, portfolioNames),
      validateLeafParents(leaves, portfolioNames)
    )
    Validation.validateAll(validations).as(())

  /** Full topology validation for submit time: all incremental rules
    * '''plus''' the requirement that every portfolio has ≥1 child.
    */
  def fullValidateTopology(
    portfolios: List[(Name, Parent)],
    leaves: List[(Name, Parent)]
  ): Validation[ValidationError, Unit] =
    Validation.validateWith(
      preValidateTopology(portfolios, leaves),
      validateNonEmptyPortfolios(portfolios, leaves)
    )((_, _) => ())

  /** Collect a portfolio and all descendant portfolio names (for cascade delete). */
  def collectCascade(targets: Set[String], portfolios: List[(Name, Parent)]): Set[String] =
    val newChildren = portfolios.collect {
      case (name, Some(parent)) if targets.contains(parent) && !targets.contains(name) => name
    }.toSet
    if newChildren.isEmpty then targets else collectCascade(targets ++ newChildren, portfolios)

  private def validateRoot(portfolios: List[(Name, Parent)], leaves: List[(Name, Parent)]): Validation[ValidationError, Unit] =
    if portfolios.isEmpty then
      leaves match
        case (leafName, None) :: Nil => Validation.succeed(())
        case Nil => Validation.fail(ValidationError("tree", ValidationErrorCode.REQUIRED_FIELD, "Tree requires a root"))
        case _ => Validation.fail(ValidationError("tree.leaves", ValidationErrorCode.INVALID_COMBINATION, "Leaves require a portfolio parent unless lone-leaf tree"))
    else
      val roots = portfolios.filter(_._2.isEmpty)
      roots match
        case Nil => Validation.fail(ValidationError("tree.portfolios", ValidationErrorCode.REQUIRED_FIELD, "Exactly one root portfolio required"))
        case _ :: Nil => Validation.succeed(())
        case _ => Validation.fail(ValidationError("tree.portfolios", ValidationErrorCode.AMBIGUOUS_REFERENCE, "Multiple root portfolios found"))

  private def validatePortfolioParents(portfolios: List[(Name, Parent)], portfolioNames: Set[String]): Validation[ValidationError, Unit] =
    val checks = portfolios.flatMap { case (name, parent) =>
      parent.map { parentName =>
        requireCond(portfolioNames.contains(parentName), s"portfolio[$name].parentName", ValidationErrorCode.MISSING_REFERENCE, s"Parent portfolio '$parentName' not found")
      }
    }
    Validation.validateAll(checks).as(())

  private def validateLeafParents(leaves: List[(Name, Parent)], portfolioNames: Set[String]): Validation[ValidationError, Unit] =
    val hasPortfolios = portfolioNames.nonEmpty
    val checks = leaves.map { case (name, parent) =>
      parent match
        case Some(parentName) if portfolioNames.contains(parentName) => Validation.succeed(())
        case Some(parentName) => Validation.fail(ValidationError(s"leaf[$name].parentName", ValidationErrorCode.MISSING_REFERENCE, s"Parent portfolio '$parentName' not found"))
        case None if hasPortfolios => Validation.fail(ValidationError(s"leaf[$name].parentName", ValidationErrorCode.REQUIRED_FIELD, "Leaf must select a parent portfolio"))
        case None => Validation.succeed(())
    }
    Validation.validateAll(checks).as(())

  /** Every portfolio must have ≥1 child. Together with acyclicity, this ensures
    * every path from root terminates at a leaf. See `requireNonEmptyPortfolios`
    * in `RiskTreeRequests` for the full inductive proof.
    */
  private def validateNonEmptyPortfolios(
    portfolios: List[(Name, Parent)],
    leaves: List[(Name, Parent)]
  ): Validation[ValidationError, Unit] =
    val childParents = (portfolios.flatMap(_._2) ++ leaves.flatMap(_._2)).groupBy(identity).view.mapValues(_.size).toMap
    val empty = portfolios.map(_._1).filterNot(childParents.contains)
    if empty.isEmpty then Validation.succeed(())
    else Validation.fail(ValidationError(
      "tree.portfolios", ValidationErrorCode.EMPTY_COLLECTION,
      s"Every portfolio must have at least one child: ${empty.mkString(", ")}"
    ))

  private def requireCond(cond: Boolean, field: String, code: ValidationErrorCode, message: String): Validation[ValidationError, Unit] =
    if cond then Validation.succeed(()) else Validation.fail(ValidationError(field, code, message))
