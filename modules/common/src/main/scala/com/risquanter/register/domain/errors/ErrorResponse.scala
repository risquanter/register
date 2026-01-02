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

  def makeGeneralResponse(): (StatusCode, ErrorResponse) = {
    val message = "General server error, please check the logs..."
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail("simulations", "internal error", message))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeValidationResponse(errorList: List[String]): (StatusCode, ErrorResponse) = {
    val message = "Domain validation error"
    val statusCode = StatusCode.BadRequest
    val errors = errorList.map(em => 
      ErrorDetail("simulations", "constraint validation error", em))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeDataConflictResponse(message: String): (StatusCode, ErrorResponse) = {
    val statusCode = StatusCode.Conflict
    val errors = List(ErrorDetail("simulations", "conflict", message))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeRepositoryFailureResponse(reason: String): (StatusCode, ErrorResponse) = {
    val message = "Repository operation failed"
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail("simulations", "repository error", reason))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }
}
