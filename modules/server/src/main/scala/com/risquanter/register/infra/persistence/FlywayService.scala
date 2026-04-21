package com.risquanter.register.infra.persistence

import zio.*
import org.flywaydb.core.Flyway

import com.risquanter.register.configs.FlywayConfig

trait FlywayService:
  def runMigrations: Task[Unit]

object FlywayService:
  val noOp: FlywayService = new FlywayService:
    override def runMigrations: Task[Unit] = ZIO.unit

final class FlywayServiceLive(config: FlywayConfig) extends FlywayService:
  override def runMigrations: Task[Unit] =
    ZIO.attempt {
      Flyway
        .configure()
        .dataSource(config.url, config.user, config.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    }.unit

object FlywayServiceLive:
  val layer: ZLayer[FlywayConfig, Nothing, FlywayService] =
    ZLayer.fromFunction(FlywayServiceLive(_))