package com.risquanter.register.configs

import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.errors.ValidationError

/** Simulation execution configuration.
  *
  * @param defaultNTrials Default number of Monte Carlo trials per simulation
  * @param maxTreeDepth Maximum allowed depth for risk tree hierarchy
  * @param defaultParallelism Default ZIO fiber parallelism for tree traversal
  * @param maxConcurrentSimulations Maximum concurrent simulations (semaphore permits)
  * @param maxNTrials Hard limit on trials per simulation (reject if exceeded)
  * @param maxParallelism Hard limit on parallelism per simulation (reject if exceeded)
  * @param defaultSeed3 Global seed 3 for HDR random number generation (reproducibility)
  * @param defaultSeed4 Global seed 4 for HDR random number generation (reproducibility)
  */
final case class SimulationConfig(
  defaultNTrials: PositiveInt,
  maxTreeDepth: NonNegativeInt,
  defaultTrialParallelism: PositiveInt,
  maxConcurrentSimulations: PositiveInt,
  maxNTrials: PositiveInt,
  maxParallelism: PositiveInt,
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

  private val nonNegativeIntConfig: Config[NonNegativeInt] =
    deriveConfig[Int].mapOrFail { value =>
      ValidationUtil.refineNonNegativeInt(value).left.map(errorsToString)
    }

  given DeriveConfig[PositiveInt] = DeriveConfig(positiveIntConfig)
  given DeriveConfig[NonNegativeInt] = DeriveConfig(nonNegativeIntConfig)
  given DeriveConfig[SimulationConfig] = DeriveConfig.derived
}
