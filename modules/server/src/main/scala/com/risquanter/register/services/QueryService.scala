package com.risquanter.register.services

import zio.*

import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}
import com.risquanter.register.http.responses.QueryResponse

import fol.logic.ParsedQuery

/** Service layer for vague quantifier query evaluation (ADR-028).
  *
  * Accepts a pre-parsed `ParsedQuery` (parsed at the HTTP boundary via
  * `QueryRequest.resolve()`) and evaluates it against a risk tree's
  * structural and simulation data using the `fol.typed` many-sorted pipeline.
  *
  * No parsing or raw-string validation happens here — ADR-001 §4:
  * "No validation in service methods".
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
    * @return Query response with satisfaction result and matching node IDs
    */
  def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery): Task[QueryResponse]

object QueryService:

  def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery): ZIO[QueryService, Throwable, QueryResponse] =
    ZIO.serviceWithZIO[QueryService](_.evaluate(wsId, treeId, parsed))
