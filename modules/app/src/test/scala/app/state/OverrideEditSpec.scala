package app.state

import zio.test.*

import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, TreeId}
import app.state.OverrideEdit.{Closed, Open, Pending}
import app.state.OverrideEditEvent.{Applied, Cancelled, OpenOn, Requested, TreeCleared, TreeLoaded}

/** Pure tests for the override edit popup's transitions, one per row of the
  * transition table, and for the state that applies them. */
object OverrideEditSpec extends ZIOSpecDefault:

  private def nid(i: Int): NodeId = NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val tree  = TreeId(nid(1).toSafeId)
  private val other = TreeId(nid(2).toSafeId)
  private val m     = MitigationId(nid(10).toSafeId)
  private val gone  = MitigationId(nid(11).toSafeId)
  private val held  = Set(m)

  def spec = suite("OverrideEdit")(

    suite("from Closed")(
      test("OpenOn a held statement opens it, and one the tree does not hold stays closed") {
        assertTrue(
          OverrideEdit.next(Closed, OpenOn(m), held) == Open(m),
          OverrideEdit.next(Closed, OpenOn(gone), held) == Closed
        )
      },
      test("Requested waits for the named tree to load") {
        assertTrue(OverrideEdit.next(Closed, Requested(tree, m), Set.empty) == Pending(tree, m))
      },
      test("every other event leaves it closed") {
        assertTrue(
          List(TreeLoaded(tree, held), TreeCleared, Applied, Cancelled)
            .forall(OverrideEdit.next(Closed, _, held) == Closed)
        )
      }
    ),

    suite("from Pending")(
      test("the named tree loading with the statement opens it") {
        assertTrue(OverrideEdit.next(Pending(tree, m), TreeLoaded(tree, held), held) == Open(m))
      },
      test("another tree loading, the named tree without the statement, or a new tree closes it") {
        assertTrue(
          OverrideEdit.next(Pending(tree, m), TreeLoaded(other, held), held) == Closed,
          OverrideEdit.next(Pending(tree, m), TreeLoaded(tree, Set(gone)), Set(gone)) == Closed,
          OverrideEdit.next(Pending(tree, m), TreeCleared, Set.empty) == Closed
        )
      },
      test("every other event leaves the request waiting") {
        assertTrue(
          List(OpenOn(m), Requested(other, gone), Applied, Cancelled)
            .forall(OverrideEdit.next(Pending(tree, m), _, held) == Pending(tree, m))
        )
      }
    ),

    suite("from Open")(
      test("Applied, Cancelled, a tree load and a new tree each close it") {
        assertTrue(
          List(Applied, Cancelled, TreeLoaded(tree, held), TreeCleared)
            .forall(OverrideEdit.next(Open(m), _, held) == Closed)
        )
      },
      test("the popup is modal: another OpenOn or a Requested changes nothing") {
        assertTrue(
          OverrideEdit.next(Open(m), OpenOn(m), held) == Open(m),
          OverrideEdit.next(Open(m), Requested(other, m), held) == Open(m)
        )
      }
    ),

    test("OverrideEditState applies each dispatched event to its current state") {
      val state = new OverrideEditState
      state.dispatch(Requested(tree, m), Set.empty)
      val waiting = state.edit.now()
      state.dispatch(TreeLoaded(tree, held), held)
      val opened = state.edit.now()
      state.dispatch(Applied, held)
      assertTrue(waiting == Pending(tree, m), opened == Open(m), state.edit.now() == Closed)
    }
  )
