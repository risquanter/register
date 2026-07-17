package com.risquanter.register.domain.data.iron

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.string.Match
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import zio.{UIO, ZIO}
import zio.json.{JsonEncoder, JsonDecoder, JsonFieldEncoder, JsonFieldDecoder}

// Base refined type alias used for most short strings:
type SafeShortStr = String :| (Not[Blank] & MaxLength[50])

// Extra short strings (e.g., for tags, codes)
type SafeExtraShortStr = String :| (Not[Blank] & MaxLength[20])

// Email with format validation (whitelist regex: local-part, @, domain with TLD)
type ValidEmail = String :| (Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"])

// URL constraints for service/internal calls (http/https with hostname, IPv4, or IPv6, optional port/path)
type UrlConstraint = Not[Blank] & MaxLength[200] & Match["^(?i)https?://(?:\\[[0-9a-fA-F:]+\\]|[^/:#?\\s]+)(?::\\d+)?(?:/[^\\s]*)?$"]

// HTTPS-only URL constraint — narrows UrlConstraint to TLS-only endpoints.
// Prevents silent plaintext downgrade for external services that receive credentials in headers.
// Use for any config-loaded service URL where a credential is transmitted (SpiceDB, Keycloak, etc.).
// See ADR-001 §8.
type SecureUrlConstraint = Not[Blank] & MaxLength[200] &
  Match["^https://(?:\\[[0-9a-fA-F:]+\\]|[^/:#?\\s]+)(?::\\d+)?(?:/[^\\s]*)?$"]

// Printable ASCII constraint — safe for outbound HTTP header values and config-loaded credentials.
// Blocks CRLF injection (\r\n), null bytes (\x00), and all ASCII control characters (\x01-\x1F, \x7F).
// Range: 0x21 (!) through 0x7E (~) — visible US-ASCII only; no space, no non-ASCII bytes.
// Use: any string sent verbatim as an HTTP header field value or as an external service credential.
// See ADR-001 §8 for the full application rule.
type PrintableAscii = Match["^[\\x21-\\x7E]+$"]

// External service token string — config-loaded opaque credential sent verbatim as an HTTP header.
// PrintableAscii eliminates CRLF injection and non-ASCII byte injection at the type level.
// MaxLength[2048] accommodates JWTs (base64url header.payload.signature) and pre-shared keys.
// Compose with a final class credential wrapper (ADR-022 R1–R8) for full credential hygiene.
// Named ExternalTokenStr (not service-specific) so it can be reused for any external API credential.
type ExternalTokenStr = String :| (Not[Blank] & MaxLength[2048] & PrintableAscii)

type BranchRefConstraint =
  Not[Blank] & MaxLength[160] & Match["^(main|scenarios/[a-z0-9][a-z0-9_-]{0,63}/[a-z0-9][a-z0-9_-]{0,63}/[a-z0-9][a-z0-9_-]{0,63})$"]

type BranchRefStr = String :| BranchRefConstraint

// Validated URL string — base alias underlying object Url
type ValidUrl = String :| UrlConstraint

// HTTPS-validated URL string — base alias underlying object SecureUrl
type SecureUrlStr = String :| SecureUrlConstraint

// Non-negative long values (IDs, counts, amounts)
type NonNegativeLong = Long :| GreaterEqual[0L]

// Positive integers (must be > 0)
type PositiveInt = Int :| Greater[0]

// Non-negative integers (>= 0)
type NonNegativeInt = Int :| GreaterEqual[0]

// Non-negative doubles (>= 0.0, e.g. scale factors)
// LessEqual[1.7976931348623157e308] is Double.MaxValue as a literal type
// (Iron's compile-time literal refinement needs a literal, not a reference
// to the `Double.MaxValue` val) and excludes +Infinity: unlike Probability/
// OccurrenceProbability, this type has no upper bound to reject +Infinity as
// a side effect, and downstream arithmetic (e.g. RiskTransform.scaleLosses'
// `.toLong`) silently converts an unchecked Infinity/NaN into
// Long.MaxValue/0L instead of failing. (-Infinity and NaN already fail
// GreaterEqual[0.0], since IEEE 754 comparisons against them are always
// false.) Not[Infinity] is semantically clearer but isn't compile-time
// foldable for literal autoRefine in this Iron version.
type NonNegativeDouble = Double :| (GreaterEqual[0.0] & LessEqual[1.7976931348623157e308])

/**
 * Common constant values for Iron refined types.
 * 
 * Use these instead of `1.refineUnsafe[Greater[0]]` scattered throughout the codebase.
 * Import with: `import com.risquanter.register.domain.data.iron.IronConstants.*`
 * 
 * These are compile-time safe since Iron validates literal values at compile time.
 */
object IronConstants:
  // PositiveInt constants (Int > 0)
  val One: PositiveInt = 1
  val Two: PositiveInt = 2
  val Four: PositiveInt = 4
  val Ten: PositiveInt = 10
  val Hundred: PositiveInt = 100
  val Thousand: PositiveInt = 1000
  val TenThousand: PositiveInt = 10000
  
  // NonNegativeInt constants (Int >= 0)
  val Zero: NonNegativeInt = 0
  val NNOne: NonNegativeInt = 1   // "NN" prefix = NonNegative (avoids clash with One)
  val NNFive: NonNegativeInt = 5
  val NNTen: NonNegativeInt = 10
  
  // NonNegativeLong constants (Long >= 0)
  val ZeroL: NonNegativeLong = 0L
  val OneL: NonNegativeLong = 1L

  // DistributionType constants — compile-time safe (Iron validates literals against the Match constraint)
  val Expert:    DistributionType = "expert"
  val Lognormal: DistributionType = "lognormal"

// Opaque type for Metalog/HDR PRNG counters (zero-cost abstraction)
// Used for the 4-5 Long counter parameters in HDR.generate(counter, entityId, varId, seed3, seed4)
// This signals that these Longs are semantically distinct from regular numeric values—
// they're stream identifiers for deterministic random number generation, not quantities or IDs.
// No validation needed; any Long is valid, but the type prevents accidental mixing.
object PRNGCounter:
  opaque type PRNGCounter = Long
  
  object PRNGCounter:
    /** Create counter from any Long value */
    def apply(value: Long): PRNGCounter = value
    def unapply(counter: PRNGCounter): Option[Long] = Some(counter)
    
  extension (counter: PRNGCounter)
    /** Extract underlying Long value */
    def value: Long = counter

// ============================================================================
// Seed Identity Types (TODO item 12 / PLAN-SEED-IDENTITY.md)
// ============================================================================
// Stochastic identity, deliberately separate from app identity (ULID):
// assigned at the creation boundary, stored on the owning aggregate, immutable
// once assigned. Ranges enforce the HDR paper's 8-decimal-digit ID budget
// (Hubbard WSC 2019) — values at or above 10^8 break the generator's
// modulus-precision guarantees. Deliberately no arithmetic operations:
// stream derivation lives in exactly one place (server-side).

// SeedEntityId: workspace-level HDR Entity axis.
// 0 is the paper's reserved shared default and is excluded from assignment.
type SeedEntityIdLong = Long :| (GreaterEqual[1L] & Less[100000000L])

object SeedEntityId:
  opaque type SeedEntityId = SeedEntityIdLong

  object SeedEntityId:
    def apply(v: SeedEntityIdLong): SeedEntityId = v
    def unapply(id: SeedEntityId): Option[SeedEntityIdLong] = Some(id)

  extension (id: SeedEntityId)
    def value: Long = id

  /** Convenience constructor from plain Long (range-validated). */
  def fromLong(v: Long): Either[List[ValidationError], SeedEntityId] =
    ValidationUtil.refineSeedEntityId(v)

  // JSON codecs (companion object placement ensures implicit scope).
  // Wire format is a JSON number (ADR-001: primitives on the wire).
  given JsonEncoder[SeedEntityId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SeedEntityId] = JsonDecoder[Long].mapOrFail(v =>
    fromLong(v).left.map(_.mkString(", ")))

// SeedVarId: leaf-level HDR Var axis. Streams derive by even/odd doubling
// (occurrence = 2k, loss = 2k+1), so the ID must stay below 5*10^7 to keep
// 2k+1 inside the 10^8 budget.
type SeedVarIdLong = Long :| (GreaterEqual[1L] & Less[50000000L])

object SeedVarId:
  opaque type SeedVarId = SeedVarIdLong

  object SeedVarId:
    def apply(v: SeedVarIdLong): SeedVarId = v
    def unapply(id: SeedVarId): Option[SeedVarIdLong] = Some(id)

  extension (id: SeedVarId)
    def value: Long = id

  /** Convenience constructor from plain Long (range-validated). */
  def fromLong(v: Long): Either[List[ValidationError], SeedVarId] =
    ValidationUtil.refineSeedVarId(v)

  /** The per-tree distinctness rule (PLAN-SEED-IDENTITY §5.1), defined once.
    *
    * Both enforcement layers delegate here — the request boundary
    * (RiskTreeRequests, field "request.seedVarIds", pre-assignment 400) and the
    * RiskTree smart constructor (field "nodes.seedVarId", safety net for
    * programmatic and store-loaded trees). One error per duplicated ID, naming
    * every holder, sorted on both axes for deterministic messages.
    */
  def requireDistinct(
    holders: Seq[(String, SeedVarId)],
    field: String
  ): zio.prelude.Validation[ValidationError, Unit] = {
    import zio.prelude.Validation
    val duplicates = holders
      .groupBy { case (_, id) => id.value }
      .filter { case (_, group) => group.size > 1 }
      .toList
      .sortBy { case (idValue, _) => idValue }
    duplicates match {
      case Nil => Validation.succeed(())
      case head :: tail =>
        def toError(idValue: Long, group: Seq[(String, SeedVarId)]) =
          ValidationError(
            field = field,
            code = ValidationErrorCode.DUPLICATE_VALUE,
            message = ValidationMessages.seedVarIdInUse(idValue, group.map(_._1).sorted)
          )
        Validation.failNonEmptyChunk(
          zio.NonEmptyChunk.fromIterable(toError.tupled(head), tail.map(toError.tupled))
        )
    }
  }

  // JSON codecs (companion object placement ensures implicit scope).
  // Wire format is a JSON number (ADR-001: primitives on the wire).
  given JsonEncoder[SeedVarId] = JsonEncoder[Long].contramap(_.value)
  given JsonDecoder[SeedVarId] = JsonDecoder[Long].mapOrFail(v =>
    fromLong(v).left.map(_.mkString(", ")))

// Probability values (must be between 0.0 and 1.0, exclusive)
// Exclusive bounds are required for numerical stability in simulation-util's
// inverse CDF calculations where 0.0 and 1.0 would cause division by zero or infinity.
// NOTE: Used for Metalog percentiles which require exclusive (0,1) bounds.
type Probability = Double :| (Greater[0.0] & Less[1.0])

// Occurrence probability for a risk leaf event (can be 0 = never, or 1 = always).
// Semantically distinct from Probability: the closed [0,1] interval is correct here
// because a risk event is allowed to never or always occur.
type OccurrenceProbability = Double :| (GreaterEqual[0.0] & LessEqual[1.0])

// Distribution type string (must be "expert" or "lognormal")
type DistributionType = String :| Match["^(expert|lognormal)$"]

// SafeName character whitelist: letters, digits, space, hyphen, forward-slash.
// Excludes HTML/script/FOL-grammar chars. Deliberately narrow — add only when
// a concrete use case requires the character and no injection risk exists.
type SafeNameConstraint = Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9 /\\-]+$"]
type SafeNameStr = String :| SafeNameConstraint

// Opaque type for names - prevents mixing with other string types
object SafeName:
  opaque type SafeName = SafeNameStr
  
  object SafeName:
    def apply(s: SafeNameStr): SafeName = s
    // Extractor for pattern matching:
    def unapply(sn: SafeName): Option[SafeNameStr] = Some(sn)
    
  extension (sn: SafeName) 
    def value: SafeNameStr = sn
  
  // Convenience constructor from plain String
  def fromString(s: String): Either[List[ValidationError], SafeName] = 
    ValidationUtil.refineName(s)

// Opaque type for emails
object Email:
  opaque type Email = ValidEmail
  
  object Email:
    def apply(s: ValidEmail): Email = s
    def unapply(e: Email): Option[ValidEmail] = Some(e)
    
  extension (e: Email)
    def value: ValidEmail = e
  
  // Convenience constructor from plain String
  def fromString(s: String): Either[List[ValidationError], Email] = 
    ValidationUtil.refineEmail(s)

// Opaque type for URLs (service endpoints, config, and domain URL fields)
object Url:
  opaque type Url = ValidUrl

  object Url:
    def apply(s: ValidUrl): Url = s
    def unapply(u: Url): Option[ValidUrl] = Some(u)

  extension (u: Url)
    def value: ValidUrl = u

  given JsonEncoder[Url] = JsonEncoder[String].contramap(_.value.toString)
  given JsonDecoder[Url] = JsonDecoder[String].mapOrFail(s =>
    fromString(s).left.map(_.map(_.message).mkString(", ")))

  // Convenience constructor from plain String
  def fromString(s: String, fieldPath: String = "url"): Either[List[ValidationError], Url] =
    ValidationUtil.refineUrl(s, fieldPath)

// SecureUrl: HTTPS-only service endpoint URL (TLS enforced at the type level).
// Use for any config-loaded external service URL where credentials are transmitted.
// Companion to object Url — identical API, narrower constraint.
object SecureUrl:
  opaque type SecureUrl = SecureUrlStr

  object SecureUrl:
    def apply(s: SecureUrlStr): SecureUrl = s
    def unapply(u: SecureUrl): Option[SecureUrlStr] = Some(u)

  extension (u: SecureUrl)
    def value: SecureUrlStr = u

  given JsonEncoder[SecureUrl] = JsonEncoder[String].contramap(_.value.toString)
  given JsonDecoder[SecureUrl] = JsonDecoder[String].mapOrFail(s =>
    fromString(s).left.map(_.map(_.message).mkString(", ")))

  def fromString(s: String, fieldPath: String = "url"): Either[List[ValidationError], SecureUrl] =
    ValidationUtil.refineSecureUrl(s, fieldPath)

// MeshServiceUrl: URL for service-to-service calls within a service mesh (Istio ambient mode or sidecar mTLS).
//
// Accepts both http:// and https:// — transport security is the mesh's responsibility.
// The Istio control plane provides mutual TLS between all mesh-enrolled pods transparently;
// adding application-layer TLS on top would be redundant and would complicate cert rotation
// (Istio rotates workload certs approximately every 24 hours by default).
//
// Use this type for any internal service URL where:
//   (1) the target service is deployed inside the same mesh (Istio ambient mode / sidecar)
//   (2) Istio PeerAuthentication STRICT is active on the target port
//
// Do NOT use for:
//   - Public or internet-facing endpoints (use SecureUrl)
//   - Services outside the mesh that transmit credentials over the wire
//
// Companion to object SecureUrl — identical API surface, accepts http://.
// See ADR-012 (service mesh), ADR-022 (credential hygiene).
object MeshServiceUrl:
  opaque type MeshServiceUrl = ValidUrl

  object MeshServiceUrl:
    def apply(s: ValidUrl): MeshServiceUrl = s
    def unapply(u: MeshServiceUrl): Option[ValidUrl] = Some(u)

  extension (u: MeshServiceUrl)
    def value: ValidUrl = u

  def fromString(s: String, fieldPath: String = "url"): Either[List[ValidationError], MeshServiceUrl] =
    Url.fromString(s, fieldPath).map(_.value)

case class BranchRef(toBranchRef: BranchRefStr)

object BranchRef:
  val Main: BranchRef = BranchRef("main")

  def fromString(s: String, fieldPath: String = "branch"): Either[List[ValidationError], BranchRef] =
    val sanitized = if s == null then "" else s.trim
    sanitized
      .refineEither[BranchRefConstraint]
      .left
      .map(err =>
        List(
          ValidationError(
            field = fieldPath,
            code = ValidationErrorCode.INVALID_FORMAT,
            message = s"Branch '$sanitized' is invalid: $err"
          )
        )
      )
      .map(BranchRef(_))

// SafeId: Canonical ULID (Crockford base32, 26 chars, uppercase)
// Accepts input case-insensitively, normalizes to uppercase canonical string.
type SafeIdStr = String :| Match["^[0-9A-HJKMNP-TV-Z]{26}$"]

// Opaque type for risk/portfolio IDs (ULID)
object SafeId:
  opaque type SafeId = SafeIdStr
  
  object SafeId:
    def apply(s: SafeIdStr): SafeId = s
    def unapply(id: SafeId): Option[SafeIdStr] = Some(id)
    
  extension (id: SafeId)
    def value: SafeIdStr = id
  
  // Convenience constructor from plain String (case-insensitive)
  def fromString(s: String): Either[List[ValidationError], SafeId] =
    ValidationUtil.refineId(s)

// TreeId: Nominal case class wrapper over SafeId for tree identity (ADR-018).
// Compiler-distinct from NodeId and raw SafeId. All validation delegates to SafeId.
case class TreeId(toSafeId: SafeId.SafeId):
  /** Extract the canonical ULID string. */
  def value: String = toSafeId.value.toString

object TreeId:
  /** Smart constructor: delegates validation to SafeId.fromString, wraps result. */
  def fromString(s: String): Either[List[ValidationError], TreeId] =
    SafeId.fromString(s).map(TreeId(_))

  // JSON codecs (companion object placement ensures implicit scope)
  given JsonEncoder[TreeId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[TreeId] = JsonDecoder[String].mapOrFail(s =>
    TreeId.fromString(s).left.map(_.mkString(", ")))

// NodeId: Nominal case class wrapper over SafeId for node identity (ADR-018).
// Compiler-distinct from TreeId and raw SafeId. All validation delegates to SafeId.
case class NodeId(toSafeId: SafeId.SafeId):
  /** Extract the canonical ULID string. */
  def value: String = toSafeId.value.toString

object NodeId:
  /** Smart constructor: delegates validation to SafeId.fromString, wraps result. */
  def fromString(s: String): Either[List[ValidationError], NodeId] =
    SafeId.fromString(s).map(NodeId(_))

  // JSON codecs (companion object placement ensures implicit scope)
  given JsonEncoder[NodeId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[NodeId] = JsonDecoder[String].mapOrFail(s =>
    NodeId.fromString(s).left.map(_.mkString(", ")))

  // JSON map-key codecs — required for Map[NodeId, V] serialization (ADR-001 §4)
  given JsonFieldEncoder[NodeId] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[NodeId] = JsonFieldDecoder.string.mapOrFail(s =>
    NodeId.fromString(s).left.map(_.mkString(", ")))

// WorkspaceKeySecret: 128-bit SecureRandom credential, base64url encoded (22 chars, no padding).
// Used as capability URL token for workspace access. Standalone type — NOT a ULID wrapper.
// Different charset (base64url vs Crockford base32) and length (22 vs 26) from SafeId.
//
// ADR-022: final class — no compiler-generated unapply, copy, or product serialisation.
// Raw value accessible only via explicit `reveal` call; toString is redacted.
type WorkspaceKeyStr = String :| Match["^[A-Za-z0-9_-]{22}$"]

final class WorkspaceKeySecret private (private val raw: WorkspaceKeyStr):
  /** Explicitly extract the raw credential string. Call sites must opt in. */
  def reveal: String = raw
  override def toString: String = "WorkspaceKeySecret(***)"
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case wk: WorkspaceKeySecret => raw == wk.raw
    case _                => false

object WorkspaceKeySecret:
  /** Construct from an already-validated WorkspaceKeyStr (Iron proof required). */
  def apply(value: WorkspaceKeyStr): WorkspaceKeySecret = new WorkspaceKeySecret(value)

  // Thread-safe: SecureRandom is documented as thread-safe in the JDK.
  // Shared instance avoids repeated seeding overhead from /dev/urandom on each call.
  private val rng: java.security.SecureRandom = new java.security.SecureRandom()

  /** Generate a cryptographically random workspace key (128-bit entropy).
    * refineUnsafe is safe here: SecureRandom(16 bytes) → base64url encoding
    * always produces exactly 22 chars from [A-Za-z0-9_-].
    */
  def generate: UIO[WorkspaceKeySecret] =
    ZIO.succeed {
      val bytes = new Array[Byte](16) // 128 bits
      rng.nextBytes(bytes)
      val encoded = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
      new WorkspaceKeySecret(encoded.refineUnsafe[Match["^[A-Za-z0-9_-]{22}$"]])
    }

  /** Smart constructor: validates base64url format, 22 chars. */
  def fromString(s: String): Either[List[ValidationError], WorkspaceKeySecret] =
    ValidationUtil.refineWorkspaceKeySecret(s)

  // JSON codecs (companion object placement ensures implicit scope)
  given JsonEncoder[WorkspaceKeySecret] = JsonEncoder[String].contramap(_.reveal)
  given JsonDecoder[WorkspaceKeySecret] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceKeySecret.fromString(s).left.map(_.mkString(", ")))

// WorkspaceKeyHash: SHA-256 digest of a workspace capability key.
// Internal-only durable identifier for workspace-key lookup.
// Redacted toString avoids accidental correlation/log leakage.
type WorkspaceKeyHashStr = String :| Match["^[0-9a-f]{64}$"]

final class WorkspaceKeyHash private (private val raw: WorkspaceKeyHashStr):
  /** Explicitly extract the digest string. Intended for persistence only. */
  def value: String = raw
  override def toString: String = "WorkspaceKeyHash(***)"
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case wh: WorkspaceKeyHash => raw == wh.raw
    case _                    => false

object WorkspaceKeyHash:
  def apply(value: WorkspaceKeyHashStr): WorkspaceKeyHash = new WorkspaceKeyHash(value)

  def fromString(s: String): Either[List[ValidationError], WorkspaceKeyHash] =
    ValidationUtil.refineWorkspaceKeyHash(s)

  def fromSecret(secret: WorkspaceKeySecret): WorkspaceKeyHash =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(secret.reveal.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
    new WorkspaceKeyHash(digest.refineUnsafe[Match["^[0-9a-f]{64}$"]])

// ============================================================================
// Auth Identity Types (ADR-012, ADR-024)
// ============================================================================

// UUID v4 constraint (RFC 4122 variant 1 — lowercase hex, 8-4-4-4-12 hyphens).
// Defined as a named constraint alias (mirrors UrlConstraint) so ValidationUtil can call
// .refineEither[UuidConstraint] without duplicating the regex string.
// External constraint: Keycloak issues `sub` claims in exactly this format.
type UuidConstraint = Match["^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"]

// UUID v4 string — the full refined type used for UserId.raw.
type UuidStr = String :| UuidConstraint

// UserId: final class wrapping a validated UUID string (JWT sub claim from Keycloak via Istio x-user-id header).
// PII (pseudonymous, links to natural person via Keycloak), NOT a secret credential.
// toString is redacted to prevent accidental PII in log interpolations (same mechanism as WorkspaceKeySecret).
// Extraction is `.value` (not `.reveal`) — signals "not a secret, but explicit extraction required".
//
// @see ADR-012: Claim Header Injection — mesh-injected; app contains zero JWT code.
// @see AUTHORIZATION-PLAN.md — UserId design rationale and PII classification.
/* final class UserId private (private val raw: UuidStr):
  /** Extract the raw UUID string. Use only in SpiceDB calls, audit logs, and serialisation. */
  def value: String = raw
  override def toString: String = "UserId(***)"
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case u: UserId => raw == u.raw
    case _         => false

object UserId:
  def apply(s: UuidStr): UserId = new UserId(s)
  def fromString(s: String): Either[List[ValidationError], UserId] =
    ValidationUtil.refineUserId(s)

  // JSON codecs (companion object placement ensures implicit scope)
  given JsonEncoder[UserId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[UserId] = JsonDecoder[String].mapOrFail(s =>
    UserId.fromString(s).left.map(_.mkString(", ")))
*/

sealed trait UserId:
  override def toString: String = "UserId(***)"

object UserId:
  // makes "val forged = Authenticated("not-validated".refineUnsafe[UuidConstraint])" impossible
  final class Authenticated private[UserId] (private val raw: UuidStr) extends UserId:
    def value: String = raw
    override def hashCode: Int = raw.hashCode
    override def equals(that: Any): Boolean = that match
      case u: Authenticated => raw == u.raw
      case _                => false

  case object Anonymous extends UserId:
    override def toString: String = "UserId.Anonymous"

  def fromString(s: String): Either[List[ValidationError], Authenticated] =
    ValidationUtil.refineUserId(s).map(new Authenticated(_))

  // JSON codecs — Authenticated only; Anonymous is never serialised to JSON.
  given JsonEncoder[Authenticated] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[Authenticated] = JsonDecoder[String].mapOrFail(s =>
    fromString(s).left.map(_.mkString(", ")))

// WorkspaceId: Nominal case class wrapper over SafeId (ULID) for workspace identity.
// Compiler-distinct from TreeId, NodeId, and raw SafeId.
// Stable, non-secret identifier for SpiceDB resource references.
//
// Why not WorkspaceKeySecret? The key is a mutable credential (rotatable), PII-adjacent,
// and must never appear in SpiceDB audit logs. WorkspaceId is a stable, non-secret ULID
// generated once at workspace creation and never changed (survives key rotation).
// @see AUTHORIZATION-PLAN.md — WorkspaceId design rationale.
case class WorkspaceId(toSafeId: SafeId.SafeId):
  /** Extract the canonical ULID string. */
  def value: String = toSafeId.value.toString

object WorkspaceId:
  /** Smart constructor: delegates validation to SafeId.fromString, wraps result. */
  def fromString(s: String): Either[List[ValidationError], WorkspaceId] =
    SafeId.fromString(s).map(WorkspaceId(_))

  // JSON codecs (companion object placement ensures implicit scope)
  given JsonEncoder[WorkspaceId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[WorkspaceId] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceId.fromString(s).left.map(_.mkString(", ")))

// CSS hex colour (#RRGGBB). Used by PaletteData and ColorAssigner in the app
// module for Vega-Lite chart curve colour assignment. Never serialized to the
// wire — .value extraction happens only at the Vega-Lite JSON edge.
// Single concept, no second hex-colour kind to distinguish → bare opaque per ADR-018.
type HexColorStr = String :| Match["^#[0-9a-fA-F]{6}$"]

object HexColor:
  opaque type HexColor = HexColorStr

  object HexColor:
    /** Construct from an already-refined HexColorStr (Iron proof required). */
    def apply(s: HexColorStr): HexColor = s

  extension (c: HexColor)
    /** Extract the refined `#rrggbb` string — only at the Vega-Lite JSON edge. */
    def value: HexColorStr = c
