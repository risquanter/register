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

  // Test fixtures
  val cyberLeaf = RiskLeaf.unsafeApply(
    id = "cyber",
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L)
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = "hardware",
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L)
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = "software",
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L)
  )

  val itPortfolio = RiskPortfolio.unsafeApply(
    id = "it-risk",
    name = "IT Risk",
    children = Array(hardwareLeaf, softwareLeaf)
  )

  val rootPortfolio = RiskPortfolio.unsafeApply(
    id = "ops-risk",
    name = "Operational Risk",
    children = Array(cyberLeaf, itPortfolio)
  )

  // SafeId values for assertions
  val opsRiskId   = safeId("ops-risk")
  val cyberId     = safeId("cyber")
  val itRiskId    = safeId("it-risk")
  val hardwareId  = safeId("hardware")
  val softwareId  = safeId("software")

  def spec = suite("TreeIndexSpec")(
    test("fromTree builds correct index structure") {
      val index = TreeIndex.fromTree(rootPortfolio)

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
      val index = TreeIndex.fromTree(rootPortfolio)

      assertTrue(
        index.parents.get(cyberId) == Some(opsRiskId),
        index.parents.get(itRiskId) == Some(opsRiskId),
        index.parents.get(hardwareId) == Some(itRiskId),
        index.parents.get(softwareId) == Some(itRiskId),
        !index.parents.contains(opsRiskId) // Root has no parent
      )
    },
    test("children maps are correct") {
      val index = TreeIndex.fromTree(rootPortfolio)

      assertTrue(
        index.children.get(opsRiskId).map(_.toSet) == Some(Set(cyberId, itRiskId)),
        index.children.get(itRiskId).map(_.toSet) == Some(Set(hardwareId, softwareId)),
        index.children.get(cyberId) == None, // Leaf has no children
        index.children.get(hardwareId) == None,
        index.children.get(softwareId) == None
      )
    },
    test("ancestorPath returns correct path for leaf node") {
      val index = TreeIndex.fromTree(rootPortfolio)
      val path  = index.ancestorPath(hardwareId)

      assertTrue(
        path == List(opsRiskId, itRiskId, hardwareId)
      )
    },
    test("ancestorPath returns correct path for intermediate node") {
      val index = TreeIndex.fromTree(rootPortfolio)
      val path  = index.ancestorPath(itRiskId)

      assertTrue(
        path == List(opsRiskId, itRiskId)
      )
    },
    test("ancestorPath returns single element for root") {
      val index = TreeIndex.fromTree(rootPortfolio)
      val path  = index.ancestorPath(opsRiskId)

      assertTrue(
        path == List(opsRiskId)
      )
    },
    test("ancestorPath returns empty list for non-existent node") {
      val index       = TreeIndex.fromTree(rootPortfolio)
      val nonExistent = safeId("non-existent")
      val path        = index.ancestorPath(nonExistent)

      assertTrue(path.isEmpty)
    },
    test("descendants returns all subtree nodes") {
      val index       = TreeIndex.fromTree(rootPortfolio)
      val descendants = index.descendants(itRiskId)

      assertTrue(
        descendants == Set(itRiskId, hardwareId, softwareId)
      )
    },
    test("descendants returns only self for leaf") {
      val index       = TreeIndex.fromTree(rootPortfolio)
      val descendants = index.descendants(cyberId)

      assertTrue(
        descendants == Set(cyberId)
      )
    },
    test("descendants returns all nodes for root") {
      val index       = TreeIndex.fromTree(rootPortfolio)
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
      val index = TreeIndex.fromTree(rootPortfolio)

      assertTrue(
        index.isAncestor(opsRiskId, hardwareId),
        index.isAncestor(itRiskId, hardwareId),
        !index.isAncestor(hardwareId, opsRiskId),
        !index.isAncestor(cyberId, hardwareId)
      )
    },
    test("rootId returns correct root") {
      val index = TreeIndex.fromTree(rootPortfolio)

      assertTrue(
        index.rootId == Some(opsRiskId)
      )
    },
    test("leafIds returns all leaf nodes") {
      val index = TreeIndex.fromTree(rootPortfolio)
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
