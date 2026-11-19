package com.risquanter.register.http.requests

import zio.test.*
import zio.json.DecoderOps
import com.risquanter.register.domain.data.{MitigationSelection, RiskTree}
import com.risquanter.register.domain.errors.{BoundedCollection, ErrorLocation, FieldName, FieldProblem}
import com.risquanter.register.testutil.TestHelpers.nodeId

/** The two analysis request bodies: the node-count bound on the curve request,
  * and an absent selection read as the mitigation-free one.
  */
object AnalysisRequestsSpec extends ZIOSpecDefault {

  def spec = suite("Analysis request bodies")(
    test("a node list above a tree's node bound is refused at the request's node list") {
      val ids = (0 to RiskTree.MaxNodes).toList.map(i => nodeId(s"n-$i"))
      val refused = LECCurvesMultiRequest.create(ids, MitigationSelection.Inherent).toEither.left.toOption.toList.flatMap(_.toList)
      assertTrue(
        refused.map(_.location) == List(ErrorLocation.requestField(FieldName.Nodes)),
        refused.forall(_.problem match
          case FieldProblem.TooMany(BoundedCollection.Nodes, _, limit) => limit == RiskTree.MaxNodes
          case _                                                       => false)
      )
    },

    test("an absent selection decodes as the mitigation-free selection in both bodies") {
      val n = nodeId("n-one")
      assertTrue(
        s"""{"nodeIds":["${n.value}"]}""".fromJson[LECCurvesMultiRequest].map(_.selection) == Right(MitigationSelection.Inherent),
        "{}".fromJson[ProbOfExceedanceRequest] == Right(ProbOfExceedanceRequest(MitigationSelection.Inherent))
      )
    }
  )
}
