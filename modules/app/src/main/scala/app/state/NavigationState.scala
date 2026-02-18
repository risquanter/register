package app.state

import com.raquo.laminar.api.L.{*, given}

/** Active section in the sidebar navigation.
  *
  * This is the "route" concept for the single-page app — a simple
  * enum toggling between the two top-level views. No URL routing
  * is introduced; the workspace key in the URL is orthogonal to
  * the active section.
  */
enum Section:
  case Design, Analyze

/** Reactive navigation state — owns the currently active section.
  *
  * Created once in `Main` and threaded down to `Sidebar` and the
  * view-switching logic in `AppShell`. Follows ADR-019 Pattern 2:
  * parent owns the `Var`, children receive it as a parameter.
  */
final class NavigationState:
  val activeSection: Var[Section] = Var(Section.Design)

  def isActive(section: Section): Signal[Boolean] =
    activeSection.signal.map(_ == section)

  def navigate(section: Section): Unit =
    activeSection.set(section)
