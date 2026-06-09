package app.state

import zio.test.*
import zio.prelude.Validation

import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode, Distribution}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, SafeName, IronConstants, Probability}
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
        val tree  = mkTree(treeUlid, "Root+Leaf", Seq(rootPortfolio, leafUnderRoot), rootId)
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
        val newProb: Probability = 0.15
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

    )
  )
