package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

enum RepositoryType:
  case InMemory, Irmin

object RepositoryType:
  private val config: Config[RepositoryType] =
    Config.string.mapOrFail {
      case "in-memory" => Right(RepositoryType.InMemory)
      case "irmin"     => Right(RepositoryType.Irmin)
      case other =>
        Left(Config.Error.InvalidData(message = s"Invalid repositoryType: '$other'. Expected one of: in-memory, irmin"))
    }

  given DeriveConfig[RepositoryType] = DeriveConfig(config)

/** Repository selection configuration. */
final case class RepositoryConfig(
  repositoryType: RepositoryType = RepositoryType.InMemory
)

object RepositoryConfig {
  val layer: ZLayer[Any, Throwable, RepositoryConfig] =
    Configs.makeLayer[RepositoryConfig]("register.repository")
}
