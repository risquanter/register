package app.state

import com.raquo.laminar.api.L.{*, given}

import com.risquanter.register.domain.data.iron.BranchChoice

/** What the Compare branch picker currently holds. Built on `BranchChoice`
  * (the single internal spelling of "main vs. a named scenario") — this enum
  * only adds the picker-specific "nothing chosen yet" case, which a bare
  * `Option[BranchChoice]` could not distinguish from an unset form value in
  * match ergonomics and which historically collided with main under the old
  * `Option[ScenarioName]` encoding.
  */
enum CompareTarget:
  case NotChosen
  case Target(choice: BranchChoice)

extension (target: CompareTarget)
  /** `None` if nothing has been chosen yet (don't fire a diff/curve fetch);
    * `Some(choice)` otherwise. */
  def toChoice: Option[BranchChoice] = target match
    case CompareTarget.NotChosen      => None
    case CompareTarget.Target(choice) => Some(choice)

/** Per-tab UI toggle for the Analyze Overlay comparison mode (milestone-2b
  * Phase C) — not fetched from the server, just which second branch (if any)
  * the user has chosen to compare the tab's active branch against.
  */
final class CompareState:
  val enabled: Var[Boolean] = Var(false)
  val compareBranch: Var[CompareTarget] = Var(CompareTarget.NotChosen)
