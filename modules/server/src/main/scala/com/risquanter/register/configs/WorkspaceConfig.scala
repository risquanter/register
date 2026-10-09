package com.risquanter.register.configs

import java.time.Duration

/** Workspace configuration.
  *
  * Controls workspace lifecycle parameters for free-tier and enterprise modes.
  *
  * `trustedProxyHops` is how many proxies this deployment operates in front of
  * the server, each of which appends its downstream peer to
  * `X-Forwarded-For`. The rate limiter reads the entry the outermost of them
  * wrote and ignores anything the caller placed to its left. The default of 1
  * is the Docker Compose stack, where the only proxy is the nginx in the
  * frontend image; a deployment behind the Istio ingress gateway as well sets 2.
  */
final case class WorkspaceConfig(
  ttl: Duration = Duration.ofHours(72),
  idleTimeout: Duration = Duration.ofHours(1),
  reaperInterval: Duration = Duration.ofMinutes(5),
  maxCreatesPerIpPerHour: Int = 5,
  maxTreesPerWorkspace: Int = 10,
  trustedProxyHops: Int = 1
)
