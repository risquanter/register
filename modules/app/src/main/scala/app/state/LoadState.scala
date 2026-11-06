package app.state

import app.core.{RequestFailure, ViewFailure}
import com.risquanter.register.http.errors.{NotFoundKind, WireError}

/** Generic async-read lifecycle ADT, parameterised on the loaded data type.
  *
  * Complements `SubmitState` (write lifecycle) — keeps read and write
  * concerns decoupled.  Each `Var[LoadState[A]]` tracks a single
  * data-fetching operation (e.g. tree list, selected tree structure).
  *
  * A request that found nothing is a `Failed` carrying the server's
  * not-found answer, which names which absence it was. The sentence for a
  * failure is composed where it is rendered.
  *
  * @tparam A The type of data being loaded
  */
enum LoadState[+A]:
  case Idle
  case Loading
  case Loaded(data: A)
  case Failed(failure: ViewFailure)

  /** Transform the loaded value, preserving every other case as-is. */
  def map[B](f: A => B): LoadState[B] = this match
    case Loaded(a)  => Loaded(f(a))
    case Idle       => Idle
    case Loading    => Loading
    case Failed(v)  => Failed(v)

  /** Chain a dependent load operation on the loaded value. */
  def flatMap[B](f: A => LoadState[B]): LoadState[B] = this match
    case Loaded(a)  => f(a)
    case Idle       => Idle
    case Loading    => Loading
    case Failed(v)  => Failed(v)

object LoadState:
  /** A tree read that found the tree held but absent at the revision named:
    * the branch lacks it, or the pinned commit predates it. The views that
    * show a tree treat this as nothing to show rather than as an error, so
    * they match it ahead of `Failed`.
    *
    * {{{
    * selectedTree.now() match
    *   case LoadState.TreeAbsent(f) => placeholder(sentenceFor(f))
    *   case LoadState.Failed(f)     => notice(f)
    * }}}
    */
  object TreeAbsent:
    def unapply[A](state: LoadState[A]): Option[ViewFailure] = state match
      case Failed(failure @ RequestFailure.Wire(WireError.NotFound(
             NotFoundKind.TreeNotOnBranch(_) | NotFoundKind.TreeNotAtCommit(), _))) => Some(failure)
      case _ => None
