package com.risquanter.register.infra.persistence

import zio.*
import javax.sql.DataSource
import io.getquill.*
import io.getquill.jdbczio.Quill

object Repository:
  val dataSourceLayer: ZLayer[Any, Throwable, DataSource] =
    Quill.DataSource.fromPrefix("register.db")

  val quillLayer: ZLayer[DataSource, Throwable, Quill.Postgres[SnakeCase]] =
    Quill.Postgres.fromNamingStrategy(SnakeCase)

  val dataLayer: ZLayer[Any, Throwable, Quill.Postgres[SnakeCase]] =
    dataSourceLayer >>> quillLayer
