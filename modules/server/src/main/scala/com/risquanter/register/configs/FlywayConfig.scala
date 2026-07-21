package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Flyway bootstrap configuration. */
final case class FlywayConfig(
  url: String,
  user: String,
  password: Config.Secret,
  migrationsLocation: String
)

object FlywayConfig:
  private val secretConfig: Config[Config.Secret] =
    Config.string.mapOrFail(value => Right(Config.Secret(value)))

  given DeriveConfig[Config.Secret] = DeriveConfig(secretConfig)

  val layer: ZLayer[Any, Throwable, FlywayConfig] =
    Configs.makeLayer[FlywayConfig]("register.flyway")