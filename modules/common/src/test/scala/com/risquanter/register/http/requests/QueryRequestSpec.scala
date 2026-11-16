package com.risquanter.register.http.requests

import io.github.iltotore.iron.autoRefine
import zio.json.DecoderOps
import zio.prelude.NonEmptyList
import zio.test.*

import com.risquanter.register.domain.data.iron.{QueryText, QueryVariable}
import com.risquanter.register.common.FolSymbols.QueryGrammarSymbol
import com.risquanter.register.http.errors.{
  LexProblem, NestingProblem, ParseProblem, Positioned, QueryFound, QueryPosition, QueryProblem, QueryToken
}

/** The query request's decoder, which bounds the text's length, and
  * `QueryRequest.resolve`, the boundary that parses query text and refuses a
  * query the service cannot answer. */
object QueryRequestSpec extends ZIOSpecDefault:

  private val booleanQuery = """Q[>=]^{1/2} x (leaf(x), gt_loss(p95(x, "inherent"), 1000))"""

  /** The request for a text within the length bound. */
  private def request(text: String): QueryRequest =
    QueryRequest(QueryText.fromString(text).fold(reason => throw new IllegalArgumentException(reason), identity))

  /** A request body whose query is `text`. */
  private def body(text: String): String = s"""{"query":"$text"}"""

  override def spec: Spec[TestEnvironment & zio.Scope, Any] = suite("QueryRequest")(
    suite("decoder")(
      test("a query of exactly the length bound decodes") {
        val text = "x" * QueryText.MaxLength
        assertTrue(body(text).fromJson[QueryRequest].map(_.query.value) == Right(text))
      },
      test("a query one character over the length bound is refused") {
        assertTrue(body("x" * (QueryText.MaxLength + 1)).fromJson[QueryRequest].isLeft)
      },
      test("a query of 50 000 commas is refused by the decoder, before the parser") {
        assertTrue(body("," * 50000).fromJson[QueryRequest].isLeft)
      }
    ),
    suite("QueryRequest.resolve")(
      test("a yes-or-no query parses") {
        assertTrue(QueryRequest.resolve(request(booleanQuery)).isRight)
      },
      test("a query naming answer variables is refused with every variable, each at an unknown position") {
        val result = QueryRequest.resolve(request("Q[>=]^{1/2} x (child_of(x, y), leaf(x)) (y)"))
        val y      = QueryVariable.fromString("y").toOption.get
        assertTrue(result == Left(QueryProblem.AnswerVariablesNotSupported(
          NonEmptyList(Positioned(QueryPosition.Unknown(), y)))))
      },
      test("text the parser refuses is Unparseable, carrying the token the parser found") {
        val result = QueryRequest.resolve(request("Q[>=]^{1/2 x (leaf(x), leaf(x))"))
        assertTrue(result match
          case Left(QueryProblem.Unparseable(Positioned(_, ParseProblem.SymbolExpected(QueryGrammarSymbol.RBrace, _, QueryFound.Token(QueryToken.Word(word)))))) =>
            word.value == "x"
          case _ => false)
      },
      test("a literal holding a character outside its alphabet is a lexical refusal") {
        val result = QueryRequest.resolve(request("""Q[>=]^{1/2} x (leaf(x), eq(x, node_named("<b>")))"""))
        assertTrue(result match
          case Left(QueryProblem.Unparseable(Positioned(_, ParseProblem.Lexical(LexProblem.RefusedLiteralCharacter)))) => true
          case _ => false)
      },
      test("a scope of 300 conjoined atoms passes the engine's operator limit and is refused before parsing") {
        val scope  = List.fill(300)("p(x)").mkString("/\\")
        val result = QueryRequest.resolve(request(s"Q[>=]^{1/2} x (leaf(x), $scope)"))
        assertTrue(result == Left(QueryProblem.Unparseable(
          Positioned(QueryPosition.Unknown(), ParseProblem.Nesting(NestingProblem.TooManyOperators(256))))))
      }
    )
  )

end QueryRequestSpec
