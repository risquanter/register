package app

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.components.{Layout, SplitPane}
import app.state.{TreeBuilderState, TreeViewState, WorkspaceState, GlobalError, LoadState}
import app.views.{TreeBuilderView, TreePreview, TreeListView, TreeDetailView, LECChartView}
import app.core.ZJS

object Main:

  def main(args: Array[String]): Unit =
    val container = dom.document.querySelector("#app")

    // ── Workspace state (owns the key lifecycle) ──────────────────
    val wsState = new WorkspaceState

    val builderState = new TreeBuilderState
    val treeViewState = new TreeViewState(wsState.keySignal)

    // Global error state — safety net for errors with no per-view handler
    val globalError: Var[Option[GlobalError]] = Var(None)

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

    // ── Pre-validate workspace key from URL (Scenarios 2 & 3) ────
    wsState.preValidate(
      onTreesLoaded = trees =>
        treeViewState.availableTrees.set(LoadState.Loaded(trees)),
      onExpired = () =>
        globalError.set(Some(GlobalError.WorkspaceExpired(
          "Your previous workspace has expired and its data is no longer available. " +
          "Creating a new tree will start a fresh workspace."
        )))
    )

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(treeViewState),
      TreeDetailView(treeViewState)
    )

    val appElement = Layout(
      globalError = globalError.signal,
      onDismissError = () => globalError.set(None),
      SplitPane.horizontal(
        left = TreeBuilderView(builderState, treeViewState, wsState),
        right = SplitPane.vertical(
          top = SplitPane.horizontal(
            left = TreePreview(builderState),
            right = savedTreePanel,
            leftPercent = 50
          ),
          // LEC chart panel — wired to real chart selection state
          bottom = LECChartView(treeViewState.lecChartSpec),
          topPercent = 60
        ),
        leftPercent = 40
      )
    )

    render(container, appElement)
