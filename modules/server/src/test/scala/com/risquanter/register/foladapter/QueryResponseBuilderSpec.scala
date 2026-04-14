package com.risquanter.register.foladapter

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.testutil.TestHelpers

import fol.result.{EvaluationOutput, VagueQueryResult}
import fol.typed.{Value, TypeId}
import fol.quantifier.AtLeast
import fol.sampling.{SamplingParams, ProportionEstimate}

/** Tests for [[QueryResponseBuilder]] — maps fol-engine `EvaluationOutput[Value]`
  * to register's `QueryResponse`.
  *
  * Covers:
  *   - Asset-value projection via Value.as[String]
  *   - Non-Asset values are filtered out (Loss, Probability)
  *   - Unknown node names are filtered out
  *   - All scalar fields pass through correctly
  *   - Empty evaluation output
  */
object QueryResponseBuilderSpec extends ZIOSpecDefault with TestHelpers:

  private val assetSort = TypeId("Asset")
  private val lossSort  = TypeId("Loss")

  private val cyberId    = nodeId("cyber")
  private val hardwareId = nodeId("hardware")

  private val nodeLookup: Map[String, NodeId] = Map(
    "Cyber"    -> cyberId,
    "Hardware" -> hardwareId
  )

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
      test("maps satisfying Asset values to NodeIds") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 1.0, rangeSize = 2, satisfyingCount = 2),
          rangeElements = Set(Value(assetSort, "Cyber"), Value(assetSort, "Hardware")),
          satisfyingElements = Set(Value(assetSort, "Cyber"), Value(assetSort, "Hardware"))
        )
        val response = QueryResponseBuilder.from(output, nodeLookup, "test query")
        assertTrue(
          response.satisfied == true,
          response.proportion == 1.0,
          response.rangeSize == 2,
          response.satisfyingCount == 2,
          response.satisfyingNodeIds.toSet == Set(cyberId, hardwareId),
          response.queryEcho == "test query"
        )
      },
      test("filters out non-Asset values from satisfying elements") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 1.0, rangeSize = 3, satisfyingCount = 3),
          rangeElements = Set(Value(assetSort, "Cyber"), Value(assetSort, "Hardware"), Value(lossSort, 5000L)),
          satisfyingElements = Set(Value(assetSort, "Cyber"), Value(lossSort, 5000L))
        )
        val response = QueryResponseBuilder.from(output, nodeLookup, "q")
        // Only "Cyber" should resolve — the Loss value should be filtered
        assertTrue(
          response.satisfyingNodeIds == List(cyberId)
        )
      },
      test("filters out unknown names not in nodeLookup") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = true, proportion = 1.0, rangeSize = 1, satisfyingCount = 1),
          rangeElements = Set(Value(assetSort, "Unknown")),
          satisfyingElements = Set(Value(assetSort, "Unknown"))
        )
        val response = QueryResponseBuilder.from(output, nodeLookup, "q")
        assertTrue(response.satisfyingNodeIds.isEmpty)
      },
      test("empty evaluation output produces empty response") {
        val output = EvaluationOutput(
          result = makeResult(satisfied = false, proportion = 0.0, rangeSize = 0, satisfyingCount = 0),
          rangeElements = Set.empty[Value],
          satisfyingElements = Set.empty[Value]
        )
        val response = QueryResponseBuilder.from(output, nodeLookup, "empty")
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
          rangeElements = Set(Value(assetSort, "Cyber"), Value(assetSort, "Hardware"), Value(assetSort, "A"), Value(assetSort, "B")),
          satisfyingElements = Set(Value(assetSort, "Cyber"), Value(assetSort, "Hardware"))
        )
        val response = QueryResponseBuilder.from(output, nodeLookup, "q")
        assertTrue(response.sampleSize == response.rangeSize)
      }
    )

end QueryResponseBuilderSpec
