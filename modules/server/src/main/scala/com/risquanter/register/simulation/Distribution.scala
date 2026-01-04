package com.risquanter.register.simulation

/** Common trait for probability distributions used in risk simulation.
  * 
  * Provides quantile-based interface for sampling from distributions.
  * Both parametric (Lognormal) and non-parametric (Metalog) distributions
  * implement this trait.
  * 
  * Used by RiskSampler to generate loss amounts from uniform random values
  * produced by HDR (Hashed Deterministic Random) generator.
  */
trait Distribution {
  
  /** Compute the quantile (inverse CDF) for a given probability.
    * 
    * @param p Probability in [0, 1]
    * @return The value x such that P(X ≤ x) = p
    */
  def quantile(p: Double): Double
  
  /** Sample from the distribution using a uniform random value.
    * 
    * Equivalent to quantile(uniform) - transforms uniform [0,1) random
    * value to a value from this distribution via inverse transform sampling.
    * 
    * @param uniform Uniform random value in [0, 1) from HDR or other PRNG
    * @return Sampled value from the distribution
    */
  def sample(uniform: Double): Double = quantile(uniform)
}
