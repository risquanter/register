package com.risquanter.register.domain.errors

/** Why access was denied. */
enum DenialKind:
  /** The identity header the route requires was absent. */
  case IdentityAbsent

  /** The authorization service answered that the permission is not held. */
  case PermissionNotHeld

object DenialKind:
  /** The server's own sentence for a log line. Never reaches a client. */
  def describe(kind: DenialKind): String = kind match
    case IdentityAbsent    => "the identity header the route requires was absent"
    case PermissionNotHeld => "the permission is not held"
