package app.core

/** Null-safe message extraction for Throwables.
  *
  * Scala.js interop with browser APIs (Fetch, WebSocket) can produce
  * exceptions whose `getMessage` returns `null`. This extension
  * provides a single consistent fallback strategy used across the
  * entire app module — ZJS error handlers, GlobalError classifier,
  * WorkspaceState bootstrap.
  *
  * @see ADR-010 (errors are values — this is the value-extraction edge)
  */
extension (e: Throwable)
  /** Null-safe message: prefers `getMessage`, falls back to `toString`
    * (which always includes the exception class name).
    */
  def safeMessage: String = Option(e.getMessage).getOrElse(e.toString)
