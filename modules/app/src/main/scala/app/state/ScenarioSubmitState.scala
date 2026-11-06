package app.state

import app.core.ViewFailure
import com.risquanter.register.http.responses.ScenarioResponse

/** Submission lifecycle ADT for scenario creation, used by `BranchBar`.
  * It has `SubmitState`'s shape and carries `ScenarioResponse`, because
  * `SubmitState` is fixed to `TreeSummaryResponse`.
  */
enum ScenarioSubmitState:
  case Idle
  case Submitting
  case Success(response: ScenarioResponse)
  case Failed(failure: ViewFailure)
