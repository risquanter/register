package com.risquanter.register.domain.errors

import io.github.iltotore.iron.autoRefine
import zio.{Exit, NonEmptyChunk}
import zio.prelude.NonEmptyList
import zio.test.*

import lexer.{Found, GrammarSymbol, LiteralText, SymbolText, TokenKind, VariableText, WordText}
import parser.SourcePosition
import vql.error.{ParseContext as EngineParseContext, ParseRefusal, QueryError as QE, RuntimeModelError, SymbolKind as EngineSymbolKind, TypeCheckError}
import vql.id.{SymbolName, TypeId}

import com.risquanter.register.common.FolSymbols.{QueryGrammarSymbol, QuerySort}
import com.risquanter.register.domain.data.iron.{QueryLiteral, QueryWord}
import com.risquanter.register.http.errors.{
  BindProblem, ParseContext, ParseProblem, Positioned, QueryFound, QueryPosition, QueryProblem, QuerySymbolName,
  QueryToken, QueryVariableName
}
import com.risquanter.register.services.QueryService

/** Tests for `QueryProblem.fromQueryError`, which reads the engine's
  * `QueryError` as the refusal a caller can act on or as the reason it is a
  * defect, and for `QueryService.refused`, which fails with that refusal or
  * dies with that reason.
  */
object QueryProblemFromQueryErrorSpec extends ZIOSpecDefault:

  private def word(s: String): QueryWord.QueryWord          = QueryWord.fromString(s).toOption.get
  private def literal(s: String): QueryLiteral.QueryLiteral = QueryLiteral.fromString(s).toOption.get

  /** `Q[>=]^{2/3 x`: a word where the parser needed `}`. */
  private val braceExpected: ParseRefusal =
    ParseRefusal.SymbolExpected(GrammarSymbol.RBrace, EngineParseContext.ToleranceOrEnd, Found.Token(TokenKind.Word(WordText("x"))))

  private val braceExpectedProblem: ParseProblem =
    ParseProblem.SymbolExpected(QueryGrammarSymbol.RBrace, ParseContext.ToleranceOrEnd, QueryFound.Token(QueryToken.Word(word("x"))))

  /** A bind-phase literal the given sort cannot read. */
  private def unreadable(name: String, sort: QuerySort): TypeCheckError =
    TypeCheckError.UnparseableConstant(LiteralText(name), TypeId(sort.symbol), LiteralText(name))

  private def unpositioned[P](problem: P): Positioned[P] = Positioned(QueryPosition.Unknown(), problem)

  /** The code the refusal reaches the wire with. */
  private def code(problem: QueryProblem): ValidationErrorCode = FieldProblem.code(FieldProblem.Query(problem))

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("QueryProblem.fromQueryError")(
      parseSuite,
      bindSuite,
      defectSuite,
      refusedSuite
    )

  // ── Errors in the query's own text → Unparseable ────────────────────

  private val parseSuite = suite("→ Unparseable")(
    test("a parse refusal keeps its typed fields and its offset") {
      assertTrue(QueryProblem.fromQueryError(QE.ParseError(braceExpected, SourcePosition.At(10))) ==
        Right(QueryProblem.Unparseable(Positioned(QueryPosition.At(10), braceExpectedProblem))))
    },
    test("a parse refusal at no known position") {
      assertTrue(QueryProblem.fromQueryError(QE.ParseError(braceExpected, SourcePosition.Unknown)) ==
        Right(QueryProblem.Unparseable(Positioned(QueryPosition.Unknown(), braceExpectedProblem))))
    },
    test("a range formula's broken variable rule is the caller's text refused") {
      val err = QE.ValidationError(
        QE.ValidationProblem.QuantifiedVariableNotInRange(VariableText.of("x"), Set(VariableText.of("y"))),
        SourcePosition.Unknown
      )
      assertTrue(QueryProblem.fromQueryError(err) == Right(QueryProblem.Unparseable(unpositioned(
        ParseProblem.QuantifiedVariableNotInRange(QueryVariableName.Word(word("x")), Set(QueryVariableName.Word(word("y"))))
      ))))
    },
    test("an unparseable query reaches the wire as PARSE_ERROR") {
      assertTrue(code(QueryProblem.Unparseable(unpositioned(braceExpectedProblem))) == ValidationErrorCode.PARSE_ERROR)
    }
  )

  // ── Bind errors → Unbound, coded by what they name ──────────────────

  private val bindSuite = suite("→ Unbound")(
    test("every bind error is kept, in order") {
      val err = QE.BindError(List(unreadable("Foo", QuerySort.Node), unreadable("Bar", QuerySort.NodeNameLiteral)))
      assertTrue(QueryProblem.fromQueryError(err) == Right(QueryProblem.Unbound(NonEmptyList(
        unpositioned(BindProblem.UnreadableLiteral(literal("Foo"), QuerySort.Node)),
        unpositioned(BindProblem.UnreadableLiteral(literal("Bar"), QuerySort.NodeNameLiteral))
      ))))
    },
    test("a refusal whose every problem names an absent node is UNKNOWN_REFERENCE") {
      // A node slot and named_risk(x, "…") fail on the same missing node.
      val err = QE.BindError(List(unreadable("Foo", QuerySort.Node), unreadable("Bar", QuerySort.NodeNameLiteral)))
      assertTrue(QueryProblem.fromQueryError(err).map(code) == Right(ValidationErrorCode.UNKNOWN_REFERENCE))
    },
    test("an absent node beside an arity mismatch is BIND_FAILED") {
      val err = QE.BindError(List(
        unreadable("Foo", QuerySort.Node),
        TypeCheckError.ArityMismatch(SymbolText.of("leaf"), EngineSymbolKind.Predicate, 1, 2)
      ))
      assertTrue(QueryProblem.fromQueryError(err).map(code) == Right(ValidationErrorCode.BIND_FAILED))
    },
    test("a malformed id literal is BIND_FAILED, because the node-id sort reads a format rather than a name") {
      val err = QE.BindError(List(unreadable("not-an-id", QuerySort.NodeIdLiteral)))
      assertTrue(QueryProblem.fromQueryError(err).map(code) == Right(ValidationErrorCode.BIND_FAILED))
    },
    test("a literal no loss reads is BIND_FAILED") {
      val err = QE.BindError(List(unreadable("notaloss", QuerySort.Loss)))
      assertTrue(QueryProblem.fromQueryError(err).map(code) == Right(ValidationErrorCode.BIND_FAILED))
    },
    test("an unknown predicate keeps its name") {
      val err = QE.BindError(List(TypeCheckError.UnknownPredicate(SymbolText.of("lef"))))
      assertTrue(QueryProblem.fromQueryError(err) ==
        Right(QueryProblem.Unbound(NonEmptyList(unpositioned(BindProblem.UnknownPredicate(QuerySymbolName.Word(word("lef"))))))))
    },
    test("an unknown constant or literal is an unknown constant, UNKNOWN_REFERENCE") {
      // Reachable through a quoted literal in the mitigation slot, such as
      // p95(x, "foo"), because no literal validator exists for that sort.
      val result = QueryProblem.fromQueryError(QE.UnknownConstantOrLiteralError(LiteralText("foo")))
      assertTrue(
        result == Right(QueryProblem.Unbound(NonEmptyList(unpositioned(BindProblem.UnknownConstant(literal("foo")))))),
        result.map(code) == Right(ValidationErrorCode.UNKNOWN_REFERENCE)
      )
    }
  )

  // ── Faults in how this service wires the engine → Left ──────────────

  private val defectSuite = suite("→ Left")(
    test("a bind error with no detail") {
      assertTrue(QueryProblem.fromQueryError(QE.BindError(Nil)) == Left("BindError carries no detail"))
    },
    test("a bind error this service never lets the binder reach") {
      assertTrue(QueryProblem.fromQueryError(QE.BindError(List(TypeCheckError.UnboundAnswerVar(VariableText.of("y"))))).isLeft)
    },
    test("an answer-tuple check, which this service never asks for") {
      val err = QE.ValidationError(QE.ValidationProblem.MissingAnswerValue(VariableText.of("y")), SourcePosition.Unknown)
      assertTrue(QueryProblem.fromQueryError(err).isLeft)
    },
    test("a negative offset") {
      assertTrue(QueryProblem.fromQueryError(QE.ParseError(braceExpected, SourcePosition.At(-1))).isLeft)
    },
    test("a model that does not satisfy its catalog") {
      val err = QE.ModelValidationError(List(RuntimeModelError.MissingFunctionImplementation(SymbolName("lec"))))
      assertTrue(QueryProblem.fromQueryError(err) == Left("ModelValidationError after the model check"))
    },
    test("a type with no domain") {
      val err = QE.DomainNotFoundError(TypeId(QuerySort.Loss.symbol), Set(TypeId(QuerySort.Node.symbol)))
      assertTrue(QueryProblem.fromQueryError(err) == Left("DomainNotFoundError after the model check"))
    },
    test("an evaluation failure names its case and not the dispatcher's text") {
      val err = QE.EvaluationError(QE.EvaluationFailure.PredicateRefused(SymbolName("leaf"), "Division by zero"))
      assertTrue(QueryProblem.fromQueryError(err) == Left("EvaluationError: PredicateRefused"))
    },
    test("an unbound variable names the variable and none of the bound names") {
      val err = QE.UnboundVariableError(VariableText.of("x"), Set("<caller text>"))
      assertTrue(QueryProblem.fromQueryError(err) == Left("UnboundVariableError: x"))
    }
  )

  // ── QueryService.refused ────────────────────────────────────────────

  /** The defect `refused` dies with, when it dies with one. */
  private def diesWithIllegalState(err: QE) =
    QueryService.refused(err).exit.map(exit =>
      assertTrue(exit.causeOption.flatMap(_.dieOption) match
        case Some(_: IllegalStateException) => true
        case _                              => false))

  private val refusedSuite = suite("QueryService.refused")(
    test("a parse failure is Rejected at Client at the whole request") {
      QueryService.refused(QE.ParseError(braceExpected, SourcePosition.At(14))).exit.map(exit =>
        assertTrue(exit == Exit.fail(Failure.rejected(
          TrustZone.Client,
          NonEmptyChunk(ValidationError(
            ErrorLocation.Whole,
            FieldProblem.Query(QueryProblem.Unparseable(Positioned(QueryPosition.At(14), braceExpectedProblem)))
          )),
          Nil
        ))))
    },
    test("a model that does not satisfy its catalog is a defect") {
      diesWithIllegalState(QE.ModelValidationError(List(RuntimeModelError.MissingFunctionImplementation(SymbolName("lec")))))
    },
    test("an evaluation failure is a defect") {
      diesWithIllegalState(QE.EvaluationError(QE.EvaluationFailure.PredicateRefused(SymbolName("leaf"), "Division by zero")))
    },
    test("an unbound variable is a defect") {
      diesWithIllegalState(QE.UnboundVariableError(VariableText.of("x"), Set("y", "z")))
    }
  )

end QueryProblemFromQueryErrorSpec
