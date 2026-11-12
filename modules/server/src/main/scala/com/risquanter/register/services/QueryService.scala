package com.risquanter.register.services

import zio.*

import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, SeedEntityId, BranchRef}
import com.risquanter.register.domain.errors.{AppError, ErrorLocation, Failure, FieldProblem, TrustZone, ValidationError}
import com.risquanter.register.http.errors.QueryProblem
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.auth.{Authority, Checked}

import vql.error.QueryError
import vql.logic.ParsedQuery

/** Service layer for vague quantifier query evaluation (ADR-028).
  *
  * Accepts a pre-parsed `ParsedQuery` (parsed at the HTTP boundary via
  * `QueryRequest.resolve()`) and evaluates it against a risk tree's
  * structural and simulation data using the `vql.typed` many-sorted pipeline.
  *
  * No parsing or raw-string validation happens here — ADR-001 §2:
  * no validation happens in service methods.
  */
trait QueryService:

  /** Evaluate a vague quantifier query against a risk tree.
    *
    * Steps:
    * 1. Load tree + ensure all leaf simulations are cached
    * 2. Build `RiskTreeKnowledgeBase` → `TypeCatalog` + `RuntimeModel`
    * 3. Call `VagueSemantics.evaluateTyped(parsed, catalog, model)`
    * 4. Map `EvaluationOutput[Value]` → `QueryResponse`
    *
    * @param wsId   Workspace that owns the target risk tree
    * @param treeId  Target risk tree identifier
    * @param parsed  Pre-parsed query (from `QueryRequest.resolve()`)
    * @param branch Target branch. Queries run against the branch head. The
    *   controller must have already verified `branch` belongs to `wsId` —
    *   see `ActiveBranch.resolve`; this method trusts it.
    * @return Query response with satisfaction result and matching node IDs
    */
  def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, seedEntityId: SeedEntityId.SeedEntityId, branch: BranchRef)(using Checked[Authority]): IO[AppError | Failure, QueryResponse]

object QueryService:

  /** The caller's refused query, located at the whole request.
    *
    * {{{
    * rejected(QueryProblem.Unparseable(Positioned(QueryPosition.Unknown(), ParseProblem.ToleranceOutOfRange())))
    * // Rejected at Client: (Whole, Query(Unparseable(Positioned(Unknown(), ToleranceOutOfRange()))))
    * }}}
    */
  def rejected(problem: QueryProblem): Failure =
    Failure.rejected(TrustZone.Client, NonEmptyChunk(ValidationError(ErrorLocation.Whole, FieldProblem.Query(problem))), Nil)

  /** The failure an engine error is: the caller's refused query, or a defect
    * when the error is a fault in how this service wired the knowledge base,
    * which no query reaches (ADR-033 §5). The defect names the engine case and
    * no caller text beyond a variable name. */
  def refused(err: QueryError): IO[Failure, Nothing] =
    QueryProblem.fromQueryError(err) match
      case Right(problem) => ZIO.fail(rejected(problem))
      case Left(reason)   =>
        ZIO.die(new IllegalStateException(s"The query engine reported a fault in this service's knowledge base: $reason"))

  def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, seedEntityId: SeedEntityId.SeedEntityId, branch: BranchRef)(using Checked[Authority]): ZIO[QueryService, AppError | Failure, QueryResponse] =
    ZIO.serviceWithZIO[QueryService](_.evaluate(wsId, treeId, parsed, seedEntityId, branch))
