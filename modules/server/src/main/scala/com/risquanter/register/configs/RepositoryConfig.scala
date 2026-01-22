package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Repository selection configuration. */
final case class RepositoryConfig(repositoryType: String = "in-memory") {
  def normalizedType: String = repositoryType.trim.toLowerCase
}

object RepositoryConfig {
  val layer: ZLayer[Any, Throwable, RepositoryConfig] =
    Configs.makeLayer[RepositoryConfig]("register.repository")
}
