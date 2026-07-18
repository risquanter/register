package com.risquanter.register.domain.data

import zio.test.*
import zio.json.EncoderOps

/**
  * Byte-stability snapshot for the leaf cache-key preimage (DD-16).
  *
  * The leaf ContentHash is `sha256(LeafSimContent.from(leaf).toJson)`, so the
  * encoder's byte output is a storage contract: field order, number
  * rendering, and None-omission must never change silently. If one of these
  * assertions breaks, every leaf cache key has changed — a mass cache miss
  * (never incorrect results, but the contract change must be deliberate).
  */
object LeafSimContentSpec extends ZIOSpecDefault {

  def spec = suite("LeafSimContentSpec")(

    test("lognormal projection JSON is byte-stable (field order, None omitted)") {
      val leaf = RiskLeaf.unsafeApply(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ1",
        name = "cyber",
        distributionType = "lognormal",
        probability = 0.3,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        seedVarId = 7L
      )
      val json = LeafSimContent.from(leaf).toJson
      assertTrue(json ==
        """{"seedVarId":7,"probability":0.3,"distributionType":"lognormal","minLoss":1000,"maxLoss":50000}""")
    },

    test("expert projection JSON is byte-stable (arrays and terms present)") {
      val leaf = RiskLeaf.unsafeApply(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ2",
        name = "expert-risk",
        distributionType = "expert",
        probability = 0.25,
        percentiles = Some(Array(0.1, 0.5, 0.9)),
        quantiles = Some(Array(1000.0, 5000.0, 25000.0)),
        terms = Some(3),
        seedVarId = 9L
      )
      val json = LeafSimContent.from(leaf).toJson
      assertTrue(json ==
        """{"seedVarId":9,"probability":0.25,"distributionType":"expert","percentiles":[0.1,0.5,0.9],"quantiles":[1000.0,5000.0,25000.0],"terms":3}""")
    },

    test("name, ULID, and parent do not enter the projection (rename/move preserves the key)") {
      val a = RiskLeaf.unsafeApply(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ3",
        name = "original name",
        distributionType = "lognormal",
        probability = 0.3,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        seedVarId = 7L
      )
      val b = RiskLeaf.unsafeApply(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ4",
        name = "renamed elsewhere",
        distributionType = "lognormal",
        probability = 0.3,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        parentId = Some(iron.NodeId(iron.SafeId.fromString("01HZZZZZZZZZZZZZZZZZZZZZZ5").toOption.get)),
        seedVarId = 7L
      )
      assertTrue(LeafSimContent.from(a).toJson == LeafSimContent.from(b).toJson)
    },

    test("a param change changes the projection bytes") {
      val a = RiskLeaf.unsafeApply(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ6",
        name = "cyber",
        distributionType = "lognormal",
        probability = 0.3,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        seedVarId = 7L
      )
      val b = RiskLeaf.unsafeApply(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ6",
        name = "cyber",
        distributionType = "lognormal",
        probability = 0.6,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        seedVarId = 7L
      )
      assertTrue(LeafSimContent.from(a).toJson != LeafSimContent.from(b).toJson)
    }
  )
}
