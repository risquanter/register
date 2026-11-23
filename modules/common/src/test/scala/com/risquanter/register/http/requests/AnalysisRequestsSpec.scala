package com.risquanter.register.http.requests

import io.github.iltotore.iron.autoRefine
import zio.test.*
import zio.json.{DecoderOps, EncoderOps}
import com.risquanter.register.domain.data.{MitigationSelection, RiskTree}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.{BoundedCollection, ErrorLocation, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.testutil.TestHelpers.{mitigationId, nodeId, unsafeGet}

/** The two analysis request bodies: the bounds on a curve request and on each
  * of its valuations, and the selection both bodies require.
  */
object AnalysisRequestsSpec extends ZIOSpecDefault {

  private val n = nodeId("n-one")

  private val valuationsLocation = ErrorLocation.requestField(FieldName.Valuations)

  private def valuation(selection: MitigationSelection, nodes: List[NodeId]): CurveValuationRequest =
    unsafeGet(CurveValuationRequest.create(selection, nodes), "valuation")

  private def refusals(built: zio.prelude.Validation[ValidationError, ?]): List[ValidationError] =
    built.toEither.left.toOption.toList.flatMap(_.toList)

  def spec = suite("Analysis request bodies")(
    test("a node list above a tree's node bound is refused at the valuation's node list") {
      val ids     = (0 to RiskTree.MaxNodes).toList.map(i => nodeId(s"n-$i"))
      val refused = refusals(CurveValuationRequest.create(MitigationSelection.Inherent, ids))
      assertTrue(
        refused.map(_.location) == List(ErrorLocation.requestField(FieldName.Nodes)),
        refused.forall(_.problem match
          case FieldProblem.TooMany(BoundedCollection.Nodes, _, limit) => limit == RiskTree.MaxNodes
          case _                                                       => false)
      )
    },

    test("a request with no valuation is refused") {
      val refused = refusals(LECCurvesMultiRequest.create(Nil))
      assertTrue(
        refused.map(_.location) == List(valuationsLocation),
        refused.map(_.problem) == List(FieldProblem.Required)
      )
    },

    test("one valuation more than the bound is refused") {
      val valuations = (0 to LECCurvesMultiRequest.MaxValuations).toList
        .map(i => valuation(MitigationSelection.single(mitigationId(s"m-$i")), List(n)))
      val refused = refusals(LECCurvesMultiRequest.create(valuations))
      assertTrue(
        valuations.size == 17,
        refused.map(_.location) == List(valuationsLocation),
        refused.forall(_.problem match
          case FieldProblem.TooMany(BoundedCollection.Valuations, count, limit) =>
            count == 17 && limit == LECCurvesMultiRequest.MaxValuations
          case _ => false)
      )
    },

    test("two valuations with equal selections are refused") {
      val twice = List(
        valuation(MitigationSelection.Residual, List(n)),
        valuation(MitigationSelection.Inherent, List(n)),
        valuation(MitigationSelection.Residual, List(nodeId("n-two")))
      )
      val refused = refusals(LECCurvesMultiRequest.create(twice))
      assertTrue(
        refused.map(_.location) == List(valuationsLocation),
        refused.map(_.problem) == List(FieldProblem.DuplicateSelection(2))
      )
    },

    test("valuations with different selections are accepted in the order given") {
      val stated = List(
        valuation(MitigationSelection.Inherent, List(n)),
        valuation(MitigationSelection.Residual, List(n))
      )
      assertTrue(LECCurvesMultiRequest.create(stated).toEither.map(_.valuations) == Right(stated))
    },

    test("a valuation with no selection is refused") {
      assertTrue(
        s"""{"valuations":[{"nodeIds":["${n.value}"]}]}""".fromJson[LECCurvesMultiRequest].isLeft,
        s"""{"nodeIds":["${n.value}"]}""".fromJson[CurveValuationRequest].isLeft
      )
    },

    test("the bounds hold for a decoded curve request") {
      assertTrue(
        """{"valuations":[]}""".fromJson[LECCurvesMultiRequest].isLeft,
        (s"""{"valuations":[{"selection":{"kind":"Inherent"},"nodeIds":["${n.value}"]},""" +
          s"""{"selection":{"kind":"Inherent"},"nodeIds":["${n.value}"]}]}""").fromJson[LECCurvesMultiRequest].isLeft
      )
    },

    test("decoding the curve request the encoder writes gives the same request") {
      val request = unsafeGet(
        LECCurvesMultiRequest.create(List(
          valuation(MitigationSelection.Inherent, List(n)),
          valuation(MitigationSelection.single(mitigationId("m-one")), List(n, nodeId("n-two")))
        )),
        "request"
      )
      assertTrue(request.toJson.fromJson[LECCurvesMultiRequest] == Right(request))
    },

    test("the exceedance body requires a selection and accepts the residual one") {
      assertTrue(
        "{}".fromJson[ProbOfExceedanceRequest].isLeft,
        """{"selection":{"kind":"Residual"}}""".fromJson[ProbOfExceedanceRequest] ==
          Right(ProbOfExceedanceRequest(MitigationSelection.Residual))
      )
    }
  )
}
