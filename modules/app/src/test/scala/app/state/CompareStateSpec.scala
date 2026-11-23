package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import app.chart.{GridColumn, PaletteData}
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, NodeId, TreeId, ScenarioName}

/** Pure tests for the Compare slot coordinate model: the target projections,
  * the branch signal derived from the target, slot-identity stability, and
  * copy-for-compare. */
object CompareStateSpec extends ZIOSpecDefault:

  private val scenarioA = ScenarioName.fromString("scenario-a").toOption.get
  private val tid = TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val tid2 = TreeId.fromString("01HX9ABCDE0000000000000002").toOption.get
  private val node = NodeId.fromString("01HX9ABCDE0000000000000003").toOption.get

  /** A grid with one user column and `node` ticked under Inherent. */
  private val grid: CurveGrid =
    CurveGrid.empty.addColumn.flatMap(_.tick(GridColumn.Inherent, node, LECChartState.MaxTickedNodes)).get

  /** A coordinate at the head. */
  private def coord(branch: BranchChoice, treeOverride: Option[TreeId]): SlotCoordinate =
    SlotCoordinate(branch, treeOverride, None)

  def spec = suite("CompareState")(

    test("toCoordinate projects a chosen target and is empty when NotChosen") {
      val c = coord(BranchChoice.Scenario(scenarioA), Some(tid))
      assertTrue(
        CompareTarget.Target(c).toCoordinate.contains(c),
        CompareTarget.NotChosen.toCoordinate.isEmpty
      )
    },

    test("effectiveTree yields the active tree when unpinned, the override when pinned") {
      assertTrue(
        coord(BranchChoice.Main, None).effectiveTree(Some(tid)).contains(tid),
        coord(BranchChoice.Main, Some(tid2)).effectiveTree(Some(tid)).contains(tid2),
        coord(BranchChoice.Main, None).effectiveTree(None).isEmpty
      )
    },

    test("samePlaceAs is true exactly when branch, effective tree and pin all agree") {
      val main = BranchChoice.Main
      val other = BranchChoice.Scenario(scenarioA)
      val commit = CommitHash.fromString("c" * 40).toOption.get
      assertTrue(
        // both follow the active tree under the same active tree
        coord(main, None).samePlaceAs(coord(main, None), Some(tid)),
        // one pinned to the tree the other effectively resolves to
        coord(main, Some(tid)).samePlaceAs(coord(main, None), Some(tid)),
        // different branch, same effective tree
        !coord(main, None).samePlaceAs(coord(other, None), Some(tid)),
        // same branch, different effective tree
        !coord(main, Some(tid2)).samePlaceAs(coord(main, None), Some(tid)),
        // same branch and tree, different point in time
        !coord(main, None).samePlaceAs(SlotCoordinate(main, None, Some(commit)), Some(tid))
      )
    },

    test("activeTab carries the branch, the active tree and the given pin") {
      val commit = CommitHash.fromString("a" * 40).toOption.get
      val tabHead = SlotCoordinate.activeTab(BranchChoice.Main, None)
      val tabPinned = SlotCoordinate.activeTab(BranchChoice.Main, Some(commit))
      assertTrue(
        tabHead.treeOverride.isEmpty,
        tabHead.branch == BranchChoice.Main,
        tabHead.effectiveTree(Some(tid)).contains(tid),
        tabHead.at.isEmpty,
        tabPinned.at.contains(commit)
      )
    },

    test("collidesWith accounts for branch, tree and the active tab's own pin") {
      val activeBranch = BranchChoice.Main
      val otherBranch = BranchChoice.Scenario(scenarioA)
      val commit = CommitHash.fromString("b" * 40).toOption.get
      assertTrue(
        // active tab at head (activeAt = None): the (branch, tree) rules hold
        coord(activeBranch, None).collidesWith(activeBranch, Some(tid), None),
        coord(activeBranch, Some(tid)).collidesWith(activeBranch, Some(tid), None),
        !coord(activeBranch, Some(tid2)).collidesWith(activeBranch, Some(tid), None),
        !coord(otherBranch, None).collidesWith(activeBranch, Some(tid), None),
        !coord(otherBranch, Some(tid)).collidesWith(activeBranch, Some(tid), None),
        !coord(activeBranch, Some(tid)).collidesWith(activeBranch, None, None),
        // baseline rewound to `commit` — a comparand at head is NOT a collision
        !coord(activeBranch, None).collidesWith(activeBranch, Some(tid), Some(commit)),
        // and a comparand at the baseline's same pin IS a collision
        SlotCoordinate(activeBranch, None, Some(commit)).collidesWith(activeBranch, Some(tid), Some(commit))
      )
    },

    test("treeOverride None means follow the active tree; Some pins a specific tree") {
      assertTrue(
        coord(BranchChoice.Main, None).treeOverride.isEmpty,
        coord(BranchChoice.Main, Some(tid)).treeOverride.contains(tid)
      )
    },

    test("branchSignal derives the chosen branch, falling back to Main while NotChosen") {
      val owner = new ManualOwner
      val slot = new CompareSlotState(PaletteData.Purple)
      val observed = slot.branchSignal.observe(using owner)
      val initial = observed.now()
      slot.target.set(CompareTarget.Target(coord(BranchChoice.Scenario(scenarioA), None)))
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
      s0.target.set(CompareTarget.Target(coord(BranchChoice.Scenario(scenarioA), None)))
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

    test("copyToNextFree claims the lowest free slot with the source's coordinate, and its grid as the pending seed") {
      val state = new CompareState
      state.addRow()
      val source = SideSnapshot(coord(BranchChoice.Main, None), grid)
      val claimed = state.copyToNextFree(source)
      assertTrue(
        claimed.contains(1),
        state.rows.now() == Vector(0, 1),
        state.slots(1).target.now() == CompareTarget.Target(source.coordinate),
        state.slots(1).pendingSeed.now().contains(grid)
      )
    },

    test("copyToNextFree refuses when every slot is in use") {
      val state = new CompareState
      (0 until CompareState.ComparedSlotCount).foreach(_ => state.addRow())
      val source = SideSnapshot(coord(BranchChoice.Main, None), grid)
      assertTrue(state.copyToNextFree(source).isEmpty, state.rows.now().size == CompareState.ComparedSlotCount)
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

    test("removeRow resets the slot's target, hidden, mirror, palette and pending seed and drops only that row") {
      val state = new CompareState
      state.addRow()
      state.addRow()
      state.slots(0).target.set(CompareTarget.Target(coord(BranchChoice.Scenario(scenarioA), Some(tid))))
      state.slots(0).hidden.set(true)
      state.slots(0).mirror.set(true)
      state.slots(0).palette.set(PaletteData.Red)
      state.slots(0).pendingSeed.set(Some(grid))
      state.removeRow(0)
      assertTrue(
        state.rows.now() == Vector(1),
        state.slots(0).target.now() == CompareTarget.NotChosen,
        !state.slots(0).hidden.now(),
        !state.slots(0).mirror.now(),
        state.slots(0).palette.now() == state.slots(0).defaultPalette,
        state.slots(0).pendingSeed.now().isEmpty,
        state.slots(1).target.now() == CompareTarget.NotChosen
      )
    },

    test("MaxBranches equals the number of palette families") {
      assertTrue(CompareState.MaxBranches == PaletteData.namedFamilies.size)
    }
  )
