package app.facades

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Minimal Scala.js facade for vega-embed v7.
  *
  * Wraps the default export of the `vega-embed` NPM package. The caller passes
  * a DOM element and a Vega-Lite JSON spec (as `js.Any` — typically parsed via
  * `js.JSON.parse`). The returned `js.Promise[EmbedResult]` resolves once the
  * chart is rendered.
  *
  * Lifecycle contract:
  *   - Call `vegaEmbed(el, spec)` to mount a chart.
  *   - Store the `EmbedResult` and call `finalize()` on unmount to release
  *     resources (event listeners, animation frames, canvas contexts).
  *
  * @see https://github.com/vega/vega-embed
  */
@js.native
@JSImport("vega-embed", JSImport.Default)
object vegaEmbed extends js.Object:
  def apply(
      el: dom.Element,
      spec: js.Any,
      options: js.UndefOr[js.Any] = js.undefined
  ): js.Promise[EmbedResult] = js.native

/** Result returned by `vegaEmbed` after a chart is successfully rendered.
  *
  * Provides access to the underlying Vega `view` for programmatic interaction
  * (signal listeners, data updates) and a `finalize()` method for cleanup.
  */
@js.native
trait EmbedResult extends js.Object:
  /** The Vega View instance backing the rendered chart. */
  val view: js.Dynamic = js.native

  /** Dispose of the chart, releasing all DOM and timer resources. */
  override def finalize(): Unit = js.native
