package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.SeedEntityId

/**
  * Per-workspace registry of `ContentCache` instances: one cache per workspace
  * seed identity, created on first request and held until the process exits.
  * Mirrored by `MitigationScopeResolverRegistry`, which does the same for
  * `MitigationScopeResolver`.
  *
  * The workspace's `seedEntityId` (HDR Entity axis) determines simulated
  * figures but appears in no leaf's bytes — so it cannot be part of the
  * content hash. One `ContentCache` instance per workspace makes
  * cross-workspace contamination structurally impossible: different entity
  * ⇒ different figures ⇒ entries must never be shared, and with separate
  * instances they cannot be.
  *
  * Keyed by `seedEntityId` (unique per workspace — assigned at workspace
  * creation). Cache lifecycle matches workspace lifecycle; a deleted
  * workspace's cache lingers until restart, because nothing is evicted.
  */
trait ContentCacheRegistry {

  /** Get or create the owning workspace's cache. */
  def forWorkspace(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache]
}

object ContentCacheRegistry {

  /** Live layer: NoOp eviction (restart clears; see EvictionStrategy). */
  val layer: ZLayer[Any, Nothing, ContentCacheRegistry] =
    ZLayer.fromZIO {
      Ref.make(Map.empty[SeedEntityId.SeedEntityId, ContentCache])
        .map(ContentCacheRegistryLive(_, () => new NoOpEvictionStrategy))
    }

  def forWorkspace(seedEntityId: SeedEntityId.SeedEntityId): URIO[ContentCacheRegistry, ContentCache] =
    ZIO.serviceWithZIO[ContentCacheRegistry](_.forWorkspace(seedEntityId))
}

final case class ContentCacheRegistryLive(
  caches: Ref[Map[SeedEntityId.SeedEntityId, ContentCache]],
  mkStrategy: () => EvictionStrategy
) extends ContentCacheRegistry {

  override def forWorkspace(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache] =
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
          _ <- ZIO.logDebug(s"ContentCacheRegistry: cache ready for seedEntityId=${seedEntityId.value}")
        } yield cache
    }
}
