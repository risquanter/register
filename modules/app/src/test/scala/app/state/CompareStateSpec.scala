package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import app.chart.PaletteData
import com.risquanter.register.domain.data.iron.{BranchChoice, TreeId, ScenarioName}

/** Pure tests for the Compare slot coordinate model: the target projections,
  * the branch signal derived from the target, and slot-identity stability. */
object CompareStateSpec extends ZIOSpecDefault:

  private val scenarioA = ScenarioName.fromString("scenario-a").toOption.get
  private val tid = TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val tid2 = TreeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  def spec = suite("CompareState")(

    test("toCoordinate projects a chosen target and is empty when NotChosen") {
      val coord = SlotCoordinate(BranchChoice.Scenario(scenarioA), Some(tid))
      assertTrue(
        CompareTarget.Target(coord).toCoordinate.contains(coord),
        CompareTarget.NotChosen.toCoordinate.isEmpty
      )
    },

    test("effectiveTree yields the active tree when unpinned, the override when pinned") {
      assertTrue(
        SlotCoordinate(BranchChoice.Main, None).effectiveTree(Some(tid)).contains(tid),
        SlotCoordinate(BranchChoice.Main, Some(tid2)).effectiveTree(Some(tid)).contains(tid2),
        SlotCoordinate(BranchChoice.Main, None).effectiveTree(None).isEmpty
      )
    },

    test("samePairAs is true exactly when branch and effective tree both agree") {
      val main = BranchChoice.Main
      val other = BranchChoice.Scenario(scenarioA)
      assertTrue(
        // both follow the active tree under the same active tree
        SlotCoordinate(main, None).samePairAs(SlotCoordinate(main, None), Some(tid)),
        // one pinned to the tree the other effectively resolves to
        SlotCoordinate(main, Some(tid)).samePairAs(SlotCoordinate(main, None), Some(tid)),
        // different branch, same effective tree
        !SlotCoordinate(main, None).samePairAs(SlotCoordinate(other, None), Some(tid)),
        // same branch, different effective tree
        !SlotCoordinate(main, Some(tid2)).samePairAs(SlotCoordinate(main, None), Some(tid))
      )
    },

    test("activeTab is the branch following the active tree, with no pinned override") {
      val tab = SlotCoordinate.activeTab(BranchChoice.Main)
      assertTrue(
        tab.treeOverride.isEmpty,
        tab.branch == BranchChoice.Main,
        tab.effectiveTree(Some(tid)).contains(tid)
      )
    },

    test("collidesWith is true only when the coordinate resolves to the active (branch, tree) pair") {
      val activeBranch = BranchChoice.Main
      val otherBranch = BranchChoice.Scenario(scenarioA)
      assertTrue(
        SlotCoordinate(activeBranch, None).collidesWith(activeBranch, Some(tid)),
        SlotCoordinate(activeBranch, Some(tid)).collidesWith(activeBranch, Some(tid)),
        !SlotCoordinate(activeBranch, Some(tid2)).collidesWith(activeBranch, Some(tid)),
        !SlotCoordinate(otherBranch, None).collidesWith(activeBranch, Some(tid)),
        !SlotCoordinate(otherBranch, Some(tid)).collidesWith(activeBranch, Some(tid)),
        !SlotCoordinate(activeBranch, Some(tid)).collidesWith(activeBranch, None)
      )
    },

    test("treeOverride None means follow the active tree; Some pins a specific tree") {
      assertTrue(
        SlotCoordinate(BranchChoice.Main, None).treeOverride.isEmpty,
        SlotCoordinate(BranchChoice.Main, Some(tid)).treeOverride.contains(tid)
      )
    },

    test("branchSignal derives the chosen branch, falling back to Main while NotChosen") {
      val owner = new ManualOwner
      val slot = new CompareSlotState(PaletteData.Purple)
      val observed = slot.branchSignal.observe(owner)
      val initial = observed.now()
      slot.target.set(CompareTarget.Target(SlotCoordinate(BranchChoice.Scenario(scenarioA), None)))
      val chosen = observed.now()
      slot.target.set(CompareTarget.NotChosen)
      val cleared = observed.now()
      owner.killSubscriptions()
      assertTrue(
        initial == BranchChoice.Main,
        chosen == BranchChoice.Scenario(scenarioA),
        cleared == BranchChoice.Main
      )
    },

    test("choosing or clearing one slot never moves another slot's target (stable slot identity)") {
      val state = new CompareState
      val s0 = state.slots(0)
      val s1 = state.slots(1)
      s0.target.set(CompareTarget.Target(SlotCoordinate(BranchChoice.Scenario(scenarioA), None)))
      val s1AfterChoose = s1.target.now()
      s0.target.set(CompareTarget.NotChosen)
      val s1AfterClear = s1.target.now()
      assertTrue(
        s1AfterChoose == CompareTarget.NotChosen,
        s1AfterClear == CompareTarget.NotChosen
      )
    },

    test("nextFreeSlot picks the lowest free pool index and is empty when the pool is full") {
      assertTrue(
        CompareState.nextFreeSlot(Vector.empty, 3).contains(0),
        CompareState.nextFreeSlot(Vector(0, 2), 3).contains(1),
        CompareState.nextFreeSlot(Vector(0, 1, 2), 3).isEmpty
      )
    },

    test("a slot's mirror flag defaults to off and its palette to its default family") {
      val slot = new CompareSlotState(PaletteData.Purple)
      assertTrue(!slot.mirror.now(), slot.palette.now() == PaletteData.Purple)
    },

    test("pool slots default to the per-slot palette families, distinct from the baseline") {
      val state = new CompareState
      assertTrue(
        state.baselinePalette.now() == PaletteData.Aqua,
        state.slots.map(_.palette.now()) == CompareState.slotDefaultPalettes,
        state.slots.head.palette.now() != state.baselinePalette.now()
      )
    },

    test("removeRow resets the slot's target, hidden, mirror, and palette and drops only that row") {
      val state = new CompareState
      state.addRow()
      state.addRow()
      state.slots(0).target.set(CompareTarget.Target(SlotCoordinate(BranchChoice.Scenario(scenarioA), Some(tid))))
      state.slots(0).hidden.set(true)
      state.slots(0).mirror.set(true)
      state.slots(0).palette.set(PaletteData.Red)
      state.removeRow(0)
      assertTrue(
        state.rows.now() == Vector(1),
        state.slots(0).target.now() == CompareTarget.NotChosen,
        !state.slots(0).hidden.now(),
        !state.slots(0).mirror.now(),
        state.slots(0).palette.now() == state.slots(0).defaultPalette,
        state.slots(1).target.now() == CompareTarget.NotChosen
      )
    },

    test("MaxBranches equals the number of palette families") {
      assertTrue(CompareState.MaxBranches == PaletteData.namedFamilies.size)
    }
  )
