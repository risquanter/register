package com.risquanter.register.infra.persistence

import javax.sql.DataSource

import io.getquill.*
import io.getquill.jdbczio.Quill
import org.postgresql.ds.PGSimpleDataSource
import zio.*

import com.risquanter.register.configs.PostgresDataSourceConfig

object Repository:
  val dataSourceLayer: ZLayer[PostgresDataSourceConfig, Throwable, DataSource] =
    ZLayer.fromZIO {
      ZIO.service[PostgresDataSourceConfig].map { config =>
        val dataSource = new PGSimpleDataSource()
        dataSource.setServerNames(Array(config.serverName))
        dataSource.setPortNumbers(Array(config.portNumber))
        dataSource.setDatabaseName(config.databaseName)
        dataSource.setUser(config.user)
        dataSource.setPassword(config.password.stringValue)
        dataSource: DataSource
      }
    }

  val quillLayer: ZLayer[DataSource, Throwable, Quill.Postgres[SnakeCase]] =
    Quill.Postgres.fromNamingStrategy(SnakeCase)

  val dataLayer: ZLayer[PostgresDataSourceConfig, Throwable, Quill.Postgres[SnakeCase]] =
    dataSourceLayer >>> quillLayer
