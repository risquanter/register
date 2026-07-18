package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.ContentHash

/**
  * Cache observability snapshot (DD-20/CacheController precedent: logged
  * only, no endpoint — build API surface only with a concrete consumer).
  *
  * @param entries Current entry count (includes orphans awaiting eviction)
  * @param hits    Lookups that found an entry, since cache creation
  * @param misses  Lookups that found nothing, since cache creation
  * @param evictedTotal Entries evicted by the strategy, since cache creation
  */
final case class CacheStats(entries: Int, hits: Long, misses: Long, evictedTotal: Long)

/**
  * Content-addressed simulation cache: `ContentHash → LeafSimResult`.
  *
  * Replaces the NodeId-keyed `RiskResultCache`/`TreeCacheManager` pair
  * (milestone 2b, DD-3). The key is recomputed from a leaf's simulation
  * content on every read (`ContentHashIndex`), so a changed leaf *is* a
  * different key — staleness is structurally impossible and there is no
  * invalidation operation. Old entries become unreachable orphans, handled
  * by the `EvictionStrategy` (memory management, never correctness).
  *
  * Holds LEAF entries only (DD-15 → B): portfolios re-aggregate from child
  * results on every read and never enter the cache. Values are identity-free
  * (DD-16/DD-18): the resolver attaches the requested node's ID at the edge.
  *
  * One instance per workspace (DD-17), created by `CacheScope` — the
  * workspace's `seedEntityId` determines figures but lives in no leaf's
  * bytes, so per-workspace instances make cross-workspace contamination
  * structurally impossible. Cache lifecycle = workspace lifecycle.
  */
trait ContentCache {

  def get(key: ContentHash): UIO[Option[LeafSimResult]]

  def put(key: ContentHash, value: LeafSimResult): UIO[Unit]

  def stats: UIO[CacheStats]
}

object ContentCache {

  def make(strategy: EvictionStrategy): UIO[ContentCache] =
    for {
      entries <- Ref.make(Map.empty[ContentHash, LeafSimResult])
      hits    <- Ref.make(0L)
      misses  <- Ref.make(0L)
    } yield ContentCacheLive(entries, hits, misses, strategy)
}

final case class ContentCacheLive(
  entries: Ref[Map[ContentHash, LeafSimResult]],
  hits: Ref[Long],
  misses: Ref[Long],
  strategy: EvictionStrategy
) extends ContentCache {

  override def get(key: ContentHash): UIO[Option[LeafSimResult]] =
    entries.get.map(_.get(key)).tap {
      case Some(_) => hits.update(_ + 1) *> strategy.onAccess(key)
      case None    => misses.update(_ + 1)
    }

  override def put(key: ContentHash, value: LeafSimResult): UIO[Unit] =
    for {
      _       <- entries.update(_ + (key -> value))
      toEvict <- strategy.onStore(key, value.approxSizeBytes)
      _       <- ZIO.when(toEvict.nonEmpty)(entries.update(_ -- toEvict))
    } yield ()

  override def stats: UIO[CacheStats] =
    for {
      m  <- entries.get
      h  <- hits.get
      mi <- misses.get
      es <- strategy.stats
    } yield CacheStats(entries = m.size, hits = h, misses = mi, evictedTotal = es.evictedTotal)
}
