package com.risquanter.register.infra.persistence

import javax.sql.DataSource

import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import zio.*

trait RepositorySpec:

  val dataSourceLayer: ZLayer[Scope, Throwable, DataSource] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        startComposePostgres
      )(_ => stopComposePostgres).flatMap { _ =>
        val ds = new PGSimpleDataSource()
        ds.setServerNames(Array("localhost"))
        ds.setPortNumbers(Array(5432))
        ds.setDatabaseName("register")
        ds.setUser("register_app")
        ds.setPassword("dev-only-password")

        for
          _ <- awaitDatabase(ds)
          _ <- migrate(ds)
          _ <- truncateTables(ds)
        yield ds
      }
    }

  val quillLayer: ZLayer[Scope, Throwable, Quill.Postgres[SnakeCase]] =
    dataSourceLayer >>> Quill.Postgres.fromNamingStrategy(SnakeCase)

  private def startComposePostgres: Task[Unit] =
    runCommand("docker", "compose", "--profile", "persistence", "up", "-d", "postgres")

  private def stopComposePostgres: UIO[Unit] =
    runCommand("docker", "compose", "--profile", "persistence", "stop", "postgres").ignoreLogged

  private def awaitDatabase(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      var remainingAttempts = 30
      var lastError: Throwable | Null = null
      var connected = false
      while remainingAttempts > 0 && !connected do
        try
          val conn = ds.getConnection()
          try
            val stmt = conn.createStatement()
            try
              stmt.execute("SELECT 1")
              connected = true
            finally stmt.close()
          finally conn.close()
        catch
          case err: Throwable =>
            lastError = err
            remainingAttempts -= 1
            if remainingAttempts == 0 then throw err
            Thread.sleep(1000)
      if !connected then throw lastError.nn
    }

  private def migrate(ds: DataSource): Task[Unit] =
    ZIO.attempt {
      Flyway
        .configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    }.unit

  private def truncateTables(ds: DataSource): Task[Unit] =
    ZIO.attemptBlocking {
      val conn = ds.getConnection()
      try
        val stmt = conn.createStatement()
        try stmt.execute("TRUNCATE TABLE workspace_trees, workspaces RESTART IDENTITY CASCADE")
        finally stmt.close()
      finally conn.close()
    }.unit

  private def runCommand(command: String*): Task[Unit] =
    ZIO.attemptBlocking {
      val process = new ProcessBuilder(command*).inheritIO().start()
      val exitCode = process.waitFor()
      if exitCode != 0 then
        throw new RuntimeException(s"Command failed (${command.mkString(" ")}): exit=$exitCode")
    }.unit