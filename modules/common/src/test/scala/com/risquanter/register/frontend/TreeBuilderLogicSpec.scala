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
    }
  )
