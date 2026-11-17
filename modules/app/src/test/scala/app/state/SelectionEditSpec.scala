package app.state

import zio.test.*

import com.risquanter.register.domain.data.{MitigationSelection, NodeMitigationEntry}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}

/** Pure tests for the interface's selection edits: the toggle each case reads
  * as, the whole-mitigation toggle, every per-node transition, and dropping
  * mitigations a tree does not hold. */
object SelectionEditSpec extends ZIOSpecDefault:

  private def nid(i: Int): NodeId = NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val n = nid(1)
  private val k = nid(2)
  private val scope = Set(n, k)
  private val m = MitigationId(nid(10).toSafeId)
  private val other = MitigationId(nid(11).toSafeId)

  private val on: MitigationSelection.Enumerated = MitigationSelection.single(m)
  private def at(nodes: NodeId*): MitigationSelection.Enumerated =
    MitigationSelection.create(Nil, nodes.toList.map(NodeMitigationEntry(_, m))).toOption.get

  private def edited(v: zio.prelude.Validation[?, MitigationSelection.Enumerated]): Option[MitigationSelection.Enumerated] =
    v.toOption

  def spec = suite("SelectionEdit")(

    test("toggleOf reads each case") {
      assertTrue(
        SelectionEdit.toggleOf(MitigationSelection.Inherent, m) == MitigationToggle.Off,
        SelectionEdit.toggleOf(at(n), m) == MitigationToggle.Partial,
        SelectionEdit.toggleOf(on, m) == MitigationToggle.On,
        SelectionEdit.toggleOf(on, other) == MitigationToggle.Off
      )
    },

    test("appliesAt is true across the whole scope when on, and at the named nodes when partial") {
      assertTrue(
        SelectionEdit.appliesAt(on, m, k),
        SelectionEdit.appliesAt(at(n), m, n),
        !SelectionEdit.appliesAt(at(n), m, k),
        !SelectionEdit.appliesAt(MitigationSelection.Inherent, m, n)
      )
    },

    test("toggleWhole turns Off and Partial on, and On off") {
      assertTrue(
        edited(SelectionEdit.toggleWhole(MitigationSelection.Inherent, m)).contains(on),
        edited(SelectionEdit.toggleWhole(at(n), m)).contains(on),
        edited(SelectionEdit.toggleWhole(on, m)).contains(MitigationSelection.Inherent)
      )
    },

    test("toggleAt on a mitigation that is on leaves it applying at the rest of its scope") {
      assertTrue(edited(SelectionEdit.toggleAt(on, m, n, scope)).contains(at(k)))
    },

    test("toggleAt flips one node of a partial selection, and becomes On or Off at the ends") {
      assertTrue(
        edited(SelectionEdit.toggleAt(at(k), m, n, scope)).contains(on),
        edited(SelectionEdit.toggleAt(at(n), m, n, scope)).contains(MitigationSelection.Inherent)
      )
    },

    test("toggleAt on a mitigation that is off applies it at that node, or turns it on for a one-node scope") {
      assertTrue(
        edited(SelectionEdit.toggleAt(MitigationSelection.Inherent, m, n, scope)).contains(at(n)),
        edited(SelectionEdit.toggleAt(MitigationSelection.Inherent, m, n, Set(n))).contains(on)
      )
    },

    test("toggleAt leaves every other mitigation of the selection as it was") {
      val withOther = MitigationSelection.create(List(other), Nil).toOption.get
      val result = edited(SelectionEdit.toggleAt(withOther, m, n, scope))
      assertTrue(
        result.exists(SelectionEdit.toggleOf(_, other) == MitigationToggle.On),
        result.exists(SelectionEdit.toggleOf(_, m) == MitigationToggle.Partial)
      )
    },

    test("present drops a mitigation the tree does not hold and keeps the rest") {
      val both = MitigationSelection.create(List(m, other), Nil).toOption.get
      assertTrue(
        SelectionEdit.present(both, Set(m)).toOption.contains((on, Set(other))),
        SelectionEdit.present(both, Set.empty).toOption.contains((MitigationSelection.Inherent, Set(m, other)))
      )
    }
  )
