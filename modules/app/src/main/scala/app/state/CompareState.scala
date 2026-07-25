package app.state

import com.raquo.laminar.api.L.{*, given}

import com.risquanter.register.domain.data.iron.{BranchChoice, TreeId}
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** What a Compare slot is pointed at: a branch, plus either the tab's active
  * tree (`treeOverride = None`, the default — the slot follows whatever tree
  * the tab has selected) or a specific other tree (`Some(t)`, cross-tree
  * comparison). The effective tree is resolved against the tab's active tree
  * at the point the slot is consumed, so a default slot keeps tracking the
  * active tree exactly as before.
  */
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId]):
  /** The tree this slot compares on when the tab's selected tree is
    * `activeTree`: the pinned override, else the active tree. */
  def effectiveTree(activeTree: Option[TreeId]): Option[TreeId] =
    treeOverride.orElse(activeTree)

  /** True when this coordinate and `other` resolve to the same
    * (branch, effective tree) pair under the tab's current active tree — the
    * one relation every exclusion, engagement, and collision check delegates
    * to. */
  def samePairAs(other: SlotCoordinate, activeTree: Option[TreeId]): Boolean =
    branch == other.branch && effectiveTree(activeTree) == other.effectiveTree(activeTree)

  /** True when this coordinate resolves to the same pair the tab itself shows.
    * The tab is the coordinate (activeBranch, follow-active). */
  def collidesWith(activeBranch: BranchChoice, activeTree: Option[TreeId]): Boolean =
    samePairAs(SlotCoordinate.activeTab(activeBranch), activeTree)

object SlotCoordinate:
  /** The active tab as a coordinate: its branch, following the active tree
    * (no pinned override), so `effectiveTree(activeTreeId) == activeTreeId`. */
  def activeTab(branch: BranchChoice): SlotCoordinate = SlotCoordinate(branch, None)

/** What a Compare branch picker slot currently holds — a chosen coordinate,
  * or nothing.
  */
enum CompareTarget:
  case NotChosen
  case Target(coordinate: SlotCoordinate)

extension (target: CompareTarget)
  /** `Some(coordinate)` once chosen; `None` while nothing is chosen (don't
    * fire a diff/curve fetch). */
  def toCoordinate: Option[SlotCoordinate] = target match
    case CompareTarget.Target(c) => Some(c)
    case CompareTarget.NotChosen => None

/** How the comparison is displayed. `Overlay` draws every compared branch's
  * curves on one chart, coloured by branch family; `SideBySide` tiles one
  * chart per branch on shared pinned axes, keeping normal single-branch node
  * colours inside each panel.
  */
enum CompareMode:
  case Off, Overlay, SideBySide

/** One compared-branch picker slot. Slot identity is stable: choosing or
  * clearing one slot never moves another slot's coordinate, so the other
  * slot's card keeps its tree and selection untouched.
  */
final class CompareSlotState:
  val target: Var[CompareTarget] = Var(CompareTarget.NotChosen)

  /** The slot's chosen branch as a signal (Main while `NotChosen`) — the
    * branch identity for the palette, and (materialized strict at the one
    * seam that needs `.now()`, the slot's `TreeViewState`) the branch its
    * fetches read. Derived from `target`, so branch and target never drift
    * across a transaction — which is why the slot's tree-selection wiring
    * needs no "wait for the branch to catch up" guard. `.distinct` keeps it
    * emitting only on a genuine branch change, so a coordinate write that
    * changes only the tree override does not fire the branch-change machinery
    * in the slot's `TreeViewState`.
    */
  val branchSignal: Signal[BranchChoice] = target.signal.map {
    case CompareTarget.Target(c) => c.branch
    case CompareTarget.NotChosen => BranchChoice.Main
  }.distinct

/** A compared-branch slot's full bundle: its picker state, the per-branch
  * services built on it — an independent tree view (selection surface +
  * curve cache on the slot's chosen coordinate) and the content-hash diff
  * against the tab's active branch — and the palette family that identifies
  * the branch in the Overlay chart, its card swatch, and its tree
  * highlights. Constructed once at startup (`Main`), one per slot.
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
