package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import java.time.Duration
import com.risquanter.register.domain.data.iron.{BranchRef, Url}
import com.risquanter.register.domain.data.iron.Url.*

/**
  * Configuration for Irmin GraphQL client.
  *
  * Loaded from application.conf under `register.irmin` path. Duration fields use
  * HOCON duration syntax ("30s", "45s", "2m") — same convention as WorkspaceConfig.
  *
  * @param url Base URL for Irmin GraphQL endpoint (e.g., "http://localhost:9080")
  * @param branch Default branch name for operations (defaults to "main")
  * @param timeout Per-request timeout for GraphQL calls
  * @param healthCheckAttemptTimeout Per-attempt bound for the startup readiness probe;
  *                                  a hung probe counts as not-ready (ADR-031)
  * @param healthCheckBudget Total elapsed budget for the startup readiness gate;
  *                          the process fails closed once exceeded. Size above the
  *                          platform's policy-reconciliation window (ADR-031)
  */
final case class IrminConfig(
  url: Url.Url,
  branch: BranchRef = BranchRef.Main,
  timeout: Duration = Duration.ofSeconds(30),
  healthCheckAttemptTimeout: Duration = Duration.ofSeconds(5),
  healthCheckBudget: Duration = Duration.ofSeconds(45)
) {
  /** Full GraphQL endpoint URL */
  def graphqlUrl: String = s"${url.value}/graphql"
}

object IrminConfig {
  // Url config descriptor — reads a string and validates through Iron URL constraint.
  // IMPORTANT: Exposed as DeriveConfig[Url.Url], NOT Config[Url.Url].
  // A bare `given Config[Url.Url]` in this companion would leak through the
  // `deriveConfigFromConfig` bridge in zio-config-magnolia's package object,
  // causing Magnolia's `summonInline[DeriveConfig[String]]` to resolve the
  // URL-validating descriptor for ALL string fields (branch, etc.) — since
  // Url.Url erases to String at runtime.  This follows the same pattern as
  // SimulationConfig's `given DeriveConfig[PositiveInt]`.
  private val urlConfig: zio.Config[Url.Url] =
    zio.Config.string.mapOrFail { s =>
      Url
        .fromString(s, "url")
        .left
        .map(errs => zio.Config.Error.InvalidData(message = errs.map(_.message).mkString("; ")))
    }

  private val branchRefConfig: zio.Config[BranchRef] =
    zio.Config.string.mapOrFail { s =>
      BranchRef
        .fromString(s, "branch")
        .left
        .map(errs => zio.Config.Error.InvalidData(message = errs.map(_.message).mkString("; ")))
    }

  // All durations in this config are timeouts/budgets — zero or negative is a
  // config error surfaced at startup, not a runtime surprise (ADR-031 §4).
  private val positiveDurationConfig: zio.Config[Duration] =
    zio.Config.duration.mapOrFail { d =>
      Either.cond(
        !d.isZero && !d.isNegative,
        d,
        zio.Config.Error.InvalidData(message = s"Duration must be positive, got '$d'")
      )
    }

  given DeriveConfig[Url.Url] = DeriveConfig(urlConfig)
  given DeriveConfig[BranchRef] = DeriveConfig(branchRefConfig)
  given DeriveConfig[Duration] = DeriveConfig(positiveDurationConfig)
  given DeriveConfig[IrminConfig] = DeriveConfig.derived

  /** ZLayer providing IrminConfig from application.conf */
  val layer: ZLayer[Any, Throwable, IrminConfig] =
    Configs.makeLayer[IrminConfig]("register.irmin")
}
