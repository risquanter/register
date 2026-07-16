package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId, ValidationMessages}
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.{idStr, nodeId, treeId}

/** Defense-in-depth layer for seed identity (PLAN-SEED-IDENTITY §5.4):
  * RiskTree's smart constructor — and therefore its JSON decoder, which routes
  * through it — rejects trees where two leaves share a seedVarId. The request
  * boundary enforces the same invariant (SeedVarIdGuardSpec); this layer covers
  * programmatically-built and store-loaded trees.
  */
object RiskTreeSeedVarIdSpec extends ZIOSpecDefault {

  private def name(s: String): SafeName.SafeName =
    SafeName.fromString(s).toOption.get

  private def leaf(idLabel: String, leafName: String, seedVarId: Long): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = idStr(idLabel),
      name = leafName,
      distributionType = "lognormal",
      probability = 0.5,
      minLoss = Some(100L),
      maxLoss = Some(1000L),
      parentId = Some(nodeId("root-pf")),
      seedVarId = seedVarId
    )

  private def tree(leaves: RiskLeaf*) = {
    val root = RiskPortfolio.unsafeFromStrings(
      id = idStr("root-pf"),
      name = "Root Portfolio",
      childIds = leaves.map(_.id.value).toArray
    )
    RiskTree.fromNodes(treeId("seed-tree"), name("Seed Tree"), root +: leaves, root.id)
  }

  def spec = suite("RiskTree seedVarId defense in depth")(
    test("accepts a tree whose leaves have distinct seedVarIds") {
      val result = tree(leaf("cyber", "Cyber Attack", 1L), leaf("flood", "Flood Risk", 2L))
      assertTrue(result.toEither.isRight)
    },
    test("rejects a tree where two leaves share a seedVarId") {
      val result = tree(leaf("cyber", "Cyber Attack", 5L), leaf("flood", "Flood Risk", 5L))
      val errs = result.toEither.swap.toOption.get
      assertTrue(
        errs.exists(e =>
          e.code == ValidationErrorCode.DUPLICATE_VALUE &&
          e.field == "nodes.seedVarId" &&
          e.message == ValidationMessages.seedVarIdInUse(5L, Seq("Cyber Attack", "Flood Risk"))
        )
      )
    },
    test("JSON decoding enforces the same invariant (decoder routes through fromNodes)") {
      val valid = tree(leaf("cyber", "Cyber Attack", 1L), leaf("flood", "Flood Risk", 2L)).toOption.get
      val jsonWithDuplicate = valid.toJson.replace("\"seedVarId\":2", "\"seedVarId\":1")
      val decoded = jsonWithDuplicate.fromJson[RiskTree]
      assertTrue(
        decoded.isLeft,
        decoded.swap.toOption.get.contains("seedVarId 1 is used by multiple nodes")
      )
    },
    test("JSON round-trip preserves each leaf's seedVarId") {
      val valid = tree(leaf("cyber", "Cyber Attack", 7L), leaf("flood", "Flood Risk", 9L)).toOption.get
      val decoded = valid.toJson.fromJson[RiskTree]
      val seedsByName = decoded.toOption.get.nodes.collect {
        case l: RiskLeaf => l.name.value -> l.seedVarId.value
      }.toMap
      assertTrue(seedsByName == Map("Cyber Attack" -> 7L, "Flood Risk" -> 9L))
    },
    suite("seedVarHighWater (§5.1)")(
      test("omitted watermark derives the current max seedVarId") {
        val result = tree(leaf("cyber", "Cyber Attack", 3L), leaf("flood", "Flood Risk", 8L))
        assertTrue(result.toOption.get.seedVarHighWater.value == 8L)
      },
      test("explicit watermark above the max is preserved (deleted-leaf history)") {
        val leaves = Seq(leaf("cyber", "Cyber Attack", 1L), leaf("flood", "Flood Risk", 2L))
        val root = RiskPortfolio.unsafeFromStrings(
          id = idStr("root-pf"), name = "Root Portfolio",
          childIds = leaves.map(_.id.value).toArray
        )
        val hw = SeedVarId.fromLong(9L).toOption.get
        val result = RiskTree.fromNodes(
          treeId("seed-tree"), name("Seed Tree"), root +: leaves, root.id, Some(hw)
        )
        assertTrue(result.toOption.get.seedVarHighWater.value == 9L)
      },
      test("explicit watermark below the max is rejected") {
        val leaves = Seq(leaf("cyber", "Cyber Attack", 1L), leaf("flood", "Flood Risk", 5L))
        val root = RiskPortfolio.unsafeFromStrings(
          id = idStr("root-pf"), name = "Root Portfolio",
          childIds = leaves.map(_.id.value).toArray
        )
        val hw = SeedVarId.fromLong(3L).toOption.get
        val result = RiskTree.fromNodes(
          treeId("seed-tree"), name("Seed Tree"), root +: leaves, root.id, Some(hw)
        )
        val errs = result.toEither.swap.toOption.get
        assertTrue(
          errs.exists(e =>
            e.code == ValidationErrorCode.CONSTRAINT_VIOLATION &&
            e.field == "seedVarHighWater" &&
            e.message.contains("below the highest assigned seedVarId 5")
          )
        )
      },
      test("JSON round-trip preserves the watermark") {
        val leaves = Seq(leaf("cyber", "Cyber Attack", 1L), leaf("flood", "Flood Risk", 2L))
        val root = RiskPortfolio.unsafeFromStrings(
          id = idStr("root-pf"), name = "Root Portfolio",
          childIds = leaves.map(_.id.value).toArray
        )
        val hw = SeedVarId.fromLong(42L).toOption.get
        val valid = RiskTree.fromNodes(
          treeId("seed-tree"), name("Seed Tree"), root +: leaves, root.id, Some(hw)
        ).toOption.get
        val decoded = valid.toJson.fromJson[RiskTree]
        assertTrue(decoded.toOption.get.seedVarHighWater.value == 42L)
      },
      test("tree JSON without seedVarHighWater fails to decode") {
        val valid = tree(leaf("cyber", "Cyber Attack", 1L), leaf("flood", "Flood Risk", 2L)).toOption.get
        val json = valid.toJson.replaceFirst(""""seedVarHighWater":\d+,?""", "").replace(",}", "}")
        assertTrue(json.fromJson[RiskTree].isLeft)
      }
    )
  )
}
