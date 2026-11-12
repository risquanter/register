package com.risquanter.register.http.errors

import io.github.iltotore.iron.autoRefine
import zio.json.*
import zio.prelude.NonEmptyList
import zio.test.*

import com.risquanter.register.common.FolSymbols.QuerySort
import com.risquanter.register.domain.data.iron.{QueryLiteral, QueryVariable, QueryWord}

/** The query refusal's wire shape, the decoder's refusal of a payload that
  * names nothing, and its sentences. */
object QueryProblemSpec extends ZIOSpecDefault:

  private def variable(name: String): QueryVariable.QueryVariable =
    QueryVariable.fromString(name).toOption.get

  private val refusal: QueryProblem =
    QueryProblem.AnswerVariablesNotSupported(NonEmptyList(
      Positioned(QueryPosition.Unknown(), variable("y")),
      Positioned(QueryPosition.Unknown(), variable("z"))
    ))

  private val unbound: QueryProblem =
    QueryProblem.Unbound(NonEmptyList(
      Positioned(QueryPosition.Unknown(), BindProblem.UnreadableLiteral(QueryLiteral.fromString("Cyber").toOption.get, QuerySort.NodeNameLiteral)),
      Positioned(QueryPosition.Unknown(), BindProblem.UnknownPredicate(QuerySymbolName.Word(QueryWord.fromString("lef").toOption.get)))
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
      suite("Unbound on the wire")(
        test("round-trips through JSON") {
          assertTrue(unbound.toJson.fromJson[QueryProblem] == Right(unbound))
        },
        test("a payload naming no bind problem does not decode") {
          assertTrue("""{"kind":"Unbound","problems":[]}""".fromJson[QueryProblem].isLeft)
        }
      ),
      suite("describe")(
        test("joins each bind problem's sentence") {
          assertTrue(QueryProblem.describe(unbound) ==
            "No node is named \"Cyber\" in this tree; The query uses 'lef' as a predicate, and no predicate has that name")
        },
        test("an unparseable query is its parse problem's sentence") {
          val problem = QueryProblem.Unparseable(Positioned(QueryPosition.At(9), ParseProblem.Lexical(LexProblem.UnterminatedLiteral)))
          assertTrue(QueryProblem.describe(problem) == "A quoted literal is not closed with '\"' (at character 9)")
        },
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
