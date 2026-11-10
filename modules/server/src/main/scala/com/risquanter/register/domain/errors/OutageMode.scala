package com.risquanter.register.domain.errors

import sttp.model.StatusCode

/** How a peer failed to answer. The cases are the four transport failures the
  * Irmin client distinguishes, generalised to every peer.
  */
enum OutageMode:
  /** The connection was refused or dropped; carries no status. */
  case ConnectionRefused

  /** No answer within the client's deadline. */
  case Timeout(after: zio.Duration)

  /** The peer answered with a status that is not success. */
  case Status(code: StatusCode)

  /** The peer answered, and the answer was not the protocol's shape: an
    * undecodable body, or a GraphQL `errors` array. */
  case Protocol

object OutageMode:
  /** The server's own sentence for a log line. Never reaches a client. */
  def describe(mode: OutageMode): String = mode match
    case ConnectionRefused => "the connection was refused or dropped"
    case Timeout(after)    => s"no answer within ${after.toMillis} ms"
    case Status(code)      => s"answered with status ${code.code}"
    case Protocol          => "answered outside the protocol's shape"
