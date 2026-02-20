package com.risquanter.register.auth

import com.risquanter.register.domain.data.iron.{UserId, Email}

/** Coarse-grained role enum for OPA claim-based gate.
  * Values mirror OPA Rego recognized role names exactly.
  * Unknown claim strings are silently dropped — never fail on unrecognised claims.
  *
  * @see ADR-012: OPA for Authorization
  * @see AUTHORIZATION-PLAN.md — Task L1.1: UserContext Extraction
  */
enum Role:
  case Analyst   // may run analysis, create scenario branches; cannot mutate canonical design
  case Editor    // ⊇ Analyst: may also mutate canonical design
  case TeamAdmin // may manage team structure

object Role:
  /** Parse a role claim string from the mesh-injected x-user-roles header.
    * Returns None for any unrecognised string — silently dropped.
    */
  def fromClaim(s: String): Option[Role] = s match
    case "analyst"    => Some(Analyst)
    case "editor"     => Some(Editor)
    case "team_admin" => Some(TeamAdmin)
    case _            => None

/** Extracted user context — populated from Istio-injected claim headers.
  * The mesh validates JWT signature, expiry, and issuer before injection.
  * App contains zero JWT code.
  *
  * userId is the ONLY field passed to SpiceDB check().
  * email is display-only, never used for authorization decisions.
  * roles are used for OPA coarse gate only — SpiceDB never receives roles.
  *
  * @see ADR-012: Minimal Service Code for Auth
  * @see ADR-024: SpiceDB Receives userId Only
  * @see AUTHORIZATION-PLAN.md — Task L1.1: UserContext Extraction
  */
final case class UserContext(
  userId: UserId,             // from x-user-id header (mesh-injected JWT sub)
  email:  Option[Email.Email], // from x-user-email header — display only, never used for authz
  roles:  Set[Role]           // from x-user-roles header — OPA coarse gate only
)
