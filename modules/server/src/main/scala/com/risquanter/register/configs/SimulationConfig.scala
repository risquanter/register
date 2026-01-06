package com.risquanter.register.configs

/** Simulation execution configuration */
final case class SimulationConfig(
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int
)
