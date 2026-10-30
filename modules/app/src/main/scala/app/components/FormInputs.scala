package app.components

import com.raquo.laminar.api.L.*
import app.state.ParentSelection

/**
 * Reusable form input components with:
 * - Input filtering (prevent invalid characters)
 * - Error display with red border
 * - Blur-triggered validation
 */
object FormInputs:

  /**
   * Text input with filtering and error display.
   * 
   * @param labelText Label shown above the input
   * @param valueVar Var to bind the input value
   * @param errorSignal Every message the input shows, one line each (empty = valid)
   * @param filter Function to filter allowed input characters
   * @param onBlurCallback Callback when field loses focus (for marking touched)
   * @param placeholderText Placeholder text
   * @param inputModeAttr HTML inputMode attribute ("text", "numeric", "decimal", "email", "url")
   *                      Controls mobile device keyboard - "numeric" shows number pad,
   *                      "decimal" shows number pad with decimal point
   */
  def textInput(
    labelText: String,
    valueVar: Var[String],
    errorSignal: Signal[List[String]],
    filter: String => Boolean = _ => true,
    onBlurCallback: () => Unit = () => (),
    placeholderText: String = "",
    inputModeAttr: String = "text",
    disabledSignal: Signal[Boolean] = Val(false),
    // Separate from `disabledSignal`: a field can be disabled without being
    // "locked" (e.g. FormMode.Inactive — the other node type is selected,
    // this field is irrelevant, but nothing here is a saved value being
    // protected from edits). Defaults to false (no caller currently disables
    // a field without also being able to say whether it's genuinely locked).
    lockedSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- lockedSignal, labelText),
      input(
        typ := "text",
        inputMode := inputModeAttr,
        cls <-- errorSignal.map {
          case Nil => "form-input"
          case _   => "form-input error"
        },
        placeholder := placeholderText,
        disabled <-- disabledSignal,
        controlled(
          value <-- valueVar.signal,
          onInput.mapToValue.filter(filter) --> valueVar
        ),
        // Guard: only mark touched if user actually typed something and then left.
        // Prevents showing "Name is required" when clicking into a pristine empty
        // field and clicking out — the field stays untouched until meaningful input.
        onBlur --> (_ => if valueVar.now().nonEmpty then onBlurCallback())
      ),
      errorLines(errorSignal)
    )

  /** One line per message under an input, in the order given. */
  private def errorLines(errorSignal: Signal[List[String]]): Modifier[HtmlElement] =
    children <-- errorSignal.map(_.map(msg => span(cls := "form-error", msg)))

  /**
   * Textarea for multi-line input (percentiles, quantiles).
   */
  def textAreaInput(
    labelText: String,
    valueVar: Var[String],
    errorSignal: Signal[List[String]],
    filter: String => Boolean = _ => true,
    onBlurCallback: () => Unit = () => (),
    placeholderText: String = "",
    rowCount: Int = 2,
    inputModeAttr: String = "text",
    disabledSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- disabledSignal, labelText),
      textArea(
        inputMode := inputModeAttr,
        cls <-- errorSignal.map {
          case Nil => "form-input form-textarea"
          case _   => "form-input form-textarea error"
        },
        placeholder := placeholderText,
        rows := rowCount,
        disabled <-- disabledSignal,
        controlled(
          value <-- valueVar.signal,
          onInput.mapToValue.filter(filter) --> valueVar
        ),
        onBlur --> (_ => if valueVar.now().nonEmpty then onBlurCallback())
      ),
      errorLines(errorSignal)
    )

  /**
   * Radio button group for mode selection.
   */
  def radioGroup[T](
    labelText: String,
    options: List[(T, String)],  // (value, displayLabel)
    selectedVar: Var[T],
    disabledSignal: Signal[Boolean] = Val(false),
    // See `textInput`'s matching parameter doc.
    lockedSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- lockedSignal, labelText),
      div(
        cls := "radio-group",
        options.map { case (optValue, optLabel) =>
          label(
            cls := "radio-option",
            input(
              typ := "radio",
              nameAttr := labelText.toLowerCase.replace(" ", "-"),
              checked <-- selectedVar.signal.map(_ == optValue),
              disabled <-- disabledSignal,
              onChange.mapTo(optValue) --> selectedVar
            ),
            span(cls := "radio-label", optLabel)
          )
        }
      )
    )

  /**
   * Submit button with disabled state based on validation.
   */
  def submitButton(
    text: String,
    isDisabled: Signal[Boolean],
    onClickCallback: () => Unit
  ): HtmlElement =
    submitButton(Signal.fromValue(text), isDisabled, onClickCallback)

  def submitButton(
    textSignal: Signal[String],
    isDisabled: Signal[Boolean],
    onClickCallback: () => Unit
  ): HtmlElement =
    button(
      typ := "button",
      cls := "form-submit",
      disabled <-- isDisabled,
      child.text <-- textSignal,
      onClick --> (_ => onClickCallback())
    )

  /**
   * Renders the reactive portion of a `<select>`'s option list, keyed by
   * each entry's own value string via `.split` — an option already present
   * keeps its same DOM node across list-signal emissions instead of being
   * torn down and recreated.
   *
   * Recreating the currently-selected `<option>` resets the browser's
   * native `<select>` selection independently of whatever Var/Signal the
   * app uses to track the choice, which is why `AnalyzeView`'s
   * compare-branch picker and `parentSelect` below both use this. Every
   * dynamic-option `<select>` in the app gets keyed options by construction
   * rather than per call site.
   *
   * @param options Signal of (value, label) pairs, in display order.
   * @param disabledValues Option values to render as `disabled` (present but
   *                       not user-selectable) rather than removing them — so
   *                       an excluded value that is the current selection stays
   *                       displayed instead of dropping the select to its
   *                       placeholder. Default disables nothing.
   */
  def splitOptions(
    options: Signal[List[(String, String)]],
    disabledValues: Signal[Set[String]] = Val(Set.empty)
  ): Modifier[HtmlElement] =
    children <-- options.split(_._1) { (key, initial, _) =>
      option(value := key, disabled <-- disabledValues.map(_.contains(key)), initial._2)
    }

  /**
   * A form's own line: the refusals not tied to one input, one per line.
   */
  def crossFieldError(errorSignal: Signal[List[String]]): HtmlElement =
    div(
      cls := "cross-field-error",
      children <-- errorSignal.map(_.map(msg => div(cls := "form-error", msg)))
    )

  /**
   * Dropdown for selecting a parent node.
   *
   * Three-state (`ParentSelection`): `Unset` — a real, disabled "— none
   * selected —" placeholder option, never itself submittable — `Root`, or a
   * named `Portfolio`. Callers set `parentVar` to `Unset` at every explicit
   * clear/populate call site (`PortfolioFormView`/`RiskLeafFormView`); there
   * is no computed fallback value for this component or its callers to
   * silently pick on the user's behalf (see `ParentSelection`'s own doc).
   *
   * `parentVar` is corrected in exactly one situation: a NAMED portfolio it
   * currently points at has genuinely stopped existing (renamed or removed,
   * by the *other* sub-form, while this one sits on some other target) —
   * corrected to `Unset`, not to an arbitrary substitute. This check is
   * driven off `existingNames` (every portfolio name in the tree, unfiltered
   * — see `TreeBuilderState.allPortfolioNames`), deliberately NOT off
   * `options` (the self-exclusion-adjusted list actually rendered as
   * `<option>`s): `options` also shrinks on every plain target switch —
   * viewing/editing a DIFFERENT node excludes that node's own name and,
   * often, `rootLabel` — for reasons that have nothing to do with whether
   * the value already sitting in `parentVar` (still holding the *previous*
   * target's parent, an instant before that target's own populate step
   * overwrites it) stopped existing. `Root` is likewise never auto-corrected
   * by this check — its exclusion from `options` is *always* a self/target
   * relative judgement (who's asking), never "root was deleted", so there is
   * nothing here for it to legitimately go stale against.
   *
   * Confirmed bug this replaced: switching the active target from node A
   * (parent = B) straight to node B itself made `options` recompute to
   * exclude B (self-exclusion) before B's own populate step (deferred one
   * Airstream transaction later, since it fires from within another
   * signal's observer) had actually written B's real parent into
   * `parentVar` — this subscription, reading `parentVar.now()` in between,
   * saw the *stale* "B" (A's old parent) missing from B's own options and
   * "corrected" it to `Unset`, in an Airstream transaction that ran *after*
   * populate's own write, clobbering it. The same thing reproduced for the
   * root case: editing the node that legitimately holds root showed the
   * dropdown stuck on "— none selected —", unselectable, because the exact
   * same stale read fired for `Root` too. Keying this check off
   * `existingNames` instead removes the coupling: that signal only changes
   * on a real add/rename/remove, never on a target switch, so it can no
   * longer race populate.
   *
   * @param parentVar Var holding the current selection.
   * @param options Signal of available parent names (may include rootLabel)
   * @param rootLabel Sentinel string representing the root (e.g., "(root)")
   * @param errorSignal Submit-time "Parent is required" / topology refusals, one line each.
   * @param existingNames Every portfolio name currently in the tree,
   *   unfiltered by self-exclusion — see `TreeBuilderState.allPortfolioNames`.
   * @param lockedSignal See `textInput`'s matching parameter doc. Defaults to
   *   mirroring `disabledSignal`.
   */
  def parentSelect(
    parentVar: Var[ParentSelection],
    options: Signal[List[String]],
    rootLabel: String,
    existingNames: Signal[Set[String]],
    disabledSignal: Signal[Boolean] = Val(false),
    errorSignal: Signal[List[String]] = Val(Nil),
    lockedSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    val unsetValue = ""

    def toValue(sel: ParentSelection): String = sel match
      case ParentSelection.Root     => rootLabel
      case ParentSelection.Under(n) => n
      case ParentSelection.Unset    => unsetValue

    def fromValue(raw: String): ParentSelection = raw match
      case `unsetValue` => ParentSelection.Unset
      case `rootLabel`  => ParentSelection.Root
      case name         => ParentSelection.Under(name)

    def correctedDisplay(sel: ParentSelection, opts: List[String]): String =
      val display = toValue(sel)
      if display == unsetValue || opts.contains(display) then display else unsetValue

    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- lockedSignal, "Parent Portfolio"),
      select(
        cls := "form-input",
        cls("error") <-- errorSignal.map(_.nonEmpty),
        disabled <-- disabledSignal,
        option(value := unsetValue, disabled := true, "— none selected —"),
        splitOptions(options.map(_.map(opt => opt -> opt))),
        controlled(
          value <-- parentVar.signal.combineWith(options).map(correctedDisplay(_, _)),
          onChange.mapToValue.map(fromValue) --> parentVar
        ),
        // See the doc comment above: triggered by `existingNames` alone
        // (never by a target switch), reading `parentVar` only via `.now()`
        // — never combined with it — so this cannot re-fire itself and
        // cannot fire as a side effect of a caller's own reset/populate
        // sequence touching `parentVar`.
        existingNames --> { names =>
          parentVar.now() match
            case ParentSelection.Under(name) if !names.contains(name) =>
              parentVar.set(ParentSelection.Unset)
            case _ => ()
        }
      ),
      errorLines(errorSignal)
    )
