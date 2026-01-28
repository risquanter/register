package com.risquanter.register.http.requests

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.http.requests.RiskTreeRequests.*
import com.risquanter.register.domain.data.Distribution

object RiskTreeRequestsSpec extends ZIOSpecDefault {

  private def validLeafDef(name: String, parent: Option[String]) =
    RiskLeafDefinitionRequest(
      name = name,
      parentName = parent,
      distributionType = "lognormal",
      probability = 0.8,
      minLoss = Some(1000L),
      maxLoss = Some(5000L),
      percentiles = None,
      quantiles = None
    )

  private def validLeafUpdate(id: String, name: String, parent: Option[String]) =
    RiskLeafUpdateRequest(
      id = id,
      name = name,
      parentName = parent,
      distributionType = "lognormal",
      probability = 0.8,
      minLoss = Some(1000L),
      maxLoss = Some(5000L),
      percentiles = None,
      quantiles = None
    )

  def spec = suite("RiskTreeRequests")(
    test("resolveCreate fails when distribution is invalid") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          RiskLeafDefinitionRequest(
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
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Root")))
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
    },

    test("resolveCreate fails when names are duplicated across nodes") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Root", Some("Root"))) // duplicate name with portfolio
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected duplicate names failure")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("Duplicate names"))
      }
    },

    test("resolveCreate fails when leaf parent points to a leaf") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(
          validLeafDef("Leaf1", Some("Root")),
          validLeafDef("Leaf2", Some("Leaf1")) // parent is a leaf
        )
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected invalid leaf parent failure")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("refers to a leaf"))
      }
    },

    test("resolveCreate fails when leaf parent is missing") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Missing")))
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected missing parent failure")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("not found in portfolios"))
      }
    },

    test("resolveCreate fails when multiple roots are provided") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "AnotherRoot", parentName = None)
        ),
        leaves = Seq.empty
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected multiple roots failure")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("Multiple roots"))
      }
    },

    test("resolveCreate fails when a cycle exists") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "A", parentName = Some("B")),
          RiskPortfolioDefinitionRequest(name = "B", parentName = Some("A"))
        ),
        leaves = Seq.empty
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected cycle detection failure")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("Cycle detected"))
      }
    },

    test("resolveCreate fails when a portfolio is left empty") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "Child", parentName = Some("Root"))
        ),
        leaves = Seq.empty
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected non-empty portfolio failure")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("Portfolios cannot be left empty"))
      }
    },

    test("resolveUpdate fails when names conflict between existing and new nodes") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = "01H0R8Z3F5J2N4R8Z3F5J2N4R8", name = "Root", parentName = None)),
        leaves = Seq.empty,
        newPortfolios = Seq.empty,
        newLeaves = Seq(validLeafDef("Root", Some("Root"))) // duplicates existing portfolio name
      )

      val result = resolveUpdate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected duplicate name failure in update")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("Duplicate names"))
      }
    },

    test("resolveUpdate fails when new leaf parent is a leaf") {
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioUpdateRequest(id = "01H0R8Z3F5J2N4R8Z3F5J2N4R8", name = "Root", parentName = None)),
        leaves = Seq(validLeafUpdate("01H0R8Z3F5J2N4R8Z3F5J2N4R9", "Leaf1", Some("Root"))),
        newPortfolios = Seq.empty,
        newLeaves = Seq(validLeafDef("Leaf2", Some("Leaf1"))) // parent is a leaf
      )

      val result = resolveUpdate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, _) => assertTrue(false).label("expected invalid parent failure in update")
        case Validation.Failure(_, errors) =>
          val message = errors.map(_.message).mkString(", ")
          assertTrue(message.contains("refers to a leaf"))
      }
    }
  )
}
