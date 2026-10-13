package app.core

/** Null-safe message extraction for a Throwable.
  *
  * Scala.js interop with browser APIs can produce exceptions whose
  * `getMessage` returns `null`, so reading one needs a fallback.
  *
  * Its one caller is the console record a transport failure writes. A failure
  * the server answered with is a typed value and composes its own sentence, so
  * nothing user-facing reads a message off an exception.
  *
  * @see ADR-010 (errors are values — this is the value-extraction edge)
  */
extension (e: Throwable)
  /** Null-safe message: prefers `getMessage`, falls back to `toString`
    * (which always includes the exception class name).
    */
  def safeMessage: String = Option(e.getMessage).getOrElse(e.toString)
