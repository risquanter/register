package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*
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
  * @param healthCheckRetries Number of retries for startup health check
  */
final case class IrminConfig(
  url: SafeUrl,
    branch: String = "main",
    timeoutSeconds: Int = 30,
    healthCheckTimeoutMillis: Int = 5000,
    healthCheckRetries: Int = 2
) {
  /** Full GraphQL endpoint URL */
  def graphqlUrl: String = s"$url/graphql"
  
  /** Request timeout as Duration */
  def timeout: Duration = timeoutSeconds.seconds

  /** Health check timeout as Duration */
  def healthCheckTimeout: Duration = healthCheckTimeoutMillis.millis
}

object IrminConfig {
  given zio.Config[SafeUrl] =
    zio.Config.string.mapOrFail { s =>
      SafeUrl
        .fromString(s, "register.irmin.url")
        .left
        .map(errs => zio.Config.Error.InvalidData(zio.Chunk("register", "irmin", "url"), errs.map(_.message).mkString("; ")))
    }

  /** ZLayer providing IrminConfig from application.conf */
  val layer: ZLayer[Any, Throwable, IrminConfig] =
    Configs.makeLayer[IrminConfig]("register.irmin")
}
