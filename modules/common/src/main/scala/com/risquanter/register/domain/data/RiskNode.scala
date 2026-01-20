package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonDecoder, JsonEncoder, jsonField}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, DistributionType, Probability, NonNegativeLong}
import com.risquanter.register.domain.tree.NodeId

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
  def id: SafeId.SafeId
  def name: String
  def parentId: Option[NodeId]
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
  * - safeId: SafeId (3-30 alphanumeric chars + hyphen/underscore)
  * - safeName: SafeName (non-blank, max 50 chars)
  * - distributionType: DistributionType ("expert" or "lognormal")
  * - probability: Probability (0.0 < p < 1.0)
  * - minLoss/maxLoss: NonNegativeLong (>= 0)
  * 
  * @param safeId Unique identifier (Iron refined type)
  * @param safeName Human-readable risk name (Iron refined type)
  * @param distributionType "expert" or "lognormal"
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param percentiles Expert opinion: percentiles [0.0, 1.0] (expert mode only)
  * @param quantiles Expert opinion: loss values in millions (expert mode only)
  * @param minLoss Lognormal: 80% CI lower bound in millions (lognormal mode only)
  * @param maxLoss Lognormal: 80% CI upper bound in millions (lognormal mode only)
  */
final case class RiskLeaf private (
  @jsonField("id") safeId: SafeId.SafeId,
  @jsonField("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  distributionType: DistributionType,
  probability: Probability,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong]
) extends RiskNode {
  // Defense in depth: invariant check as safety net
  // Should never trigger if custom decoder works correctly
  require(
    distributionType.toString match {
      case "expert" => percentiles.exists(_.nonEmpty) && quantiles.exists(_.nonEmpty)
      case "lognormal" => minLoss.isDefined && maxLoss.isDefined && minLoss.get < maxLoss.get
      case _ => true
    },
    s"RiskLeaf invariant violated: $distributionType mode missing required fields or invalid bounds"
  )
  
  // Public API: Extract values from Iron types
  override def id: SafeId.SafeId = safeId
  override def name: String = safeName.value.toString
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
    maxLoss: Option[Long] = None,
    parentId: Option[NodeId] = None
  ): RiskLeaf = {
    // Unsafe: Assumes valid input (for backward compatibility only)
    create(id, name, distributionType, probability, percentiles, quantiles, minLoss, maxLoss, parentId = parentId)
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
    maxLoss: Option[Long] = None,
    parentId: Option[NodeId] = None,
    fieldPrefix: String = "root"
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskLeaf] = {
    
    import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Step 1: Validate all individual fields in parallel
    val idV = toValidation(ValidationUtil.refineId(id, s"$fieldPrefix.id"))
    val nameV = toValidation(ValidationUtil.refineName(name, s"$fieldPrefix.name"))
    val probV = toValidation(ValidationUtil.refineProbability(probability, s"$fieldPrefix.probability"))
    val distTypeV = toValidation(ValidationUtil.refineDistributionType(distributionType, s"$fieldPrefix.distributionType"))
    
    // Step 2: Apply cross-field business rules based on distribution type
    // Use flatMap for dependent validation (requires distTypeV to succeed first)
    val crossFieldV = distTypeV.flatMap { dt =>
      dt.toString match {
        case "expert" => validateExpertMode(percentiles, quantiles, fieldPrefix)
        case "lognormal" => validateLognormalMode(minLoss, maxLoss, fieldPrefix)
        case unknown => failOnUnknownDistributionType(unknown, fieldPrefix)
      }
    }
    
    // Step 3: Combine all validations (parallel accumulation where possible)
    Validation.validateWith(idV, nameV, probV, distTypeV, crossFieldV) {
      case (validId, validName, validProb, validDistType, (validMinLoss, validMaxLoss)) =>
        new RiskLeaf(
          safeId = validId,
          safeName = validName,
          parentId = parentId,
          distributionType = validDistType,
          probability = validProb,
          percentiles = percentiles,
          quantiles = quantiles,
          minLoss = validMinLoss,
          maxLoss = validMaxLoss
        )
    }
  }
  
  /** Validate expert mode: requires non-empty percentiles and quantiles of equal length */
  private def validateExpertMode(
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    fieldPrefix: String
  ): Validation[com.risquanter.register.domain.errors.ValidationError, (Option[NonNegativeLong], Option[NonNegativeLong])] = {
    import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
    
    (percentiles, quantiles) match {
      case (Some(p), Some(q)) if p.nonEmpty && q.nonEmpty =>
        if (p.length != q.length)
          Validation.fail(ValidationError(
            field = s"$fieldPrefix.distributionType",
            code = ValidationErrorCode.INVALID_COMBINATION,
            message = s"Expert mode: percentiles and quantiles must have same length (got ${p.length} vs ${q.length})"
          ))
        else
          Validation.succeed((None, None))
      
      case (None, None) =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.distributionType",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Expert mode requires both percentiles and quantiles"
        ))
      
      case (None, _) =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.percentiles",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Expert mode requires percentiles"
        ))
      
      case (_, None) =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.quantiles",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Expert mode requires quantiles"
        ))
      
      case _ =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.distributionType",
          code = ValidationErrorCode.INVALID_COMBINATION,
          message = "Expert mode: percentiles and quantiles cannot be empty"
        ))
    }
  }
  
  /** Validate lognormal mode: requires minLoss < maxLoss */
  private def validateLognormalMode(
    minLoss: Option[Long],
    maxLoss: Option[Long],
    fieldPrefix: String
  ): Validation[com.risquanter.register.domain.errors.ValidationError, (Option[NonNegativeLong], Option[NonNegativeLong])] = {
    import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    (minLoss, maxLoss) match {
      case (Some(min), Some(max)) =>
        val minV = toValidation(ValidationUtil.refineNonNegativeLong(min, s"$fieldPrefix.minLoss"))
        val maxV = toValidation(ValidationUtil.refineNonNegativeLong(max, s"$fieldPrefix.maxLoss"))
        
        // Validate both, then check cross-field constraint
        Validation.validateWith(minV, maxV) { (validMin, validMax) =>
          if (validMin >= validMax)
            Validation.fail(ValidationError(
              field = s"$fieldPrefix.minLoss",
              code = ValidationErrorCode.INVALID_RANGE,
              message = s"minLoss ($validMin) must be less than maxLoss ($validMax)"
            ))
          else
            Validation.succeed((Some(validMin), Some(validMax)))
        }.flatten
      
      case (None, None) =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.distributionType",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Lognormal mode requires both minLoss and maxLoss"
        ))
      
      case (None, _) =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.minLoss",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Lognormal mode requires minLoss"
        ))
      
      case (_, None) =>
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.maxLoss",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Lognormal mode requires maxLoss"
        ))
    }
  }
  
  /** Defense in depth: Fail validation for unknown distribution types that bypass Iron constraint */
  private def failOnUnknownDistributionType(
    unknown: String,
    fieldPrefix: String
  ): Validation[com.risquanter.register.domain.errors.ValidationError, (Option[NonNegativeLong], Option[NonNegativeLong])] = {
    import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
    
    Validation.fail(ValidationError(
      field = s"$fieldPrefix.distributionType",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = s"Invalid distribution type '$unknown' - expected 'expert' or 'lognormal'"
    ))
  }
  
  // --- Custom JSON Codec that enforces cross-field validation via smart constructor ---
  
  /** Raw intermediate type for JSON wire format (primitives only).
    * 
    * Per ADR-001: Wire format uses primitives, domain uses Iron types.
    * - Decoder: RiskLeafRaw (primitives) → create() → RiskLeaf (Iron types)
    * - Encoder: RiskLeaf (Iron types) → RiskLeafRaw (primitives) → JSON
    */
  private case class RiskLeafRaw(
    id: String,
    name: String,
    parentId: Option[String],
    distributionType: String,
    probability: Double,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[Long],
    maxLoss: Option[Long]
  )
  private object RiskLeafRaw {
    given rawCodec: JsonCodec[RiskLeafRaw] = DeriveJsonCodec.gen[RiskLeafRaw]
  }
  
  /** Custom decoder that uses smart constructor for cross-field validation */
  given decoder: JsonDecoder[RiskLeaf] = RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
    // Convert parentId string to NodeId if present
    val parentIdOpt: Either[String, Option[NodeId]] = raw.parentId match {
      case None => Right(None)
      case Some(pid) => SafeId.fromString(pid).map(Some(_)).left.map(_.mkString(", "))
    }
    parentIdOpt.flatMap { validParentId =>
      create(
        raw.id, raw.name, raw.distributionType, raw.probability,
        raw.percentiles, raw.quantiles, raw.minLoss, raw.maxLoss,
        parentId = validParentId,
        fieldPrefix = s"riskLeaf[id=${raw.id}]"
      ).toEither.left.map(errors => errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; "))
    }
  }
  
  /** Encoder: Extract primitives from Iron types for wire format */
  given encoder: JsonEncoder[RiskLeaf] = JsonEncoder[RiskLeafRaw].contramap { leaf =>
    RiskLeafRaw(
      id = leaf.id.value.toString,
      name = leaf.name,
      parentId = leaf.parentId.map(_.value.toString),
      distributionType = leaf.distributionType.toString,
      probability = leaf.probability,
      percentiles = leaf.percentiles,
      quantiles = leaf.quantiles,
      minLoss = leaf.minLoss.map(identity),
      maxLoss = leaf.maxLoss.map(identity)
    )
  }
  
  given codec: JsonCodec[RiskLeaf] = JsonCodec(encoder, decoder)
}

/** RiskPortfolio node: Aggregates child risks (can be leaves or other portfolios).
  * 
  * Behavior:
  * - No distribution parameters (computes from children)
  * - No probability (implicitly 1.0 - portfolio always "occurs")
  * - Loss = sum of all children's losses per trial
  * - Can nest arbitrarily deep (typically 5-6 levels)
  * 
  * Domain Model: Uses Iron refined types for type safety
  * - safeId: SafeId (3-30 alphanumeric chars + hyphen/underscore)
  * - safeName: SafeName (non-blank, max 50 chars)
  * - childIds: Array[NodeId] (references to child nodes, must be non-empty)
  * 
  * @param safeId Unique identifier (Iron refined type)
  * @param safeName Human-readable portfolio name (Iron refined type)
  * @param parentId Optional parent node ID (None for root)
  * @param childIds Array of child node IDs (references, not embedded objects)
  */
final case class RiskPortfolio private (
  @jsonField("id") safeId: SafeId.SafeId,
  @jsonField("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  childIds: Array[NodeId]
) extends RiskNode {
  // Defense in depth: invariant check as safety net
  require(childIds != null && childIds.nonEmpty, "RiskPortfolio invariant violated: childIds must be non-empty")
  
  // Public API: Extract values from Iron types
  override def id: SafeId.SafeId = safeId
  override def name: String = safeName.value.toString
}

object RiskPortfolio {
  import zio.prelude.Validation
  import com.risquanter.register.domain.data.iron._
  
  /** Smart constructor: Validates all fields and returns Validation.
    * 
    * Validation Rules:
    * 1. ID must be valid SafeId (3-30 chars, alphanumeric + hyphen/underscore)
    * 2. Name must be valid SafeName (non-blank, max 50 chars)
    * 3. childIds array must be non-empty
    * 
    * Returns:
    * - Validation.succeed(RiskPortfolio) if all validations pass
    * - Validation.fail(errors) if any validation fails (accumulates all errors)
    */
  def create(
    id: String,
    name: String,
    childIds: Array[NodeId],
    parentId: Option[NodeId] = None,
    fieldPrefix: String = "root"
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskPortfolio] = {
    
    import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Step 1: Validate ID (Iron refinement)
    val idValidation: Validation[ValidationError, SafeId.SafeId] = 
      toValidation(ValidationUtil.refineId(id, s"$fieldPrefix.id"))
    
    // Step 2: Validate name (Iron refinement)
    val nameValidation: Validation[ValidationError, SafeName.SafeName] =
      toValidation(ValidationUtil.refineName(name, s"$fieldPrefix.name"))
    
    // Step 3: Validate childIds array (business rule)
    val childIdsValidation: Validation[ValidationError, Array[NodeId]] =
      if (childIds == null || childIds.isEmpty) {
        Validation.fail(ValidationError(
          field = s"$fieldPrefix.childIds",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "childIds array must not be empty"
        ))
      } else {
        Validation.succeed(childIds)
      }
    
    // Step 4: Combine all validations (parallel error accumulation)
    Validation.validateWith(
      idValidation,
      nameValidation,
      childIdsValidation
    ) { case (validId, validName, validChildIds) =>
      // All validations passed - construct with private constructor using Iron types
      new RiskPortfolio(
        safeId = validId,
        safeName = validName,
        parentId = parentId,
        childIds = validChildIds
      )
    }
  }
  
  /** Alternative constructor from string child IDs (for API convenience) */
  def createFromStrings(
    id: String,
    name: String,
    childIds: Array[String],
    parentId: Option[NodeId] = None,
    fieldPrefix: String = "root"
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskPortfolio] = {
    import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
    
    // Convert string childIds to NodeIds
    val childIdResults = childIds.zipWithIndex.map { case (cid, idx) =>
      SafeId.fromString(cid).left.map { errors =>
        ValidationError(
          field = s"$fieldPrefix.childIds[$idx]",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = errors.mkString(", ")
        )
      }
    }
    
    val errors = childIdResults.collect { case Left(e) => e }
    if (errors.nonEmpty) {
      // Accumulate all errors using NonEmptyChunk
      import zio.prelude.Validation
      val nonEmpty = zio.NonEmptyChunk.fromIterable(errors.head, errors.tail)
      Validation.failNonEmptyChunk(nonEmpty)
    } else {
      val validChildIds = childIdResults.collect { case Right(id) => id }
      create(id, name, validChildIds, parentId, fieldPrefix)
    }
  }
  
  // --- Custom JSON Codec that enforces cross-field validation via smart constructor ---
  
  /** Raw intermediate type for JSON wire format (primitives only).
    * Per ADR-001: Wire format uses primitives, domain uses Iron types.
    */
  private case class RiskPortfolioRaw(
    id: String,
    name: String,
    parentId: Option[String],
    childIds: Array[String]
  )
  private object RiskPortfolioRaw {
    given rawCodec: JsonCodec[RiskPortfolioRaw] = DeriveJsonCodec.gen[RiskPortfolioRaw]
  }
  
  /** Custom decoder that uses smart constructor for cross-field validation */
  given decoder: JsonDecoder[RiskPortfolio] = RiskPortfolioRaw.rawCodec.decoder.mapOrFail { raw =>
    // Convert parentId string to NodeId if present
    val parentIdOpt: Either[String, Option[NodeId]] = raw.parentId match {
      case None => Right(None)
      case Some(pid) => SafeId.fromString(pid).map(Some(_)).left.map(_.mkString(", "))
    }
    parentIdOpt.flatMap { validParentId =>
      createFromStrings(raw.id, raw.name, raw.childIds, parentId = validParentId, fieldPrefix = s"riskPortfolio[id=${raw.id}]")
        .toEither.left.map(errors => errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; "))
    }
  }
  
  /** Encoder: Extract primitives from Iron types for wire format */
  given encoder: JsonEncoder[RiskPortfolio] = JsonEncoder[RiskPortfolioRaw].contramap { portfolio =>
    RiskPortfolioRaw(
      id = portfolio.id.value.toString,
      name = portfolio.name,
      parentId = portfolio.parentId.map(_.value.toString),
      childIds = portfolio.childIds.map(_.value.toString)
    )
  }
  
  given codec: JsonCodec[RiskPortfolio] = JsonCodec(encoder, decoder)
  
  /** Temporary backward compatibility method - bypasses validation.
    * 
    * WARNING: This method will be removed once service layer is refactored.
    * Use create() for new code.
    */
  def unsafeApply(
    id: String,
    name: String,
    childIds: Array[NodeId],
    parentId: Option[NodeId] = None
  ): RiskPortfolio = {
    // Force refinement (throws on failure)
    val validId = SafeId.fromString(id).getOrElse(
      throw new IllegalArgumentException(s"Invalid ID: $id")
    )
    val validName = SafeName.fromString(name).getOrElse(
      throw new IllegalArgumentException(s"Invalid name: $name")
    )
    new RiskPortfolio(safeId = validId, safeName = validName, parentId = parentId, childIds = childIds)
  }
  
  /** Helper: Create portfolio from string child IDs (for test convenience) */
  def unsafeFromStrings(
    id: String,
    name: String,
    childIds: Array[String],
    parentId: Option[NodeId] = None
  ): RiskPortfolio = {
    val validChildIds = childIds.map { cid =>
      SafeId.fromString(cid).getOrElse(
        throw new IllegalArgumentException(s"Invalid child ID: $cid")
      )
    }
    unsafeApply(id, name, validChildIds, parentId)
  }
}
