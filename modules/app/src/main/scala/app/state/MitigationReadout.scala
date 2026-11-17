package app.state

import zio.NonEmptyChunk

import com.risquanter.register.domain.data.{Mitigation, MitigationTarget, TargetingPredicate}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, SafeName}
import com.risquanter.register.http.errors.BindProblem
import com.risquanter.register.http.responses.TreeStructureResponse

/** What the tree card shows for one node: mitigations scoped at it, or only
  * below it. */
enum CoverageBadge:
  case Scoped(mitigations: NonEmptyChunk[Mitigation])
  case BelowOnly(mitigations: NonEmptyChunk[Mitigation])

/** Lookups over one structure read. It holds no data of its own: the response
  * carries the tree, each mitigation's resolution and the stale identifiers.
  */
object MitigationReadout:

  /** The tree's mitigations in the order application uses: ascending
    * precedence key, the identifier breaking a tie. */
  def mitigations(response: TreeStructureResponse): List[Mitigation] =
    response.tree.mitigations.toList.sortBy(m => (m.precedence.key, m.id.value: String))

  /** The nodes `id` resolved to in this tree version; empty for a predicate
    * that did not bind and for an identifier the read does not list. */
  def scopeOf(response: TreeStructureResponse, id: MitigationId): Set[NodeId] =
    response.mitigationScopes.find(_.mitigationId == id).fold(Set.empty[NodeId])(_.outcome.scopeOrEmpty)

  /** Why `id`'s predicate did not bind, or none when it did. */
  def failuresOf(response: TreeStructureResponse, id: MitigationId): Option[NonEmptyChunk[BindProblem]] =
    response.mitigationScopes.find(_.mitigationId == id).flatMap(_.outcome.failures)

  /** The mitigations whose resolved scope holds `node`, in precedence order. */
  def scopingAt(response: TreeStructureResponse, node: NodeId): List[Mitigation] =
    mitigations(response).filter(m => scopeOf(response, m.id).contains(node))

  /** The badge for `node`: the mitigations scoped at it, or, where none is,
    * the ones scoped at a node below it. None when neither holds.
    *
    * {{{
    * // m targets the leaf L under the portfolio P
    * badgeAt(response, L)   // Some(Scoped(m))
    * badgeAt(response, P)   // Some(BelowOnly(m))
    * }}}
    */
  def badgeAt(response: TreeStructureResponse, node: NodeId): Option[CoverageBadge] =
    NonEmptyChunk.fromIterableOption(scopingAt(response, node)) match
      case Some(scoped) => Some(CoverageBadge.Scoped(scoped))
      case None =>
        val below = response.tree.index.descendants(node) - node
        NonEmptyChunk
          .fromIterableOption(mitigations(response).filter(m => scopeOf(response, m.id).exists(below.contains)))
          .map(CoverageBadge.BelowOnly(_))

  /** Each node `id`'s predicate names, in source order, with its name in this
    * tree version. A node the tree does not hold has no name. */
  def namedNodes(response: TreeStructureResponse, id: MitigationId): List[(NodeId, Option[SafeName.SafeName])] =
    response.tree.mitigations.find(_.id == id).toList.flatMap { m =>
      m.target match
        case MitigationTarget.Predicate(predicate) =>
          TargetingPredicate.referencedNodes(predicate).map(n => n -> response.tree.index.nodes.get(n).map(_.name))
    }
