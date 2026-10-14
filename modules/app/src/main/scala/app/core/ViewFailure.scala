package app.core

import sttp.model.StatusCode

import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.http.errors.WireError

/** Why a view has nothing to show.
  *
  * Sealed, and extended directly by the two kinds of answer rather than
  * wrapping them in cases of its own. A `RequestFailure` is therefore already a
  * `ViewFailure`, so a request result becomes a display state with no
  * conversion and a pattern reaches a wire case without unwrapping a level.
  *
  * The four display states carry this type rather than a composed sentence,
  * which is what lets a view present the same failure two ways: the sentence is
  * written where it is rendered, so a view can read the failure's own case and
  * treat one specially.
  *
  * All three types live in this file because the trait is sealed, and Scala
  * requires a sealed type's direct subtypes to be declared alongside it.
  */
sealed trait ViewFailure

/** Why a request to the backend did not produce a result.
  *
  * Three failures reach the browser and only one of them is a `WireError`. A
  * `WireError` exists when the server answered and the body decoded. A
  * transport failure is the browser reporting that the call never happened at
  * all. Between those sits an answer that arrived and could not be read, which
  * is its own case because the three differ in the one way a user acts on:
  * whether the request may have taken effect.
  *
  * No case is a `Throwable`. ZIO's error channel is an unconstrained type
  * parameter, so a client failure is an ordinary value and needs no exception
  * supertype to travel in it.
  */
enum RequestFailure extends ViewFailure:
  /** The server answered with one of its statuses and a typed body. */
  case Wire(error: WireError)

  /** The request never completed: no network, a DNS failure, a refused
    * connection, or a rejected cross-origin check. The browser's `fetch`
    * rejects with a JavaScript `TypeError` here, so there is no status and no
    * body to read.
    */
  case Transport(cause: Throwable)

  /** The server answered and the reply could not be read: a body no declared
    * variant decodes, or a status no variant claims — a gateway's own error
    * page, for instance.
    *
    * Carries the status, because the request may have taken effect and the
    * status is the only evidence of that the browser has.
    */
  case Undecodable(status: StatusCode)

/** A failure that never left the browser, so no request was made.
  *
  * This is the half of the question a `RequestFailure` cannot answer. A view
  * asks "why have I nothing to show", and the answer is sometimes that the
  * input was rejected here, or that the action needs a workspace the browser
  * does not hold — neither of which involved the server.
  */
enum LocalProblem extends ViewFailure:
  /** The form's own validation rejected the input before it was sent. */
  case FormInvalid(errors: List[ValidationError])

  /** The action needs a workspace and no key is held. */
  case NoActiveWorkspace
