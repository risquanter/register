package com.risquanter.register.configs

import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.errors.ValidationError

/** Simulation execution configuration.
  *
  * Every field here is read by running code. A value that controls nothing does
  * not belong in this class: it reads as an enforced limit and is not one.
  *
  * @param defaultNTrials Default number of Monte Carlo trials per simulation
  * @param defaultTrialParallelism Trial-batch parallelism within one leaf simulation.
  *   This bounds fibers per leaf, not per request: the resolver simulates sibling
  *   leaves concurrently (ZIO.foreachPar), so one request may hold up to
  *   (leaves in flight) × defaultTrialParallelism runnable fibers. Actual CPU
  *   concurrency stays capped by the ZIO runtime thread pool (core count).
  * @param maxConcurrentSimulations How many risk nodes of one request resolve
  *   concurrently. Read by `Simulator.simulate` only; the live resolver path does
  *   not yet apply it, so its fan-out across sibling nodes is unbounded.
  * @param defaultSeed3 Global seed 3 for HDR random number generation (reproducibility)
  * @param defaultSeed4 Global seed 4 for HDR random number generation (reproducibility)
  */
final case class SimulationConfig(
  defaultNTrials: PositiveInt,
  defaultTrialParallelism: PositiveInt,
  maxConcurrentSimulations: PositiveInt,
  defaultSeed3: Long,
  defaultSeed4: Long
)

object SimulationConfig {
  private def errorsToString(errs: List[ValidationError]): Config.Error =
    Config.Error.InvalidData(message = errs.map(_.message).mkString("; "))

  private val positiveIntConfig: Config[PositiveInt] =
    deriveConfig[Int].mapOrFail { value =>
      ValidationUtil.refinePositiveInt(value).left.map(errorsToString)
    }

  given DeriveConfig[PositiveInt] = DeriveConfig(positiveIntConfig)
  given DeriveConfig[SimulationConfig] = DeriveConfig.derived
}
