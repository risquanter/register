package app.core

/** Guard for Scala.js ↔ JS interop edges. */
object JsBoundary:

  /** The one sanctioned `catch Throwable` site (ADR-033 §4): converts ANY
    * throwable — including Scala.js `UndefinedBehaviorError`, which `NonFatal`
    * and named types miss — into the total fallback. Use only at a
    * Scala.js ↔ JS interop edge.
    */
  inline def orElse[A](inline fallback: A)(inline body: A): A =
    try body catch case _: Throwable => fallback
