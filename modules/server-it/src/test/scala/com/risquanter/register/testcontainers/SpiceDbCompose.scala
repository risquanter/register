package com.risquanter.register.testcontainers

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import scala.sys.process.*

import zio.*

import com.risquanter.register.configs.{SpiceDbConfig, SpiceDbConsistency, SpiceDbToken}
import com.risquanter.register.domain.data.iron.{IronConstants, MeshServiceUrl}

/**
 * Starts the repo's docker-compose (authz profile) via local docker compose CLI and
 * exposes a SpiceDB REST endpoint for integration tests.
 *
 * Uses a unique compose project name per run to isolate state between test suites.
 * SpiceDB is started with an in-memory datastore and a fixed preshared key.
 *
 * On acquisition:
 *   1. Starts the `spicedb` service (authz profile) and waits for it to become healthy.
 *   2. Writes `infra/spicedb/schema.zed` via the REST API.
 *   3. Seeds the standard test relationships (alice/carol on ws1, alice on ws2, tree1 ↔ ws1).
 *
 * On release (scope end): tears down the compose project and removes volumes.
 *
 * Test-data constants (aliceUuid, ws1Id, etc.) are public so the integration test spec
 * can reference them without duplicating literals.
 */
object SpiceDbCompose:

  // ─── Test data ─────────────────────────────────────────────────────────────

  /** Bearer token — must match --grpc-preshared-key in docker-compose.server-it.yml */
  val TestToken = "test-spicedb-token"

  val aliceUuid:    String = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  val bobUuid:      String = "b0b0b0b0-b0b0-4b0b-a0b0-b0b0b0b0b0b0"
  val carolUuid:    String = "ca10ca10-ca10-4a10-aa10-ca10ca10ca10"
  val sentinelUuid: String = "00000000-0000-0000-0000-000000000000"

  val ws1Id:   String = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
  val ws2Id:   String = "01BX5ZZKBKACTAV9WEVGEMMVRZ"
  val ws3Id:   String = "01C3NDEQTSV4RRFFQ69G5FAVZZ"
  val tree1Id: String = "01JTZKNYYR3NKZJK2XYVQKTHB8"

  // ─── Resource type ─────────────────────────────────────────────────────────

  final case class Resource(
    baseUrl:     String,
    token:       String,
    composeFile: File,
    projectName: String
  )

  // ─── Public layers ──────────────────────────────────────────────────────────

  /** Scoped layer: starts SpiceDB (with schema + seed data) and stops it on release. */
  val layer: ZLayer[Any, Throwable, Resource] = ZLayer.scoped(acquire)

  /** Derives a SpiceDbConfig from the running instance.
    *
    * Uses FullyConsistent so integration tests never hit the NewEnemy consistency
    * window — critical for T-S10 which writes a relationship then immediately reads it.
    */
  val configLayer: ZLayer[Resource, Throwable, SpiceDbConfig] =
    ZLayer.fromZIO(
      ZIO.serviceWithZIO[Resource] { res =>
        for
          url   <- ZIO.fromEither(
                     MeshServiceUrl
                       .fromString(res.baseUrl)
                       .left.map(errs => new RuntimeException(errs.map(_.message).mkString("; ")))
                   )
          token <- ZIO.fromEither(
                     SpiceDbToken
                       .fromString(res.token)
                       .left.map(errs => new RuntimeException(errs.map(_.message).mkString("; ")))
                   )
        yield SpiceDbConfig(
          url            = url,
          token          = token,
          consistency    = SpiceDbConsistency.FullyConsistent,
          timeoutSeconds = IronConstants.Ten
        )
      }
    )

  // ─── Lifecycle ─────────────────────────────────────────────────────────────

  private def acquire: ZIO[Scope, Throwable, Resource] =
    ZIO.acquireRelease(start)(res => stop(res.projectName, res.composeFile).orDie)

  private def start: Task[Resource] =
    for
      composeFile <- ZIO.attempt(findComposeFile())
      schemaFile  <- ZIO.attempt(findSchemaFile())
      projectName <- ZIO.attempt(s"register_it_authz_${UUID.randomUUID().toString.take(8)}")
      composeDir   = composeFile.getParentFile
      _           <- ZIO.logInfo(s"[SpiceDbCompose] Starting project=$projectName from ${composeFile.getAbsolutePath}")

      rc          <- ZIO.attemptBlocking {
                       val cmd = Seq(
                         "docker", "compose",
                         "-f", composeFile.getAbsolutePath,
                         "-p", projectName,
                         "--profile", "authz",
                         "up", "-d", "--wait", "--no-build",
                         "spicedb"
                       )
                       Process(cmd, composeDir).!
                     }
      _           <- ZIO.fail(new RuntimeException(s"docker compose up failed with exit code $rc")).when(rc != 0)

      portOutput  <- ZIO.attemptBlocking {
                       val cmd = Seq(
                         "docker", "compose",
                         "-f", composeFile.getAbsolutePath,
                         "-p", projectName,
                         "port", "spicedb", "8080"
                       )
                       Process(cmd, composeDir).!!.trim
                     }
      port        <- ZIO.attempt(portOutput.split(":").last.toInt)
      baseUrl      = s"http://localhost:$port"
      _           <- ZIO.logInfo(s"[SpiceDbCompose] SpiceDB running at $baseUrl")

      schema      <- ZIO.attemptBlocking(
                       new String(Files.readAllBytes(schemaFile.toPath), StandardCharsets.UTF_8)
                     )
      _           <- postJson(baseUrl, "/v1/schema/write", schemaJson(schema))
      _           <- ZIO.logInfo("[SpiceDbCompose] Schema applied")

      _           <- postJson(baseUrl, "/v1/relationships/write", seedRelationshipsBody)
      _           <- ZIO.logInfo("[SpiceDbCompose] Test relationships seeded")
    yield Resource(baseUrl, TestToken, composeFile, projectName)

  private def stop(projectName: String, composeFile: File): Task[Unit] =
    ZIO.logInfo(s"[SpiceDbCompose] Stopping project=$projectName") *>
      ZIO.attemptBlocking {
        val cmd = Seq(
          "docker", "compose",
          "-f", composeFile.getAbsolutePath,
          "-p", projectName,
          "down", "-v", "--remove-orphans"
        )
        Process(cmd, composeFile.getParentFile).!
        ()
      }

  // ─── File discovery ─────────────────────────────────────────────────────────

  private def findComposeFile(): File =
    List(
      "docker-compose.server-it.yml",
      "../docker-compose.server-it.yml",
      "../../docker-compose.server-it.yml"
    ).map(new File(_)).find(_.exists()).getOrElse(
      throw new IllegalStateException(
        "docker-compose.server-it.yml not found (looked in ., .., ../..)"
      )
    )

  private def findSchemaFile(): File =
    List(
      "infra/spicedb/schema.zed",
      "../infra/spicedb/schema.zed",
      "../../infra/spicedb/schema.zed"
    ).map(new File(_)).find(_.exists()).getOrElse(
      throw new IllegalStateException(
        "infra/spicedb/schema.zed not found (looked in ., .., ../..)"
      )
    )

  // ─── HTTP API helpers ───────────────────────────────────────────────────────

  private def postJson(baseUrl: String, path: String, body: String): Task[Unit] =
    ZIO.attemptBlocking {
      val client = HttpClient.newHttpClient()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(s"$baseUrl$path"))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer $TestToken")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() / 100 != 2 then
        throw new RuntimeException(
          s"SpiceDB $path returned HTTP ${resp.statusCode()}: ${resp.body()}"
        )
    }

  // Escapes a raw schema string for embedding in JSON (no zio-json dependency here).
  private def schemaJson(schema: String): String =
    val escaped = schema
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s"""{"schema":"$escaped"}"""

  // Produces a single TOUCH update object for a relationship tuple.
  private def touch(
    resType: String, resId: String,
    relation: String,
    subType: String, subId: String
  ): String =
    s"""{"operation":"OPERATION_TOUCH","relationship":{"resource":{"objectType":"$resType","objectId":"$resId"},"relation":"$relation","subject":{"object":{"objectType":"$subType","objectId":"$subId"}}}}"""

  // Pre-seeded state used by T-S1 through T-S9:
  //   workspace:ws1#owner_user@user:alice  (T-S1, T-S4, T-S7)
  //   workspace:ws1#viewer@user:carol      (T-S3)
  //   workspace:ws2#viewer@user:alice      (T-S7 — alice views both ws1 + ws2)
  //   risk_tree:tree1#workspace@workspace:ws1  (T-S4 — inheritance path)
  //
  // bob has no relationships (T-S2, T-S8 negative tests).
  // sentinelUuid has no relationships (T-S9 guard).
  // ws3 is intentionally absent (T-S10 fresh-write precondition).
  private val seedRelationshipsBody: String =
    val updates = List(
      touch("workspace", ws1Id,   "owner_user", "user",      aliceUuid),
      touch("workspace", ws1Id,   "viewer",     "user",      carolUuid),
      touch("workspace", ws2Id,   "viewer",     "user",      aliceUuid),
      touch("risk_tree", tree1Id, "workspace",  "workspace", ws1Id)
    )
    s"""{"updates":[${updates.mkString(",")}]}"""

end SpiceDbCompose
