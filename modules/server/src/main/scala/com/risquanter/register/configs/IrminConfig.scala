package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import com.risquanter.register.domain.data.iron.SafeUrl
import com.risquanter.register.domain.errors.ValidationError
import io.github.iltotore.iron.refineEither

/**
  * Configuration for Irmin GraphQL client.
  *
  * Loaded from application.conf under `register.irmin` path.
  *
  * @param url Base URL for Irmin GraphQL endpoint (e.g., "http://localhost:9080")
  * @param branch Default branch name for operations (defaults to "main")
  * @param timeoutSeconds Request timeout in seconds
  * @param healthCheckTimeoutMillis Max duration for startup health check
  * @param healthCheckRetries Number of retries for startup health check (default 0; bounded to non-negative)
  */
final case class IrminConfig(
  url: SafeUrl,
  branch: String = "main",
  timeoutSeconds: Int = 30,
  healthCheckTimeoutMillis: Int = 5000,
  healthCheckRetries: Int = 0
) {
  /** Full GraphQL endpoint URL */
  def graphqlUrl: String = s"$url/graphql"
  
  /** Request timeout as Duration */
  def timeout: Duration = timeoutSeconds.seconds

  /** Health check timeout as Duration */
  def healthCheckTimeout: Duration = healthCheckTimeoutMillis.millis
}

object IrminConfig {
  // SafeUrl config descriptor — reads a string and validates through Iron URL constraint.
  // IMPORTANT: Exposed as DeriveConfig[SafeUrl], NOT Config[SafeUrl].
  // A bare `given Config[SafeUrl]` in this companion would leak through the
  // `deriveConfigFromConfig` bridge in zio-config-magnolia's package object,
  // causing Magnolia's `summonInline[DeriveConfig[String]]` to resolve the
  // URL-validating descriptor for ALL string fields (branch, etc.) — since
  // SafeUrl erases to String at runtime.  This follows the same pattern as
  // SimulationConfig's `given DeriveConfig[PositiveInt]`.
  private val safeUrlConfig: zio.Config[SafeUrl] =
    zio.Config.string.mapOrFail { s =>
      SafeUrl
        .fromString(s, "url")
        .left
        .map(errs => zio.Config.Error.InvalidData(message = errs.map(_.message).mkString("; ")))
    }

  given DeriveConfig[SafeUrl] = DeriveConfig(safeUrlConfig)
  given DeriveConfig[IrminConfig] = DeriveConfig.derived

  /** ZLayer providing IrminConfig from application.conf */
  val layer: ZLayer[Any, Throwable, IrminConfig] =
    Configs.makeLayer[IrminConfig]("register.irmin")
}
