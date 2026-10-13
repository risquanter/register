package app.state

import app.core.ViewFailure

/** Generic async-read lifecycle ADT, parameterised on the loaded data type.
  *
  * Complements `SubmitState` (write lifecycle) — keeps read and write
  * concerns decoupled.  Each `Var[LoadState[A]]` tracks a single
  * data-fetching operation (e.g. tree list, selected tree structure).
  *
  * `Absent` and `Failed` are different outcomes and a view renders them
  * differently. `Absent` means the request succeeded and the thing is not
  * there, which is a placeholder rather than an error. `Failed` carries why
  * there is nothing to show, and the sentence for it is composed where it is
  * rendered.
  *
  * @tparam A The type of data being loaded
  */
enum LoadState[+A]:
  case Idle
  case Loading
  case Absent
  case Loaded(data: A)
  case Failed(failure: ViewFailure)

  /** Transform the loaded value, preserving every other case as-is. */
  def map[B](f: A => B): LoadState[B] = this match
    case Loaded(a)  => Loaded(f(a))
    case Idle       => Idle
    case Loading    => Loading
    case Absent     => Absent
    case Failed(v)  => Failed(v)

  /** Chain a dependent load operation on the loaded value. */
  def flatMap[B](f: A => LoadState[B]): LoadState[B] = this match
    case Loaded(a)  => f(a)
    case Idle       => Idle
    case Loading    => Loading
    case Absent     => Absent
    case Failed(v)  => Failed(v)
