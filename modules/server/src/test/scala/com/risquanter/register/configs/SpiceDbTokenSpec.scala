package com.risquanter.register.configs

import zio.test.*
import com.risquanter.register.domain.errors.ErrorLocation

/** The credential properties of SpiceDbToken that the type alone does not prove
  * (ADR-022).
  */
object SpiceDbTokenSpec extends ZIOSpecDefault {

  private val rawToken = "test-bearer-token"

  private def token(raw: String): SpiceDbToken =
    SpiceDbToken.fromString(raw, ErrorLocation.Whole).toOption.get

  def spec = suite("SpiceDbToken")(
    suite("R3: toString redaction")(
      test("prints the type name and never the value") {
        assertTrue(
          token(rawToken).toString == "SpiceDbToken(***)",
          !s"got ${token(rawToken)}".contains(rawToken)
        )
      }
    ),
    suite("R4: reveal")(
      test("returns the value it was built from") {
        assertTrue(token(rawToken).reveal == rawToken)
      }
    ),
    suite("R6: equals and hashCode")(
      test("equal values produce equal tokens with equal hashes") {
        assertTrue(
          token(rawToken) == token(rawToken),
          token(rawToken).hashCode == token(rawToken).hashCode
        )
      },
      test("distinct values produce unequal tokens") {
        assertTrue(token(rawToken) != token("another-bearer-token"))
      }
    ),
    suite("R7: fromString")(
      test("refuses a blank value") {
        assertTrue(SpiceDbToken.fromString("", ErrorLocation.Whole).isLeft)
      },
      test("refuses a carriage return and line feed, which could inject a header") {
        assertTrue(SpiceDbToken.fromString("token\r\nX-Injected: 1", ErrorLocation.Whole).isLeft)
      },
      test("refuses a value past the 2048 character bound") {
        assertTrue(SpiceDbToken.fromString("a" * 2049, ErrorLocation.Whole).isLeft)
      }
    )
  )
}
