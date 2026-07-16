package com.risquanter.register.simulation

import zio.test.*
import com.risquanter.register.domain.data.iron.{SeedEntityId, SeedVarId}

/** Layer 5 of the seed-identity test plan (PLAN-SEED-IDENTITY §11):
  * statistical sanity on the streams the boundary-assigned identities select.
  *
  * With seeds fixed every statistic below is a **constant** — these are
  * regression tests, not hypothesis tests, and cannot flake. The bounds are
  * still chosen like statistical tests (≥ 4σ or a named critical value) so a
  * failure means the generator is genuinely misbehaving for this usage
  * pattern, not that a threshold was pinned knife-edge.
  *
  * Why this exists: the Dieharder validation covered the HDR algorithm, but
  * **dense consecutive var IDs are a new usage pattern** — the old
  * hash-spread IDs never exercised adjacency, and a linear-mix generator
  * earns an adjacency check (plan §11 Layer 5 rationale).
  */
object SeedStatisticalSanitySpec extends ZIOSpecDefault:

  private val NTrials = 10_000
  private val Entity  = 1L

  private def entity(v: Long) = SeedEntityId.fromLong(v).toOption.get
  private def varId(v: Long)  = SeedVarId.fromLong(v).toOption.get

  private def stream(rawVarId: Long): IndexedSeq[Double] =
    val rng = HDRWrapper.createGenerator(Entity, rawVarId, 0L, 0L)
    (0 until NTrials).map(t => rng(t.toLong))

  private def pearson(xs: IndexedSeq[Double], ys: IndexedSeq[Double]): Double =
    val n  = xs.size
    val mx = xs.sum / n
    val my = ys.sum / n
    val cov = xs.lazyZip(ys).map((x, y) => (x - mx) * (y - my)).sum
    val sx  = math.sqrt(xs.map(x => (x - mx) * (x - mx)).sum)
    val sy  = math.sqrt(ys.map(y => (y - my) * (y - my)).sum)
    cov / (sx * sy)

  def spec = suite("SeedStatisticalSanity (PLAN §11 Layer 5)")(

    test("adjacent leaves' streams are uncorrelated (Pearson, dense consecutive seedVarIds)") {
      // Leaves with seedVarId k and k+1 own raw var IDs (2k, 2k+1) and
      // (2k+2, 2k+3). Checking every consecutive raw-ID pair over a dense
      // block covers both cross-leaf adjacency and the within-leaf
      // occurrence/loss pair. |r| for independent uniforms has σ ≈ 1/√N = 0.01;
      // the 0.05 bound is 5σ.
      val streams = (2L to 18L).map(v => v -> stream(v)).toMap
      val correlations =
        (2L until 18L).map(v => (v, v + 1L, pearson(streams(v), streams(v + 1L))))
      assertTrue(correlations.forall((_, _, r) => math.abs(r) < 0.05))
    },

    test("occurrence frequency matches the declared probability (derived streams, real sampler path)") {
      // Same derivation the production sampler uses: seedVarId k → occurrence
      // stream 2k. Bernoulli frequency at N=10⁴: σ = √(p(1-p)/N); tolerances
      // are ≥ 4σ (p=0.25 → σ≈0.0043; p=0.05 → σ≈0.0022; p=0.5 → σ=0.005).
      def occurrenceFreq(seedVar: Long, p: Double): Double =
        val s   = SeedDerivation.streams(entity(Entity), varId(seedVar), 0L, 0L)
        val rng = HDRWrapper.createGenerator(s.entityId, s.occurrenceVarId, s.seed3, s.seed4)
        (0 until NTrials).count(t => rng(t.toLong) < p).toDouble / NTrials
      assertTrue(
        math.abs(occurrenceFreq(1L, 0.25) - 0.25) < 0.02,
        math.abs(occurrenceFreq(2L, 0.05) - 0.05) < 0.01,
        math.abs(occurrenceFreq(3L, 0.50) - 0.50) < 0.025
      )
    },

    test("one stream passes a Kolmogorov–Smirnov uniformity smoke") {
      // D·√N critical value at α=0.001 is 1.95 → bound D < 0.02 at N=10⁴.
      val sorted = stream(2L).sorted
      val d = sorted.zipWithIndex.map { (x, i) =>
        val fEmpHi = (i + 1).toDouble / NTrials
        val fEmpLo = i.toDouble / NTrials
        math.max(math.abs(fEmpHi - x), math.abs(x - fEmpLo))
      }.max
      assertTrue(d < 0.02)
    }
  )
