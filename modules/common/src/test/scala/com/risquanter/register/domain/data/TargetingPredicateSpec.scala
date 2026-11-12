package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{FieldProblem, ValidationErrorCode}
import com.risquanter.register.http.errors.{LexProblem, ParseProblem, Positioned, QueryPosition, QueryToken}

/** Boundary validation for the targeting sublanguage: a bare single-free-variable
  * FOL formula, in the targeting fragment (no quantifiers, no function terms),
  * with no mitigation-state predicate. `create` is the only constructor, so these
  * rules also gate every decode.
  */
object TargetingPredicateSpec extends ZIOSpecDefault:

  private def firstCode(source: String): Option[ValidationErrorCode] =
    TargetingPredicate.create(source).toEither.swap.toOption.map(errs => FieldProblem.code(errs.head.problem))

  private def firstProblem(source: String): Option[FieldProblem] =
    TargetingPredicate.create(source).toEither.swap.toOption.map(_.head.problem)

  def spec = suite("TargetingPredicateSpec")(
    suite("accepts")(
      test("a plain single-variable predicate") {
        assertTrue(TargetingPredicate.create("leaf(x)").toEither.isRight)
      },
      test("a comparison against a literal") {
        assertTrue(TargetingPredicate.create("x < 5").toEither.isRight)
      }
    ),
    suite("rejects")(
      test("a blank source (length)") {
        assertTrue(firstCode("") == Some(ValidationErrorCode.INVALID_LENGTH))
      },
      test("a source over 256 characters (length)") {
        assertTrue(firstCode("a" * 300) == Some(ValidationErrorCode.INVALID_LENGTH))
      },
      test("malformed syntax (parse)") {
        assertTrue(firstCode("leaf(x") == Some(ValidationErrorCode.INVALID_FORMAT))
      },
      test("malformed syntax carries the formula parser's typed refusal, at the unknown position the parser reports it with") {
        assertTrue(firstProblem("leaf(x") match
          case Some(FieldProblem.TargetingPredicateUnparseable(Positioned(QueryPosition.Unknown(), ParseProblem.Formula(_)))) => true
          case _ => false)
      },
      test("text after a whole formula is a trailing-tokens refusal naming the first token") {
        assertTrue(firstProblem("leaf(x) leaf(y)") match
          case Some(FieldProblem.TargetingPredicateUnparseable(Positioned(_, ParseProblem.TrailingTokens(QueryToken.Word(word), _)))) =>
            word.value == "leaf"
          case _ => false)
      },
      test("a literal holding a character outside its alphabet is a lexical refusal, with no text") {
        assertTrue(firstProblem("""named_risk(x, "a<b")""") match
          case Some(FieldProblem.TargetingPredicateUnparseable(Positioned(_, ParseProblem.Lexical(LexProblem.RefusedLiteralCharacter)))) => true
          case _ => false)
      },
      test("a quantifier (targeting fragment)") {
        assertTrue(firstCode("forall x . leaf(x)") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION))
      },
      test("a function-application term (targeting fragment)") {
        assertTrue(firstCode("p95(x, inherent) < 5") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION))
      },
      test("more than one free variable") {
        assertTrue(firstCode("child_of(x, y)") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION))
      },
      test("an unquoted attribute value is reported with a quoting hint, and the typed words are not echoed") {
        // `fraud` parses as a second variable; the sentence must point at
        // quoting without repeating what was typed (ADR-035).
        val problem = TargetingPredicate.create("category(x, fraud)").toEither.swap.toOption.get.head.problem
        val msg     = ValidationMessages.render(problem)
        assertTrue(
          problem == FieldProblem.TargetingSeveralFreeVariables,
          msg.contains("quote"),
          !msg.contains("fraud")
        )
      },
      test("the mitigate / mitigated / unmitigated predicates, case-insensitively") {
        assertTrue(
          firstCode("mitigated(x)") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION),
          firstCode("mitigate(x)") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION),
          firstCode("unmitigated(x)") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION),
          firstCode("Mitigated(x)") == Some(ValidationErrorCode.CONSTRAINT_VIOLATION)
        )
      },
      test("accumulates every independent formula-level violation at once") {
        // A quantifier (fragment) and a reserved predicate (mitigation state)
        // are independent checks on the parsed formula; both must be reported.
        val errs     = TargetingPredicate.create("forall x . mitigated(y)").toEither.swap.toOption.get
        val problems = errs.map(_.problem).toList
        assertTrue(
          errs.size >= 2,
          problems.contains(FieldProblem.TargetingQuantifierNotAllowed),
          problems.contains(FieldProblem.TargetingMitigationStateReferenced)
        )
      }
    ),
    test("round-trips through its JSON codec") {
      val p = TargetingPredicate.create("leaf(x)").toEither.toOption.get
      assertTrue(p.toJson.fromJson[TargetingPredicate] == Right(p))
    }
  )
