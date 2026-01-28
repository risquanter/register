package com.risquanter.register.http.requests

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.http.requests.RiskTreeV2Requests.*

object RiskTreeV2RequestsSpec extends ZIOSpecDefault {

  def spec = suite("RiskTreeV2Requests")(
    test("resolveCreate passes raw leaf payload without distribution validation") {
      val req = RiskTreeDefinitionRequestV2(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequestV2(name = "Root", parentName = None)),
        leaves = Seq(
          RiskLeafDefinitionRequestV2(
            name = "Leaf",
            parentName = Some("Root"),
            distributionType = "lognormal",
            probability = 0.8,
            minLoss = None, // invalid for lognormal, but resolveCreate should ignore
            maxLoss = None,
            percentiles = None,
            quantiles = None
          )
        )
      )

      val result: Validation[com.risquanter.register.domain.errors.ValidationError, ResolvedCreate] =
        resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, resolved) =>
          val payload = resolved.leafPayloads.values.head
          assertTrue(
            resolved.nodes.values.count(_.kind == NodeKind.Leaf) == 1,
            payload.distributionType == "lognormal",
            payload.minLoss.isEmpty,
            payload.maxLoss.isEmpty,
            payload.percentiles.isEmpty,
            payload.quantiles.isEmpty
          )
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(errors.isEmpty).label(s"resolveCreate should succeed but failed: $message")
      }
    }
  )
}
