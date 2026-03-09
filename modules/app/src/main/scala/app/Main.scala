package app

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.components.AppShell
import app.state.{NavigationState, TreeBuilderState, TreeViewState, WorkspaceState, GlobalError, LoadState, HealthState, AnalyzeQueryState}
import app.views.{DesignView, AnalyzeView}
import app.core.ZJS

object Main:

  def main(args: Array[String]): Unit =
    val container = dom.document.querySelector("#app")

    // ── Workspace state (owns the key lifecycle) ──────────────────
    val wsState = new WorkspaceState

    val builderState = new TreeBuilderState
    val treeViewState = new TreeViewState(wsState.keySignal, () => wsState.currentUserId)
    val analyzeQueryState = new AnalyzeQueryState

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
      designView = DesignView(builderState, treeViewState, wsState),
      analyzeView = AnalyzeView(treeViewState, analyzeQueryState)
    )

    render(container, appElement)
