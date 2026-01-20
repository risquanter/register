package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.domain.tree.NodeId
import com.risquanter.register.testutil.TestHelpers.safeId

object RiskPortfolioSpec extends ZIOSpecDefault {

  def spec = suite("RiskPortfolio Smart Constructor")(
    
    suite("Valid RiskPortfolio Creation")(
      
      test("accept valid portfolio with single childId") {
        val result = RiskPortfolio.create(
          id = "portfolio-1",
          name = "Test Portfolio",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.id == safeId("portfolio-1")) &&
        assertTrue(result.toEither.toOption.get.name == "Test Portfolio") &&
        assertTrue(result.toEither.toOption.get.childIds.length == 1)
      },

      test("accept valid portfolio with multiple childIds") {
        val childIds: Array[NodeId] = Array(
          safeId("leaf-1"),
          safeId("leaf-2"),
          safeId("leaf-3")
        )
        val result = RiskPortfolio.create(
          id = "multi-child",
          name = "Multi-Child Portfolio",
          childIds = childIds
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.childIds.length == 3)
      },

      test("accept valid portfolio referencing another portfolio") {
        // In flat format, we just reference child IDs - the actual nodes are stored separately
        val result = RiskPortfolio.create(
          id = "parent-port",
          name = "Parent Portfolio",
          childIds = Array[NodeId](safeId("child-port"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.id == safeId("parent-port")) &&
        assertTrue(result.toEither.toOption.get.childIds.length == 1)
      },

      test("accept valid portfolio with mixed child references") {
        // References to both leaves and portfolios (just IDs)
        val result = RiskPortfolio.create(
          id = "mixed-port",
          name = "Mixed Portfolio",
          childIds = Array[NodeId](safeId("leaf-1"), safeId("child-port"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.childIds.length == 2)
      },

      test("accept valid ID with minimum length (3 chars)") {
        val result = RiskPortfolio.create(
          id = "abc",
          name = "Short ID",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess)
      },

      test("accept valid ID with maximum length (30 chars)") {
        val longId = "a" * 30
        val result = RiskPortfolio.create(
          id = longId,
          name = "Long ID",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess)
      },

      test("accept valid ID with hyphens and underscores") {
        val result = RiskPortfolio.create(
          id = "ops-risk_2024",
          name = "Ops Risk",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess)
      },
      
      test("accept portfolio with parentId") {
        val result = RiskPortfolio.create(
          id = "child-portfolio",
          name = "Child Portfolio",
          childIds = Array[NodeId](safeId("leaf-1")),
          parentId = Some(safeId("parent-portfolio"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.parentId == Some(safeId("parent-portfolio")))
      }
    ),

    suite("Invalid ID Validation")(
      
      test("reject empty ID") {
        val result = RiskPortfolio.create(
          id = "",
          name = "Valid Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject ID with only whitespace") {
        val result = RiskPortfolio.create(
          id = "   ",
          name = "Valid Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject ID shorter than 3 characters") {
        val result = RiskPortfolio.create(
          id = "ab",
          name = "Valid Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject ID longer than 30 characters") {
        val longId = "a" * 31
        val result = RiskPortfolio.create(
          id = longId,
          name = "Valid Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject ID with special characters (spaces)") {
        val result = RiskPortfolio.create(
          id = "ops risk",
          name = "Valid Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject ID with special characters (dots, slashes)") {
        val result = RiskPortfolio.create(
          id = "ops.risk/2024",
          name = "Valid Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Invalid Name Validation")(
      
      test("reject empty name") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject name with only whitespace") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "   ",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      },

      test("reject name longer than 50 characters") {
        val longName = "a" * 51
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = longName,
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Invalid ChildIds Validation")(
      
      test("reject null childIds array") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "Valid Name",
          childIds = null
        )

        assertTrue(result.isFailure)
      },

      test("reject empty childIds array") {
        val result = RiskPortfolio.create(
          id = "valid-id",
          name = "Valid Name",
          childIds = Array.empty[NodeId]
        )

        assertTrue(result.isFailure)
      }
    ),

    suite("Error Accumulation")(
      
      test("accumulate multiple validation errors") {
        val result = RiskPortfolio.create(
          id = "ab",  // Too short
          name = "",  // Empty
          childIds = Array.empty[NodeId]  // Empty array
        )

        assertTrue(result.isFailure)
      },
      
      test("accumulates all field validation errors and returns them") {
        val result = RiskPortfolio.create(
          id = "x",                    // Too short (< 3 chars)
          name = "",                   // Empty
          childIds = Array.empty[NodeId]  // Empty - invalid
        )
        
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errors.length >= 3,  // At least 3 errors (id, name, childIds)
              errorStr.toLowerCase.contains("id") || errorStr.contains("3"),
              errorStr.toLowerCase.contains("name") || errorStr.contains("blank"),
              errorStr.toLowerCase.contains("childids") || errorStr.contains("empty")
            )
          case Right(_) =>
            assertTrue(false) // Should have failed
        }
      },
      
      test("accumulates ID and childIds validation errors") {
        val result = RiskPortfolio.create(
          id = "this-id-is-way-too-long-and-exceeds-thirty-characters",  // > 30 chars
          name = "Valid Name",
          childIds = null  // Null - invalid
        )
        
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errors.length >= 2,
              errorStr.toLowerCase.contains("id") || errorStr.contains("30"),
              errorStr.toLowerCase.contains("childids") || errorStr.toLowerCase.contains("null") || errorStr.contains("empty")
            )
          case Right(_) =>
            assertTrue(false)
        }
      },
      
      test("accumulates invalid ID format and empty childIds errors") {
        val result = RiskPortfolio.create(
          id = "invalid id!",          // Contains spaces and special chars
          name = "a" * 51,              // > 50 chars
          childIds = Array.empty[NodeId]
        )
        
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errors.length >= 3,
              errorStr.toLowerCase.contains("id") || errorStr.contains("alphanumeric"),
              errorStr.toLowerCase.contains("name") || errorStr.contains("50"),
              errorStr.toLowerCase.contains("childids") || errorStr.contains("empty")
            )
          case Right(_) =>
            assertTrue(false)
        }
      }
    ),

    suite("Successful Construction Properties")(
      
      test("extract ID as SafeId correctly") {
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.id == safeId("test-id"))
      },

      test("extract name as String correctly") {
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.name == "Test Name")
      },

      test("preserve childIds array correctly") {
        val childIds: Array[NodeId] = Array(
          safeId("leaf-1"),
          safeId("leaf-2")
        )
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          childIds = childIds
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.childIds.length == 2) &&
        assertTrue(result.toEither.toOption.get.childIds(0) == safeId("leaf-1")) &&
        assertTrue(result.toEither.toOption.get.childIds(1) == safeId("leaf-2"))
      },
      
      test("parentId defaults to None when not provided") {
        val result = RiskPortfolio.create(
          id = "test-id",
          name = "Test Name",
          childIds = Array[NodeId](safeId("leaf-1"))
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.parentId.isEmpty)
      }
    ),
    
    suite("createFromStrings convenience method")(
      
      test("accept valid string childIds") {
        val result = RiskPortfolio.createFromStrings(
          id = "portfolio-1",
          name = "Test Portfolio",
          childIds = Array("leaf-1", "leaf-2")
        )

        assertTrue(result.isSuccess) &&
        assertTrue(result.toEither.toOption.get.childIds.length == 2)
      },
      
      test("reject invalid string childIds") {
        val result = RiskPortfolio.createFromStrings(
          id = "portfolio-1",
          name = "Test Portfolio",
          childIds = Array("ab", "valid-id")  // "ab" is too short
        )

        assertTrue(result.isFailure)
      }
    ),
    
    suite("unsafeFromStrings convenience method")(
      
      test("create portfolio from string IDs") {
        val portfolio = RiskPortfolio.unsafeFromStrings(
          id = "portfolio-1",
          name = "Test Portfolio",
          childIds = Array("leaf-1", "leaf-2"),
          parentId = None
        )

        assertTrue(portfolio.id == safeId("portfolio-1")) &&
        assertTrue(portfolio.childIds.length == 2) &&
        assertTrue(portfolio.childIds(0) == safeId("leaf-1"))
      },
      
      test("create portfolio with parentId from string") {
        val portfolio = RiskPortfolio.unsafeFromStrings(
          id = "child-portfolio",
          name = "Child",
          childIds = Array("leaf-1"),
          parentId = Some(safeId("parent-portfolio"))
        )

        assertTrue(portfolio.parentId == Some(safeId("parent-portfolio")))
      }
    )
  )
}
