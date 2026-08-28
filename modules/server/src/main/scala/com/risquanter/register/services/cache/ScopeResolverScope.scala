package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, CommitHash}

/** Per-workspace `MitigationScopeResolver` resolution, mirroring `CacheScope`.
  * One resolver instance per workspace makes cross-workspace scope contamination
  * structurally impossible; the memo key inside each instance is
  * (treeId, branch, revision).
  */
trait ScopeResolverScope:
  def resolverFor(workspaceId: WorkspaceId): UIO[MitigationScopeResolver]

object ScopeResolverScope:
  val layer: ZLayer[Any, Nothing, ScopeResolverScope] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[WorkspaceId, MitigationScopeResolver]).map(ScopeResolverScopeLive(_))
    )

  def resolverFor(workspaceId: WorkspaceId): URIO[ScopeResolverScope, MitigationScopeResolver] =
    ZIO.serviceWithZIO[ScopeResolverScope](_.resolverFor(workspaceId))

final case class ScopeResolverScopeLive(
  resolvers: Ref[Map[WorkspaceId, MitigationScopeResolver]]
) extends ScopeResolverScope:
  override def resolverFor(workspaceId: WorkspaceId): UIO[MitigationScopeResolver] =
    resolvers.get.map(_.get(workspaceId)).flatMap {
      case Some(r) => ZIO.succeed(r)
      case None =>
        for
          memo     <- Ref.make(Map.empty[(TreeId, BranchRef), (CommitHash, ResolvedScopes)])
          candidate = MitigationScopeResolverLive(memo)
          // modify picks the winner atomically if two fibers race on first access
          resolver <- resolvers.modify { m =>
                        m.get(workspaceId) match
                          case Some(existing) => (existing, m)
                          case None           => (candidate, m + (workspaceId -> candidate))
                      }
        yield resolver
    }
