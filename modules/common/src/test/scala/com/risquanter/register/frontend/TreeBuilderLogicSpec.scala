package com.risquanter.register.frontend

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError

object TreeBuilderLogicSpec extends ZIOSpecDefault:

  def spec = suite("TreeBuilderLogic")(
    test("accepts lone leaf tree with no portfolios") {
      val res = TreeBuilderLogic.preValidateTopology(Nil, List("Leaf" -> None))
      assertTrue(res.isSuccess)
    },
    test("rejects missing root when no nodes") {
      val res = TreeBuilderLogic.preValidateTopology(Nil, Nil)
      assertTrue(res.isFailure)
    },
    test("enforces single root portfolio") {
      val res = TreeBuilderLogic.preValidateTopology(List("R1" -> None, "R2" -> None), Nil)
      assertTrue(res.isFailure)
    },
    test("rejects duplicate names across portfolios and leaves") {
      val res = TreeBuilderLogic.preValidateTopology(List("P" -> None), List("P" -> Some("P")))
      assertTrue(res.isFailure)
    },
    test("rejects leaf without parent when portfolio exists") {
      val res = TreeBuilderLogic.preValidateTopology(List("Root" -> None), List("Leaf" -> None))
      assertTrue(res.isFailure)
    },
    test("validates leaf must point to portfolio") {
      val res = TreeBuilderLogic.preValidateTopology(List("Root" -> None), List("Leaf" -> Some("Missing")))
      assertTrue(res.isFailure)
    },
    test("preValidateTopology allows childless portfolio (valid mid-construction state)") {
      val res = TreeBuilderLogic.preValidateTopology(List("Root" -> None), Nil)
      assertTrue(res.isSuccess)
    },
    test("cascade collects descendants") {
      val ports = List(
        "Root" -> None,
        "Child" -> Some("Root"),
        "Grand" -> Some("Child"),
        "Sibling" -> Some("Root")
      )
      val remove = TreeBuilderLogic.collectCascade(Set("Child"), ports)
      assertTrue(remove == Set("Child", "Grand"))
    },

    // ── validateNonEmptyPortfolios ─────────────────────────────────

    test("fullValidateTopology rejects single portfolio with no children") {
      val res = TreeBuilderLogic.fullValidateTopology(
        List("Root" -> None),
        Nil
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message.contains("Every portfolio must have at least one child")), _ => false)
      )
    },
    test("fullValidateTopology accepts portfolio with a leaf child") {
      val res = TreeBuilderLogic.fullValidateTopology(
        List("Root" -> None),
        List("Leaf" -> Some("Root"))
      )
      assertTrue(res.isSuccess)
    },
    test("fullValidateTopology rejects nested portfolio tree where terminal portfolio is childless") {
      // Root ← {Child}, Child has no children → fails
      val res = TreeBuilderLogic.fullValidateTopology(
        List("Root" -> None, "Child" -> Some("Root")),
        Nil
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message.contains("Child")), _ => false)
      )
    },
    test("fullValidateTopology rejects tree where one sibling portfolio is childless") {
      // Root ← {P1 ← L1, P2 (empty)} → fails on P2
      val res = TreeBuilderLogic.fullValidateTopology(
        List("Root" -> None, "P1" -> Some("Root"), "P2" -> Some("Root")),
        List("L1" -> Some("P1"))
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message.contains("P2")), _ => false)
      )
    },
    test("fullValidateTopology accepts tree where all portfolios have children") {
      // Root ← {P1 ← L1, P2 ← L2} → valid
      val res = TreeBuilderLogic.fullValidateTopology(
        List("Root" -> None, "P1" -> Some("Root"), "P2" -> Some("Root")),
        List("L1" -> Some("P1"), "L2" -> Some("P2"))
      )
      assertTrue(res.isSuccess)
    },
    test("fullValidateTopology passes lone leaf with no portfolios") {
      // Degenerate tree: just a single leaf, zero portfolios
      val res = TreeBuilderLogic.fullValidateTopology(Nil, List("OnlyLeaf" -> None))
      assertTrue(res.isSuccess)
    },

    // ── Incremental construction sequences ────────────────────────
    // These simulate the multi-step flow that TreeBuilderState.addPortfolio /
    // addLeaf perform — maintaining lists manually (no Laminar Var needed)
    // to exercise the same preValidateTopology calls on each step.

    suite("incremental construction sequences")(

      test("portfolio then leaf with parent succeeds") {
        // Step 1: add portfolio "Root"
        val ps1 = List("Root" -> Option.empty[String])
        val ls1 = List.empty[(String, Option[String])]
        val step1 = TreeBuilderLogic.preValidateTopology(ps1, ls1)
        // Step 2: add leaf with parent = Some("Root")
        val ls2 = List("Leaf1" -> Some("Root"))
        val step2 = TreeBuilderLogic.preValidateTopology(ps1, ls2)
        assertTrue(step1.isSuccess) && assertTrue(step2.isSuccess)
      },

      test("leaf with None parent is rejected when portfolios exist") {
        // Simulates the bug scenario: parentVar reset to None after success
        val ps = List("Root" -> Option.empty[String])
        val ls = List("Leaf1" -> Some("Root"))  // first leaf already added
        // Attempt second leaf with parent = None
        val step = TreeBuilderLogic.preValidateTopology(ps, ls :+ ("Leaf2" -> None))
        assertTrue(step.isFailure) &&
        assertTrue(
          step.fold(es => es.exists(_.message.contains("must select a parent")), _ => false)
        )
      },

      test("successive leaves with explicit parent all succeed") {
        val ps = List("Root" -> Option.empty[String])
        // Simulate adding 3 leaves one at a time, each with parent = Some("Root")
        val ls1 = List("L1" -> Some("Root"))
        val ls2 = ls1 :+ ("L2" -> Some("Root"))
        val ls3 = ls2 :+ ("L3" -> Some("Root"))
        assertTrue(
          TreeBuilderLogic.preValidateTopology(ps, ls1).isSuccess &&
          TreeBuilderLogic.preValidateTopology(ps, ls2).isSuccess &&
          TreeBuilderLogic.preValidateTopology(ps, ls3).isSuccess
        )
      },

      test("duplicate leaf name is caught during construction") {
        val ps = List("Root" -> Option.empty[String])
        val ls = List("Leaf1" -> Some("Root"))
        // Attempt to add another "Leaf1"
        val step = TreeBuilderLogic.preValidateTopology(ps, ls :+ ("Leaf1" -> Some("Root")))
        assertTrue(step.isFailure) &&
        assertTrue(
          step.fold(es => es.exists(_.message.contains("Duplicate")), _ => false)
        )
      },

      test("full validate rejects incomplete tree at submit time") {
        // Construction: portfolio added but no leaves yet → pre passes
        val ps = List("Root" -> Option.empty[String], "Sub" -> Some("Root"))
        val ls = List("L1" -> Some("Root"))
        // pre allows childless "Sub" during construction
        assertTrue(TreeBuilderLogic.preValidateTopology(ps, ls).isSuccess) &&
        // full rejects at submit time — "Sub" has no children
        assertTrue(TreeBuilderLogic.fullValidateTopology(ps, ls).isFailure)
      }
    )
  )
