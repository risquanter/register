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

  /** Transform the loaded value, preserving Idle/Loading/Failed as-is. */
  def map[B](f: A => B): LoadState[B] = this match
    case Loaded(a)  => Loaded(f(a))
    case Idle       => Idle
    case Loading    => Loading
    case Failed(m)  => Failed(m)

  /** Chain a dependent load operation on the loaded value. */
  def flatMap[B](f: A => LoadState[B]): LoadState[B] = this match
    case Loaded(a)  => f(a)
    case Idle       => Idle
    case Loading    => Loading
    case Failed(m)  => Failed(m)
