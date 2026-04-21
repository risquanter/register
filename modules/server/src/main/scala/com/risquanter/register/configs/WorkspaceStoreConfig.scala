package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

enum WorkspaceStoreBackend:
  case InMemory, Postgres

object WorkspaceStoreBackend:
  private val config: Config[WorkspaceStoreBackend] =
    Config.string.mapOrFail {
      case "in-memory" => Right(WorkspaceStoreBackend.InMemory)
      case "postgres"  => Right(WorkspaceStoreBackend.Postgres)
      case other =>
        Left(Config.Error.InvalidData(message = s"Invalid workspaceStore.backend: '$other'. Expected one of: in-memory, postgres"))
    }

  given DeriveConfig[WorkspaceStoreBackend] = DeriveConfig(config)

/** Workspace-store backend selection configuration. */
final case class WorkspaceStoreConfig(
  backend: WorkspaceStoreBackend = WorkspaceStoreBackend.InMemory
)

object WorkspaceStoreConfig:
  val layer: ZLayer[Any, Throwable, WorkspaceStoreConfig] =
    Configs.makeLayer[WorkspaceStoreConfig]("register.workspaceStore")
