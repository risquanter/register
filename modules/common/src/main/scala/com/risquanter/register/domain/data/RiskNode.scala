package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonDecoder, JsonEncoder}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, DistributionType, Probability, NonNegativeLong}

/** Recursive ADT representing a risk hierarchy tree.
  * 
  * Tree Structure:
  * - RiskLeaf: Terminal node with distribution parameters (actual risk)
  * - RiskPortfolio: Branch node containing children (aggregation of risks)
  * 
  * Example:
  * {{{
  * RiskPortfolio(
  *   id = "ops-risk",
  *   name = "Operational Risk",
  *   children = Array(
  *     RiskLeaf(
  *       id = "cyber",
  *       name = "Cyber Attack",
  *       distributionType = "lognormal",
  *       probability = 0.25,
  *       minLoss = Some(1000),
  *       maxLoss = Some(50000)
  *     ),
  *     RiskPortfolio(
  *       id = "it-risk",
  *       name = "IT Risk",
  *       children = Array(...)
  *     )
  *   )
  * )
  * }}}
  */
sealed trait RiskNode {
  def id: String
  def name: String
}

object RiskNode {
  // Recursive JSON codec - handles nested structures
  given codec: JsonCodec[RiskNode] = DeriveJsonCodec.gen[RiskNode]
  
  // Tapir schema: Use Schema.any to avoid recursive derivation
  // This tells Tapir to skip validation and just pass through the JSON
  given schema: Schema[RiskNode] = Schema.any[RiskNode]
}

/** Leaf node: Represents an actual risk with a loss distribution.
  * 
  * Distribution Modes:
  * - Expert Opinion: distributionType="expert", provide percentiles + quantiles
  * - Lognormal (BCG): distributionType="lognormal", provide minLoss + maxLoss (80% CI)
  * 
  * Domain Model: Uses Iron refined types for type safety
  * - _id: SafeId (3-30 alphanumeric chars + hyphen/underscore)
  * - _name: SafeName (non-blank, max 50 chars)
  * - distributionType: DistributionType ("expert" or "lognormal")
  * - probability: Probability (0.0 < p < 1.0)
  * - minLoss/maxLoss: NonNegativeLong (>= 0)
  * 
  * @param _id Unique identifier for this risk (must be unique within tree)
  * @param _name Human-readable risk name
  * @param distributionType "expert" or "lognormal"
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param percentiles Expert opinion: percentiles [0.0, 1.0] (expert mode only)
  * @param quantiles Expert opinion: loss values in millions (expert mode only)
  * @param minLoss Lognormal: 80% CI lower bound in millions (lognormal mode only)
  * @param maxLoss Lognormal: 80% CI upper bound in millions (lognormal mode only)
  */
final case class RiskLeaf private (
  _id: SafeId.SafeId,
  _name: SafeName.SafeName,
  distributionType: DistributionType,
  probability: Probability,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong]
) extends RiskNode {
  // Implement trait methods by extracting String values from Iron types
  override def id: String = _id.value.toString
  override def name: String = _name.value.toString
}

object RiskLeaf {
  import zio.prelude.Validation
  import com.risquanter.register.domain.data.iron._
  
  // JSON encoders/decoders for Iron types (encode as underlying primitives)
  given JsonEncoder[SafeId.SafeId] = JsonEncoder[String].contramap(_.value.toString)
  given JsonEncoder[SafeName.SafeName] = JsonEncoder[String].contramap(_.value.toString)
  given JsonEncoder[DistributionType] = JsonEncoder[String].contramap(_.toString)
  given JsonEncoder[Probability] = JsonEncoder[Double].contramap(identity)
  given JsonEncoder[NonNegativeLong] = JsonEncoder[Long].contramap(identity)
  
  given JsonDecoder[SafeId.SafeId] = JsonDecoder[String].mapOrFail(s => 
    SafeId.fromString(s).left.map(_.mkString(", "))
  )
  given JsonDecoder[SafeName.SafeName] = JsonDecoder[String].mapOrFail(s =>
    SafeName.fromString(s).left.map(_.mkString(", "))
  )
  given JsonDecoder[DistributionType] = JsonDecoder[String].mapOrFail(s =>
    ValidationUtil.refineDistributionType(s).left.map(_.mkString(", "))
  )
  given JsonDecoder[Probability] = JsonDecoder[Double].mapOrFail(d =>
    ValidationUtil.refineProbability(d).left.map(_.mkString(", "))
  )
  given JsonDecoder[NonNegativeLong] = JsonDecoder[Long].mapOrFail(l =>
    ValidationUtil.refineNonNegativeLong(l, "value").left.map(_.mkString(", "))
  )
  
  // Temporary: Unsafe constructor for backward compatibility during migration
  // TODO: Remove this in Step 3 when service is refactored
  def unsafeApply(
    id: String,
    name: String,
    distributionType: String,
    probability: Double,
    percentiles: Option[Array[Double]] = None,
    quantiles: Option[Array[Double]] = None,
    minLoss: Option[Long] = None,
    maxLoss: Option[Long] = None
  ): RiskLeaf = {
    // Unsafe: Assumes valid input (for backward compatibility only)
    create(id, name, distributionType, probability, percentiles, quantiles, minLoss, maxLoss)
      .toEither
      .fold(
        errors => throw new IllegalArgumentException(s"Invalid RiskLeaf: $errors"),
        identity
      )
  }
  
  /**
   * Smart constructor - validates all fields and constructs RiskLeaf with Iron types.
   * 
   * @param id Plain string identifier (will be refined to SafeId)
   * @param name Plain string name (will be refined to SafeName)
   * @param distributionType Plain string ("expert" or "lognormal", will be refined)
   * @param probability Plain double [0.0, 1.0] (will be refined to Probability)
   * @param percentiles Optional array of percentiles (expert mode)
   * @param quantiles Optional array of loss quantiles (expert mode)
   * @param minLoss Optional min loss (lognormal mode, will be refined to NonNegativeLong)
   * @param maxLoss Optional max loss (lognormal mode, will be refined to NonNegativeLong)
   * @return Validation with all errors accumulated, or valid RiskLeaf
   */
  def create(
    id: String,
    name: String,
    distributionType: String,
    probability: Double,
    percentiles: Option[Array[Double]] = None,
    quantiles: Option[Array[Double]] = None,
    minLoss: Option[Long] = None,
    maxLoss: Option[Long] = None
  ): Validation[String, RiskLeaf] = {
    
    // Helper: Convert Either[List[String], A] to Validation[String, A]
    def toValidation[A](either: Either[List[String], A]): Validation[String, A] =
      Validation.fromEither(either.left.map(_.mkString("; ")))
    
    // Step 1: Validate and refine id to SafeId
    val idValidation: Validation[String, SafeId.SafeId] =
      toValidation(ValidationUtil.refineId(id))
    
    // Step 2: Validate and refine name to SafeName
    val nameValidation: Validation[String, SafeName.SafeName] =
      toValidation(ValidationUtil.refineName(name))
    
    // Step 3: Validate and refine probability to Probability
    val probValidation: Validation[String, Probability] =
      toValidation(ValidationUtil.refineProbability(probability))
    
    // Step 4: Validate and refine distributionType to DistributionType
    val distTypeValidation: Validation[String, DistributionType] =
      toValidation(ValidationUtil.refineDistributionType(distributionType))
    
    // Step 5: Mode-specific validation (cross-field business rules)
    val modeValidation: Validation[String, (Option[NonNegativeLong], Option[NonNegativeLong])] = 
      distTypeValidation.flatMap { dt =>
        dt.toString match {
          case "expert" =>
            // Expert mode: requires percentiles AND quantiles
            (percentiles, quantiles) match {
              case (Some(p), Some(q)) if p.nonEmpty && q.nonEmpty =>
                if (p.length != q.length)
                  Validation.fail("Expert mode: percentiles and quantiles must have same length")
                else
                  Validation.succeed((None, None))  // No minLoss/maxLoss for expert
              case (None, None) =>
                Validation.fail("Expert mode requires both percentiles and quantiles")
              case (None, _) =>
                Validation.fail("Expert mode requires percentiles")
              case (_, None) =>
                Validation.fail("Expert mode requires quantiles")
              case _ =>
                Validation.fail("Expert mode: percentiles and quantiles cannot be empty")
            }
            
          case "lognormal" =>
            // Lognormal mode: requires minLoss AND maxLoss
            (minLoss, maxLoss) match {
              case (Some(min), Some(max)) =>
                // Refine to NonNegativeLong (keep refined types)
                val minValid: Validation[String, NonNegativeLong] =
                  toValidation(ValidationUtil.refineNonNegativeLong(min, "minLoss"))
                val maxValid: Validation[String, NonNegativeLong] =
                  toValidation(ValidationUtil.refineNonNegativeLong(max, "maxLoss"))
                
                // Cross-field validation: minLoss < maxLoss
                Validation.validateWith(minValid, maxValid) { (validMin, validMax) =>
                  if (validMin >= validMax)
                    Validation.fail(
                      s"minLoss ($validMin) must be less than maxLoss ($validMax)"
                    )
                  else
                    Validation.succeed((Some(validMin), Some(validMax)))
                }.flatten
                
              case (None, None) =>
                Validation.fail("Lognormal mode requires both minLoss and maxLoss")
              case (None, _) =>
                Validation.fail("Lognormal mode requires minLoss")
              case (_, None) =>
                Validation.fail("Lognormal mode requires maxLoss")
            }
            
          case _ =>
            // Should never happen due to Iron validation
            Validation.succeed((None, None))
        }
      }
    
    // Step 6: Combine all validations (parallel error accumulation)
    Validation.validateWith(
      idValidation,
      nameValidation,
      probValidation,
      distTypeValidation,
      modeValidation
    ) { case (validId, validName, validProb, validDistType, minMaxTuple) =>
      val (validMinLoss, validMaxLoss) = minMaxTuple
      // All validations passed - construct with private constructor using Iron types
      new RiskLeaf(
        _id = validId,
        _name = validName,
        distributionType = validDistType,
        probability = validProb,
        percentiles = percentiles,
        quantiles = quantiles,
        minLoss = validMinLoss,
        maxLoss = validMaxLoss
      )
    }
  }
  
  // JSON codec (TODO: Will need custom decoder using create() in next phase)
  given codec: JsonCodec[RiskLeaf] = DeriveJsonCodec.gen[RiskLeaf]
}

/** RiskPortfolio node: Aggregates child risks (can be leaves or other portfolios).
  * 
  * Behavior:
  * - No distribution parameters (computes from children)
  * - No probability (implicitly 1.0 - portfolio always "occurs")
  * - Loss = sum of all children's losses per trial
  * - Can nest arbitrarily deep (typically 5-6 levels)
  * 
  * @param id Unique identifier for this portfolio (must be unique within tree)
  * @param name Human-readable portfolio name
  * @param children Array of child nodes (RiskLeaf or RiskPortfolio)
  */
final case class RiskPortfolio(
  id: String,
  name: String,
  children: Array[RiskNode]
) extends RiskNode

object RiskPortfolio {
  given codec: JsonCodec[RiskPortfolio] = DeriveJsonCodec.gen[RiskPortfolio]
  
  /** Helper: Create a flat portfolio from legacy risk array */
  def fromFlatRisks(id: String, name: String, risks: Array[RiskNode]): RiskPortfolio =
    RiskPortfolio(id, name, risks)
}
