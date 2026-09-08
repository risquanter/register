package com.risquanter.register.domain.data.iron

import zio.test.*
import zio.test.Assertion.*

object WorkspaceKeyHashSpec extends ZIOSpecDefault:

  def spec = suite("WorkspaceKeyHash")(
    test("toString is redacted") {
      val hash = WorkspaceKeyHash.fromString("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").toOption.get
      assertTrue(hash.toString == "WorkspaceKeyHash(***)")
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