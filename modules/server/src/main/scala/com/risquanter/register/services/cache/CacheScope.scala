package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.SeedEntityId

/**
  * Per-workspace `ContentCache` resolution (DD-17, closed 2026-07-16).
  *
  * The workspace's `seedEntityId` (HDR Entity axis) determines simulated
  * figures but appears in no leaf's bytes — so it cannot be part of the
  * content hash. One `ContentCache` instance per workspace makes
  * cross-workspace contamination structurally impossible: different entity
  * ⇒ different figures ⇒ entries must never be shared, and with separate
  * instances they cannot be.
  *
  * Keyed by `seedEntityId` (unique per workspace — assigned at workspace
  * creation). Cache lifecycle = workspace lifecycle; a deleted workspace's
  * cache lingers until restart (NoOp eviction, Phase A).
  *
  * Replaces `TreeCacheManager` as the resolver's cache entry point.
  */
trait CacheScope {

  /** Get or create the owning workspace's cache. */
  def cacheFor(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache]
}

object CacheScope {

  /** Live layer: NoOp eviction (Phase A — restart clears; see EvictionStrategy). */
  val layer: ZLayer[Any, Nothing, CacheScope] =
    ZLayer.fromZIO {
      Ref.make(Map.empty[SeedEntityId.SeedEntityId, ContentCache])
        .map(CacheScopeLive(_, () => new NoOpEvictionStrategy))
    }

  def cacheFor(seedEntityId: SeedEntityId.SeedEntityId): URIO[CacheScope, ContentCache] =
    ZIO.serviceWithZIO[CacheScope](_.cacheFor(seedEntityId))
}

final case class CacheScopeLive(
  caches: Ref[Map[SeedEntityId.SeedEntityId, ContentCache]],
  mkStrategy: () => EvictionStrategy
) extends CacheScope {

  override def cacheFor(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache] =
    caches.get.map(_.get(seedEntityId)).flatMap {
      case Some(cache) => ZIO.succeed(cache)
      case None =>
        for {
          candidate <- ContentCache.make(mkStrategy())
          // modify decides the winner atomically if two fibers race on first access
          cache <- caches.modify { m =>
            m.get(seedEntityId) match {
              case Some(existing) => (existing, m)
              case None           => (candidate, m + (seedEntityId -> candidate))
            }
          }
          _ <- ZIO.logDebug(s"CacheScope: cache ready for seedEntityId=${seedEntityId.value}")
        } yield cache
    }
}
