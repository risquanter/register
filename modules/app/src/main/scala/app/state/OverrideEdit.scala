package app.state

import com.raquo.laminar.api.L.*

import com.risquanter.register.domain.data.iron.{MitigationId, TreeId}

/** The override edit popup's state: closed, waiting for the Design view to
  * load the tree a link named, or open on one absolute statement. */
enum OverrideEdit:
  case Closed
  case Pending(tree: TreeId, id: MitigationId)
  case Open(id: MitigationId)

/** What moves the popup. */
enum OverrideEditEvent:
  /** The Design list's edit button, on the loaded tree. */
  case OpenOn(id: MitigationId)
  /** An Analyze link: open `id` once the Design view has loaded `tree`. */
  case Requested(tree: TreeId, id: MitigationId)
  /** The builder loaded `tree`, holding the absolute statements `held`. */
  case TreeLoaded(tree: TreeId, held: Set[MitigationId])
  /** The builder started a new tree. */
  case TreeCleared
  case Applied, Cancelled

object OverrideEdit:

  /** The one transition function. `held` is the loaded tree's absolute
    * statements, so an `OpenOn` naming one the tree no longer holds stays
    * `Closed`. The popup is modal, so an `OpenOn` while `Open` changes
    * nothing. A pending request that the next tree load does not answer is
    * dropped, which is what declining the Design view's unsaved-draft confirm
    * leads to.
    *
    * {{{
    * next(Closed, Requested(t, m), held = Set.empty)       // Pending(t, m)
    * next(Pending(t, m), TreeLoaded(t, Set(m)), Set(m))    // Open(m)
    * next(Pending(t, m), TreeLoaded(u, Set(m)), Set(m))    // Closed
    * }}}
    */
  def next(state: OverrideEdit, event: OverrideEditEvent, held: Set[MitigationId]): OverrideEdit =
    (state, event) match
      case (Closed, OverrideEditEvent.OpenOn(id)) if held.contains(id)              => Open(id)
      case (Closed, OverrideEditEvent.Requested(tree, id))                          => Pending(tree, id)
      case (Pending(tree, id), OverrideEditEvent.TreeLoaded(loaded, loadedHeld))
          if loaded == tree && loadedHeld.contains(id)                              => Open(id)
      case (Pending(_, _), OverrideEditEvent.TreeLoaded(_, _))                      => Closed
      case (Pending(_, _), OverrideEditEvent.TreeCleared)                           => Closed
      case (Open(_), OverrideEditEvent.Applied | OverrideEditEvent.Cancelled |
                     OverrideEditEvent.TreeLoaded(_, _) | OverrideEditEvent.TreeCleared) => Closed
      case _                                                                        => state

/** The popup's state, created once in `Main` and passed to both views. */
final class OverrideEditState:
  val edit: Var[OverrideEdit] = Var(OverrideEdit.Closed)

  /** Move the popup by `event`, against the loaded tree's absolute
    * statements `held`. */
  def dispatch(event: OverrideEditEvent, held: Set[MitigationId]): Unit =
    edit.update(OverrideEdit.next(_, event, held))
