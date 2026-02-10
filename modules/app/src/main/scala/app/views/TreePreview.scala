package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.TreeBuilderState

/**
 * Lightweight tree preview showing current portfolios and leaves with remove actions.
 * Uses signals from TreeBuilderState (signals down, events up).
 */
object TreePreview:
  def apply(builderState: TreeBuilderState): HtmlElement =
    div(
      cls := "tree-preview",
      h3("Tree Preview"),
      div(
        cls := "tree-name",
        span("Tree name: "),
        child.text <-- builderState.treeNameVar.signal
      ),
      div(
        cls := "tree-portfolios",
        h4("Portfolios"),
        ul(
          children <-- builderState.portfoliosVar.signal.map { ports =>
            if ports.isEmpty then List(li(em("(none)")))
            else ports.map { p =>
              li(
                span(s"${p.name}"),
                p.parent.fold(span(cls := "node-parent", " (root)")) { parentName =>
                  span(cls := "node-parent", s" (parent: $parentName)")
                },
                button(
                  cls := "remove-btn",
                  "Remove",
                  onClick --> (_ => builderState.removeNode(p.name))
                )
              )
            }
          }
        )
      ),
      div(
        cls := "tree-leaves",
        h4("Leaves"),
        ul(
          children <-- builderState.leavesVar.signal.map { leaves =>
            if leaves.isEmpty then List(li(em("(none)")))
            else leaves.map { l =>
              li(
                span(s"${l.name}"),
                l.parent.fold(span(cls := "node-parent", " (root)")) { parentName =>
                  span(cls := "node-parent", s" (parent: $parentName)")
                },
                button(
                  cls := "remove-btn",
                  "Remove",
                  onClick --> (_ => builderState.removeNode(l.name))
                )
              )
            }
          }
        )
      )
    )
