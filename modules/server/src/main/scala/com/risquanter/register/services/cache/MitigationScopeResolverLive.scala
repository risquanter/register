package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{RiskTree, Mitigation, MitigationTarget, MitigationSpec, LeafStageSpec, TargetingPredicate}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash}
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase.given   // Extract[NodeId]
import com.risquanter.register.http.errors.BindProblem

import vql.typed.{QueryBinder, TypedSemantics, extract}
import vql.error.TypeCheckError
import _root_.parser.FOLParser
import _root_.logic.FOLUtil

/** Memoizes the resolved scopes of one tree version. Head-only: one entry per
  * (treeId, branch) holds a revision and its scopes, and any resolve at a
  * different revision overwrites it, so revisions never accumulate. In-memory
  * `Ref` → `UIO`. One instance per workspace (`MitigationScopeResolverRegistry`).
  *
  * The revision is a stamp inside the stored value rather than part of the map
  * key. Keying on the revision would make every revision a separate entry with
  * nothing to remove it; each entry holds a full mitigation-to-node-id map, so
  * an editing session would retain one per edit and grow without a ceiling. As
  * a stamp under a slot keyed by (treeId, branch), the map is bounded by the
  * number of live tree-and-branch pairs instead.
  *
  * The memo read and write are not atomic. Last-writer-wins is deliberate and
  * safe: the entry carries the revision it was resolved at, and a read serves
  * it only on an exact revision match, so a lost write costs a recomputation
  * and never yields a scope from the wrong version.
  */
final case class MitigationScopeResolverLive(
  memo: Ref[Map[(TreeId, BranchRef), (CommitHash, ResolvedScopes)]]
) extends MitigationScopeResolver:

  override def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes] =
    val slot = (context.treeId, context.branch)
    memo.get.map(_.get(slot)).flatMap {
      case Some((rev, cached)) if rev == context.revision => ZIO.succeed(cached)
      case _ =>
        val resolved = computeAll(tree)
        memo.update(_ + (slot -> (context.revision, resolved))).as(resolved)
    }

  /** Results-free, scopes-free KB: the targeting sublanguage admits no
    * simulation symbol and no mitigation predicate, so an empty result map and
    * empty resolved scopes are correct and make the resolved scopes a pure
    * function of the tree version. */
  private def computeAll(tree: RiskTree): ResolvedScopes =
    val kb = RiskTreeKnowledgeBase(RiskTreeKnowledgeBase.schemaFor(tree), Map.empty, Map.empty)
    ResolvedScopes(tree.mitigations.map(m => m.id -> resolveOne(m, tree, kb)).toMap)

  private def resolveOne(m: Mitigation, tree: RiskTree, kb: RiskTreeKnowledgeBase): ScopeOutcome =
    val predicate = m.target match { case MitigationTarget.Predicate(p) => p }
    val domain: Set[NodeId] = m.spec match          // stage-domain restriction
      case _: LeafStageSpec              => tree.index.leafIds
      case _: MitigationSpec.ResultStage => tree.index.nodes.keySet
    satisfyingIds(predicate, kb) match
      case Right(ids)     => ScopeOutcome.Resolved(ids intersect domain)
      case Left(failures) => ScopeOutcome.Failed(failures)

  /** Re-parse (source is the only stored form) → bind against this tree version's
    * catalog → evaluate to the exact satisfying set → lift each `Value` to its
    * `NodeId`. Bind is the tree-version-relative check: it fails when a quoted
    * node was renamed or deleted, or when the catalog changed under a stored
    * predicate, and that failure is the outcome. A predicate `TargetingPredicate.create`
    * accepted re-parses, has one free variable and evaluates once bound, so a
    * failure there, and a bind error that does not translate, is a defect and
    * throws (ADR-033). */
  private def satisfyingIds(
    predicate: TargetingPredicate, kb: RiskTreeKnowledgeBase
  ): Either[NonEmptyChunk[BindProblem], Set[NodeId]] =
    FOLParser.parse(predicate.source) match
      case Left(pe) =>
        defect(s"a stored targeting predicate does not re-parse: ${pe.getClass.getSimpleName}")
      case Right(formula) =>
        FOLUtil.fvFOL(formula).distinct match
          case variable :: Nil =>
            QueryBinder.bindSatisfyingFormula(formula, variable, kb.catalog) match
              case Left(Nil) =>
                defect("the binder refused a targeting predicate with no error")
              case Left(head :: tail) =>
                Left(NonEmptyChunk(bindProblem(head), tail.map(bindProblem)*))
              case Right((bound, boundVar)) =>
                TypedSemantics.satisfyingSet(bound, boundVar, kb.model) match
                  case Left(qe)      => defect(s"a bound targeting predicate failed to evaluate: ${qe.getClass.getSimpleName}")
                  case Right(values) => Right(values.flatMap(_.extract[NodeId].toOption))
          case _ =>
            defect("a stored targeting predicate does not have exactly one free variable")

  private def bindProblem(error: TypeCheckError): BindProblem =
    BindProblem.fromTypeCheckError(error).fold(reason => defect(s"a targeting predicate's bind error does not translate: $reason"), identity)

  private def defect(reason: String): Nothing =
    throw new IllegalStateException(reason)
