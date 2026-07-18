package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.ContentHash

/**
  * Eviction statistics for observability (logged, never exposed as API —
  * DD-20/CacheController precedent: build API surface only with a concrete
  * consumer).
  *
  * @param evictedTotal Entries evicted since this strategy instance was created
  */
final case class EvictionStats(evictedTotal: Long)

/**
  * Memory-management policy for a `ContentCache`.
  *
  * Content-addressed caching creates orphan entries: when a leaf's params
  * change, the old hash's entry is never looked up again (the key is
  * recomputed from content, so a stale entry is unreachable — eviction is
  * about memory, never correctness).
  *
  * Phase A ships `NoOpEvictionStrategy` only (in-memory cache, restart
  * clears). Graduate to an LRU cap when memory pressure is observable.
  */
trait EvictionStrategy {

  /** Called after a cache write. Returns hashes to evict (may be empty). */
  def onStore(hash: ContentHash, sizeBytes: Long): UIO[Set[ContentHash]]

  /** Called on cache hit. Allows recency tracking. */
  def onAccess(hash: ContentHash): UIO[Unit]

  /** Periodic or on-demand sweep. Returns all hashes to evict now. */
  def sweep: UIO[Set[ContentHash]]

  /** Observability. */
  def stats: UIO[EvictionStats]
}

/**
  * Phase A default: never evicts. Small trees, ~80KB entries — memory is
  * cheaper than the policy. Orphans linger until server restart empties the
  * in-memory cache.
  */
final class NoOpEvictionStrategy extends EvictionStrategy {
  override def onStore(hash: ContentHash, sizeBytes: Long): UIO[Set[ContentHash]] =
    ZIO.succeed(Set.empty)

  override def onAccess(hash: ContentHash): UIO[Unit] = ZIO.unit

  override def sweep: UIO[Set[ContentHash]] = ZIO.succeed(Set.empty)

  override def stats: UIO[EvictionStats] = ZIO.succeed(EvictionStats(evictedTotal = 0L))
}
