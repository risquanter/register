package app.state

import com.raquo.laminar.api.L.{*, given}

import com.risquanter.register.domain.data.iron.BranchChoice

/** What the Compare branch picker currently holds. Built on `BranchChoice`
  * (the single internal spelling of "main vs. a named scenario") — this enum
  * only adds the picker-specific "nothing chosen yet" case.
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

/** Per-tab UI state for the Analyze comparison mode — whether Compare is on,
  * and which second branch (if any) to compare the tab's active branch
  * against. Not fetched from the server.
  */
final class CompareState:
  val enabled: Var[Boolean] = Var(false)
  val compareBranch: Var[CompareTarget] = Var(CompareTarget.NotChosen)

  /** The chosen compare branch as a plain `BranchChoice` — the branch signal
    * the compare card's `TreeViewState` is constructed with, which needs a
    * definite branch before any choice exists. Falls back to `Main` while
    * `compareBranch` is `NotChosen`, so no state tied to it (branch-keyed
    * list fetches, the key-change reload in `TreeViewState`) can act on a
    * branch that may no longer exist. Synced here so views write
    * `compareBranch` only. */
  val chosenBranch: Var[BranchChoice] = Var(BranchChoice.Main)

  compareBranch.signal.changes.foreach {
    case CompareTarget.Target(choice) => chosenBranch.set(choice)
    case CompareTarget.NotChosen      => chosenBranch.set(BranchChoice.Main)
  }(using unsafeWindowOwner)
