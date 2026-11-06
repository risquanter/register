package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.json.*
import zio.http.{Request, URL, Method, Status}

import com.risquanter.register.http.responses.{ProbeResponse, ProbeStatus}

/** Unit tests for the dedicated health-probe server endpoints.
  *
  * The probe server answers exactly `/health` and `/ready`, answers 404 for
  * every other path, and runs no interceptors. The tests exercise
  * `HealthProbeServer.endpoints` through a bare `ZioHttpInterpreter` with
  * default options, the same configuration used in production.
  */
object HealthProbeServerSpec extends ZIOSpecDefault:

  // Use the production httpApp directly — no interpreter reconstruction.
  // This guarantees the test exercises the exact same code path as serve().
  private val httpApp = HealthProbeServer.httpApp

  private def makeRequest(path: String): Request =
    Request(method = Method.GET, url = URL.decode(path).toOption.get)

  override def spec = suite("HealthProbeServer")(

    suite("/health endpoint")(
      test("returns 200 OK") {
        for
          response <- httpApp.runZIO(makeRequest("/health"))
        yield assertTrue(response.status == Status.Ok)
      },

      test("returns expected JSON body") {
        for
          response <- httpApp.runZIO(makeRequest("/health"))
          body     <- response.body.asString
          parsed    = body.fromJson[ProbeResponse]
        yield assertTrue(
          parsed == Right(ProbeResponse(ProbeStatus.Healthy, ProbeResponses.ServiceName))
        )
      }
    ),

    suite("/ready endpoint")(
      test("returns 200 OK") {
        for
          response <- httpApp.runZIO(makeRequest("/ready"))
        yield assertTrue(response.status == Status.Ok)
      },

      test("returns expected JSON body") {
        for
          response <- httpApp.runZIO(makeRequest("/ready"))
          body     <- response.body.asString
          parsed    = body.fromJson[ProbeResponse]
        yield assertTrue(
          parsed == Right(ProbeResponse(ProbeStatus.Ready, ProbeResponses.ServiceName))
        )
      }
    ),

    suite("Unknown paths return 404")(
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

    suite("No interceptors")(
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
