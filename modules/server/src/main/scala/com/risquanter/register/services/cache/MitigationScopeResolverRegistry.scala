package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, CommitHash}

/** Per-workspace registry of `MitigationScopeResolver` instances: one resolver
  * per workspace, created on first access and held until the process exits.
  * Mirrors `ContentCacheRegistry`.
  *
  * One resolver instance per workspace makes cross-workspace scope
  * contamination structurally impossible. Inside each instance the memo is
  * keyed by (treeId, branch), with the revision held in the entry as a validity
  * guard.
  */
trait MitigationScopeResolverRegistry:
  def forWorkspace(workspaceId: WorkspaceId): UIO[MitigationScopeResolver]

object MitigationScopeResolverRegistry:
  val layer: ZLayer[Any, Nothing, MitigationScopeResolverRegistry] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[WorkspaceId, MitigationScopeResolver]).map(MitigationScopeResolverRegistryLive(_))
    )

  def forWorkspace(workspaceId: WorkspaceId): URIO[MitigationScopeResolverRegistry, MitigationScopeResolver] =
    ZIO.serviceWithZIO[MitigationScopeResolverRegistry](_.forWorkspace(workspaceId))

final case class MitigationScopeResolverRegistryLive(
  resolvers: Ref[Map[WorkspaceId, MitigationScopeResolver]]
) extends MitigationScopeResolverRegistry:
  override def forWorkspace(workspaceId: WorkspaceId): UIO[MitigationScopeResolver] =
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
