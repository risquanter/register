package com.risquanter.register.domain.data

import zio.prelude.{Validation, Equal}
import zio.json.JsonCodec
import sttp.tapir.Schema
import io.github.iltotore.iron.*

import com.risquanter.register.common.FolSymbols.{NodeFunction, QueryPredicate, Signatures}
import com.risquanter.register.domain.data.iron.{NodeId, NonNegativeInt, TargetingSource, TargetingSourceConstraint}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.http.errors.{ParseProblem, Positioned}

import parser.{FOLParser, ParseError}
import logic.{Formula, FOL, FOLUtil}
import vql.fragment.{Fragment, FragmentCheck, FragmentViolation}

/** A mitigation's targeting predicate: a bare single-free-variable FOL formula
  * that resolves, server-side, to the set of nodes the mitigation scopes.
  *
  * Only the source text is stored and serialized; the parsed formula is derived
  * state. `create` is the single validation boundary (decode == create), and
  * `ofNode` builds its text and passes it through `create`, so no
  * `TargetingPredicate` value exists whose source is not a well-formed member
  * of the targeting fragment. It admits structural and
  * attribute predicates over one variable, and names a node by
  * `node_id("…")`. It rejects quantifiers, every function application other
  * than `node_id` (the targeting fragment), so also a node named by
  * `node_named`, and the mitigation-state predicates
  * `mitigate`/`mitigated`/`unmitigated`, case-insensitively (a mitigation must
  * not target by the state it changes).
  */
final case class TargetingPredicate private (source: TargetingSource)

object TargetingPredicate:

  /** The length bounds of `TargetingSource`, as the values a refusal carries.
    * They mirror the type's `MinLength` and `MaxLength` literals, which cannot
    * be read back at runtime. */
  private val MinSourceLength: NonNegativeInt = 1
  private val MaxSourceLength: NonNegativeInt = 2048

  /** Boundary validation, cross-compiled (browser and server), in two phases:
    *  - a gate that short-circuits, because each step needs the prior's output:
    *    1. length-refine into `TargetingSource` (1–2048) — also bounds the
    *       input the parser sees; 2. parse via the foundation `FOLParser`;
    *  - once parsed, two independent checks on the formula, whose errors
    *    accumulate so an authoring form reports every problem at once:
    *    3. targeting-fragment membership: no quantifier, no function term
    *       other than `node_id`, so no `node_named` and a rename cannot move
    *       the scope onto another node, and no mitigation-state predicate,
    *       compared after lower-casing. Every such refusal is reported, once
    *       each, in the order the formula holds them;
    *    4. exactly one free variable (the target variable).
    *
    * A parse refusal carries the parser's typed refusal at its position, and
    * never the parser's sentence or the whole text. Of the caller's text it
    * holds only words and literal contents within their alphabets; an
    * operator or punctuation crosses as a case, and anything else as a kind
    * with no text (ADR-035). A parser refusal that does not translate is a
    * defect and throws.
    *
    * Every refusal is at `subject`'s `TargetingSource` field, so a predicate
    * inside the third submitted mitigation is refused at that mitigation.
    */
  def create(source: String, subject: ErrorSubject): Validation[ValidationError, TargetingPredicate] =
    val location = ErrorLocation.Field(subject, FieldName.TargetingSource)
    val gate: Either[FieldProblem, (TargetingSource, Formula[FOL])] =
      for
        refined <- source
          .refineEither[TargetingSourceConstraint]
          .left.map(_ => FieldProblem.LengthOutOfRange(MinSourceLength, MaxSourceLength))
        formula <- FOLParser.parse(refined)
          .left.map(error => FieldProblem.TargetingPredicateUnparseable(parseProblem(error)))
      yield (refined, formula)

    def checked(check: Either[FieldProblem, Unit]): Validation[ValidationError, Unit] =
      Validation.fromEither(check.left.map(ValidationError(location, _)))

    Validation.fromEither(gate.left.map(ValidationError(location, _))).flatMap { case (refined, formula) =>
      Validation.validateWith(
        checkFragment(formula, location),
        checked(requireSingleFreeVariable(formula))
      )((_, _) => new TargetingPredicate(refined))
    }

  /** The predicate naming exactly the node `id`: `eq(x, node_id("<id>"))`. It
    * goes through `create`, so it is checked like every other predicate. A
    * `NodeId` admits only the 26 characters of a ULID, so the text always
    * parses and fits the length bound, and a refusal here is a defect that
    * throws (ADR-033). */
  def ofNode(id: NodeId): TargetingPredicate =
    create(nodeSource(id), ErrorSubject.Request).toEither.fold(
      errors => throw new IllegalStateException(s"The predicate naming one node was refused: ${errors.head.problem}"),
      identity)

  /** The source text of `ofNode`. */
  private def nodeSource(id: NodeId): String =
    s"""${QueryPredicate.Eq.symbol}(x, ${NodeFunction.ById.symbol}("${id.value}"))"""

  /** The nodes the predicate names by `node_id("…")`, in source order and with
    * repeats kept. The source passed `create`, so it parses again. A literal
    * that is not a node identifier names no node and is left out.
    *
    * {{{
    * referencedNodes(leaf(x) /\ descendant_of(x, node_id("A")) \/ eq(x, node_id("B")))  // List(A, B)
    * referencedNodes(leaf(x))                                                           // Nil
    * }}}
    */
  def referencedNodes(predicate: TargetingPredicate): List[NodeId] =
    FOLParser.parse(predicate.source).fold(_ => Nil, FragmentCheck.literalArguments(_, NodeFunction.ById.symbolName))
      .flatMap(literal => NodeId.fromString(literal.value).toOption)

  private def parseProblem(error: ParseError): Positioned[ParseProblem] =
    ParseProblem.fromFormulaError(error).fold(
      reason => throw new IllegalStateException(s"The formula parser returned a refusal that does not translate: $reason"),
      identity)

  /** Every targeting-fragment refusal of the formula, once each, in the order
    * the formula holds them. */
  private def checkFragment(formula: Formula[FOL], location: ErrorLocation): Validation[ValidationError, Unit] =
    Validation.validateAll(
      FragmentCheck.violations(formula, targetingFragment)
        .map(fragmentProblem)
        .distinct
        .map(problem => Validation.fail(ValidationError(location, problem)))
    ).as(())

  /** The fragment a targeting predicate belongs to. */
  private val targetingFragment: Fragment =
    Fragment.Targeting(Signatures.targetingFunctions, Signatures.targetingForbiddenPredicates)

  private def fragmentProblem(v: FragmentViolation): FieldProblem = v match
    case FragmentViolation.QuantifierNotAllowed => FieldProblem.TargetingQuantifierNotAllowed
    case FragmentViolation.FunctionApplicationNotAllowed(function) if function.value == NodeFunction.ByName.symbol =>
      FieldProblem.TargetingNodeNameReferenced
    case FragmentViolation.FunctionApplicationNotAllowed(_) => FieldProblem.TargetingFunctionNotAllowed
    case FragmentViolation.PredicateNotAllowed(_)           => FieldProblem.TargetingMitigationStateReferenced
    case FragmentViolation.QuantifierDepthExceeded(limit, found) =>
      FieldProblem.TargetingQuantifierDepthExceeded(NonNegativeInt.count(found), NonNegativeInt.count(limit))

  private def requireSingleFreeVariable(formula: Formula[FOL]): Either[FieldProblem, Unit] =
    FOLUtil.fvFOL(formula).distinct match
      case _ :: Nil => Right(())
      case Nil      => Left(FieldProblem.TargetingNoFreeVariable)
      case _        => Left(FieldProblem.TargetingSeveralFreeVariables)

  given Equal[TargetingPredicate] = Equal.default

  /** Wire format is the source string; decode == create (boundary validation). */
  given JsonCodec[TargetingPredicate] =
    JsonCodec[String].transformOrFail(
      s => create(s, ErrorSubject.Request).toEither.left.map(_.map(ValidationError.describe).mkString("; ")),
      _.source
    )

  given Schema[TargetingPredicate] = Schema.string[TargetingPredicate]
