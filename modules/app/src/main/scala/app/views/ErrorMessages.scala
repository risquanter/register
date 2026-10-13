package app.views

import java.time.Duration
import app.core.RequestFailure
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.ScenarioName
import com.risquanter.register.domain.errors.MergeRefusal
import com.risquanter.register.http.errors.{Capability, ConflictKind, QueryProblem, WireError}

/** Every sentence the browser shows for a failure.
  *
  * The server sends a case and the values that belong to it; the sentence is
  * written here, because only this side knows it is drawing a banner rather than
  * a log line, and a sentence that arrives already written cannot be translated.
  *
  * A sentence that takes a value is a function of that value rather than a
  * template a caller fills in, so the words and the values cannot drift apart
  * (ADR-037).
  */
object ErrorMessages:

  // ── Fixed sentences: the failure carries no value to say anything about ──

  val Forbidden: String =
    "You do not have access to this. If you reached it from a link, the link may no longer be valid."

  val DependencyDown: String =
    "A service this request needed did not answer. Nothing was changed. Try again in a moment."

  val InternalFault: String =
    "Something went wrong on the server. Nothing was changed."

  val NotFound: String =
    "That item no longer exists, or the link you used does not point at it any more."

  val ConflictRejected: String =
    "Someone else changed this first, so the change was not applied. Reload and try again."

  val StaleVersion: String =
    "This was edited elsewhere since you loaded it. Reload to see the current version."

  /** Shown when startup pre-validation finds the stored workspace expired or
    * gone. The lifetime-aware `workspaceExpired` needs the server's durations,
    * which that path does not carry.
    */
  val WorkspaceExpiredOnStartup: String =
    "Your previous workspace has expired and its data is no longer available. " +
    "Creating a new tree will start a fresh workspace."

  /** Shown when the request never reached the server.
    *
    * The browser's own text for this is developer wording such as "Failed to
    * fetch", so it is not repeated here. It goes to the browser console
    * instead, where someone diagnosing the failure will look for it.
    */
  val TransportFailed: String =
    "The request did not reach the server. Nothing was changed. " +
    "Check your connection and try again."

  // ── The sentence for a whole failure, whatever kind it is ──

  /** The sentence for any failed request.
    *
    * This is what an inline view error displays, and it is the same text the
    * banner shows for the same failure, because both read the functions below.
    */
  def requestFailed(failure: RequestFailure): String = failure match
    case RequestFailure.Wire(error)      => wire(error)
    case RequestFailure.Transport(_)     => TransportFailed

  /** The sentence for each case the server can answer with.
    *
    * The match is on the wire cases rather than on a status, so a new case is a
    * compile error here until it has a sentence.
    */
  def wire(error: WireError): String = error match
    case WireError.ValidationRejected(errors, _)      => errors.map(_.message).mkString("; ")
    case WireError.QueryRejected(problem, _)          => query(problem)
    case WireError.NotFound(_)                        => NotFound
    case WireError.WorkspaceExpired(c, life, idle, _) => workspaceExpired(c, life, idle)
    case WireError.AccessDenied(_)                    => Forbidden
    case WireError.Conflict(kind, _)                  => conflict(kind)
    case WireError.NotSupported(capability, _)        => capabilityUnavailable(capability)
    case WireError.DependencyUnavailable(_)           => DependencyDown
    case WireError.InternalFault(_)                   => InternalFault

  // ── Sentences built from what the failure carries ──

  /** One sentence per cause, because the two are different events.
    *
    * A workspace that sat untouched past its idle window hit `idleLimit`; one
    * that ran to its ceiling hit `lifetime`. Naming the other figure, or naming
    * one figure for both cases, tells the reader something untrue about why
    * their workspace is gone.
    */
  def workspaceExpired(cause: ExpiryCause, lifetime: Duration, idleLimit: Duration): String =
    val reason = cause match
      case ExpiryCause.Inactivity =>
        s"It was not used for ${hours(idleLimit)}, which is the limit."
      case ExpiryCause.Lifetime =>
        s"It reached its ${hours(lifetime)} limit, which no amount of use extends."
    s"Your workspace has expired. $reason Its data is no longer available, " +
    "and creating a new tree will start a fresh workspace."

  private def hours(d: Duration): String =
    val h = d.toHours
    if h == 1 then "1 hour" else s"$h hours"

  def capabilityUnavailable(capability: Capability): String = capability match
    case Capability.Scenarios =>
      "Scenarios are not available in this deployment."

  def query(problem: QueryProblem): String = problem match
    case QueryProblem.Unparseable(message, position) =>
      position.fold(message)(p => s"$message (at character $p)")
    case QueryProblem.UnknownReferences(messages) =>
      s"The query names something that is not in this tree: ${messages.mkString("; ")}"
    case QueryProblem.TypeCheckFailed(messages) =>
      s"The query does not type-check: ${messages.mkString("; ")}"
    case QueryProblem.NotQuantifiable(message) =>
      message

  def conflict(kind: ConflictKind): String = kind match
    case ConflictKind.Rejected()     => ConflictRejected
    case ConflictKind.StaleVersion() => StaleVersion

    case ConflictKind.MergeRefused(scenario, refusal) =>
      mergeRefused(scenario, refusal)

    case ConflictKind.MergeAlreadyRunning(scenario) =>
      s"A merge of ${scenario.value} is already running. It will finish on its own — re-check in a moment."

    case ConflictKind.MergeTargetMoved(scenario) =>
      s"Main changed while ${scenario.value} was being merged, so nothing was written. Re-run the preview and try again."

    case ConflictKind.ScenarioHeadStale(scenario, expected) =>
      s"${scenario.value} changed since you read it, so the change was not applied. Reload the scenario."

    case ConflictKind.SimulationRequired(treeId) =>
      "This tree has no simulation results yet. Run a simulation before querying it."

  /** Why a merge was refused, and what to do about it.
    *
    * Each case says what the remedy is, because the four refusals need four
    * different actions: edit either side, rename a node, report a fault, or
    * re-run the preview.
    */
  def mergeRefused(scenario: ScenarioName.ScenarioName, refusal: MergeRefusal): String = refusal match
    case MergeRefusal.Conflicts(paths) =>
      s"${scenario.value} and main both changed ${paths.size} ${if paths.size == 1 then "node" else "nodes"}. " +
      "Edit either side so they agree, then merge again."

    case MergeRefusal.DuplicateNames(perTree) =>
      val names = perTree.values.flatten.map(_.value.toString).toList.sorted
      s"Merging ${scenario.value} would leave a repeated node name in one tree (${names.mkString(", ")}). " +
      "Rename one of them, then merge again."

    case MergeRefusal.InvariantBroken(trees) =>
      s"The merge of ${scenario.value} was undone because the merged state was not valid, " +
      s"so ${if trees.size == 1 then "the tree is" else "the trees are"} as they were. This is a fault to report."

    case MergeRefusal.BranchesConflict() =>
      s"${scenario.value} and main conflict. Re-run the preview to see where, then merge again."
