package app.views

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId

/** Pure tests for `AnalyzeView.computeSeed` — the rule deciding what a
  * compare card gets selected when its branch enters the comparison.
  */
object AnalyzeViewSeedSpec extends ZIOSpecDefault:

  private def nid(i: Int): NodeId =
    NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val root  = nid(99)
  private val leaf1 = nid(1)
  private val leaf2 = nid(2)
  private val leaf3 = nid(3)

  def spec = suite("AnalyzeView.computeSeed")(

    test("nonempty baseline seeds only its counterparts on the compare branch, no root involved") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set(leaf1, leaf2),
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(leaf2, leaf3, root)
      )
      assertTrue(rootToSelect.isEmpty, seeds == List(leaf2))
    },

    test("empty baseline falls back to the root: selected on the active card and seeded where a counterpart exists") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(root, leaf2)
      )
      assertTrue(rootToSelect.contains(root), seeds == List(root))
    },

    test("root fallback still selects on the active card when the compare branch has no counterpart") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(leaf2, leaf3)
      )
      assertTrue(rootToSelect.contains(root), seeds.isEmpty)
    },

    test("no active tree loaded and empty baseline seeds nothing") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = None,
        compareTreeNodeIds = Set(leaf1, leaf2)
      )
      assertTrue(rootToSelect.isEmpty, seeds.isEmpty)
    },

    test("seeds are capped in deterministic id order") {
      val many = (1 to 15).map(nid).toSet
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = many,
        activeRoot         = Some(root),
        compareTreeNodeIds = many
      )
      assertTrue(
        rootToSelect.isEmpty,
        seeds.length == 13,
        seeds == seeds.sortBy(_.value),
        seeds == many.toList.sortBy(_.value).take(13)
      )
    }
  )
