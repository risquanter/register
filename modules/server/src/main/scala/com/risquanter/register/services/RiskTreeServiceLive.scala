package com.risquanter.register.services

import zio.*
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
    req.root.foreach { rootNode =>
      validateRiskNode(rootNode, errors)
    }
    
    // Validate flat risks array if provided
    req.risks.foreach { risksArray =>
      if (risksArray.isEmpty) {
        errors += "risks array cannot be empty"
      }
      
      risksArray.foreach { risk =>
        validateRiskDefinition(risk, errors)
      }
    }
    
    if (errors.nonEmpty) {
      ZIO.fail(ValidationFailed(errors.toList))
    } else {
      ZIO.unit
    }
  }
  
  // Helper: Validate RiskNode (hierarchical structure)
  private def validateRiskNode(node: RiskNode, errors: collection.mutable.ArrayBuffer[String]): Unit = {
    // Validate id
    if (node.id.trim.isEmpty) {
      errors += s"Risk node id cannot be empty"
    }
    
    // Validate name
    ValidationUtil.refineName(node.name) match {
      case Left(errs) => errors ++= errs.map(err => s"Node '${node.id}': $err")
      case Right(_) => // Valid
    }
    
    node match {
      case leaf: RiskLeaf =>
        // Validate probability
        ValidationUtil.refineProbability(leaf.probability) match {
          case Left(errs) => errors ++= errs.map(err => s"Risk '${leaf.id}': $err")
          case Right(_) => // Valid
        }
        
        // Validate distribution type
        ValidationUtil.refineDistributionType(leaf.distributionType) match {
          case Left(errs) => errors ++= errs.map(err => s"Risk '${leaf.id}': $err")
          case Right(distType) =>
            // Validate mode-specific fields
            if (distType == "expert") {
              if (leaf.percentiles.isEmpty || leaf.quantiles.isEmpty) {
                errors += s"Risk '${leaf.id}': Expert distribution requires percentiles and quantiles"
              }
            } else if (distType == "lognormal") {
              if (leaf.minLoss.isEmpty || leaf.maxLoss.isEmpty) {
                errors += s"Risk '${leaf.id}': Lognormal distribution requires minLoss and maxLoss"
              } else {
                // Validate minLoss and maxLoss are non-negative
                leaf.minLoss.foreach { minL =>
                  ValidationUtil.refineNonNegativeLong(minL, s"minLoss for '${leaf.id}'") match {
                    case Left(errs) => errors ++= errs
                    case Right(_) => // Valid
                  }
                }
                leaf.maxLoss.foreach { maxL =>
                  ValidationUtil.refineNonNegativeLong(maxL, s"maxLoss for '${leaf.id}'") match {
                    case Left(errs) => errors ++= errs
                    case Right(_) => // Valid
                  }
                }
              }
            }
        }
        
      case portfolio: RiskPortfolio =>
        if (portfolio.children.isEmpty) {
          errors += s"Portfolio '${portfolio.id}' cannot have empty children array"
        }
        // Recursively validate children
        portfolio.children.foreach(child => validateRiskNode(child, errors))
    }
  }
  
  // Helper: Validate RiskDefinition (flat structure)
  private def validateRiskDefinition(risk: RiskDefinition, errors: collection.mutable.ArrayBuffer[String]): Unit = {
    // Validate name
    ValidationUtil.refineName(risk.name) match {
      case Left(errs) => errors ++= errs.map(err => s"Risk '${risk.name}': $err")
      case Right(_) => // Valid
    }
    
    // Validate probability
    ValidationUtil.refineProbability(risk.probability) match {
      case Left(errs) => errors ++= errs.map(err => s"Risk '${risk.name}': $err")
      case Right(_) => // Valid
    }
    
    // Validate distribution type and mode-specific fields
    ValidationUtil.refineDistributionType(risk.distributionType) match {
      case Left(errs) => errors ++= errs.map(err => s"Risk '${risk.name}': $err")
      case Right(distType) =>
        if (distType == "expert") {
          if (risk.percentiles.isEmpty || risk.quantiles.isEmpty) {
            errors += s"Risk '${risk.name}': Expert distribution requires percentiles and quantiles"
          }
        } else if (distType == "lognormal") {
          if (risk.minLoss.isEmpty || risk.maxLoss.isEmpty) {
            errors += s"Risk '${risk.name}': Lognormal distribution requires minLoss and maxLoss"
          } else {
            // Validate minLoss and maxLoss are non-negative
            risk.minLoss.foreach { minL =>
              ValidationUtil.refineNonNegativeLong(minL, s"minLoss for '${risk.name}'") match {
                case Left(errs) => errors ++= errs
                case Right(_) => // Valid
              }
            }
            risk.maxLoss.foreach { maxL =>
              ValidationUtil.refineNonNegativeLong(maxL, s"maxLoss for '${risk.name}'") match {
                case Left(errs) => errors ++= errs
                case Right(_) => // Valid
              }
            }
          }
        }
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
