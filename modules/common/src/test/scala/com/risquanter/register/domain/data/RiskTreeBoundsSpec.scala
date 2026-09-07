package com.risquanter.register.domain.data

import zio.test.*
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.{idStr, nodeId, treeId, unsafeGet}

/** Tree-level collection bounds enforced in `RiskTree.fromNodes`:
  *   - node count <= 10 000 (resource limit against oversized trees on merges,
  *     store-loads, and programmatic construction)
  *   - node names unique across the whole tree (D4; makes duplicate node names
  *     impossible on every construction path)
  * plus the happy path where both hold.
  */
object RiskTreeBoundsSpec extends ZIOSpecDefault {

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private def leaf(idLabel: String, leafName: String, seedVarId: Long, parent: String): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = idStr(idLabel),
      name = leafName,
      distributionType = "lognormal",
      probability = 0.5,
      minLoss = Some(100L),
      maxLoss = Some(1000L),
      parentId = Some(nodeId(parent)),
      seedVarId = seedVarId
    ), "leaf")

  def spec = suite("RiskTree collection bounds")(
    test("accepts a tree within all bounds (distinct names, small node count)") {
      val l1 = leaf("cyber", "Cyber", 1L, "root-pf")
      val l2 = leaf("flood", "Flood", 2L, "root-pf")
      val root = unsafeGet(RiskPortfolio.createFromStrings(
        id = idStr("root-pf"), name = "Root",
        childIds = Array(l1.id.value, l2.id.value)), "portfolio")
      val result = RiskTree.fromNodes(treeId("bounds-ok"), name("Bounds OK"), Seq(root, l1, l2), root.id)
      assertTrue(result.isSuccess)
    },

    test("rejects duplicate node names with AMBIGUOUS_REFERENCE (D4)") {
      // Two distinct nodes (distinct id + seedVarId) sharing one name.
      val l1 = leaf("dup-a", "Dup", 1L, "root-pf")
      val l2 = leaf("dup-b", "Dup", 2L, "root-pf")
      val root = unsafeGet(RiskPortfolio.createFromStrings(
        id = idStr("root-pf"), name = "Root",
        childIds = Array(l1.id.value, l2.id.value)), "portfolio")
      val result = RiskTree.fromNodes(treeId("bounds-dup"), name("Bounds Dup"), Seq(root, l1, l2), root.id)
      assertTrue(
        result.toEither.swap.toOption.get.exists(e =>
          e.code == ValidationErrorCode.AMBIGUOUS_REFERENCE && e.field == "nodes.name")
      )
    },

    test("rejects a tree above the node-count limit of 10 000 with CONSTRAINT_VIOLATION") {
      // A valid two-level tree just over the cap. A single portfolio caps at
      // 1000 children, so the count is reached via 10 sub-portfolios of 1000
      // leaves each: 10 * 1000 leaves + 10 subs + 1 root = 10 011 nodes.
      val subCount = 10
      val perSub   = 1000
      val subs = (0 until subCount).map { s =>
        val subLabel = s"sub-$s"
        val leaves = (0 until perSub).map { i =>
          val n = s * perSub + i
          leaf(s"n-$n", s"Node $n", (n + 1).toLong, subLabel)
        }
        val sub = unsafeGet(RiskPortfolio.createFromStrings(
          id = idStr(subLabel), name = s"Sub $s",
          childIds = leaves.map(_.id.value).toArray,
          parentId = Some(nodeId("root-pf"))), "portfolio")
        (sub, leaves)
      }
      val root = unsafeGet(RiskPortfolio.createFromStrings(
        id = idStr("root-pf"), name = "Root",
        childIds = subs.map(_._1.id.value).toArray), "portfolio")
      val allNodes = root +: subs.flatMap { case (sub, leaves) => sub +: leaves }
      val result = RiskTree.fromNodes(treeId("bounds-many"), name("Bounds Many"), allNodes, root.id)
      assertTrue(
        result.toEither.swap.toOption.get.exists(e =>
          e.code == ValidationErrorCode.CONSTRAINT_VIOLATION && e.field == "nodes")
      )
    }
  )
}
