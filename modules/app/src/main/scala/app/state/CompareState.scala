package app.state

import com.raquo.laminar.api.L.{*, given}

import com.risquanter.register.domain.data.iron.ScenarioName

/** What the Compare branch picker currently holds. A plain `Var[Option[ScenarioName]]`
  * can't represent this: at the domain level `main` itself is `None` (DD-8),
  * so "nothing chosen yet" and "user explicitly chose to compare against main"
  * would collide on the same `None` value.
  */
enum CompareTarget:
  case NotChosen
  case Main
  case Scenario(name: ScenarioName.ScenarioName)

extension (target: CompareTarget)
  /** `None` if nothing has been chosen yet (don't fire a diff/curve fetch);
    * `Some(branch)` otherwise, in the `Option[ScenarioName]` shape every
    * branch-aware endpoint already expects (`None` = main).
    */
  def toBranchOption: Option[Option[ScenarioName.ScenarioName]] = target match
    case CompareTarget.NotChosen     => None
    case CompareTarget.Main          => Some(None)
    case CompareTarget.Scenario(name) => Some(Some(name))

/** Per-tab UI toggle for the Analyze Overlay comparison mode (milestone-2b
  * Phase C) — not fetched from the server, just which second branch (if any)
  * the user has chosen to compare the tab's active branch against.
  */
final class CompareState:
  val enabled: Var[Boolean] = Var(false)
  val compareBranch: Var[CompareTarget] = Var(CompareTarget.NotChosen)
