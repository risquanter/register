package com.risquanter.register.http.codecs

import sttp.tapir.*
import com.risquanter.register.domain.data.iron.{PositiveInt, NonNegativeInt, NonNegativeLong, SafeId, TreeId, ValidationUtil}

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

  /** Codec for TreeId (ULID, canonical uppercase). */
  given Codec[String, TreeId.TreeId, CodecFormat.TextPlain] =
    Codec.string.mapDecode[TreeId.TreeId](raw =>
      TreeId.fromString(raw).fold(
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
}
