package com.risquanter.register.domain.data

import java.time.{Duration, Instant}
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash}

/** Workspace durable record — association/hash index.
  *
  * A workspace groups a set of trees under a single capability hash.
  * It is NOT a content store — it maps durable metadata to a set of TreeIds.
  * Tree data lives in RiskTreeRepository; this is just the access layer.
  *
  * Security-relevant fields (OWASP cross-reference):
  * - `lastAccessedAt` (A10): tracks idle timeout
  * - `idleTimeout` + `ttl` (A11): dual timeout — absolute AND idle
  */
final case class WorkspaceRecord(
  id: WorkspaceId,
  keyHash: WorkspaceKeyHash,
  trees: Set[TreeId],
  createdAt: Instant,
  lastAccessedAt: Instant,
  ttl: Duration,
  idleTimeout: Duration
):
  /** Check if this workspace has expired via EITHER timeout mechanism (A11).
    *
    * Returns true if:
    * - absolute timeout exceeded: `now - createdAt >= ttl`, OR
    * - idle timeout exceeded: `now - lastAccessedAt >= idleTimeout`
    *
    * When ttl is Duration.ZERO or negative, absolute timeout is disabled
    * (enterprise mode: infinite TTL).
    */
  def isExpired(now: Instant): Boolean =
    val absoluteExpired = !ttl.isZero && !ttl.isNegative &&
      Duration.between(createdAt, now).compareTo(ttl) >= 0
    val idleExpired = !idleTimeout.isZero && !idleTimeout.isNegative &&
      Duration.between(lastAccessedAt, now).compareTo(idleTimeout) >= 0
    absoluteExpired || idleExpired

  /** Record access — returns a copy with updated lastAccessedAt (A10). */
  def touch(now: Instant): WorkspaceRecord = copy(lastAccessedAt = now)

  /** Absolute expiry instant, if TTL is finite. None for enterprise mode (infinite TTL). */
  def expiresAt: Option[Instant] =
    if ttl.isZero || ttl.isNegative then None
    else Some(createdAt.plus(ttl))
