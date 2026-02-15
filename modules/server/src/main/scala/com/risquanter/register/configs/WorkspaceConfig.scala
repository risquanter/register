package com.risquanter.register.configs

import java.time.Duration

/** Workspace configuration.
  *
  * Controls workspace lifecycle parameters for free-tier and enterprise modes.
  */
final case class WorkspaceConfig(
  ttl: Duration = Duration.ofHours(72),
  idleTimeout: Duration = Duration.ofHours(1),
  reaperInterval: Duration = Duration.ofMinutes(5),
  maxCreatesPerIpPerHour: Int = 5,
  maxTreesPerWorkspace: Int = 10
)
