package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonDecoder, JsonEncoder, jsonField}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, OccurrenceProbability, NodeId, SeedVarId}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode}

/** Recursive ADT representing a risk hierarchy tree.
  *
  * Tree Structure:
  * - RiskLeaf: Terminal node with distribution parameters (actual risk)
  * - RiskPortfolio: Branch node containing children (aggregation of risks)
  *
  * A portfolio names its children by identifier rather than embedding them, and
  * the identifiers are held in an immutable sequence. So a tree is a flat set of
  * nodes plus the parent and child references relating them, and both node kinds
  * are built through their own smart constructor:
  *
  * {{{
  * RiskPortfolio.create(
  *   id = "ops-risk", name = "Operational Risk",
  *   childIds = Vector(cyberId, itRiskId), parentId = None
  * )
  * RiskLeaf.create(
  *   id = "cyber", name = "Cyber Attack", probability = 0.25,
  *   shape = lognormalShape, parentId = Some(opsRiskId), seedVarId = 1L
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
  * Domain Model: Uses Iron refined types for type safety
  * - safeId: SafeId (26-character ULID)
  * - safeName: SafeName (non-blank, max 50 chars)
  * - probability: OccurrenceProbability (0.0 <= p <= 1.0)
  *
  * @param safeId Unique identifier (Iron refined type)
  * @param safeName Human-readable risk name (Iron refined type)
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param shape The loss distribution the author declared, in one of its two
  *              modes. It carries its own construction proof, so the leaf
  *              states no rule about which fields a mode populates.
  * @param seedVarId Stochastic identity: selects the leaf's HDR random streams
  *                  (occurrence = 2k, loss = 2k+1). Assigned at the creation
  *                  boundary, immutable, unique per tree. Independent of the
  *                  app identity (SafeId).
  */
final case class RiskLeaf private (
  // Both annotations are required and name the same wire field: @jsonField
  // renames it for the zio-json codec, @encodedName for the Tapir schema that
  // generates the OpenAPI document. They are read by different derivations.
  @jsonField("id") @encodedName("id") safeId: SafeId.SafeId,
  @jsonField("name") @encodedName("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  probability: OccurrenceProbability,
  shape: DistributionShape,
  seedVarId: SeedVarId.SeedVarId
) extends RiskNode {
  // Public API: Extract values from Iron types
  override def id: NodeId = NodeId(safeId)
  override def name: SafeName.SafeName = safeName
}

object RiskLeaf {
  import zio.prelude.Validation
  import com.risquanter.register.domain.data.iron.ValidationUtil

  /** Production constructor: accepts already-validated Iron types.
    *
    * The shape carries its own proof, so there is nothing left for this to
    * drop — every rule it could re-check is held behind the shape's own
    * construction gate.
    */
  def fromValidated(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    probability: OccurrenceProbability,
    shape: DistributionShape,
    parentId: Option[NodeId],
    seedVarId: SeedVarId.SeedVarId
  ): RiskLeaf =
    new RiskLeaf(id, name, parentId, probability, shape, seedVarId)

  /**
   * Smart constructor: refines the identifier, the name, the probability and the
   * seed identity, accumulating every refusal.
   *
   * The shape is not re-validated: it cannot exist unless its own gate passed,
   * which is the validate-once-at-the-boundary rule of ADR-001 §2.
   *
   * @param id Plain string identifier (will be refined to SafeId)
   * @param name Plain string name (will be refined to SafeName)
   * @param probability Plain double [0.0, 1.0] (will be refined to OccurrenceProbability)
   * @param shape The already-gated loss distribution
   * @param seedVarId Stochastic identity (will be refined to SeedVarId)
   * @return Validation with all errors accumulated, or a valid RiskLeaf
   */
  def create(
    id: String,
    name: String,
    probability: Double,
    shape: DistributionShape,
    parentId: Option[NodeId],
    seedVarId: Long,
    subject: ErrorSubject = ErrorSubject.Request
  ): Validation[ValidationError, RiskLeaf] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation

    val idV        = toValidation(ValidationUtil.refineId(id, ErrorLocation.Field(subject, FieldName.Id)))
    val nameV      = toValidation(ValidationUtil.refineName(name, ErrorLocation.Field(subject, FieldName.Name)))
    val probV      = toValidation(ValidationUtil.refineOccurrenceProbability(probability, ErrorLocation.Field(subject, FieldName.Probability)))
    val seedVarIdV = toValidation(ValidationUtil.refineSeedVarId(seedVarId, ErrorLocation.Field(subject, FieldName.SeedVarId)))

    Validation.validateWith(idV, nameV, probV, seedVarIdV) {
      (validId, validName, validProb, validSeedVarId) =>
        new RiskLeaf(
          safeId = validId,
          safeName = validName,
          parentId = parentId,
          probability = validProb,
          shape = shape,
          seedVarId = validSeedVarId
        )
    }
  }

  // --- Custom JSON Codec that enforces cross-field validation via smart constructor ---

  /** Raw intermediate type for JSON wire format (primitives only).
    *
    * Per ADR-001: Wire format uses primitives, domain uses Iron types.
    * - Decoder: RiskLeafRaw (primitives) → create() → RiskLeaf (Iron types)
    * - Encoder: RiskLeaf (Iron types) → RiskLeafRaw (primitives) → JSON
    *
    * The shape is one field here as it is in the domain, carried by
    * `DistributionShape`'s own codec. A record naming one mode while carrying
    * the other mode's fields therefore has no spelling at all, which is what
    * removes the refusal a flat field set would need.
    */
  private case class RiskLeafRaw(
    id: String,
    name: String,
    parentId: Option[String],
    probability: Double,
    shape: DistributionShape,
    seedVarId: Long
  )
  private object RiskLeafRaw {
    given rawCodec: JsonCodec[RiskLeafRaw] = DeriveJsonCodec.gen[RiskLeafRaw]
  }

  /** Custom decoder that uses smart constructor for cross-field validation.
    *
    * The shape arrives already gated: `DistributionShape`'s decoder routes
    * through its own smart constructors, so this refines only the four
    * remaining primitives.
    */
  given decoder: JsonDecoder[RiskLeaf] = RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
    // Convert parentId string to NodeId if present
    val parentIdOpt: Either[String, Option[NodeId]] = raw.parentId match {
      case None => Right(None)
      case Some(pid) => SafeId.fromString(pid).map(sid => Some(NodeId(sid))).left.map(_.mkString(", "))
    }
    parentIdOpt.flatMap { validParentId =>
      create(
        id = raw.id,
        name = raw.name,
        probability = raw.probability,
        shape = raw.shape,
        parentId = validParentId,
        seedVarId = raw.seedVarId,
        subject = ErrorSubject.Request
      )
        .toEither.left.map(errors => errors.toChunk.map(e => s"[${ErrorLocation.describe(e.location)}] ${e.message}").mkString("; "))
    }
  }

  /** Encoder: Extract primitives from Iron types for wire format */
  given encoder: JsonEncoder[RiskLeaf] = JsonEncoder[RiskLeafRaw].contramap { leaf =>
    RiskLeafRaw(
      id = leaf.id.value,
      name = leaf.name.value,
      parentId = leaf.parentId.map(_.value),
      probability = leaf.probability,
      shape = leaf.shape,
      seedVarId = leaf.seedVarId.value
    )
  }

  given codec: JsonCodec[RiskLeaf] = JsonCodec(encoder, decoder)

  /** The OpenAPI shape is the wire form the codec emits, not the domain field
    * list: the identifier and name are refined types in the domain and strings
    * here. Derived from the raw type for the same reason `RiskTree` derives its
    * schema from `RiskTreeJson`. */
  given schema: Schema[RiskLeaf] = {
    import sttp.tapir.generic.auto.*
    Schema.derived[RiskLeafRaw].as[RiskLeaf]
  }
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
  * - childIds: Vector[NodeId] (references to child nodes, must be non-empty)
  *
  * The children are held in an immutable sequence, so two portfolios with the
  * same children are equal and hash alike. An `Array` field would make the
  * derived `equals` compare by reference and report two identical portfolios as
  * different.
  *
  * @param safeId Unique identifier (Iron refined type)
  * @param safeName Human-readable portfolio name (Iron refined type)
  * @param parentId Optional parent node ID (None for root)
  * @param childIds Child node IDs (references, not embedded objects)
  */
final case class RiskPortfolio private (
  // See RiskLeaf: @jsonField is read by the zio-json codec, @encodedName by the
  // Tapir schema. Both are needed for the two to agree on the wire name.
  @jsonField("id") @encodedName("id") safeId: SafeId.SafeId,
  @jsonField("name") @encodedName("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  childIds: Vector[NodeId]
) extends RiskNode {
  // Public API: Extract values from Iron types
  override def id: NodeId = NodeId(safeId)
  override def name: SafeName.SafeName = safeName
}

object RiskPortfolio {
  import zio.prelude.Validation
  import com.risquanter.register.domain.data.iron.*

  /** Upper bound on a portfolio's direct child count — a resource limit
    * against oversized fan-out. */
  private val MaxChildren = 1000

  /** Smart constructor: Validates all fields and returns Validation.
    *
    * Validation Rules:
    * 1. ID must be valid SafeId (3-30 chars, alphanumeric + hyphen/underscore)
    * 2. Name must be valid SafeName (non-blank, max 50 chars)
    * 3. childIds must be non-empty and within the child-count limit
    *
    * Returns:
    * - Validation.succeed(RiskPortfolio) if all validations pass
    * - Validation.fail(errors) if any validation fails (accumulates all errors)
    */
  def create(
    id: String,
    name: String,
    childIds: Vector[NodeId],
    parentId: Option[NodeId],
    subject: ErrorSubject = ErrorSubject.Request
  ): Validation[com.risquanter.register.domain.errors.ValidationError, RiskPortfolio] = {

    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation

    // Step 1: Validate ID (Iron refinement)
    val idValidation: Validation[ValidationError, SafeId.SafeId] =
      toValidation(ValidationUtil.refineId(id, ErrorLocation.Field(subject, FieldName.Id)))

    // Step 2: Validate name (Iron refinement)
    val nameValidation: Validation[ValidationError, SafeName.SafeName] =
      toValidation(ValidationUtil.refineName(name, ErrorLocation.Field(subject, FieldName.Name)))

    // Step 3: Validate childIds (business rule): non-empty AND count-bounded, accumulated
    val nonEmptyV = Validation.fromPredicateWith[ValidationError, Vector[NodeId]](
      ValidationError(
        location = ErrorLocation.Field(subject, FieldName.ChildIds),
        code = ValidationErrorCode.REQUIRED_FIELD,
        message = ValidationMessages.portfolioHasNoChildren
      )
    )(childIds)(_.nonEmpty)
    val countV = Validation.fromPredicateWith[ValidationError, Vector[NodeId]](
      ValidationError(
        location = ErrorLocation.Field(subject, FieldName.ChildIds),
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = ValidationMessages.tooManyChildren(childIds.length, MaxChildren)
      )
    )(childIds)(_.length <= MaxChildren)
    val childIdsValidation: Validation[ValidationError, Vector[NodeId]] =
      Validation.validateWith(nonEmptyV, countV)((_, _) => childIds)

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
    childIds: Vector[String],
    parentId: Option[NodeId],
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
    childIds: Vector[String]
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
    * Use when domain types are already refined (e.g., from a decoded request).
    */
  def fromValidated(
    id: SafeId.SafeId,
    name: SafeName.SafeName,
    childIds: Vector[NodeId],
    parentId: Option[NodeId]
  ): RiskPortfolio =
    new RiskPortfolio(safeId = id, safeName = name, parentId = parentId, childIds = childIds)
}
