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
    inputModeAttr: String = "text"
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", labelText),
      input(
        typ := "text",
        inputMode := inputModeAttr,
        cls <-- errorSignal.map(err => if err.isDefined then "form-input error" else "form-input"),
        placeholder := placeholderText,
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
    inputModeAttr: String = "text"
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", labelText),
      textArea(
        inputMode := inputModeAttr,
        cls <-- errorSignal.map(err => if err.isDefined then "form-input form-textarea error" else "form-input form-textarea"),
        placeholder := placeholderText,
        rows := rowCount,
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
    selectedVar: Var[T]
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", labelText),
      div(
        cls := "radio-group",
        options.map { case (optValue, optLabel) =>
          label(
            cls := "radio-option",
            input(
              typ := "radio",
              nameAttr := labelText.toLowerCase.replace(" ", "-"),
              checked <-- selectedVar.signal.map(_ == optValue),
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
    rootLabel: String
  ): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", "Parent Portfolio"),
      select(
        cls := "form-input",
        controlled(
          value <-- parentVar.signal.combineWith(options).map { (sel, opts) =>
            val display = sel.getOrElse(rootLabel)
            if opts.contains(display) then display else opts.headOption.getOrElse(rootLabel)
          },
          onChange.mapToValue.map { v => if v == rootLabel then None else Some(v) } --> parentVar
        ),
        // Auto-sync parentVar when options change and current selection becomes invalid
        options --> { opts =>
          val current = parentVar.now().getOrElse(rootLabel)
          if !opts.contains(current) then
            opts.headOption match
              case Some(v) if v == rootLabel => parentVar.set(None)
              case Some(v) => parentVar.set(Some(v))
              case None => ()
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
