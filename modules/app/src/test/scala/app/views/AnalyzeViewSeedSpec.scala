package app.views

import zio.test.*

import app.components.BranchBar
import app.state.{CompareTarget, SlotCoordinate}
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, ScenarioName, TreeId}

/** Pure tests for `AnalyzeView`'s comparison rules: `computeSeed` (what a
  * compare card gets selected when its branch enters the comparison),
  * `engagedSlots` (which slots take part), and the two picker exclusion
  * functions.
  */
object AnalyzeViewSeedSpec extends ZIOSpecDefault:

  private def nid(i: Int): NodeId =
    NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val root  = nid(99)
  private val croot = nid(88)
  private val leaf1 = nid(1)
  private val leaf2 = nid(2)
  private val leaf3 = nid(3)

  private val tid  = TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val tid2 = TreeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  private val scenarioB = BranchChoice.Scenario(ScenarioName.fromString("scenario-b").toOption.get)

  def spec = suite("AnalyzeView pure helpers")(

    test("nonempty baseline seeds only its counterparts on the compare branch, no root involved") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set(leaf1, leaf2),
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(leaf2, leaf3, root),
        compareRoot        = Some(croot)
      )
      assertTrue(rootToSelect.isEmpty, seeds == List(leaf2))
    },

    test("empty baseline falls back to the root: selected on the active card and seeded where a counterpart exists") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(root, leaf2),
        compareRoot        = Some(croot)
      )
      assertTrue(rootToSelect.contains(root), seeds == List(root))
    },

    test("root fallback selects on the active card and seeds nothing when neither the active root has a counterpart nor a compare root is available") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(leaf2, leaf3),
        compareRoot        = None
      )
      assertTrue(rootToSelect.contains(root), seeds.isEmpty)
    },

    test("cross-tree empty baseline: active root absent from the compare tree seeds the compare tree's own root") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(leaf2, leaf3, croot),
        compareRoot        = Some(croot)
      )
      assertTrue(rootToSelect.contains(root), seeds == List(croot))
    },

    test("cross-tree nonempty baseline: no intersection seeds empty and returns None for the active card") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set(leaf1),
        activeRoot         = Some(root),
        compareTreeNodeIds = Set(leaf2, leaf3, croot),
        compareRoot        = Some(croot)
      )
      assertTrue(rootToSelect.isEmpty, seeds.isEmpty)
    },

    test("no active tree loaded and empty baseline seeds nothing") {
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = Set.empty,
        activeRoot         = None,
        compareTreeNodeIds = Set(leaf1, leaf2),
        compareRoot        = None
      )
      assertTrue(rootToSelect.isEmpty, seeds.isEmpty)
    },

    test("seeds are capped in deterministic id order") {
      val many = (1 to 15).map(nid).toSet
      val (rootToSelect, seeds) = AnalyzeView.computeSeed(
        baseline           = many,
        activeRoot         = Some(root),
        compareTreeNodeIds = many,
        compareRoot        = None
      )
      assertTrue(
        rootToSelect.isEmpty,
        seeds.length == 13,
        seeds == seeds.sortBy(_.value),
        seeds == many.toList.sortBy(_.value).take(13)
      )
    },

    test("engagedSlots drops a slot whose pair duplicates an earlier engaged slot's (earlier wins)") {
      val targets = Vector(
        CompareTarget.Target(SlotCoordinate(scenarioB, Some(tid))),
        CompareTarget.Target(SlotCoordinate(scenarioB, None)) // follows active tree = tid: same pair
      )
      val engaged = AnalyzeView.engagedSlots(targets, BranchChoice.Main, Some(tid))
      assertTrue(engaged == Vector((0, scenarioB)))
    },

    test("engagedSlots keeps a same-branch slot on a different tree") {
      val targets = Vector(
        CompareTarget.Target(SlotCoordinate(scenarioB, Some(tid))),
        CompareTarget.Target(SlotCoordinate(scenarioB, Some(tid2)))
      )
      val engaged = AnalyzeView.engagedSlots(targets, BranchChoice.Main, None)
      assertTrue(engaged == Vector((0, scenarioB), (1, scenarioB)))
    },

    test("engagedSlots drops a slot colliding with the tab's own pair") {
      val targets = Vector(
        CompareTarget.Target(SlotCoordinate(BranchChoice.Main, None)), // = the tab's own pair
        CompareTarget.Target(SlotCoordinate(scenarioB, None))
      )
      val engaged = AnalyzeView.engagedSlots(targets, BranchChoice.Main, Some(tid))
      assertTrue(engaged == Vector((1, scenarioB)))
    },

    test("excludedBranchValues hides another slot's branch exactly when the effective trees coincide") {
      assertTrue(
        // no other slots: nothing excluded
        AnalyzeView.excludedBranchValues(None, Vector.empty, Some(tid)).isEmpty,
        // other slot's effective tree equals this slot's would-be effective tree
        AnalyzeView.excludedBranchValues(None, Vector(SlotCoordinate(scenarioB, None)), Some(tid))
          == Set(BranchBar.branchOptionValue(scenarioB)),
        // other slot pinned to a different tree: its branch stays available
        AnalyzeView.excludedBranchValues(None, Vector(SlotCoordinate(scenarioB, Some(tid2))), Some(tid)).isEmpty
      )
    },

    test("excludedTreeOverrideValues hides follow-active when another same-branch slot follows the active tree") {
      val values = "" :: List(tid.value.toString, tid2.value.toString)
      val excluded = AnalyzeView.excludedTreeOverrideValues(
        scenarioB, values, Vector(SlotCoordinate(scenarioB, None)), Some(tid)
      )
      // pinning the active tree resolves to the same pair as following it,
      // so its explicit value is excluded alongside ""
      assertTrue(excluded == Set("", tid.value.toString))
    },

    test("excludedTreeOverrideValues hides a pinned tree another same-branch slot already pins") {
      val values = "" :: List(tid.value.toString, tid2.value.toString)
      val excluded = AnalyzeView.excludedTreeOverrideValues(
        scenarioB, values, Vector(SlotCoordinate(scenarioB, Some(tid2))), Some(tid)
      )
      assertTrue(excluded == Set(tid2.value.toString))
    },

    test("excludedTreeOverrideValues excludes nothing across branches") {
      val values = "" :: List(tid.value.toString, tid2.value.toString)
      val excluded = AnalyzeView.excludedTreeOverrideValues(
        scenarioB, values, Vector(SlotCoordinate(BranchChoice.Main, None)), Some(tid)
      )
      assertTrue(excluded.isEmpty)
    },

    test("excludedTreeOverrideValues never treats an unparseable value as follow-active") {
      val values = "not-a-tree-id" :: Nil
      val excluded = AnalyzeView.excludedTreeOverrideValues(
        scenarioB, values, Vector(SlotCoordinate(scenarioB, None)), Some(tid)
      )
      assertTrue(excluded.isEmpty)
    }
  )
