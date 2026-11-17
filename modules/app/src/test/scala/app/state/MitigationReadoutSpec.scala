package app.state

import zio.test.*

import com.risquanter.register.domain.data.{
  Mitigation, MitigationPrecedence, MitigationSpec, MitigationTarget, RelativeLikelihood,
  RelativeSeverity, RiskLeaf, RiskPortfolio, RiskTree, ScopeOutcome, TargetingPredicate
}
import com.risquanter.register.domain.data.iron.{NodeId, SafeName, TreeId}
import com.risquanter.register.domain.errors.ErrorSubject
import com.risquanter.register.http.responses.{MitigationScopeEntry, TreeStructureResponse}
import com.risquanter.register.testutil.TestHelpers.{lognormalShape, mitigationId, retentionFactor, unsafeGet}

/** Pure tests for the lookups over one structure read: the precedence order,
  * the mitigations scoped at a node, the coverage badge, and the nodes a
  * predicate names. */
object MitigationReadoutSpec extends ZIOSpecDefault:

  private val rootUlid = "01HX9ABCDE0000000000000001"
  private val leafUlid = "01HX9ABCDE0000000000000002"
  private val goneUlid = "01HX9ABCDE0000000000000009"

  private val rootId = NodeId.fromString(rootUlid).toOption.get
  private val leafId = NodeId.fromString(leafUlid).toOption.get
  private val goneId = NodeId.fromString(goneUlid).toOption.get

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private val root = unsafeGet(RiskPortfolio.createFromStrings(subject = ErrorSubject.Request,
    id = rootUlid, name = "Root", childIds = Vector(leafUlid), parentId = None), "root")
  private val leaf = unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
    id = leafUlid, name = "Leaf", probability = 0.3, shape = lognormalShape(1000L, 5000L),
    parentId = Some(rootId), seedVarId = 1L), "leaf")

  private def mitigation(id: String, source: String, precedence: Int): Mitigation =
    unsafeGet(Mitigation.create(
      mitigationId(id),
      name(id),
      MitigationTarget.Predicate(unsafeGet(TargetingPredicate.create(source, ErrorSubject.Request), "target")),
      MitigationSpec.LeafRelative(RelativeLikelihood.Scale(retentionFactor(0.5)), RelativeSeverity.Keep),
      MitigationPrecedence(precedence)
    ), "mitigation")

  /** Scoped at the leaf, applied last. */
  private val late  = mitigation("late", s"""eq(x, node_id("$leafUlid")) \\/ eq(x, node_id("$goneUlid"))""", 10)
  /** Scoped at the leaf, applied first. */
  private val early = mitigation("early", "leaf(x)", -10)

  private val response = TreeStructureResponse(
    RiskTree.fromNodesUnsafe(TreeId.fromString("01HX9ABCDE0000000000000005").toOption.get, name("Tree"),
      Seq(root, leaf), rootId, Some(leaf.seedVarId), mitigations = Seq(late, early)),
    List(
      MitigationScopeEntry(late.id, ScopeOutcome.Resolved(Set(leafId))),
      MitigationScopeEntry(early.id, ScopeOutcome.Resolved(Set(leafId)))
    ),
    Set.empty
  )

  def spec = suite("MitigationReadout")(

    test("mitigations are in application order: precedence key, then identifier") {
      assertTrue(MitigationReadout.mitigations(response).map(_.id) == List(early.id, late.id))
    },

    test("scopingAt lists the mitigations whose resolved scope holds the node, in that order") {
      assertTrue(
        MitigationReadout.scopingAt(response, leafId).map(_.id) == List(early.id, late.id),
        MitigationReadout.scopingAt(response, rootId).isEmpty
      )
    },

    test("badgeAt: scoped at the leaf, only below at the portfolio, nothing at a node outside") {
      val unscoped = MitigationReadout.badgeAt(response.copy(mitigationScopes = Nil), leafId)
      assertTrue(
        MitigationReadout.badgeAt(response, leafId).exists {
          case CoverageBadge.Scoped(ms)    => ms.toList.map(_.id) == List(early.id, late.id)
          case CoverageBadge.BelowOnly(_)  => false
        },
        MitigationReadout.badgeAt(response, rootId).exists {
          case CoverageBadge.BelowOnly(ms) => ms.toList.map(_.id) == List(early.id, late.id)
          case CoverageBadge.Scoped(_)     => false
        },
        unscoped.isEmpty
      )
    },

    test("namedNodes names each node by its name in this tree, and a node the tree lacks by none") {
      assertTrue(
        MitigationReadout.namedNodes(response, late.id) == List(leafId -> Some(name("Leaf")), goneId -> None),
        MitigationReadout.namedNodes(response, early.id).isEmpty
      )
    }
  )
