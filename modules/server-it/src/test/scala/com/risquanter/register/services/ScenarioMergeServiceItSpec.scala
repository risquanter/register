package com.risquanter.register.services

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.{
  RiskTree, RiskLeaf, RiskPortfolio, Mitigation,
  MitigationTarget, MitigationSpec, MitigationPrecedence, TargetingPredicate,
  RiskLeafTransform, LikelihoodTransform, DistributionTransform
}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, ContentHash, SafeName, TreeId, NodeId, MitigationId}
import com.risquanter.register.domain.errors.{MergeConflict, ValidationFailed}
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId as treeIdOf}

/**
  * Integration tests for `ScenarioMergeService` against live Irmin.
  *
  * Three layers are covered. The byte-level pre-check (ADR-032 storage
  * relation) must agree with what Irmin's native merge actually does, and the
  * merge must compensate for Irmin's silently-swallowed conflicts
  * (`IrminMergeSemanticsSpec`). The pre-merge node-name scan must refuse a
  * merge whose combined node set would repeat a name within one tree. The
  * post-merge guard must undo a merge whose result breaks an invariant the
  * pre-check does not look at.
  *
  * Every fixture writes a **real stored tree** — `meta`, real node JSON, real
  * mitigation JSON — because the post-merge guard reads every tree in the
  * workspace back through `RiskTree.fromNodes`. A workspace holding opaque
  * blobs at node paths is a workspace whose trees cannot be read, which the
  * guard is built to reject.
  *
  * Run: sbt "serverIt/testOnly *ScenarioMergeServiceItSpec"
  */
object ScenarioMergeServiceItSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private val testLayer: ZLayer[Any, Throwable, IrminClient & RiskTreeRepository & ScenarioMergeService] =
    ZLayer.make[IrminClient & RiskTreeRepository & ScenarioMergeService](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer,
      ScenarioMergeServiceLive.layer
    )

  private val treeId: TreeId = treeIdOf("merge-it-tree")
  private val rootId: NodeId = nodeId("merge-it-root")
  private val nodeA:  NodeId = nodeId("merge-it-node-a")
  private val nodeB:  NodeId = nodeId("merge-it-node-b")
  private val newX:   NodeId = nodeId("merge-it-node-x")
  private val newY:   NodeId = nodeId("merge-it-node-y")
  private val mitM: MitigationId = MitigationId(safeId("merge-it-mit-m"))
  private val mitN: MitigationId = MitigationId(safeId("merge-it-mit-n"))

  private def sname(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private def scenarioName(s: String): ScenarioName.ScenarioName =
    ScenarioName.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def branchOf(wsId: WorkspaceId, name: ScenarioName.ScenarioName): BranchRef =
    BranchRef.scenario(wsId, name).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  // ── fixtures: real nodes, real mitigations ───────────────────────────────

  /** A leaf of the seeded tree. `probability` is the byte-level lever: two
    * writes of the same node id with different probabilities differ in bytes
    * while staying a valid node, which is what the three-way merge rule reacts
    * to. `seedVarId` stays fixed per node id so the tree keeps its
    * seed-variable distinctness.
    */
  private def leaf(id: NodeId, leafName: String, probability: Double, seedVarId: Long): RiskLeaf =
    RiskLeaf.create(
      id = id.value, name = leafName, distributionType = "lognormal",
      probability = probability, minLoss = Some(1000L), maxLoss = Some(100000L),
      parentId = Some(rootId), seedVarId = seedVarId
    ).toEither.toOption.get

  /** The tree's root. `childIds` is a parameter because a fixture that adds a
    * node has to list it here too — `fromNodes` checks that every parent link
    * has a matching child link.
    */
  private def root(childIds: NodeId*): RiskPortfolio =
    RiskPortfolio.create(
      id = rootId.value, name = "Merge Root", childIds = childIds.toArray, parentId = None
    ).toEither.toOption.get

  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  private val stamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  /** A mitigation of the seeded tree. `likelihood` is this fixture's byte-level
    * lever, the same role `probability` plays for a leaf.
    */
  private def mitigation(id: MitigationId, label: String, likelihood: Double): Mitigation =
    Mitigation.create(
      id, sname(label), pred,
      MitigationSpec.LeafStage(
        // refineUnsafe rather than autoRefine: the value is a parameter here,
        // so the refinement cannot be checked at compile time.
        RiskLeafTransform(LikelihoodTransform.Override(likelihood.refineUnsafe), DistributionTransform.Keep),
        Some(stamp), Some(nodeA)),
      MitigationPrecedence.overrideFinal
    ).toEither.toOption.get

  private def seededTree(mits: Mitigation*): RiskTree =
    RiskTree.fromNodes(
      treeId, sname("Merge Tree"),
      Seq(root(nodeA, nodeB), leaf(nodeA, "Alpha", 0.2, 1L), leaf(nodeB, "Beta", 0.3, 2L)),
      rootId,
      mitigations = mits.toList
    ).toEither.toOption.get

  // ── paths ────────────────────────────────────────────────────────────────

  private def nodePath(wsId: WorkspaceId, node: NodeId): IrminPath =
    IrminPath.unsafeFrom(s"workspaces/${wsId.value}/risk-trees/${treeId.value}/nodes/${node.value}")

  private def mitPath(wsId: WorkspaceId, mit: MitigationId): IrminPath =
    IrminPath.unsafeFrom(s"workspaces/${wsId.value}/risk-trees/${treeId.value}/mitigations/${mit.value}")

  private def writeNodeBlob(wsId: WorkspaceId, node: NodeId, json: String, branch: BranchRef = BranchRef.Main) =
    IrminClient.set(nodePath(wsId, node), json, s"write ${node.value}", branch)

  private def writeLeaf(wsId: WorkspaceId, node: NodeId, leafName: String, probability: Double, seedVarId: Long, branch: BranchRef = BranchRef.Main) =
    writeNodeBlob(wsId, node, leaf(node, leafName, probability, seedVarId).toJson, branch)

  private def writeRoot(wsId: WorkspaceId, childIds: Seq[NodeId], branch: BranchRef = BranchRef.Main) =
    writeNodeBlob(wsId, rootId, root(childIds*).toJson, branch)

  private def writeMit(wsId: WorkspaceId, mit: MitigationId, label: String, likelihood: Double, branch: BranchRef = BranchRef.Main) =
    IrminClient.set(mitPath(wsId, mit), mitigation(mit, label, likelihood).toJson, s"write mit ${mit.value}", branch)

  private def mainHeadHash: ZIO[IrminClient, Throwable, String] =
    IrminClient.mainBranch.map(_.flatMap(_.head).map(_.hash))
      .someOrFail(new IllegalStateException("main has no head"))

  /** Seed a workspace with one real stored tree on main, then fork a scenario
    * from main's head. Returns the workspace, the scenario branch, and the
    * commit the fork happened at.
    */
  private def seedAndFork(wsLabel: String, scenario: ScenarioName.ScenarioName, mits: Mitigation*) =
    val wsId = WorkspaceId(safeId(wsLabel))
    for
      repo     <- ZIO.service[RiskTreeRepository]
      _        <- repo.create(wsId, seededTree(mits*), BranchRef.Main)
      mainHash <- mainHeadHash
      branch    = branchOf(wsId, scenario)
      head     <- ZIO.fromEither(CommitHash.fromString(mainHash))
                    .mapError(e => new IllegalStateException(e.mkString(";")))
      _        <- IrminClient.createBranchAt(branch, head)
    yield (wsId, branch, head)

  override def spec = suite("ScenarioMergeServiceItSpec")(

    test("edits to different nodes: preview Clean, merge succeeds and folds both sides into main") {
      val scenario = scenarioName("merge-clean")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-clean", scenario)
        _         <- writeLeaf(wsId, nodeA, "Alpha", 0.45, 1L, branch)
        _         <- writeLeaf(wsId, nodeB, "Beta", 0.55, 2L)
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        merged    <- svc.merge(wsId, scenario)
        mainHead  <- IrminClient.mainBranch.map(_.flatMap(_.head).map(_.hash))
        a         <- IrminClient.get(nodePath(wsId, nodeA))
        b         <- IrminClient.get(nodePath(wsId, nodeB))
      yield assertTrue(
        previewed == MergePreviewResult.Clean,
        mainHead.contains(merged.value),
        // each side's edit survives: the scenario's probability on A, main's on B
        a.exists(_.contains("0.45")),
        b.exists(_.contains("0.55"))
      )
    },

    test("same node edited differently on both sides: preview names exactly that node, merge refuses with MergeConflict, main untouched") {
      val scenario = scenarioName("merge-conflict")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-conflict", scenario)
        _         <- writeLeaf(wsId, nodeA, "Alpha", 0.45, 1L, branch)
        _         <- writeLeaf(wsId, nodeA, "Alpha", 0.65, 1L)
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        mergeExit <- svc.merge(wsId, scenario).exit
        a         <- IrminClient.get(nodePath(wsId, nodeA))
      yield assertTrue(
        previewed == MergePreviewResult.Conflicts(List(MergeConflictPath(
          s"risk-trees/${treeId.value}/nodes/${nodeA.value}", Some(treeId), Some(nodeA)
        ))),
        a.exists(_.contains("0.65"))
      ) && assert(mergeExit)(fails(isSubtype[MergeConflict](anything)))
    },

    test("edits to different mitigations: preview Clean, merge succeeds and folds both sides into main") {
      val scenario = scenarioName("merge-mit-clean")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-mit-clean", scenario,
                               mitigation(mitM, "m-control", 0.05), mitigation(mitN, "n-control", 0.06))
        _         <- writeMit(wsId, mitM, "m-control", 0.15, branch)
        _         <- writeMit(wsId, mitN, "n-control", 0.16)
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        merged    <- svc.merge(wsId, scenario)
        mainHead  <- IrminClient.mainBranch.map(_.flatMap(_.head).map(_.hash))
        m         <- IrminClient.get(mitPath(wsId, mitM))
        n         <- IrminClient.get(mitPath(wsId, mitN))
      yield assertTrue(
        previewed == MergePreviewResult.Clean,
        mainHead.contains(merged.value),
        m.exists(_.contains("0.15")),
        n.exists(_.contains("0.16"))
      )
    },

    test("same mitigation edited differently on both sides: preview names exactly that mitigation path, merge refuses with MergeConflict, main untouched") {
      val scenario = scenarioName("merge-mit-conflict")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-mit-conflict", scenario,
                               mitigation(mitM, "m-control", 0.05))
        _         <- writeMit(wsId, mitM, "m-control", 0.15, branch)
        _         <- writeMit(wsId, mitM, "m-control", 0.25)
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        mergeExit <- svc.merge(wsId, scenario).exit
        m         <- IrminClient.get(mitPath(wsId, mitM))
      yield assertTrue(
        previewed == MergePreviewResult.Conflicts(List(MergeConflictPath(
          s"risk-trees/${treeId.value}/mitigations/${mitM.value}", Some(treeId), None
        ))),
        m.exists(_.contains("0.25"))
      ) && assert(mergeExit)(fails(isSubtype[MergeConflict](anything)))
    },

    test("resolution as ordinary edit: bringing the conflicted node to byte agreement makes preview Clean and the merge succeed") {
      val scenario = scenarioName("merge-resolve")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-resolve", scenario)
        _          <- writeLeaf(wsId, nodeA, "Alpha", 0.45, 1L, branch)
        _          <- writeLeaf(wsId, nodeA, "Alpha", 0.65, 1L)
        svc        <- ZIO.service[ScenarioMergeService]
        conflicted <- svc.preview(wsId, scenario)
        // "[keep main]": save main's value to the scenario — an ordinary
        // branch-aware edit, the same request a resolution action sends.
        _          <- writeLeaf(wsId, nodeA, "Alpha", 0.65, 1L, branch)
        resolved   <- svc.preview(wsId, scenario)
        merged     <- svc.merge(wsId, scenario)
        mainHead   <- IrminClient.mainBranch.map(_.flatMap(_.head).map(_.hash))
        a          <- IrminClient.get(nodePath(wsId, nodeA))
      yield assertTrue(
        conflicted match { case MergePreviewResult.Conflicts(_) => true; case _ => false },
        resolved == MergePreviewResult.Clean,
        mainHead.contains(merged.value),
        a.exists(_.contains("0.65"))
      )
    },

    // ── the two invariant guards, against real Irmin ───────────────────────
    // These two break *different* invariants on purpose. The pre-merge scan
    // runs first and would otherwise catch both, which would leave the
    // post-merge guard unexercised.

    test("pre-merge scan: a combined node set that repeats a name within one tree is refused before any commit") {
      val scenario = scenarioName("merge-dup-name")
      for
        (wsId, branch, forkHead) <- seedAndFork("merge-ws-dup-name", scenario)
        // Both sides write the SAME root blob listing both new nodes, so the
        // root's own path is byte-identical and does not conflict. Each side
        // then adds only its own leaf, and the two share the name "Gamma".
        _         <- writeRoot(wsId, Seq(nodeA, nodeB, newX, newY))
        _         <- writeRoot(wsId, Seq(nodeA, nodeB, newX, newY), branch)
        _         <- writeLeaf(wsId, newX, "Gamma", 0.1, 3L)
        _         <- writeLeaf(wsId, newY, "Gamma", 0.1, 4L, branch)
        before    <- mainHeadHash
        svc       <- ZIO.service[ScenarioMergeService]
        mergeExit <- svc.merge(wsId, scenario).exit
        after     <- mainHeadHash
      yield
        val failure = mergeExit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
        assertTrue(
          failure.exists(_.getMessage.contains("duplicate node name(s)")),
          failure.exists(_.getMessage.contains("Gamma")),
          // refused before anything was written: main's head never moved
          after == before
        )
    },

    test("post-merge guard: a combined node set colliding on a seed-variable id is refused, and main never moves") {
      val scenario = scenarioName("merge-seed-collide")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-seed-collide", scenario)
        // Distinct names, so the pre-merge scan has nothing to report; the
        // same seedVarId on both new leaves, which only `fromNodes` checks.
        _         <- writeRoot(wsId, Seq(nodeA, nodeB, newX, newY))
        _         <- writeRoot(wsId, Seq(nodeA, nodeB, newX, newY), branch)
        _         <- writeLeaf(wsId, newX, "Delta", 0.1, 9L)
        _         <- writeLeaf(wsId, newY, "Epsilon", 0.1, 9L, branch)
        before    <- mainHeadHash
        svc       <- ZIO.service[ScenarioMergeService]
        mergeExit <- svc.merge(wsId, scenario).exit
        after     <- mainHeadHash
        remaining <- IrminClient.branches
      yield
        val failure = mergeExit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
        assertTrue(
          failure.exists(_.getMessage.contains("the merge was not applied")),
          // The merge is assembled on a staging branch and published only once
          // the guard passes, so main is never pointed at the invalid commit
          // and there is nothing to undo.
          after == before,
          // and the staging branch is gone, so the next merge of this scenario
          // is not refused as already running
          !remaining.exists(_.startsWith("merge-staging."))
        )
    },

    test("a successful merge leaves no staging branch behind") {
      val scenario = scenarioName("merge-staging-clean")
      for
        (wsId, branch, _) <- seedAndFork("merge-ws-staging-clean", scenario)
        _         <- writeLeaf(wsId, nodeB, "Beta renamed on the scenario", 0.2, 2L, branch)
        before    <- mainHeadHash
        svc       <- ZIO.service[ScenarioMergeService]
        head      <- svc.merge(wsId, scenario)
        after     <- mainHeadHash
        remaining <- IrminClient.branches
      yield assertTrue(
        after.contains(head.value),
        after != before,
        !remaining.exists(_.startsWith("merge-staging."))
      )
    },

    test("unknown scenario: preview reports ScenarioMissing, merge fails with ValidationFailed") {
      val scenario = scenarioName("merge-nowhere")
      val wsId     = WorkspaceId(safeId("merge-ws-missing"))
      for
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        mergeExit <- svc.merge(wsId, scenario).exit
      yield assertTrue(previewed == MergePreviewResult.ScenarioMissing) &&
        assert(mergeExit)(fails(isSubtype[ValidationFailed](anything)))
    }

  ).provideLayerShared(testLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
