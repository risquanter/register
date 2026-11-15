package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, BranchRef, CommitHash, IronConstants, PositiveInt}

/** Memoizes resolved scopes per tree version. Each (tree, branch) slot holds at
  * most two revisions, most recently used first, so a pinned historic read
  * takes the second position and the head entry survives. The bound is a
  * property of the structure, not an eviction policy. In-memory `Ref` → `UIO`.
  * One instance per workspace (`MitigationScopeResolverRegistry`).
  *
  * The revision is a stamp inside each stored entry rather than part of the map
  * key. Keying on the revision would make every revision a separate entry with
  * nothing to remove it; each entry holds a full mitigation-to-node-id map, so
  * an editing session would retain one per edit and grow without a ceiling. As
  * a stamp under a slot keyed by (treeId, branch), the map is bounded by the
  * number of tree-and-branch pairs this workspace has read, times the slot
  * capacity instead. Nothing removes a slot, so the slot of a deleted tree or
  * branch stays until the resolver itself is released.
  *
  * The memo read and write are not atomic — last-writer-wins is a deliberate,
  * accepted trade-off, safe because the exact-revision hit guard never serves a
  * mismatched scope.
  */
final case class MitigationScopeResolverLive(
  memo: Ref[Map[(TreeId, BranchRef), Vector[(CommitHash, ResolvedScopes)]]]
) extends MitigationScopeResolver:

  override def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes] =
    val slot = (context.treeId, context.branch)
    memo.get.map(_.getOrElse(slot, Vector.empty)).flatMap { entries =>
      entries.find(_._1 == context.revision) match
        case Some(hit) =>
          memo.update(m => m.updated(slot, hit +: entries.filterNot(_._1 == context.revision))).as(hit._2)
        case None =>
          val resolved = MitigationScopeResolver.resolveUncached(tree)
          memo.update { m =>
            val current = m.getOrElse(slot, Vector.empty).filterNot(_._1 == context.revision)
            m.updated(slot, ((context.revision, resolved) +: current).take(MitigationScopeResolverLive.SlotCapacity))
          }.as(resolved)
    }

object MitigationScopeResolverLive:
  /** Revisions one (tree, branch) slot holds: the branch head and one pinned
    * revision. */
  val SlotCapacity: PositiveInt = IronConstants.Two
