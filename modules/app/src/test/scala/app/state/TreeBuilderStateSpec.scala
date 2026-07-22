package app.state

import zio.test.*
import zio.prelude.Validation

import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode, Distribution}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, SafeName, IronConstants, OccurrenceProbability, SeedVarId}
import io.github.iltotore.iron.*
import com.risquanter.register.domain.tree.TreeIndex

object TreeBuilderStateSpec extends ZIOSpecDefault:

  // ── Valid Crockford-base32 ULID constants (26 chars, no I/L/O/U) ──────
  private val rootUlid  = "01HX9ABCDE0000000000000001"
  private val childUlid = "01HX9ABCDE0000000000000002"
  private val leafUlid  = "01HX9ABCDE0000000000000003"
  private val treeUlid  = "01HX9ABCDE0000000000000004"

  private val rootId  = NodeId.fromString(rootUlid).toOption.get
  private val childId = NodeId.fromString(childUlid).toOption.get
  private val leafId  = NodeId.fromString(leafUlid).toOption.get

  // ── Test fixture helpers ──────────────────────────────────────────────

  private def mkTree(ulid: String, name: String, nodes: Seq[RiskNode], rootId: NodeId): RiskTree =
    val treeId = TreeId.fromString(ulid).toOption.getOrElse(
      throw new AssertionError(s"Invalid TreeId: $ulid")
    )
    val safeName = SafeName.fromString(name).toOption.getOrElse(
      throw new AssertionError(s"Invalid SafeName: $name")
    )
    val index = TreeIndex.fromNodeSeq(nodes).toEither.fold(
      errs => throw new AssertionError(s"Invalid tree index: $errs"),
      identity
    )
    val highWater = nodes.collect { case l: RiskLeaf => l.seedVarId }.maxByOption(_.value).getOrElse(
      SeedVarId.fromLong(1L).toOption.get
    )
    RiskTree(treeId, safeName, nodes, rootId, index, highWater)

  // ── Fixture nodes ────────────────────────────────────────────────────

  private val lognormalLeaf = RiskLeaf.unsafeApply(
    id               = leafUlid,
    name             = "Cyber Risk",
    distributionType = "lognormal",
    probability      = 0.3,
    minLoss          = Some(1000L),
    maxLoss          = Some(50000L),
    parentId         = None,
    seedVarId = 1L
  )

  private val expertLeaf = RiskLeaf.unsafeApply(
    id               = leafUlid,
    name             = "Expert Risk",
    distributionType = "expert",
    probability      = 0.2,
    percentiles      = Some(Array(0.1, 0.5, 0.9)),
    quantiles        = Some(Array(1000.0, 5000.0, 20000.0)),
    terms            = Some(3),
    parentId         = None,
    seedVarId = 2L
  )

  private val rootPortfolio = RiskPortfolio.unsafeFromStrings(
    id       = rootUlid,
    name     = "Operational Risk",
    childIds = Array(leafUlid),
    parentId = None
  )

  private val childPortfolio = RiskPortfolio.unsafeFromStrings(
    id       = childUlid,
    name     = "IT Risk",
    childIds = Array(leafUlid),
    parentId = Some(rootId)
  )

  private val leafUnderChild = RiskLeaf.unsafeApply(
    id               = leafUlid,
    name             = "Hardware Failure",
    distributionType = "lognormal",
    probability      = 0.1,
    minLoss          = Some(500L),
    maxLoss          = Some(10000L),
    parentId         = Some(childId),
    seedVarId = 3L
  )

  private val rootPortfolioWithChild = RiskPortfolio.unsafeFromStrings(
    id       = rootUlid,
    name     = "Operational Risk",
    childIds = Array(childUlid),
    parentId = None
  )

  // ── Spec ─────────────────────────────────────────────────────────────

  def spec = suite("TreeBuilderStateSpec")(
    suite("loadFromTree")(

      test("lone-leaf lognormal tree: portfoliosVar empty, leavesVar has one entry") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.portfoliosVar.now().isEmpty) &&
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent.isEmpty) &&
        assertTrue(state.leavesVar.now().head.name.value == "Cyber Risk") &&
        assertTrue(state.treeNameVar.now() == "My Tree") &&
        assertTrue(state.editingTreeId.now().isDefined)
      },

      test("lone-leaf expert tree: terms field threads correctly") {
        val tree  = mkTree(treeUlid, "Expert Tree", Seq(expertLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent.isEmpty) &&
        assertTrue(state.leavesVar.now().head.distribution.terms.contains(3)) &&
        assertTrue(state.leavesVar.now().head.distribution.distributionType == IronConstants.Expert)
      },

      test("root portfolio + leaf under root: parent resolved correctly") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 4L
        )
        val tree  = mkTree(treeUlid, "Root and Leaf", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.portfoliosVar.now().length == 1) &&
        assertTrue(state.portfoliosVar.now().head.parent.isEmpty) &&
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent.map(_.value) == Some("Operational Risk"))
      },

      test("root portfolio + child portfolio + leaf under child: hierarchy resolved") {
        val tree = mkTree(
          treeUlid, "Deep Tree",
          Seq(rootPortfolioWithChild, childPortfolio, leafUnderChild),
          rootId
        )
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.portfoliosVar.now().length == 2) &&
        assertTrue(state.portfoliosVar.now().exists(p => p.name.value == "Operational Risk" && p.parent.isEmpty)) &&
        assertTrue(state.portfoliosVar.now().exists(p => p.name.value == "IT Risk" && p.parent.map(_.value) == Some("Operational Risk"))) &&
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent.map(_.value) == Some("IT Risk"))
      },

      test("round-trip: after loadFromTree, toUpdateRequest() succeeds") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 5L
        )
        val tree  = mkTree(treeUlid, "Round Trip", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val result = state.toUpdateRequest()
        assertTrue(result.isSuccess)
      }

    ),

    // isDirty used to mean "has any content" — true for every loaded tree,
    // not just edited ones (loadFromTree populates the fields). Fixed
    // 2026-07-21: dirty now means "differs from the loaded snapshot".
    suite("isDirty")(

      test("false immediately after loadFromTree — loading a tree is not, by itself, an edit") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(!state.isDirty)
      },

      test("true after editing the tree name post-load") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        state.treeNameVar.set("Renamed Tree")
        assertTrue(state.isDirty)
      },

      test("true after the leaf set changes post-load") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val extra = state.leavesVar.now().head.copy(name = SafeName.fromString("Duplicate").toOption.get, id = None)
        state.leavesVar.update(_ :+ extra)
        assertTrue(state.isDirty)
      },

      test("false in create-mode with no content — matches pre-fix semantics for the unloaded case") {
        val state = new TreeBuilderState()
        assertTrue(!state.isDirty)
      },

      test("true in create-mode once any content is entered") {
        val state = new TreeBuilderState()
        state.treeNameVar.set("Draft")
        assertTrue(state.isDirty)
      },

      test("false again after startNewTree resets the snapshot") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        state.treeNameVar.set("Renamed Tree")
        state.startNewTree()
        assertTrue(!state.isDirty)
      },

      test("markJustSaved closes the window between a successful create and the follow-up reload landing") {
        // Reproduces: create a tree, then immediately (before the auto-reload
        // finishes) do something that reads isDirty (e.g. switch scenario
        // branch) — without markJustSaved, loadedSnapshotVar is still None,
        // so isDirty falls through to "create-mode, any content is dirty"
        // and spuriously reports true for a tree the server already saved.
        val state = new TreeBuilderState()
        state.treeNameVar.set("New Tree")
        state.addPortfolio(SafeName.fromString("Root").toOption.get, None)
        val dirtyBeforeSave = state.isDirty
        state.markJustSaved()
        val dirtyAfterSave = state.isDirty
        assertTrue(dirtyBeforeSave, !dirtyAfterSave)
      }
    ),

    suite("toUpdateRequest — node identity preservation")(

      test("loaded nodes route to the existing buckets carrying their original ids; new buckets empty") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 6L
        )
        val tree  = mkTree(treeUlid, "Identity", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        state.toUpdateRequest() match
          case Validation.Success(_, req) =>
            assertTrue(
              req.newPortfolios.isEmpty,
              req.newLeaves.isEmpty,
              req.portfolios.map(_.id).contains(rootUlid),
              req.leaves.map(_.id).contains(leafUlid)
            )
          case Validation.Failure(_, errors) =>
            assertTrue(false).label(errors.map(_.message).mkString("; "))
      },

      test("a node added after load routes to the new bucket while loaded nodes keep identity") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 7L
        )
        val tree  = mkTree(treeUlid, "Mixed", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)

        val newShape = Distribution(
          distributionType = IronConstants.Lognormal,
          minLoss          = Some(2000L),
          maxLoss          = Some(8000L),
          percentiles      = None,
          quantiles        = None,
          terms            = None
        )
        val newProb: OccurrenceProbability = 0.15
        val added = state.addLeaf("Fraud Risk", Some("Operational Risk"), newShape, newProb)

        state.toUpdateRequest() match
          case Validation.Success(_, req) =>
            assertTrue(
              added.isSuccess,
              req.leaves.map(_.id).contains(leafUlid),       // loaded leaf keeps its NodeId
              req.newLeaves.length == 1,                     // the session-added leaf is "new"
              req.newLeaves.exists(_.name == "Fraud Risk")
            )
          case Validation.Failure(_, errors) =>
            assertTrue(false).label(errors.map(_.message).mkString("; "))
      },

      test("NodeId survives the value/refine round-trip: emitted id strings are valid SafeIds") {
        // Guards the precondition the server relies on: NodeId.value re-refines to the
        // same SafeId, so the existing-bucket id is accepted, not rejected.
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 8L
        )
        val tree  = mkTree(treeUlid, "RoundTripId", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        state.toUpdateRequest() match
          case Validation.Success(_, req) =>
            val emittedIds = req.portfolios.map(_.id) ++ req.leaves.map(_.id)
            assertTrue(
              emittedIds.nonEmpty,
              emittedIds.forall(s => NodeId.fromString(s).isRight)
            )
          case Validation.Failure(_, errors) =>
            assertTrue(false).label(errors.map(_.message).mkString("; "))
      }

    ),

    suite("updateLeaf")(

      test("happy path: replaces draft in leavesVar; old name gone") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 9L
        )
        val tree  = mkTree(treeUlid, "UpdateLeaf", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        val newShape = Distribution(IronConstants.Lognormal, Some(2000L), Some(80000L), None, None, None)
        val newProb: OccurrenceProbability = 0.5
        state.updateLeaf(originalName, "Renamed Risk", Some("Operational Risk"), newShape, newProb) match
          case Validation.Success(_, _) =>
            assertTrue(
              state.leavesVar.now().length == 1,
              state.leavesVar.now().head.name.value == "Renamed Risk",
              !state.leavesVar.now().exists(_.name.value == "Cyber Risk")
            )
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      },

      test("preserves NodeId across rename") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 10L
        )
        val tree  = mkTree(treeUlid, "LeafIdPreserve", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        val newShape = Distribution(IronConstants.Lognormal, Some(2000L), Some(8000L), None, None, None)
        state.updateLeaf(originalName, "Renamed Risk", Some("Operational Risk"), newShape, 0.3) match
          case Validation.Success(_, _) =>
            assertTrue(state.leavesVar.now().head.id.map(_.value.toString).contains(leafUlid))
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      },

      test("fails when new name collides with another leaf") {
        val leaf2Ulid = "01HX9ABCDE0000000000000005"
        val leaf1 = RiskLeaf.unsafeApply(id = leafUlid,  name = "LeafA", distributionType = "lognormal", probability = 0.1, minLoss = Some(100L), maxLoss = Some(1000L), parentId = Some(rootId), seedVarId = 11L)
        val leaf2 = RiskLeaf.unsafeApply(id = leaf2Ulid, name = "LeafB", distributionType = "lognormal", probability = 0.2, minLoss = Some(100L), maxLoss = Some(1000L), parentId = Some(rootId), seedVarId = 12L)
        val root2 = RiskPortfolio.unsafeFromStrings(id = rootUlid, name = "Operational Risk", childIds = Array(leafUlid, leaf2Ulid), parentId = None)
        val tree  = mkTree(treeUlid, "Collision", Seq(root2, leaf1, leaf2), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val nameA    = state.leavesVar.now().find(_.name.value == "LeafA").get.name
        val newShape = Distribution(IronConstants.Lognormal, Some(100L), Some(1000L), None, None, None)
        assertTrue(state.updateLeaf(nameA, "LeafB", Some("Operational Risk"), newShape, 0.1).isFailure)
      }

    ),

    suite("updatePortfolio")(

      test("renames portfolio and cascade-updates leaf parent ref") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 13L
        )
        val tree  = mkTree(treeUlid, "CascadeRename", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName     = state.portfoliosVar.now().head.name
        val newPortfolioName = SafeName.fromString("RenamedPortfolio").toOption.get
        state.updatePortfolio(originalName, newPortfolioName, None) match
          case Validation.Success(_, _) =>
            assertTrue(
              state.portfoliosVar.now().head.name == newPortfolioName,
              state.leavesVar.now().head.parent.map(_.value).contains("RenamedPortfolio")
            )
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      },

      test("preserves NodeId across rename") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 14L
        )
        val tree  = mkTree(treeUlid, "PortIdPreserve", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName     = state.portfoliosVar.now().head.name
        val newPortfolioName = SafeName.fromString("RenamedPortfolio").toOption.get
        state.updatePortfolio(originalName, newPortfolioName, None) match
          case Validation.Success(_, _) =>
            assertTrue(state.portfoliosVar.now().head.id.map(_.value.toString).contains(rootUlid))
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      }

    ),

    suite("activeForm")(

      test("addLeaf: activeForm becomes Locked(Leaf(name)) on success") {
        val state = new TreeBuilderState()
        val shape = Distribution(IronConstants.Lognormal, Some(1000L), Some(5000L), None, None, None)
        val added = state.addLeaf("Fraud Risk", None, shape, 0.2)
        assertTrue(
          added.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Leaf(state.leavesVar.now().head.name))
        )
      },

      test("addPortfolio: activeForm becomes Locked(Portfolio(name)) on success") {
        val state = new TreeBuilderState()
        val added = state.addPortfolio(SafeName.fromString("Operational Risk").toOption.get, None)
        assertTrue(
          added.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Portfolio(state.portfoliosVar.now().head.name))
        )
      },

      test("updateLeaf: activeForm becomes Locked(Leaf(newName)) on success, replacing whatever mode was active") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 15L
        )
        val tree  = mkTree(treeUlid, "ClearSel", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        state.activeForm.set(FormMode.Editing(FormTarget.Leaf(originalName)))
        val newShape = Distribution(IronConstants.Lognormal, Some(2000L), Some(8000L), None, None, None)
        val result = state.updateLeaf(originalName, "Renamed", Some("Operational Risk"), newShape, 0.3)
        assertTrue(
          result.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Leaf(SafeName.fromString("Renamed").toOption.get))
        )
      },

      test("updatePortfolio: activeForm becomes Locked(Portfolio(newName)) on success") {
        val state = new TreeBuilderState()
        val added = state.addPortfolio(SafeName.fromString("Operational Risk").toOption.get, None)
        val originalName = state.portfoliosVar.now().head.name
        state.activeForm.set(FormMode.Editing(FormTarget.Portfolio(originalName)))
        val newName = SafeName.fromString("Renamed Portfolio").toOption.get
        val result = state.updatePortfolio(originalName, newName, None)
        assertTrue(
          added.isSuccess,
          result.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Portfolio(newName))
        )
      },

      test("removeNode: clears activeForm to Blank when the removed leaf was the active target") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 16L
        )
        val tree  = mkTree(treeUlid, "RemoveClearsSel", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val leafName = state.leavesVar.now().head.name
        state.activeForm.set(FormMode.Locked(FormTarget.Leaf(leafName)))
        state.removeNode(leafName.value.toString)
        assertTrue(state.activeForm.now() == FormMode.Blank)
      },

      test("removeNode: leaves activeForm untouched when a different leaf is removed") {
        val leaf1 = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 17L
        )
        val leaf2 = RiskLeaf.unsafeApply(
          id               = childUlid,
          name             = "Market Risk",
          distributionType = "lognormal",
          probability      = 0.2,
          minLoss          = Some(500L),
          maxLoss          = Some(20000L),
          parentId         = Some(rootId),
          seedVarId = 18L
        )
        val rootWithTwo = RiskPortfolio.unsafeFromStrings(id = rootUlid, name = "Operational Risk", childIds = Array(leafUlid, childUlid), parentId = None)
        val tree  = mkTree(treeUlid, "RemoveOther", Seq(rootWithTwo, leaf1, leaf2), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val cyberName  = state.leavesVar.now().find(_.name.value == "Cyber Risk").get.name
        val marketName = state.leavesVar.now().find(_.name.value == "Market Risk").get.name
        state.activeForm.set(FormMode.Locked(FormTarget.Leaf(cyberName)))
        state.removeNode(marketName.value.toString)
        assertTrue(state.activeForm.now() == FormMode.Locked(FormTarget.Leaf(cyberName)))
      },

      test("loadFromTree resets activeForm to Blank even if a node was selected beforehand (bug fix: stale form after scenario/branch switch)") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 19L
        )
        val tree  = mkTree(treeUlid, "ResetOnLoad", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.activeForm.set(FormMode.Editing(FormTarget.Leaf(SafeName.fromString("Stale Leaf").toOption.get)))
        state.loadFromTree(tree)
        assertTrue(state.activeForm.now() == FormMode.Blank)
      },

      test("startNewTree resets activeForm to Blank") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        state.activeForm.set(FormMode.Locked(FormTarget.Leaf(state.leavesVar.now().head.name)))
        state.startNewTree()
        assertTrue(state.activeForm.now() == FormMode.Blank)
      }

    ),

    suite("populateLeafForm")(

      test("expert mode: percentiles rescaled from 0-1 domain to 0-100 form scale") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Expert Risk",
          distributionType = "expert",
          probability      = 0.2,
          percentiles      = Some(Array(0.1, 0.5, 0.9)),
          quantiles        = Some(Array(1000.0, 5000.0, 20000.0)),
          terms            = Some(3),
          parentId         = Some(rootId),
          seedVarId = 19L
        )
        val tree  = mkTree(treeUlid, "PopulateExpert", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val formState = new RiskLeafFormState
        state.populateLeafForm(formState, state.leavesVar.now().head)
        // domainToDisplayPct(0.1, 0) → "10"; noise eliminated by BigDecimal rounding.
        // Comma-separated integers, space after comma, matching mkString(", ") format.
        assertTrue(
          formState.percentilesVar.now() == "10, 50, 90",
          // domainToDisplayPct(0.2, 2) → "20" (trailing zeros stripped)
          formState.probabilityVar.now() == "20",
          formState.nameVar.now() == "Expert Risk",
          formState.distributionModeVar.now() == DistributionMode.Expert
        )
      },

      test("lognormal mode: minLoss and maxLoss fields populated correctly") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Lognormal Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId),
          seedVarId = 20L
        )
        val tree  = mkTree(treeUlid, "PopulateLognormal", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val formState = new RiskLeafFormState
        state.populateLeafForm(formState, state.leavesVar.now().head)
        assertTrue(
          formState.distributionModeVar.now() == DistributionMode.Lognormal,
          formState.minLossVar.now() == "1000",
          formState.maxLossVar.now() == "50000"
        )
      }

    )
  )
