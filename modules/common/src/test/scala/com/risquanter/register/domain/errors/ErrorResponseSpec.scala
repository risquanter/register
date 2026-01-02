package com.risquanter.register.domain.errors

import zio.test.*
import zio.json.*
import sttp.model.StatusCode

object ErrorResponseSpec extends ZIOSpecDefault {

  def spec = suite("ErrorResponse")(
    suite("encode")(
      test("encodes ValidationFailed to BadRequest") {
        val error = ValidationFailed(List("Name is required", "Email is invalid"))
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
          response.error.errors.head.reason == "repository error"
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
      
      test("encodes duplicate key exception to Conflict") {
        val error = new RuntimeException("duplicate key value")
        val (status, response) = ErrorResponse.encode(error)
        
        assertTrue(
          status == StatusCode.Conflict,
          response.error.code == 409
        )
      }
    ),
    
    suite("decode")(
      test("decodes error response to RuntimeException") {
        val response = ErrorResponse(
          JsonHttpError(400, "Validation failed", List(
            ErrorDetail("simulations", "validation", "Name is required")
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
            ErrorDetail("test", "reason", "message")
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
        val detail = ErrorDetail("domain", "reason", "message")
        val json = detail.toJson
        val decoded = json.fromJson[ErrorDetail]
        
        assertTrue(
          decoded.isRight,
          decoded.contains(detail)
        )
      },
      
      test("JsonHttpError can be serialized to JSON") {
        val httpError = JsonHttpError(500, "Error", List(
          ErrorDetail("test", "test", "test message")
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
        val errors = List("Field1 invalid", "Field2 required")
        val (status, response) = ErrorResponse.makeValidationResponse(errors)
        
        assertTrue(
          status == StatusCode.BadRequest,
          response.error.code == 400,
          response.error.errors.length == 2,
          response.error.errors.forall(_.reason == "constraint validation error")
        )
      }
    )
  )
}
