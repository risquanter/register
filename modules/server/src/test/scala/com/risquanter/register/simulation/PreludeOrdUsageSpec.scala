package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Ord
import com.risquanter.register.domain.data.Loss
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.testutil.TestHelpers.nodeId
import com.risquanter.register.testutil.ConfigTestLoader.withCfg
import com.risquanter.register.testutil.RiskResultTestSupport.leafOf

/**
 * Tests for Ord[Loss] usage in TreeMap operations.
 *
 * Verifies that:
 * - TreeMap construction uses Ord[Loss].toScala
 * - maxLoss uses Ord[Loss] for comparison
 * - minLoss uses Ord[Loss] for comparison
 * - TreeMap maintains sorted order for quantile queries
 *
 * The aggregated cases are in `services.cache.NodeLossesSpec`: a portfolio is
 * built by `PortfolioLosses.create`, which is `private[cache]` and cannot be
 * named from this package.
 */
object PreludeOrdUsageSpec extends ZIOSpecDefault {

  def spec = suite("PreludeOrdUsageSpec")(

    suite("LossDistribution - Ord[Loss] with TreeMap")(
      test("maxLoss uses Ord[Loss] - single loss") {
        val result = withCfg(10) { leafOf(nodeId("test-risk"), Map(1 -> 5000L)) }

        assertTrue(result.maxLoss == 5000L)
      },

      test("maxLoss uses Ord[Loss] - multiple losses") {
        val result = withCfg(10) { leafOf(nodeId("test-risk"), Map(1 -> 1000L, 2 -> 5000L, 3 -> 2000L)) }

        assertTrue(result.maxLoss == 5000L)
      },

      test("minLoss uses Ord[Loss] - single loss") {
        val result = withCfg(10) { leafOf(nodeId("test-risk"), Map(1 -> 5000L)) }

        assertTrue(result.minLoss == 5000L)
      },

      test("minLoss uses Ord[Loss] - multiple losses") {
        val result = withCfg(10) { leafOf(nodeId("test-risk"), Map(1 -> 1000L, 2 -> 5000L, 3 -> 2000L)) }

        assertTrue(result.minLoss == 1000L)
      },

      test("empty result has zero max/min") {
        val result = withCfg(10) { leafOf(nodeId("empty-risk"), Map.empty) }

        assertTrue(result.maxLoss == 0L) &&
        assertTrue(result.minLoss == 0L)
      },

      test("outcomeCount is sorted by Loss (ascending)") {
        val result = withCfg(10) {
          leafOf(
            nodeId("test-risk"),
            Map(
              1 -> 3000L,
              2 -> 1000L,
              3 -> 5000L,
              4 -> 2000L
            )
          )
        }

        val losses = result.outcomeCount.keys.toVector

        assertTrue(
          losses == Vector(1000L, 2000L, 3000L, 5000L)
        )
      },

      test("outcomeCount aggregates duplicate losses") {
        val result = withCfg(10) {
          leafOf(
            nodeId("test-risk"),
            Map(
              1 -> 1000L,
              2 -> 2000L,
              3 -> 1000L,  // Duplicate
              4 -> 2000L   // Duplicate
            )
          )
        }

        assertTrue(
          result.outcomeCount(1000L) == 2,
          result.outcomeCount(2000L) == 2,
          result.outcomeCount.size == 2
        )
      },

      test("rangeFrom uses Ord[Loss] for threshold queries") {
        val result = withCfg(10) {
          leafOf(
            nodeId("test-risk"),
            Map(
              1 -> 1000L,
              2 -> 2000L,
              3 -> 3000L,
              4 -> 4000L,
              5 -> 5000L
            )
          )
        }

        // Query losses >= 3000L
        val exceedingLosses = result.outcomeCount.rangeFrom(3000L).keys.toVector

        assertTrue(exceedingLosses == Vector(3000L, 4000L, 5000L))
      }
    ),

    suite("Ord[Loss] - explicit type class usage")(
      test("Ord[Loss].toScala provides scala.math.Ordering") {
        val ordering: scala.math.Ordering[Loss] = Ord[Loss].toScala

        assertTrue(
          ordering.compare(1000L, 2000L) < 0,
          ordering.compare(2000L, 1000L) > 0,
          ordering.compare(1000L, 1000L) == 0
        )
      },

      test("Ord[Loss] integrates with TreeMap construction") {
        val data = Map(3000L -> 1, 1000L -> 2, 2000L -> 1)
        val treeMap = scala.collection.immutable.TreeMap.from(data)(using Ord[Loss].toScala)

        assertTrue(treeMap.keys.toVector == Vector(1000L, 2000L, 3000L))
      }
    )
  )
}
