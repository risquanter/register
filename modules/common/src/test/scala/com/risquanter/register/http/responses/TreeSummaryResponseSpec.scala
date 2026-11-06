package com.risquanter.register.http.responses

import zio.test.*
import zio.json.*
import sttp.tapir.DecodeResult
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf}
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.testutil.TestHelpers.{idStr, lognormalShape, treeId, unsafeGet}

/** The tree summary and the location a tree create answers with. */
object TreeSummaryResponseSpec extends ZIOSpecDefault {

  private val name: SafeName.SafeName = SafeName.SafeName("Risk Assessment".refineUnsafe)

  private val riskTree: RiskTree =
    val root = unsafeGet(RiskLeaf.create(
      id = idStr("test-risk"),
      name = "TestRisk",
      probability = 0.5,
      shape = lognormalShape(1000L, 50000L),
      parentId = None,
      seedVarId = 1L
    ), "leaf")
    unsafeGet(RiskTree.fromNodes(
      id = treeId("sim-1"),
      name = name,
      nodes = Seq(root),
      rootId = root.id,
      seedVarHighWater = None,
      mitigations = Nil
    ), "tree")

  def spec = suite("TreeSummaryResponse")(
    test("the summary is the tree's identifier and name") {
      assertTrue(riskTree.toSummary == TreeSummaryResponse(treeId("sim-1"), name))
    },
    test("round-trips through its codec with the name still refined") {
      val summary = riskTree.toSummary
      assertTrue(summary.toJson.fromJson[TreeSummaryResponse] == Right(summary))
    },
    test("a list wraps its trees in an object") {
      val json = TreeListResponse(List(riskTree.toSummary)).toJson
      assertTrue(json.startsWith("{\"trees\":["))
    },
    suite("TreeLocation")(
      test("renders as the collection segment and the identifier") {
        val id = treeId("sim-1")
        assertTrue(TreeLocation.codec.encode(TreeLocation(id)) == TreeLocation.Prefix + id.value)
      },
      test("parse undoes render") {
        val location = TreeLocation(treeId("sim-1"))
        assertTrue(TreeLocation.codec.decode(TreeLocation.codec.encode(location)) == DecodeResult.Value(location))
      },
      test("a reference without the collection segment is refused") {
        val decoded = TreeLocation.codec.decode(treeId("sim-1").value)
        assertTrue(decoded match
          case DecodeResult.Mismatch(_, _) => true
          case _                           => false)
      }
    )
  )
}
