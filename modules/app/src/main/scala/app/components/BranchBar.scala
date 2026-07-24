package app.components

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.chart.PaletteData
import app.components.FormInputs
import app.state.{BranchPaletteState, ScenarioState, ScenarioSubmitState, LoadState, Section}
import com.risquanter.register.domain.data.iron.{BranchChoice, ScenarioName}
import com.risquanter.register.http.responses.ScenarioSummaryResponse

/** Branch indicator + scenario management surfaces. Independent pieces:
  *
  *   - `chipForSection` — read-only topbar indicator, always visible in both
  *                        Design and Analyze — shows whichever section's own
  *                        branch is on screen.
  *   - `toolbar`         — the interactive "Scenarios ▾" menu (switch/create/
  *                        duplicate/delete), Design-only, sits atop TreeBuilderView.
  *   - `picker`          — plain "pick a branch" `<select>` with no management
  *                        actions, for Analyze's own baseline-branch selector.
  *
  * All are entirely absent from the DOM when scenarios are unavailable —
  * removed outright, not grayed out.
  */
object BranchBar:

  /** Source branch a new scenario forks from, as the `forkOf` request field
    * (a genuine wire `Option`: `None` = main's current head — main is not a
    * scenario). `Main` always forks main; `Current` forks whatever branch is
    * active, which on main issues the identical `forkOf`. [[forkTarget]] is a
    * pure function so the equivalence is unit-testable without a DOM harness.
    */
  enum CreateSource:
    case Main, Current

  def forkTarget(source: CreateSource, current: BranchChoice): Option[ScenarioName.ScenarioName] =
    source match
      case CreateSource.Main    => None
      case CreateSource.Current => current.toWire

  /** Plain display name for a branch — `"main"` or the scenario's own name.
    * Shared by the Compare branch cards and the chart legend's branch
    * suffixes; the topbar chip's own label adds the ⎇ glyph on top. */
  def branchDisplayName(choice: BranchChoice): String = choice match
    case BranchChoice.Main           => "main"
    case BranchChoice.Scenario(name) => name.value.toString

  private def branchLabel(choice: BranchChoice): String =
    s"⎇ ${branchDisplayName(choice)}"

  /** Topbar branch indicator. Inert — no click handler. Carries the shown
    * branch's palette swatch: its user-assigned family (`BranchPaletteState`),
    * Aqua — the single-branch chart's selected-node family — while
    * unassigned.
    *
    * Design and Analyze each own an independent `ScenarioState`. The topbar
    * chip is shared chrome above both views, so it shows whichever section's
    * own branch is presently on screen rather than always Design's.
    */
  def chipForSection(
    activeSection: Signal[Section],
    designScenarioState: ScenarioState,
    analyzeScenarioState: ScenarioState,
    scenariosEnabled: Signal[Boolean],
    branchPaletteState: BranchPaletteState
  ): HtmlElement =
    val shownBranch: Signal[BranchChoice] = activeSection
      .combineWith(designScenarioState.activeBranch.signal, analyzeScenarioState.activeBranch.signal)
      .map { case (section, designBranch, analyzeBranch) =>
        if section == Section.Design then designBranch else analyzeBranch
      }
    span(
      cls := "topbar-badge branch-chip",
      display <-- scenariosEnabled.map(if _ then "inline-flex" else "none"),
      span(
        cls := "branch-chip-swatch",
        styleAttr <-- branchPaletteState.paletteFor(shownBranch, PaletteData.Aqua)
          .map(family => s"background-color: ${PaletteData.familySwatch(family).value};")
      ),
      child.text <-- shownBranch.map(branchLabel)
    )

  /** Sentinel for "main" in a branch-picker `<select>`'s raw value protocol.
    * A `ScenarioName` can never collide with it (see `ScenarioName`'s
    * charset). Shared by every branch-picking `<select>` in the app
    * (`picker` below, AnalyzeView's Compare-mode "compare against" picker)
    * so the string and its parsing live in one place instead of being
    * copy-pasted per picker.
    */
  val mainSentinel = "__main__"

  def branchOptionValue(choice: BranchChoice): String = choice match
    case BranchChoice.Main           => mainSentinel
    case BranchChoice.Scenario(name) => name.value.toString

  /** An unparseable value falls back to `Main` — callers that also need to
    * distinguish "nothing chosen yet" (e.g. Compare's placeholder option)
    * check for the empty string themselves before calling this.
    */
  def parseBranchOptionValue(raw: String): BranchChoice =
    if raw == mainSentinel then BranchChoice.Main
    else ScenarioName.fromString(raw).toOption.fold(BranchChoice.Main)(BranchChoice.Scenario(_))

  /** Shared option list for every branch-picking `<select>`: main + every
    * scenario, rendered via `FormInputs.splitOptions` (keyed by each option's
    * own value) so recreating the currently-selected `<option>` doesn't reset
    * the browser's native `<select>` selection out from under the tracked Var.
    *
    * @param scenarios The held last-Loaded list
    *                  (`ScenarioState.lastLoadedScenarios`), NOT the raw
    *                  `LoadState` — a refresh cycles the raw signal through
    *                  Loading, and options built from that empty out for the
    *                  cycle's duration; the removed `<option>` drops the
    *                  browser's native selection even though the tracked Var
    *                  is unchanged, leaving the select on its placeholder
    *                  until the Var next emits.
    * @param excludeValues Raw option values to omit — e.g. each Compare-mode
    *                      picker hides the tab's own baseline branch
    *                      (comparing a branch to itself is a no-op) and the
    *                      other pickers' current choices (comparing a branch
    *                      to itself twice over is the same no-op). The
    *                      default omits nothing — every branch, including
    *                      main, is always offered.
    */
  def branchOptionEntries(
    scenarios: Signal[List[ScenarioSummaryResponse]],
    excludeValues: Signal[Set[String]] = Val(Set.empty)
  ): Signal[List[(String, String)]] =
    scenarios.combineWith(excludeValues).map { (list, excl) =>
      val all = (mainSentinel -> "main") :: list.map(s => s.name.value.toString -> s.name.value.toString)
      all.filterNot { case (v, _) => excl.contains(v) }
    }

  /** Plain "pick a branch" `<select>` — no switch/create/duplicate/delete
    * menu, unlike `toolbar`. Used for Analyze's own baseline-branch selector:
    * every branch including main is always a valid choice, since — unlike
    * the Compare-mode picker — this one isn't comparing against anything,
    * just choosing what Analyze itself is looking at.
    */
  def picker(
    scenarioState: ScenarioState,
    scenariosEnabled: Signal[Boolean],
    domCls: String = "baseline-branch-select"
  ): HtmlElement =
    select(
      cls := domCls,
      display <-- scenariosEnabled.map(if _ then "inline-block" else "none"),
      onMountCallback(_ => scenarioState.refresh()),
      FormInputs.splitOptions(branchOptionEntries(scenarioState.lastLoadedScenarios)),
      controlled(
        value <-- scenarioState.activeBranch.signal.map(branchOptionValue),
        onInput.mapToValue --> { raw => scenarioState.switchTo(parseBranchOptionValue(raw)) }
      )
    )

  /** Design-view "Scenarios" toolbar row: switch / create / duplicate /
    * delete / merge.
    *
    * @param menuOpen Owned by the caller (DesignView) so a click anywhere in
    *                 the surrounding panel can dismiss the menu — mirrors the
    *                 `pickerOpenFor` pattern in `TreeDetailView`.
    * @param onMergeRequest Opens the merge modal for the given scenario —
    *                 shown only while a scenario branch is active (main has
    *                 nothing to merge into itself). The modal itself is owned
    *                 by the caller alongside `ScenarioMergeState`.
    */
  def toolbar(
    scenarioState: ScenarioState,
    scenariosEnabled: Signal[Boolean],
    menuOpen: Var[Boolean],
    onMergeRequest: ScenarioName.ScenarioName => Unit
  ): HtmlElement =
    // None = create form closed. Some(forkOf) = open, forking from `forkOf`
    // (None = main, Some(name) = duplicating an existing scenario).
    val createTrigger: Var[Option[Option[ScenarioName.ScenarioName]]] = Var(None)
    val createState: Var[ScenarioSubmitState] = Var(ScenarioSubmitState.Idle)
    val createNameInput: Var[String] = Var("")

    def closeCreate(): Unit =
      createTrigger.set(None)
      createState.set(ScenarioSubmitState.Idle)
      createNameInput.set("")

    def closeAll(): Unit =
      menuOpen.set(false)
      closeCreate()

    div(
      cls := "scenario-toolbar",
      display <-- scenariosEnabled.map(if _ then "flex" else "none"),
      onMountCallback(_ => scenarioState.refresh()),
      span(cls := "scenario-toolbar-label", "Scenarios:"),
      div(
        cls := "scenario-dropdown",
        button(
          cls := "scenario-dropdown-trigger",
          tpe := "button",
          child.text <-- scenarioState.activeBranch.signal.map(n => s"${branchLabel(n)} ▾"),
          onClick.stopPropagation --> { _ => menuOpen.update(!_) }
        ),
        div(
          cls := "scenario-menu",
          display <-- menuOpen.signal.map(if _ then "block" else "none"),
          onClick.stopPropagation --> { _ => () },
          child <-- scenarioState.scenarios.combineWith(scenarioState.activeBranch.signal).map {
            (listState, current) =>
              renderMenuBody(scenarioState, listState, current, createTrigger, createState, createNameInput, closeCreate, closeAll, onMergeRequest)
          }
        )
      )
    )

  private def renderMenuBody(
    scenarioState: ScenarioState,
    listState: LoadState[List[ScenarioSummaryResponse]],
    current: BranchChoice,
    createTrigger: Var[Option[Option[ScenarioName.ScenarioName]]],
    createState: Var[ScenarioSubmitState],
    createNameInput: Var[String],
    closeCreate: () => Unit,
    closeAll: () => Unit,
    onMergeRequest: ScenarioName.ScenarioName => Unit
  ): HtmlElement =
    val names: List[ScenarioName.ScenarioName] = listState match
      case LoadState.Loaded(list) => list.map(_.name)
      case _                       => Nil

    div(
      menuItem("⎇ main", current == BranchChoice.Main, () => { scenarioState.switchTo(BranchChoice.Main); closeAll() }),
      names.map(name => scenarioRow(scenarioState, name, current == BranchChoice.Scenario(name), closeAll)),
      div(cls := "scenario-menu-divider"),
      child.maybe <-- createTrigger.signal.map {
        case None => Some(actionItem("+ Create from main…", () => createTrigger.set(Some(forkTarget(CreateSource.Main, current)))))
        case Some(_) => None
      },
      if current != BranchChoice.Main then
        child.maybe <-- createTrigger.signal.map {
          case None => Some(actionItem("⧉ Create from current…", () => createTrigger.set(Some(forkTarget(CreateSource.Current, current)))))
          case Some(_) => None
        }
      else emptyNode,
      current match
        case BranchChoice.Scenario(name) =>
          actionItem("⇄ Merge into main…", () => { onMergeRequest(name); closeAll() })
        case BranchChoice.Main => emptyNode,
      child.maybe <-- createTrigger.signal.map {
        case Some(forkOf) => Some(renderCreateForm(scenarioState, forkOf, createState, createNameInput, closeCreate))
        case None         => None
      }
    )

  private def menuItem(label: String, active: Boolean, onSelect: () => Unit): HtmlElement =
    div(
      cls := "scenario-menu-item",
      cls("scenario-menu-item--active") := active,
      label,
      onClick --> { _ => onSelect() }
    )

  /** Named-scenario row: switching (click the label) and deleting (click the
    * ✕) are both reachable from the same row, instead of delete being buried
    * as a separate "Delete current" item that only ever applied to whichever
    * branch you already happened to be on. `stopPropagation` on the ✕ keeps
    * a delete click from also firing the row's own switch-to action.
    */
  private def scenarioRow(
    scenarioState: ScenarioState,
    name: ScenarioName.ScenarioName,
    active: Boolean,
    closeAll: () => Unit
  ): HtmlElement =
    div(
      cls := "scenario-menu-item-row",
      span(
        cls := "scenario-menu-item-label",
        cls("scenario-menu-item--active") := active,
        s"⎇ ${name.value}",
        onClick --> { _ => scenarioState.switchTo(BranchChoice.Scenario(name)); closeAll() }
      ),
      span(
        cls := "scenario-menu-item-delete",
        title := s"Delete scenario '${name.value}'",
        "✕",
        onClick.stopPropagation --> { _ =>
          ConfirmGuard.proceedOrConfirm(true, s"Delete scenario '${name.value}'? This cannot be undone.") { () =>
            scenarioState.delete(name)
          }
          closeAll()
        }
      )
    )

  private def actionItem(label: String, onSelect: () => Unit): HtmlElement =
    div(cls := "scenario-menu-item", label, onClick --> { _ => onSelect() })

  private def renderCreateForm(
    scenarioState: ScenarioState,
    forkOf: Option[ScenarioName.ScenarioName],
    createState: Var[ScenarioSubmitState],
    nameInput: Var[String],
    onDone: () => Unit
  ): HtmlElement =
    def submit(): Unit =
      ScenarioName.fromString(nameInput.now()) match
        case Left(errors) => createState.set(ScenarioSubmitState.Failed(errors.map(_.message).mkString("; ")))
        case Right(name)  => scenarioState.create(name, forkOf, createState)

    div(
      cls := "scenario-create-form",
      onClick.stopPropagation --> { _ => () },
      FormInputs.textInput(
        labelText = "",
        valueVar = nameInput,
        errorSignal = createState.signal.map {
          case ScenarioSubmitState.Failed(msg) => Some(msg)
          case _                                => None
        },
        filter = s => s.matches("^[a-zA-Z0-9 _-]*$"),
        placeholderText = "scenario-name"
      ),
      div(
        cls := "scenario-create-actions",
        button(tpe := "button", "Create", onClick --> { _ => submit() }),
        button(tpe := "button", "Cancel", onClick --> { _ => onDone() })
      ),
      // Close the form automatically once creation succeeds.
      createState.signal.changes.collect { case ScenarioSubmitState.Success(_) => () } --> { _ => onDone() }
    )
