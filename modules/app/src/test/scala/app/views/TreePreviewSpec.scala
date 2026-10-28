package app.views

import zio.test.*
import app.state.{TreeBuilderState, PortfolioDraft, LeafDraft}
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{SafeName, OccurrenceProbability}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef
import com.risquanter.register.testutil.TestHelpers.lognormalShape
import io.github.iltotore.iron.*

/** Structural tests for TreePreview rendering logic.
  *
  * TreePreview is a pure view — it derives its rows from
  * `TreeBuilderState.portfoliosVar` and `leavesVar` via a `Signal`.
  * Since DOM creation requires a browser, we test the underlying
  * data-structure invariants that drive the rendering:
  *
  *   - Row count = 1 (root) + #portfolios + #leaves.
  *   - Parent-child grouping produces the right child count per parent.
  */
object TreePreviewSpec extends ZIOSpecDefault:

  private def makeState(
    treeName: String,
    portfolios: List[PortfolioDraft],
    leaves: List[LeafDraft]
  ): TreeBuilderState =
    val s = new TreeBuilderState()
    s.treeNameVar.set(treeName)
    s.portfoliosVar.set(portfolios)
    s.leavesVar.set(leaves)
    s

  private def sn(s: String): SafeName.SafeName =
    SafeName.fromString(s).toOption.getOrElse(throw new AssertionError(s"Invalid SafeName: $s"))

  private val leafShape: DistributionShape = lognormalShape(1000L, 50000L)

  private val lognormalProb: OccurrenceProbability = 0.3

  /** Replication of renderTree's groupBy logic for structural assertion. */
  private def expectedGrouping(
    portfolios: List[PortfolioDraft],
    leaves: List[LeafDraft]
  ): Map[Option[String], Int] =
    val allParents: List[Option[String]] =
      portfolios.map(p => ParentRef.nameOf(p.parent).map(_.value)) ++
        leaves.map(l => ParentRef.nameOf(l.parent).map(_.value))
    allParents.groupBy(identity).view.mapValues(_.size).toMap

  def spec = suite("TreePreviewSpec")(
    suite("row count invariant")(

      test("empty state: 0 portfolios, 0 leaves → no rows (empty-tree branch)") {
        val state = makeState("My Tree", Nil, Nil)
        assertTrue(state.portfoliosVar.now().isEmpty) &&
        assertTrue(state.leavesVar.now().isEmpty)
      },

      test("lone-leaf: 0 portfolios, 1 leaf → 1 child row (+ root = 2 total)") {
        val leaf = LeafDraft(sn("Cyber Risk"), ParentRef.Root, leafShape, lognormalProb, None)
        val state = makeState("My Tree", Nil, List(leaf))
        val totalNodes = 1 + state.portfoliosVar.now().size + state.leavesVar.now().size
        assertTrue(totalNodes == 2)
      },

      test("root portfolio + leaf: 1 portfolio, 1 leaf → 3 rows total") {
        val portfolio = PortfolioDraft(sn("Operational Risk"), ParentRef.Root, None)
        val leaf      = LeafDraft(sn("Cyber Risk"), ParentRef.Under(sn("Operational Risk")), leafShape, lognormalProb, None)
        val state     = makeState("My Tree", List(portfolio), List(leaf))
        val totalNodes = 1 + state.portfoliosVar.now().size + state.leavesVar.now().size
        assertTrue(totalNodes == 3)
      },

      test("nested portfolios + leaf: 2 portfolios, 1 leaf → 4 rows total") {
        val root  = PortfolioDraft(sn("Root"), ParentRef.Root, None)
        val child = PortfolioDraft(sn("IT Risk"), ParentRef.Under(sn("Root")), None)
        val leaf  = LeafDraft(sn("Hardware Failure"), ParentRef.Under(sn("IT Risk")), leafShape, lognormalProb, None)
        val state = makeState("My Tree", List(root, child), List(leaf))
        val totalNodes = 1 + state.portfoliosVar.now().size + state.leavesVar.now().size
        assertTrue(totalNodes == 4)
      }

    ),

    suite("parent-child grouping")(

      test("root portfolio has no parent → grouped under None key") {
        val portfolio = PortfolioDraft(sn("Operational Risk"), ParentRef.Root, None)
        val state     = makeState("My Tree", List(portfolio), Nil)
        val grouping  = expectedGrouping(state.portfoliosVar.now(), state.leavesVar.now())
        assertTrue(grouping.getOrElse(None, 0) == 1)
      },

      test("child portfolio has parent → grouped under parent-name key") {
        val root  = PortfolioDraft(sn("Root"), ParentRef.Root, None)
        val child = PortfolioDraft(sn("IT Risk"), ParentRef.Under(sn("Root")), None)
        val state = makeState("My Tree", List(root, child), Nil)
        val grouping = expectedGrouping(state.portfoliosVar.now(), state.leavesVar.now())
        assertTrue(grouping.getOrElse(Some("Root"), 0) == 1) &&
        assertTrue(grouping.getOrElse(None, 0) == 1)
      },

      test("leaf under child portfolio → correct parent group") {
        val root  = PortfolioDraft(sn("Root"), ParentRef.Root, None)
        val child = PortfolioDraft(sn("IT Risk"), ParentRef.Under(sn("Root")), None)
        val leaf  = LeafDraft(sn("Hardware Failure"), ParentRef.Under(sn("IT Risk")), leafShape, lognormalProb, None)
        val state = makeState("My Tree", List(root, child), List(leaf))
        val grouping = expectedGrouping(state.portfoliosVar.now(), state.leavesVar.now())
        assertTrue(grouping.getOrElse(Some("IT Risk"), 0) == 1) &&
        assertTrue(grouping.getOrElse(Some("Root"), 0) == 1) &&
        assertTrue(grouping.getOrElse(None, 0) == 1)
      }

    )
  )
