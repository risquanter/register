package com.risquanter.register.domain.data

import zio.prelude.{Validation, Equal}
import zio.json.JsonCodec
import sttp.tapir.Schema
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}

import com.risquanter.register.domain.data.iron.TargetingSource
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

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

  private val field = "targetingPredicate.source"

  /** Mitigation-state predicates a targeting predicate may not reference — a
    * mitigation targeting by its own effect would be self-referential. These
    * are atoms (not function terms), so the targeting fragment does not exclude
    * them; this check does. Matched case-insensitively (see
    * `requireNoMitigationPredicate`); the M3 knowledge base is the eventual
    * authority (marks its own predicates non-targetable), superseding this list. */
  private val reservedPredicates: Set[String] = Set("mitigate", "mitigated", "unmitigated")

  /** Boundary validation, cross-compiled (browser and server), in two phases:
    *  - a gate that short-circuits, because each step needs the prior's output:
    *    1. length-refine into `TargetingSource` (1–256) — also bounds the input
    *       the parser sees; 2. parse via the foundation `FOLParser`;
    *  - once parsed, three independent checks on the formula, whose errors
    *    accumulate so an authoring form reports every problem at once:
    *    3. targeting-fragment membership (no quantifiers, no function terms);
    *    4. exactly one free variable (the target variable);
    *    5. no `mitigate` / `mitigated` predicate. */
  def create(source: String): Validation[ValidationError, TargetingPredicate] =
    val gate: Either[ValidationError, (TargetingSource, Formula[FOL])] =
      for
        refined <- source
          .refineEither[MinLength[1] & MaxLength[256]]
          .left.map(_ => err(ValidationErrorCode.INVALID_LENGTH,
            "Targeting predicate must be between 1 and 256 characters"))
        formula <- FOLParser.parse(refined)
          .left.map(pe => err(ValidationErrorCode.INVALID_FORMAT,
            s"Targeting predicate is not valid syntax: ${pe.message}"))
      yield (refined, formula)

    Validation.fromEither(gate).flatMap { case (refined, formula) =>
      Validation.validateWith(
        Validation.fromEither(checkFragment(formula)),
        Validation.fromEither(requireSingleFreeVariable(formula)),
        Validation.fromEither(requireNoMitigationPredicate(formula))
      )((_, _, _) => new TargetingPredicate(refined))
    }

  private def err(code: ValidationErrorCode, message: String): ValidationError =
    ValidationError(field = field, code = code, message = message)

  private def checkFragment(formula: Formula[FOL]): Either[ValidationError, Unit] =
    FragmentCheck.check(formula, Fragment.Targeting)
      .left.map(v => err(ValidationErrorCode.CONSTRAINT_VIOLATION, fragmentMessage(v)))

  private def fragmentMessage(v: FragmentViolation): String = v match
    case FragmentViolation.QuantifierNotAllowed =>
      "Targeting predicates may not use quantifiers"
    case FragmentViolation.FunctionApplicationNotAllowed(fn) =>
      s"Targeting predicates may not use function application: '$fn'"
    case FragmentViolation.QuantifierDepthExceeded(limit, found) =>
      s"Targeting predicate quantifier depth $found exceeds the limit of $limit"

  private def requireSingleFreeVariable(formula: Formula[FOL]): Either[ValidationError, Unit] =
    FOLUtil.fvFOL(formula).distinct match
      case _ :: Nil => Right(())
      case Nil      => Left(err(ValidationErrorCode.CONSTRAINT_VIOLATION,
        "Targeting predicate must reference exactly one variable; found none"))
      case vars     => Left(err(ValidationErrorCode.CONSTRAINT_VIOLATION,
        s"Targeting predicate must have exactly one free variable; found: ${vars.sorted.mkString(", ")}. " +
          "Unquoted words are read as variables — if one was meant as a literal value, quote it (e.g. \"cyber\")."))

  private def requireNoMitigationPredicate(formula: Formula[FOL]): Either[ValidationError, Unit] =
    val offending = atomPredicates(formula).filter(p => reservedPredicates.contains(p.toLowerCase))
    if offending.isEmpty then Right(())
    else Left(err(ValidationErrorCode.CONSTRAINT_VIOLATION,
      s"Targeting predicates may not reference mitigation state: ${offending.toList.sorted.mkString(", ")}"))

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
      s => create(s).toEither.left.map(errs => errs.map(e => s"[${e.field}] ${e.message}").mkString("; ")),
      _.source
    )

  given Schema[TargetingPredicate] = Schema.string[TargetingPredicate]
