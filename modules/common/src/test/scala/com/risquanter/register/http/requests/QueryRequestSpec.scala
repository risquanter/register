package com.risquanter.register.http.requests

import zio.prelude.NonEmptyList
import zio.test.*

import com.risquanter.register.domain.data.iron.QueryVariable
import com.risquanter.register.common.FolSymbols.QueryGrammarSymbol
import com.risquanter.register.http.errors.{LexProblem, ParseProblem, Positioned, QueryFound, QueryPosition, QueryProblem, QueryToken}

/** `QueryRequest.resolve`, the boundary that parses query text and refuses a
  * query the service cannot answer. */
object QueryRequestSpec extends ZIOSpecDefault:

  private val booleanQuery = """Q[>=]^{1/2} x (leaf(x), gt_loss(p95(x, "inherent"), 1000))"""

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("QueryRequest.resolve")(
      test("a yes-or-no query parses") {
        assertTrue(QueryRequest.resolve(QueryRequest(booleanQuery)).isRight)
      },
      test("a query naming answer variables is refused with every variable, each at an unknown position") {
        val result = QueryRequest.resolve(QueryRequest("Q[>=]^{1/2} x (child_of(x, y), leaf(x)) (y)"))
        val y      = QueryVariable.fromString("y").toOption.get
        assertTrue(result == Left(QueryProblem.AnswerVariablesNotSupported(
          NonEmptyList(Positioned(QueryPosition.Unknown(), y)))))
      },
      test("text the parser refuses is Unparseable, carrying the token the parser found") {
        val result = QueryRequest.resolve(QueryRequest("Q[>=]^{1/2 x (leaf(x), leaf(x))"))
        assertTrue(result match
          case Left(QueryProblem.Unparseable(Positioned(_, ParseProblem.SymbolExpected(QueryGrammarSymbol.RBrace, _, QueryFound.Token(QueryToken.Word(word)))))) =>
            word.value == "x"
          case _ => false)
      },
      test("a literal holding a character outside its alphabet is a lexical refusal") {
        val result = QueryRequest.resolve(QueryRequest("""Q[>=]^{1/2} x (leaf(x), named_risk(x, "<b>"))"""))
        assertTrue(result match
          case Left(QueryProblem.Unparseable(Positioned(_, ParseProblem.Lexical(LexProblem.RefusedLiteralCharacter)))) => true
          case _ => false)
      }
    )

end QueryRequestSpec
