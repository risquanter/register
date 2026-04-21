package com.risquanter.register.configs

import zio.*
import zio.config.magnolia.*

/** Flyway bootstrap configuration. */
final case class FlywayConfig(
  url: String,
  user: String,
  password: String
)

object FlywayConfig:
  val layer: ZLayer[Any, Throwable, FlywayConfig] =
    Configs.makeLayer[FlywayConfig]("register.flyway")