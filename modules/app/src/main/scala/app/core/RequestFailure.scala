package app.core

import com.risquanter.register.http.errors.WireError

/** Why a request to the backend did not produce a result.
  *
  * Two different failures reach the browser and only one of them is a
  * `WireError`. A `WireError` exists only when the server answered. A transport
  * failure is the browser reporting that the call never happened at all.
  *
  * Neither case is a `Throwable`. ZIO's error channel is an unconstrained type
  * parameter, so a client failure is an ordinary value and needs no exception
  * supertype to travel in it.
  */
enum RequestFailure:
  /** The server answered with one of its statuses and a typed body. */
  case Wire(error: WireError)

  /** The request never completed: no network, a DNS failure, a refused
    * connection, or a rejected cross-origin check. The browser's `fetch`
    * rejects with a JavaScript `TypeError` here, so there is no status and no
    * body to read.
    */
  case Transport(cause: Throwable)
