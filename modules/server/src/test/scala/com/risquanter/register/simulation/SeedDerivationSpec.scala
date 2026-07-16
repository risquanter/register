package com.risquanter.register.simulation

import zio.test.*
import com.risquanter.register.domain.data.iron.{SeedEntityId, SeedVarId}

/** Layer 1 of the seed-identity test plan (PLAN-SEED-IDENTITY §11):
  * exact, hand-verifiable derivation + the HDR magnitude budget as an
  * executable property. The budget test fails on the old hash-based
  * derivation (String.hashCode var IDs, ±2.1×10⁹, blow the 10⁸ ID budget
  * 21×) and permanently guards the paper's limits.
  */
object SeedDerivationSpec extends ZIOSpecDefault:

  private def entity(v: Long) = SeedEntityId.fromLong(v).toOption.get
  private def varId(v: Long)  = SeedVarId.fromLong(v).toOption.get

  // HDR paper Table 2 coefficients (Hubbard, WSC 2019) — lane 1 and lane 2.
  // Sum form per lane: trial*cT + var*cV + ent*cE + time*cS3 + agent*cS4 (mod divisor)
  private final case class Lane(cTrial: Long, cVar: Long, cEnt: Long, cTime: Long, cAgent: Long, divisor: Long)
  private val lane1 = Lane(2499997L, 1800451L, 2000371L, 1796777L, 2299603L, 7450589L)
  private val lane2 = Lane(2246527L, 2399993L, 2100869L, 1918303L, 1624729L, 7450987L)

  private val PaperPrecisionLimit = 1_000_000_000_000_000L // 10^15 (Excel precision bound)
  private val MaxTrial = 100_000_000L                      // paper's 10^8 ID budget

  private def maxDividend(lane: Lane, entityId: Long, varIdValue: Long, seed3: Long, seed4: Long): Long =
    MaxTrial * lane.cTrial + varIdValue * lane.cVar + entityId * lane.cEnt +
      seed3 * lane.cTime + seed4 * lane.cAgent

  def spec = suite("SeedDerivation (PLAN §11 Layer 1)")(
    test("exact literals: seedVarId k yields streams (2k, 2k+1)") {
      val s1 = SeedDerivation.streams(entity(7L), varId(1L), 0L, 0L)
      val s5 = SeedDerivation.streams(entity(7L), varId(5L), 0L, 0L)
      val sMax = SeedDerivation.streams(entity(7L), varId(49999999L), 0L, 0L)
      assertTrue(
        s1.occurrenceVarId == 2L,  s1.lossVarId == 3L,
        s5.occurrenceVarId == 10L, s5.lossVarId == 11L,
        sMax.occurrenceVarId == 99999998L, sMax.lossVarId == 99999999L,
        s1.entityId == 7L
      )
    },
    test("global knobs pass through untouched") {
      val s = SeedDerivation.streams(entity(3L), varId(4L), 111L, 222L)
      assertTrue(s.seed3 == 111L, s.seed4 == 222L)
    },
    test("disjointness: distinct seedVarIds never share a stream (even/odd parity)") {
      check(Gen.long(1L, 49999999L), Gen.long(1L, 49999999L)) { (j, k) =>
        val sj = SeedDerivation.streams(entity(1L), varId(j), 0L, 0L)
        val sk = SeedDerivation.streams(entity(1L), varId(k), 0L, 0L)
        val setJ = Set(sj.occurrenceVarId, sj.lossVarId)
        val setK = Set(sk.occurrenceVarId, sk.lossVarId)
        assertTrue(if (j == k) setJ == setK else (setJ & setK).isEmpty)
      }
    },
    test("occurrence and loss streams are distinct for every seedVarId") {
      check(Gen.long(1L, 49999999L)) { k =>
        val s = SeedDerivation.streams(entity(1L), varId(k), 0L, 0L)
        assertTrue(s.occurrenceVarId != s.lossVarId)
      }
    },
    test("magnitude budget: maximal legal inputs keep every HDR lane dividend within the paper's limits") {
      // Maximal derivable values: entity 99999999, var 2*49999999+1 = 99999999,
      // trial 10^8; global knobs at their current value 0.
      val s = SeedDerivation.streams(entity(99999999L), varId(49999999L), 0L, 0L)
      val worstVar = math.max(s.occurrenceVarId, s.lossVarId)
      val d1 = maxDividend(lane1, s.entityId, worstVar, s.seed3, s.seed4)
      val d2 = maxDividend(lane2, s.entityId, worstVar, s.seed3, s.seed4)
      val lane1Cap = (1L << 27) * lane1.divisor // 2^27 · divisor (paper's modulus bound)
      val lane2Cap = (1L << 27) * lane2.divisor
      assertTrue(
        s.entityId < 100000000L,
        worstVar < 100000000L,
        d1 <= PaperPrecisionLimit, d1 <= lane1Cap,
        d2 <= PaperPrecisionLimit, d2 <= lane2Cap
      )
    },
    test("magnitude budget: the old hashCode-derived var IDs violate the budget (regression witness)") {
      // Documents WHY hashing died: a typical String.hashCode magnitude
      // (~2.1×10⁹) pushes the lane dividend past the 10^15 precision limit.
      val oldStyleVarId = Int.MaxValue.toLong // 2147483647 — representable pre-redesign
      val d1 = maxDividend(lane1, 99999999L, oldStyleVarId, 0L, 0L)
      assertTrue(d1 > PaperPrecisionLimit)
    }
  )
