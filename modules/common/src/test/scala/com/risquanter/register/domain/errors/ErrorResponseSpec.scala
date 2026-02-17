package com.risquanter.register.domain.errors

import scala.concurrent.duration.*
import java.time.{Instant, Duration as JDuration}

import zio.test.*
import zio.json.*
import sttp.model.StatusCode
import com.risquanter.register.domain.data.iron.WorkspaceKeySecret

object ErrorResponseSpec extends ZIOSpecDefault {

  def spec = suite("ErrorResponse")(
    suite("encode")(
      test("encodes ValidationFailed to BadRequest") {
        val error = ValidationFailed(List(
          ValidationError("name", ValidationErrorCode.REQUIRED_FIELD, "Name is required"),
          ValidationError("email", ValidationErrorCode.INVALID_FORMAT, "Email is invalid")
        ))
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.BadRequest,
          response.error.code == 400,
          response.error.errors.length == 2
        )
      },
      
      test("encodes RepositoryFailure to InternalServerError") {
        val error = RepositoryFailure("Database connection failed")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.InternalServerError,
          response.error.code == 500,
          response.error.errors.head.code == ValidationErrorCode.CONSTRAINT_VIOLATION,
          response.error.message == "Internal server error"
        )
      },

      test("encodes AccessDenied to Forbidden") {
        val error = AccessDenied("Endpoint disabled")
        val (status, response) = ErrorResponse.encode(error)

        assertTrue(
          status == StatusCode.Forbidden,
          response.error.code == 403,
          response.error.message == "Forbidden"
        )
      },

      test("encodes RateLimitExceeded to TooManyRequests") {
        val error = RateLimitExceeded("127.0.0.1", 5)
        val (status, response) = ErrorResponse.encode(error)

        assertTrue(
          status == StatusCode.TooManyRequests,
          response.error.code == 429,
          response.error.message == "Too many requests"
        )
      },

      test("encodes WorkspaceNotFound to opaque 404") {
        val key = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        val (status, response) = ErrorResponse.encode(WorkspaceNotFound(key))

        assertTrue(
          status == StatusCode.NotFound,
          response.error.code == 404,
          response.error.message == "Workspace not found"
        )
      },

      test("encodes WorkspaceExpired to same opaque 404 as not-found (A13)") {
        val key = WorkspaceKeySecret.fromString("ABCDEFGHIJKLMNOPQRSTUV").toOption.get
        val (status, response) = ErrorResponse.encode(
          WorkspaceExpired(key, Instant.now(), JDuration.ofHours(1))
        )

        assertTrue(
          status == StatusCode.NotFound,
          response.error.code == 404,
          response.error.message == "Workspace not found"
        )
      },
      
      test("encodes generic Exception to InternalServerError") {
        val error = new RuntimeException("Something went wrong")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.InternalServerError,
          response.error.code == 500
        )
      },
      
      test("encodes DataConflict to Conflict") {
        val error = DataConflict("Duplicate key value")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.Conflict,
          response.error.code == 409
        )
      },
      
      // Infrastructure errors (ADR-008)
      test("encodes IrminUnavailable to ServiceUnavailable (503)") {
        val error = IrminUnavailable("Connection refused")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.ServiceUnavailable,
          response.error.code == 503,
          response.error.errors.head.code == ValidationErrorCode.DEPENDENCY_FAILED,
          response.error.message.contains("unavailable")
        )
      },
      
      test("encodes NetworkTimeout to GatewayTimeout (504)") {
        val error = NetworkTimeout("getNode", 5.seconds)
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.GatewayTimeout,
          response.error.code == 504,
          response.error.errors.head.code == ValidationErrorCode.DEPENDENCY_FAILED,
          response.error.message.contains("timeout")
        )
      },

      test("encodes IrminHttpError without leaking upstream body") {
        val sensitiveBody = "upstream stacktrace: jdbc://user:password@host/db"
        val error = IrminHttpError(StatusCode.BadGateway, sensitiveBody)
        val (status, response) = ErrorResponse.encode(error)

        assertTrue(
          status == StatusCode.BadGateway,
          response.error.code == 502,
          response.error.message == "Upstream service error (HTTP 502)",
          !response.error.message.contains("password"),
          !response.error.message.contains("stacktrace")
        )
      },
      
      test("encodes VersionConflict to Conflict (409)") {
        val error = VersionConflict("node-123", "v1", "v2")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.Conflict,
          response.error.code == 409,
          response.error.message.contains("Version conflict"),
          response.error.message.contains("node-123")
        )
      },
      
      test("encodes MergeConflict to Conflict (409)") {
        val error = MergeConflict("feature-branch", "Conflicting changes in node X")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.Conflict,
          response.error.code == 409,
          response.error.message.contains("Merge conflict"),
          response.error.errors.head.field == "branch"
        )
      }
    ),
    
    suite("decode")(
      test("decodes 400 to ValidationFailed with structured errors") {
        val response = ErrorResponse(
          JsonHttpError(400, "Validation failed", List(
            ErrorDetail("simulations", "name", ValidationErrorCode.REQUIRED_FIELD, "Name is required"),
            ErrorDetail("simulations", "email", ValidationErrorCode.INVALID_FORMAT, "Email is invalid")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.BadRequest, response))
        
        assertTrue(
          throwable.isInstanceOf[ValidationFailed],
          throwable.asInstanceOf[ValidationFailed].errors.length == 2,
          throwable.asInstanceOf[ValidationFailed].errors.head.field == "name",
          throwable.asInstanceOf[ValidationFailed].errors.head.code == ValidationErrorCode.REQUIRED_FIELD
        )
      },

      test("decodes 409 with version field to VersionConflict") {
        val response = ErrorResponse(
          JsonHttpError(409, "Version conflict", List(
            ErrorDetail("risk-trees", "version", ValidationErrorCode.CONSTRAINT_VIOLATION, "Version conflict on node-123")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.Conflict, response))
        assertTrue(throwable.isInstanceOf[VersionConflict])
      },

      test("decodes 409 with branch field to MergeConflict") {
        val response = ErrorResponse(
          JsonHttpError(409, "Merge conflict", List(
            ErrorDetail("scenarios", "branch", ValidationErrorCode.CONSTRAINT_VIOLATION, "Merge conflict on feature-1")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.Conflict, response))
        assertTrue(throwable.isInstanceOf[MergeConflict])
      },

      test("decodes 409 with generic field to DataConflict") {
        val response = ErrorResponse(
          JsonHttpError(409, "Data conflict", List(
            ErrorDetail("risk-trees", "unknown", ValidationErrorCode.DUPLICATE_VALUE, "Duplicate key")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.Conflict, response))
        assertTrue(throwable.isInstanceOf[DataConflict])
      },

      test("decodes 503 to IrminUnavailable") {
        val response = ErrorResponse(
          JsonHttpError(503, "Service unavailable", List(
            ErrorDetail("irmin", "service", ValidationErrorCode.DEPENDENCY_FAILED, "Connection refused")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.ServiceUnavailable, response))
        assertTrue(throwable.isInstanceOf[IrminUnavailable])
      },

      test("decodes 403 to AccessDenied") {
        val response = ErrorResponse(
          JsonHttpError(403, "Forbidden", List(
            ErrorDetail("risk-trees", "authorization", ValidationErrorCode.CONSTRAINT_VIOLATION, "Forbidden")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.Forbidden, response))
        assertTrue(throwable.isInstanceOf[AccessDenied])
      },

      test("decodes 429 to RateLimitExceeded") {
        val response = ErrorResponse(
          JsonHttpError(429, "Too many requests", List(
            ErrorDetail("risk-trees", "rate-limit", ValidationErrorCode.CONSTRAINT_VIOLATION, "Too many requests")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.TooManyRequests, response))
        assertTrue(throwable.isInstanceOf[RateLimitExceeded])
      },

      test("decodes 504 to NetworkTimeout") {
        val response = ErrorResponse(
          JsonHttpError(504, "Gateway timeout", List(
            ErrorDetail("irmin", "network", ValidationErrorCode.DEPENDENCY_FAILED, "Timed out")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.GatewayTimeout, response))
        assertTrue(throwable.isInstanceOf[NetworkTimeout])
      },

      test("decodes 502 to IrminGraphQLError") {
        val response = ErrorResponse(
          JsonHttpError(502, "Bad gateway", List(
            ErrorDetail("irmin", "graphql", ValidationErrorCode.DEPENDENCY_FAILED, "Query failed")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.BadGateway, response))
        assertTrue(throwable.isInstanceOf[IrminGraphQLError])
      },

      test("decodes 500 with simulation field to SimulationFailure") {
        val response = ErrorResponse(
          JsonHttpError(500, "Simulation failed", List(
            ErrorDetail("risk-trees", "simulation", ValidationErrorCode.CONSTRAINT_VIOLATION, "Simulation sim-1 failed")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.InternalServerError, response))
        assertTrue(throwable.isInstanceOf[SimulationFailure])
      },

      test("decodes 500 with generic field to RepositoryFailure") {
        val response = ErrorResponse(
          JsonHttpError(500, "Error", List(
            ErrorDetail("risk-trees", "unknown", ValidationErrorCode.CONSTRAINT_VIOLATION, "DB connection failed")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.InternalServerError, response))
        assertTrue(throwable.isInstanceOf[RepositoryFailure])
      },

      test("roundtrip: encode then decode preserves type for each error kind") {
        // ValidationFailed
        val vf = ValidationFailed(List(ValidationError("name", ValidationErrorCode.REQUIRED_FIELD, "Required")))
        val vfDecoded = ErrorResponse.decode(ErrorResponse.encode(vf))
        // DataConflict
        val dc = DataConflict("Duplicate key")
        val dcDecoded = ErrorResponse.decode(ErrorResponse.encode(dc))
        // VersionConflict
        val vc = VersionConflict("n1", "v1", "v2")
        val vcDecoded = ErrorResponse.decode(ErrorResponse.encode(vc))
        // MergeConflict
        val mc = MergeConflict("feat", "conflict detail")
        val mcDecoded = ErrorResponse.decode(ErrorResponse.encode(mc))
        // IrminUnavailable
        val iu = IrminUnavailable("Connection refused")
        val iuDecoded = ErrorResponse.decode(ErrorResponse.encode(iu))
        // NetworkTimeout
        val nt = NetworkTimeout("getNode", 5.seconds)
        val ntDecoded = ErrorResponse.decode(ErrorResponse.encode(nt))
        // SimulationFailure
        val sf = SimulationFailure("sim-1", new RuntimeException("boom"))
        val sfDecoded = ErrorResponse.decode(ErrorResponse.encode(sf))
        // RepositoryFailure
        val rf = RepositoryFailure("DB down")
        val rfDecoded = ErrorResponse.decode(ErrorResponse.encode(rf))

        assertTrue(
          vfDecoded.isInstanceOf[ValidationFailed],
          vfDecoded.asInstanceOf[ValidationFailed].errors.head.field == "name",
          dcDecoded.isInstanceOf[DataConflict],
          vcDecoded.isInstanceOf[VersionConflict],
          mcDecoded.isInstanceOf[MergeConflict],
          iuDecoded.isInstanceOf[IrminUnavailable],
          ntDecoded.isInstanceOf[NetworkTimeout],
          sfDecoded.isInstanceOf[SimulationFailure],
          rfDecoded.isInstanceOf[RepositoryFailure]
        )
      }
    ),
    
    suite("JSON serialization")(
      test("ErrorResponse can be serialized to JSON") {
        val response = ErrorResponse(
          JsonHttpError(400, "Test error", List(
            ErrorDetail("test", "field", ValidationErrorCode.CONSTRAINT_VIOLATION, "message")
          ))
        )
        
        val json = response.toJson
        val decoded = json.fromJson[ErrorResponse]
        
        assertTrue(
          decoded.isRight,
          decoded.contains(response)
        )
      },
      
      test("ErrorDetail can be serialized to JSON") {
        val detail = ErrorDetail("domain", "field", ValidationErrorCode.INVALID_FORMAT, "message")
        val json = detail.toJson
        val decoded = json.fromJson[ErrorDetail]
        
        assertTrue(
          decoded.isRight,
          decoded.contains(detail)
        )
      },
      
      test("JsonHttpError can be serialized to JSON") {
        val httpError = JsonHttpError(500, "Error", List(
          ErrorDetail("test", "field", ValidationErrorCode.CONSTRAINT_VIOLATION, "test message")
        ))
        
        val json = httpError.toJson
        val decoded = json.fromJson[JsonHttpError]
        
        assertTrue(
          decoded.isRight,
          decoded.contains(httpError)
        )
      }
    ),
    
    suite("makeValidationResponse")(
      test("creates proper validation error response") {
        val errors = List(
          ValidationError("field1", ValidationErrorCode.INVALID_FORMAT, "Field1 invalid"),
          ValidationError("field2", ValidationErrorCode.REQUIRED_FIELD, "Field2 required")
        )
        val (status, response) = ErrorResponse.makeValidationResponse(errors)
        
        assertTrue(
          status == StatusCode.BadRequest,
          response.error.code == 400,
          response.error.errors.length == 2,
          response.error.errors.exists(_.code == ValidationErrorCode.INVALID_FORMAT),
          response.error.errors.exists(_.code == ValidationErrorCode.REQUIRED_FIELD)
        )
      }
    )
  )
}
