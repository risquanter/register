package com.risquanter.register.domain.data

import zio.NonEmptyChunk
import zio.test.*
import zio.json.{DecoderOps, EncoderOps}
import com.risquanter.register.common.FolSymbols.QuerySort
import com.risquanter.register.domain.data.iron.QueryLiteral
import com.risquanter.register.http.errors.BindProblem
import com.risquanter.register.testutil.TestHelpers.nodeId

/** `ScopeOutcome` crosses the wire tagged by its case, and a failure that names
  * no problem cannot be decoded.
  */
object ScopeOutcomeSpec extends ZIOSpecDefault {

  private val problem: BindProblem =
    BindProblem.UnreadableLiteral(QueryLiteral.fromString("Gone").toOption.get, QuerySort.NodeNameLiteral)

  def spec = suite("ScopeOutcome codec")(
    test("a resolved scope round-trips") {
      val outcome: ScopeOutcome = ScopeOutcome.Resolved(Set(nodeId("n-one"), nodeId("n-two")))
      assertTrue(outcome.toJson.fromJson[ScopeOutcome] == Right(outcome))
    },

    test("an empty resolved scope round-trips as a different value from a failure") {
      val outcome: ScopeOutcome = ScopeOutcome.Resolved(Set.empty)
      assertTrue(outcome.toJson.fromJson[ScopeOutcome] == Right(outcome))
    },

    test("a failure round-trips with its problems") {
      val outcome: ScopeOutcome = ScopeOutcome.Failed(NonEmptyChunk(problem))
      assertTrue(outcome.toJson.fromJson[ScopeOutcome] == Right(outcome))
    },

    test("the case is named in the tag field") {
      val encoded = (ScopeOutcome.Resolved(Set.empty): ScopeOutcome).toJson
      assertTrue(encoded.contains(s""""${ScopeOutcome.TagField}":"Resolved""""))
    },

    test("a failure naming no problem is refused") {
      val decoded = s"""{"${ScopeOutcome.TagField}":"Failed","errors":[]}""".fromJson[ScopeOutcome]
      assertTrue(decoded.isLeft)
    }
  )
}
