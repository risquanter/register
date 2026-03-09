package app.core

import scala.scalajs.LinkingInfo
import org.scalajs.dom.window

/** Application constants, including environment-aware backend URL.
  *
  * In development mode (sbt fastLinkJS / Vite dev-server on port 5173),
  * the backend is assumed to run on localhost:8090.
  * In production the frontend is served from the same origin as the backend.
  */
object Constants:

  val backendBaseURL: String =
    if LinkingInfo.developmentMode then "http://localhost:8090"
    else window.document.location.origin
