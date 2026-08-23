package com.risquanter.register.foladapter

import zio.test.*

import com.risquanter.register.testutil.TestHelpers

import vql.result.{EvaluationOutput, VagueQueryResult}
import vql.typed.{Value, TypeId}
import vql.quantifier.AtLeast
import vql.sampling.{SamplingParams, ProportionEstimate}

/** Tests for [[QueryResponseBuilder]] — maps fol-engine `EvaluationOutput[Value]`
  * to register's `QueryResponse`.
  *
  * Covers:
  *   - Node-value projection via `extract[NodeId]`
  *   - Non-node sorts are filtered out (Loss, Probability)
  *   - A node-sorted value whose carrier is not a `NodeId` is filtered out
  *   - All scalar fields pass through correctly
  *   - Empty evaluation output
  */
object QueryResponseBuilderSpec extends ZIOSpecDefault with TestHelpers:

  private val nodeSort = RiskTreeKnowledgeBase.NodeSort
  private val lossSort = TypeId("Loss")

  private val cyberId    = nodeId("cyber")
  private val hardwareId = nodeId("hardware")
  private val aId        = nodeId("a")
  private val bId        = nodeId("b")

  private def nodeVal(id: com.risquanter.register.domain.data.iron.NodeId): Value =
    Value(nodeSort, id)

  private def makeEstimate(proportion: Double, successes: Int, sampleSize: Int): ProportionEstimate =
    ProportionEstimate(
      proportion = proportion,
      sampleSize = sampleSize,
      successes  = successes,
      confidenceInterval = (proportion, proportion), // exact mode
      marginOfError = 0.0,
      params = SamplingParams.exact
    )

  private def makeResult(
    satisfied: Boolean,
    proportion: Double,
    rangeSize: Int,
    satisfyingCount: Int
  ): VagueQueryResult =
    VagueQueryResult(
      satisfied          = satisfied,
      proportion         = proportion,
      confidenceInterval = (proportion, proportion),
      quantifier         = AtLeast(0.5),
      domainSize         = rangeSize,
      sampleSize         = rangeSize,
      satisfyingCount    = satisfyingCount,
      estimate           = makeEstimate(proportion, satisfyingCount, rangeSize)
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("QueryResponseBuilder")(
      test("maps satisfying Node values to NodeIds") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 1.0, rangeSize = 2, satisfyingCount = 2),
          rangeElements = Set(nodeVal(cyberId), nodeVal(hardwareId)),
          satisfyingElements = Set(nodeVal(cyberId), nodeVal(hardwareId))
        )
        val response = QueryResponseBuilder.from(output, "test query")
        assertTrue(
          response.satisfied == true,
          response.proportion == 1.0,
          response.rangeSize == 2,
          response.satisfyingCount == 2,
          response.satisfyingNodeIds.toSet == Set(cyberId, hardwareId),
          response.queryEcho == "test query"
        )
      },
      test("filters out non-node sorts from satisfying elements") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 1.0, rangeSize = 3, satisfyingCount = 3),
          rangeElements = Set(nodeVal(cyberId), nodeVal(hardwareId), Value(lossSort, 5000L)),
          satisfyingElements = Set(nodeVal(cyberId), Value(lossSort, 5000L))
        )
        val response = QueryResponseBuilder.from(output, "q")
        // Only the node value should resolve — the Loss value is filtered by sort
        assertTrue(
          response.satisfyingNodeIds == List(cyberId)
        )
      },
      test("filters out a node-sorted value whose carrier is not a NodeId") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 1.0, rangeSize = 1, satisfyingCount = 1),
          rangeElements = Set(Value(nodeSort, "not-an-id")),
          satisfyingElements = Set(Value(nodeSort, "not-an-id"))
        )
        val response = QueryResponseBuilder.from(output, "q")
        assertTrue(response.satisfyingNodeIds.isEmpty)
      },
      test("empty evaluation output produces empty response") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = false, proportion = 0.0, rangeSize = 0, satisfyingCount = 0),
          rangeElements = Set.empty[Value],
          satisfyingElements = Set.empty[Value]
        )
        val response = QueryResponseBuilder.from(output, "empty")
        assertTrue(
          response.satisfied == false,
          response.proportion == 0.0,
          response.rangeSize == 0,
          response.satisfyingCount == 0,
          response.satisfyingNodeIds.isEmpty,
          response.queryEcho == "empty"
        )
      },
      test("sampleSize equals rangeSize in exact mode") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 0.5, rangeSize = 4, satisfyingCount = 2),
          rangeElements = Set(nodeVal(cyberId), nodeVal(hardwareId), nodeVal(aId), nodeVal(bId)),
          satisfyingElements = Set(nodeVal(cyberId), nodeVal(hardwareId))
        )
        val response = QueryResponseBuilder.from(output, "q")
        assertTrue(response.sampleSize == response.rangeSize)
      }
    )

end QueryResponseBuilderSpec
