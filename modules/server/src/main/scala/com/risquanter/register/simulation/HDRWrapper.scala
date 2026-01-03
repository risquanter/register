package com.risquanter.register.simulation

import com.risquanter.simulation.util.rng.HDR

/**
 * Thin Scala wrapper around simulation-util's HDR (Hash-Derived Random) PRNG.
 * 
 * HDR provides a counter-based deterministic pseudo-random number generator
 * using the Splitmix64 algorithm. The same inputs always produce the same outputs,
 * enabling reproducible Monte Carlo simulations.
 * 
 * Key properties:
 * - Deterministic: generate(trial, entityId, varId, seed3, seed4) always returns same value
 * - Uniform: outputs are uniformly distributed in [0, 1)
 * - Independent: different seeds/varIds produce independent streams
 * 
 * @see com.risquanter.simulation.util.rng.HDR Java implementation in simulation-util
 */
object HDRWrapper {
  
  /**
   * Creates a curried random number generator for a specific entity and variable.
   * 
   * The returned function maps trial numbers to uniform random values in [0, 1).
   * This pattern enables efficient repeated sampling across trials while capturing
   * entity/variable configuration upfront.
   * 
   * @param entityId Entity identifier (e.g., company ID, project ID)
   * @param varId Variable identifier (e.g., risk ID, cost category)
   * @param seed3 Additional seed for reproducibility (default 0L)
   * @param seed4 Additional seed for reproducibility (default 0L)
   * @return Function mapping trial number to uniform random value
   * 
   * @example
   * {{{
   * val rng = HDRWrapper.createGenerator(entityId = 1L, varId = 42L)
   * val sample1 = rng(trial = 0L)  // First trial
   * val sample2 = rng(trial = 1L)  // Second trial
   * }}}
   */
  def createGenerator(
    entityId: Long,
    varId: Long,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Long => Double = {
    (trial: Long) => HDR.generate(trial, entityId, varId, seed3, seed4)
  }
  
  /**
   * Direct access to HDR generation for backward compatibility.
   * 
   * Delegates to simulation-util's HDR.generate without currying.
   * Use createGenerator for more ergonomic repeated sampling.
   * 
   * @param counter Trial or iteration counter
   * @param entityId Entity identifier
   * @param varId Variable identifier
   * @param seed3 Additional seed (default 0L)
   * @param seed4 Additional seed (default 0L)
   * @return Uniform random value in [0, 1)
   */
  def generate(
    counter: Long,
    entityId: Long,
    varId: Long,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Double = {
    HDR.generate(counter, entityId, varId, seed3, seed4)
  }
}
