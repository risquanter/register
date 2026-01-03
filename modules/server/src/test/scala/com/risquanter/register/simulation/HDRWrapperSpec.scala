package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*

object HDRWrapperSpec extends ZIOSpecDefault {
  
  def spec = suite("HDRWrapperSpec")(
    suite("createGenerator")(
      test("returns consistent values for same inputs") {
        val gen = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        
        val run1 = (0L until 100L).map(gen)
        val run2 = (0L until 100L).map(gen)
        
        assertTrue(run1 == run2)
      },
      
      test("produces values in [0, 1) range") {
        val gen = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        val samples = (0L until 10000L).map(gen)
        
        assertTrue(
          samples.forall(v => v >= 0.0 && v < 1.0)
        )
      },
      
      test("different entity IDs produce different sequences") {
        val gen1 = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        val gen2 = HDRWrapper.createGenerator(entityId = 2L, varId = 100L)
        
        val seq1 = (0L until 100L).map(gen1)
        val seq2 = (0L until 100L).map(gen2)
        
        assertTrue(seq1 != seq2)
      },
      
      test("different var IDs produce different sequences") {
        val gen1 = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        val gen2 = HDRWrapper.createGenerator(entityId = 1L, varId = 101L)
        
        val seq1 = (0L until 100L).map(gen1)
        val seq2 = (0L until 100L).map(gen2)
        
        assertTrue(seq1 != seq2)
      },
      
      test("different seed3 values produce different sequences") {
        val gen1 = HDRWrapper.createGenerator(entityId = 1L, varId = 100L, seed3 = 0L)
        val gen2 = HDRWrapper.createGenerator(entityId = 1L, varId = 100L, seed3 = 42L)
        
        val seq1 = (0L until 100L).map(gen1)
        val seq2 = (0L until 100L).map(gen2)
        
        assertTrue(seq1 != seq2)
      },
      
      test("different seed4 values produce different sequences") {
        val gen1 = HDRWrapper.createGenerator(entityId = 1L, varId = 100L, seed4 = 0L)
        val gen2 = HDRWrapper.createGenerator(entityId = 1L, varId = 100L, seed4 = 99L)
        
        val seq1 = (0L until 100L).map(gen1)
        val seq2 = (0L until 100L).map(gen2)
        
        assertTrue(seq1 != seq2)
      },
      
      test("produces approximately uniform distribution") {
        val gen = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        val samples = (0L until 10000L).map(gen)
        
        // Divide [0,1) into 10 bins and count samples per bin
        val bins = Array.fill(10)(0)
        samples.foreach { v =>
          val binIdx = math.min((v * 10).toInt, 9)
          bins(binIdx) += 1
        }
        
        // Each bin should have roughly 1000 samples (allow 20% deviation)
        val allBinsReasonable = bins.forall { count =>
          count >= 800 && count <= 1200
        }
        
        assertTrue(allBinsReasonable)
      }
    ),
    
    suite("generate")(
      test("produces deterministic output") {
        val v1 = HDRWrapper.generate(counter = 42L, entityId = 1L, varId = 100L)
        val v2 = HDRWrapper.generate(counter = 42L, entityId = 1L, varId = 100L)
        
        assertTrue(v1 == v2)
      },
      
      test("produces values in [0, 1) range") {
        val samples = (0L until 1000L).map { counter =>
          HDRWrapper.generate(counter, entityId = 1L, varId = 100L)
        }
        
        assertTrue(samples.forall(v => v >= 0.0 && v < 1.0))
      },
      
      test("matches createGenerator output") {
        val gen = HDRWrapper.createGenerator(entityId = 1L, varId = 100L, seed3 = 5L, seed4 = 10L)
        
        val direct = (0L until 100L).map { counter =>
          HDRWrapper.generate(counter, entityId = 1L, varId = 100L, seed3 = 5L, seed4 = 10L)
        }
        val curried = (0L until 100L).map(gen)
        
        assertTrue(direct == curried)
      },
      
      test("different counters produce different values") {
        val v1 = HDRWrapper.generate(counter = 0L, entityId = 1L, varId = 100L)
        val v2 = HDRWrapper.generate(counter = 1L, entityId = 1L, varId = 100L)
        
        assertTrue(v1 != v2)
      }
    ),
    
    suite("determinism properties")(
      test("same generator called multiple times returns same sequence") {
        val gen = HDRWrapper.createGenerator(entityId = 123L, varId = 456L, seed3 = 789L, seed4 = 101112L)
        
        val trials = List(0L, 1L, 10L, 100L, 1000L, 10000L)
        val results1 = trials.map(gen)
        val results2 = trials.map(gen)
        val results3 = trials.map(gen)
        
        assertTrue(
          results1 == results2 &&
          results2 == results3
        )
      },
      
      test("generator is pure and referentially transparent") {
        val gen1 = HDRWrapper.createGenerator(entityId = 1L, varId = 2L)
        val gen2 = HDRWrapper.createGenerator(entityId = 1L, varId = 2L)
        
        val trial = 42L
        assertTrue(gen1(trial) == gen2(trial))
      }
    ),
    
    suite("edge cases")(
      test("handles zero trial counter") {
        val gen = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        val v = gen(0L)
        
        assertTrue(v >= 0.0 && v < 1.0)
      },
      
      test("handles large trial counters") {
        val gen = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
        val v = gen(Long.MaxValue)
        
        assertTrue(v >= 0.0 && v < 1.0)
      },
      
      test("handles negative entity IDs") {
        val gen = HDRWrapper.createGenerator(entityId = -1L, varId = 100L)
        val samples = (0L until 100L).map(gen)
        
        assertTrue(samples.forall(v => v >= 0.0 && v < 1.0))
      },
      
      test("handles zero entity and var IDs") {
        val gen = HDRWrapper.createGenerator(entityId = 0L, varId = 0L)
        val samples = (0L until 100L).map(gen)
        
        assertTrue(samples.forall(v => v >= 0.0 && v < 1.0))
      }
    )
  )
}
