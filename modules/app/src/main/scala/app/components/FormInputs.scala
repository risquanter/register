package app.components

import com.raquo.laminar.api.L.{*, given}
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
   * @param errorSignal Signal containing error message (None = valid)
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
    errorSignal: Signal[Option[String]],
    filter: String => Boolean = _ => true,
    onBlurCallback: () => Unit = () => (),
    placeholderText: String = "",
    inputModeAttr: String = "text",
    disabledSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- disabledSignal, labelText),
      input(
        typ := "text",
        inputMode := inputModeAttr,
        cls <-- errorSignal.map(err => if err.isDefined then "form-input error" else "form-input"),
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
      child.maybe <-- errorSignal.map(_.map(msg => 
        span(cls := "form-error", msg)
      ))
    )

  /**
   * Textarea for multi-line input (percentiles, quantiles).
   */
  def textAreaInput(
    labelText: String,
    valueVar: Var[String],
    errorSignal: Signal[Option[String]],
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
        cls <-- errorSignal.map(err => if err.isDefined then "form-input form-textarea error" else "form-input form-textarea"),
        placeholder := placeholderText,
        rows := rowCount,
        disabled <-- disabledSignal,
        controlled(
          value <-- valueVar.signal,
          onInput.mapToValue.filter(filter) --> valueVar
        ),
        onBlur --> (_ => if valueVar.now().nonEmpty then onBlurCallback())
      ),
      child.maybe <-- errorSignal.map(_.map(msg =>
        span(cls := "form-error", msg)
      ))
    )

  /**
   * Radio button group for mode selection.
   */
  def radioGroup[T](
    labelText: String,
    options: List[(T, String)],  // (value, displayLabel)
    selectedVar: Var[T],
    disabledSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- disabledSignal, labelText),
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
   * app uses to track the choice — confirmed in `AnalyzeView`'s
   * compare-branch picker and shared by the same mechanism in
   * `parentSelect` below (TODO.md item 26). Extracted here so every
   * dynamic-option `<select>` in the app gets this by construction instead
   * of copy-pasting the fix (or missing it) per call site.
   *
   * @param options Signal of (value, label) pairs, in display order.
   */
  def splitOptions(options: Signal[List[(String, String)]]): Modifier[HtmlElement] =
    children <-- options.split(_._1) { (key, initial, _) => option(value := key, initial._2) }

  /**
   * Cross-field error display (for errors not tied to a specific field).
   */
  def crossFieldError(errorSignal: Signal[Option[String]]): HtmlElement =
    div(
      cls := "cross-field-error",
      child.maybe <-- errorSignal.map(_.map(msg =>
        div(cls := "form-error", msg)
      ))
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
   * `parentVar` is corrected in exactly one situation: the option list
   * itself changes (a portfolio is added/renamed/removed elsewhere) and the
   * currently-held value is no longer among the valid choices — corrected to
   * `Unset`, not to an arbitrary substitute. That correction is driven off
   * `options` alone, deliberately *not* off `parentVar` itself — the earlier
   * version watched both together, which meant setting `parentVar` (from
   * here, or from a caller's own reset) re-triggered this same subscription.
   * Nested inside whatever the caller's own multi-step reset/populate
   * sequence was doing at the time, that self-triggering was the mechanism
   * behind two confirmed bugs (a locked node's own parent field silently
   * overwritten to point at itself; a leaf form reading as dirty after tree
   * creation even with every visible field genuinely empty). Watching
   * `options` only means this subscription never fires as a side effect of a
   * caller clearing or populating its own form — only when the world outside
   * this form actually changes — so it can't race with, or get raced by, a
   * caller's own reset sequence.
   *
   * @param parentVar Var holding the current selection.
   * @param options Signal of available parent names (may include rootLabel)
   * @param rootLabel Sentinel string representing the root (e.g., "(root)")
   * @param errorSignal Submit-time "Parent is required" / topology error, if any.
   */
  def parentSelect(
    parentVar: Var[ParentSelection],
    options: Signal[List[String]],
    rootLabel: String,
    disabledSignal: Signal[Boolean] = Val(false),
    errorSignal: Signal[Option[String]] = Val(None)
  ): HtmlElement =
    val unsetValue = ""

    def toValue(sel: ParentSelection): String = sel match
      case ParentSelection.Root         => rootLabel
      case ParentSelection.Portfolio(n) => n
      case ParentSelection.Unset        => unsetValue

    def fromValue(raw: String): ParentSelection =
      if raw == unsetValue then ParentSelection.Unset
      else if raw == rootLabel then ParentSelection.Root
      else ParentSelection.Portfolio(raw)

    def correctedDisplay(sel: ParentSelection, opts: List[String]): String =
      val display = toValue(sel)
      if display == unsetValue || opts.contains(display) then display else unsetValue

    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- disabledSignal, "Parent Portfolio"),
      select(
        cls := "form-input",
        cls("error") <-- errorSignal.map(_.isDefined),
        disabled <-- disabledSignal,
        option(value := unsetValue, disabled := true, "— none selected —"),
        splitOptions(options.map(_.map(opt => opt -> opt))),
        controlled(
          value <-- parentVar.signal.combineWith(options).map(correctedDisplay(_, _)),
          onChange.mapToValue.map(fromValue) --> parentVar
        ),
        // See the doc comment above: triggered by `options` alone, reading
        // `parentVar` only via `.now()` — never combined with it — so this
        // cannot re-fire itself and cannot fire as a side effect of a
        // caller's own reset/populate sequence touching `parentVar`.
        options --> { opts =>
          val current = toValue(parentVar.now())
          if current != unsetValue && !opts.contains(current) then
            parentVar.set(ParentSelection.Unset)
        }
      ),
      child.maybe <-- errorSignal.map(_.map(msg => span(cls := "form-error", msg)))
    )
