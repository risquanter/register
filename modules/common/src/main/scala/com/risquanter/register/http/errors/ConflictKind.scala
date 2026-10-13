package com.risquanter.register.http.errors

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.iron.{CommitHash, ScenarioName, TreeId}
import com.risquanter.register.domain.errors.MergeRefusal

/** What the request conflicts with.
  *
  * Every conflict answers `409`, and the browser selects a wire case by status
  * alone, so the seven outcomes share one wire case and this enumeration
  * discriminates between them. Each case carries what the person needs in order
  * to act, and nothing else.
  */
@jsonDiscriminator("kind")
enum ConflictKind:
  /** A uniqueness or state conflict with no further structure. */
  case Rejected()

  /** The caller's expectation of a resource's version is stale. */
  case StaleVersion()

  /** Two branches hold conflicting values, so no merge commit was created. */
  case MergeRefused(scenario: ScenarioName.ScenarioName, refusal: MergeRefusal)

  /** A merge of this scenario is already running; it clears by itself once the
    * first one finishes, so waiting is the remedy.
    */
  case MergeAlreadyRunning(scenario: ScenarioName.ScenarioName)

  /** Main moved while the merge was being assembled; main is untouched. */
  case MergeTargetMoved(scenario: ScenarioName.ScenarioName)

  /** The scenario's head is not what the caller last observed.
    *
    * Carries what was expected and not the head the branch currently holds: the
    * caller can act on its own expectation, while the current head only helps
    * someone correlate activity in a workspace they do not hold.
    */
  case ScenarioHeadStale(scenario: ScenarioName.ScenarioName, expected: CommitHash)

  /** A query arrived before the tree had simulation results. */
  case SimulationRequired(treeId: TreeId)

object ConflictKind:
  given codec: JsonCodec[ConflictKind] = DeriveJsonCodec.gen[ConflictKind]
