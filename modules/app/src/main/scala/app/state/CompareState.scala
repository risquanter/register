package app.state

import com.raquo.laminar.api.L.{*, given}

import app.chart.PaletteData
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, TreeId}
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** What a Compare slot is pointed at: a branch, plus either the tab's active
  * tree (`treeOverride = None`, the default — the slot follows whatever tree
  * the tab has selected) or a specific other tree (`Some(t)`, cross-tree
  * comparison). The effective tree is resolved against the tab's active tree
  * at the point the slot is consumed, so a default slot keeps tracking the
  * active tree exactly as before.
  */
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId], at: Option[CommitHash] = None):
  /** The tree this slot compares on when the tab's selected tree is
    * `activeTree`: the pinned override, else the active tree. */
  def effectiveTree(activeTree: Option[TreeId]): Option[TreeId] =
    treeOverride.orElse(activeTree)

  /** True when this coordinate and `other` resolve to the same
    * (branch, effective tree, point-in-time) triple under the tab's current
    * active tree — the one relation every exclusion, engagement, and collision
    * check delegates to. The `at` pin is part of identity: a rewound slot on
    * the baseline's tree is a distinct pair (both chart), not a collision. */
  def samePairAs(other: SlotCoordinate, activeTree: Option[TreeId]): Boolean =
    branch == other.branch && effectiveTree(activeTree) == other.effectiveTree(activeTree) && at == other.at

  /** True when this coordinate resolves to the same pair the tab itself shows.
    * The tab is the coordinate (activeBranch, follow-active, live head). */
  def collidesWith(activeBranch: BranchChoice, activeTree: Option[TreeId]): Boolean =
    samePairAs(SlotCoordinate.activeTab(activeBranch), activeTree)

object SlotCoordinate:
  /** The active tab as a coordinate: its branch, following the active tree
    * (no pinned override), at the live head (no pin), so
    * `effectiveTree(activeTreeId) == activeTreeId`. */
  def activeTab(branch: BranchChoice): SlotCoordinate = SlotCoordinate(branch, None, None)

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

/** How multiple loaded trees are displayed. `Overlay` draws every visible
  * side's curves on one chart, coloured by branch family; `SideBySide` tiles
  * one chart per side on shared pinned axes. With no visible comparand side,
  * both render the plain single-tree chart.
  */
enum CompareLayout:
  case Overlay, SideBySide

/** One comparand picker slot. Slot identity is stable: choosing or clearing
  * one slot never moves another slot's coordinate, so the other slot's card
  * keeps its tree and selection untouched.
  */
final class CompareSlotState(val defaultPalette: Vector[HexColor]):
  val target: Var[CompareTarget] = Var(CompareTarget.NotChosen)

  /** Eye state: this slot's curves are excluded from the chart while true; all
    * other machinery (fetches, diffs, resets, viewer card) stays live. */
  val hidden: Var[Boolean] = Var(false)

  /** Mirror state: while true this row's selection continuously tracks the
    * baseline's charted set (the counterparts present in this row's tree), and
    * manual Ctrl+click selection on the row is locked. */
  val mirror: Var[Boolean] = Var(false)

  /** This slot's curve/tree-highlight colour family. Slot-keyed, not branch:
    * two slots on the same branch (past vs present of one tree) stay visually
    * distinct and are pickable independently. Defaults to the slot's family;
    * the card's `SlotPalettePicker` overwrites it, and two slots may hold the
    * same family deliberately. */
  val palette: Var[Vector[HexColor]] = Var(defaultPalette)

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

  /** The slot's rewind pin, derived from `target` exactly like `branchSignal`,
    * so the slider writes `target` (via `setAt`) and the pin follows without
    * `target` and pin ever drifting across a transaction. `None` = live head. */
  val atSignal: Signal[Option[CommitHash]] = target.signal.map {
    case CompareTarget.Target(c) => c.at
    case CompareTarget.NotChosen => None
  }.distinct

  /** Set the slot's rewind pin into its coordinate (no-op while nothing is
    * chosen — there is no tree to rewind). */
  def setAt(at: Option[CommitHash]): Unit =
    target.update {
      case CompareTarget.Target(c) => CompareTarget.Target(c.copy(at = at))
      case CompareTarget.NotChosen => CompareTarget.NotChosen
    }

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
  val diffState: ChangedNodesState,
  val historyState: TreeHistoryState
)

object CompareState:
  /** Branches on screen at most: one per palette family — the baseline (Aqua
    * default) plus a comparand pool slot per remaining family. Derived from
    * the palette list, so adding a family raises the cap automatically. */
  val MaxBranches: Int = PaletteData.namedFamilies.size

  val ComparedSlotCount: Int = MaxBranches - 1

  /** Default colour family per comparand pool slot (pool order) — the named
    * families other than the baseline's Aqua, one per slot. */
  val slotDefaultPalettes: Vector[Vector[HexColor]] = Vector(
    PaletteData.Purple, PaletteData.Orange, PaletteData.Green,
    PaletteData.Yellow, PaletteData.Red, PaletteData.Pink, PaletteData.Emerald
  )
  require(
    slotDefaultPalettes.length == ComparedSlotCount,
    "one default palette family per compare slot"
  )

  /** The pool slot a new row claims: lowest index not currently in use. */
  def nextFreeSlot(rows: Vector[Int], poolSize: Int): Option[Int] =
    (0 until poolSize).find(i => !rows.contains(i))

/** Per-tab UI state for the Analyze comparison view — how loaded trees are
  * displayed (overlay / side-by-side), which pool slots are on screen as
  * comparand rows, and the eye state hiding a side's chart contribution. Not
  * fetched from the server.
  */
final class CompareState:
  val layout: Var[CompareLayout] = Var(CompareLayout.Overlay)

  /** Baseline eye state — mirrors `CompareSlotState.hidden` for row 0. */
  val baselineHidden: Var[Boolean] = Var(false)

  /** Baseline rewind pin — the baseline is the active tab, not a slot, so its
    * history pin lives here rather than in a coordinate. `None` = live head. */
  val baselineAt: Var[Option[CommitHash]] = Var(None)

  /** Baseline (active tab) colour family — slot-keyed like the comparands, so
    * the baseline owns its colour independently of its branch. Default Aqua,
    * the single-branch chart's selected-node family. */
  val baselinePalette: Var[Vector[HexColor]] = Var(PaletteData.Aqua)

  /** Fixed pool; a pool slot is live only while its index is in `rows`. */
  val slots: Vector[CompareSlotState] =
    CompareState.slotDefaultPalettes.map(new CompareSlotState(_))

  /** Pool indices of the user-added comparand rows, in display order. */
  val rows: Var[Vector[Int]] = Var(Vector.empty)

  /** (poolIdx, target) pairs in row order — the engagement/dedup input. */
  val rowTargets: Signal[Vector[(Int, CompareTarget)]] =
    rows.signal
      .combineWith(Signal.combineSeq(slots.map(_.target.signal)))
      .map { (order, ts) => order.map(i => i -> ts(i)) }

  /** Per-pool-slot hidden flags (pool order). */
  val hiddenFlags: Signal[Vector[Boolean]] =
    Signal.combineSeq(slots.map(_.hidden.signal)).map(_.toVector)

  def addRow(): Unit =
    CompareState.nextFreeSlot(rows.now(), slots.size)
      .foreach(i => rows.update(_ :+ i))

  def removeRow(poolIdx: Int): Unit =
    rows.update(_.filterNot(_ == poolIdx))
    slots(poolIdx).target.set(CompareTarget.NotChosen)
    slots(poolIdx).hidden.set(false)
    slots(poolIdx).mirror.set(false)
    slots(poolIdx).palette.set(slots(poolIdx).defaultPalette)
