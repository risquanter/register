package com.risquanter.register.services

import zio.*
import zio.prelude.Validation
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.domain.data.{RiskTree, RiskTreeWithLEC, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil, PositiveInt, Probability, DistributionType, NonNegativeLong}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.configs.SimulationConfig

class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  executionService: SimulationExecutionService,
  config: SimulationConfig
) extends RiskTreeService {
  
  // Config CRUD - only persist, no execution
  override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
    for {
      // Use DTO toDomain method for comprehensive validation
      validated <- ZIO.fromEither(
        RiskTreeDefinitionRequest.toDomain(req)
          .toEither
          .left.map(errors => ValidationFailed(errors.toList))
      )
      (safeName, nTrials, rootNode) = validated
      
      // Create RiskTree entity (id will be assigned by repo)
      riskTree = RiskTree(
        id = 0L.refineUnsafe, // repo will assign
        name = safeName,
        nTrials = nTrials,
        root = rootNode
      )
      
      // Persist
      persisted <- repo.create(riskTree)
    } yield persisted
  }
  
  override def update(id: Long, req: RiskTreeDefinitionRequest): Task[RiskTree] = {
    for {
      // Use DTO toDomain method for comprehensive validation
      validated <- ZIO.fromEither(
        RiskTreeDefinitionRequest.toDomain(req)
          .toEither
          .left.map(errors => ValidationFailed(errors.toList))
      )
      (safeName, nTrials, rootNode) = validated
      
      updated <- repo.update(id, tree => tree.copy(
        name = safeName,
        nTrials = nTrials,
        root = rootNode
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
    parallelism: Int,
    depth: Int = 0,
    includeProvenance: Boolean = false
  ): Task[RiskTreeWithLEC] = {
    // Validate parameters using Iron
    val validated = for {
      validDepth <- ValidationUtil.refineNonNegativeInt(depth, "depth")
      validParallelism <- ValidationUtil.refinePositiveInt(parallelism, "parallelism")
      validTrials <- nTrialsOverride match {
        case Some(trials) => ValidationUtil.refinePositiveInt(trials, "nTrials").map(Some(_))
        case None => Right(None)
      }
    } yield (validDepth, validParallelism, validTrials)
    
    validated match {
      case Left(errors) => ZIO.fail(ValidationFailed(errors))
      case Right((validDepth, validParallelism, validTrials)) =>
        // Use config for defaults and limits
        val clampedDepth = Math.min(validDepth, config.maxTreeDepth)
        val effectiveParallelism = if (validParallelism <= 0) config.defaultParallelism else validParallelism
        
        for {
          // Load config
          treeOpt <- repo.getById(id)
          tree <- ZIO.fromOption(treeOpt).orElseFail(
            ValidationFailed(List(ValidationError(
              field = "id",
              code = com.risquanter.register.domain.errors.ValidationErrorCode.REQUIRED_FIELD,
              message = s"RiskTree with id=$id not found"
            )))
          )
          
          // Determine trials (use override or tree config or default)
          nTrials = validTrials.map(t => t: Int).getOrElse(tree.nTrials.toInt)
          
          // Execute simulation with optional provenance
          resultAndProv <- executionService.runTreeSimulation(
            simulationId = s"tree-${tree.id}",
            root = tree.root,
            nTrials = nTrials,
            parallelism = effectiveParallelism,
            includeProvenance = includeProvenance
          )
          (result, provenance) = resultAndProv
          
          // Set tree ID in provenance if present
          finalProvenance = provenance.map(_.copy(treeId = tree.id))
          
          // Convert result to LEC data with depth
          lec <- convertResultToLEC(tree, result, clampedDepth, finalProvenance)
        } yield lec
    }
  }
  
  // Helper: Convert TreeResult to RiskTreeWithLEC
  private def convertResultToLEC(
    tree: RiskTree,
    result: com.risquanter.register.domain.data.RiskTreeResult,
    depth: Int,
    provenance: Option[com.risquanter.register.domain.data.TreeProvenance] = None
  ): Task[RiskTreeWithLEC] = {
    import com.risquanter.register.simulation.{LECGenerator, VegaLiteBuilder}
    import com.risquanter.register.domain.data.{LECCurveData, LECPoint}
    
    // Build hierarchical LEC node structure
    def buildLECNode(treeResult: com.risquanter.register.domain.data.RiskTreeResult, currentDepth: Int): LECCurveData = {
      val riskResult = treeResult.result
      
      // Generate curve points
      val curvePoints = LECGenerator.generateCurvePoints(riskResult, nEntries = 100)
      val lecPoints = curvePoints.map { case (loss, prob) => LECPoint(loss, prob) }
      
      // Calculate quantiles
      val quantiles = LECGenerator.calculateQuantiles(riskResult)
      
      // Recursively build children if depth allows
      val children: Option[Vector[LECCurveData]] = treeResult match {
        case com.risquanter.register.domain.data.RiskTreeResult.Branch(_, _, childResults) if currentDepth > 0 =>
          Some(childResults.map(child => buildLECNode(child, currentDepth - 1)))
        case _ => None
      }
      
      LECCurveData(
        id = treeResult.id,
        name = riskResult.name,
        curve = lecPoints,
        quantiles = quantiles,
        children = children
      )
    }
    
    // Build root LEC node
    val lecNode = buildLECNode(result, depth)
    
    // Generate Vega-Lite spec for all visible nodes
    val vegaLiteSpec = VegaLiteBuilder.generateSpec(lecNode)
    
    // Aggregated quantiles from root node
    val rootQuantiles = lecNode.quantiles
    
    ZIO.succeed(RiskTreeWithLEC(
      riskTree = tree,
      quantiles = rootQuantiles,
      vegaLiteSpec = Some(vegaLiteSpec),
      lecCurveData = Some(lecNode),
      depth = depth,
      provenance = provenance
    ))
  }
}

object RiskTreeServiceLive {
  val layer: ZLayer[RiskTreeRepository & SimulationExecutionService & SimulationConfig, Nothing, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      execService <- ZIO.service[SimulationExecutionService]
      config <- ZIO.service[SimulationConfig]
    } yield new RiskTreeServiceLive(repo, execService, config)
  }
}
