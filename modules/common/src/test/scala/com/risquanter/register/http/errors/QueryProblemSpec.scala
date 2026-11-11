package com.risquanter.register.http.errors

import io.github.iltotore.iron.autoRefine
import zio.json.*
import zio.prelude.NonEmptyList
import zio.test.*

import com.risquanter.register.domain.data.iron.QueryVariable

/** The answer-variable refusal: its wire shape, the decoder's refusal of a
  * payload that names no variable, and its sentence. */
object QueryProblemSpec extends ZIOSpecDefault:

  private def variable(name: String): QueryVariable.QueryVariable =
    QueryVariable.fromString(name).toOption.get

  private val refusal: QueryProblem =
    QueryProblem.AnswerVariablesNotSupported(NonEmptyList(
      Positioned(QueryPosition.Unknown(), variable("y")),
      Positioned(QueryPosition.Unknown(), variable("z"))
    ))

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("QueryProblem")(
      suite("AnswerVariablesNotSupported on the wire")(
        test("round-trips through JSON") {
          assertTrue(refusal.toJson.fromJson[QueryProblem] == Right(refusal))
        },
        test("names each variable beside its position") {
          assertTrue(refusal.toJson ==
            """{"kind":"AnswerVariablesNotSupported","variables":[""" +
              """{"position":{"kind":"Unknown"},"problem":"y"},""" +
              """{"position":{"kind":"Unknown"},"problem":"z"}]}""")
        },
        test("a payload naming no variable does not decode") {
          val empty = """{"kind":"AnswerVariablesNotSupported","variables":[]}"""
          assertTrue(empty.fromJson[QueryProblem].isLeft)
        },
        test("a payload naming a variable outside the parser's rule does not decode") {
          val bad = """{"kind":"AnswerVariablesNotSupported","variables":[{"position":{"kind":"Unknown"},"problem":"<b>"}]}"""
          assertTrue(bad.fromJson[QueryProblem].isLeft)
        },
        test("a position at an offset round-trips, and a negative offset does not decode") {
          val at: QueryPosition = QueryPosition.At(3)
          assertTrue(
            at.toJson.fromJson[QueryPosition] == Right(at),
            """{"kind":"At","offset":-1}""".fromJson[QueryPosition].isLeft
          )
        }
      ),
      suite("describe")(
        test("lists the variables and gives no position clause for an unknown position") {
          assertTrue(QueryProblem.describe(refusal) ==
            "This service answers yes-or-no queries only and does not return answer variables yet: y, z")
        },
        test("ends a variable with the character it stands at, where that is known") {
          val placed = QueryProblem.AnswerVariablesNotSupported(NonEmptyList(Positioned(QueryPosition.At(31), variable("y"))))
          assertTrue(QueryProblem.describe(placed) ==
            "This service answers yes-or-no queries only and does not return answer variables yet: y (at character 31)")
        }
      )
    )

end QueryProblemSpec
