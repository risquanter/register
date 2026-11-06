package com.risquanter.register.domain.data

import zio.test.*
import zio.json.*
import com.risquanter.register.domain.data.LECNodeCurve.QuantileLevel
import com.risquanter.register.domain.data.iron.{OccurrenceProbability, given}

/** A curve's decoded figures are as trustworthy as constructed ones (ADR-038). */
object QuantileLevelSpec extends ZIOSpecDefault {

  def spec = suite("Decoding a curve's figures")(
    suite("a quantile map's key")(
      test("reads each level by its case name") {
        val decoded = """{"P90":1.0,"P95":2.0,"P99":3.0,"P995":4.0}""".fromJson[Map[QuantileLevel, Double]]
        assertTrue(decoded.map(_.keySet) == Right(QuantileLevel.values.toSet))
      },
      test("refuses a level that does not exist") {
        assertTrue("""{"P50":1.0}""".fromJson[Map[QuantileLevel, Double]].isLeft)
      }
    ),
    suite("an occurrence probability")(
      test("reads both ends of the closed interval") {
        assertTrue(
          "0.0".fromJson[OccurrenceProbability].isRight,
          "1.0".fromJson[OccurrenceProbability].isRight
        )
      },
      test("refuses a value above 1") {
        assertTrue("1.1".fromJson[OccurrenceProbability].isLeft)
      },
      test("refuses a value below 0") {
        assertTrue("-0.1".fromJson[OccurrenceProbability].isLeft)
      }
    )
  )
}
