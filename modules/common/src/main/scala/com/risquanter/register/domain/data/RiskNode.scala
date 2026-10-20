package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonDecoder, JsonEncoder, jsonField}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, DistributionType, OccurrenceProbability, PositiveLong, NodeId, PositiveInt, SeedVarId}
import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode}

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
  def id: NodeId
  def name: SafeName.SafeName
  def parentId: Option[NodeId]
}

object RiskNode {
  import sttp.tapir.generic.auto.*
  import com.risquanter.register.http.codecs.IronTapirCodecs.given

  // Recursive JSON codec - handles nested structures
  given codec: JsonCodec[RiskNode] = DeriveJsonCodec.gen[RiskNode]

  given schema: Schema[RiskNode] = Schema.derived[RiskNode]
}

/** Leaf node: Represents an actual risk with a loss distribution.
  * 
  * Distribution Modes:
  * - Expert Opinion: distributionType="expert", provide percentiles + quantiles
  * - Lognormal (BCG): distributionType="lognormal", provide minLoss + maxLoss (90% CI)
  * 
  * Domain Model: Uses Iron refined types for type safety
  * - safeId: SafeId (3-30 alphanumeric chars + hyphen/underscore)
  * - safeName: SafeName (non-blank, max 50 chars)
  * - distributionType: DistributionType ("expert" or "lognormal")
  * - probability: Probability (0.0 < p < 1.0)
  * - minLoss/maxLoss: PositiveLong (> 0 — both feed a logarithm in the distribution fit)
  * 
  * @param safeId Unique identifier (Iron refined type)
  * @param safeName Human-readable risk name (Iron refined type)
  * @param distributionType "expert" or "lognormal"
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param percentiles Expert opinion: percentiles [0.0, 1.0] (expert mode only)
  * @param quantiles Expert opinion: loss values in millions (expert mode only)
  * @param minLoss Lognormal: 90% CI lower bound in millions (lognormal mode only)
  * @param maxLoss Lognormal: 90% CI upper bound in millions (lognormal mode only)
  * @param seedVarId Stochastic identity: selects the leaf's HDR random streams
  *                  (occurrence = 2k, loss = 2k+1). Assigned at the creation
  *                  boundary, immutable, unique per tree. Independent of the
  *                  app identity (SafeId) — see PLAN-SEED-IDENTITY.md.
  */
final case class RiskLeaf private (
  // Both annotations are required and name the same wire field: @jsonField
  // renames it for the zio-json codec, @encodedName for the Tapir schema that
  // generates the OpenAPI document. They are read by different derivations.
  @jsonField("id") @encodedName("id") safeId: SafeId.SafeId,
  @jsonField("name") @encodedName("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  distributionType: DistributionType,
  probability: OccurrenceProbability,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[PositiveLong],
  maxLoss: Option[PositiveLong],
  terms: Option[PositiveInt],
  seedVarId: SeedVarId.SeedVarId
) extends RiskNode {
  // Defense in depth: invariant check as safety net
  // Should never trigger if custom decoder works correctly
  require(
    distributionType.toString match {
      case "expert" => percentiles.exists(_.nonEmpty) && quantiles.exists(_.nonEmpty)
      case "lognormal" =>
        minLoss.isDefined && maxLoss.isDefined && minLoss.get > 0L && minLoss.get < maxLoss.get
      case _ => true
    },
    s"RiskLeaf invariant violated: $distributionType mode missing required fields or invalid bounds"
  )
  
  // Public API: Extract values from Iron types
  override def id: NodeId = NodeId(safeId)
  override def name: SafeName.SafeName = safeName
}

object RiskLeaf {
  import zio.prelude.Validation
  import com.risquanter.register.domain.data.iron._

  /** Expert-mode percentile/quantile point-count bounds, matching the metalog
    * fitter's valid term range [2, 20]. Fewer than 2 or more than 20 points
    * makes the fitter throw past the validation boundary. Because
    * requireTermsWithinPercentiles enforces terms <= percentiles.length, bounding
    * the point count here transitively bounds the fitted term count. */
  private val MinPercentiles = 2
  private val MaxPercentiles = 20

  /** Production constructor: accepts already-validated Iron types, bypasses re-validation.
    * Use when domain types are already refined (e.g., from Distribution in buildNodes).
    */
  def fromValidated(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    distributionType: DistributionType,
    probability: OccurrenceProbability,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[PositiveLong],
    maxLoss: Option[PositiveLong],
    parentId: Option[NodeId],
    terms: Option[PositiveInt],
    seedVarId: SeedVarId.SeedVarId
  ): RiskLeaf =
    new RiskLeaf(id, name, parentId, distributionType, probability, percentiles, quantiles, minLoss, maxLoss, terms, seedVarId)
  
  /**
   * Smart constructor - validates all fields and constructs RiskLeaf with Iron types.
   * 
   * @param id Plain string identifier (will be refined to SafeId)
   * @param name Plain string name (will be refined to SafeName)
   * @param distributionType Plain string ("expert" or "lognormal", will be refined)
   * @param probability Plain double [0.0, 1.0] (will be refined to Probability)
   * @param percentiles Optional array of percentiles (expert mode)
   * @param quantiles Optional array of loss quantiles (expert mode)
   * @param minLoss Optional min loss (lognormal mode, will be refined to PositiveLong)
   * @param maxLoss Optional max loss (lognormal mode, will be refined to PositiveLong)
   * @param seedVarId Stochastic identity (will be refined to SeedVarId)
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
    subject: ErrorSubject = ErrorSubject.Request,
    terms: Option[Int] = None,
    seedVarId: Long
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskLeaf] = {
    
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Step 1: Validate all individual fields in parallel
    val idV = toValidation(ValidationUtil.refineId(id, ErrorLocation.Field(subject, FieldName.Id)))
    val nameV = toValidation(ValidationUtil.refineName(name, ErrorLocation.Field(subject, FieldName.Name)))
    val probV = toValidation(ValidationUtil.refineOccurrenceProbability(probability, ErrorLocation.Field(subject, FieldName.Probability)))
    val distTypeV = toValidation(ValidationUtil.refineDistributionType(distributionType, ErrorLocation.Field(subject, FieldName.DistributionType)))
    val seedVarIdV = toValidation(ValidationUtil.refineSeedVarId(seedVarId, ErrorLocation.Field(subject, FieldName.SeedVarId)))
    val termsV: Validation[ValidationError, Option[PositiveInt]] = terms match {
      case Some(v) => toValidation(ValidationUtil.refinePositiveInt(v, ErrorLocation.Field(subject, FieldName.Terms))).map(Some(_))
      case None    => Validation.succeed(None)
    }
    
    // Step 2: Apply cross-field business rules based on distribution type
    // Use flatMap for dependent validation (requires distTypeV to succeed first)
    val crossFieldV = distTypeV.flatMap { dt =>
      dt.toString match {
        case "expert" =>
          validateExpertMode(percentiles, quantiles, subject).flatMap { result =>
            // The dependent terms-within-percentiles rule applies only when `terms`
            // itself refined successfully; when it did not, its error is already
            // carried by the outer validateWith, so skip it here (no double-report).
            termsV match {
              case Validation.Success(_, t) =>
                requireTermsWithinPercentiles(t, percentiles, subject).map(_ => result)
              case _ => Validation.succeed(result)
            }
          }
        case "lognormal" => validateLognormalMode(minLoss, maxLoss, subject)
        case unknown => failOnUnknownDistributionType(unknown, subject)
      }
    }
    
    // Step 3: Combine all validations (parallel accumulation where possible)
    Validation.validateWith(idV, nameV, probV, distTypeV, seedVarIdV, termsV, crossFieldV) {
      case (validId, validName, validProb, validDistType, validSeedVarId, validTerms, (validMinLoss, validMaxLoss)) =>
        new RiskLeaf(
          safeId = validId,
          safeName = validName,
          parentId = parentId,
          distributionType = validDistType,
          probability = validProb,
          percentiles = percentiles,
          quantiles = quantiles,
          minLoss = validMinLoss,
          maxLoss = validMaxLoss,
          terms = validTerms,
          seedVarId = validSeedVarId
        )
    }
  }
  
  /** Validate expert mode: requires non-empty percentiles and quantiles of equal length */
  private def validateExpertMode(
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    subject: ErrorSubject
  ): Validation[com.risquanter.register.domain.errors.ValidationError, (Option[PositiveLong], Option[PositiveLong])] = {
    
    (percentiles, quantiles) match {
      case (Some(p), Some(q)) if p.nonEmpty && q.nonEmpty =>
        (p.length, q.length) match {
          case (pl, ql) if pl != ql =>
            Validation.fail(ValidationError(
              location = ErrorLocation.Field(subject, FieldName.DistributionType),
              code = ValidationErrorCode.INVALID_COMBINATION,
              message = s"Expert mode: percentiles and quantiles must have same length (got $pl vs $ql)"
            ))
          case (pl, _) if pl < MinPercentiles =>
            Validation.fail(ValidationError(
              location = ErrorLocation.Field(subject, FieldName.Percentiles),
              code = ValidationErrorCode.CONSTRAINT_VIOLATION,
              message = s"Expert mode: requires at least $MinPercentiles percentile/quantile points (got $pl)"
            ))
          case (pl, _) if pl > MaxPercentiles =>
            Validation.fail(ValidationError(
              location = ErrorLocation.Field(subject, FieldName.Percentiles),
              code = ValidationErrorCode.CONSTRAINT_VIOLATION,
              message = s"Expert mode: at most $MaxPercentiles percentile/quantile points (got $pl)"
            ))
          case _ =>
            Validation.succeed((None, None))
        }
      
      case (None, None) =>
        Validation.fail(ValidationError(
          location = ErrorLocation.Field(subject, FieldName.DistributionType),
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Expert mode requires both percentiles and quantiles"
        ))
      
      case (None, _) =>
        Validation.fail(ValidationError(
          location = ErrorLocation.Field(subject, FieldName.Percentiles),
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Expert mode requires percentiles"
        ))
      
      case (_, None) =>
        Validation.fail(ValidationError(
          location = ErrorLocation.Field(subject, FieldName.Quantiles),
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "Expert mode requires quantiles"
        ))
      
      case _ =>
        Validation.fail(ValidationError(
          location = ErrorLocation.Field(subject, FieldName.DistributionType),
          code = ValidationErrorCode.INVALID_COMBINATION,
          message = "Expert mode: percentiles and quantiles cannot be empty"
        ))
    }
  }
  
  /** Validate lognormal mode: requires minLoss < maxLoss */
  private def validateLognormalMode(
    minLoss: Option[Long],
    maxLoss: Option[Long],
    subject: ErrorSubject
  ): Validation[com.risquanter.register.domain.errors.ValidationError, (Option[PositiveLong], Option[PositiveLong])] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    (minLoss, maxLoss) match {
      case (Some(min), Some(max)) =>
        val minV = toValidation(ValidationUtil.refinePositiveLong(min, ErrorLocation.Field(subject, FieldName.MinLoss)))
        val maxV = toValidation(ValidationUtil.refinePositiveLong(max, ErrorLocation.Field(subject, FieldName.MaxLoss)))

        // Validate both, then check cross-field constraint
        Validation.validateWith(minV, maxV) { (validMin, validMax) =>
          requireMinBelowMax(validMin, validMax, subject)
            .map(_ => (Some(validMin), Some(validMax)))
        }.flatten

      case (None, None) => lognormalBothBoundsMissing(subject)
      case (None, _)    => lognormalMinMissing(subject)
      case (_, None)    => lognormalMaxMissing(subject)
    }
  }

  // ── Mode-rule building blocks — single definition per rule, shared between
  //    RiskLeaf.create's raw-input path and validateModeFields' refined-input
  //    path (OverrideDistributionParams) ──

  private def requireMinBelowMax(
    min: PositiveLong,
    max: PositiveLong,
    subject: ErrorSubject
  ): Validation[com.risquanter.register.domain.errors.ValidationError, Unit] = {
    Validation
      .fromPredicateWith[ValidationError, (PositiveLong, PositiveLong)](
        ValidationError(
          location = ErrorLocation.Field(subject, FieldName.MinLoss),
          code = ValidationErrorCode.INVALID_RANGE,
          message = s"minLoss ($min) must be less than maxLoss ($max)"
        )
      )((min, max)) { case (minVal, maxVal) => minVal < maxVal }
      .map(_ => ())
  }

  private def requireTermsWithinPercentiles(
    terms: Option[PositiveInt],
    percentiles: Option[Array[Double]],
    subject: ErrorSubject
  ): Validation[com.risquanter.register.domain.errors.ValidationError, Unit] = {
    terms match {
      case Some(t) if percentiles.exists(pct => t.toInt > pct.length) =>
        Validation.fail(ValidationError(
          location = ErrorLocation.Field(subject, FieldName.Terms),
          code = ValidationErrorCode.INVALID_COMBINATION,
          message = ValidationMessages.termsOutOfRange
        ))
      case _ => Validation.succeed(())
    }
  }

  private def lognormalBothBoundsMissing[A](subject: ErrorSubject): Validation[com.risquanter.register.domain.errors.ValidationError, A] = {
    Validation.fail(ValidationError(
      location = ErrorLocation.Field(subject, FieldName.DistributionType),
      code = ValidationErrorCode.REQUIRED_FIELD,
      message = "Lognormal mode requires both minLoss and maxLoss"
    ))
  }

  private def lognormalMinMissing[A](subject: ErrorSubject): Validation[com.risquanter.register.domain.errors.ValidationError, A] = {
    Validation.fail(ValidationError(
      location = ErrorLocation.Field(subject, FieldName.MinLoss),
      code = ValidationErrorCode.REQUIRED_FIELD,
      message = "Lognormal mode requires minLoss"
    ))
  }

  private def lognormalMaxMissing[A](subject: ErrorSubject): Validation[com.risquanter.register.domain.errors.ValidationError, A] = {
    Validation.fail(ValidationError(
      location = ErrorLocation.Field(subject, FieldName.MaxLoss),
      code = ValidationErrorCode.REQUIRED_FIELD,
      message = "Lognormal mode requires maxLoss"
    ))
  }

  /** The complete mode-fields rule on already-refined inputs — the single
    * entry point for callers outside the leaf's own raw-input constructor
    * (`OverrideDistributionParams.create`): expert ⇒ percentiles+quantiles
    * present, non-empty, equal length, terms within percentile count;
    * lognormal ⇒ both bounds present and minLoss < maxLoss.
    */
  private[data] def validateModeFields(
    distributionType: DistributionType,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[PositiveLong],
    maxLoss: Option[PositiveLong],
    terms: Option[PositiveInt],
    subject: ErrorSubject
  ): Validation[com.risquanter.register.domain.errors.ValidationError, Unit] =
    distributionType.toString match {
      case "expert" =>
        validateExpertMode(percentiles, quantiles, subject)
          .flatMap(_ => requireTermsWithinPercentiles(terms, percentiles, subject))
      case "lognormal" =>
        (minLoss, maxLoss) match {
          case (Some(min), Some(max)) => requireMinBelowMax(min, max, subject)
          case (None, None)           => lognormalBothBoundsMissing(subject)
          case (None, _)              => lognormalMinMissing(subject)
          case (_, None)              => lognormalMaxMissing(subject)
        }
      case unknown =>
        failOnUnknownDistributionType(unknown, subject).map(_ => ())
    }

  /** Defense in depth: Fail validation for unknown distribution types that bypass Iron constraint */
  private def failOnUnknownDistributionType(
    unknown: String,
    subject: ErrorSubject
  ): Validation[com.risquanter.register.domain.errors.ValidationError, (Option[PositiveLong], Option[PositiveLong])] = {
    
    Validation.fail(ValidationError(
      location = ErrorLocation.Field(subject, FieldName.DistributionType),
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
    maxLoss: Option[Long],
    terms: Option[Int],
    seedVarId: Long
  )
  private object RiskLeafRaw {
    given rawCodec: JsonCodec[RiskLeafRaw] = DeriveJsonCodec.gen[RiskLeafRaw]
  }
  
  /** Custom decoder that uses smart constructor for cross-field validation */
  given decoder: JsonDecoder[RiskLeaf] = RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
    // Convert parentId string to NodeId if present
    val parentIdOpt: Either[String, Option[NodeId]] = raw.parentId match {
      case None => Right(None)
      case Some(pid) => SafeId.fromString(pid).map(sid => Some(NodeId(sid))).left.map(_.mkString(", "))
    }
    parentIdOpt.flatMap { validParentId =>
      create(
        raw.id, raw.name, raw.distributionType, raw.probability,
        raw.percentiles, raw.quantiles, raw.minLoss, raw.maxLoss,
        parentId = validParentId,
        subject = ErrorSubject.Request,
        terms = raw.terms,
        seedVarId = raw.seedVarId
      ).toEither.left.map(errors => errors.toChunk.map(e => s"[${ErrorLocation.describe(e.location)}] ${e.message}").mkString("; "))
    }
  }
  
  /** Encoder: Extract primitives from Iron types for wire format */
  given encoder: JsonEncoder[RiskLeaf] = JsonEncoder[RiskLeafRaw].contramap { leaf =>
    RiskLeafRaw(
      id = leaf.id.value,
      name = leaf.name.value,
      parentId = leaf.parentId.map(_.value),
      distributionType = leaf.distributionType.toString,
      probability = leaf.probability,
      percentiles = leaf.percentiles,
      quantiles = leaf.quantiles,
      minLoss = leaf.minLoss.map(identity),
      maxLoss = leaf.maxLoss.map(identity),
      terms = leaf.terms.map(_.toInt),
      seedVarId = leaf.seedVarId.value
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
  // See RiskLeaf: @jsonField is read by the zio-json codec, @encodedName by the
  // Tapir schema. Both are needed for the two to agree on the wire name.
  @jsonField("id") @encodedName("id") safeId: SafeId.SafeId,
  @jsonField("name") @encodedName("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  childIds: Array[NodeId]
) extends RiskNode {
  // Defense in depth: invariant check as safety net
  require(childIds != null && childIds.nonEmpty, "RiskPortfolio invariant violated: childIds must be non-empty")
  
  // Public API: Extract values from Iron types
  override def id: NodeId = NodeId(safeId)
  override def name: SafeName.SafeName = safeName
}

object RiskPortfolio {
  import zio.prelude.Validation
  import com.risquanter.register.domain.data.iron._

  /** Upper bound on a portfolio's direct child count — a resource limit
    * against oversized fan-out. */
  private val MaxChildren = 1000

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
    subject: ErrorSubject = ErrorSubject.Request
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskPortfolio] = {
    
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Step 1: Validate ID (Iron refinement)
    val idValidation: Validation[ValidationError, SafeId.SafeId] = 
      toValidation(ValidationUtil.refineId(id, ErrorLocation.Field(subject, FieldName.Id)))
    
    // Step 2: Validate name (Iron refinement)
    val nameValidation: Validation[ValidationError, SafeName.SafeName] =
      toValidation(ValidationUtil.refineName(name, ErrorLocation.Field(subject, FieldName.Name)))
    
    // Step 3: Validate childIds array (business rule): non-empty AND count-bounded, accumulated
    val arr = Option(childIds).getOrElse(Array.empty[NodeId])
    val nonEmptyV = Validation.fromPredicateWith[ValidationError, Array[NodeId]](
      ValidationError(
        location = ErrorLocation.Field(subject, FieldName.ChildIds),
        code = ValidationErrorCode.REQUIRED_FIELD,
        message = "childIds array must not be empty"
      )
    )(arr)(_.nonEmpty)
    val countV = Validation.fromPredicateWith[ValidationError, Array[NodeId]](
      ValidationError(
        location = ErrorLocation.Field(subject, FieldName.ChildIds),
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"too many children: ${arr.length} exceeds the limit of $MaxChildren"
      )
    )(arr)(_.length <= MaxChildren)
    val childIdsValidation: Validation[ValidationError, Array[NodeId]] =
      Validation.validateWith(nonEmptyV, countV)((_, _) => arr)
    
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
    subject: ErrorSubject = ErrorSubject.Request
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskPortfolio] = {
    
    // Convert string childIds to NodeIds
    val childIdResults = childIds.zipWithIndex.map { case (cid, idx) =>
      SafeId.fromString(cid).left.map { errors =>
        ValidationError(
          location = ErrorLocation.Field(subject, FieldName.childAt(idx)),
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
      val validChildIds = childIdResults.collect { case Right(id) => NodeId(id) }
      create(id, name, validChildIds, parentId, subject)
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
      case Some(pid) => SafeId.fromString(pid).map(sid => Some(NodeId(sid))).left.map(_.mkString(", "))
    }
    parentIdOpt.flatMap { validParentId =>
      createFromStrings(raw.id, raw.name, raw.childIds, parentId = validParentId, subject = ErrorSubject.Request)
        .toEither.left.map(errors => errors.toChunk.map(e => s"[${ErrorLocation.describe(e.location)}] ${e.message}").mkString("; "))
    }
  }
  
  /** Encoder: Extract primitives from Iron types for wire format */
  given encoder: JsonEncoder[RiskPortfolio] = JsonEncoder[RiskPortfolioRaw].contramap { portfolio =>
    RiskPortfolioRaw(
      id = portfolio.id.value,
      name = portfolio.name.value,
      parentId = portfolio.parentId.map(_.value),
      childIds = portfolio.childIds.map(_.value)
    )
  }
  
  given codec: JsonCodec[RiskPortfolio] = JsonCodec(encoder, decoder)
  
  /** Production constructor: accepts already-validated Iron types, bypasses re-validation.
    * Use when domain types are already refined (e.g., from Distribution in buildNodes).
    */
  def fromValidated(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    childIds: Array[NodeId],
    parentId: Option[NodeId]
  ): RiskPortfolio =
    new RiskPortfolio(safeId = id, safeName = name, parentId = parentId, childIds = childIds)
}
