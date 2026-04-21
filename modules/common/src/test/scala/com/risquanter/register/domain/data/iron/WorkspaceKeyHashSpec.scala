package com.risquanter.register.domain.data.iron

import zio.test.*
import zio.test.Assertion.*

object WorkspaceKeyHashSpec extends ZIOSpecDefault:

  def spec = suite("WorkspaceKeyHash")(
    test("toString is redacted") {
      val hash = WorkspaceKeyHash.fromString("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").toOption.get
      assertTrue(hash.toString == "WorkspaceKeyHash(***)")
    },
    test("fromSecret is deterministic") {
      val key = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
      val h1 = WorkspaceKeyHash.fromSecret(key)
      val h2 = WorkspaceKeyHash.fromSecret(key)
      assertTrue(h1 == h2, h1.value.length == 64)
    },
    test("fromString accepts lowercase sha256 hex") {
      val hash = WorkspaceKeyHash.fromString("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
      assertTrue(hash.isRight)
    },
    test("fromString rejects non-hex input") {
      val hash = WorkspaceKeyHash.fromString("zzzz456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
      assertTrue(hash.isLeft)
    }
  )