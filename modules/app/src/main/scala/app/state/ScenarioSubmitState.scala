package app.state

import app.core.ViewFailure
import com.risquanter.register.http.responses.ScenarioResponse

/** Submission lifecycle ADT for scenario creation, used by `BranchBar`.
  * Mirrors `SubmitState`'s shape but carries `ScenarioResponse` —
  * kept as a separate type rather than generalizing `SubmitState` because
  * `SubmitState` is hardcoded to `SimulationResponse` and is shared by
  * `TreeBuilderView`; widening it would touch an existing signature outside
  * this item's scope.
  */
enum ScenarioSubmitState:
  case Idle
  case Submitting
  case Success(response: ScenarioResponse)
  case Failed(failure: ViewFailure)
