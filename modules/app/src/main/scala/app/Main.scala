package app

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.components.Layout
import app.views.RiskLeafFormView

object Main:

  def main(args: Array[String]): Unit =
    val container = dom.document.querySelector("#app")
    
    val appElement = Layout(
      RiskLeafFormView()
    )
    
    render(container, appElement)
