package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*

object RiskPortfolioSpec extends ZIOSpecDefault {

  // Helper: Create valid RiskLeaf for use as child
  private def createValidLeaf(id: String = "leaf-1", name: String = "Test Leaf"): RiskLeaf = {
    RiskLeaf.unsafeApply(
      id = id,
      name = name,
      distributionType = "lognormal",
      probability = 0.5,
      percentiles = None,
      quantiles = None,
      minLoss = Some(1000L),
      maxLoss = Some(50000L)
    )
  }

  def spec = suite("RiskPortfolio Smart Constructor")(
    
    suite("Valid RiskPortfolio Creation")(
      
      test("accept valid flat portfolio with single child") {
        val child = createValidLeaf()
        val result = RiskPortfolio.create(
          id = "portfolio-1",
          name = "Test Portfolio",
          children = Array[RiskNode](child)
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.id == "portfolio-1") &&
        assertTrue(result.toEither.toOption.get.name == "Test Portfolio") &&
        assertTrue(result.toEither.toOption.get.children.length == 1)
      },

      test("accept valid flat portfolio with multiple children") {
        val children: Array[RiskNode] = Array(
          createValidLeaf("leaf-1", "Leaf 1"),
          createValidLeaf("leaf-2", "Leaf 2"),
          createValidLeaf("leaf-3", "Leaf 3")
        )
        val result = RiskPortfolio.create(
          id = "multi-child",
          name = "Multi-Child Portfolio",
          children = children
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.children.length == 3)
      },

      test("accept valid nested portfolio (portfolio containing portfolio)") {
        val leaf = createValidLeaf()
        val childPortfolio: RiskNode = RiskPortfolio.unsafeApply(
          id = "child-port",
          name = "Child Portfolio",
          children = Array[RiskNode](leaf)
        )
        val result = RiskPortfolio.create(
          id = "parent-port",
          name = "Parent Portfolio",
          children = Array[RiskNode](childPortfolio)
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.id == "parent-port") &&
        assertTrue(result.toEither.toOption.get.children.length == 1)
      },

      test("accept valid mixed children (leaves and portfolios)") {
        val leaf: RiskNode = createValidLeaf("leaf-1", "Leaf")
        val childPortfolio: RiskNode = RiskPortfolio.unsafeApply(
          id = "child-port",
          name = "Child Portfolio",
          children = Array[RiskNode](createValidLeaf("leaf-2", "Nested Leaf"))
        )
        val result = RiskPortfolio.create(
          id = "mixed-port",
          name = "Mixed Portfolio",
          children = Array[RiskNode](leaf, childPortfolio)
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.children.length == 2)
      },

      test("accept valid ID with minimum length (3 chars)") {
        val result = RiskPortfolio.create(
          id = "abc",
          name = "Short ID",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isSuccess)
      },

      test("accept valid ID with maximum length (30 chars)") {
        val longId = "a" * 30
        val result = RiskPortfolio.create(
          id = longId,
          name = "Long ID",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isSuccess)
      },

      test("accept valid ID with hyphens and underscores") {
        val result = RiskPortfolio.create(
          id = "ops-risk_2024",
          name = "Ops Risk",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isSuccess)
      }
    ),

    suite("Invalid ID Validation")(
      
      test("reject empty ID") {
        val result = RiskPortfolio.create(
          id = "",
          name = "Valid Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject ID with only whitespace") {
        val result = RiskPortfolio.create(
          id = "   ",
          name = "Valid Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject ID shorter than 3 characters") {
        val result = RiskPortfolio.create(
          id = "ab",
          name = "Valid Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject ID longer than 30 characters") {
        val longId = "a" * 31
        val result = RiskPortfolio.create(
          id = longId,
          name = "Valid Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject ID with special characters (spaces)") {
        val result = RiskPortfolio.create(
          id = "ops risk",
          name = "Valid Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject ID with special characters (dots, slashes)") {
        val result = RiskPortfolio.create(
          id = "ops.risk/2024",
          name = "Valid Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Invalid Name Validation")(
      
      test("reject empty name") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject name with only whitespace") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "   ",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      },

      test("reject name longer than 50 characters") {
        val longName = "a" * 51
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = longName,
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Invalid Children Validation")(
      
      test("reject null children array") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "Valid Name",
          children = null
        )

        assertTrue(result.isFailure)
      },

      test("reject empty children array") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "Valid Name",
          children = Array.empty[RiskNode]
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Error Accumulation")(
      
      test("accumulate multiple validation errors") {
        val result = RiskPortfolio.create(
          id = "ab",  // Too short
          name = "",  // Empty
          children = Array.empty[RiskNode]  // Empty array
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Successful Construction Properties")(
      
      test("extract ID as String correctly") {
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.id == "test-id")
      },

      test("extract name as String correctly") {
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          children = Array[RiskNode](createValidLeaf())
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.name == "Test Name")
      },

      test("preserve children array correctly") {
        val children: Array[RiskNode] = Array(
          createValidLeaf("leaf-1", "Leaf 1"),
          createValidLeaf("leaf-2", "Leaf 2")
        )
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          children = children
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.children.length == 2) &&
        assertTrue(result.toEither.toOption.get.children(0).id == "leaf-1") &&
        assertTrue(result.toEither.toOption.get.children(1).id == "leaf-2")
      }
    )
  )
}
