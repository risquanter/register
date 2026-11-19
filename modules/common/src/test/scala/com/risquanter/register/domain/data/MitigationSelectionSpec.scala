package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{DecoderOps, EncoderOps}
import com.risquanter.register.domain.data.iron.MitigationId
import com.risquanter.register.domain.errors.{BoundedCollection, ErrorLocation, ErrorSubject, FieldName, FieldProblem}
import com.risquanter.register.testutil.TestHelpers.{mitigationId, nodeId, unsafeGet}

/** `MitigationSelection.create` builds the selection a request states, within
  * the two bounds `RiskTree` sets, and the wire form decodes through it.
  */
object MitigationSelectionSpec extends ZIOSpecDefault {

  private val m1 = mitigationId("m-one")
  private val m2 = mitigationId("m-two")
  private val n1 = nodeId("n-one")
  private val n2 = nodeId("n-two")

  private def tick(node: String, m: MitigationId): NodeMitigationEntry =
    NodeMitigationEntry(nodeId(node), m)

  /** The entries of a built selection, or `None` for one with no entries. */
  private def entriesOf(s: MitigationSelection): Option[Map[MitigationId, ScopeRestriction]] =
    s match
      case MitigationSelection.Selected(entries)                      => Some(entries)
      case MitigationSelection.Inherent | MitigationSelection.Residual => None

  def spec = suite("MitigationSelection.create")(
    test("two empty lists are the mitigation-free selection") {
      assertTrue(MitigationSelection.create(Nil, Nil).toEither == Right(MitigationSelection.Inherent))
    },

    test("the two lists state the entry map, with every node a mitigation is ticked at") {
      val built = unsafeGet(MitigationSelection.create(List(m1), List(tick("n-one", m2), tick("n-two", m2))), "selection")
      assertTrue(entriesOf(built) == Some(Map(
        m1 -> ScopeRestriction.FullScope,
        m2 -> ScopeRestriction.NodesOnly(Set(n1, n2))
      )))
    },

    test("a mitigation named in both lists applies across its whole scope") {
      val built = unsafeGet(MitigationSelection.create(List(m1), List(tick("n-one", m1))), "selection")
      assertTrue(entriesOf(built) == Some(Map(m1 -> ScopeRestriction.FullScope)))
    },

    test("more mitigations than a tree can hold are refused at the request's mitigation list") {
      val ids = (0 to RiskTree.MaxMitigations).toList.map(i => mitigationId(s"m-$i"))
      val refused = MitigationSelection.create(ids, Nil).toEither.left.toOption.toList.flatMap(_.toList)
      assertTrue(
        refused.map(_.location) == List(ErrorLocation.requestField(FieldName.Mitigations)),
        refused.forall(_.problem match
          case FieldProblem.TooMany(BoundedCollection.Mitigations, _, limit) => limit == RiskTree.MaxMitigations
          case _                                                             => false)
      )
    },

    test("every mitigation ticked at too many nodes is refused at its own subject, together with the count") {
      val tooMany = (m: MitigationId) =>
        (0 to RiskTree.MaxNodes).toList.map(i => tick(s"n-$i", m))
      val ids = (0 to RiskTree.MaxMitigations).toList.map(i => mitigationId(s"m-$i"))
      val refused = MitigationSelection.create(ids, tooMany(m1) ++ tooMany(m2)).toEither.left.toOption.toList.flatMap(_.toList)
      assertTrue(
        refused.map(_.location).toSet == Set(
          ErrorLocation.requestField(FieldName.Mitigations),
          ErrorLocation.Field(ErrorSubject.Mitigation(m1), FieldName.Nodes),
          ErrorLocation.Field(ErrorSubject.Mitigation(m2), FieldName.Nodes)
        ),
        refused.size == 3
      )
    },

    test("an omitted list decodes as the empty list") {
      assertTrue(
        "{}".fromJson[MitigationSelection.Enumerated] == Right(MitigationSelection.Inherent),
        s"""{"fullScope":["${m1.value}"]}""".fromJson[MitigationSelection.Enumerated] == Right(MitigationSelection.single(m1))
      )
    },

    test("decoding what the encoder writes gives the same selection") {
      val built: List[MitigationSelection.Enumerated] = List(
        MitigationSelection.Inherent,
        MitigationSelection.single(m1),
        unsafeGet(MitigationSelection.create(List(m1), List(tick("n-one", m2), tick("n-two", m2))), "selection")
      )
      assertTrue(built.forall(s => s.toJson.fromJson[MitigationSelection.Enumerated] == Right(s)))
    }
  )
}
