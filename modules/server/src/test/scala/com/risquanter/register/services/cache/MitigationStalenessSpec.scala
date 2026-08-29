package com.risquanter.register.services.cache

import zio.Scope
import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.{
  RiskTree, RiskLeaf, RiskPortfolio, RiskNode,
  Mitigation, MitigationTarget, MitigationSpec, MitigationPrecedence, TargetingPredicate,
  RiskLeafTransform, LikelihoodTransform, DistributionTransform,
  TransformPipeline, ResultTransformSpec,
  MitigationApplication, MitigationSelection
}
import com.risquanter.register.domain.data.iron.{NodeId, SafeName, SeedVarId, ContentHash}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.testutil.TestHelpers

/** Tests for [[MitigationStaleness]] — the diagnostic predicate flagging Override
  * mitigations whose stored `overrideBaseStamp` no longer matches the anchor
  * leaf's current `LeafSimContent` hash. Pure, no Docker. Fixtures build a small
  * `root-pf → {cyber, flood}` tree and stamp overrides with hashes computed the
  * same way the cache does (`ContentHashIndex.hashOf`).
  */
object MitigationStalenessSpec extends ZIOSpecDefault with TestHelpers:

  private val cyberId = nodeId("cyber")
  private val floodId = nodeId("flood")
  private val rootId  = nodeId("root-pf")

  /** The anchor leaf, varied per case. Only `probability` and the loss
    * distribution feed `LeafSimContent`; `name`/`parentId` are excluded (DD-16). */
  private def cyber(
    prob: Double,
    maxLoss: Long = 100000L,
    name: String = "Cyber",
    parent: NodeId = rootId
  ): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = cyberId.value, name = name, distributionType = "lognormal",
      probability = prob, minLoss = Some(1000L), maxLoss = Some(maxLoss),
      parentId = Some(parent), seedVarId = 1L)

  private val flood: RiskLeaf =
    RiskLeaf.unsafeApply(
      id = floodId.value, name = "Flood", distributionType = "lognormal",
      probability = 0.3, minLoss = Some(1000L), maxLoss = Some(50000L),
      parentId = Some(rootId), seedVarId = 2L)

  private def root(childIds: NodeId*): RiskPortfolio =
    RiskPortfolio.unsafeApply(
      id = rootId.value, name = "Root", childIds = childIds.toArray, parentId = None)

  private def mkTree(root: RiskPortfolio, leaves: Seq[RiskLeaf], mits: Mitigation*): RiskTree =
    val all: Map[NodeId, RiskNode] = (root +: leaves).map(n => n.id -> n).toMap
    RiskTree(
      id     = treeId("stale-tree"),
      name   = SafeName.fromString("Stale Tree").toOption.get,
      nodes  = all.values.toSeq,
      rootId = root.id,
      index  = TreeIndex.fromNodesUnsafe(all),
      seedVarHighWater = SeedVarId.fromLong(1000L).toOption.get,
      mitigations = mits.toList
    )

  private def sname(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get
  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  /** An Override mitigation asserting probability 0.05 on `anchor`, stamped with
    * `stamp` (the caller supplies the authoring-time hash). */
  private def overrideMit(label: String, stamp: ContentHash, anchor: NodeId): Mitigation =
    Mitigation.create(
      mitigationId(label), sname(label), pred,
      MitigationSpec.LeafStage(
        RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Keep),
        Some(stamp), Some(anchor)),
      MitigationPrecedence.overrideFinal
    ).toEither.toOption.get

  private def scaleMit(label: String): Mitigation =
    Mitigation.create(
      mitigationId(label), sname(label), pred,
      MitigationSpec.LeafStage(
        RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None, None),
      MitigationPrecedence.default
    ).toEither.toOption.get

  private def resultMit(label: String): Mitigation =
    Mitigation.create(
      mitigationId(label), sname(label), pred,
      MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.CapLosses(1000000L)))),
      MitigationPrecedence.default
    ).toEither.toOption.get

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MitigationStaleness.staleOverrides")(

      test("anchor leaf's probability changed since authoring → reported stale") {
        val stamp = ContentHashIndex.hashOf(cyber(prob = 0.2))
        val m     = overrideMit("m-prob", stamp, cyberId)
        val tree  = mkTree(root(cyberId, floodId), Seq(cyber(prob = 0.9), flood), m)
        assertTrue(MitigationStaleness.staleOverrides(tree) == Set(m.id))
      },

      test("anchor leaf's loss distribution changed since authoring → reported stale") {
        val stamp = ContentHashIndex.hashOf(cyber(prob = 0.2, maxLoss = 100000L))
        val m     = overrideMit("m-loss", stamp, cyberId)
        val tree  = mkTree(root(cyberId, floodId), Seq(cyber(prob = 0.2, maxLoss = 200000L), flood), m)
        assertTrue(MitigationStaleness.staleOverrides(tree) == Set(m.id))
      },

      test("anchor leaf renamed only (no simulation-relevant field changed) → not reported (DD-16)") {
        val original = cyber(prob = 0.2, name = "Cyber")
        val renamed  = cyber(prob = 0.2, name = "Cyber Renamed")
        val stamp    = ContentHashIndex.hashOf(original)
        val m        = overrideMit("m-rename", stamp, cyberId)
        val tree     = mkTree(root(cyberId, floodId), Seq(renamed, flood), m)
        assertTrue(
          ContentHashIndex.hashOf(renamed) == ContentHashIndex.hashOf(original),
          MitigationStaleness.staleOverrides(tree).isEmpty
        )
      },

      test("stamp equal to the anchor leaf's current hash → not reported") {
        val current = cyber(prob = 0.9)
        val stamp   = ContentHashIndex.hashOf(current)
        val m       = overrideMit("m-fresh", stamp, cyberId)
        val tree    = mkTree(root(cyberId, floodId), Seq(current, flood), m)
        assertTrue(MitigationStaleness.staleOverrides(tree).isEmpty)
      },

      test("non-Override LeafStage and ResultStage mitigations are never reported") {
        val tree = mkTree(root(cyberId, floodId), Seq(cyber(prob = 0.2), flood),
          scaleMit("m-scale"), resultMit("m-result"))
        assertTrue(MitigationStaleness.staleOverrides(tree).isEmpty)
      },

      test("anchor leaf deleted → reported stale (OD-8 Option A)") {
        val stamp = ContentHashIndex.hashOf(cyber(prob = 0.2))
        val m     = overrideMit("m-orphan", stamp, cyberId)
        // Tree no longer contains the anchor leaf; root references only flood.
        val tree  = mkTree(root(floodId), Seq(flood), m)
        assertTrue(MitigationStaleness.staleOverrides(tree) == Set(m.id))
      },

      test("resolution ignores staleness — the override applies whether stale or fresh") {
        val authored = cyber(prob = 0.2)
        val stamp    = ContentHashIndex.hashOf(authored)
        val m        = overrideMit("m-apply", stamp, cyberId)
        val scopes   = Map(m.id -> Set(cyberId))

        val treeStale = mkTree(root(cyberId, floodId), Seq(cyber(prob = 0.9), flood), m)  // base moved
        val treeFresh = mkTree(root(cyberId, floodId), Seq(authored, flood), m)           // base matches

        val probIn = (t: RiskTree) =>
          MitigationApplication.effectiveTree(t, MitigationSelection.All, scopes)
            .toEither.toOption.get.index.nodes(cyberId) match
              case l: RiskLeaf      => l.probability
              case p: RiskPortfolio => sys.error(s"expected a leaf at $cyberId, got $p")

        assertTrue(
          MitigationStaleness.staleOverrides(treeStale) == Set(m.id),
          MitigationStaleness.staleOverrides(treeFresh).isEmpty,
          probIn(treeStale) == 0.05,
          probIn(treeFresh) == 0.05
        )
      }
    )

end MitigationStalenessSpec
