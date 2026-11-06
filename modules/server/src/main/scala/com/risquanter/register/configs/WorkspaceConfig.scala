package com.risquanter.register.configs

import java.time.Duration
import io.github.iltotore.iron.autoRefine
import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import com.risquanter.register.domain.data.iron.{PositiveInt, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ValidationError}

/** Workspace configuration.
  *
  * Controls workspace lifecycle parameters for free-tier and enterprise modes.
  *
  * @param cascadeDeleteParallelism How many deletes one cascade runs at once: per
  *   workspace in the reaper and the eviction route, per tree and per scenario
  *   inside each cascade
  */
final case class WorkspaceConfig(
  ttl: Duration = Duration.ofHours(72),
  idleTimeout: Duration = Duration.ofHours(1),
  reaperInterval: Duration = Duration.ofMinutes(5),
  maxTreesPerWorkspace: PositiveInt = 10,
  cascadeDeleteParallelism: PositiveInt
)

object WorkspaceConfig:
  private val positiveIntConfig: Config[PositiveInt] =
    deriveConfig[Int].mapOrFail { value =>
      ValidationUtil.refinePositiveInt(value, ErrorLocation.Whole).left.map(errs =>
        Config.Error.InvalidData(message = errs.map(ValidationError.describe).mkString("; ")))
    }

  given DeriveConfig[PositiveInt] = DeriveConfig(positiveIntConfig)
  given DeriveConfig[WorkspaceConfig] = DeriveConfig.derived
