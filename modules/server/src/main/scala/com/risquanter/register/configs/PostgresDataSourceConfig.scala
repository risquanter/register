package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

final case class PostgresDataSourceConfig(
  user: String,
  password: Config.Secret,
  databaseName: String,
  portNumber: Int,
  serverName: String
)

object PostgresDataSourceConfig:
  private val secretConfig: Config[Config.Secret] =
    Config.string.mapOrFail(value => Right(Config.Secret(value)))

  given DeriveConfig[Config.Secret] = DeriveConfig(secretConfig)

  val layer: ZLayer[Any, Throwable, PostgresDataSourceConfig] =
    Configs.makeLayer[PostgresDataSourceConfig]("register.db.dataSource")