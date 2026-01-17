package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

/**
  * Configuration for Irmin GraphQL client.
  *
  * Loaded from application.conf under `register.irmin` path.
  *
  * @param endpoint Base URL for Irmin GraphQL endpoint (e.g., "http://localhost:9080")
  * @param branch Default branch name for operations (defaults to "main")
  * @param timeoutSeconds Request timeout in seconds
  */
final case class IrminConfig(
    endpoint: String,
    branch: String = "main",
    timeoutSeconds: Int = 30
) {
  /** Full GraphQL endpoint URL */
  def graphqlUrl: String = s"$endpoint/graphql"
  
  /** Request timeout as Duration */
  def timeout: Duration = timeoutSeconds.seconds
}

object IrminConfig {
  /** ZLayer providing IrminConfig from application.conf */
  val layer: ZLayer[Any, Throwable, IrminConfig] =
    Configs.makeLayer[IrminConfig]("register.irmin")
}
