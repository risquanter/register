package com.risquanter.register.http.requests

import zio.prelude.NonEmptyList
import zio.test.*

import com.risquanter.register.domain.data.iron.QueryVariable
import com.risquanter.register.http.errors.{Positioned, QueryPosition, QueryProblem}

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
      test("text the parser refuses is Unparseable") {
        val result = QueryRequest.resolve(QueryRequest("Q[>=]^{1/2 x (leaf(x), leaf(x))"))
        assertTrue(result match
          case Left(QueryProblem.Unparseable(_, _)) => true
          case _                                    => false)
      }
    )

end QueryRequestSpec
