package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, TreeId, NodeId}
import com.risquanter.register.domain.errors.{MergeConflict, ValidationFailed}
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.safeId

/**
  * Integration tests for `ScenarioMergeService` against live Irmin: the
  * byte-level pre-check (ADR-032 storage relation) must agree with what
  * Irmin's native merge actually does, and the merge must compensate for
  * Irmin's silently-swallowed conflicts (`IrminMergeSemanticsSpec`).
  *
  * Also proves the resolution mechanism end-to-end: a conflicted node becomes
  * mergeable by an ordinary edit that brings both branches to byte agreement.
  *
  * Run: sbt "serverIt/testOnly *ScenarioMergeServiceItSpec"
  */
object ScenarioMergeServiceItSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private val testLayer: ZLayer[Any, Throwable, IrminClient & ScenarioMergeService] =
    ZLayer.make[IrminClient & ScenarioMergeService](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      ScenarioMergeServiceLive.layer
    )

  private val treeId: TreeId = TreeId(safeId("merge-it-tree"))
  private val nodeA:  NodeId = NodeId(safeId("merge-it-node-a"))
  private val nodeB:  NodeId = NodeId(safeId("merge-it-node-b"))

  private def scenarioName(s: String): ScenarioName.ScenarioName =
    ScenarioName.fromString(s).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def branchOf(wsId: WorkspaceId, name: ScenarioName.ScenarioName): BranchRef =
    BranchRef.scenario(wsId, name).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  private def nodePath(wsId: WorkspaceId, node: NodeId): IrminPath =
    IrminPath.unsafeFrom(s"workspaces/${wsId.value}/risk-trees/${treeId.value}/nodes/${node.value}")

  private def writeNode(wsId: WorkspaceId, node: NodeId, value: String, branch: BranchRef = BranchRef.Main) =
    IrminClient.set(nodePath(wsId, node), value, s"write ${node.value}", branch)

  /** Seed a workspace on main (two nodes) and fork a scenario from its head. */
  private def seedAndFork(wsLabel: String, scenario: ScenarioName.ScenarioName) =
    val wsId = WorkspaceId(safeId(wsLabel))
    for
      _        <- writeNode(wsId, nodeA, "\"a0\"")
      mainHead <- writeNode(wsId, nodeB, "\"b0\"")
      branch    = branchOf(wsId, scenario)
      head     <- ZIO.fromEither(CommitHash.fromString(mainHead.hash))
                    .mapError(e => new IllegalStateException(e.mkString(";")))
      _        <- IrminClient.createBranchAt(branch, head)
    yield (wsId, branch)

  override def spec = suite("ScenarioMergeServiceItSpec")(

    test("edits to different nodes: preview Clean, merge succeeds and folds both sides into main") {
      val scenario = scenarioName("merge-clean")
      for
        (wsId, branch) <- seedAndFork("merge-ws-clean", scenario)
        _         <- writeNode(wsId, nodeA, "\"a-scenario\"", branch)
        _         <- writeNode(wsId, nodeB, "\"b-main\"")
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        merged    <- svc.merge(wsId, scenario)
        mainHead  <- IrminClient.mainBranch.map(_.flatMap(_.head).map(_.hash))
        a         <- IrminClient.get(nodePath(wsId, nodeA))
        b         <- IrminClient.get(nodePath(wsId, nodeB))
      yield assertTrue(
        previewed == MergePreviewResult.Clean,
        mainHead.contains(merged.value),
        a.contains("\"a-scenario\""),
        b.contains("\"b-main\"")
      )
    },

    test("same node edited differently on both sides: preview names exactly that node, merge refuses with MergeConflict, main untouched") {
      val scenario = scenarioName("merge-conflict")
      for
        (wsId, branch) <- seedAndFork("merge-ws-conflict", scenario)
        _         <- writeNode(wsId, nodeA, "\"a-scenario\"", branch)
        _         <- writeNode(wsId, nodeA, "\"a-main\"")
        svc       <- ZIO.service[ScenarioMergeService]
        previewed <- svc.preview(wsId, scenario)
        mergeExit <- svc.merge(wsId, scenario).exit
        a         <- IrminClient.get(nodePath(wsId, nodeA))
      yield assertTrue(
        previewed == MergePreviewResult.Conflicts(List(MergeConflictPath(
          s"risk-trees/${treeId.value}/nodes/${nodeA.value}", Some(treeId), Some(nodeA)
        ))),
        a.contains("\"a-main\"")
      ) && assert(mergeExit)(fails(isSubtype[MergeConflict](anything)))
    },

    test("resolution as ordinary edit: bringing the conflicted node to byte agreement makes preview Clean and the merge succeed") {
      val scenario = scenarioName("merge-resolve")
      for
        (wsId, branch) <- seedAndFork("merge-ws-resolve", scenario)
        _          <- writeNode(wsId, nodeA, "\"a-scenario\"", branch)
        _          <- writeNode(wsId, nodeA, "\"a-main\"")
        svc        <- ZIO.service[ScenarioMergeService]
        conflicted <- svc.preview(wsId, scenario)
        // "[keep main]": save main's value to the scenario — an ordinary
        // branch-aware edit, the same request a resolution action sends.
        _          <- writeNode(wsId, nodeA, "\"a-main\"", branch)
        resolved   <- svc.preview(wsId, scenario)
        merged     <- svc.merge(wsId, scenario)
        mainHead   <- IrminClient.mainBranch.map(_.flatMap(_.head).map(_.hash))
        a          <- IrminClient.get(nodePath(wsId, nodeA))
      yield assertTrue(
        conflicted match { case MergePreviewResult.Conflicts(_) => true; case _ => false },
        resolved == MergePreviewResult.Clean,
        mainHead.contains(merged.value),
        a.contains("\"a-main\"")
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
