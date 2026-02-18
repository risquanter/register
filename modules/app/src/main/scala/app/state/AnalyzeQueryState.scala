package app.state

import com.raquo.laminar.api.L.{*, given}

/** Draft state for Analyze view query-language inputs.
  *
  * Kept as a dedicated state object so `AnalyzeView` stays structural
  * and receives state via parameters (ADR-019 Pattern 2).
  */
final class AnalyzeQueryState:
  val queryInput: Var[String] = Var("")
  val textualResponse: Var[String] = Var("")
