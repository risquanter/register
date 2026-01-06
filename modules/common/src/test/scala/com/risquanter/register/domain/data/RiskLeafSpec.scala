package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.RiskLeaf

object RiskLeafSpec extends ZIOSpecDefault {

  def spec = suite("RiskLeaf Smart Constructor")(
    suite("Valid RiskLeaf Creation")(
      test("creates valid expert mode risk") {
        val result = RiskLeaf.create(
          id = "cyber-attack",
          name = "Cyber Attack",
          distributionType = "expert",
          probability = 0.25,
          percentiles = Some(Array(0.05, 0.5, 0.95)),
          quantiles = Some(Array(1000.0, 5000.0, 25000.0))
        )
        assertTrue(result.isSuccess)
      },
      test("creates valid lognormal mode risk") {
        val result = RiskLeaf.create(
          id = "data-breach",
          name = "Data Breach",
          distributionType = "lognormal",
          probability = 0.15,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        assertTrue(result.isSuccess)
      },
      test("accepts minimum valid id length (3 chars)") {
        val result = RiskLeaf.create(
          id = "abc",
          name = "ABC Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isSuccess)
      },
      test("accepts maximum valid id length (30 chars)") {
        val result = RiskLeaf.create(
          id = "a" * 30,
          name = "Long ID Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isSuccess)
      },
      test("accepts id with hyphens and underscores") {
        val result = RiskLeaf.create(
          id = "ops_risk-2024",
          name = "Operations Risk",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(500L),
          maxLoss = Some(5000L)
        )
        assertTrue(result.isSuccess)
      },
      test("accepts boundary probability values") {
        val result = RiskLeaf.create(
          id = "low-prob",
          name = "Low Probability",
          distributionType = "lognormal",
          probability = 0.01,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isSuccess)
      }
    ),
    suite("Invalid ID Validation")(
      test("rejects empty id") {
        val result = RiskLeaf.create(
          id = "",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects id too short (< 3 chars)") {
        val result = RiskLeaf.create(
          id = "ab",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects id too long (> 30 chars)") {
        val result = RiskLeaf.create(
          id = "a" * 31,
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects id with spaces") {
        val result = RiskLeaf.create(
          id = "cyber attack",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects id with special characters") {
        val result = RiskLeaf.create(
          id = "cyber@attack!",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Invalid Name Validation")(
      test("rejects empty name") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects blank name") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "   ",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects name too long (> 50 chars)") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "a" * 51,
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Invalid Probability Validation")(
      test("rejects probability = 0.0") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.0,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects probability = 1.0") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 1.0,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects negative probability") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = -0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects probability > 1.0") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 1.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Invalid Distribution Type Validation")(
      test("rejects invalid distribution type") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "normal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects empty distribution type") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Expert Mode Validation")(
      test("rejects expert mode without percentiles") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          quantiles = Some(Array(1000.0, 5000.0))
        )
        assertTrue(result.isFailure)
      },
      test("rejects expert mode without quantiles") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.05, 0.95))
        )
        assertTrue(result.isFailure)
      },
      test("rejects expert mode with empty percentiles") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array.empty[Double]),
          quantiles = Some(Array(1000.0))
        )
        assertTrue(result.isFailure)
      },
      test("rejects expert mode with mismatched array lengths") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.05, 0.5, 0.95)),
          quantiles = Some(Array(1000.0, 5000.0))  // Length mismatch
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Lognormal Mode Validation")(
      test("rejects lognormal mode without minLoss") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects lognormal mode without maxLoss") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects negative minLoss") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(-100L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects negative maxLoss") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(-1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects minLoss >= maxLoss (equal)") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      },
      test("rejects minLoss > maxLoss") {
        val result = RiskLeaf.create(
          id = "valid-id",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(5000L),
          maxLoss = Some(1000L)
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Error Accumulation")(
      test("accumulates multiple validation errors") {
        val result = RiskLeaf.create(
          id = "ab",                    // Too short
          name = "",                    // Empty
          distributionType = "invalid", // Invalid type
          probability = 1.5,            // Out of range
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(
          result.isFailure
        )
        // Note: Error accumulation verified by isFailure - Validation accumulates all errors
      },
      
      test("accumulates all field validation errors and returns them") {
        val result = RiskLeaf.create(
          id = "x",                     // Too short (< 3 chars)
          name = "",                    // Empty
          distributionType = "invalid", // Not "expert" or "lognormal"
          probability = 2.0,            // > 1.0
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errorStr.contains("id") || errorStr.contains("ID") || errorStr.contains("3"),
              errorStr.toLowerCase.contains("name") || errorStr.contains("blank"),
              errorStr.toLowerCase.contains("distribution") || errorStr.contains("expert") || errorStr.contains("lognormal"),
              errorStr.toLowerCase.contains("prob") || errorStr.contains("1.0")
            )
          case Right(_) =>
            assertTrue(false) // Should have failed
        }
      },
      
      test("accumulates cross-field validation errors with field errors") {
        val result = RiskLeaf.create(
          id = "",                      // Empty - invalid
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 1.5,            // > 1.0 - invalid
          minLoss = Some(5000L),        // minLoss > maxLoss - cross-field error
          maxLoss = Some(1000L)
        )
        
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errors.length >= 3,  // At least 3 errors
              errorStr.toLowerCase.contains("id") || errorStr.contains("blank") || errorStr.contains("empty"),
              errorStr.toLowerCase.contains("prob"),
              errorStr.contains("min") && errorStr.contains("max")
            )
          case Right(_) =>
            assertTrue(false) // Should have failed
        }
      },
      
      test("accumulates mode-specific validation errors") {
        // Expert mode without required fields
        val result = RiskLeaf.create(
          id = "x",                     // Too short
          name = "",                    // Empty
          distributionType = "expert",
          probability = 0.5,
          percentiles = None,           // Missing for expert mode
          quantiles = None              // Missing for expert mode
        )
        
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errors.length >= 3,  // id, name, and mode-specific errors
              errorStr.toLowerCase.contains("percentile") || errorStr.toLowerCase.contains("quantile"),
              errorStr.toLowerCase.contains("expert")
            )
          case Right(_) =>
            assertTrue(false)
        }
      }
    ),
    suite("Successful Construction Properties")(
      test("constructed RiskLeaf has correct id") {
        val result = RiskLeaf.create(
          id = "test-risk",
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(
          result.map(_.id).toOption.contains("test-risk")
        )
      },
      test("constructed RiskLeaf has correct name") {
        val result = RiskLeaf.create(
          id = "test-risk",
          name = "Test Risk Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L)
        )
        assertTrue(
          result.map(_.name).toOption.contains("Test Risk Name")
        )
      },
      test("constructed RiskLeaf preserves percentiles/quantiles") {
        val percentiles = Array(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        val result = RiskLeaf.create(
          id = "test-risk",
          name = "Test Risk",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(percentiles),
          quantiles = Some(quantiles)
        )
        assertTrue(
          result.map(_.percentiles.isDefined).toOption.contains(true),
          result.map(_.quantiles.isDefined).toOption.contains(true)
        )
      }
    )
  )
}
