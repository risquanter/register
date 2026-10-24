package com.risquanter.register.http.errors

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.iron.{ScenarioName, TreeId}
import com.risquanter.register.domain.errors.MergeRefusal

/** What the request conflicts with.
  *
  * Every conflict answers `409`, and the browser selects a wire case by status
  * alone, so the seven outcomes share one wire case and this enumeration
  * discriminates between them. Each case carries what the person needs in order
  * to act, and nothing else.
  */
@jsonDiscriminator(ConflictKind.TagField)
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
    * Carries the scenario and no commit hash. Neither hash is something the
    * person can act on — the remedy is to reload the scenario either way — and
    * the head the branch currently holds would additionally help someone
    * correlate activity in a workspace they do not hold. A developer who needs
    * to see which commit was expected has the trace and the server log.
    */
  case ScenarioHeadStale(scenario: ScenarioName.ScenarioName)

  /** A query arrived before the tree had simulation results. */
  case SimulationRequired(treeId: TreeId)

object ConflictKind:
  /** The key naming which conflict a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. Each case's published value is its own name, which is
    * what the derived codec writes. */
  final val TagField = "kind"

  given codec: JsonCodec[ConflictKind] = DeriveJsonCodec.gen[ConflictKind]

  /** Declared rather than left to a holder's derivation, so the payload's
    * published description is the seven cases rather than an unconstrained
    * object, and names the key that decides between them. */
  given schema: Schema[ConflictKind] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[ConflictKind]
