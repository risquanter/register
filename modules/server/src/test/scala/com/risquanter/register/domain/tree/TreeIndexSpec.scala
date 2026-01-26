package com.risquanter.register.domain.tree

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.safeId

object TreeIndexSpec extends ZIOSpecDefault {

  // Helper to extract validated index or fail test
  private def getIndex(nodes: Seq[RiskNode]): TreeIndex =
    TreeIndex.fromNodeSeq(nodes).toEither match {
      case Right(idx) => idx
      case Left(errors) => throw new AssertionError(s"Expected valid TreeIndex but got errors: ${errors.map(_.message).mkString("; ")}")
    }

  private def idStr(label: String): String = safeId(label).value.toString

  // Test fixtures - flat node format with parentId and childIds
  private val opsRiskIdStr  = safeId("ops-risk").value.toString
  private val cyberIdStr    = safeId("cyber").value.toString
  private val itRiskIdStr   = safeId("it-risk").value.toString
  private val hardwareIdStr = safeId("hardware").value.toString
  private val softwareIdStr = safeId("software").value.toString

  val cyberLeaf = RiskLeaf.unsafeApply(
    id = cyberIdStr,
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L),
    parentId = Some(safeId("ops-risk"))
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = hardwareIdStr,
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L),
    parentId = Some(safeId("it-risk"))
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = softwareIdStr,
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L),
    parentId = Some(safeId("it-risk"))
  )

  val itPortfolio = RiskPortfolio.unsafeFromStrings(
    id = itRiskIdStr,
    name = "IT Risk",
    childIds = Array(hardwareIdStr, softwareIdStr),
    parentId = Some(safeId("ops-risk"))
  )

  val rootPortfolio = RiskPortfolio.unsafeFromStrings(
    id = opsRiskIdStr,
    name = "Operational Risk",
    childIds = Array(cyberIdStr, itRiskIdStr),
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
    suite("valid tree construction")(
      test("fromNodeSeq builds correct index structure") {
        val index = getIndex(allNodes)

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
        val index = getIndex(allNodes)

        assertTrue(
          index.parents.get(cyberId) == Some(opsRiskId),
          index.parents.get(itRiskId) == Some(opsRiskId),
          index.parents.get(hardwareId) == Some(itRiskId),
          index.parents.get(softwareId) == Some(itRiskId),
          !index.parents.contains(opsRiskId) // Root has no parent
        )
      },
      test("children maps are correct") {
        val index = getIndex(allNodes)

        assertTrue(
          index.children.get(opsRiskId).map(_.toSet) == Some(Set(cyberId, itRiskId)),
          index.children.get(itRiskId).map(_.toSet) == Some(Set(hardwareId, softwareId)),
          index.children.get(cyberId) == None, // Leaf has no children
          index.children.get(hardwareId) == None,
          index.children.get(softwareId) == None
        )
      },
      test("ancestorPath returns correct path for leaf node") {
        val index = getIndex(allNodes)
        val path  = index.ancestorPath(hardwareId)

        assertTrue(
          path == List(opsRiskId, itRiskId, hardwareId)
        )
      },
      test("ancestorPath returns correct path for intermediate node") {
        val index = getIndex(allNodes)
        val path  = index.ancestorPath(itRiskId)

        assertTrue(
          path == List(opsRiskId, itRiskId)
        )
      },
      test("ancestorPath returns single element for root") {
        val index = getIndex(allNodes)
        val path  = index.ancestorPath(opsRiskId)

        assertTrue(
          path == List(opsRiskId)
        )
      },
      test("ancestorPath returns empty list for non-existent node") {
        val index       = getIndex(allNodes)
        val nonExistent = safeId("non-existent")
        val path        = index.ancestorPath(nonExistent)

        assertTrue(path.isEmpty)
      },
      test("descendants returns all subtree nodes") {
        val index       = getIndex(allNodes)
        val descendants = index.descendants(itRiskId)

        assertTrue(
          descendants == Set(itRiskId, hardwareId, softwareId)
        )
      },
      test("descendants returns only self for leaf") {
        val index       = getIndex(allNodes)
        val descendants = index.descendants(cyberId)

        assertTrue(
          descendants == Set(cyberId)
        )
      },
      test("descendants returns all nodes for root") {
        val index       = getIndex(allNodes)
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
        val index = getIndex(allNodes)

        assertTrue(
          index.isAncestor(opsRiskId, hardwareId),
          index.isAncestor(itRiskId, hardwareId),
          !index.isAncestor(hardwareId, opsRiskId),
          !index.isAncestor(cyberId, hardwareId)
        )
      },
      test("rootId returns correct root") {
        val index = getIndex(allNodes)

        assertTrue(
          index.rootId == Some(opsRiskId)
        )
      },
      test("leafIds returns all leaf nodes") {
        val index = getIndex(allNodes)
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
    ),
    suite("parent-child consistency validation")(
      test("fails when child's parentId doesn't match portfolio's childIds") {
        // Create a child that claims wrong parent
        val orphanLeaf = RiskLeaf.unsafeApply(
          id = idStr("orphan"),
          name = "Orphan Node",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("wrong-parent"))  // Points to non-existent parent
        )
        
        val root = RiskPortfolio.unsafeFromStrings(
          id = idStr("root"),
          name = "Root",
          childIds = Array(idStr("orphan")),  // Lists orphan as child
          parentId = None
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(root, orphanLeaf))
        
        assertTrue(
          result.toEither.isLeft,
          result.toEither.left.exists(errors =>
            errors.exists(e => 
              e.code == ValidationErrorCode.CONSTRAINT_VIOLATION &&
              e.message.contains(idStr("orphan")) &&
              e.message.contains("parentId")
            )
          )
        )
      },
      test("fails when portfolio lists child that doesn't exist") {
        val root = RiskPortfolio.unsafeFromStrings(
          id = idStr("root"),
          name = "Root",
          childIds = Array(idStr("ghost-child")),  // References non-existent node
          parentId = None
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(root))
        
        assertTrue(
          result.toEither.isLeft,
          result.toEither.left.exists(errors =>
            errors.exists(e => 
              e.code == ValidationErrorCode.CONSTRAINT_VIOLATION &&
              e.message.contains(idStr("ghost-child")) &&
              e.message.contains("does not exist")
            )
          )
        )
      },
      test("fails when node has parentId but parent doesn't list it as child") {
        // Child "lonely" claims root as parent, but root only lists "other-child"
        val lonely = RiskLeaf.unsafeApply(
          id = idStr("lonely"),
          name = "Lonely Node",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("root"))  // Claims root as parent
        )
        
        val otherChild = RiskLeaf.unsafeApply(
          id = idStr("other-child"),
          name = "Other Child",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("root"))  // Correctly claims root
        )
        
        val root = RiskPortfolio.unsafeFromStrings(
          id = idStr("root"),
          name = "Root",
          childIds = Array(idStr("other-child")),  // Only lists other-child, not lonely
          parentId = None
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(root, lonely, otherChild))
        
        assertTrue(
          result.toEither.isLeft,
          result.toEither.left.exists(errors =>
            errors.exists(e => 
              e.code == ValidationErrorCode.CONSTRAINT_VIOLATION &&
              e.message.contains(idStr("lonely")) &&
              e.message.contains("doesn't list it as child")
            )
          )
        )
      },
      test("fails when node has parentId pointing to a leaf (not portfolio)") {
        val parent = RiskLeaf.unsafeApply(
          id = idStr("parent-leaf"),
          name = "Parent Leaf",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = None
        )
        
        val child = RiskLeaf.unsafeApply(
          id = idStr("child"),
          name = "Child",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("parent-leaf"))  // Points to leaf, not portfolio
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(parent, child))
        
        assertTrue(
          result.toEither.isLeft,
          result.toEither.left.exists(errors =>
            errors.exists(e => 
              e.code == ValidationErrorCode.CONSTRAINT_VIOLATION &&
              e.message.contains("leaf") &&
              e.message.contains("not a portfolio")
            )
          )
        )
      },
      test("fails when node has parentId pointing to non-existent node") {
        val orphan = RiskLeaf.unsafeApply(
          id = idStr("orphan"),
          name = "Orphan",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("non-existent"))  // Points to missing parent
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(orphan))
        
        assertTrue(
          result.toEither.isLeft,
          result.toEither.left.exists(errors =>
            errors.exists(e => 
              e.code == ValidationErrorCode.CONSTRAINT_VIOLATION &&
              e.message.contains(safeId("non-existent").value.toString) &&
              e.message.contains("doesn't exist")
            )
          )
        )
      },
      test("accumulates multiple validation errors") {
        // Create multiple inconsistencies
        val orphan1 = RiskLeaf.unsafeApply(
          id = idStr("orphan1"),
          name = "Orphan 1",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("missing1"))
        )
        
        val orphan2 = RiskLeaf.unsafeApply(
          id = idStr("orphan2"),
          name = "Orphan 2",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("missing2"))
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(orphan1, orphan2))
        
        assertTrue(
          result.toEither.isLeft,
          // Should have at least 2 errors (one per orphan)
          result.toEither.left.exists(errors => errors.length >= 2)
        )
      },
      test("succeeds with valid bidirectional parent-child references") {
        // Valid structure: parent lists child, child points to parent
        val child = RiskLeaf.unsafeApply(
          id = idStr("valid-child"),
          name = "Valid Child",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          parentId = Some(safeId("valid-parent"))
        )
        
        val parent = RiskPortfolio.unsafeFromStrings(
          id = idStr("valid-parent"),
          name = "Valid Parent",
          childIds = Array(idStr("valid-child")),
          parentId = None
        )
        
        val result = TreeIndex.fromNodeSeq(Seq(parent, child))
        
        assertTrue(
          result.toEither.isRight,
          result.toEither.exists(idx => idx.nodes.size == 2)
        )
      }
    )
  )
}
