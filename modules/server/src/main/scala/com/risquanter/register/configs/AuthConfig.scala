package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.magnolia.*

enum AuthMode:
  case CapabilityOnly, Identity, FineGrained

object AuthMode:
  private val config: Config[AuthMode] =
    Config.string.mapOrFail {
      case "capability-only" => Right(AuthMode.CapabilityOnly)
      case "identity"        => Right(AuthMode.Identity)
      case "fine-grained"    => Right(AuthMode.FineGrained)
      case other =>
        Left(Config.Error.InvalidData(message = s"Invalid auth.mode: '$other'. Expected one of: capability-only, identity, fine-grained"))
    }

  given DeriveConfig[AuthMode] = DeriveConfig(config)

/** Authorization mode configuration.
  *
  * Controls which authorization layers are active at runtime:
  *   - "capability-only": Layer 0 only (free-tier default — workspace key is sole credential)
  *   - "identity":        Layer 0+1 (Keycloak JWT required, SpiceDB NoOp)
  *   - "fine-grained":    Layer 0+1+2 (Keycloak JWT + SpiceDB ACL)
  *
  * @see AUTHORIZATION-PLAN.md — Single Codebase, Config-Driven
  * @see ADR-012: Service Mesh / Istio
  */
final case class AuthConfig(
  mode: AuthMode = AuthMode.CapabilityOnly
)

object AuthConfig:
  val layer: ZLayer[Any, Throwable, AuthConfig] =
    Configs.makeLayer[AuthConfig]("register.auth")
