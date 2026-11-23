package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{DecoderOps, EncoderOps}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.domain.errors.{BoundedCollection, ErrorLocation, ErrorSubject, FieldName, FieldProblem}
import com.risquanter.register.testutil.TestHelpers.{mitigationId, nodeId, unsafeGet}

/** `MitigationSelection.create` and `ScopeRestriction.nodesOnly` build the
  * selection a request states, within the two bounds `RiskTree` sets, and the
  * wire form decodes through them.
  */
object MitigationSelectionSpec extends ZIOSpecDefault {

  private val List(m1, m2) = List(mitigationId("m-one"), mitigationId("m-two")).sorted
  private val List(n1, n2) = List(nodeId("n-one"), nodeId("n-two")).sorted

  private val nodesLocation = ErrorLocation.Field(ErrorSubject.Mitigation(m2), FieldName.Nodes)

  private def nodesOnly(ids: Set[NodeId]): ScopeRestriction =
    unsafeGet(ScopeRestriction.nodesOnly(ids, nodesLocation), "restriction")

  private def selected(entries: Map[MitigationId, ScopeRestriction]): MitigationSelection =
    unsafeGet(MitigationSelection.create(entries), "selection")

  /** The entries of a built selection, or `None` for one with no entries. */
  private def entriesOf(s: MitigationSelection): Option[Map[MitigationId, ScopeRestriction]] =
    s match
      case MitigationSelection.Selected(entries)                      => Some(entries)
      case MitigationSelection.Inherent | MitigationSelection.Residual => None

  private val mixed: MitigationSelection =
    selected(Map(m1 -> ScopeRestriction.FullScope, m2 -> nodesOnly(Set(n2, n1))))

  private val mixedJson: String =
    s"""{"kind":"Selected","mitigations":{"${m1.value}":{"scope":"FullScope"},""" +
    s""""${m2.value}":{"scope":"NodesOnly","nodes":["${n1.value}","${n2.value}"]}}}"""

  def spec = suite("MitigationSelection")(
    test("an empty map is the mitigation-free selection") {
      assertTrue(MitigationSelection.create(Map.empty).toEither == Right(MitigationSelection.Inherent))
    },

    test("one entry is the selection of that mitigation") {
      assertTrue(
        entriesOf(selected(Map(m1 -> ScopeRestriction.FullScope))) == Some(Map(m1 -> ScopeRestriction.FullScope)),
        selected(Map(m1 -> ScopeRestriction.FullScope)) == MitigationSelection.single(m1)
      )
    },

    test("more mitigations than a tree can hold are refused at the request's mitigation list") {
      val entries = (0 to RiskTree.MaxMitigations).map(i => mitigationId(s"m-$i") -> ScopeRestriction.FullScope).toMap
      val refused = MitigationSelection.create(entries).toEither.left.toOption.toList.flatMap(_.toList)
      assertTrue(
        refused.map(_.location) == List(ErrorLocation.requestField(FieldName.Mitigations)),
        refused.forall(_.problem match
          case FieldProblem.TooMany(BoundedCollection.Mitigations, _, limit) => limit == RiskTree.MaxMitigations
          case _                                                             => false)
      )
    },

    test("an empty node set is refused at the location given") {
      val refused = ScopeRestriction.nodesOnly(Set.empty, nodesLocation).toEither.left.toOption.toList.flatMap(_.toList)
      assertTrue(
        refused.map(_.location) == List(nodesLocation),
        refused.map(_.problem) == List(FieldProblem.NodeIdsEmpty)
      )
    },

    test("more nodes than a tree can hold are refused at the location given") {
      val ids     = (0 to RiskTree.MaxNodes).map(i => nodeId(s"n-$i")).toSet
      val refused = ScopeRestriction.nodesOnly(ids, nodesLocation).toEither.left.toOption.toList.flatMap(_.toList)
      assertTrue(
        refused.map(_.location) == List(nodesLocation),
        refused.forall(_.problem match
          case FieldProblem.TooMany(BoundedCollection.Nodes, _, limit) => limit == RiskTree.MaxNodes
          case _                                                       => false)
      )
    },

    test("each alternative and each restriction is written under its case name, in identifier order") {
      assertTrue(
        (MitigationSelection.Inherent: MitigationSelection).toJson == """{"kind":"Inherent"}""",
        (MitigationSelection.Residual: MitigationSelection).toJson == """{"kind":"Residual"}""",
        mixed.toJson == mixedJson
      )
    },

    test("each alternative and each restriction is read from its case name") {
      assertTrue(
        """{"kind":"Inherent"}""".fromJson[MitigationSelection] == Right(MitigationSelection.Inherent),
        """{"kind":"Residual"}""".fromJson[MitigationSelection] == Right(MitigationSelection.Residual),
        mixedJson.fromJson[MitigationSelection] == Right(mixed)
      )
    },

    test("an empty mitigations map reads as the mitigation-free selection") {
      assertTrue(
        """{"kind":"Selected","mitigations":{}}""".fromJson[MitigationSelection] == Right(MitigationSelection.Inherent)
      )
    },

    test("a body with no kind is refused") {
      assertTrue(
        "{}".fromJson[MitigationSelection].isLeft,
        s"""{"mitigations":{"${m1.value}":{"scope":"FullScope"}}}""".fromJson[MitigationSelection].isLeft
      )
    },

    test("an empty node list is refused, and every such mitigation is named together") {
      val body =
        s"""{"kind":"Selected","mitigations":{"${m1.value}":{"scope":"NodesOnly","nodes":[]},""" +
        s""""${m2.value}":{"scope":"NodesOnly","nodes":[]}}}"""
      val refusal = body.fromJson[MitigationSelection].left.toOption
      assertTrue(
        refusal.exists(_.contains(m1.value)),
        refusal.exists(_.contains(m2.value))
      )
    },

    test("decoding what the encoder writes gives the same selection") {
      val built: List[MitigationSelection] = List(MitigationSelection.Inherent, MitigationSelection.Residual, mixed)
      assertTrue(built.forall(s => s.toJson.fromJson[MitigationSelection] == Right(s)))
    }
  )
}
