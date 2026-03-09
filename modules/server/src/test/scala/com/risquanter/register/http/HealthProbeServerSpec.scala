package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import zio.http.{Request, URL, Method, Status}

/** Unit tests for the dedicated health-probe server endpoints.
  *
  * Validates FR-2 (exactly `/health` and `/ready`), FR-3 (404 for other paths),
  * and NFR-2 (no interceptors) by exercising `HealthProbeServer.endpoints`
  * through a bare `ZioHttpInterpreter` with default options — the same
  * configuration used in production.
  */
object HealthProbeServerSpec extends ZIOSpecDefault:

  // Use the production httpApp directly — no interpreter reconstruction.
  // This guarantees the test exercises the exact same code path as serve().
  private val httpApp = HealthProbeServer.httpApp

  private def makeRequest(path: String): Request =
    Request(method = Method.GET, url = URL.decode(path).toOption.get)

  override def spec = suite("HealthProbeServer")(

    suite("/health endpoint (FR-2)")(
      test("returns 200 OK") {
        for
          response <- httpApp.runZIO(makeRequest("/health"))
        yield assertTrue(response.status == Status.Ok)
      },

      test("returns expected JSON body") {
        for
          response <- httpApp.runZIO(makeRequest("/health"))
          body     <- response.body.asString
          parsed    = body.fromJson[Map[String, String]]
        yield assertTrue(
          parsed == Right(Map("status" -> "healthy", "service" -> "risk-register"))
        )
      }
    ),

    suite("/ready endpoint (FR-2)")(
      test("returns 200 OK") {
        for
          response <- httpApp.runZIO(makeRequest("/ready"))
        yield assertTrue(response.status == Status.Ok)
      },

      test("returns expected JSON body") {
        for
          response <- httpApp.runZIO(makeRequest("/ready"))
          body     <- response.body.asString
          parsed    = body.fromJson[Map[String, String]]
        yield assertTrue(
          parsed == Right(Map("status" -> "ready", "service" -> "risk-register"))
        )
      }
    ),

    suite("Unknown paths return 404 (FR-3)")(
      test("GET /unknown returns 404") {
        for
          response <- httpApp.runZIO(makeRequest("/unknown"))
        yield assertTrue(response.status == Status.NotFound)
      },

      test("GET /api/health returns 404") {
        for
          response <- httpApp.runZIO(makeRequest("/api/health"))
        yield assertTrue(response.status == Status.NotFound)
      },

      test("GET / returns 404") {
        for
          response <- httpApp.runZIO(makeRequest("/"))
        yield assertTrue(response.status == Status.NotFound)
      }
    ),

    suite("No interceptors (NFR-2)")(
      test("response has no security headers") {
        for
          response <- httpApp.runZIO(makeRequest("/health"))
        yield assertTrue(
          response.headers.get("X-Frame-Options").isEmpty,
          response.headers.get("Content-Security-Policy").isEmpty,
          response.headers.get("Strict-Transport-Security").isEmpty
        )
      }
    )
  )
