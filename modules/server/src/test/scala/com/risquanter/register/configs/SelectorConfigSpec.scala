package com.risquanter.register.configs

import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*
import zio.test.Assertion.*

object SelectorConfigSpec extends ZIOSpecDefault {

  private def withConfig[R, E, A](entries: (String, String)*)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect.withConfigProvider(
      ConfigProvider.fromMap(Map.from(entries))
    )

  private val repositoryTypeConfig =
    deriveConfig[RepositoryType].nested("register", "repository", "repositoryType")

  private val workspaceStoreBackendConfig =
    deriveConfig[WorkspaceStoreBackend].nested("register", "workspaceStore", "backend")

  private val authModeConfig =
    deriveConfig[AuthMode].nested("register", "auth", "mode")

  def spec = suite("SelectorConfig")(
    test("loads RepositoryConfig with in-memory selector") {
      withConfig("register.repository.repositoryType" -> "in-memory") {
        ZIO.config(repositoryTypeConfig).map(value => assertTrue(value == RepositoryType.InMemory))
      }
    },
    test("loads WorkspaceStoreConfig with postgres selector") {
      withConfig("register.workspaceStore.backend" -> "postgres") {
        ZIO.config(workspaceStoreBackendConfig).map(value => assertTrue(value == WorkspaceStoreBackend.Postgres))
      }
    },
    test("loads AuthConfig with capability-only selector") {
      withConfig("register.auth.mode" -> "capability-only") {
        ZIO.config(authModeConfig).map(value => assertTrue(value == AuthMode.CapabilityOnly))
      }
    },
    test("rejects invalid repository selector") {
      withConfig("register.repository.repositoryType" -> "bogus") {
        ZIO.config(repositoryTypeConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects invalid workspace store selector") {
      withConfig("register.workspaceStore.backend" -> "bogus") {
        ZIO.config(workspaceStoreBackendConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects invalid auth mode selector") {
      withConfig("register.auth.mode" -> "bogus") {
        ZIO.config(authModeConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    }
  )
}