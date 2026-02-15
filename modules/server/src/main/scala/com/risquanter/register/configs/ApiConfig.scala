package com.risquanter.register.configs

/** API feature gate configuration.
  *
  * Controls access to endpoints that should be disabled by default
  * for security reasons.
  *
  * A17: GET /risk-trees (list-all) is sealed by default. Only enabled
  * for admin/debug use via explicit config override.
  */
final case class ApiConfig(
  listAllTreesEnabled: Boolean = false
)
