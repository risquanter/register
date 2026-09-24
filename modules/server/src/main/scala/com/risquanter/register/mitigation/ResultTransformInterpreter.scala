package com.risquanter.register.mitigation

import com.risquanter.register.domain.data.{ResultTransformSpec, TransformPipeline}
import com.risquanter.register.domain.data.ResultTransformSpec.{
  ApplyDeductible, CapLosses, ScaleLosses, FilterBelowThreshold, InsurancePolicy
}

/**
 * Turns a serializable transform description into the function that executes
 * it. The spec types are shared with the browser, which builds and displays
 * them; only this interpretation of them runs, and it runs here.
 */
object ResultTransformInterpreter {

  /** Single interpreter: spec → executable transform. */
  def toTransform(spec: ResultTransformSpec): RiskResultTransform = spec match {
    case ApplyDeductible(d)       => RiskResultTransform.applyDeductible(d)
    case CapLosses(c)             => RiskResultTransform.capLosses(c)
    case ScaleLosses(f)           => RiskResultTransform.scaleLosses(f)
    case FilterBelowThreshold(t)  => RiskResultTransform.filterBelowThreshold(t)
    case InsurancePolicy(d, c)    =>
      RiskResultTransform.applyDeductible(d).andThen(RiskResultTransform.capLosses(c))
  }

  /** Law (tested): toTransform(a <> b) behaves as toTransform(a) andThen toTransform(b). */
  def toTransform(p: TransformPipeline): RiskResultTransform =
    p.steps.foldLeft(RiskResultTransform.identityTransform)((acc, s) =>
      acc.andThen(toTransform(s))
    )
}
