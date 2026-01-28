package com.risquanter.register.http.requests

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.http.requests.RiskTreeV2Requests.*
import com.risquanter.register.domain.data.Distribution

object RiskTreeV2RequestsSpec extends ZIOSpecDefault {

  def spec = suite("RiskTreeV2Requests")(
    test("resolveCreate fails when distribution is invalid") {
      val req = RiskTreeDefinitionRequestV2(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequestV2(name = "Root", parentName = None)),
        leaves = Seq(
          RiskLeafDefinitionRequestV2(
            name = "Leaf",
            parentName = Some("Root"),
            distributionType = "lognormal",
            probability = 0.8,
            minLoss = None, // invalid for lognormal
            maxLoss = None,
            percentiles = None,
            quantiles = None
          )
        )
      )

      val result: Validation[com.risquanter.register.domain.errors.ValidationError, ResolvedCreate] =
        resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) =>
          assertTrue(false).label("expected validation failure for invalid lognormal distribution")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("Lognormal mode requires minLoss and maxLoss"))
      }
    },

    test("resolveCreate returns validated distributions for leaves") {
      val req = RiskTreeDefinitionRequestV2(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequestV2(name = "Root", parentName = None)),
        leaves = Seq(
          RiskLeafDefinitionRequestV2(
            name = "Leaf",
            parentName = Some("Root"),
            distributionType = "lognormal",
            probability = 0.8,
            minLoss = Some(1000L),
            maxLoss = Some(5000L),
            percentiles = None,
            quantiles = None
          )
        )
      )

      val result: Validation[com.risquanter.register.domain.errors.ValidationError, ResolvedCreate] =
        resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, resolved) =>
          val dist: Distribution = resolved.leafDistributions.values.head
          assertTrue(
            resolved.nodes.values.count(_.kind == NodeKind.Leaf) == 1,
            dist.minLoss.exists(_ == 1000L),
            dist.maxLoss.exists(_ == 5000L)
          )
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(errors.isEmpty).label(s"resolveCreate should succeed but failed: $message")
      }
    }
  )
}
