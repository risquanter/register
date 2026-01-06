package com.risquanter.register.services

import zio.*
import zio.prelude.Validation
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import com.risquanter.register.http.requests.{CreateSimulationRequest, RiskDefinition}
import com.risquanter.register.domain.data.{RiskTree, RiskTreeWithLEC, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil, PositiveInt, Probability, DistributionType, NonNegativeLong}
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
      root <- buildRiskNodeFromRequest(req)
      
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
      
      root <- buildRiskNodeFromRequest(req)
      
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
        // Clamp depth to maximum of 5
        val clampedDepth = if (validDepth > 5) 5 else validDepth
        
        for {
          // Load config
          treeOpt <- repo.getById(id)
          tree <- ZIO.fromOption(treeOpt).orElseFail(
            ValidationFailed(List(s"RiskTree with id=$id not found"))
          )
          
          // Determine trials (use override or config)
          nTrials = validTrials.map(t => t: Int).getOrElse(tree.nTrials.toInt)
          
          // Execute simulation with optional provenance
          resultAndProv <- executionService.runTreeSimulation(
            simulationId = s"tree-${tree.id}",
            root = tree.root,
            nTrials = nTrials,
            parallelism = validParallelism: Int,
            includeProvenance = includeProvenance
          )
          (result, provenance) = resultAndProv
          
          // Set tree ID in provenance if present
          finalProvenance = provenance.map(_.copy(treeId = tree.id))
          
          // Convert result to LEC data with depth
          lec <- convertResultToLEC(tree, result, clampedDepth: Int, finalProvenance)
        } yield lec
    }
  }
  
  // Helper: Validate request
  private def validateRequest(req: CreateSimulationRequest): Task[Unit] = {
    val errors = collection.mutable.ArrayBuffer[String]()
    
    // Validate name using Iron
    ValidationUtil.refineName(req.name) match {
      case Left(errs) => errors ++= errs
      case Right(_) => // Valid
    }
    
    // Validate nTrials using Iron (must be positive)
    ValidationUtil.refinePositiveInt(req.nTrials, "nTrials") match {
      case Left(errs) => errors ++= errs
      case Right(_) => // Valid
    }
    
    // Must have either root or risks (not both, not neither)
    (req.root, req.risks) match {
      case (None, None) =>
        errors += "Must provide either 'root' (hierarchical) or 'risks' (flat array)"
      case (Some(_), Some(_)) =>
        errors += "Cannot provide both 'root' and 'risks' - use one format only"
      case _ => // Valid: exactly one is provided
    }
    
    // Validate hierarchical root if provided
    // NOTE: Validation is redundant - smart constructors already validate
    // TODO Step 3.1: Remove validateRequest entirely, use smart constructors directly
    req.root.foreach { rootNode =>
      // validateRiskNode(rootNode, errors) // REMOVED: redundant validation
    }
    
    // Validate flat risks array if provided
    // NOTE: Validation is redundant - will use smart constructors when building nodes
    // TODO Step 3.1: Remove validateRequest entirely
    req.risks.foreach { risksArray =>
      if (risksArray.isEmpty) {
        errors += "risks array cannot be empty"
      }
    }
    
    if (errors.nonEmpty) {
      ZIO.fail(ValidationFailed(errors.toList))
    } else {
      ZIO.unit
    }
  }
  
  
  /** Generate deterministic ID from name for flat format.
    * 
    * Flat format is designed for convenience - users provide names only.
    * We generate IDs automatically to satisfy domain model requirements.
    * 
    * Pattern: Sanitize name + append index for uniqueness
    * Example: "Test Risk" with index 0 → "test-risk-0"
    * 
    * @param name Risk name from user
    * @param index Position in array (ensures uniqueness)
    * @return Valid SafeId-compliant identifier
    */
  private def generateIdFromName(name: String, index: Int): String = {
    val sanitized = name
      .toLowerCase
      .replaceAll("[^a-z0-9_-]", "-")  // Replace invalid chars with hyphen
      .replaceAll("-+", "-")            // Collapse multiple hyphens
      .replaceAll("^-|-$", "")          // Remove leading/trailing hyphens
      .take(25)                         // Leave room for suffix
    
    // Append index for guaranteed uniqueness within request
    s"$sanitized-$index"
  }
  
  // Helper: Build RiskNode from request (supports both hierarchical and flat)
  private def buildRiskNodeFromRequest(req: CreateSimulationRequest): Task[RiskNode] = {
    req.root match {
      case Some(node) =>
        // Hierarchical format - use directly
        ZIO.succeed(node)
        
      case None =>
        // Flat format - convert to portfolio using smart constructors
        val risks = req.risks.getOrElse(Array.empty[RiskDefinition])
        
        // Build leaves using create() smart constructor
        // Generate IDs from names since flat format is designed to be ID-free
        val leavesTask: Task[Array[RiskNode]] = ZIO.foreach(risks.zipWithIndex) { case (risk, idx) =>
          ZIO.fromEither(
            RiskLeaf.create(
              id = generateIdFromName(risk.name, idx),  // Auto-generate ID for convenience
              name = risk.name,
              distributionType = risk.distributionType,
              probability = risk.probability,
              percentiles = risk.percentiles,
              quantiles = risk.quantiles,
              minLoss = risk.minLoss,
              maxLoss = risk.maxLoss
            ).toEitherWith(errors => ValidationFailed(errors.toList))
          ).map(leaf => leaf: RiskNode)
        }
        
        // Build root portfolio using create() smart constructor
        leavesTask.flatMap { leaves =>
          ZIO.fromEither(
            RiskPortfolio.create(
              id = "root",
              name = req.name,
              children = leaves
            ).toEitherWith(errors => ValidationFailed(errors.toList))
          )
        }
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
  val layer: ZLayer[RiskTreeRepository & SimulationExecutionService, Nothing, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      execService <- ZIO.service[SimulationExecutionService]
    } yield new RiskTreeServiceLive(repo, execService)
  }
}
