package com.risquanter.register.services

import zio.*
import io.github.iltotore.iron.*
import com.risquanter.register.http.requests.{CreateSimulationRequest, RiskDefinition}
import com.risquanter.register.domain.data.{RiskTree, RiskTreeWithLEC, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ValidationFailed
import com.risquanter.register.repositories.RiskTreeRepository

class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  executionService: SimulationExecutionService
) extends RiskTreeService {
  
  // Config CRUD - only persist, no execution
  override def create(req: CreateSimulationRequest): Task[RiskTree] = {
    for {
      // Validate request
      _ <- validateRequest(req)
      
      // Convert to domain model (already validated, safe to use refineUnsafe)
      safeName = SafeName.SafeName(req.name.refineUnsafe)
      
      // Build RiskNode tree from flat risks array (for now)
      root = buildRiskNodeFromRequest(req)
      
      // Create RiskTree entity (id will be assigned by repo)
      riskTree = RiskTree(
        id = 0L.refineUnsafe, // repo will assign
        name = safeName,
        nTrials = req.nTrials, // nTrials is just Int, not refined
        root = root
      )
      
      // Persist
      persisted <- repo.create(riskTree)
    } yield persisted
  }
  
  override def update(id: Long, req: CreateSimulationRequest): Task[RiskTree] = {
    for {
      _ <- validateRequest(req)
      
      // Convert to domain model (already validated, safe to use refineUnsafe)
      safeName = SafeName.SafeName(req.name.refineUnsafe)
      
      root = buildRiskNodeFromRequest(req)
      
      updated <- repo.update(id, tree => tree.copy(
        name = safeName,
        nTrials = req.nTrials,
        root = root
      ))
    } yield updated
  }
  
  override def delete(id: Long): Task[RiskTree] =
    repo.delete(id)
  
  override def getAll: Task[List[RiskTree]] =
    repo.getAll
  
  override def getById(id: Long): Task[Option[RiskTree]] =
    repo.getById(id)
  
  // LEC Computation - load config and execute
  override def computeLEC(
    id: Long,
    nTrialsOverride: Option[Int],
    parallelism: Int
  ): Task[RiskTreeWithLEC] = {
    for {
      // Load config
      treeOpt <- repo.getById(id)
      tree <- ZIO.fromOption(treeOpt).orElseFail(
        ValidationFailed(List(s"RiskTree with id=$id not found"))
      )
      
      // Determine trials (use override or config)
      nTrials = nTrialsOverride.getOrElse(tree.nTrials.toInt)
      
      // Execute simulation
      result <- executionService.runTreeSimulation(
        simulationId = s"tree-${tree.id}",
        root = tree.root,
        nTrials = nTrials,
        parallelism = parallelism
      )
      
      // Convert result to LEC data
      lec <- convertResultToLEC(tree, result)
    } yield lec
  }
  
  // Helper: Validate request
  private def validateRequest(req: CreateSimulationRequest): Task[Unit] = {
    val errors = collection.mutable.ArrayBuffer[String]()
    
    if (req.name.trim.isEmpty) {
      errors += "Name cannot be empty"
    }
    
    if (req.nTrials <= 0) {
      errors += "nTrials must be positive"
    }
    
    // Must have either root or risks (not both, not neither)
    (req.root, req.risks) match {
      case (None, None) =>
        errors += "Must provide either 'root' (hierarchical) or 'risks' (flat array)"
      case (Some(_), Some(_)) =>
        errors += "Cannot provide both 'root' and 'risks' - use one format only"
      case _ => // Valid: exactly one is provided
    }
    
    // Validate flat risks array if provided
    req.risks.foreach { risksArray =>
      if (risksArray.isEmpty) {
        errors += "risks array cannot be empty"
      }
      
      risksArray.foreach { risk =>
        if (risk.probability < 0.0 || risk.probability > 1.0) {
          errors += s"Invalid probability ${risk.probability} for risk '${risk.name}'"
        }
        
        risk.distributionType match {
          case "expert" =>
            if (risk.percentiles.isEmpty || risk.quantiles.isEmpty) {
              errors += s"Expert distribution requires percentiles and quantiles for '${risk.name}'"
            }
          case "lognormal" =>
            if (risk.minLoss.isEmpty || risk.maxLoss.isEmpty) {
              errors += s"Lognormal distribution requires minLoss and maxLoss for '${risk.name}'"
            }
          case other =>
            errors += s"Unsupported distribution type '$other' for '${risk.name}'"
        }
      }
    }
    
    if (errors.nonEmpty) {
      ZIO.fail(ValidationFailed(errors.toList))
    } else {
      ZIO.unit
    }
  }
  
  // Helper: Build RiskNode from request (supports both hierarchical and flat)
  private def buildRiskNodeFromRequest(req: CreateSimulationRequest): RiskNode = {
    req.root match {
      case Some(node) =>
        // Hierarchical format - use directly
        node
        
      case None =>
        // Flat format - convert to portfolio
        val risks = req.risks.getOrElse(Array.empty[RiskDefinition])
        val leaves: Array[RiskNode] = risks.map { risk =>
          RiskLeaf(
            id = risk.name,
            name = risk.name,
            distributionType = risk.distributionType,
            probability = risk.probability,
            percentiles = risk.percentiles,
            quantiles = risk.quantiles,
            minLoss = risk.minLoss,
            maxLoss = risk.maxLoss
          ): RiskNode
        }
        
        // Wrap in root portfolio
        RiskPortfolio(
          id = "root",
          name = req.name,
          children = leaves
        )
    }
  }
  
  // Helper: Convert TreeResult to RiskTreeWithLEC
  private def convertResultToLEC(tree: RiskTree, result: com.risquanter.register.domain.data.RiskTreeResult): Task[RiskTreeWithLEC] = {
    // Extract aggregated RiskResult from tree result
    val aggregatedResult = result.result
    
    // Calculate quantiles from simulation outcomes
    val quantiles = com.risquanter.register.simulation.LECGenerator.calculateQuantiles(aggregatedResult)
    
    // Generate Vega-Lite spec for LEC visualization
    val vegaLiteSpec = if (quantiles.nonEmpty) {
      com.risquanter.register.simulation.LECGenerator.generateVegaLiteSpec(aggregatedResult)
    } else {
      None
    }
    
    // TODO: Extract individual risk data for hierarchical results
    // For now, return top-level aggregated results
    ZIO.succeed(RiskTreeWithLEC(
      riskTree = tree,
      quantiles = quantiles,
      vegaLiteSpec = vegaLiteSpec,
      individualRisks = Array.empty[com.risquanter.register.http.responses.RiskLEC]
    ))
  }
}

object RiskTreeServiceLive {
  val layer: ZLayer[RiskTreeRepository & SimulationExecutionService, Nothing, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      execService <- ZIO.service[SimulationExecutionService]
    } yield new RiskTreeServiceLive(repo, execService)
  }
}
