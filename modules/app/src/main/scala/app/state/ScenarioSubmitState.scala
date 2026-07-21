package app.state

import com.risquanter.register.http.responses.ScenarioResponse

/** Submission lifecycle ADT for scenario creation (BranchBar, milestone-2b
  * Phase B). Mirrors `SubmitState`'s shape but carries `ScenarioResponse` —
  * kept as a separate type rather than generalizing `SubmitState` because
  * `SubmitState` is hardcoded to `SimulationResponse` and is shared by
  * `TreeBuilderView`; widening it would touch an existing signature outside
  * this item's scope.
  */
enum ScenarioSubmitState:
  case Idle
  case Submitting
  case Success(response: ScenarioResponse)
  case Failed(message: String)
