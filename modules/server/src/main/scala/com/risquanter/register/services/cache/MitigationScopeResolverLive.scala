package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{RiskTree, Mitigation, MitigationTarget, MitigationSpec, TargetingPredicate}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.FolQueryFailure   // NodeReferenceSortNames (single source of truth)
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase.given   // Extract[NodeId]

import vql.typed.{QueryBinder, TypedSemantics, TypeCheckError, extract}
import vql.error.QueryError
import _root_.parser.FOLParser
import _root_.logic.FOLUtil

/** Memoizes the resolved scopes of one tree version. Head-only: one entry per
  * (treeId, branch) holds a revision and its scopes, and any resolve at a
  * different revision overwrites it, so revisions never accumulate. In-memory
  * `Ref` → `UIO`. One instance per workspace (`ScopeResolverScope`).
  *
  * The memo read and write are not atomic — last-writer-wins is a deliberate,
  * accepted trade-off, safe because the exact-revision hit guard never serves a
  * mismatched scope. Rationale and rejected alternatives: PLAN-RISKTRANSFORM
  * §8.13 (memo write policy).
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

  /** Results-free KB: the targeting sublanguage admits no simulation symbol, so
    * an empty result map is correct and makes the resolved scopes a pure
    * function of the tree version. */
  private def computeAll(tree: RiskTree): ResolvedScopes =
    val kb = RiskTreeKnowledgeBase(tree, Map.empty)
    ResolvedScopes(tree.mitigations.map(m => m.id -> resolveOne(m, tree, kb)).toMap)

  private def resolveOne(m: Mitigation, tree: RiskTree, kb: RiskTreeKnowledgeBase): ScopeOutcome =
    val predicate = m.target match { case MitigationTarget.Predicate(p) => p }
    val domain: Set[NodeId] = m.spec match          // stage-domain restriction
      case _: MitigationSpec.LeafStage   => tree.index.leafIds
      case _: MitigationSpec.ResultStage => tree.index.nodes.keySet
    satisfyingIds(predicate, kb) match
      case Right(ids)     => ScopeOutcome.Resolved(ids intersect domain)
      case Left(failures) => ScopeOutcome.Failed(failures)

  /** Re-parse (source is the only stored form) → bind against this tree version's
    * catalog → evaluate to the exact satisfying set → lift each `Value` to its
    * `NodeId`. Bind is the tree-version-relative check that fails when a quoted
    * node was renamed/deleted; parse and extract cannot fail for a
    * create-validated predicate over the node sort, so their failure arms are
    * `InternalError`. */
  private def satisfyingIds(
    predicate: TargetingPredicate, kb: RiskTreeKnowledgeBase
  ): Either[NonEmptyChunk[ScopeResolutionFailure], Set[NodeId]] =
    FOLParser.parse(predicate.source) match
      case Left(pe) =>
        Left(NonEmptyChunk(ScopeResolutionFailure.InternalError(s"re-parse failed: ${pe.message}")))
      case Right(formula) =>
        FOLUtil.fvFOL(formula).distinct match
          case variable :: Nil =>
            QueryBinder.bindSatisfyingFormula(formula, variable, kb.catalog) match
              case Left(errs) =>
                Left(
                  NonEmptyChunk
                    .fromIterableOption(errs.map(fromTypeCheckError))
                    .getOrElse(NonEmptyChunk(ScopeResolutionFailure.InternalError("empty bind-error list")))
                )
              case Right((bound, boundVar)) =>
                TypedSemantics.satisfyingSet(bound, boundVar, kb.model) match
                  case Left(qe)      => Left(NonEmptyChunk(fromQueryError(qe)))
                  case Right(values) => Right(values.flatMap(_.extract[NodeId].toOption))
          case _ =>
            // create guarantees exactly one free variable — unreachable
            Left(NonEmptyChunk(ScopeResolutionFailure.InternalError(
              "targeting predicate free-variable invariant violated")))

  /** Engine bind error → register failure. The three user-actionable variants
    * stay distinct; every structural fault a create-validated predicate cannot
    * legitimately reach collapses to `MalformedPredicate`. */
  private def fromTypeCheckError(e: TypeCheckError): ScopeResolutionFailure = e match
    case TypeCheckError.UnparseableConstant(name, sort, _) =>
      sort.value match
        case s if s == RiskTreeKnowledgeBase.NodeIdLiteralSort.value  => ScopeResolutionFailure.MalformedNodeId(name)
        case s if FolQueryFailure.NodeReferenceSortNames.contains(s)  => ScopeResolutionFailure.UnknownNode(name)
        case s                                                        => ScopeResolutionFailure.MalformedPredicate(s"unparseable literal '$name' for sort '$s'")
    case TypeCheckError.TypeMismatch(expected, actual, ctx)           => ScopeResolutionFailure.TypeError(s"$ctx: expected ${expected.value}, got ${actual.value}")
    case TypeCheckError.ConflictingTypes(name, l, r)                  => ScopeResolutionFailure.TypeError(s"variable '$name' used at ${l.value} and ${r.value}")
    case TypeCheckError.UnknownPredicate(name)                        => ScopeResolutionFailure.MalformedPredicate(s"unknown predicate '$name'")
    case TypeCheckError.UnknownFunction(name)                         => ScopeResolutionFailure.MalformedPredicate(s"unknown function '$name'")
    case TypeCheckError.ArityMismatch(sym, exp, act)                  => ScopeResolutionFailure.MalformedPredicate(s"arity mismatch for '$sym': expected $exp, got $act")
    case TypeCheckError.UnknownConstantOrLiteral(name)                => ScopeResolutionFailure.MalformedPredicate(s"unknown constant or literal '$name'")
    case TypeCheckError.UnconstrainedVar(name)                        => ScopeResolutionFailure.MalformedPredicate(s"unconstrained variable '$name'")
    case TypeCheckError.UnexpectedFreeVar(name)                       => ScopeResolutionFailure.MalformedPredicate(s"unexpected free variable '$name'")
    case TypeCheckError.TypeNotQuantifiable(name)                     => ScopeResolutionFailure.MalformedPredicate(s"non-quantifiable target sort '$name'")
    case TypeCheckError.UnboundAnswerVar(name)                        => ScopeResolutionFailure.MalformedPredicate(s"unbound answer variable '$name'")

  /** Evaluation-phase `QueryError` is an internal wiring fault for a bound
    * targeting predicate (no domain gap, no unbound var possible once bound), so
    * it is `InternalError`, not a user-facing drift reason. */
  private def fromQueryError(e: QueryError): ScopeResolutionFailure =
    ScopeResolutionFailure.InternalError(e.formatted)
