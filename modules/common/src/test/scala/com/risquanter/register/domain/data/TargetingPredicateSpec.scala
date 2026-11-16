package com.risquanter.register.domain.data

import io.github.iltotore.iron.autoRefine
import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import com.risquanter.register.domain.data.iron.{NodeId, SafeId, ValidationMessages}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, FieldProblem, SubmittedMitigations, ValidationErrorCode}
import com.risquanter.register.http.errors.{LexProblem, NestingProblem, ParseProblem, Positioned, QueryPosition, QueryToken}

/** Boundary validation for the targeting sublanguage: a bare single-free-variable
  * FOL formula, in the targeting fragment (no quantifiers, no function term
  * other than a node-returning function), with no mitigation-state predicate and
  * no node name. `create` is the validation boundary, so these rules also gate
  * every decode, and `ofNode` passes its text through it.
  */
object TargetingPredicateSpec extends ZIOSpecDefault:

  private def firstCode(source: String): Option[ValidationErrorCode] =
    TargetingPredicate.create(source, ErrorSubject.Request).toEither.swap.toOption.map(errs => FieldProblem.code(errs.head.problem))

  private def firstProblem(source: String): Option[FieldProblem] =
    TargetingPredicate.create(source, ErrorSubject.Request).toEither.swap.toOption.map(_.head.problem)

  private def problems(source: String): List[FieldProblem] =
    TargetingPredicate.create(source, ErrorSubject.Request).toEither.swap.toOption.toList.flatMap(_.map(_.problem).toList)

  /** A well-formed node identifier. */
  private val nodeId: String = "01HQ8ZK3V4N5P6Q7R8S9T0W1X2"

  /** The source length bound, from `TargetingSource`. */
  private val MaxSourceLength: Int = 4096

  def spec = suite("TargetingPredicateSpec")(
    suite("accepts")(
      test("a plain single-variable predicate") {
        assertTrue(TargetingPredicate.create("leaf(x)", ErrorSubject.Request).toEither.isRight)
      },
      test("a comparison against a literal") {
        assertTrue(TargetingPredicate.create("x < 5", ErrorSubject.Request).toEither.isRight)
      },
      test("a node named by its identifier") {
        assertTrue(TargetingPredicate.create(s"""descendant_of(x, node_id("$nodeId"))""", ErrorSubject.Request).toEither.isRight)
      },
      test("a source of exactly the length bound") {
        val source = "leaf(x)" + " " * (MaxSourceLength - "leaf(x)".length)
        assertTrue(source.length == MaxSourceLength, TargetingPredicate.create(source, ErrorSubject.Request).toEither.isRight)
      }
    ),
    suite("ofNode")(
      test("names exactly one node by its identifier, and create of its source gives it back") {
        val id        = NodeId(SafeId.fromString(nodeId).toOption.get)
        val predicate = TargetingPredicate.ofNode(id)
        assertTrue(
          (predicate.source: String) == s"""eq(x, node_id("$nodeId"))""",
          TargetingPredicate.create(predicate.source, ErrorSubject.Request).toEither == Right(predicate)
        )
      }
    ),
    suite("rejects")(
      test("a blank source (length)") {
        assertTrue(firstCode("") == Some(ValidationErrorCode.INVALID_LENGTH))
      },
      test("a source one character over the length bound (length)") {
        val source = "leaf(x)" + " " * (MaxSourceLength + 1 - "leaf(x)".length)
        assertTrue(firstCode(source) == Some(ValidationErrorCode.INVALID_LENGTH))
      },
      test("a value function, alone or inside a node function (targeting fragment)") {
        assertTrue(
          firstProblem("""p95(x, "inherent") < 5""") == Some(FieldProblem.TargetingFunctionNotAllowed),
          firstProblem("""eq(x, node_id(p95(x, "inherent")))""") == Some(FieldProblem.TargetingFunctionNotAllowed)
        )
      },
      test("a node named by its name") {
        assertTrue(firstProblem("""eq(x, node_named("Web"))""") == Some(FieldProblem.TargetingNodeNameReferenced))
      },
      test("a quantifier and a node name together report both") {
        val found = problems("""forall y . eq(x, node_named("Web"))""")
        assertTrue(
          found.contains(FieldProblem.TargetingQuantifierNotAllowed),
          found.contains(FieldProblem.TargetingNodeNameReferenced)
        )
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
        assertTrue(firstProblem("""eq(x, node_id("a<b"))""") match
          case Some(FieldProblem.TargetingPredicateUnparseable(Positioned(_, ParseProblem.Lexical(LexProblem.RefusedLiteralCharacter)))) => true
          case _ => false)
      },
      test("parentheses nested past the engine's depth limit are refused before parsing, at no position") {
        val source = "(" * 65 + "leaf(x)" + ")" * 65
        assertTrue(firstProblem(source) == Some(FieldProblem.TargetingPredicateUnparseable(
          Positioned(QueryPosition.Unknown(), ParseProblem.Nesting(NestingProblem.ParenthesesTooDeep(64))))))
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
        val problem = TargetingPredicate.create("category(x, fraud)", ErrorSubject.Request).toEither.swap.toOption.get.head.problem
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
        val errs     = TargetingPredicate.create("forall x . mitigated(y)", ErrorSubject.Request).toEither.swap.toOption.get
        val problems = errs.map(_.problem).toList
        assertTrue(
          errs.size >= 2,
          problems.contains(FieldProblem.TargetingQuantifierNotAllowed),
          problems.contains(FieldProblem.TargetingMitigationStateReferenced)
        )
      }
    ),
    test("every refusal is located at the given subject's TargetingSource") {
      val subject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.Mitigations, 1)
      val errs    = TargetingPredicate.create("forall x . mitigated(y)", subject).toEither.swap.toOption.get
      assertTrue(errs.forall(_.location == ErrorLocation.Field(subject, FieldName.TargetingSource)))
    },
    test("round-trips through its JSON codec") {
      val p = TargetingPredicate.create("leaf(x)", ErrorSubject.Request).toEither.toOption.get
      assertTrue(p.toJson.fromJson[TargetingPredicate] == Right(p))
    }
  )
