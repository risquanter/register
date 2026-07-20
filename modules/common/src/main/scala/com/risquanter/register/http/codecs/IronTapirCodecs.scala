package com.risquanter.register.http.codecs

import sttp.tapir.*
import com.risquanter.register.domain.data.iron.{PositiveInt, NonNegativeInt, NonNegativeLong, SafeId, SafeName, DistributionType, Probability, OccurrenceProbability, TreeId, NodeId, WorkspaceKeySecret, UserId, ValidationUtil, SeedEntityId, SeedVarId, BranchRef, ScenarioName, CommitHash}

/**
 * Tapir codecs for Iron refined types.
 * 
 * Enables using Iron types directly in Tapir endpoint definitions:
 * ```scala
 * .in(query[Option[PositiveInt]]("nTrials"))
 * .in(query[NonNegativeInt]("depth"))
 * .in(path[NonNegativeLong]("id"))
 * .in(path[SafeId.SafeId]("nodeId"))
 * ```
 * 
 * Validation happens at the HTTP layer (codec decode), following the
 * "Parse, don't validate" principle. Invalid input returns 400 Bad Request
 * before reaching controllers or services.
 * 
 * @see ADR-001 for validation strategy details
 */
object IronTapirCodecs {

  /** Codec for PositiveInt (Int > 0). */
  given Codec[String, PositiveInt, CodecFormat.TextPlain] =
    Codec.int.mapDecode[PositiveInt](raw =>
      ValidationUtil.refinePositiveInt(raw, "value").fold(
        errs => DecodeResult.Error(raw.toString, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(identity)

  /** Codec for NonNegativeInt (Int >= 0). */
  given Codec[String, NonNegativeInt, CodecFormat.TextPlain] =
    Codec.int.mapDecode[NonNegativeInt](raw =>
      ValidationUtil.refineNonNegativeInt(raw, "value").fold(
        errs => DecodeResult.Error(raw.toString, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(identity)

  /** Codec for NonNegativeLong (Long >= 0).
    * Used for internally-generated domain identifiers (e.g., RiskTree IDs).
    * 
    * This codec validates at the HTTP boundary that external clients provide
    * valid ID references. The validated type flows through the entire stack
    * (controller → service → repository), maintaining type safety.
    */
  given Codec[String, NonNegativeLong, CodecFormat.TextPlain] =
    Codec.long.mapDecode[NonNegativeLong](raw =>
      ValidationUtil.refineNonNegativeLong(raw, "id").fold(
        errs => DecodeResult.Error(raw.toString, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(identity)

  /** Codec for SafeId (3-30 alphanumeric chars + hyphen/underscore).
    * Used for node identifiers in risk trees (e.g., "cyber-attack", "ops_risk_001").
    * 
    * Validates:
    * - Not blank
    * - Length: 3-30 characters
    * - Pattern: ^[a-zA-Z0-9_-]+$
    */
  given Codec[String, SafeId.SafeId, CodecFormat.TextPlain] =
    Codec.string.mapDecode[SafeId.SafeId](raw =>
      SafeId.fromString(raw).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.value.toString)

  /** Schema for SafeId.SafeId for JSON body parameters.
    * 
    * Tapir's jsonBody[T] requires both JsonCodec (for serialization) and Schema 
    * (for OpenAPI documentation). Path/query params derive Schema from Codec,
    * but JSON bodies need explicit Schema for Iron types.
    * 
    * @see ADR-001 Section "JSON Bodies with Iron Types"
    */
  given Schema[SafeId.SafeId] = Schema.string

  /** Codec for TreeId (case class wrapping SafeId.SafeId).
    * Used for tree-level identifiers in risk tree endpoints.
    * Delegates validation to SafeId.fromString, then wraps in TreeId.
    * 
    * @see ADR-018 for nominal wrapper pattern
    */
  given Codec[String, TreeId, CodecFormat.TextPlain] =
    Codec.string.mapDecode[TreeId](raw =>
      TreeId.fromString(raw).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.value)

  given Schema[TreeId] = Schema.string

  /** Codec for NodeId (case class wrapping SafeId.SafeId).
    * Used for node-level identifiers in risk tree endpoints.
    * Delegates validation to SafeId.fromString, then wraps in NodeId.
    * 
    * @see ADR-018 for nominal wrapper pattern
    */
  given Codec[String, NodeId, CodecFormat.TextPlain] =
    Codec.string.mapDecode[NodeId](raw =>
      NodeId.fromString(raw).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.value)

  given Schema[NodeId] = Schema.string

  given [V: Schema]: Schema[Map[NodeId, V]] = Schema.schemaForMap[NodeId, V](_.value)

  /** Codec for WorkspaceKeySecret (base64url, 22 chars).
    * Used as path segment for workspace-scoped capability URLs.
    * Standalone validation — not a ULID wrapper.
    */
  given Codec[String, WorkspaceKeySecret, CodecFormat.TextPlain] =
    Codec.string.mapDecode[WorkspaceKeySecret](raw =>
      WorkspaceKeySecret.fromString(raw).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.reveal)

  given Schema[WorkspaceKeySecret] = Schema.string

  /** Codec for UserId (UUID string wrapped in a final class with PII redaction).
    * Used for the x-user-id claim header injected by the service mesh (Istio).
    * Validates UUID format at the HTTP boundary via UserId.fromString.
    *
    * The header is decoded to Option[UserId] in authedBaseEndpoint — absent header
    * decodes to None (not an error), allowing capability-only mode to work without
    * the header present.
    *
    * @see ADR-012: Claim Header Injection
    * @see ADR-022: PII handling (toString redacted in UserId)
    */
  given Codec[String, UserId.Authenticated, CodecFormat.TextPlain] =
    Codec.string.mapDecode[UserId.Authenticated](raw =>
      UserId.fromString(raw).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.value)

  given Schema[UserId.Authenticated] = Schema.string

  /** Schema for ScenarioName for JSON body derivation and path-segment use
    * (e.g. DELETE /w/{key}/scenarios/{name}).
    */
  given Schema[ScenarioName.ScenarioName] = Schema.string.map[ScenarioName.ScenarioName](
    (s: String) => ScenarioName.fromString(s).toOption
  )(_.value)

  /** Codec for ScenarioName as a path segment. */
  given Codec[String, ScenarioName.ScenarioName, CodecFormat.TextPlain] =
    Codec.string.mapDecode[ScenarioName.ScenarioName](raw =>
      ScenarioName.fromString(raw).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.value)

  /** Schema for BranchRef for JSON body derivation (e.g. scenario create response). */
  given Schema[BranchRef] = Schema.string.map[BranchRef](
    (s: String) => BranchRef.fromString(s).toOption
  )(_.toBranchRef)

  /** Schema for CommitHash for JSON body derivation (e.g. scenario list response). */
  given Schema[CommitHash] = Schema.string.map[CommitHash](
    (s: String) => CommitHash.fromString(s).toOption
  )(_.value)

  /** Codec for CommitHash as the `If-Match` header value on `deleteScenario`
    * (DD-5 CAS precondition, transport locked 2026-07-20 — see
    * milestone-2b-cache-and-decisions.md DD-8 note). RFC 7232 renders
    * `If-Match` as a quoted string (`"<hash>"`); this codec strips the
    * quotes on decode and re-adds them on encode, so the same definition
    * works both server-side (decode) and in the generated sttp client
    * (encode).
    */
  given Codec[String, CommitHash, CodecFormat.TextPlain] =
    Codec.string.mapDecode[CommitHash](raw =>
      val unquoted = raw.strip.stripPrefix("\"").stripSuffix("\"")
      CommitHash.fromString(unquoted).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(hash => s"\"${hash.value}\"")

  /** Codec for BranchRef as the `X-Active-Branch` header value (milestone-2b
    * Phase B item 4b). Plain header, not a structured-syntax one like
    * `If-Match` (RFC 7232) — no quoting on either side.
    */
  given Codec[String, BranchRef, CodecFormat.TextPlain] =
    Codec.string.mapDecode[BranchRef](raw =>
      BranchRef.fromString(raw.strip).fold(
        errs => DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.toBranchRef)

  /** Schema for SafeName.SafeName for JSON body derivation. */
  given Schema[SafeName.SafeName] = Schema.string.map[SafeName.SafeName](
    (s: String) => SafeName.fromString(s).toOption
  )(_.value)

  /** Schema for DistributionType for JSON body derivation. */
  given Schema[DistributionType] = Schema.string.map[DistributionType](
    (s: String) => ValidationUtil.refineDistributionType(s).toOption
  )(identity)

  /** Schema for Probability for JSON body derivation. */
  given Schema[Probability] = Schema.schemaForDouble.map[Probability](
    d => ValidationUtil.refineProbability(d).toOption
  )(identity)

  /** Schema for OccurrenceProbability for JSON body derivation. */
  given Schema[OccurrenceProbability] = Schema.schemaForDouble.map[OccurrenceProbability](
    d => ValidationUtil.refineOccurrenceProbability(d).toOption
  )(identity)

  /** Schema for NonNegativeLong for JSON body derivation. */
  given Schema[NonNegativeLong] = Schema.schemaForLong.map[NonNegativeLong](
    l => ValidationUtil.refineNonNegativeLong(l).toOption
  )(identity)

  /** Schema for PositiveInt for JSON body derivation. */
  given Schema[PositiveInt] = Schema.schemaForInt.map[PositiveInt](
    i => ValidationUtil.refinePositiveInt(i).toOption
  )(identity)

  /** Schema for NonNegativeInt for JSON body derivation. */
  given Schema[NonNegativeInt] = Schema.schemaForInt.map[NonNegativeInt](
    i => ValidationUtil.refineNonNegativeInt(i).toOption
  )(identity)

  /** Schema for SeedVarId for JSON body derivation. */
  given Schema[SeedVarId.SeedVarId] = Schema.schemaForLong.map[SeedVarId.SeedVarId](
    l => SeedVarId.fromLong(l).toOption
  )(_.value)

  /** Codec for SeedEntityId (Long in [1, 100000000)) — the workspace's HDR
    * Entity-axis seed. Used as an optional query parameter on workspace
    * bootstrap so exports can be re-imported with the same entity
    * (PLAN-SEED-IDENTITY §7, §8 round-trip).
    */
  given Codec[String, SeedEntityId.SeedEntityId, CodecFormat.TextPlain] =
    Codec.long.mapDecode[SeedEntityId.SeedEntityId](raw =>
      SeedEntityId.fromLong(raw).fold(
        errs => DecodeResult.Error(raw.toString, new IllegalArgumentException(errs.map(_.message).mkString("; "))),
        DecodeResult.Value(_)
      )
    )(_.value)

  /** Schema for SeedEntityId for JSON body derivation. */
  given Schema[SeedEntityId.SeedEntityId] = Schema.schemaForLong.map[SeedEntityId.SeedEntityId](
    l => SeedEntityId.fromLong(l).toOption
  )(_.value)
}
