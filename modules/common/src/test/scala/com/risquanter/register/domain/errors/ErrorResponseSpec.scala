package com.risquanter.register.domain.errors

import scala.concurrent.duration.*

import zio.test.*
import zio.json.*
import sttp.model.StatusCode

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
          response.error.errors.head.code == ValidationErrorCode.CONSTRAINT_VIOLATION
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
      test("decodes error response to RuntimeException") {
        val response = ErrorResponse(
          JsonHttpError(400, "Validation failed", List(
            ErrorDetail("simulations", "name", ValidationErrorCode.REQUIRED_FIELD, "Name is required")
          ))
        )
        val throwable = ErrorResponse.decode((StatusCode.BadRequest, response))
        
        assertTrue(
          throwable.isInstanceOf[RuntimeException],
          throwable.getMessage.contains("Name is required")
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
