# Implementation Plan: Monte Carlo Risk Simulation with Category Theory Design

## Overview
This plan integrates Monte Carlo risk simulation into the register service following a hybrid approach:
- **BCG implementation:** Production-tested sparse storage and parallelization patterns
- **Agent-output principles:** Pure functional design with category theory (ZIO Prelude)
- **simulation-util library:** Trusted HDR PRNG and Metalog distribution (no reimplementation)

## Critical Design Principles
1. ✅ **simulation-util library:** Use HDR.generate and QPFitter directly (treat as correct)
2. ✅ **Validation:** Apply Iron refinement types (Probability) before calling Java APIs
3. ✅ **Category Theory Alignment:** Lawful type classes (Monoid, Equal, Order, Show)
4. ✅ **Naming Clarity:** 
   - Agent-output `Risk` trait = Sampling strategy (pure function)
   - BCG `Risk` → renamed to `SimulationResult` = Aggregated outcomes (data structure)
5. 🔍 **Review Focus:** Parallelization correctness + functional design consistency

---

## Phase 1: Wrap simulation-util (HDR + Metalog)

**Objective:** Create thin Scala wrappers with validation and defensive testing

### 1.1 HDR Wrapper
**File:** `modules/common/src/main/scala/com/risquanter/register/domain/simulation/HDRWrapper.scala`

```scala
package com.risquanter.register.domain.simulation

import no.promon.riskquant.HDR  // Java simulation-util

object HDRWrapper {
  /** Curried generator: trial => uniform [0,1) */
  def createGenerator(
    entityId: Long,
    varId: Long,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Long => Double = {
    (trial: Long) => HDR.generate(trial, entityId, varId, seed3, seed4)
  }
  
  def generate(counter: Long, entityId: Long, varId: Long, 
               seed3: Long = 0L, seed4: Long = 0L): Double = {
    HDR.generate(counter, entityId, varId, seed3, seed4)
  }
}
```

**Testing:** 10+ tests
- Determinism: same inputs → identical outputs
- Uniformity: Kolmogorov-Smirnov test on 10k samples
- Seed isolation: different seeds → different sequences

### 1.2 Metalog Wrapper with Iron Validation
**File:** `modules/common/src/main/scala/com/risquanter/register/domain/simulation/MetalogDistribution.scala`

```scala
package com.risquanter.register.domain.simulation

import no.promon.riskquant.{Metalog, QPFitter}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import com.risquanter.register.domain.data.{Probability, ValidationUtil}

type PositiveInt = Int :| Positive

case class MetalogDistribution private(fitter: Metalog) {
  def quantile(p: Double): Double = fitter.quantile(p)
  def sample(uniform: Double): Double = quantile(uniform)
}

object MetalogDistribution {
  /** 
   * Create Metalog from percentile-quantile pairs.
   * Uses Iron Probability type for validation (guarantees [0,1]).
   */
  def fromPercentiles(
    percentiles: Array[Probability],  // ✅ Iron refinement from stack
    quantiles: Array[Double],
    terms: PositiveInt = 9,
    lower: Option[Double] = None,
    upper: Option[Double] = None
  ): Either[ValidationError, MetalogDistribution] = {
    
    val validations = List(
      validateSorted(percentiles),
      validateArrayLengths(percentiles, quantiles),
      validateBounds(lower, upper)
    )
    
    ValidationUtil.combineErrors(validations) match {
      case Left(errors) => Left(ValidationError(errors))
      case Right(_) => 
        try {
          val builder = QPFitter.`with`(
            percentiles.map(_.value), 
            quantiles, 
            terms
          )
          val withBounds = (lower, upper) match {
            case (Some(l), Some(u)) => builder.lower(l).upper(u)
            case (Some(l), None)    => builder.lower(l)
            case (None, Some(u))    => builder.upper(u)
            case (None, None)       => builder
          }
          val metalog = withBounds.fit()
          Right(MetalogDistribution(metalog))
        } catch {
          case e: Exception => 
            Left(ValidationError(List(s"QPFitter failed: ${e.getMessage}")))
        }
    }
  }
  
  private def validateSorted(ps: Array[Probability]): Either[String, Unit] = {
    val values = ps.map(_.value)
    if (values.sorted.sameElements(values)) Right(())
    else Left("Percentiles must be sorted")
  }
  
  private def validateArrayLengths(ps: Array[Probability], qs: Array[Double]): Either[String, Unit] = {
    if (ps.length != qs.length) Left("Percentiles and quantiles must have same length")
    else if (ps.isEmpty) Left("Arrays cannot be empty")
    else Right(())
  }
  
  private def validateBounds(lower: Option[Double], upper: Option[Double]): Either[String, Unit] = {
    (lower, upper) match {
      case (Some(l), Some(u)) if l >= u => Left("Lower bound must be < upper bound")
      case _ => Right(())
    }
  }
}
```

**Testing:** 15+ tests
- **Defensive tests:** Verify simulation-util handles invalid inputs
  - Empty arrays → caught by validation
  - Unsorted percentiles → validation rejects
  - Mismatched lengths → validation rejects
  - Invalid bounds → validation rejects
- Valid inputs: successful fitting, quantile queries
- Boundary cases: single point, unbounded, semi-bounded

**Deliverables:**
- HDRWrapper.scala with 10+ tests
- MetalogDistribution.scala with 15+ tests

---

## Phase 2: RiskSampler with Factory Pattern

**Objective:** Implement curried generator pattern for occurrence + loss sampling

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/simulation/RiskSampler.scala`

```scala
package com.risquanter.register.domain.simulation

trait RiskSampler {
  def id: String
  def sampleOccurrence(trial: Long): Boolean
  def sampleLoss(trial: Long): Double
}

object RiskSampler {
  def fromMetalog(
    entityId: Long,
    riskId: String,
    occurrenceProb: Probability,
    lossDistribution: MetalogDistribution,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): RiskSampler = {
    // Offset seeds like BCG: occurrence=hash(riskId)+1000, loss=hash(riskId)+2000
    val riskHash = riskId.hashCode.toLong
    val occRng = HDRWrapper.createGenerator(entityId, riskHash + 1000, seed3, seed4)
    val lossRng = HDRWrapper.createGenerator(entityId, riskHash + 2000, seed3, seed4)
    
    new RiskSampler {
      def id: String = riskId
      def sampleOccurrence(trial: Long): Boolean = occRng(trial) < occurrenceProb.value
      def sampleLoss(trial: Long): Double = lossDistribution.sample(lossRng(trial))
    }
  }
  
  // Additional factories for lognormal, etc.
  def fromLognormal(
    entityId: Long,
    riskId: String,
    occurrenceProb: Probability,
    mu: Double,
    sigma: Double,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): RiskSampler = ???
}
```

**Review Focus:**
- 🔍 Seed offset strategy prevents correlation
- ✅ API design: trait interface minimal and composable

**Testing:** 20+ tests
- Occurrence rates match expected probabilities
- Loss distributions match Metalog quantiles
- Seed isolation (different riskIds → independent sequences)

**Deliverables:**
- RiskSampler.scala with 20+ tests

---

## Phase 3A: Review BCG Risk API for Category Theory Alignment

**Objective:** Critically analyze BCG's Risk.scala from functional programming perspective

### Review Findings (AGREED)

1. **Should Risk be a Monoid?** ✅ YES
   - BCG's `+` operator is associative
   - Identity element = empty map
   - **Action:** Make explicit `Monoid[RiskResult]` with ZIO Prelude

2. **Is merge operation lawful?** ✅ YES
   - Associativity: `merge(a, merge(b, c)) == merge(merge(a, b), c)` ✓
   - Commutativity: `merge(a, b) == merge(b, a)` ✓
   - Identity: `merge(empty, a) == a` ✓

3. **Does sparse Map fit functional design?** ✅ YES
   - Immutable Map is functional
   - **Enhancement:** Add `Equal[RiskResult]` for value equality

4. **Naming Convention** ✅ AGREED
   - Keep BCG naming: `TrialSet`, `TrialSetGroup` (clearer for domain)
   - Add type aliases for clarity:
     ```scala
     type TrialId = Int
     type Loss = Long
     ```

5. **Critical Distinction: Risk vs SimulationResult** ✅ AGREED
   ```scala
   // Agent-output (pure): Sampling strategy
   trait Risk {
     def sample(trial: Long): Double  
   }
   
   // BCG (data): Simulation results
   sealed abstract class SimulationResult(outcomes: Map[Int, Long]) {
     def probOfExceedance(limit: Long): BigDecimal
   }
   ```
   
   **Decision:**
   - Rename BCG `Risk` → `SimulationResult` to avoid confusion
   - Keep agent-output `Risk` trait for sampling
   - Use both: `Risk` for sampling, `SimulationResult` for aggregation

**Deliverables:**
- Design decisions documented above
- API improvements ready for Phase 3B

---

## Phase 3B: Port and Refactor Risk Domain Model

**Objective:** Port BCG's Risk.scala as `SimulationResult` with ZIO Prelude

**Files:**
1. `modules/common/src/main/scala/com/risquanter/register/domain/data/package.scala`
2. `modules/common/src/main/scala/com/risquanter/register/domain/data/SimulationResult.scala`
3. `modules/common/src/main/scala/com/risquanter/register/domain/simulation/Risk.scala` (new - pure trait)

### Type Aliases
```scala
package com.risquanter.register.domain

package object data {
  type TrialId = Int
  type Loss = Long
}
```

### Pure Risk Trait (Agent-Output Style)
```scala
package com.risquanter.register.domain.simulation

/** Pure sampling strategy */
trait Risk {
  def id: String
  def sample(trial: Long): Double  // Pure function: trial => loss
}

case class SimpleRisk(
  id: String,
  sampler: RiskSampler
) extends Risk {
  def sample(trial: Long): Double = {
    if (sampler.sampleOccurrence(trial)) sampler.sampleLoss(trial)
    else 0.0
  }
}
```

### SimulationResult (BCG Style with Prelude)
```scala
package com.risquanter.register.domain.data

import zio.prelude.*
import scala.collection.immutable.{TreeMap, TreeSet}

sealed abstract class SimulationResult(
  val riskName: String,
  val outcomes: Map[TrialId, Loss],  // Sparse storage
  val nTrials: Int,
  val rType: RiskType
) {
  def outcomeCount: TreeMap[Loss, Int]
  def maxLoss: Loss
  def minLoss: Loss
  def probOfExceedance(threshold: Loss): BigDecimal
  def outcomeOf(trial: TrialId): Loss = outcomes.getOrElse(trial, 0L)
  def trialIds(): Set[TrialId] = outcomes.keySet
  def flatten: Vector[SimulationResult]
}

case class RiskResult(
  override val riskName: String,
  override val outcomes: Map[TrialId, Loss],
  override val nTrials: Int
) extends SimulationResult(riskName, outcomes, nTrials, RiskType.Base) {
  
  override val outcomeCount: TreeMap[Loss, Int] = 
    TreeMap.from(outcomes.values.groupMapReduce(identity)(_ => 1)(_ + _))
  
  override val maxLoss: Loss = if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max
  override val minLoss: Loss = if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min
  
  override def probOfExceedance(threshold: Loss): BigDecimal = {
    val count = outcomeCount.rangeFrom(threshold).values.sum
    BigDecimal(count) / BigDecimal(nTrials)
  }
  
  override def flatten: Vector[SimulationResult] = Vector(this)
}

object RiskResult {
  def empty(name: String, nTrials: Int): RiskResult = 
    RiskResult(name, Map.empty, nTrials)
  
  /** ZIO Prelude Monoid instance */
  given Monoid[RiskResult] with {
    def identity: RiskResult = RiskResult("", Map.empty, 0)
    
    def combine(a: RiskResult, b: RiskResult): RiskResult = {
      require(a.nTrials == b.nTrials, "Cannot merge results with different trial counts")
      RiskResult(
        a.riskName,
        SimulationResult.merge(a, b),
        a.nTrials
      )
    }
  }
  
  given Equal[RiskResult] = Equal.make { (a, b) =>
    a.outcomes == b.outcomes && a.nTrials == b.nTrials
  }
  
  given Show[RiskResult] = Show.make { r =>
    s"RiskResult(${r.riskName}, ${r.outcomes.size} outcomes, ${r.nTrials} trials)"
  }
}

case class RiskResultGroup(
  val children: List[RiskResult],
  override val riskName: String,
  override val outcomes: Map[TrialId, Loss],
  override val nTrials: Int
) extends SimulationResult(riskName, outcomes, nTrials, RiskType.Aggregate) {
  
  override val outcomeCount: TreeMap[Loss, Int] = 
    TreeMap.from(outcomes.values.groupMapReduce(identity)(_ => 1)(_ + _))
  
  override val maxLoss: Loss = if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max
  override val minLoss: Loss = if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min
  
  override def probOfExceedance(threshold: Loss): BigDecimal = {
    val count = outcomeCount.rangeFrom(threshold).values.sum
    BigDecimal(count) / BigDecimal(nTrials)
  }
  
  override def flatten: Vector[SimulationResult] =
    this +: children.toVector.sortBy(_.riskName)
}

object RiskResultGroup {
  def apply(
    riskName: String,
    nTrials: Int,
    results: RiskResult*
  ): RiskResultGroup = {
    if (results.isEmpty) 
      RiskResultGroup(List.empty, riskName, Map.empty, nTrials)
    else 
      RiskResultGroup(results.toList, riskName, SimulationResult.merge(results: _*), nTrials)
  }
}

object SimulationResult {
  /** 
   * Outer join merge: union of trial IDs, sum losses per trial.
   * This is the associative operation for the Monoid.
   */
  def merge(rs: SimulationResult*): Map[TrialId, Loss] = {
    // Step 1: Union of all trial IDs
    val allTrialIds: Set[TrialId] = 
      rs.foldLeft(Set.empty[TrialId])((acc, r) => acc ++ r.trialIds())
    
    // Step 2: For each trial, sum losses from all results
    allTrialIds.map { trial =>
      trial -> rs.foldLeft(0L)((acc, r) => acc + r.outcomeOf(trial))
    }.toMap
  }
}
```

**REVIEW CHECKLIST:**

1. ✅ **Monoid Laws:** Property tests verify associativity + identity
2. ✅ **Outer Join Correctness:** Union captures all trial IDs, missing keys → 0L
3. ✅ **Sparse Storage:** Memory efficient for low-probability events
4. 🔍 **Integer Overflow:** Add bounds check or use BigInt for extreme cases

**Testing:** 30+ tests
- Property tests: Monoid laws (associativity, identity)
- Outer join: overlapping trials, disjoint trials, empty results
- Edge cases: empty map, single trial, all trials
- Performance: profile with 100k trials

**Deliverables:**
- SimulationResult.scala with 30+ tests
- Risk.scala (pure trait) with integration tests

---

## Phase 4: Simulator with Sparse Trials

**Objective:** Port BCG's Simulator with parallelization correctness review

**File:** `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala`

```scala
package com.risquanter.register.services.helper

import com.risquanter.register.domain.simulation.RiskSampler
import com.risquanter.register.domain.data.{RiskResult, TrialId, Loss}
import scala.collection.parallel.immutable.ParVector
import zio.{ZIO, Task}

object Simulator {
  /** 
   * Run trials for a single risk using sparse storage.
   * Only stores trials where risk occurred.
   */
  def performTrials(
    sampler: RiskSampler,
    nTrials: Int
  ): ParVector[(TrialId, Loss)] = {
    // Filter successful trials (view is memory-efficient)
    val successfulTrials = (1 to nTrials).view
      .filter(trial => sampler.sampleOccurrence(trial))
      .toVector
    
    // Parallel computation of losses for successful trials
    new ParVector(successfulTrials).map { trial =>
      (trial, sampler.sampleLoss(trial).toLong)
    }
  }
  
  /** 
   * Simulate multiple risks in parallel.
   * Returns sparse RiskResult for each risk.
   */
  def simulate(
    samplers: Vector[RiskSampler],
    sampleSize: Int
  ): Task[Vector[RiskResult]] = {
    val trialSets = samplers.map { sampler =>
      ZIO.attempt {
        val trials = performTrials(sampler, sampleSize).toIndexedSeq
        RiskResult(sampler.id, trials.toMap, sampleSize)
      }
    }
    
    ZIO.collectAllPar(trialSets).withParallelism(8)
  }
}
```

**CRITICAL PARALLELIZATION REVIEW:**

1. **performTrials Filter Logic:**
   - ✅ View is lazy (no allocation until toVector)
   - ✅ ParVector.map is thread-safe for pure functions
   - ✅ Determinism: sampler calls are pure (HDR-based)
   - ✅ No race conditions: no shared mutable state

2. **ZIO.collectAllPar:**
   - ✅ ZIO.attempt wraps blocking ParVector computation
   - ✅ Fiber isolation: each fiber operates on independent sampler
   - ✅ No shared state between fibers
   - 🔍 Parallelism limit: 8 optimal? (make configurable)

**Testing:** 20+ tests
- Determinism: same seeds → identical results across runs
- Concurrency: 100+ parallel simulations (no corruption)
- Sparse correctness: verify only occurred trials stored
- Performance: profile vs dense storage (measure memory)

**Deliverables:**
- Simulator.scala with 20+ tests

---

## Phase 5A: Port t-digest Aggregator as Monoid

**Objective:** Extract agent-output's TdigestAggregator with lawful Monoid

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/aggregator/TdigestAggregator.scala`

```scala
package com.risquanter.register.domain.aggregator

import zio.prelude.*
import com.tdunning.math.stats.TDigest

case class TdigestAggregator private(
  digest: TDigest,
  count: Long
) {
  def add(value: Double): TdigestAggregator = {
    digest.add(value)
    this.copy(count = count + 1)
  }
  
  def quantile(p: Double): Double = digest.quantile(p)
  def cdf(value: Double): Double = digest.cdf(value)
}

object TdigestAggregator {
  def empty(compression: Double = 100.0): TdigestAggregator =
    TdigestAggregator(TDigest.createDigest(compression), 0L)
  
  /** ZIO Prelude Monoid instance */
  given monoid(using compression: Double = 100.0): Monoid[TdigestAggregator] with {
    def identity: TdigestAggregator = empty(compression)
    
    def combine(a: TdigestAggregator, b: TdigestAggregator): TdigestAggregator = {
      val merged = TDigest.createDigest(compression)
      merged.add(a.digest)
      merged.add(b.digest)
      TdigestAggregator(merged, a.count + b.count)
    }
  }
}
```

**Testing:** 20+ tests
- Monoid laws (property tests)
- Quantile accuracy vs sorted array
- Merge correctness (disjoint samples)

**Deliverables:**
- TdigestAggregator.scala with 20+ tests

---

## Phase 5B: LEC with Sketch Backing

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/simulation/LEC.scala`

```scala
package com.risquanter.register.domain.simulation

import com.risquanter.register.domain.aggregator.TdigestAggregator

trait LEC {
  def quantile(p: Double): Double
  def exceedanceProbability(threshold: Double): Double
  def trialCount: Int
  def metadata: Map[String, String]
}

case class OnlineSketchLEC(
  aggregator: TdigestAggregator,
  riskIds: Set[String],
  nTrials: Int,
  metadata: Map[String, String] = Map.empty
) extends LEC {
  
  def quantile(p: Double): Double = aggregator.quantile(p)
  
  def exceedanceProbability(threshold: Double): Double = 
    1.0 - aggregator.cdf(threshold)
  
  def trialCount: Int = nTrials
}
```

**Testing:** 15+ tests

---

## Phase 5C: KLL Aggregator (Optional)

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/aggregator/KllAggregator.scala`

```scala
package com.risquanter.register.domain.aggregator

import zio.prelude.*
import org.apache.datasketches.kll.KllFloatsSketch

case class KllAggregator private(
  sketch: KllFloatsSketch,
  count: Long
) {
  def add(value: Double): KllAggregator = {
    sketch.update(value.toFloat)
    this.copy(count = count + 1)
  }
  
  def quantile(p: Double): Double = sketch.getQuantile(p).toDouble
}

object KllAggregator {
  def empty(k: Int = 200): KllAggregator =
    KllAggregator(new KllFloatsSketch(k), 0L)
  
  given monoid(using k: Int = 200): Monoid[KllAggregator] with {
    def identity: KllAggregator = empty(k)
    
    def combine(a: KllAggregator, b: KllAggregator): KllAggregator = {
      val merged = KllFloatsSketch.heapify(a.sketch.toByteArray)
      merged.merge(b.sketch)
      KllAggregator(merged, a.count + b.count)
    }
  }
}
```

**Testing:** 15+ tests

---

## Phase 5D: Supporting Aggregators

**Files:**
- `modules/common/src/main/scala/com/risquanter/register/domain/aggregator/TopKCollector.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/aggregator/Reservoir.scala`

**Testing:** 10+ tests per aggregator

---

## Phase 6: SimulationService Orchestration

**File:** `modules/server/src/main/scala/com/risquanter/register/services/SimulationExecutionService.scala`

```scala
package com.risquanter.register.services

import zio.{Task, ZIO, ZStream}
import zio.prelude.*
import com.risquanter.register.domain.simulation.*
import com.risquanter.register.domain.aggregator.TdigestAggregator
import com.risquanter.register.services.helper.Simulator

trait SimulationExecutionService {
  def runSimulation(
    simulationId: String,
    risks: Seq[RiskConfig],
    nTrials: Int
  ): Task[LEC]
}

case class SimulationExecutionServiceLive(
  simulator: Simulator
) extends SimulationExecutionService {
  
  def runSimulation(
    simulationId: String,
    risks: Seq[RiskConfig],
    nTrials: Int
  ): Task[LEC] = for {
    // 1. Create samplers from risk configs
    samplers <- ZIO.foreach(risks)(createSampler)
    
    // 2. Create pure Risk instances
    pureRisks = samplers.map(s => SimpleRisk(s.id, s))
    
    // 3. Run simulation with per-chunk aggregation
    results <- ZStream.range(0L, nTrials.toLong)
      .mapZIOParUnordered(64) { trial =>
        // Sample all risks for this trial
        ZIO.foreach(pureRisks)(risk => 
          ZIO.succeed(risk.sample(trial))
        ).map(_.sum)
      }
      .grouped(1000)  // Chunk size
      .mapZIO { chunk =>
        // Local aggregator per chunk
        val localAgg = chunk.foldLeft(TdigestAggregator.empty)((agg, loss) => 
          agg.add(loss)
        )
        ZIO.succeed(localAgg)
      }
      .runCollect
    
    // 4. Merge via Monoid.combineAll
    finalAgg = Monoid[TdigestAggregator].combineAll(results)
    
    // 5. Generate LEC
    lec = OnlineSketchLEC.fromAggregator(
      finalAgg, 
      risks.map(_.id).toSet, 
      nTrials
    )
  } yield lec
  
  private def createSampler(config: RiskConfig): Task[RiskSampler] = ???
}
```

**Testing:** 20+ tests (sequential vs parallel determinism)

---

## Phase 7: ZIO Prelude Foundation

**build.sbt:**
```scala
libraryDependencies ++= Seq(
  "dev.zio" %% "zio-prelude" % "1.0.0-RC21",
  "no.promon.riskquant" % "simulation-util" % "1.0.0",
  // ... existing deps
)
```

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/PreludeInstances.scala`

```scala
package com.risquanter.register.domain

import zio.prelude.*

object PreludeInstances {
  // Monoid for Loss aggregation
  given Monoid[Long] with {
    def identity: Long = 0L
    def combine(a: Long, b: Long): Long = a + b
  }
  
  // Order for Loss
  given Order[Long] = Order.make((a, b) => a.compare(b))
}
```

**Testing:** Property tests for all instances

---

## Phase 8: RiskTransform for Composable Mitigations

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTransform.scala`

```scala
package com.risquanter.register.domain.data

import zio.prelude.*

/** Pure transformation of simulation results (mitigation strategies) */
case class RiskTransform(run: SimulationResult => SimulationResult)

object RiskTransform {
  val identity: RiskTransform = RiskTransform(r => r)
  
  given Monoid[RiskTransform] with {
    def identity: RiskTransform = RiskTransform.identity
    
    def combine(a: RiskTransform, b: RiskTransform): RiskTransform =
      RiskTransform(r => a.run(b.run(r)))
  }
  
  given Equal[RiskTransform] = Equal.default
  given Show[RiskTransform] = Show.make(_ => "RiskTransform(...)")
}
```

**Testing:** Monoid laws

---

## Phase 9: REST Endpoints

**File:** `modules/common/src/main/scala/com/risquanter/register/http/endpoints/SimulationExecutionEndpoints.scala`

```scala
case class ExpertOpinionRequest(
  simulationId: String,
  risks: Seq[RiskDefinition],
  nTrials: NonNegativeInt = 10000
)

case class RiskDefinition(
  riskId: String,
  occurrenceProb: Probability,
  lossPercentiles: Array[Probability],
  lossQuantiles: Array[Double],
  metalogTerms: PositiveInt = 9
)

case class LECResponse(
  simulationId: String,
  quantiles: Map[String, Double],
  exceedanceCurve: VegaLiteSpec
)
```

**Testing:** 10+ tests

---

## Phase 10: Vega-Lite Visualization

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/vegalite/LECDiagram.scala`

**Testing:** 8+ tests

---

## Phase 11: Provenance Metadata

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/metadata/Provenance.scala`

```scala
case class Provenance(
  seed3: Long,
  seed4: Long,
  metalogVersion: String,
  aggregatorType: String,
  compressionOrK: Double,
  timestamp: java.time.Instant
)
```

**Testing:** 5+ tests

---

## Phase 12: Comprehensive Testing Suite

**Testing Target:** 150+ tests
- 122 existing tests
- 28 new simulation/prelude tests

**Focus Areas:**
1. Parallelization correctness (determinism, no races)
2. Monoid laws (property tests)
3. Defensive inputs (validation catches errors)

---

## Phase 13: Integration and Deployment

**Verification:**
- All endpoints accessible via Swagger
- Performance: <5s for 10k trials, <500MB for 100k trials
- Aggregator switching documented (t-digest ↔ KLL)

```bash
curl -X POST http://localhost:8080/api/simulations/execute \
  -H "Content-Type: application/json" \
  -d '{
    "simulationId": "test-001",
    "risks": [{
      "riskId": "cyber-attack",
      "occurrenceProb": 0.15,
      "lossPercentiles": [0.1, 0.5, 0.9],
      "lossQuantiles": [5000, 25000, 100000]
    }],
    "nTrials": 10000
  }'
```

---

## Success Metrics

- ✅ **150+ tests passing**
- ✅ **Lawful type classes:** All Monoid/Equal instances pass property tests
- ✅ **Memory efficient:** <500MB for 100k trials
- ✅ **Fast execution:** <5s for 10k trials
- ✅ **Deterministic:** Identical results across parallelism levels
- ✅ **Production ready:** Deployed with aggregator switching
- ✅ **Category theory aligned:** BCG designs refactored to functional principles
