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
        onBlur --> (_ => onBlurCallback())
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
        onBlur --> (_ => onBlurCallback())
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
    button(
      typ := "button",
      cls := "form-submit",
      disabled <-- isDisabled,
      text,
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
