package app.components

import com.raquo.laminar.api.L.{*, given}

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
   * Auto-syncs parentVar when available options change (e.g., root slot claimed).
   *
   * @param parentVar Var holding None (root) or Some(portfolioName)
   * @param options Signal of available parent names (may include rootLabel)
   * @param rootLabel Sentinel string representing the root (e.g., "(root)")
   */
  def parentSelect(
    parentVar: Var[Option[String]],
    options: Signal[List[String]],
    rootLabel: String,
    disabledSignal: Signal[Boolean] = Val(false)
  ): HtmlElement =
    // Both the displayed <select> value and the auto-correct subscription
    // below need the same "is the current selection still valid, what's the
    // fallback" rule — one shared computation, so a future change to that
    // rule can't update one without the other and silently reintroduce a
    // display/data mismatch.
    def correctedDisplay(sel: Option[String], opts: List[String]): String =
      val display = sel.getOrElse(rootLabel)
      if opts.contains(display) then display else opts.headOption.getOrElse(rootLabel)

    val selectionAndOptions = parentVar.signal.combineWith(options)

    div(
      cls := "form-field",
      label(cls := "form-label", cls("form-label--locked") <-- disabledSignal, "Parent Portfolio"),
      select(
        cls := "form-input",
        disabled <-- disabledSignal,
        controlled(
          value <-- selectionAndOptions.map(correctedDisplay(_, _)),
          onChange.mapToValue.map { v => if v == rootLabel then None else Some(v) } --> parentVar
        ),
        // Auto-correct parentVar itself — not just the <select>'s displayed
        // value above — whenever either it or the option list changes and
        // the current selection is no longer valid. E.g. a Templating draft
        // copies its source node's `None` (root) parent, but root may
        // already be taken by a real committed portfolio, making `None`
        // invalid for a *new* node. Subscribing only to `options` (as before)
        // misses this: `parentVar` changing (not `options`) is what makes
        // the selection invalid here, so the correction never ran — the
        // dropdown displayed the corrected option (via the `value <--`
        // binder above) while the Var actually used at submit time silently
        // kept the stale, invalid selection.
        selectionAndOptions --> { (sel, opts) =>
          val current = sel.getOrElse(rootLabel)
          if !opts.contains(current) then
            // No correction possible when opts is empty (nothing valid to
            // fall back to yet) — leave parentVar as-is rather than forcing
            // it to root, which would itself be an invalid, unlisted choice.
            opts.headOption.foreach { v =>
              parentVar.set(if v == rootLabel then None else Some(v))
            }
        },
        children <-- options.map { opts =>
          opts.map { opt =>
            option(
              value := opt,
              opt
            )
          }
        }
      )
    )
