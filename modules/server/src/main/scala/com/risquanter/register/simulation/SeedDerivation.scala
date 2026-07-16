package com.risquanter.register.simulation

import com.risquanter.register.domain.data.iron.{SeedEntityId, SeedVarId}

/** The complete HDR generator input tuple for one leaf's two random streams.
  *
  * Both the sampler and the provenance record consume one instance of this
  * value, so what was simulated and what was recorded cannot disagree
  * (PLAN-SEED-IDENTITY §6.2 — fixes the provenance var-ID mismatch by
  * construction).
  */
final case class HdrStreams(
  entityId: Long,
  occurrenceVarId: Long,
  lossVarId: Long,
  seed3: Long,
  seed4: Long
)

/** The single derivation site for HDR stream identifiers (PLAN-SEED-IDENTITY §1).
  *
  * {{{
  * entityId        = workspace.seedEntityId          (paper's Entity axis: the organization)
  * occurrenceVarId = 2 * leaf.seedVarId              (paper's Variable axis, even slot)
  * lossVarId       = 2 * leaf.seedVarId + 1          (odd slot — disjoint from every
  *                                                    other leaf's streams by parity)
  * seed3, seed4    = global reproducibility knobs    (paper's Time/Agent axes)
  * }}}
  *
  * Magnitude safety: `seedVarId < 5×10⁷` (Iron-enforced) keeps both var IDs
  * below the paper's 10⁸ ID budget; `seedEntityId < 10⁸` likewise. No hashing
  * anywhere — stochastic identity is assigned data, and the node's ULID has no
  * influence on any figure.
  */
object SeedDerivation:

  def streams(
    entity: SeedEntityId.SeedEntityId,
    varId: SeedVarId.SeedVarId,
    seed3: Long,
    seed4: Long
  ): HdrStreams =
    HdrStreams(
      entityId = entity.value,
      occurrenceVarId = 2L * varId.value,
      lossVarId = 2L * varId.value + 1L,
      seed3 = seed3,
      seed4 = seed4
    )
