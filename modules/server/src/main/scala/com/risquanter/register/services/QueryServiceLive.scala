package com.risquanter.register.services

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind

import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.errors.FolQueryFailure
import com.risquanter.register.foladapter.{RiskTreeKnowledgeBase, QueryResponseBuilder}
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.services.cache.RiskResultResolver

import fol.logic.ParsedQuery
import fol.semantics.VagueSemantics
import fol.sampling.{SamplingParams, HDRConfig}
import fol.typed.FolModel

/** Live implementation of [[QueryService]] using the `fol.typed` many-sorted pipeline.
  *
  * Dependencies:
  *   - `RiskTreeRepository` for tree lookups
  *   - `RiskResultResolver` for cache-aside simulation results
  *   - `Tracing` for OpenTelemetry spans
  */
class QueryServiceLive private (
  repo: RiskTreeRepository,
  resolver: RiskResultResolver,
  tracing: Tracing
) extends QueryService:

  /** Wrap body in an OTel span. */
  private def traced[A](name: String)(body: Task[A]): Task[A] =
    tracing.span(s"QueryService.$name", SpanKind.INTERNAL)(body)

  override def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery): Task[QueryResponse] =
    traced("evaluate") {
      for
        _ <- tracing.setAttribute("query.tree_id", treeId.value)

        // 1. Load tree
        tree <- repo.getById(wsId, treeId).flatMap {
          case Some(t) => ZIO.succeed(t)
          case None =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = "treeId",
              code = ValidationErrorCode.NOT_FOUND,
              message = s"Tree not found: ${treeId.value}"
            ))))
        }

        // 2. Ensure all node simulations are cached
        allNodeIds = tree.index.nodes.keySet
        results <- resolver.ensureCachedAll(tree, allNodeIds)
          .tapError(e => ZIO.logWarning(s"Simulation cache unavailable for tree ${treeId.value}: ${e.getMessage}"))
          .mapError { e =>
            FolQueryFailure.SimulationNotCached(treeId): Throwable
          }
        _ <- tracing.setAttribute("query.nodes_cached", results.size.toLong)

        // 3. Build knowledge base (TypeCatalog + RuntimeModel)
        kb = RiskTreeKnowledgeBase(tree, results)

        // 4. Validate catalog+model pairing (FolModel smart constructor)
        folModel <- ZIO.fromEither(
          FolModel(kb.catalog, kb.model)
        ).tapError(e => ZIO.logWarning(s"FolModel validation failed for tree ${treeId.value}: ${e.formatted}"))
         .mapError(e => FolQueryFailure.fromQueryError(e))

        // 5. Evaluate via fol.typed pipeline
        queryText = parsed.toString
        _ <- tracing.setAttribute("query.text", queryText)

        output <- ZIO.fromEither(
          VagueSemantics.evaluateTyped(
            query = parsed,
            folModel = folModel,
            answerTuple = Map.empty,
            samplingParams = SamplingParams.exact,
            hdrConfig = HDRConfig.default
          )
        ).tapError(e => ZIO.logWarning(s"FOL evaluation failed for tree ${treeId.value}: ${e.formatted}"))
         .mapError(e => FolQueryFailure.fromQueryError(e))

        // 6. Build response
        _ <- tracing.setAttribute("query.range_size", output.rangeElements.size.toLong)
        _ <- tracing.setAttribute("query.satisfying_count", output.satisfyingElements.size.toLong)
        _ <- tracing.setAttribute("query.satisfied", output.satisfied)
        _ <- tracing.setAttribute("query.proportion", output.proportion)

        response = QueryResponseBuilder.from(output, kb.nameToNodeId, queryText)
      yield response
    }

end QueryServiceLive

object QueryServiceLive:

  val layer: ZLayer[RiskTreeRepository & RiskResultResolver & Tracing, Nothing, QueryService] = ZLayer {
    for
      repo     <- ZIO.service[RiskTreeRepository]
      resolver <- ZIO.service[RiskResultResolver]
      tracing  <- ZIO.service[Tracing]
    yield QueryServiceLive(repo, resolver, tracing)
  }
