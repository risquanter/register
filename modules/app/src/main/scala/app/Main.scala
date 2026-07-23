package app

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.chart.PaletteData
import app.components.{AppShell, BranchBar}
import app.state.{NavigationState, TreeBuilderState, TreeListState, TreeViewState, WorkspaceState, GlobalError, HealthState, AnalyzeQueryState, DistributionChartState, ScenarioState, ScenarioListState, AppConfigState, CompareState, CompareSlot, ScenarioDiffState}
import app.views.{DesignView, AnalyzeView}
import app.core.ZJS

object Main:

  def main(args: Array[String]): Unit =
    val container = dom.document.querySelector("#app")

    // ── Workspace state (owns the key lifecycle) ──────────────────
    val wsState = new WorkspaceState

    // Global error state — safety net for errors with no per-view handler
    val globalError: Var[Option[GlobalError]] = Var(None)

    // Scenario (branch) + tree-selection state (BranchBar).
    // Design and Analyze each own an independent ScenarioState instance for
    // `activeBranch` — a single shared instance meant Design-only actions
    // (switching branches, handling "tree not found on this branch")
    // silently reset what Analyze was showing, with no feedback on the
    // Analyze side. `wsState` stays shared: it's the
    // actual server-side session, both views legitimately look at the same
    // one. The workspace's actual scenario list is likewise genuinely shared
    // server state, not per-view — a single ScenarioListState passed to both
    // instances (its list is read-only from ScenarioState's side — every
    // write goes through create/delete/refresh, never a raw Var), so
    // creating a scenario through Design's toolbar is immediately visible in
    // Analyze's own picker too, and deleting one resets any view's
    // `activeBranch` that was pointing at it (see ScenarioState's own
    // branch-fallback subscription).
    val scenarioListState = new ScenarioListState(wsState.keySignal, () => wsState.currentUserId)
    val designScenarioState = new ScenarioState(wsState.keySignal, scenarioListState, () => wsState.currentUserId)
    val analyzeScenarioState = new ScenarioState(wsState.keySignal, scenarioListState, () => wsState.currentUserId)
    val appConfigState = new AppConfigState
    appConfigState.refresh()

    // The workspace's tree lists, keyed by branch — shared for the same
    // reason as scenarioListState: one list per branch on the server, so one
    // owner here. A mutation through Design refreshes the entry Analyze reads.
    val treeListState = new TreeListState(
      wsState.keySignal, scenarioListState.scenarios, () => wsState.currentUserId
    )

    val builderState = new TreeBuilderState
    val designTreeViewState = new TreeViewState(
      wsState.keySignal, treeListState, globalError, () => wsState.currentUserId, designScenarioState.activeBranch.signal
    )
    val analyzeTreeViewState = new TreeViewState(
      wsState.keySignal, treeListState, globalError, () => wsState.currentUserId, analyzeScenarioState.activeBranch.signal
    )
    // Compare cards: each compared-branch slot gets its own full
    // TreeViewState — an independent tree view, Ctrl+click surface, and
    // curve cache on the slot's chosen branch — plus its own hash-diff
    // state. The palette family is the slot's branch identity in the
    // Overlay chart; passing it to the TreeViewState makes the card's tree
    // highlights match its curves.
    val analyzeCompareState = new CompareState
    val compareSlotPalettes = Vector(PaletteData.Purple, PaletteData.Orange)
    require(
      compareSlotPalettes.length == CompareState.ComparedSlotCount,
      "every compare slot needs its own palette family"
    )
    val analyzeCompareSlots = analyzeCompareState.slots.zip(compareSlotPalettes).map { (slotState, palette) =>
      new CompareSlot(
        state = slotState,
        treeViewState = new TreeViewState(
          wsState.keySignal, treeListState, globalError, () => wsState.currentUserId,
          slotState.chosenBranch.signal, userPalette = palette
        ),
        diffState = new ScenarioDiffState(wsState.keySignal, () => wsState.currentUserId),
        palette = palette
      )
    }
    val analyzeQueryState = new AnalyzeQueryState(
      keySignal = wsState.keySignal,
      selectedTreeId = analyzeTreeViewState.selectedTreeId.signal,
      userIdAccessor = () => wsState.currentUserId,
      branchAccessor = () => analyzeScenarioState.activeBranch.now()
    )

    // Register the global error observer at the ZJS chokepoint.
    // Every API call forked via forkProvided will automatically:
    //  - surface network/server errors in the error banner
    //  - auto-dismiss stale banners on the next successful call
    ZJS.registerErrorObserver(new ZJS.ErrorObserver:
      def onError(error: Throwable): Unit =
        globalError.set(Some(GlobalError.fromThrowable(error)))
      def onSuccess(): Unit =
        globalError.set(None)
    )

    // ── Pre-validate workspace key from URL ──────────────────────
    wsState.preValidate(
      onTreesLoaded = trees =>
        // Seeds the shared list's main entry — both views' TreeListView
        // mounts then find it Loaded and skip their own fetch
        // (ensureTreeListLoaded), instead of re-requesting what this
        // startup call just returned.
        treeListState.seedMain(trees),
      onExpired = () =>
        globalError.set(Some(GlobalError.WorkspaceExpired(
          "Your previous workspace has expired and its data is no longer available. " +
          "Creating a new tree will start a fresh workspace."
        )))
    )

    // ── Navigation state ────────────────────────────────────────
    val navState = new NavigationState

    // Workspace badge — indicates whether a workspace session is active
    val workspaceBadge: Signal[String] = wsState.keySignal.map:
      case Some(_) => "workspace active"
      case None    => "no workspace"

    // ── Health state (one-shot probe at startup) ─────────────────
    val healthState = new HealthState
    healthState.refresh()

    val appElement = AppShell(
      navState = navState,
      globalError = globalError.signal,
      onDismissError = () => globalError.set(None),
      healthStatus = healthState.status.signal,
      workspaceBadge = workspaceBadge,
      appVersion = appConfigState.appVersion.signal,
      branchChip = BranchBar.chipForSection(
        navState.activeSection.signal, designScenarioState, analyzeScenarioState, appConfigState.scenariosEnabled.signal
      ),
      designView = DesignView(
        builderState,
        designTreeViewState,
        wsState,
        new DistributionChartState(
          draftSignal    = builderState.draftSignal,
          keySignal      = wsState.keySignal,
          userIdAccessor = () => wsState.currentUserId
        ),
        designScenarioState,
        appConfigState
      ),
      analyzeView = AnalyzeView(
        analyzeTreeViewState,
        analyzeQueryState,
        analyzeScenarioState,
        appConfigState,
        analyzeCompareState,
        analyzeCompareSlots
      )
    )

    render(container, appElement)
