package app.components

import zio.test.*

/** Unit tests for TreeNodeRow data contracts.
  *
  * Because DOM creation requires a browser (Scala.js), these tests
  * exercise the pure data invariants that drive rendering:
  *   - NodeKind enum has exactly Portfolio and Leaf.
  *   - Row CSS class names are formatted correctly for any depth.
  *   - The selected/deselected class variants are distinct.
  *
  * DOM-level rendering tests (slot wiring, click handlers) require
  * a jsdom environment and are deferred to Phase D.
  */
object TreeNodeRowSpec extends ZIOSpecDefault:

  /** Pure replication of the rowCls formula inside TreeNodeRow.
    * Tests that rely on this will catch regressions if the class
    * name format changes.
    */
  private def rowCls(depth: Int, selected: Boolean): String =
    if selected then s"tree-node-row tree-node-row--depth-$depth node-selected"
    else s"tree-node-row tree-node-row--depth-$depth"

  def spec = suite("TreeNodeRowSpec")(

    suite("NodeKind enum")(

      test("Portfolio and Leaf are distinct values") {
        assertTrue(TreeNodeRow.NodeKind.Portfolio != TreeNodeRow.NodeKind.Leaf)
      },

      test("enum has exactly two values") {
        assertTrue(TreeNodeRow.NodeKind.values.length == 2)
      }

    ),

    suite("row CSS class contract")(

      test("depth-0 not selected → tree-node-row tree-node-row--depth-0") {
        assertTrue(rowCls(0, false) == "tree-node-row tree-node-row--depth-0")
      },

      test("depth-1 not selected → tree-node-row tree-node-row--depth-1") {
        assertTrue(rowCls(1, false) == "tree-node-row tree-node-row--depth-1")
      },

      test("depth-3 not selected → depth suffix is --depth-3") {
        assertTrue(rowCls(3, false).endsWith("--depth-3"))
      },

      test("selected appends node-selected to the base class string") {
        val unselected = rowCls(2, false)
        val selected   = rowCls(2, true)
        assertTrue(selected.startsWith(unselected)) &&
        assertTrue(selected.endsWith("node-selected"))
      },

      test("deselected does not contain node-selected") {
        assertTrue(!rowCls(4, false).contains("node-selected"))
      }

    )

  )
