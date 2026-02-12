package com.risquanter.register.http.requests

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.http.requests.RiskTreeRequests.*
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.errors.ValidationError

object RiskTreeRequestsSpec extends ZIOSpecDefault {

  /** Assert that a `Validation` failed and at least one error message contains every given substring. */
  private def assertFailsWith(result: Validation[ValidationError, ?])(substrings: String*) =
    result match
      case Validation.Failure(_, errors) =>
        val messages = errors.map(_.message)
        substrings.map(sub => assertTrue(messages.exists(_.contains(sub)))).reduce(_ && _)
      case Validation.Success(_, _) =>
        assertTrue(false).label(s"expected failure containing: ${substrings.mkString(", ")}")

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

  // Update request helpers use raw ULID string literals for `id` fields because
  // the DTO layer (RiskPortfolioUpdateRequest, RiskLeafUpdateRequest) accepts pre-validation
  // strings. Iron refinement happens inside resolveUpdate, which is the code under test.
  // Using TestHelpers.safeId here would bypass the validation path we're testing.
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

      val result = resolveCreate(req, () => safeId("generated"))
      assertFailsWith(result)("Lognormal mode requires minLoss and maxLoss")
    },

    test("resolveCreate returns validated distributions for leaves") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Root")))
      )

      val result = resolveCreate(req, () => safeId("generated"))

      result match {
        case Validation.Success(_, resolved) =>
          val dist: Distribution = resolved.leafDistributions.values.head
          assertTrue(
            resolved.nodes.values.count(_.kind == NodeKind.Leaf) == 1,
            dist.minLoss.exists(_ == 1000L),
            dist.maxLoss.exists(_ == 5000L)
          )
        case Validation.Failure(_, errors) =>
          assertTrue(false).label(s"resolveCreate should succeed but failed: ${errors.map(_.message).mkString(", ")}")
      }
    },

    test("resolveCreate fails when names are duplicated across nodes") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Root", Some("Root"))) // duplicate name with portfolio
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("Duplicate names")
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

      assertFailsWith(result)("refers to a leaf")
    },

    test("resolveCreate fails when leaf parent is missing") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
        leaves = Seq(validLeafDef("Leaf", Some("Missing")))
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("not found in portfolios")
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

      assertFailsWith(result)("Multiple roots")
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

      assertFailsWith(result)("Cycle detected")
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

      assertFailsWith(result)("Every portfolio must have at least one child")
    },

    test("resolveCreate fails naming the childless portfolio") {
      // Root ← {P1 ← L1, P2 (empty)} – error should mention P2
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "P1", parentName = Some("Root")),
          RiskPortfolioDefinitionRequest(name = "P2", parentName = Some("Root"))
        ),
        leaves = Seq(validLeafDef("L1", Some("P1")))
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertFailsWith(result)("P2", "Every portfolio must have at least one child")
    },

    test("resolveCreate succeeds when all portfolios have children") {
      val req = RiskTreeDefinitionRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioDefinitionRequest(name = "Root", parentName = None),
          RiskPortfolioDefinitionRequest(name = "P1", parentName = Some("Root")),
          RiskPortfolioDefinitionRequest(name = "P2", parentName = Some("Root"))
        ),
        leaves = Seq(validLeafDef("L1", Some("P1")), validLeafDef("L2", Some("P2")))
      )

      val result = resolveCreate(req, () => safeId("generated"))

      assertTrue(result.isSuccess)
    },

    test("resolveUpdate fails when existing portfolio becomes childless") {
      // Root (existing) with no children → should fail
      val req = RiskTreeUpdateRequest(
        name = "Tree",
        portfolios = Seq(
          RiskPortfolioUpdateRequest(id = "01H0R8Z3F5J2N4R8Z3F5J2N4R8", name = "Root", parentName = None),
          RiskPortfolioUpdateRequest(id = "01H0R8Z3F5J2N4R8Z3F5J2N4R9", name = "Child", parentName = Some("Root"))
        ),
        leaves = Seq.empty,
        newPortfolios = Seq.empty,
        newLeaves = Seq.empty
      )

      val result = resolveUpdate(req, () => safeId("generated"))

      assertFailsWith(result)("Every portfolio must have at least one child")
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

      assertFailsWith(result)("Duplicate names")
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

      assertFailsWith(result)("refers to a leaf")
    }
  )
}
