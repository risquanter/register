package app.state

import com.raquo.laminar.api.L.{*, given}

import com.risquanter.register.domain.data.iron.BranchChoice
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** What a Compare branch picker slot currently holds. Built on `BranchChoice`
  * (the single internal spelling of "main vs. a named scenario") — this enum
  * only adds the picker-specific "nothing chosen yet" case.
  */
enum CompareTarget:
  case NotChosen
  case Target(choice: BranchChoice)

extension (target: CompareTarget)
  /** `None` if nothing has been chosen yet (don't fire a diff/curve fetch);
    * `Some(choice)` otherwise. */
  def toChoice: Option[BranchChoice] = target match
    case CompareTarget.NotChosen      => None
    case CompareTarget.Target(choice) => Some(choice)

/** How the comparison is displayed. `Overlay` draws every compared branch's
  * curves on one chart, coloured by branch family; `SideBySide` tiles one
  * chart per branch on shared pinned axes, keeping normal single-branch node
  * colours inside each panel.
  */
enum CompareMode:
  case Off, Overlay, SideBySide

/** One compared-branch picker slot. Slot identity is stable: choosing or
  * clearing one slot never moves another slot's branch, so the other slot's
  * card keeps its tree and selection untouched.
  */
final class CompareSlotState:
  val target: Var[CompareTarget] = Var(CompareTarget.NotChosen)

  /** The slot's target as a plain `BranchChoice` — the branch signal the
    * slot's `TreeViewState` is constructed with, which needs a definite
    * branch before any choice exists. Falls back to `Main` while `target` is
    * `NotChosen`, so no state tied to it (branch-keyed list fetches, the
    * key-change reload in `TreeViewState`) can act on a branch that may no
    * longer exist. Synced here so views write `target` only. */
  val chosenBranch: Var[BranchChoice] = Var(BranchChoice.Main)

  target.signal.changes.foreach {
    case CompareTarget.Target(choice) => chosenBranch.set(choice)
    case CompareTarget.NotChosen      => chosenBranch.set(BranchChoice.Main)
  }(using unsafeWindowOwner)

/** A compared-branch slot's full bundle: its picker state, the per-branch
  * services built on it — an independent tree view (selection surface +
  * curve cache on the slot's chosen branch) and the content-hash diff
  * against the tab's active branch — and the palette family that identifies
  * the branch in the Overlay chart, its card swatch, and its tree
  * highlights. The palette is a signal: the slot's chosen branch's
  * user-assigned family (`BranchPaletteState`), falling back to the slot's
  * own default family while the branch has no assignment. Constructed once
  * at startup (`Main`), one per slot.
  */
final class CompareSlot(
  val state: CompareSlotState,
  val treeViewState: TreeViewState,
  val diffState: ScenarioDiffState,
  val palette: Signal[Vector[HexColor]]
)

object CompareState:
  /** The comparison cap: at most this many branches on screen — the tab's
    * active branch plus `MaxBranches - 1` compared slots. Raising the cap
    * means changing this constant and giving each new slot its palette
    * family (`Main.scala`, which asserts the two lists agree in length). */
  val MaxBranches: Int = 3

  val ComparedSlotCount: Int = MaxBranches - 1

/** Per-tab UI state for the Analyze comparison mode — how the comparison is
  * displayed (off / overlay / side-by-side), and which branches (if any) to
  * compare the tab's active branch against. Not fetched from the server.
  */
final class CompareState:
  val mode: Var[CompareMode] = Var(CompareMode.Off)

  /** The compared-branch picker slots, in display order. */
  val slots: Vector[CompareSlotState] =
    Vector.fill(CompareState.ComparedSlotCount)(new CompareSlotState)

  /** True in either comparison mode — everything shared by Overlay and
    * Side-by-side (branch cards, ✎ diff markers, entry seeding, the compare
    * cards' fetches) keys off this rather than the specific mode. */
  val comparisonOn: Signal[Boolean] = mode.signal.map(_ != CompareMode.Off)

  def comparisonOnNow: Boolean = mode.now() != CompareMode.Off

  /** Every slot's target, in slot order — what the card stack, the panel
    * grid, and the pickers' mutual-exclusion lists all read. */
  val targets: Signal[Vector[CompareTarget]] =
    Signal.combineSeq(slots.map(_.target.signal)).map(_.toVector)
