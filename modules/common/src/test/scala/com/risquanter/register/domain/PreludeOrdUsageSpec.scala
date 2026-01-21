package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Ord
import com.risquanter.register.domain.data.{RiskResult, RiskResultGroup, Loss}
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.testutil.RiskResultTestSupport.withCfg

/**
 * Tests for Ord[Loss] usage in TreeMap operations.
 * 
 * Verifies that:
 * - TreeMap construction uses Ord[Loss].toScala
 * - maxLoss uses Ord[Loss] for comparison
 * - minLoss uses Ord[Loss] for comparison
 * - TreeMap maintains sorted order for quantile queries
 */
object PreludeOrdUsageSpec extends ZIOSpecDefault {
  import io.github.iltotore.iron.refineUnsafe
  
  private def simulationConfig(nTrials: Int): SimulationConfig =
    SimulationConfig(
      defaultNTrials = nTrials.refineUnsafe,
      maxTreeDepth = 5.refineUnsafe,
      defaultTrialParallelism = 8.refineUnsafe,
      maxConcurrentSimulations = 4.refineUnsafe,
      maxNTrials = 1000000.refineUnsafe,
      maxParallelism = 16.refineUnsafe,
      defaultSeed3 = 0L,
      defaultSeed4 = 0L
    )


  def spec = suite("PreludeOrdUsageSpec")(
    
    suite("RiskResult - Ord[Loss] with TreeMap")(
      test("maxLoss uses Ord[Loss] - single loss") {
        val result = withCfg(10) { RiskResult(safeId("test-risk"), Map(1 -> 5000L), Nil) }
        
        assertTrue(result.maxLoss == 5000L)
      },
      
      test("maxLoss uses Ord[Loss] - multiple losses") {
        val result = withCfg(10) { RiskResult(safeId("test-risk"), Map(1 -> 1000L, 2 -> 5000L, 3 -> 2000L), Nil) }
        
        assertTrue(result.maxLoss == 5000L)
      },
      
      test("minLoss uses Ord[Loss] - single loss") {
        val result = withCfg(10) { RiskResult(safeId("test-risk"), Map(1 -> 5000L), Nil) }
        
        assertTrue(result.minLoss == 5000L)
      },
      
      test("minLoss uses Ord[Loss] - multiple losses") {
        val result = withCfg(10) { RiskResult(safeId("test-risk"), Map(1 -> 1000L, 2 -> 5000L, 3 -> 2000L), Nil) }
        
        assertTrue(result.minLoss == 1000L)
      },
      
      test("empty result has zero max/min") {
        val result = withCfg(10) { RiskResult(safeId("empty-risk"), Map.empty, Nil) }
        
        assertTrue(result.maxLoss == 0L) &&
        assertTrue(result.minLoss == 0L)
      },
      
      test("outcomeCount is sorted by Loss (ascending)") {
        val result = withCfg(10) {
          RiskResult(
            safeId("test-risk"),
            Map(
              1 -> 3000L,
              2 -> 1000L,
              3 -> 5000L,
              4 -> 2000L
            ),
            Nil
          )
        }
        
        val losses = result.outcomeCount.keys.toVector
        
        assertTrue(
          losses == Vector(1000L, 2000L, 3000L, 5000L)
        )
      },
      
      test("outcomeCount aggregates duplicate losses") {
        val result = withCfg(10) {
          RiskResult(
            safeId("test-risk"),
            Map(
              1 -> 1000L,
              2 -> 2000L,
              3 -> 1000L,  // Duplicate
              4 -> 2000L   // Duplicate
            ),
            Nil
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
          RiskResult(
            safeId("test-risk"),
            Map(
              1 -> 1000L,
              2 -> 2000L,
              3 -> 3000L,
              4 -> 4000L,
              5 -> 5000L
            ),
            Nil
          )
        }
        
        // Query losses >= 3000L
        val exceedingLosses = result.outcomeCount.rangeFrom(3000L).keys.toVector
        
        assertTrue(exceedingLosses == Vector(3000L, 4000L, 5000L))
      }
    ),
    
    suite("RiskResultGroup - Ord[Loss] with TreeMap")(
      test("maxLoss uses Ord[Loss] for aggregated results") {
        val group = withCfg(10) {
          val child1 = RiskResult(safeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
          val child2 = RiskResult(safeId("risk-002"), Map(1 -> 3000L, 2 -> 4000L), Nil)
          RiskResultGroup(safeId("total-risk"), child1, child2)
        }
        
        // Trial 1: 1000 + 3000 = 4000
        // Trial 2: 2000 + 4000 = 6000 <- Max
        assertTrue(group.maxLoss == 6000L)
      },
      
      test("minLoss uses Ord[Loss] for aggregated results") {
        val group = withCfg(10) {
          val child1 = RiskResult(safeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
          val child2 = RiskResult(safeId("risk-002"), Map(1 -> 3000L, 2 -> 4000L), Nil)
          RiskResultGroup(safeId("total-risk"), child1, child2)
        }
        
        // Trial 1: 1000 + 3000 = 4000 <- Min
        // Trial 2: 2000 + 4000 = 6000
        assertTrue(group.minLoss == 4000L)
      },
      
      test("outcomeCount sorted with aggregated losses") {
        val group = withCfg(10) {
          val child1 = RiskResult(safeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
          val child2 = RiskResult(safeId("risk-002"), Map(1 -> 500L, 2 -> 3000L), Nil)
          RiskResultGroup(safeId("total-risk"), child1, child2)
        }
        
        val losses = group.outcomeCount.keys.toVector
        
        // Trial 1: 1000 + 500 = 1500
        // Trial 2: 2000 + 3000 = 5000
        assertTrue(losses == Vector(1500L, 5000L))
      },
      
      test("empty group has zero max/min") {
        val group = withCfg(10) { RiskResultGroup(safeId("empty-risk")) }
        
        assertTrue(group.maxLoss == 0L) &&
        assertTrue(group.minLoss == 0L)
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
