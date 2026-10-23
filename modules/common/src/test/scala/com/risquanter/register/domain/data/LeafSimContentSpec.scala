package com.risquanter.register.domain.data

import zio.test.*
import zio.json.EncoderOps
import com.risquanter.register.testutil.TestHelpers.{expertShape, lognormalShape, unsafeGet}

/**
  * Byte-stability snapshot for the leaf cache-key preimage.
  *
  * The leaf ContentHash is `sha256(LeafSimContent.from(leaf).toJson)`, so the
  * encoder's byte output is a storage contract: field order, number
  * rendering, and the flattening of the shape's own fields must never change
  * silently. If one of these assertions breaks, every leaf cache key has
  * changed — a mass cache miss (never incorrect results, but the contract
  * change must be deliberate).
  */
object LeafSimContentSpec extends ZIOSpecDefault {

  def spec = suite("LeafSimContentSpec")(

    test("lognormal projection JSON is byte-stable (field order, shape flattened)") {
      val leaf = unsafeGet(RiskLeaf.create(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ1",
        name = "cyber",
        probability = 0.3,
        shape = lognormalShape(1000L, 50000L),
        parentId = None,
        seedVarId = 7L
      ), "leaf")
      val json = LeafSimContent.from(leaf).toJson
      assertTrue(json ==
        """{"seedVarId":7,"probability":0.3,"shape":{"distributionType":"lognormal","minLoss":1000,"maxLoss":50000}}""")
    },

    test("expert projection JSON is byte-stable (arrays and terms present)") {
      val leaf = unsafeGet(RiskLeaf.create(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ2",
        name = "expert-risk",
        probability = 0.25,
        shape = expertShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 25000.0), Some(3)),
        parentId = None,
        seedVarId = 9L
      ), "leaf")
      val json = LeafSimContent.from(leaf).toJson
      assertTrue(json ==
        """{"seedVarId":9,"probability":0.25,"shape":{"distributionType":"expert","percentiles":[0.1,0.5,0.9],"quantiles":[1000.0,5000.0,25000.0],"terms":3}}""")
    },

    test("name, ULID, and parent do not enter the projection (rename/move preserves the key)") {
      val a = unsafeGet(RiskLeaf.create(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ3",
        name = "original name",
        probability = 0.3,
        shape = lognormalShape(1000L, 50000L),
        parentId = None,
        seedVarId = 7L
      ), "leaf")
      val b = unsafeGet(RiskLeaf.create(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ4",
        name = "renamed elsewhere",
        probability = 0.3,
        shape = lognormalShape(1000L, 50000L),
        parentId = Some(iron.NodeId(iron.SafeId.fromString("01HZZZZZZZZZZZZZZZZZZZZZZ5").toOption.get)),
        seedVarId = 7L
      ), "leaf")
      assertTrue(LeafSimContent.from(a).toJson == LeafSimContent.from(b).toJson)
    },

    test("a param change changes the projection bytes") {
      val a = unsafeGet(RiskLeaf.create(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ6",
        name = "cyber",
        probability = 0.3,
        shape = lognormalShape(1000L, 50000L),
        parentId = None,
        seedVarId = 7L
      ), "leaf")
      val b = unsafeGet(RiskLeaf.create(
        id = "01HZZZZZZZZZZZZZZZZZZZZZZ6",
        name = "cyber",
        probability = 0.6,
        shape = lognormalShape(1000L, 50000L),
        parentId = None,
        seedVarId = 7L
      ), "leaf")
      assertTrue(LeafSimContent.from(a).toJson != LeafSimContent.from(b).toJson)
    }
  )
}
