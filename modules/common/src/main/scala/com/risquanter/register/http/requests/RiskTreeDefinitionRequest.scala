package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.{RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, SafeId, ValidationUtil}
import com.risquanter.register.domain.tree.NodeId
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import io.github.iltotore.iron.*

/** Request DTO for defining a new risk tree (flat node format)
  * 
  * **Flat Node Structure:**
  * - Provide `nodes` as a flat list of all RiskNode objects
  * - Each RiskPortfolio contains `childIds` (references) not embedded children
  * - Each node has optional `parentId` pointing to its parent
  * - `rootId` identifies which node is the tree root
  * 
  * **Distribution Modes:**
  * 1. **Expert Opinion**: `distributionType="expert"`, provide `percentiles` + `quantiles`
  * 2. **Lognormal (BCG)**: `distributionType="lognormal"`, provide `minLoss` + `maxLoss` (80% CI bounds)
  * 
  * **Note:** Simulation parameters (nTrials, parallelism, seeds) come from
  * SimulationConfig, not the request.
  * 
  * @param name Risk tree name (plain String, validated by toDomain())
  * @param nodes Flat list of all nodes in the tree
  * @param rootId ID of the root node
  */
final case class RiskTreeDefinitionRequest(
  name: String,
  nodes: Seq[RiskNode],
  rootId: String
)

object RiskTreeDefinitionRequest {
  given codec: JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen[RiskTreeDefinitionRequest]
  
  /** 
   * Validate request and convert to domain types.
   * 
   * Validates:
   * - name is a valid SafeName
   * - rootId is a valid SafeId
   * - rootId exists in nodes list
   * - nodes list is non-empty
   * 
   * @return Validation with accumulated errors, or validated tuple of (SafeName, Seq[RiskNode], NodeId)
   */
  def toDomain(req: RiskTreeDefinitionRequest): Validation[ValidationError, (SafeName.SafeName, Seq[RiskNode], NodeId)] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Validate name
    val nameV = toValidation(ValidationUtil.refineName(req.name, "request.name"))
    
    // Validate rootId
    val rootIdV = toValidation(SafeId.fromString(req.rootId).left.map { errors =>
      List(ValidationError(
        field = "request.rootId",
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = errors.mkString(", ")
      ))
    })
    
    // Validate nodes is non-empty
    val nodesV: Validation[ValidationError, Seq[RiskNode]] = 
      if (req.nodes.isEmpty) {
        Validation.fail(ValidationError(
          field = "request.nodes",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = "nodes array must not be empty"
        ))
      } else {
        Validation.succeed(req.nodes)
      }
    
    // Combine basic validations (cross-field validation done in validate())
    Validation.validateWith(nameV, rootIdV, nodesV) { (name, rootId, nodes) =>
      (name, nodes, rootId)
    }
  }
  
  /** 
   * Validate with comprehensive cross-field checking.
   * Returns Either for easier error handling in service layer.
   */
  def validate(req: RiskTreeDefinitionRequest): Either[List[ValidationError], (SafeName.SafeName, Seq[RiskNode], NodeId)] = {
    val basicValidation = toDomain(req)
    
    basicValidation.toEither.left.map(_.toList).flatMap { case (name, nodes, rootId) =>
      // Cross-field validation: rootId must exist in nodes
      val nodeIds = nodes.map(_.id).toSet
      if (!nodeIds.contains(rootId)) {
        Left(List(ValidationError(
          field = "request.rootId",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"rootId '${rootId.value}' not found in nodes list. Available: ${nodeIds.map(_.value).mkString(", ")}"
        )))
      } else {
        Right((name, nodes, rootId))
      }
    }
  }
}
