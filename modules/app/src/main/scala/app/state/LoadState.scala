package app.state

/** Generic async-read lifecycle ADT, parameterised on the loaded data type.
  *
  * Complements `SubmitState` (write lifecycle) — keeps read and write
  * concerns decoupled.  Each `Var[LoadState[A]]` tracks a single
  * data-fetching operation (e.g. tree list, selected tree structure).
  *
  * @tparam A The type of data being loaded
  */
enum LoadState[+A]:
  case Idle
  case Loading
  case Loaded(data: A)
  case Failed(message: String)
