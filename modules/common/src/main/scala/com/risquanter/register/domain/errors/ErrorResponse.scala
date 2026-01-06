package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.model.StatusCode

/** Wrapper for error responses sent to clients */
final case class ErrorResponse(error: JsonHttpError)

object ErrorResponse {
  given codec: JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]

  /** Decode error response tuple back to Throwable */
  def decode(tuple: (StatusCode, ErrorResponse)): Throwable =
    new RuntimeException(tuple._2.error.errors.mkString("; "))

  /** Encode Throwable to error response tuple for HTTP */
  def encode(error: Throwable): (StatusCode, ErrorResponse) = error match {
    case ValidationFailed(errors) => makeValidationResponse(errors)
    case RepositoryFailure(reason) => makeRepositoryFailureResponse(reason)
    case e: Exception =>
      val message = Option(e.getMessage).getOrElse("")
      if (message.contains("duplicate key")) {
        makeDataConflictResponse("duplicate key value violates unique constraint")
      } else {
        makeGeneralResponse()
      }
    case _ => makeGeneralResponse()
  }

  def makeGeneralResponse(requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = "General server error, please check the logs..."
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail(
      domain = "simulations",
      field = "unknown",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      reason = "internal_error",
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeValidationResponse(errorList: List[String], requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = "Domain validation error"
    val statusCode = StatusCode.BadRequest
    val errors = errorList.map { em =>
      val field = ErrorDetail.extractFieldFromMessage(em)
      val code = ValidationErrorCode.categorize(em)
      ErrorDetail(
        domain = "simulations",
        field = field,
        code = code,
        reason = "validation_failed",
        message = em,
        requestId = requestId
      )
    }
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeDataConflictResponse(message: String, requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val statusCode = StatusCode.Conflict
    val errors = List(ErrorDetail(
      domain = "simulations",
      field = "unknown",
      code = ValidationErrorCode.DUPLICATE_VALUE,
      reason = "data_conflict",
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeRepositoryFailureResponse(reason: String, requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = "Repository operation failed"
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail(
      domain = "simulations",
      field = "unknown",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      reason = "repository_error",
      message = reason,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }
}
