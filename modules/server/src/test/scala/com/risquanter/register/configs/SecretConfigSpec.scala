package com.risquanter.register.configs

import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*

object SecretConfigSpec extends ZIOSpecDefault {

  private def withConfig[R, E, A](entries: (String, String)*)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect.withConfigProvider(
      ConfigProvider.fromMap(Map.from(entries))
    )

  private val flywayConfig =
    deriveConfig[FlywayConfig].nested("register", "flyway")

  private val postgresDataSourceConfig =
    deriveConfig[PostgresDataSourceConfig].nested("register", "db", "dataSource")

  def spec = suite("SecretConfig")(
    test("loads FlywayConfig password as Config.Secret") {
      withConfig(
        "register.flyway.url" -> "jdbc:postgresql://localhost:5432/register",
        "register.flyway.user" -> "register",
        "register.flyway.password" -> "super-secret"
      ) {
        ZIO.config(flywayConfig).map { config =>
          assertTrue(
            config.password.stringValue == "super-secret",
            !config.password.toString.contains("super-secret")
          )
        }
      }
    },
    test("loads PostgresDataSourceConfig password as Config.Secret") {
      withConfig(
        "register.db.dataSource.user" -> "register",
        "register.db.dataSource.password" -> "super-secret",
        "register.db.dataSource.databaseName" -> "register",
        "register.db.dataSource.portNumber" -> "5432",
        "register.db.dataSource.serverName" -> "localhost"
      ) {
        ZIO.config(postgresDataSourceConfig).map { config =>
          assertTrue(
            config.password.stringValue == "super-secret",
            !config.password.toString.contains("super-secret")
          )
        }
      }
    }
  )
}