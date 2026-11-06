package app.state

import app.core.ViewFailure

/** Submission lifecycle ADT for a request answering `A`.
  *
  * Used as `Var[SubmitState[TreeSummaryResponse]]` in TreeBuilderView and
  * `Var[SubmitState[ScenarioResponse]]` in BranchBar, following the cheleb
  * tapError→Var pattern: success and failure are pushed directly into
  * the Var rather than through separate event buses.
  */
enum SubmitState[+A]:
  case Idle
  case Submitting
  case Success(response: A)
  case Failed(failure: ViewFailure)
