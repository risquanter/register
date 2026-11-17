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
import logic.{Formula, FOL, FOLUtil, Term}
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
  * than a node-returning function (the targeting fragment), a node named by
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

  /** Mitigation-state predicates a targeting predicate may not reference — a
    * mitigation targeting by its own effect would be self-referential. These
    * are atoms (not function terms), so the targeting fragment does not exclude
    * them; this check does. Matched case-insensitively (see
    * `requireNoMitigationPredicate`). */
  private val reservedPredicates: Set[String] =
    Set(QueryPredicate.Mitigate, QueryPredicate.Mitigated, QueryPredicate.Unmitigated).map(_.symbol)

  /** Boundary validation, cross-compiled (browser and server), in two phases:
    *  - a gate that short-circuits, because each step needs the prior's output:
    *    1. length-refine into `TargetingSource` (1–2048) — also bounds the
    *       input the parser sees; 2. parse via the foundation `FOLParser`;
    *  - once parsed, four independent checks on the formula, whose errors
    *    accumulate so an authoring form reports every problem at once:
    *    3. targeting-fragment membership (no quantifiers, no function term
    *       other than a node-returning function);
    *    4. exactly one free variable (the target variable);
    *    5. no `mitigate` / `mitigated` predicate;
    *    6. no `node_named`, so a rename cannot move the scope onto another node.
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
        checked(checkFragment(formula)),
        checked(requireSingleFreeVariable(formula)),
        checked(requireNoMitigationPredicate(formula)),
        checked(requireNoNodeName(formula))
      )((_, _, _, _) => new TargetingPredicate(refined))
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
    FOLParser.parse(predicate.source).fold(_ => Nil, namedNodeLiterals)
      .flatMap(literal => NodeId.fromString(literal).toOption)

  /** The literal of every `node_id("…")` application in the formula, left to
    * right. */
  private def namedNodeLiterals(formula: Formula[FOL]): List[String] = formula match
    case Formula.Atom(FOL(_, terms))  => terms.flatMap(termNodeLiterals)
    case Formula.Not(p)               => namedNodeLiterals(p)
    case Formula.And(p, q)            => namedNodeLiterals(p) ++ namedNodeLiterals(q)
    case Formula.Or(p, q)             => namedNodeLiterals(p) ++ namedNodeLiterals(q)
    case Formula.Imp(p, q)            => namedNodeLiterals(p) ++ namedNodeLiterals(q)
    case Formula.Iff(p, q)            => namedNodeLiterals(p) ++ namedNodeLiterals(q)
    case Formula.Forall(_, p)         => namedNodeLiterals(p)
    case Formula.Exists(_, p)         => namedNodeLiterals(p)
    case Formula.True | Formula.False => Nil

  private def termNodeLiterals(term: Term): List[String] = term match
    case Term.Fn(NodeFunction.ById.symbol, Term.Const(literal) :: Nil) => List(literal)
    case Term.Fn(_, args)                                              => args.flatMap(termNodeLiterals)
    case Term.Var(_) | Term.Const(_)                                   => Nil

  private def parseProblem(error: ParseError): Positioned[ParseProblem] =
    ParseProblem.fromFormulaError(error).fold(
      reason => throw new IllegalStateException(s"The formula parser returned a refusal that does not translate: $reason"),
      identity)

  private def checkFragment(formula: Formula[FOL]): Either[FieldProblem, Unit] =
    FragmentCheck.check(formula, Fragment.Targeting(Signatures.targetingFunctions))
      .left.map(fragmentProblem)

  private def fragmentProblem(v: FragmentViolation): FieldProblem = v match
    case FragmentViolation.QuantifierNotAllowed            => FieldProblem.TargetingQuantifierNotAllowed
    case FragmentViolation.FunctionApplicationNotAllowed(_) => FieldProblem.TargetingFunctionNotAllowed
    case FragmentViolation.QuantifierDepthExceeded(limit, found) =>
      FieldProblem.TargetingQuantifierDepthExceeded(NonNegativeInt.count(limit), NonNegativeInt.count(found))

  private def requireSingleFreeVariable(formula: Formula[FOL]): Either[FieldProblem, Unit] =
    FOLUtil.fvFOL(formula).distinct match
      case _ :: Nil => Right(())
      case Nil      => Left(FieldProblem.TargetingNoFreeVariable)
      case _        => Left(FieldProblem.TargetingSeveralFreeVariables)

  private def requireNoMitigationPredicate(formula: Formula[FOL]): Either[FieldProblem, Unit] =
    val offending = atomPredicates(formula).filter(p => reservedPredicates.contains(p.toLowerCase))
    if offending.isEmpty then Right(())
    else Left(FieldProblem.TargetingMitigationStateReferenced)

  /** A stored predicate names a node by its identifier only, so a name cannot
    * follow a rename onto a different node. */
  private def requireNoNodeName(formula: Formula[FOL]): Either[FieldProblem, Unit] =
    if appliedFunctions(formula).contains(NodeFunction.ByName.symbol) then
      Left(FieldProblem.TargetingNodeNameReferenced)
    else Right(())

  /** The predicate names of every atom in the formula. Total over the tree;
    * quantifier nodes never appear in a targeting-fragment formula but are
    * walked anyway so the function stands alone. */
  private def atomPredicates(formula: Formula[FOL]): Set[String] = formula match
    case Formula.Atom(FOL(predicate, _)) => Set(predicate)
    case Formula.Not(p)                  => atomPredicates(p)
    case Formula.And(p, q)               => atomPredicates(p) ++ atomPredicates(q)
    case Formula.Or(p, q)                => atomPredicates(p) ++ atomPredicates(q)
    case Formula.Imp(p, q)               => atomPredicates(p) ++ atomPredicates(q)
    case Formula.Iff(p, q)               => atomPredicates(p) ++ atomPredicates(q)
    case Formula.Forall(_, p)            => atomPredicates(p)
    case Formula.Exists(_, p)            => atomPredicates(p)
    case Formula.True | Formula.False    => Set.empty

  /** The function names applied anywhere in the formula's terms, nested
    * applications included. */
  private def appliedFunctions(formula: Formula[FOL]): Set[String] = formula match
    case Formula.Atom(FOL(_, terms))  => terms.flatMap(termFunctions).toSet
    case Formula.Not(p)               => appliedFunctions(p)
    case Formula.And(p, q)            => appliedFunctions(p) ++ appliedFunctions(q)
    case Formula.Or(p, q)             => appliedFunctions(p) ++ appliedFunctions(q)
    case Formula.Imp(p, q)            => appliedFunctions(p) ++ appliedFunctions(q)
    case Formula.Iff(p, q)            => appliedFunctions(p) ++ appliedFunctions(q)
    case Formula.Forall(_, p)         => appliedFunctions(p)
    case Formula.Exists(_, p)         => appliedFunctions(p)
    case Formula.True | Formula.False => Set.empty

  private def termFunctions(term: Term): Set[String] = term match
    case Term.Fn(name, args)      => args.flatMap(termFunctions).toSet + name
    case Term.Var(_) | Term.Const(_) => Set.empty

  given Equal[TargetingPredicate] = Equal.default

  /** Wire format is the source string; decode == create (boundary validation). */
  given JsonCodec[TargetingPredicate] =
    JsonCodec[String].transformOrFail(
      s => create(s, ErrorSubject.Request).toEither.left.map(_.map(ValidationError.describe).mkString("; ")),
      _.source
    )

  given Schema[TargetingPredicate] = Schema.string[TargetingPredicate]
