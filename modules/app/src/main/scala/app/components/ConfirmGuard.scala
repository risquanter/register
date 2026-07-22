package app.components

import org.scalajs.dom

/** Shared "confirm before discarding unsaved work" gate.
  *
  * Extracted from `TreePreview.scala` (previously private there) so the
  * tree-switch/new-tree/submit guards across the Design tab share one
  * definition instead of each inlining the same `!isDirty || dom.window.confirm(...)`
  * check.
  */
object ConfirmGuard:

  def proceedOrConfirm(isDirty: Boolean, message: String)(action: () => Unit): Unit =
    if !isDirty || dom.window.confirm(message) then action()
