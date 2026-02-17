package app.state

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import com.risquanter.register.domain.data.iron.WorkspaceKeySecret
import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse}

import app.core.ZJS.*
import app.core.safeMessage

/** Central workspace lifecycle state — deferred bootstrap pattern.
  *
  * Manages the workspace key for all API calls:
  *   - Extracts key from URL on load (passive — no API call if absent)
  *   - Pre-validates existing keys via `listWorkspaceTrees` (detects expiry)
  *   - Bootstraps a new workspace via `POST /workspaces` on first tree create
  *   - Pushes key into browser URL via `history.replaceState`
  *
  * Three startup scenarios:
  *   1. No key (`/`)            → no API calls, form ready, bootstrap on first Create
  *   2. Valid key (`/w/{key}`)  → pre-validate loads tree list, workspace ready
  *   3. Expired key (`/w/{k}`) → pre-validate fails, key cleared, blue info banner
  *
  * All workspace-scoped views read the key from `keySignal`.
  * Extends `WorkspaceEndpoints` for ZJS bridge access to Tapir endpoint definitions.
  */
final class WorkspaceState extends WorkspaceEndpoints:

  // ── URL management ────────────────────────────────────────────

  private val workspacePathPattern = "^/w/([A-Za-z0-9_-]{22})(?:/.*)?$".r

  /** The active workspace key. None until bootstrap or pre-validation succeeds. */
  val workspaceKey: Var[Option[WorkspaceKeySecret]] = Var(extractKeyFromURL())

  /** Read-only signal for downstream state objects. */
  def keySignal: StrictSignal[Option[WorkspaceKeySecret]] = workspaceKey.signal

  /** Synchronous accessor for the current key. */
  def currentKey: Option[WorkspaceKeySecret] = workspaceKey.now()

  /** Whether a workspace is currently active. */
  def hasWorkspace: Boolean = currentKey.isDefined

  /** Extract workspace key from the current browser URL path.
    * Uses fromString for Iron-validated construction (ADR-022 R5, R7).
    */
  private def extractKeyFromURL(): Option[WorkspaceKeySecret] =
    dom.window.location.pathname match
      case workspacePathPattern(key) => WorkspaceKeySecret.fromString(key).toOption
      case _                         => None

  /** Push workspace key into the browser URL (no page reload). */
  private def pushKeyToURL(key: WorkspaceKeySecret): Unit =
    dom.window.history.replaceState(null, "", s"/w/${key.reveal}")

  /** Reset URL to root (used when clearing a stale key). */
  private def clearURL(): Unit =
    dom.window.history.replaceState(null, "", "/")

  // ── Pre-validation (Scenarios 2 & 3) ──────────────────────────

  /** Pre-validate an existing key extracted from URL.
    *
    * Called once from Main.scala on mount, only when key is present.
    * On success: populates the tree list (Scenario 2 — free data fetch).
    * On failure: clears the stale key, resets URL, signals WorkspaceExpired
    * via the provided callback (Scenario 3 — blue info banner).
    *
    * The call site (this method) provides the contextual differentiation:
    * we KNOW this is a pre-validation check, so failure means expired workspace,
    * not a generic mid-session 404.
    *
    * @param onTreesLoaded   Callback with tree list on success (populates TreeViewState)
    * @param onExpired        Callback to signal workspace expiry (sets GlobalError.WorkspaceExpired)
    */
  def preValidate(
    onTreesLoaded: List[SimulationResponse] => Unit,
    onExpired: () => Unit
  ): Unit =
    currentKey match
      case None => () // Scenario 1 — nothing to validate
      case Some(key) =>
        listWorkspaceTreesEndpoint(key)
          .tap(trees => zio.ZIO.succeed(onTreesLoaded(trees)))
          .tapError { _ =>
            zio.ZIO.succeed {
              workspaceKey.set(None)
              clearURL()
              onExpired()
            }
          }
          .runJs

  // ── Bootstrap (Scenario 1 — first tree create) ────────────────

  /** Bootstrap a new workspace with the first tree.
    *
    * Called by TreeBuilderView when the user submits their first tree
    * and no workspace exists yet. After success, all subsequent creates
    * use `createWorkspaceTreeEndpoint` directly.
    *
    * @param request    The tree definition from the builder form
    * @param onSuccess  Callback with the created tree (drives TreeViewState)
    * @param onError    Callback with error message (drives SubmitState.Failed)
    */
  def bootstrap(
    request: RiskTreeDefinitionRequest,
    onSuccess: SimulationResponse => Unit,
    onError: String => Unit
  ): Unit =
    bootstrapWorkspaceEndpoint((None, request))
      .tap { response =>
        zio.ZIO.succeed {
          workspaceKey.set(Some(response.workspaceKey))
          pushKeyToURL(response.workspaceKey)
          onSuccess(response.tree)
        }
      }
      .tapError(e => zio.ZIO.succeed(onError(e.safeMessage)))
      .runJs
