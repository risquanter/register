package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import com.risquanter.register.domain.data.iron.NodeId

/** Bidirectional hover bridge between Vega chart and Laminar tree (§3B).
  *
  * Manages the shared `hoveredCurveId` state and provides feedback-loop-safe
  * communication between the Vega signal system and Laminar reactive state.
  *
  * The guard flag `externallySet` prevents hover echo:
  *   - Tree → Chart: sets `externallySet` before pushing to Vega
  *   - Chart → Tree: the signal listener ignores values matching `externallySet`
  *
  * @see §3B.5 in PLAN-QUERY-RESULT-VISUALIZATION_v4.md
  */
final class ChartHoverBridge:

  /** Currently hovered curve ID, shared between chart and tree views. */
  val hoveredCurveId: Var[Option[NodeId]] = Var(None)

  /** Guard flag to prevent feedback loops (§3B.5). */
  private var externallySet: Option[NodeId] = None

  private var currentHandler: js.UndefOr[js.Function2[String, js.Dynamic, Unit]] = js.undefined

  /** Attach a signal listener to the Vega view for Chart → Tree hover (§3B.2).
    *
    * Listens to the `"hover"` signal. On change, parses the selection
    * value to extract a NodeId and writes to `hoveredCurveId`
    * (unless the hover was externally set by `pushToView`).
    */
  def attachToView(view: js.Dynamic): Unit =
    val handler: js.Function2[String, js.Dynamic, Unit] = { (_, value) =>
      val hoveredId = ChartHoverBridge.parseHoverSignal(value)
      if hoveredId != externallySet then
        hoveredCurveId.set(hoveredId)
    }
    currentHandler = handler
    view.addSignalListener("hover", handler)

  /** Remove the signal listener from the Vega view.
    *
    * Called during cleanup before re-embed or unmount.
    */
  def detachFromView(view: js.Dynamic): Unit =
    currentHandler.foreach { h =>
      view.removeSignalListener("hover", h)
      currentHandler = js.undefined
    }

  /** Push a hover state from Laminar to Vega (Tree → Chart) (§3B.3).
    *
    * Builds a selection store and sets it on the Vega view via
    * `view.signal("hover_store", store).run()`. Sets the guard flag
    * before running to prevent the signal listener from echoing.
    */
  def pushToView(view: js.Dynamic, nodeId: Option[NodeId]): Unit =
    val store = ChartHoverBridge.buildSelectionStore(nodeId)
    externallySet = nodeId
    view.signal("hover_store", store).run()


/** Named pure functions for Vega JS boundary parsing (FL1).
  *
  * `parseHoverSignal` and `buildSelectionStore` encapsulate all
  * `asInstanceOf` casts at the Vega JS boundary and are independently
  * unit-testable.
  */
object ChartHoverBridge:

  /** Extract a NodeId from a Vega selection signal value.
    *
    * Returns None on unexpected shapes — never throws.
    * Encapsulates `asInstanceOf` casts at the Vega JS boundary.
    */
  def parseHoverSignal(value: js.Dynamic): Option[NodeId] =
    try
      val arr = value.asInstanceOf[js.Array[js.Dynamic]]
      if arr.length > 0 then
        val values = arr(0).values.asInstanceOf[js.Array[String]]
        if values.length > 0 then NodeId.fromString(values(0)).toOption else None
      else None
    catch case _: Throwable => None

  /** Build a Vega selection store for a given NodeId (or empty if None).
    *
    * The store format matches Vega-Lite point selection internals:
    * `[{fields: [{field: "curveId", type: "E"}], values: ["<ulid>"]}]`
    */
  def buildSelectionStore(nodeId: Option[NodeId]): js.Array[js.Any] =
    nodeId match
      case Some(id) =>
        js.Array(js.Dynamic.literal(
          "fields" -> js.Array(js.Dynamic.literal("field" -> "curveId", "type" -> "E")),
          "values" -> js.Array(id.value)
        ))
      case None => js.Array()
