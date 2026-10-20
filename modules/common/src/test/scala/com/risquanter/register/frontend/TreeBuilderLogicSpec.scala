package com.risquanter.register.frontend

import zio.test.*

import com.risquanter.register.domain.data.iron.{SafeName, ValidationMessages}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef

object TreeBuilderLogicSpec extends ZIOSpecDefault:

  private def n(value: String): SafeName.SafeName =
    SafeName.fromString(value).getOrElse(throw new IllegalArgumentException(s"not a SafeName: $value"))

  /** A node sitting at the top of the draft, with no parent above it. */
  private def root(name: String): (SafeName.SafeName, ParentRef) =
    n(name) -> ParentRef.Root

  /** A node sitting under the named portfolio. */
  private def under(name: String, parent: String): (SafeName.SafeName, ParentRef) =
    n(name) -> ParentRef.Under(n(parent))

  def spec = suite("TreeBuilderLogic")(
    test("accepts lone leaf tree with no portfolios") {
      val res = TreeBuilderLogic.preValidateTopology(Nil, List(root("Leaf")))
      assertTrue(res.isSuccess)
    },
    test("rejects missing root when no nodes") {
      val res = TreeBuilderLogic.preValidateTopology(Nil, Nil)
      assertTrue(res.isFailure)
    },
    test("enforces single root portfolio") {
      val res = TreeBuilderLogic.preValidateTopology(List(root("R1"), root("R2")), Nil)
      assertTrue(res.isFailure)
    },
    test("a duplicate-name resubmission of the same root reports only the duplicate name, not a second root") {
      // Candidate list temporarily holds two entries both named "Ops", both root —
      // this must read as one duplicate-name error, not also as two distinct roots.
      val res = TreeBuilderLogic.preValidateTopology(List(root("Ops"), root("Ops")), Nil)
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message == ValidationMessages.duplicateNodeNames("Ops", 2)), _ => false)
      ) &&
      assertTrue(
        res.fold(es => !es.exists(_.message == ValidationMessages.multipleRootsFound), _ => false)
      )
    },
    test("rejects duplicate names across portfolios and leaves") {
      val res = TreeBuilderLogic.preValidateTopology(List(root("P")), List(under("P", "P")))
      assertTrue(res.isFailure)
    },
    test("rejects leaf without parent when portfolio exists") {
      val res = TreeBuilderLogic.preValidateTopology(List(root("Root")), List(root("Leaf")))
      assertTrue(res.isFailure)
    },
    test("validates leaf must point to portfolio") {
      val res = TreeBuilderLogic.preValidateTopology(List(root("Root")), List(under("Leaf", "Missing")))
      assertTrue(res.isFailure)
    },
    test("preValidateTopology allows childless portfolio (valid mid-construction state)") {
      val res = TreeBuilderLogic.preValidateTopology(List(root("Root")), Nil)
      assertTrue(res.isSuccess)
    },
    test("cascade collects descendants") {
      val ports = List(
        root("Root"),
        under("Child", "Root"),
        under("Grand", "Child"),
        under("Sibling", "Root")
      )
      val remove = TreeBuilderLogic.collectCascade(Set(n("Child")), ports)
      assertTrue(remove == Set(n("Child"), n("Grand")))
    },

    // ── validateNonEmptyPortfolios ─────────────────────────────────

    test("fullValidateTopology rejects single portfolio with no children") {
      val res = TreeBuilderLogic.fullValidateTopology(
        List(root("Root")),
        Nil
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(
          es => es.exists(_.message == ValidationMessages.portfoliosWithoutChildren(Seq("Root"))),
          _ => false
        )
      )
    },
    test("fullValidateTopology accepts portfolio with a leaf child") {
      val res = TreeBuilderLogic.fullValidateTopology(
        List(root("Root")),
        List(under("Leaf", "Root"))
      )
      assertTrue(res.isSuccess)
    },
    test("fullValidateTopology rejects nested portfolio tree where terminal portfolio is childless") {
      // Root ← {Child}, Child has no children → fails
      val res = TreeBuilderLogic.fullValidateTopology(
        List(root("Root"), under("Child", "Root")),
        Nil
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(
          es => es.exists(_.message == ValidationMessages.portfoliosWithoutChildren(Seq("Child"))),
          _ => false
        )
      )
    },
    test("fullValidateTopology rejects tree where one sibling portfolio is childless") {
      // Root ← {P1 ← L1, P2 (empty)} → fails on P2
      val res = TreeBuilderLogic.fullValidateTopology(
        List(root("Root"), under("P1", "Root"), under("P2", "Root")),
        List(under("L1", "P1"))
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(
          es => es.exists(_.message == ValidationMessages.portfoliosWithoutChildren(Seq("P2"))),
          _ => false
        )
      )
    },
    test("fullValidateTopology accepts tree where all portfolios have children") {
      // Root ← {P1 ← L1, P2 ← L2} → valid
      val res = TreeBuilderLogic.fullValidateTopology(
        List(root("Root"), under("P1", "Root"), under("P2", "Root")),
        List(under("L1", "P1"), under("L2", "P2"))
      )
      assertTrue(res.isSuccess)
    },
    test("fullValidateTopology passes lone leaf with no portfolios") {
      // Degenerate tree: just a single leaf, zero portfolios
      val res = TreeBuilderLogic.fullValidateTopology(Nil, List(root("OnlyLeaf")))
      assertTrue(res.isSuccess)
    },

    // ── Incremental construction sequences ────────────────────────
    // These simulate the multi-step flow that TreeBuilderState.addPortfolio /
    // addLeaf perform — maintaining lists manually (no Laminar Var needed)
    // to exercise the same preValidateTopology calls on each step.

    suite("incremental construction sequences")(

      test("portfolio then leaf with parent succeeds") {
        // Step 1: add portfolio "Root"
        val ps1 = List(root("Root"))
        val step1 = TreeBuilderLogic.preValidateTopology(ps1, Nil)
        // Step 2: add leaf under "Root"
        val ls2 = List(under("Leaf1", "Root"))
        val step2 = TreeBuilderLogic.preValidateTopology(ps1, ls2)
        assertTrue(step1.isSuccess) && assertTrue(step2.isSuccess)
      },

      test("a leaf left at the root is rejected when portfolios exist") {
        // The parent control was reset after the previous leaf was accepted.
        val ps = List(root("Root"))
        val ls = List(under("Leaf1", "Root")) // first leaf already added
        val step = TreeBuilderLogic.preValidateTopology(ps, ls :+ root("Leaf2"))
        assertTrue(step.isFailure) &&
        assertTrue(
          step.fold(es => es.exists(_.message == ValidationMessages.leafNeedsPortfolioParent), _ => false)
        )
      },

      test("successive leaves with explicit parent all succeed") {
        val ps = List(root("Root"))
        // Simulate adding 3 leaves one at a time, each under "Root"
        val ls1 = List(under("L1", "Root"))
        val ls2 = ls1 :+ under("L2", "Root")
        val ls3 = ls2 :+ under("L3", "Root")
        assertTrue(
          TreeBuilderLogic.preValidateTopology(ps, ls1).isSuccess &&
          TreeBuilderLogic.preValidateTopology(ps, ls2).isSuccess &&
          TreeBuilderLogic.preValidateTopology(ps, ls3).isSuccess
        )
      },

      test("duplicate leaf name is caught during construction") {
        val ps = List(root("Root"))
        val ls = List(under("Leaf1", "Root"))
        // Attempt to add another "Leaf1"
        val step = TreeBuilderLogic.preValidateTopology(ps, ls :+ under("Leaf1", "Root"))
        assertTrue(step.isFailure) &&
        assertTrue(
          step.fold(
            es => es.exists(_.message == ValidationMessages.duplicateNodeNames("Leaf1", 2)),
            _ => false
          )
        )
      },

      test("full validate rejects incomplete tree at submit time") {
        // Construction: portfolio added but no leaves yet → pre passes
        val ps = List(root("Root"), under("Sub", "Root"))
        val ls = List(under("L1", "Root"))
        // pre allows childless "Sub" during construction
        assertTrue(TreeBuilderLogic.preValidateTopology(ps, ls).isSuccess) &&
        // full rejects at submit time — "Sub" has no children
        assertTrue(TreeBuilderLogic.fullValidateTopology(ps, ls).isFailure)
      }
    )
  )
