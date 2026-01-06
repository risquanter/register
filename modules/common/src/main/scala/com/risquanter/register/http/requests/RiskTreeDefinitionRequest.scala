package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.{RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationError
import io.github.iltotore.iron.*

/** Request DTO for defining a new risk tree
  * 
  * **Hierarchical Structure:**
  * - Provide `root` RiskNode (can be RiskLeaf or RiskPortfolio)
  * - Supports arbitrary nesting depth
  * - Each node has its own distribution and probability
  * 
  * **Distribution Modes:**
  * 1. **Expert Opinion**: `distributionType="expert"`, provide `percentiles` + `quantiles`
  * 2. **Lognormal (BCG)**: `distributionType="lognormal"`, provide `minLoss` + `maxLoss` (80% CI bounds)
  * 
  * @param name Risk tree name (plain String, validated by toDomain())
  * @param nTrials Number of Monte Carlo trials (default: 10,000)
  * @param root Hierarchical risk tree structure
  */
final case class RiskTreeDefinitionRequest(
  name: String,
  nTrials: Int = 10000,
  root: RiskNode
)

object RiskTreeDefinitionRequest {
  given codec: JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen[RiskTreeDefinitionRequest]
  
  /** 
   * Validate top-level request fields with error accumulation.
   * 
   * Note: The `root: RiskNode` field is already validated during JSON parsing
   * by the Iron-based JsonDecoders. This method validates the remaining fields
   * (name, nTrials) and provides a consistent validation entry point.
   * 
   * @return Validation with accumulated errors for name/nTrials, or validated tuple
   */
  def toDomain(req: RiskTreeDefinitionRequest): Validation[ValidationError, (SafeName.SafeName, Int, RiskNode)] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Validate request-level fields (RiskNode already validated during JSON parsing)
    val nameV = toValidation(ValidationUtil.refineName(req.name, "request.name"))
    val trialsV = toValidation(ValidationUtil.refinePositiveInt(req.nTrials, "request.nTrials"))
      .map(_ => req.nTrials)
    
    // Combine validations - accumulates name/nTrials errors
    Validation.validateWith(nameV, trialsV, Validation.succeed(req.root)) { (name, trials, root) =>
      (name, trials, root)
    }
  }
}
