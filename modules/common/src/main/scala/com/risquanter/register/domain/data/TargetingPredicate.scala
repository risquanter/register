package com.risquanter.register.domain.data

import zio.prelude.{Validation, Equal}
import zio.json.JsonCodec
import sttp.tapir.Schema
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}

import com.risquanter.register.domain.data.iron.{NonNegativeInt, TargetingSource}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, FieldProblem, ValidationError}

import parser.FOLParser
import logic.{Formula, FOL, FOLUtil}
import vql.fragment.{Fragment, FragmentCheck, FragmentViolation}

/** A mitigation's targeting predicate: a bare single-free-variable FOL formula
  * that resolves, server-side, to the set of nodes the mitigation scopes.
  *
  * Only the source text is stored and serialized; the parsed formula is derived
  * state. `create` is the sole constructor and the single validation boundary
  * (decode == create), so no `TargetingPredicate` value exists whose source is
  * not a well-formed member of the targeting fragment. It admits structural and
  * attribute predicates over one variable; it rejects quantifiers and function
  * application (the targeting fragment) and the mitigation-state predicates
  * `mitigate`/`mitigated`/`unmitigated`, case-insensitively (a mitigation must
  * not target by the state it changes).
  */
final case class TargetingPredicate private (source: TargetingSource)

object TargetingPredicate:

  private val location: ErrorLocation = ErrorLocation.requestField(FieldName.TargetingSource)

  /** The length bounds of `TargetingSource`, as the values a refusal carries.
    * They mirror the type's `MinLength` and `MaxLength` literals, which cannot
    * be read back at runtime. */
  private val MinSourceLength: NonNegativeInt = 1
  private val MaxSourceLength: NonNegativeInt = 256

  /** Mitigation-state predicates a targeting predicate may not reference — a
    * mitigation targeting by its own effect would be self-referential. These
    * are atoms (not function terms), so the targeting fragment does not exclude
    * them; this check does. Matched case-insensitively (see
    * `requireNoMitigationPredicate`). */
  private val reservedPredicates: Set[String] = Set("mitigate", "mitigated", "unmitigated")

  /** Boundary validation, cross-compiled (browser and server), in two phases:
    *  - a gate that short-circuits, because each step needs the prior's output:
    *    1. length-refine into `TargetingSource` (1–256) — also bounds the input
    *       the parser sees; 2. parse via the foundation `FOLParser`;
    *  - once parsed, three independent checks on the formula, whose errors
    *    accumulate so an authoring form reports every problem at once:
    *    3. targeting-fragment membership (no quantifiers, no function terms);
    *    4. exactly one free variable (the target variable);
    *    5. no `mitigate` / `mitigated` predicate.
    *
    * No refusal carries the parser's own text or the names it found: both are
    * the user's typed input coming back, which no refusal may carry (ADR-035).
    */
  def create(source: String): Validation[ValidationError, TargetingPredicate] =
    val gate: Either[ValidationError, (TargetingSource, Formula[FOL])] =
      for
        refined <- source
          .refineEither[MinLength[1] & MaxLength[256]]
          .left.map(_ => err(FieldProblem.LengthOutOfRange(MinSourceLength, MaxSourceLength)))
        formula <- FOLParser.parse(refined)
          .left.map(_ => err(FieldProblem.TargetingPredicateUnparseable))
      yield (refined, formula)

    Validation.fromEither(gate).flatMap { case (refined, formula) =>
      Validation.validateWith(
        Validation.fromEither(checkFragment(formula)),
        Validation.fromEither(requireSingleFreeVariable(formula)),
        Validation.fromEither(requireNoMitigationPredicate(formula))
      )((_, _, _) => new TargetingPredicate(refined))
    }

  private def err(problem: FieldProblem): ValidationError =
    ValidationError(location, problem)

  private def checkFragment(formula: Formula[FOL]): Either[ValidationError, Unit] =
    FragmentCheck.check(formula, Fragment.Targeting)
      .left.map(v => err(fragmentProblem(v)))

  private def fragmentProblem(v: FragmentViolation): FieldProblem = v match
    case FragmentViolation.QuantifierNotAllowed            => FieldProblem.TargetingQuantifierNotAllowed
    case FragmentViolation.FunctionApplicationNotAllowed(_) => FieldProblem.TargetingFunctionNotAllowed
    case FragmentViolation.QuantifierDepthExceeded(limit, found) =>
      FieldProblem.TargetingQuantifierDepthExceeded(NonNegativeInt.count(limit), NonNegativeInt.count(found))

  private def requireSingleFreeVariable(formula: Formula[FOL]): Either[ValidationError, Unit] =
    FOLUtil.fvFOL(formula).distinct match
      case _ :: Nil => Right(())
      case Nil      => Left(err(FieldProblem.TargetingNoFreeVariable))
      case _        => Left(err(FieldProblem.TargetingSeveralFreeVariables))

  private def requireNoMitigationPredicate(formula: Formula[FOL]): Either[ValidationError, Unit] =
    val offending = atomPredicates(formula).filter(p => reservedPredicates.contains(p.toLowerCase))
    if offending.isEmpty then Right(())
    else Left(err(FieldProblem.TargetingMitigationStateReferenced))

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

  given Equal[TargetingPredicate] = Equal.default

  /** Wire format is the source string; decode == create (boundary validation). */
  given JsonCodec[TargetingPredicate] =
    JsonCodec[String].transformOrFail(
      s => create(s).toEither.left.map(_.map(ValidationError.describe).mkString("; ")),
      _.source
    )

  given Schema[TargetingPredicate] = Schema.string[TargetingPredicate]
