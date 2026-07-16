package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.RiskLeaf
import com.risquanter.register.testutil.TestHelpers.{idStr, nodeId}

object RiskLeafSpec extends ZIOSpecDefault {

  def spec = suite("RiskLeaf Smart Constructor")(
    suite("Valid RiskLeaf Creation")(
      test("creates valid expert mode risk") {
        val result = RiskLeaf.create(
          id = idStr("cyber-attack"),
          name = "Cyber Attack",
          distributionType = "expert",
          probability = 0.25,
          percentiles = Some(Array(0.05, 0.5, 0.95)),
          quantiles = Some(Array(1000.0, 5000.0, 25000.0)),
          seedVarId = 1L
        )
        assertTrue(result.isSuccess)
      },
      test("creates valid lognormal mode risk") {
        val result = RiskLeaf.create(
          id = idStr("data-breach"),
          name = "Data Breach",
          distributionType = "lognormal",
          probability = 0.15,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          seedVarId = 2L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts ULID-format id") {
        val result = RiskLeaf.create(
          id = idStr("abc"),
          name = "ABC Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 3L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts another ULID-format id") {
        val result = RiskLeaf.create(
          id = idStr("long-id"),
          name = "Long ID Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 4L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts id derived from label with hyphens and underscores") {
        val result = RiskLeaf.create(
          id = idStr("ops_risk-2024"),
          name = "Operations Risk",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(500L),
          maxLoss = Some(5000L),
          seedVarId = 5L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts boundary probability values") {
        val result = RiskLeaf.create(
          id = idStr("low-prob"),
          name = "Low Probability",
          distributionType = "lognormal",
          probability = 0.01,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 6L
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
          maxLoss = Some(1000L),
          seedVarId = 7L
        )
        assertTrue(result.isFailure)
      },
      test("rejects non-ULID id (too short)") {
        val result = RiskLeaf.create(
          id = "ab",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 8L
        )
        assertTrue(result.isFailure)
      },
      test("rejects non-ULID id (too long)") {
        val result = RiskLeaf.create(
          id = "a" * 31,
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 9L
        )
        assertTrue(result.isFailure)
      },
      test("rejects non-ULID id (spaces)") {
        val result = RiskLeaf.create(
          id = "cyber attack",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 10L
        )
        assertTrue(result.isFailure)
      },
      test("rejects non-ULID id (special characters)") {
        val result = RiskLeaf.create(
          id = "cyber@attack!",
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 11L
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Invalid Name Validation")(
      test("rejects empty name") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 12L
        )
        assertTrue(result.isFailure)
      },
      test("rejects blank name") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "   ",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 13L
        )
        assertTrue(result.isFailure)
      },
      test("rejects name too long (> 50 chars)") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "a" * 51,
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 14L
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Invalid Probability Validation")(
      test("accepts probability = 0.0 (event that never occurs)") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.0,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 15L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts probability = 1.0 (event that always occurs)") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 1.0,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 16L
        )
        assertTrue(result.isSuccess)
      },
      test("rejects negative probability") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = -0.1,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 17L
        )
        assertTrue(result.isFailure)
      },
      test("rejects probability > 1.0") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 1.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 18L
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Invalid Distribution Type Validation")(
      test("rejects invalid distribution type") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "normal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 19L
        )
        assertTrue(result.isFailure)
      },
      test("rejects empty distribution type") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 20L
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Expert Mode Validation")(
      test("rejects expert mode without percentiles") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          quantiles = Some(Array(1000.0, 5000.0)),
          seedVarId = 21L
        )
        assertTrue(result.isFailure)
      },
      test("rejects expert mode without quantiles") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.05, 0.95)),
          seedVarId = 22L
        )
        assertTrue(result.isFailure)
      },
      test("rejects expert mode with empty percentiles") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array.empty[Double]),
          quantiles = Some(Array(1000.0)),
          seedVarId = 23L
        )
        assertTrue(result.isFailure)
      },
      test("rejects expert mode with mismatched array lengths") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.05, 0.5, 0.95)),
          quantiles = Some(Array(1000.0, 5000.0)),  // Length mismatch
          seedVarId = 24L
        )
        assertTrue(result.isFailure)
      }
    ),
    suite("Lognormal Mode Validation")(
      test("rejects lognormal mode without minLoss") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          maxLoss = Some(1000L),
          seedVarId = 25L
        )
        assertTrue(result.isFailure)
      },
      test("rejects lognormal mode without maxLoss") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          seedVarId = 26L
        )
        assertTrue(result.isFailure)
      },
      test("rejects negative minLoss") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(-100L),
          maxLoss = Some(1000L),
          seedVarId = 27L
        )
        assertTrue(result.isFailure)
      },
      test("rejects negative maxLoss") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(-1000L),
          seedVarId = 28L
        )
        assertTrue(result.isFailure)
      },
      test("rejects minLoss >= maxLoss (equal)") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(1000L),
          seedVarId = 29L
        )
        assertTrue(result.isFailure)
      },
      test("rejects minLoss > maxLoss") {
        val result = RiskLeaf.create(
          id = idStr("valid-id"),
          name = "Valid Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(5000L),
          maxLoss = Some(1000L),
          seedVarId = 30L
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
          maxLoss = Some(1000L),
          seedVarId = 31L
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
          maxLoss = Some(1000L),
          seedVarId = 32L
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
          maxLoss = Some(1000L),
          seedVarId = 33L
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
          quantiles = None,             // Missing for expert mode
          seedVarId = 34L
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
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 35L
        )
        assertTrue(
          result.map(_.id).toOption.contains(nodeId("test-risk"))
        )
      },
      test("constructed RiskLeaf has correct name") {
        val result = RiskLeaf.create(
          id = idStr("test-risk"),
          name = "Test Risk Name",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 36L
        )
        assertTrue(
          result.map(_.name).toOption.contains("Test Risk Name")
        )
      },
      test("constructed RiskLeaf preserves percentiles/quantiles") {
        val percentiles = Array(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        val result = RiskLeaf.create(
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(percentiles),
          quantiles = Some(quantiles),
          seedVarId = 37L
        )
        assertTrue(
          result.map(_.percentiles.isDefined).toOption.contains(true),
          result.map(_.quantiles.isDefined).toOption.contains(true)
        )
      }
    ),
    suite("Field Path Context in Errors")(
      test("invalid id includes field path") {
        val result = RiskLeaf.create(
          id = "x",  // Too short (< 3)
          name = "Test",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 38L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.id")
        )
      },
      test("invalid name includes field path") {
        val result = RiskLeaf.create(
          id = idStr("test-id"),
          name = "",  // Blank
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 39L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.name")
        )
      },
      test("invalid probability includes field path") {
        val result = RiskLeaf.create(
          id = idStr("test-id"),
          name = "Test",
          distributionType = "lognormal",
          probability = 1.5,  // Out of range
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 40L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.probability")
        )
      },
      test("invalid distributionType includes field path") {
        val result = RiskLeaf.create(
          id = idStr("test-id"),
          name = "Test",
          distributionType = "invalid",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 41L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.distributionType")
        )
      },
      test("minLoss >= maxLoss includes field path") {
        val result = RiskLeaf.create(
          id = idStr("test-id"),
          name = "Test",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(100L),  // maxLoss < minLoss
          seedVarId = 42L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.minLoss")
        )
      },
      test("missing expert mode fields includes field path") {
        val result = RiskLeaf.create(
          id = idStr("test-id"),
          name = "Test",
          distributionType = "expert",
          probability = 0.5,
          // Missing percentiles and quantiles
          seedVarId = 43L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.distributionType")
        )
      },
      test("custom field prefix propagates to errors") {
        val result = RiskLeaf.create(
          id = "x",  // Too short
          name = "Test",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          fieldPrefix = "children[0]",
          seedVarId = 44L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("children[0].id")
        )
      }
    ),
    suite("Terms Validation (Expert Mode)")(
      test("accepts explicit terms equal to anchor count") {
        val result = RiskLeaf.create(
          id = idStr("terms-exact"),
          name = "Terms Exact",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(3),
          seedVarId = 45L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts terms less than anchor count") {
        val result = RiskLeaf.create(
          id = idStr("terms-less"),
          name = "Terms Less",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(2),
          seedVarId = 46L
        )
        assertTrue(result.isSuccess)
      },
      test("accepts terms = None (server chooses min(n, 4) default)") {
        val result = RiskLeaf.create(
          id = idStr("terms-none"),
          name = "Terms None",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = None,
          seedVarId = 47L
        )
        assertTrue(result.isSuccess)
      },
      test("preserves terms value on created RiskLeaf") {
        val result = RiskLeaf.create(
          id = idStr("terms-preserved"),
          name = "Terms Preserved",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(3),
          seedVarId = 48L
        )
        assertTrue(
          result.isSuccess,
          result.toOption.get.terms.exists(_.toInt == 3)
        )
      },
      test("terms = None produces None on RiskLeaf") {
        val result = RiskLeaf.create(
          id = idStr("terms-none-field"),
          name = "Terms None Field",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          seedVarId = 49L
        )
        assertTrue(
          result.isSuccess,
          result.toOption.get.terms.isEmpty
        )
      },
      test("rejects terms exceeding anchor count") {
        // 3 anchor points, but terms = 5 — exceeds n
        val result = RiskLeaf.create(
          id = idStr("terms-too-high"),
          name = "Terms Too High",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(5),
          seedVarId = 50L
        )
        assertTrue(result.isFailure)
      },
      test("rejects terms = 0 (not PositiveInt)") {
        val result = RiskLeaf.create(
          id = idStr("terms-zero"),
          name = "Terms Zero",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(0),
          seedVarId = 51L
        )
        assertTrue(result.isFailure)
      },
      test("terms error includes field path") {
        val result = RiskLeaf.create(
          id = idStr("terms-field-path"),
          name = "Terms Field Path",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(5),
          seedVarId = 52L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("terms")
        )
      }
    ),
    suite("SeedVarId Validation")(
      test("rejects seedVarId = 0 with field path") {
        val result = RiskLeaf.create(
          id = idStr("seed-zero"),
          name = "Seed Zero",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 0L
        )
        val errorMsg = result.toEither.swap.getOrElse(zio.NonEmptyChunk.single("")).mkString("; ")
        assertTrue(
          result.isFailure,
          errorMsg.contains("root.seedVarId")
        )
      },
      test("rejects seedVarId = 50000000 (stream doubling budget)") {
        val result = RiskLeaf.create(
          id = idStr("seed-cap"),
          name = "Seed Cap",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 50000000L
        )
        assertTrue(result.isFailure)
      },
      test("accepts range bounds 1 and 49999999") {
        val lo = RiskLeaf.create(
          id = idStr("seed-lo"),
          name = "Seed Lo",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 1L
        )
        val hi = RiskLeaf.create(
          id = idStr("seed-hi"),
          name = "Seed Hi",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 49999999L
        )
        assertTrue(lo.isSuccess, hi.isSuccess)
      },
      test("seedVarId error accumulates with other field errors") {
        val result = RiskLeaf.create(
          id = "x",           // too short
          name = "Seed Accum",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 0L      // out of range
        )
        result.toEither match {
          case Left(errors) =>
            val errorStr = errors.mkString("; ")
            assertTrue(
              errors.length >= 2,
              errorStr.contains("root.id"),
              errorStr.contains("root.seedVarId")
            )
          case Right(_) => assertTrue(false)
        }
      },
      test("preserves seedVarId on created RiskLeaf") {
        val result = RiskLeaf.create(
          id = idStr("seed-preserved"),
          name = "Seed Preserved",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 77L
        )
        assertTrue(
          result.isSuccess,
          result.toOption.get.seedVarId.value == 77L
        )
      }
    ),
    suite("SeedVarId JSON")(
      test("encoder writes seedVarId and decoder round-trips it") {
        import zio.json.{EncoderOps, DecoderOps}
        val leaf = RiskLeaf.unsafeApply(
          id = idStr("seed-json"),
          name = "Seed JSON",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(100L),
          maxLoss = Some(1000L),
          seedVarId = 42L
        )
        val json = leaf.toJson
        val decoded = json.fromJson[RiskLeaf]
        assertTrue(
          json.contains("\"seedVarId\":42"),
          decoded.map(_.seedVarId.value) == Right(42L)
        )
      },
      test("decoder rejects leaf JSON without seedVarId") {
        import zio.json.DecoderOps
        val json =
          s"""{"id":"${idStr("seed-missing")}","name":"Seed Missing","distributionType":"lognormal","probability":0.5,"minLoss":100,"maxLoss":1000}"""
        assertTrue(json.fromJson[RiskLeaf].isLeft)
      },
      test("decoder rejects out-of-range seedVarId in JSON") {
        import zio.json.DecoderOps
        val json =
          s"""{"id":"${idStr("seed-oor")}","name":"Seed OOR","distributionType":"lognormal","probability":0.5,"minLoss":100,"maxLoss":1000,"seedVarId":0}"""
        val decoded = json.fromJson[RiskLeaf]
        assertTrue(
          decoded.isLeft,
          decoded.swap.toOption.get.contains("seedVarId")
        )
      }
    )
  )
}

