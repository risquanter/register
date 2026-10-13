package app.state

import app.core.ViewFailure
import com.risquanter.register.http.responses.SimulationResponse

/** Submission lifecycle ADT for the tree-creation request.
  *
  * Used as `Var[SubmitState]` in TreeBuilderView following the cheleb
  * tapError→Var pattern: success and failure are pushed directly into
  * the Var rather than through separate event buses.
  */
enum SubmitState:
  case Idle
  case Submitting
  case Success(response: SimulationResponse)
  case Failed(failure: ViewFailure)
