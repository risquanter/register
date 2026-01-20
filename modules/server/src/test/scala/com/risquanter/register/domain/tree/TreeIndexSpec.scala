package com.risquanter.register.domain.tree

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.*

object TreeIndexSpec extends ZIOSpecDefault {

  // Helper to create SafeId from string literal (safe for tests with known-valid values)
  // Pattern from RiskPortfolio.unsafeApply
  def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  // Test fixtures - flat node format with parentId and childIds
  val cyberLeaf = RiskLeaf.unsafeApply(
    id = "cyber",
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L),
    parentId = Some(safeId("ops-risk"))
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = "hardware",
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L),
    parentId = Some(safeId("it-risk"))
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = "software",
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L),
    parentId = Some(safeId("it-risk"))
  )

  val itPortfolio = RiskPortfolio.unsafeFromStrings(
    id = "it-risk",
    name = "IT Risk",
    childIds = Array("hardware", "software"),
    parentId = Some(safeId("ops-risk"))
  )

  val rootPortfolio = RiskPortfolio.unsafeFromStrings(
    id = "ops-risk",
    name = "Operational Risk",
    childIds = Array("cyber", "it-risk"),
    parentId = None
  )
  
  // All nodes as flat list for TreeIndex.fromNodeSeq
  val allNodes: Seq[RiskNode] = Seq(rootPortfolio, cyberLeaf, itPortfolio, hardwareLeaf, softwareLeaf)

  // SafeId values for assertions
  val opsRiskId   = safeId("ops-risk")
  val cyberId     = safeId("cyber")
  val itRiskId    = safeId("it-risk")
  val hardwareId  = safeId("hardware")
  val softwareId  = safeId("software")

  def spec = suite("TreeIndexSpec")(
    test("fromNodeSeq builds correct index structure") {
      val index = TreeIndex.fromNodeSeq(allNodes)

      assertTrue(
        index.nodes.size == 5,
        index.nodes.contains(opsRiskId),
        index.nodes.contains(cyberId),
        index.nodes.contains(itRiskId),
        index.nodes.contains(hardwareId),
        index.nodes.contains(softwareId)
      )
    },
    test("parent pointers are correct") {
      val index = TreeIndex.fromNodeSeq(allNodes)

      assertTrue(
        index.parents.get(cyberId) == Some(opsRiskId),
        index.parents.get(itRiskId) == Some(opsRiskId),
        index.parents.get(hardwareId) == Some(itRiskId),
        index.parents.get(softwareId) == Some(itRiskId),
        !index.parents.contains(opsRiskId) // Root has no parent
      )
    },
    test("children maps are correct") {
      val index = TreeIndex.fromNodeSeq(allNodes)

      assertTrue(
        index.children.get(opsRiskId).map(_.toSet) == Some(Set(cyberId, itRiskId)),
        index.children.get(itRiskId).map(_.toSet) == Some(Set(hardwareId, softwareId)),
        index.children.get(cyberId) == None, // Leaf has no children
        index.children.get(hardwareId) == None,
        index.children.get(softwareId) == None
      )
    },
    test("ancestorPath returns correct path for leaf node") {
      val index = TreeIndex.fromNodeSeq(allNodes)
      val path  = index.ancestorPath(hardwareId)

      assertTrue(
        path == List(opsRiskId, itRiskId, hardwareId)
      )
    },
    test("ancestorPath returns correct path for intermediate node") {
      val index = TreeIndex.fromNodeSeq(allNodes)
      val path  = index.ancestorPath(itRiskId)

      assertTrue(
        path == List(opsRiskId, itRiskId)
      )
    },
    test("ancestorPath returns single element for root") {
      val index = TreeIndex.fromNodeSeq(allNodes)
      val path  = index.ancestorPath(opsRiskId)

      assertTrue(
        path == List(opsRiskId)
      )
    },
    test("ancestorPath returns empty list for non-existent node") {
      val index       = TreeIndex.fromNodeSeq(allNodes)
      val nonExistent = safeId("non-existent")
      val path        = index.ancestorPath(nonExistent)

      assertTrue(path.isEmpty)
    },
    test("descendants returns all subtree nodes") {
      val index       = TreeIndex.fromNodeSeq(allNodes)
      val descendants = index.descendants(itRiskId)

      assertTrue(
        descendants == Set(itRiskId, hardwareId, softwareId)
      )
    },
    test("descendants returns only self for leaf") {
      val index       = TreeIndex.fromNodeSeq(allNodes)
      val descendants = index.descendants(cyberId)

      assertTrue(
        descendants == Set(cyberId)
      )
    },
    test("descendants returns all nodes for root") {
      val index       = TreeIndex.fromNodeSeq(allNodes)
      val descendants = index.descendants(opsRiskId)

      assertTrue(
        descendants.size == 5,
        descendants.contains(opsRiskId),
        descendants.contains(cyberId),
        descendants.contains(itRiskId),
        descendants.contains(hardwareId),
        descendants.contains(softwareId)
      )
    },
    test("isAncestor correctly identifies ancestor relationships") {
      val index = TreeIndex.fromNodeSeq(allNodes)

      assertTrue(
        index.isAncestor(opsRiskId, hardwareId),
        index.isAncestor(itRiskId, hardwareId),
        !index.isAncestor(hardwareId, opsRiskId),
        !index.isAncestor(cyberId, hardwareId)
      )
    },
    test("rootId returns correct root") {
      val index = TreeIndex.fromNodeSeq(allNodes)

      assertTrue(
        index.rootId == Some(opsRiskId)
      )
    },
    test("leafIds returns all leaf nodes") {
      val index = TreeIndex.fromNodeSeq(allNodes)
      val leafs = index.leafIds

      assertTrue(
        leafs == Set(cyberId, hardwareId, softwareId)
      )
    },
    test("empty index has no nodes") {
      val index = TreeIndex.empty

      assertTrue(
        index.nodes.isEmpty,
        index.parents.isEmpty,
        index.children.isEmpty,
        index.rootId.isEmpty
      )
    }
  )
}
