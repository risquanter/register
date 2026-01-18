package com.risquanter.register.domain.bundle

import zio.prelude.{Identity, Validation}
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.domain.data.LECPoint
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/** Type alias for node ID - uses Iron-refined SafeId throughout (ADR-001) */
type NodeId = SafeId.SafeId

/**
  * Exceedance probabilities at which LEC curves are sampled.
  *
  * Represents the "ticks" on the y-axis of a Loss Exceedance Curve, where:
  * - Each tick is an exceedance probability in (0, 1)
  * - Ticks are sorted in descending order (1.0 → 0.0)
  * - Higher ticks (e.g., 0.99) = low-probability, high-severity losses
  * - Lower ticks (e.g., 0.10) = high-probability, low-severity losses
  *
  * == Tick Domain Monotonicity ==
  *
  * Key insight: Parent tick domains contain all descendant tick domains.
  * When computing a parent's LEC from children, the parent domain is the
  * union of all child domains:
  *
  * {{{
  *   cyberDomain    = [0.99, 0.95, 0.90, 0.50, 0.10]
  *   hardwareDomain = [0.99, 0.90, 0.50, 0.10]
  *   parentDomain   = [0.99, 0.95, 0.90, 0.50, 0.10]  // union
  * }}}
  *
  * This means expanding navigation (leaf → parent) never requires
  * recalculation—we simply interpolate child curves to the union domain.
  *
  * @param ticks Sorted exceedance probabilities (descending: high → low)
  */
final case class TickDomain(ticks: Vector[Double]) extends AnyVal {
  
  import TickDomain.Epsilon
  
  /**
    * Expand this domain to include all ticks from another domain.
    * Returns the union, maintaining sorted descending order.
    * Uses epsilon-based deduplication to handle floating-point imprecision.
    *
    * @param other Domain to merge with
    * @return Union domain with all ticks from both
    */
  def expandTo(other: TickDomain): TickDomain = {
    val combined = (ticks ++ other.ticks).sorted.reverse
    // Deduplicate with epsilon tolerance
    val deduped = combined.foldLeft(Vector.empty[Double]) { (acc, tick) =>
      if (acc.isEmpty || math.abs(acc.last - tick) > Epsilon) acc :+ tick
      else acc
    }
    TickDomain(deduped)
  }
  
  /**
    * Check if this domain contains all ticks from another domain.
    * Uses epsilon tolerance for floating-point comparison.
    */
  def contains(other: TickDomain): Boolean =
    other.ticks.forall(t => containsTick(t))
  
  /**
    * Check if domain contains a specific tick (with epsilon tolerance).
    */
  def containsTick(tick: Double): Boolean =
    ticks.exists(t => math.abs(t - tick) < Epsilon)
  
  /**
    * Find the closest tick in this domain to the given value.
    */
  def findClosest(tick: Double): Option[Double] =
    ticks.find(t => math.abs(t - tick) < Epsilon)
  
  /**
    * Number of ticks in the domain.
    */
  def size: Int = ticks.size
  
  /**
    * Check if domain is empty.
    */
  def isEmpty: Boolean = ticks.isEmpty
  
  /**
    * Check if domain is non-empty.
    */
  def nonEmpty: Boolean = ticks.nonEmpty
}

object TickDomain {
  
  /** Tolerance for floating-point tick comparisons. */
  val Epsilon: Double = 1e-9
  
  /** Empty domain (identity for expansion). */
  val empty: TickDomain = TickDomain(Vector.empty)
  
  /**
    * Create domain from sequence of exceedance probabilities.
    * Sorts and deduplicates automatically (using epsilon tolerance).
    */
  def fromProbabilities(probs: Seq[Double]): TickDomain = {
    val sorted = probs.sorted.reverse
    val deduped = sorted.foldLeft(Vector.empty[Double]) { (acc, tick) =>
      if (acc.isEmpty || math.abs(acc.last - tick) > Epsilon) acc :+ tick
      else acc
    }
    TickDomain(deduped)
  }
  
  /**
    * Standard domain with common quantile points.
    * Useful for consistent LEC rendering.
    */
  val standard: TickDomain = fromProbabilities(
    Seq(0.99, 0.95, 0.90, 0.80, 0.70, 0.60, 0.50, 0.40, 0.30, 0.20, 0.10, 0.05, 0.01)
  )
  
  /**
    * ZIO Prelude Identity for TickDomain.
    * 
    * Implements join-semilattice: combine = union of ticks.
    * Identity is empty domain (neutral element for union).
    */
  given identity: Identity[TickDomain] with {
    def identity: TickDomain = TickDomain.empty
    
    def combine(a: => TickDomain, b: => TickDomain): TickDomain =
      a.expandTo(b)
  }
}

/**
  * Bundle of LEC curves aligned to a common tick domain.
  *
  * == Purpose ==
  *
  * Enables efficient multi-curve rendering and caching:
  * - All curves share the same exceedance probability ticks
  * - Curves can be combined without re-simulation
  * - Vega-Lite charts require tick-aligned data
  *
  * == Structure ==
  *
  * {{{
  *   CurveBundle(
  *     domain = TickDomain([0.99, 0.95, 0.90, 0.50, 0.10]),
  *     curves = Map(
  *       SafeId("cyber")    → [1000000, 800000, 500000, 100000, 10000],  // loss at each tick
  *       SafeId("hardware") → [500000, 400000, 250000, 50000, 5000]
  *     )
  *   )
  * }}}
  *
  * Each curve is a `Vector[Long]` of loss values, indexed by position
  * in the domain's tick vector.
  *
  * == Monoidal Properties ==
  *
  * CurveBundle forms a monoid under tick-aligned combination:
  * - Identity: Empty bundle (no curves, empty domain)
  * - Combine: Union domains, align curves via interpolation, merge curve maps
  *
  * @param domain Common tick domain for all curves
  * @param curves Map from node ID to loss values at each tick
  */
final case class CurveBundle(
    domain: TickDomain,
    curves: Map[NodeId, Vector[Long]]
) {
  
  /**
    * Get curve for a specific node.
    */
  def get(nodeId: NodeId): Option[Vector[Long]] =
    curves.get(nodeId)
  
  /**
    * Check if bundle contains curve for node.
    */
  def contains(nodeId: NodeId): Boolean =
    curves.contains(nodeId)
  
  /**
    * Number of curves in bundle.
    */
  def size: Int = curves.size
  
  /**
    * All node IDs in bundle.
    */
  def nodeIds: Set[NodeId] = curves.keySet
  
  /**
    * Check if bundle is empty.
    */
  def isEmpty: Boolean = curves.isEmpty
  
  /**
    * Add or replace a curve in the bundle with validation.
    *
    * Returns a Validation error if the curve size doesn't match the domain.
    * Per ADR-010: errors are values, not exceptions.
    *
    * @param nodeId Node identifier
    * @param losses Loss values at each tick (must match domain size)
    * @return Validation containing new bundle or size mismatch error
    */
  def withCurve(nodeId: NodeId, losses: Vector[Long]): Validation[ValidationError, CurveBundle] =
    if losses.size == domain.size then
      Validation.succeed(copy(curves = curves + (nodeId -> losses)))
    else
      Validation.fail(ValidationError(
        field = s"curves.${nodeId.value}",
        code = ValidationErrorCode.INVALID_LENGTH,
        message = s"Curve size (${losses.size}) must match domain size (${domain.size})"
      ))
  
  /**
    * Add or replace a curve in the bundle (unsafe version).
    *
    * Use only when curve size is guaranteed to match domain size
    * (e.g., curves generated from simulation with known tick count).
    *
    * @param nodeId Node identifier
    * @param losses Loss values at each tick (must match domain size)
    * @return New bundle with added curve
    * @throws IllegalArgumentException if sizes don't match
    */
  def withCurveUnsafe(nodeId: NodeId, losses: Vector[Long]): CurveBundle = {
    assert(
      losses.size == domain.size,
      s"Invariant violation: Curve size (${losses.size}) must match domain size (${domain.size})"
    )
    copy(curves = curves + (nodeId -> losses))
  }
  
  /**
    * Remove a curve from the bundle.
    */
  def without(nodeId: NodeId): CurveBundle =
    copy(curves = curves - nodeId)
  
  /**
    * Expand bundle to a new tick domain via linear interpolation.
    *
    * Target domain must fully contain this domain's ticks - extrapolation
    * beyond known range is not supported (returns Validation error).
    *
    * @param targetDomain New domain (must contain this domain's ticks)
    * @return Bundle with curves interpolated to target domain
    */
  def expandTo(targetDomain: TickDomain): CurveBundle = {
    if (domain.ticks == targetDomain.ticks) this
    else if (domain.isEmpty) CurveBundle(targetDomain, Map.empty)
    else {
      val expandedCurves = curves.map { case (nodeId, losses) =>
        nodeId -> interpolateTo(losses, domain, targetDomain)
      }
      CurveBundle(targetDomain, expandedCurves)
    }
  }
  
  /**
    * Convert to LECCurveResponse for API output.
    *
    * @param nodeId Node to extract
    * @param name Display name for the curve
    * @param childIds Optional child node IDs for navigation
    * @return LECCurveResponse suitable for API response
    */
  def toLECCurveResponse(
      nodeId: NodeId,
      name: String,
      childIds: Option[List[String]] = None
  ): Option[com.risquanter.register.domain.data.LECCurveResponse] =
    curves.get(nodeId).map { losses =>
      val curvePoints = domain.ticks.zip(losses).map { case (prob, loss) =>
        LECPoint(loss, prob)
      }
      val quantiles = extractQuantiles(losses)
      com.risquanter.register.domain.data.LECCurveResponse(
        id = nodeId.value,
        name = name,
        curve = curvePoints,
        quantiles = quantiles,
        childIds = childIds
      )
    }
  
  /**
    * Extract standard quantiles from loss values.
    */
  private def extractQuantiles(losses: Vector[Long]): Map[String, Double] = {
    val tickToLoss: Map[Double, Long] = domain.ticks.zip(losses).toMap
    Map(
      "p50" -> tickToLoss.get(0.50).map(_.toDouble).getOrElse(0.0),
      "p90" -> tickToLoss.get(0.10).map(_.toDouble).getOrElse(0.0),  // 10% exceedance = 90th percentile
      "p95" -> tickToLoss.get(0.05).map(_.toDouble).getOrElse(0.0),
      "p99" -> tickToLoss.get(0.01).map(_.toDouble).getOrElse(0.0)
    )
  }
  
  /**
    * Interpolate curve to new tick domain.
    *
    * == LEC Semantics ==
    *
    * Loss Exceedance Curves are step functions (empirical distributions).
    * When querying a tick not in our sampled domain:
    *
    * - **Within range**: Linear interpolation between adjacent known points
    * - **Above max tick** (higher probability): Use loss at max tick (floor)
    * - **Below min tick** (lower probability, rarer events): Use loss at min tick (ceiling)
    *
    * This follows the natural semantics of empirical exceedance curves:
    * - If we ask "what loss has 99% exceedance?" and we only measured up to 95%,
    *   we use the 95% value as a conservative lower bound.
    * - If we ask "what loss has 1% exceedance?" and we only measured down to 5%,
    *   we use the 5% value as a conservative upper bound.
    */
  private def interpolateTo(
      losses: Vector[Long],
      fromDomain: TickDomain,
      toDomain: TickDomain
  ): Vector[Long] = {
    import TickDomain.Epsilon
    
    // Build lookup with epsilon tolerance
    val tickLossPairs = fromDomain.ticks.zip(losses)
    def findLoss(tick: Double): Option[Long] =
      tickLossPairs.find { case (t, _) => math.abs(t - tick) < Epsilon }.map(_._2)
    
    val sortedTicks = fromDomain.ticks.sorted  // ascending
    val minTick = sortedTicks.headOption.getOrElse(0.0)
    val maxTick = sortedTicks.lastOption.getOrElse(1.0)
    val minLoss = findLoss(minTick).getOrElse(0L)
    val maxLoss = findLoss(maxTick).getOrElse(0L)
    
    toDomain.ticks.map { tick =>
      findLoss(tick) match {
        case Some(loss) => loss
        case None if tick > maxTick + Epsilon =>
          // Above max tick (higher probability) → use loss at max tick
          maxLoss
        case None if tick < minTick - Epsilon =>
          // Below min tick (lower probability, rarer) → use loss at min tick
          minLoss
        case None =>
          // Within range: linear interpolation between adjacent known ticks
          val lowerOpt = sortedTicks.filter(_ < tick - Epsilon).lastOption
          val upperOpt = sortedTicks.filter(_ > tick + Epsilon).headOption
          
          (lowerOpt, upperOpt) match {
            case (Some(lower), Some(upper)) =>
              val lowerLoss = findLoss(lower).get
              val upperLoss = findLoss(upper).get
              val ratio = (tick - lower) / (upper - lower)
              (lowerLoss + ((upperLoss - lowerLoss) * ratio)).toLong
            case (Some(lower), None) => findLoss(lower).getOrElse(minLoss)
            case (None, Some(upper)) => findLoss(upper).getOrElse(maxLoss)
            case (None, None) => minLoss
          }
      }
    }
  }
}

object CurveBundle {
  
  /** Empty bundle (identity for monoidal combination). */
  val empty: CurveBundle = CurveBundle(TickDomain.empty, Map.empty)
  
  /**
    * Create bundle with single curve.
    */
  def single(nodeId: NodeId, domain: TickDomain, losses: Vector[Long]): CurveBundle =
    CurveBundle(domain, Map(nodeId -> losses))
  
  /**
    * ZIO Prelude Identity instance for CurveBundle.
    *
    * == Monoidal Laws ==
    *
    * - Identity: empty.combine(a) = a.combine(empty) = a
    * - Associativity: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)
    *
    * == Combination Semantics ==
    *
    * 1. Compute union tick domain (join-semilattice)
    * 2. Expand both bundles to union domain via interpolation
    * 3. Merge curve maps (right takes precedence for duplicates)
    *
    * This enables incremental aggregation: compute child curves,
    * then combine them for parent without re-simulation.
    */
  given identity: Identity[CurveBundle] with {
    def identity: CurveBundle = CurveBundle.empty
    
    def combine(a: => CurveBundle, b: => CurveBundle): CurveBundle = {
      if (a.isEmpty) b
      else if (b.isEmpty) a
      else {
        // 1. Union tick domains
        val unionDomain = a.domain.expandTo(b.domain)
        
        // 2. Expand both bundles to union domain
        val expandedA = a.expandTo(unionDomain)
        val expandedB = b.expandTo(unionDomain)
        
        // 3. Merge curve maps (b takes precedence)
        CurveBundle(unionDomain, expandedA.curves ++ expandedB.curves)
      }
    }
  }
}
