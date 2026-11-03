package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.SeedEntityId

/**
  * Registry of `ContentCache` instances, one per seed entity id, created on
  * first request and held until the process exits.
  * `MitigationScopeResolverRegistry` does the same for `MitigationScopeResolver`,
  * keyed by workspace.
  *
  * The `seedEntityId` (HDR Entity axis) is an input to every draw, so it
  * determines the simulated figures, and it appears in no leaf's bytes, so it
  * cannot be part of the content hash. One instance per entity id is what keeps
  * figures drawn under different entity ids apart: different entity ⇒ different
  * figures ⇒ entries must never be shared, and with separate instances they
  * cannot be. The workspace id takes no part in the key.
  *
  * Today a workspace holds one seed entity id, assigned at creation, and no two
  * workspaces hold the same one, so there is one instance per workspace as a
  * consequence. A deleted workspace's instance lingers until restart, because
  * nothing is evicted.
  */
trait ContentCacheRegistry {

  /** Get or create the cache for this seed entity id. */
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
