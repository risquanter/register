package com.risquanter.register.testcontainers

import java.io.File
import java.util.UUID
import scala.sys.process.*

import zio.*

import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.domain.data.iron.SafeUrl

/**
 * Starts the repo's docker-compose (persistence profile) via local docker compose CLI and exposes IRMIN_URL.
 * Uses a unique compose project name per run to isolate state (volumes/networks) between tests.
 * 
 * This avoids Testcontainers' Docker client which has API version negotiation issues with newer Docker daemons.
 */
object IrminCompose:

  final case class Resource(irminUrl: String, projectName: String)

  /** Scoped layer that starts compose and stops it on release. */
  val layer: ZLayer[Any, Throwable, Resource] = ZLayer.scoped(acquire)

  /** Convenience layer that also builds an IrminConfig from the running compose. */
  val irminConfigLayer: ZLayer[Any, Throwable, IrminConfig] =
    layer >>> ZLayer.fromZIO(
      ZIO.serviceWithZIO[Resource] { res =>
        ZIO.fromEither(SafeUrl.fromString(res.irminUrl).left.map(errs => new RuntimeException(errs.map(_.message).mkString("; "))))
          .map(url => IrminConfig(url = url))
      }
    )

  private def acquire: ZIO[Scope, Throwable, Resource] =
    ZIO.acquireRelease(start)(started => stop(started.projectName, started.composeFile).orDie)
      .map(started => Resource(started.irminUrl, started.projectName))

  private final case class Started(irminUrl: String, projectName: String, composeFile: File)

  private def findComposeFile(): File =
    List(
      "docker-compose.yml",
      "../docker-compose.yml",
      "../../docker-compose.yml"
    ).map(path => new File(path)).find(_.exists()).getOrElse {
      throw new IllegalStateException("docker-compose.yml not found (looked in ., .., ../..)")
    }

  private def start: Task[Started] =
    for
      composeFile <- ZIO.attempt(findComposeFile())
      projectName <- ZIO.attempt(s"register_it_${UUID.randomUUID().toString.take(8)}")
      composeDir   = composeFile.getParentFile
      _           <- ZIO.logInfo(s"[IrminCompose] Starting docker compose project=$projectName from ${composeFile.getAbsolutePath}")

      // Defensive: remove any left-over named container from previous runs (compose uses container_name irmin-graphql)
      _ <- ZIO.attemptBlocking(Process(Seq("docker", "rm", "-f", "irmin-graphql"), composeDir).!).ignore

      startResult <- ZIO.attemptBlocking {
        // Start only the irmin service (not register-server which would conflict with existing containers)
        val startCmd = Seq(
          "docker", "compose",
          "-f", composeFile.getAbsolutePath,
          "-p", projectName,
          "--profile", "persistence",
          "up", "-d", "--build", "--wait",
          "irmin"  // Only start the irmin service
        )

        Process(startCmd, composeDir).!
      }
      _ <- ZIO.fail(new RuntimeException(s"docker compose up failed with exit code $startResult")).when(startResult != 0)

      portOutput <- ZIO.attemptBlocking {
        // Get the mapped port for irmin service
        val portCmd = Seq(
          "docker", "compose",
          "-f", composeFile.getAbsolutePath,
          "-p", projectName,
          "port", "irmin", "8080"
        )

        Process(portCmd, composeDir).!!.trim
      }
      // Output format is like "0.0.0.0:32768" or "[::]:32768"
      port     <- ZIO.attempt(portOutput.split(":").last.toInt)
      irminUrl  = s"http://localhost:$port"
      _        <- ZIO.logInfo(s"[IrminCompose] Irmin running at $irminUrl")
    yield Started(irminUrl, projectName, composeFile)

  private def stop(projectName: String, composeFile: File): Task[Unit] =
    ZIO.logInfo(s"[IrminCompose] Stopping docker compose project=$projectName") *>
      ZIO.attemptBlocking {
        val stopCmd = Seq(
          "docker", "compose",
          "-f", composeFile.getAbsolutePath,
          "-p", projectName,
          "down", "-v", "--remove-orphans"
        )

        val composeDir = composeFile.getParentFile
        Process(stopCmd, composeDir).!
        ()
      }

end IrminCompose
