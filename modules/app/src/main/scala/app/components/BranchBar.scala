package app.components

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.components.FormInputs
import app.state.{ScenarioState, ScenarioSubmitState, LoadState}
import com.risquanter.register.domain.data.iron.ScenarioName
import com.risquanter.register.http.responses.ScenarioSummaryResponse

/** Branch indicator + scenario management surfaces (milestone-2b Phase B,
  * DD-9, PLAN-UI-MILESTONE-2B.md §4). Two independent pieces:
  *
  *   - `chip`    — read-only topbar indicator, always visible in both
  *                 Design and Analyze (§0: neutral chrome, no role colour;
  *                 §4.3: inert in Analyze).
  *   - `toolbar` — the interactive "Scenarios ▾" menu (switch/create/
  *                 duplicate/delete), Design-only, sits atop TreeBuilderView.
  *
  * Both are entirely absent from the DOM when scenarios are unavailable
  * (Variant R, §5 — decided 2026-07-19: full removal, not graying out).
  */
object BranchBar:

  /** Source branch a new scenario forks from. `Main` is always `None` (main's
    * current head); `Current` is whatever branch is active — which is itself
    * `None` while on main, making the two menu actions issue an identical
    * `forkOf` in that case. Extracted as a pure function (not inlined in the
    * click handlers) so the equivalence is directly unit-testable without a
    * Laminar/DOM harness — see [[forkTarget]] and `BranchBarSpec`.
    */
  enum CreateSource:
    case Main, Current

  def forkTarget(source: CreateSource, current: Option[ScenarioName.ScenarioName]): Option[ScenarioName.ScenarioName] =
    source match
      case CreateSource.Main    => None
      case CreateSource.Current => current

  private def branchLabel(name: Option[ScenarioName.ScenarioName]): String =
    s"⎇ ${name.map(_.value.toString).getOrElse("main")}"

  /** Topbar branch indicator. Inert — no click handler (§4.1/§4.3). */
  def chip(scenarioState: ScenarioState, scenariosEnabled: Signal[Boolean]): HtmlElement =
    span(
      cls := "topbar-badge branch-chip",
      display <-- scenariosEnabled.map(if _ then "inline-flex" else "none"),
      child.text <-- scenarioState.activeBranch.signal.map(branchLabel)
    )

  /** Design-view "Scenarios" toolbar row: switch / create / duplicate / delete.
    *
    * @param menuOpen Owned by the caller (DesignView) so a click anywhere in
    *                 the surrounding panel can dismiss the menu — mirrors the
    *                 `pickerOpenFor` pattern in `TreeDetailView`.
    */
  def toolbar(
    scenarioState: ScenarioState,
    scenariosEnabled: Signal[Boolean],
    menuOpen: Var[Boolean]
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
          child <-- scenarioState.scenarios.signal.combineWith(scenarioState.activeBranch.signal).map {
            (listState, current) =>
              renderMenuBody(scenarioState, listState, current, createTrigger, createState, createNameInput, closeCreate, closeAll)
          }
        )
      )
    )

  private def renderMenuBody(
    scenarioState: ScenarioState,
    listState: LoadState[List[ScenarioSummaryResponse]],
    current: Option[ScenarioName.ScenarioName],
    createTrigger: Var[Option[Option[ScenarioName.ScenarioName]]],
    createState: Var[ScenarioSubmitState],
    createNameInput: Var[String],
    closeCreate: () => Unit,
    closeAll: () => Unit
  ): HtmlElement =
    val names: List[ScenarioName.ScenarioName] = listState match
      case LoadState.Loaded(list) => list.map(_.name)
      case _                       => Nil

    div(
      menuItem("⎇ main", current.isEmpty, () => { scenarioState.switchTo(None); closeAll() }),
      names.map(name => menuItem(s"⎇ ${name.value}", current.contains(name), () => { scenarioState.switchTo(Some(name)); closeAll() })),
      div(cls := "scenario-menu-divider"),
      child.maybe <-- createTrigger.signal.map {
        case None => Some(actionItem("+ Create from main…", () => createTrigger.set(Some(forkTarget(CreateSource.Main, current)))))
        case Some(_) => None
      },
      if current.isDefined then
        child.maybe <-- createTrigger.signal.map {
          case None => Some(actionItem("⧉ Create from current…", () => createTrigger.set(Some(forkTarget(CreateSource.Current, current)))))
          case Some(_) => None
        }
      else emptyNode,
      child.maybe <-- createTrigger.signal.map {
        case Some(forkOf) => Some(renderCreateForm(scenarioState, forkOf, createState, createNameInput, closeCreate))
        case None         => None
      },
      current.map(name => destructiveItem("✕ Delete current…", () => {
        if dom.window.confirm(s"Delete scenario '${name.value}'? This cannot be undone.") then
          scenarioState.delete(name)
        closeAll()
      })).getOrElse(emptyNode)
    )

  private def menuItem(label: String, active: Boolean, onSelect: () => Unit): HtmlElement =
    div(
      cls := "scenario-menu-item",
      cls("scenario-menu-item--active") := active,
      label,
      onClick --> { _ => onSelect() }
    )

  private def actionItem(label: String, onSelect: () => Unit): HtmlElement =
    div(cls := "scenario-menu-item", label, onClick --> { _ => onSelect() })

  private def destructiveItem(label: String, onSelect: () => Unit): HtmlElement =
    div(cls := "scenario-menu-item scenario-menu-item--destructive", label, onClick --> { _ => onSelect() })

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
