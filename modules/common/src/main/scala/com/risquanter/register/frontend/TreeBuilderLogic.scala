package com.risquanter.register.frontend

import zio.prelude.Validation

import com.risquanter.register.domain.data.iron.{SafeName, ValidationMessages}
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode
}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef

/** Pure topology rules for a tree that is still being assembled, shared by the
  * browser and the Java Virtual Machine tests.
  *
  * Its inputs are already refined, because every caller holds refined drafts:
  * the rules decide relationships between nodes and would pass a malformed name
  * through untouched, so a parameter admitting one would claim a defence this
  * object does not perform.
  *
  * A node here has no identifier yet, so a failure about one node reports at
  * `ErrorSubject.DraftNode` and a failure about the shape of the whole draft
  * reports at `ErrorLocation.Whole`.
  */
object TreeBuilderLogic:

  type Name = SafeName.SafeName
  type Parent = ParentRef

  /** The name of the draft node being added or edited. */
  private val draftName: ErrorLocation =
    ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.Name)

  /** The parent that draft node selects. */
  private val draftParent: ErrorLocation =
    ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.ParentName)

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
    val portfolioNames = portfolios.map(_._1).toSet
    val validations = List(
      SafeName.requireDistinct(names, draftName),
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
  def collectCascade(targets: Set[Name], portfolios: List[(Name, Parent)]): Set[Name] =
    val newChildren = portfolios.collect {
      case (name, ParentRef.Under(parent)) if targets.contains(parent) && !targets.contains(name) => name
    }.toSet
    if newChildren.isEmpty then targets else collectCascade(targets ++ newChildren, portfolios)

  private def validateRoot(portfolios: List[(Name, Parent)], leaves: List[(Name, Parent)]): Validation[ValidationError, Unit] =
    if portfolios.isEmpty then
      leaves match
        case (_, ParentRef.Root) :: Nil => Validation.succeed(())
        case Nil => Validation.fail(ValidationError(ErrorLocation.Whole, ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.exactlyOneRootRequired))
        case _ => Validation.fail(ValidationError(ErrorLocation.Whole, ValidationErrorCode.INVALID_COMBINATION, ValidationMessages.leafNeedsPortfolioParent))
    else
      // Distinct by name: a duplicate-name resubmission of the same root
      // (already flagged separately by the uniqueness check above) must not
      // also read as "a second root" just because the candidate list
      // temporarily holds two entries with the same name.
      val roots = portfolios.collect { case (name, ParentRef.Root) => name }.distinct
      roots match
        case Nil => Validation.fail(ValidationError(ErrorLocation.Whole, ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.exactlyOneRootRequired))
        case _ :: Nil => Validation.succeed(())
        case _ => Validation.fail(ValidationError(ErrorLocation.Whole, ValidationErrorCode.AMBIGUOUS_REFERENCE, ValidationMessages.multipleRootsFound))

  private def validatePortfolioParents(portfolios: List[(Name, Parent)], portfolioNames: Set[Name]): Validation[ValidationError, Unit] =
    val checks = portfolios.map { case (_, parent) =>
      parent match
        case ParentRef.Root => Validation.succeed(())
        case ParentRef.Under(parentName) if portfolioNames.contains(parentName) => Validation.succeed(())
        case ParentRef.Under(parentName) =>
          Validation.fail(ValidationError(draftParent, ValidationErrorCode.MISSING_REFERENCE, ValidationMessages.parentNameNotFound(parentName.value.toString)))
    }
    Validation.validateAll(checks).as(())

  private def validateLeafParents(leaves: List[(Name, Parent)], portfolioNames: Set[Name]): Validation[ValidationError, Unit] =
    val hasPortfolios = portfolioNames.nonEmpty
    val checks = leaves.map { case (_, parent) =>
      parent match
        case ParentRef.Under(parentName) if portfolioNames.contains(parentName) => Validation.succeed(())
        case ParentRef.Under(parentName) =>
          Validation.fail(ValidationError(draftParent, ValidationErrorCode.MISSING_REFERENCE, ValidationMessages.parentNameNotFound(parentName.value.toString)))
        case ParentRef.Root if hasPortfolios =>
          Validation.fail(ValidationError(draftParent, ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.leafNeedsPortfolioParent))
        case ParentRef.Root => Validation.succeed(())
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
    val namedParents =
      (portfolios.flatMap(p => ParentRef.nameOf(p._2)) ++ leaves.flatMap(l => ParentRef.nameOf(l._2))).toSet
    val empty = portfolios.map(_._1).filterNot(namedParents.contains)
    if empty.isEmpty then Validation.succeed(())
    else Validation.fail(ValidationError(
      ErrorLocation.Whole, ValidationErrorCode.EMPTY_COLLECTION,
      ValidationMessages.portfoliosWithoutChildren(empty.map(_.value.toString))
    ))

