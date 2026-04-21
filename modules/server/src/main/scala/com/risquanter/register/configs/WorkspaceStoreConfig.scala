package com.risquanter.register.configs

import zio.*

/** Workspace-store backend selection configuration. */
final case class WorkspaceStoreConfig(backend: String = "in-memory"):
  def normalizedBackend: String = backend.trim.toLowerCase

object WorkspaceStoreConfig:
  val layer: ZLayer[Any, Throwable, WorkspaceStoreConfig] =
    Configs.makeLayer[WorkspaceStoreConfig]("register.workspaceStore")
