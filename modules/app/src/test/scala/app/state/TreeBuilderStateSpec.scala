package app.state

import zio.test.*
import zio.prelude.Validation
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode, DistributionShape}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, SafeName, OccurrenceProbability, SeedVarId}
import io.github.iltotore.iron.*
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName}
import com.risquanter.register.testutil.TestHelpers.{unsafeGet, metalogShape, lognormalShape}

object TreeBuilderStateSpec extends ZIOSpecDefault:

  private def n(value: String): SafeName.SafeName =
    SafeName.fromString(value).getOrElse(throw new AssertionError(s"not a SafeName: $value"))

  /** Observe a signal's current value under a throwaway owner. */
  private def currentOf[A](signal: Signal[A]): A =
    given owner: ManualOwner = new ManualOwner
    var seen: Option[A] = None
    signal.foreach(a => seen = Some(a))
    owner.killSubscriptions()
    seen.get

  /** The parent a node sitting under the named portfolio carries. */
  private def under(parent: String): ParentRef = ParentRef.Under(n(parent))

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
    val highWater = nodes.collect { case l: RiskLeaf => l.seedVarId }.maxByOption(_.value).getOrElse(
      SeedVarId.fromLong(1L).toOption.get
    )
    RiskTree.fromNodesUnsafe(treeId, safeName, nodes, rootId, Some(highWater), mitigations = Nil)

  // ── Fixture nodes ────────────────────────────────────────────────────

  private val lognormalLeaf = unsafeGet(RiskLeaf.create(
    id               = leafUlid,
    name             = "Cyber Risk",
    probability      = 0.3,
    shape            = lognormalShape(1000L, 50000L),
    parentId         = None,
    seedVarId = 1L
  ), "leaf")

  private val metalogLeaf = unsafeGet(RiskLeaf.create(
    id               = leafUlid,
    name             = "Metalog Risk",
    probability      = 0.2,
    shape            = metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 20000.0), Some(3)),
    parentId         = None,
    seedVarId = 2L
  ), "leaf")

  private val rootPortfolio = unsafeGet(RiskPortfolio.createFromStrings(
    id       = rootUlid,
    name     = "Operational Risk",
    childIds = Vector(leafUlid),
    parentId = None
  ), "portfolio")

  private val childPortfolio = unsafeGet(RiskPortfolio.createFromStrings(
    id       = childUlid,
    name     = "IT Risk",
    childIds = Vector(leafUlid),
    parentId = Some(rootId)
  ), "portfolio")

  private val leafUnderChild = unsafeGet(RiskLeaf.create(
    id               = leafUlid,
    name             = "Hardware Failure",
    probability      = 0.1,
    shape            = lognormalShape(500L, 10000L),
    parentId         = Some(childId),
    seedVarId = 3L
  ), "leaf")

  private val rootPortfolioWithChild = unsafeGet(RiskPortfolio.createFromStrings(
    id       = rootUlid,
    name     = "Operational Risk",
    childIds = Vector(childUlid),
    parentId = None
  ), "portfolio")

  // ── Spec ─────────────────────────────────────────────────────────────

  def spec = suite("TreeBuilderStateSpec")(
    suite("loadFromTree")(

      test("lone-leaf lognormal tree: portfoliosVar empty, leavesVar has one entry") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.portfoliosVar.now().isEmpty) &&
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent == ParentRef.Root) &&
        assertTrue(state.leavesVar.now().head.name.value == "Cyber Risk") &&
        assertTrue(state.treeNameVar.now() == "My Tree") &&
        assertTrue(state.editingTreeId.now().isDefined)
      },

      test("lone-leaf metalog tree: terms field threads correctly") {
        val tree  = mkTree(treeUlid, "Metalog Tree", Seq(metalogLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent == ParentRef.Root) &&
        assertTrue(state.leavesVar.now().head.shape match
          case m: DistributionShape.Metalog   => m.terms.contains(3)
          case _: DistributionShape.Lognormal => false
        ) &&
        assertTrue(state.leavesVar.now().head.shape.isInstanceOf[DistributionShape.Metalog])
      },

      test("root portfolio + leaf under root: parent resolved correctly") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 4L
        ), "leaf")
        val tree  = mkTree(treeUlid, "Root and Leaf", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        assertTrue(state.portfoliosVar.now().length == 1) &&
        assertTrue(state.portfoliosVar.now().head.parent == ParentRef.Root) &&
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent == under("Operational Risk"))
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
        assertTrue(state.portfoliosVar.now().exists(p => p.name.value == "Operational Risk" && p.parent == ParentRef.Root)) &&
        assertTrue(state.portfoliosVar.now().exists(p => p.name.value == "IT Risk" && p.parent == under("Operational Risk"))) &&
        assertTrue(state.leavesVar.now().length == 1) &&
        assertTrue(state.leavesVar.now().head.parent == under("IT Risk"))
      },

      test("round-trip: after loadFromTree, toUpdateRequest() succeeds") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 5L
        ), "leaf")
        val tree  = mkTree(treeUlid, "Round Trip", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val result = state.toUpdateRequest()
        assertTrue(result.isSuccess)
      }

    ),

    // isDirty means "differs from the loaded snapshot", not "has any content":
    // loadFromTree populates the fields, and that alone is not an edit.
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

      test("isDirtySignal gives the answer isDirty gives, before and after an edit post-load") {
        val tree  = mkTree(treeUlid, "My Tree", Seq(lognormalLeaf), leafId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val beforeEdit = (currentOf(state.isDirtySignal), state.isDirty)
        state.treeNameVar.set("Renamed Tree")
        val afterEdit = (currentOf(state.isDirtySignal), state.isDirty)
        assertTrue(beforeEdit == (false, false), afterEdit == (true, true))
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
        state.addPortfolio(n("Root"), ParentRef.Root)
        val dirtyBeforeSave = state.isDirty
        state.markJustSaved()
        val dirtyAfterSave = state.isDirty
        assertTrue(dirtyBeforeSave, !dirtyAfterSave)
      }
    ),

    suite("toUpdateRequest — node identity preservation")(

      test("loaded nodes route to the existing buckets carrying their original ids; new buckets empty") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 6L
        ), "leaf")
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
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 7L
        ), "leaf")
        val tree  = mkTree(treeUlid, "Mixed", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)

        val newShape = lognormalShape(2000L, 8000L)
        val newProb: OccurrenceProbability = 0.15
        val added = state.addLeaf(n("Fraud Risk"), under("Operational Risk"), newShape, newProb)

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
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 8L
        ), "leaf")
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
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 9L
        ), "leaf")
        val tree  = mkTree(treeUlid, "UpdateLeaf", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        val newShape = lognormalShape(2000L, 80000L)
        val newProb: OccurrenceProbability = 0.5
        state.updateLeaf(originalName, n("Renamed Risk"), under("Operational Risk"), newShape, newProb) match
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
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 10L
        ), "leaf")
        val tree  = mkTree(treeUlid, "LeafIdPreserve", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        val newShape = lognormalShape(2000L, 8000L)
        state.updateLeaf(originalName, n("Renamed Risk"), under("Operational Risk"), newShape, 0.3) match
          case Validation.Success(_, _) =>
            assertTrue(state.leavesVar.now().head.id.map(_.value.toString).contains(leafUlid))
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      },

      test("fails when new name collides with another leaf") {
        val leaf2Ulid = "01HX9ABCDE0000000000000005"
        val leaf1 = unsafeGet(RiskLeaf.create(id = leafUlid,  name = "LeafA", probability = 0.1, shape = lognormalShape(100L, 1000L), parentId = Some(rootId), seedVarId = 11L), "leaf")
        val leaf2 = unsafeGet(RiskLeaf.create(id = leaf2Ulid, name = "LeafB", probability = 0.2, shape = lognormalShape(100L, 1000L), parentId = Some(rootId), seedVarId = 12L), "leaf")
        val root2 = unsafeGet(RiskPortfolio.createFromStrings(id = rootUlid, name = "Operational Risk", childIds = Vector(leafUlid, leaf2Ulid), parentId = None), "portfolio")
        val tree  = mkTree(treeUlid, "Collision", Seq(root2, leaf1, leaf2), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val nameA    = state.leavesVar.now().find(_.name.value == "LeafA").get.name
        val newShape = lognormalShape(100L, 1000L)
        assertTrue(state.updateLeaf(nameA, n("LeafB"), under("Operational Risk"), newShape, 0.1).isFailure)
      }

    ),

    suite("updatePortfolio")(

      test("renames portfolio and cascade-updates leaf parent ref") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 13L
        ), "leaf")
        val tree  = mkTree(treeUlid, "CascadeRename", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName     = state.portfoliosVar.now().head.name
        val newPortfolioName = SafeName.fromString("RenamedPortfolio").toOption.get
        state.updatePortfolio(originalName, newPortfolioName, ParentRef.Root) match
          case Validation.Success(_, _) =>
            assertTrue(
              state.portfoliosVar.now().head.name == newPortfolioName,
              state.leavesVar.now().head.parent == under("RenamedPortfolio")
            )
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      },

      test("preserves NodeId across rename") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 14L
        ), "leaf")
        val tree  = mkTree(treeUlid, "PortIdPreserve", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName     = state.portfoliosVar.now().head.name
        val newPortfolioName = SafeName.fromString("RenamedPortfolio").toOption.get
        state.updatePortfolio(originalName, newPortfolioName, ParentRef.Root) match
          case Validation.Success(_, _) =>
            assertTrue(state.portfoliosVar.now().head.id.map(_.value.toString).contains(rootUlid))
          case Validation.Failure(_, errs) =>
            assertTrue(false).label(errs.map(_.message).mkString("; "))
      }

    ),

    suite("activeForm")(

      test("addLeaf: activeForm becomes Locked(Leaf(name)) on success") {
        val state = new TreeBuilderState()
        val shape = lognormalShape(1000L, 5000L)
        val added = state.addLeaf(n("Fraud Risk"), ParentRef.Root, shape, 0.2)
        assertTrue(
          added.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Leaf(state.leavesVar.now().head.name))
        )
      },

      test("addPortfolio: activeForm becomes Locked(Portfolio(name)) on success") {
        val state = new TreeBuilderState()
        val added = state.addPortfolio(n("Operational Risk"), ParentRef.Root)
        assertTrue(
          added.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Portfolio(state.portfoliosVar.now().head.name))
        )
      },

      test("updateLeaf: activeForm becomes Locked(Leaf(newName)) on success, replacing whatever mode was active") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 15L
        ), "leaf")
        val tree  = mkTree(treeUlid, "ClearSel", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        state.activeForm.set(FormMode.Editing(FormTarget.Leaf(originalName)))
        val newShape = lognormalShape(2000L, 8000L)
        val result = state.updateLeaf(originalName, n("Renamed"), under("Operational Risk"), newShape, 0.3)
        assertTrue(
          result.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Leaf(SafeName.fromString("Renamed").toOption.get))
        )
      },

      test("updatePortfolio: activeForm becomes Locked(Portfolio(newName)) on success") {
        val state = new TreeBuilderState()
        val added = state.addPortfolio(n("Operational Risk"), ParentRef.Root)
        val originalName = state.portfoliosVar.now().head.name
        state.activeForm.set(FormMode.Editing(FormTarget.Portfolio(originalName)))
        val newName = SafeName.fromString("Renamed Portfolio").toOption.get
        val result = state.updatePortfolio(originalName, newName, ParentRef.Root)
        assertTrue(
          added.isSuccess,
          result.isSuccess,
          state.activeForm.now() == FormMode.Locked(FormTarget.Portfolio(newName))
        )
      },

      test("removeNode: clears activeForm to Blank when the removed leaf was the active target") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 16L
        ), "leaf")
        val tree  = mkTree(treeUlid, "RemoveClearsSel", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val leafName = state.leavesVar.now().head.name
        state.activeForm.set(FormMode.Locked(FormTarget.Leaf(leafName)))
        state.removeNode(leafName)
        assertTrue(state.activeForm.now() == FormMode.Blank)
      },

      test("removeNode: leaves activeForm untouched when a different leaf is removed") {
        val leaf1 = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 17L
        ), "leaf")
        val leaf2 = unsafeGet(RiskLeaf.create(
          id               = childUlid,
          name             = "Market Risk",
          probability      = 0.2,
          shape            = lognormalShape(500L, 20000L),
          parentId         = Some(rootId),
          seedVarId = 18L
        ), "leaf")
        val rootWithTwo = unsafeGet(RiskPortfolio.createFromStrings(id = rootUlid, name = "Operational Risk", childIds = Vector(leafUlid, childUlid), parentId = None), "portfolio")
        val tree  = mkTree(treeUlid, "RemoveOther", Seq(rootWithTwo, leaf1, leaf2), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val cyberName  = state.leavesVar.now().find(_.name.value == "Cyber Risk").get.name
        val marketName = state.leavesVar.now().find(_.name.value == "Market Risk").get.name
        state.activeForm.set(FormMode.Locked(FormTarget.Leaf(cyberName)))
        state.removeNode(marketName)
        assertTrue(state.activeForm.now() == FormMode.Locked(FormTarget.Leaf(cyberName)))
      },

      test("loadFromTree resets activeForm to Blank even if a node was selected beforehand (bug fix: stale form after scenario/branch switch)") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Cyber Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 19L
        ), "leaf")
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

      test("metalog mode: percentiles rescaled from 0-1 domain to 0-100 form scale") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Metalog Risk",
          probability      = 0.2,
          shape            = metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 20000.0), Some(3)),
          parentId         = Some(rootId),
          seedVarId = 19L
        ), "leaf")
        val tree  = mkTree(treeUlid, "PopulateMetalog", Seq(rootPortfolio, leafUnderRoot), rootId)
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
          formState.nameVar.now() == "Metalog Risk",
          formState.distributionModeVar.now() == DistributionMode.Metalog
        )
      },

      test("lognormal mode: minLoss and maxLoss fields populated correctly") {
        val leafUnderRoot = unsafeGet(RiskLeaf.create(
          id               = leafUlid,
          name             = "Lognormal Risk",
          probability      = 0.3,
          shape            = lognormalShape(1000L, 50000L),
          parentId         = Some(rootId),
          seedVarId = 20L
        ), "leaf")
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

    ),

    suite("a populated form names the node it was populated from")(

      test("a loaded leaf is named by its identifier") {
        val tree  = mkTree(treeUlid, "SubjectLeaf", Seq(rootPortfolioWithChild, childPortfolio, leafUnderChild), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val formState = new RiskLeafFormState
        state.populateLeafForm(formState, state.leavesVar.now().head)
        assertTrue(formState.subjectVar.now() == ErrorSubject.ExistingNode(leafId))
      },

      test("a loaded portfolio is named by its identifier") {
        val tree  = mkTree(treeUlid, "SubjectPortfolio", Seq(rootPortfolioWithChild, childPortfolio, leafUnderChild), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val formState = new PortfolioFormState
        state.populatePortfolioForm(formState, state.portfoliosVar.now().find(_.id.contains(childId)).get)
        assertTrue(formState.subjectVar.now() == ErrorSubject.ExistingNode(childId))
      },

      test("a node added this session is named as the draft node") {
        val state     = new TreeBuilderState()
        val formState = new PortfolioFormState
        formState.subjectVar.set(ErrorSubject.ExistingNode(rootId))
        state.populatePortfolioForm(formState, PortfolioDraft(n("Operations"), ParentRef.Root, None))
        assertTrue(formState.subjectVar.now() == ErrorSubject.DraftNode)
      },

      test("a template populated from inside an observer ends on the draft node") {
        // The leaf view's Templating arm populates from the source and then
        // resets the subject, both from inside an observer of the form mode.
        // The writes apply in the order they were issued, so the form holds the
        // source's fields and names the draft node.
        val tree      = mkTree(treeUlid, "TemplateLeaf", Seq(rootPortfolioWithChild, childPortfolio, leafUnderChild), rootId)
        val state     = new TreeBuilderState()
        state.loadFromTree(tree)
        val formState = new RiskLeafFormState
        val trigger   = Var(0)
        given owner: ManualOwner = new ManualOwner
        trigger.signal.changes.foreach { _ =>
          state.populateLeafForm(formState, state.leavesVar.now().head)
          formState.subjectVar.set(ErrorSubject.DraftNode)
        }
        trigger.set(1)
        owner.killSubscriptions()
        assertTrue(
          formState.nameVar.now() == "Hardware Failure",
          formState.subjectVar.now() == ErrorSubject.DraftNode
        )
      }

    ),

    suite("tree name refusal")(

      test("a blank tree name is refused at the request's name field") {
        val state = new TreeBuilderState()
        val locations = state.toRequest() match
          case Validation.Failure(_, es) => es.toList.map(_.location).toSet
          case Validation.Success(_, _)  => Set.empty[ErrorLocation]
        assertTrue(locations.contains(ErrorLocation.requestField(FieldName.Name)))
      }

    )
  )
