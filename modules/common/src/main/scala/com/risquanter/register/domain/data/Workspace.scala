package com.risquanter.register.domain.data

import java.time.{Duration, Instant}
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash, SeedEntityId}

/** Which of a workspace's two deadlines ended it.
  *
  * This crosses to the client. Without it a browser knows only that the
  * workspace is gone, and a sentence naming the 72-hour limit is simply wrong
  * for a workspace that was abandoned after ten minutes.
  */
enum ExpiryCause:
  /** The absolute deadline: `createdAt + ttl`. Use does not postpone it. */
  case Lifetime

  /** The idle deadline: `lastAccessedAt + idleTimeout`. Every access to the
    * workspace pushes it forward.
    */
  case Inactivity

object ExpiryCause:
  given codec: JsonCodec[ExpiryCause] = DeriveJsonCodec.gen[ExpiryCause]

/** A deadline a workspace has passed: which one, and when. */
final case class Expiry(cause: ExpiryCause, at: Instant)

/** Workspace durable record — association/hash index.
  *
  * A workspace groups a set of trees under a single capability hash.
  * It is NOT a content store — it maps durable metadata to a set of TreeIds.
  * Tree data lives in RiskTreeRepository; this is just the access layer.
  *
  * Security-relevant fields (OWASP cross-reference):
  * - `lastAccessedAt` (A10): tracks idle timeout
  * - `idleTimeout` + `ttl` (A11): dual timeout — absolute AND idle
  *
  * `seedEntityId` is the workspace's stochastic identity: the HDR generator's
  * Entity axis, shared by every simulation in this workspace. Assigned from the
  * store's monotonic counter (or provided at creation), globally unique per
  * deployment, and immutable once assigned. Never derived from the ULID.
  */
final case class WorkspaceRecord(
  id: WorkspaceId,
  keyHash: WorkspaceKeyHash,
  trees: Set[TreeId],
  createdAt: Instant,
  lastAccessedAt: Instant,
  ttl: Duration,
  idleTimeout: Duration,
  seedEntityId: SeedEntityId.SeedEntityId
):
  /** The deadline this workspace has passed, or `None` while it is live.
    *
    * Two deadlines can end a workspace and either one is enough (A11): the
    * absolute deadline at `createdAt + ttl`, which no amount of use postpones,
    * and the idle deadline at `lastAccessedAt + idleTimeout`, which every
    * recorded access pushes forward. A deadline set to zero or a negative
    * duration is switched off, which is how an unlimited lifetime is expressed
    * (enterprise mode: infinite TTL).
    *
    * When both have passed, the earlier one is the answer: it is the moment the
    * workspace stopped being usable, and the later deadline never applied to a
    * workspace that was already gone.
    */
  def expiry(now: Instant): Option[Expiry] =
    val absolute = Option.when(!ttl.isZero && !ttl.isNegative)(
      Expiry(ExpiryCause.Lifetime, createdAt.plus(ttl)))
    val idle = Option.when(!idleTimeout.isZero && !idleTimeout.isNegative)(
      Expiry(ExpiryCause.Inactivity, lastAccessedAt.plus(idleTimeout)))
    (absolute.toList ++ idle.toList).filterNot(_.at.isAfter(now)).minByOption(_.at)

  /** Whether this workspace has expired via either deadline (A11). */
  def isExpired(now: Instant): Boolean = expiry(now).isDefined

  /** Record access — returns a copy with updated lastAccessedAt (A10). */
  def touch(now: Instant): WorkspaceRecord = copy(lastAccessedAt = now)

  /** Absolute expiry instant, if TTL is finite. None for enterprise mode (infinite TTL). */
  def expiresAt: Option[Instant] =
    if ttl.isZero || ttl.isNegative then None
    else Some(createdAt.plus(ttl))
