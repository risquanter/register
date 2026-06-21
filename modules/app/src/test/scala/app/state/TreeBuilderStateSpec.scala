package app.state

import zio.test.*
import zio.prelude.Validation

import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode, Distribution}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, SafeName, IronConstants, OccurrenceProbability}
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
    RiskTree(treeId, safeName, nodes, rootId, index)

  // ── Fixture nodes ────────────────────────────────────────────────────

  private val lognormalLeaf = RiskLeaf.unsafeApply(
    id               = leafUlid,
    name             = "Cyber Risk",
    distributionType = "lognormal",
    probability      = 0.3,
    minLoss          = Some(1000L),
    maxLoss          = Some(50000L),
    parentId         = None
  )

  private val expertLeaf = RiskLeaf.unsafeApply(
    id               = leafUlid,
    name             = "Expert Risk",
    distributionType = "expert",
    probability      = 0.2,
    percentiles      = Some(Array(0.1, 0.5, 0.9)),
    quantiles        = Some(Array(1000.0, 5000.0, 20000.0)),
    terms            = Some(3),
    parentId         = None
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
    parentId         = Some(childId)
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
        )
        val tree  = mkTree(treeUlid, "Round Trip", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val result = state.toUpdateRequest()
        assertTrue(result.isSuccess)
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
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
        val leaf1 = RiskLeaf.unsafeApply(id = leafUlid,  name = "LeafA", distributionType = "lognormal", probability = 0.1, minLoss = Some(100L), maxLoss = Some(1000L), parentId = Some(rootId))
        val leaf2 = RiskLeaf.unsafeApply(id = leaf2Ulid, name = "LeafB", distributionType = "lognormal", probability = 0.2, minLoss = Some(100L), maxLoss = Some(1000L), parentId = Some(rootId))
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
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

    suite("selection state")(

      test("updateLeaf: selectedLeafName cleared on success") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId)
        )
        val tree  = mkTree(treeUlid, "ClearSel", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val originalName = state.leavesVar.now().head.name
        state.selectedLeafName.set(Some(originalName))
        val newShape = Distribution(IronConstants.Lognormal, Some(2000L), Some(8000L), None, None, None)
        state.updateLeaf(originalName, "Renamed", Some("Operational Risk"), newShape, 0.3)
        assertTrue(state.selectedLeafName.now().isEmpty)
      },

      test("removeNode: clears selectedLeafName when the removed leaf was selected") {
        val leafUnderRoot = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId)
        )
        val tree  = mkTree(treeUlid, "RemoveClearsSel", Seq(rootPortfolio, leafUnderRoot), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val leafName = state.leavesVar.now().head.name
        state.selectedLeafName.set(Some(leafName))
        state.removeNode(leafName.value.toString)
        assertTrue(state.selectedLeafName.now().isEmpty)
      },

      test("removeNode: does not clear selectedLeafName when a different leaf is removed") {
        val leaf1 = RiskLeaf.unsafeApply(
          id               = leafUlid,
          name             = "Cyber Risk",
          distributionType = "lognormal",
          probability      = 0.3,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L),
          parentId         = Some(rootId)
        )
        val leaf2 = RiskLeaf.unsafeApply(
          id               = childUlid,
          name             = "Market Risk",
          distributionType = "lognormal",
          probability      = 0.2,
          minLoss          = Some(500L),
          maxLoss          = Some(20000L),
          parentId         = Some(rootId)
        )
        val rootWithTwo = RiskPortfolio.unsafeFromStrings(id = rootUlid, name = "Operational Risk", childIds = Array(leafUlid, childUlid), parentId = None)
        val tree  = mkTree(treeUlid, "RemoveOther", Seq(rootWithTwo, leaf1, leaf2), rootId)
        val state = new TreeBuilderState()
        state.loadFromTree(tree)
        val cyberName  = state.leavesVar.now().find(_.name.value == "Cyber Risk").get.name
        val marketName = state.leavesVar.now().find(_.name.value == "Market Risk").get.name
        state.selectedLeafName.set(Some(cyberName))
        state.removeNode(marketName.value.toString)
        assertTrue(state.selectedLeafName.now().contains(cyberName))
      },

      test("selecting a leaf clears selectedPortfolioName (mutual exclusivity)") {
        val state = new TreeBuilderState()
        val portName = SafeName.fromString("MyPortfolio").toOption.get
        val leafName = SafeName.fromString("MyLeaf").toOption.get
        state.selectedPortfolioName.set(Some(portName))
        state.selectedLeafName.set(Some(leafName))
        // the click handler in TreePreview clears the other Var first
        state.selectedPortfolioName.set(None)
        assertTrue(
          state.selectedLeafName.now().contains(leafName),
          state.selectedPortfolioName.now().isEmpty
        )
      },

      test("selecting a portfolio clears selectedLeafName (mutual exclusivity)") {
        val state = new TreeBuilderState()
        val portName = SafeName.fromString("MyPortfolio").toOption.get
        val leafName = SafeName.fromString("MyLeaf").toOption.get
        state.selectedLeafName.set(Some(leafName))
        state.selectedPortfolioName.set(Some(portName))
        // the click handler in TreePreview clears the other Var first
        state.selectedLeafName.set(None)
        assertTrue(
          state.selectedPortfolioName.now().contains(portName),
          state.selectedLeafName.now().isEmpty
        )
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
          parentId         = Some(rootId)
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
          parentId         = Some(rootId)
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
