package com.risquanter.register.frontend

import zio.test.*
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError

object TreeBuilderLogicSpec extends ZIOSpecDefault:

  def spec = suite("TreeBuilderLogic")(
    test("accepts lone leaf tree with no portfolios") {
      val res = TreeBuilderLogic.validateTopology(Nil, List("Leaf" -> None))
      assertTrue(res.isSuccess)
    },
    test("rejects missing root when no nodes") {
      val res = TreeBuilderLogic.validateTopology(Nil, Nil)
      assertTrue(res.isFailure)
    },
    test("enforces single root portfolio") {
      val res = TreeBuilderLogic.validateTopology(List("R1" -> None, "R2" -> None), Nil)
      assertTrue(res.isFailure)
    },
    test("rejects duplicate names across portfolios and leaves") {
      val res = TreeBuilderLogic.validateTopology(List("P" -> None), List("P" -> Some("P")))
      assertTrue(res.isFailure)
    },
    test("rejects leaf without parent when portfolio exists") {
      val res = TreeBuilderLogic.validateTopology(List("Root" -> None), List("Leaf" -> None))
      assertTrue(res.isFailure)
    },
    test("validates leaf must point to portfolio") {
      val res = TreeBuilderLogic.validateTopology(List("Root" -> None), List("Leaf" -> Some("Missing")))
      assertTrue(res.isFailure)
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

    test("rejects single portfolio with no children") {
      val res = TreeBuilderLogic.validateTopology(
        List("Root" -> None),
        Nil
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message.contains("Every portfolio must have at least one child")), _ => false)
      )
    },
    test("accepts portfolio with a leaf child") {
      val res = TreeBuilderLogic.validateTopology(
        List("Root" -> None),
        List("Leaf" -> Some("Root"))
      )
      assertTrue(res.isSuccess)
    },
    test("rejects nested portfolio tree where terminal portfolio is childless") {
      // Root ← {Child}, Child has no children → fails
      val res = TreeBuilderLogic.validateTopology(
        List("Root" -> None, "Child" -> Some("Root")),
        Nil
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message.contains("Child")), _ => false)
      )
    },
    test("rejects tree where one sibling portfolio is childless") {
      // Root ← {P1 ← L1, P2 (empty)} → fails on P2
      val res = TreeBuilderLogic.validateTopology(
        List("Root" -> None, "P1" -> Some("Root"), "P2" -> Some("Root")),
        List("L1" -> Some("P1"))
      )
      assertTrue(res.isFailure) &&
      assertTrue(
        res.fold(es => es.exists(_.message.contains("P2")), _ => false)
      )
    },
    test("accepts tree where all portfolios have children") {
      // Root ← {P1 ← L1, P2 ← L2} → valid
      val res = TreeBuilderLogic.validateTopology(
        List("Root" -> None, "P1" -> Some("Root"), "P2" -> Some("Root")),
        List("L1" -> Some("P1"), "L2" -> Some("P2"))
      )
      assertTrue(res.isSuccess)
    },
    test("lone leaf with no portfolios still passes") {
      // Degenerate tree: just a single leaf, zero portfolios
      val res = TreeBuilderLogic.validateTopology(Nil, List("OnlyLeaf" -> None))
      assertTrue(res.isSuccess)
    }
  )
