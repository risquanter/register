package com.risquanter.register.configs

import zio.Config
import zio.config.magnolia.{DeriveConfig, deriveConfig}
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.iron.{ExternalTokenStr, PositiveInt, IronConstants, SecureUrl}
import com.risquanter.register.domain.data.iron.SecureUrl.*
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.domain.data.iron.ValidationUtil

// SpiceDbToken: redacted credential wrapping a config-loaded bearer token.
//
// Follows ADR-022 R1–R8 credential class design:
//   R1: final class — no compiler-generated unapply, copy, or product serialisation
//   R2: private constructor — construction via companion fromString only
//   R3: toString redacted — prevents accidental log leakage
//   R4: reveal method — explicit opt-in extraction at call sites
//   R5: Iron-validated raw type (ExternalTokenStr) — PrintableAscii + MaxLength[2048]
//       blocks CRLF injection before the value can reach an HTTP header (ADR-001 §8)
//   R6: manual equals / hashCode — structural equality without reflection
//   R7: fromString companion constructor — validates at config load time
//   R8: no JSON codec — config-only type; never serialised to or from JSON
final class SpiceDbToken private (private val raw: ExternalTokenStr):
  /** Explicitly extract the raw token string. Use only when populating HTTP Authorization headers. */
  def reveal: String = raw
  override def toString: String = "SpiceDbToken(***)"
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case t: SpiceDbToken => raw == t.raw
    case _               => false

object SpiceDbToken:
  /** Validates and wraps a raw token string.
    * Returns a ValidationError if the value is blank, exceeds 2048 chars,
    * or contains control characters / non-printable bytes (CRLF injection guard).
    */
  def fromString(s: String, fieldPath: String = "token"): Either[List[ValidationError], SpiceDbToken] =
    ValidationUtil.refineExternalToken(s, fieldPath).map(new SpiceDbToken(_))

// Controls ZedToken cache freshness for SpiceDB authorization checks.
//
//   MinimizeLatency  — SpiceDB resolves ACL checks from its in-memory NewEnemy cache.
//                      Approximately 1 ms overhead. Safe for pure PEP (application does
//                      not write relationships) because revocation cannot be self-consistent.
//
//   FullyConsistent  — SpiceDB reads a PostgreSQL snapshot on every check.
//                      Approximately 5 ms overhead. Required for environments where
//                      immediate revocation visibility is a compliance obligation.
//
// Default: MinimizeLatency
enum SpiceDbConsistency:
  case MinimizeLatency
  case FullyConsistent

object SpiceDbConsistency:
  private val config: Config[SpiceDbConsistency] =
    Config.string.mapOrFail {
      case "minimize-latency" => Right(SpiceDbConsistency.MinimizeLatency)
      case "fully-consistent" => Right(SpiceDbConsistency.FullyConsistent)
      case other =>
        Left(Config.Error.InvalidData(message =
          s"Invalid spicedb.consistency: '$other'. Expected one of: minimize-latency, fully-consistent"))
    }

  given DeriveConfig[SpiceDbConsistency] = DeriveConfig(config)

/** SpiceDB connectivity configuration.
  *
  * Loaded from application.conf under `register.spicedb`.
  *
  * @param url            HTTPS endpoint for the SpiceDB gRPC-gateway REST API
  * @param token          Pre-shared key or token sent as HTTP Authorization Bearer header
  * @param consistency    ZedToken cache freshness policy (default: minimize-latency)
  * @param timeoutSeconds Per-request timeout in seconds (default: 10)
  *
  * @see ADR-001 §8: ExternalTokenStr PrintableAscii injection guard
  * @see ADR-022: SpiceDbToken credential class design (R1–R8)
  */
final case class SpiceDbConfig(
  url:            SecureUrl.SecureUrl,
  token:          SpiceDbToken,
  consistency:    SpiceDbConsistency = SpiceDbConsistency.MinimizeLatency,
  timeoutSeconds: PositiveInt = IronConstants.Ten
)

object SpiceDbConfig:
  // All DeriveConfig instances are local to this companion object.
  // IMPORTANT: exposing these as bare `given Config[T]` would leak via the
  // deriveConfigFromConfig bridge in zio-config-magnolia, causing Magnolia to
  // resolve SecureUrl's validator for ALL string fields in the case class.
  // Following the IrminConfig / SimulationConfig pattern exactly.

  private def errorsToConfigError(errs: List[ValidationError]): Config.Error =
    Config.Error.InvalidData(message = errs.map(_.message).mkString("; "))

  private val secureUrlConfig: Config[SecureUrl.SecureUrl] =
    Config.string.mapOrFail { s =>
      SecureUrl
        .fromString(s, "url")
        .left
        .map(errorsToConfigError)
    }

  private val tokenConfig: Config[SpiceDbToken] =
    Config.string.mapOrFail { s =>
      SpiceDbToken
        .fromString(s, "token")
        .left
        .map(errorsToConfigError)
    }

  private val positiveIntConfig: Config[PositiveInt] =
    deriveConfig[Int].mapOrFail { value =>
      ValidationUtil.refinePositiveInt(value).left.map(errorsToConfigError)
    }

  given DeriveConfig[SecureUrl.SecureUrl]   = DeriveConfig(secureUrlConfig)
  given DeriveConfig[SpiceDbToken]          = DeriveConfig(tokenConfig)
  // DeriveConfig[SpiceDbConsistency] is defined in SpiceDbConsistency companion — found via implicit scope
  given DeriveConfig[PositiveInt]           = DeriveConfig(positiveIntConfig)
  given DeriveConfig[SpiceDbConfig]         = DeriveConfig.derived
