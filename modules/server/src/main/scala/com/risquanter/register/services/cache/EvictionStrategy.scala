package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.ContentHash

/**
  * Eviction statistics. The cache folds them into `CacheStats`, which the
  * resolver logs at debug; no endpoint exposes them.
  *
  * @param evictedTotal Entries evicted since this strategy instance was created
  */
final case class EvictionStats(evictedTotal: Long)

/**
  * Memory-management policy for a `ContentCache`.
  *
  * Content-addressed caching creates orphan entries: when a leaf's parameters
  * change, the old hash's entry is never looked up again. The key is
  * recomputed from content, so a stale entry is unreachable — eviction is
  * about memory, never correctness.
  *
  * `NoOpEvictionStrategy` is the only implementation, and `CacheScope`
  * constructs it for every workspace cache.
  */
trait EvictionStrategy {

  /** Called after a cache write. Returns hashes to evict (may be empty). */
  def onStore(hash: ContentHash, sizeBytes: Long): UIO[Set[ContentHash]]

  /** Called on cache hit. Allows recency tracking. */
  def onAccess(hash: ContentHash): UIO[Unit]

  /** Returns every hash to evict now. No caller invokes it. */
  def sweep: UIO[Set[ContentHash]]

  /** Observability. */
  def stats: UIO[EvictionStats]
}

/**
  * Never evicts. Orphan entries linger until a server restart empties the
  * in-memory cache.
  */
final class NoOpEvictionStrategy extends EvictionStrategy {
  override def onStore(hash: ContentHash, sizeBytes: Long): UIO[Set[ContentHash]] =
    ZIO.succeed(Set.empty)

  override def onAccess(hash: ContentHash): UIO[Unit] = ZIO.unit

  override def sweep: UIO[Set[ContentHash]] = ZIO.succeed(Set.empty)

  override def stats: UIO[EvictionStats] = ZIO.succeed(EvictionStats(evictedTotal = 0L))
}
