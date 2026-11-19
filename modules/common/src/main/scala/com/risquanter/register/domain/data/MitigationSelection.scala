package com.risquanter.register.domain.data

import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}
import zio.prelude.{Equal, Validation}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, NonNegativeInt}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** How far one selected mitigation reaches: its whole resolved scope, or the
  * named nodes only. A `NodesOnly` set intersects with the mitigation's
  * resolved scope at application time, so an id outside that scope does
  * nothing.
  */
sealed trait ScopeRestriction

object ScopeRestriction:
  case object FullScope extends ScopeRestriction

  /** Built only by `MitigationSelection.create`, which bounds the set and never
    * builds an empty one. */
  final case class NodesOnly private[data] (ids: Set[NodeId]) extends ScopeRestriction

  given Equal[ScopeRestriction] = Equal.default

/** One tick on a mitigation's row beneath a node: that mitigation applies at
  * that node and nowhere else.
  */
final case class NodeMitigationEntry(node: NodeId, mitigation: MitigationId)

object NodeMitigationEntry:
  given codec: JsonCodec[NodeMitigationEntry] = DeriveJsonCodec.gen[NodeMitigationEntry]
  given schema: Schema[NodeMitigationEntry]   = Schema.derived[NodeMitigationEntry]

/** Which mitigations a valuation applies, each across its whole resolved scope
  * or at named nodes only. `Inherent` applies none. `Residual` applies every
  * mitigation the tree holds. `Selected` applies the ones it names.
  */
sealed trait MitigationSelection

object MitigationSelection:

  /** A selection stated by naming its mitigations, which is what a request body
    * can state. `Residual` is not one: it means every mitigation of a tree, so
    * it has no statement without that tree, and only the query language's
    * `residual` constant produces it. */
  sealed trait Enumerated extends MitigationSelection

  /** The mitigation-free valuation: no mitigation applied. */
  case object Inherent extends Enumerated

  /** Every mitigation the tree holds, each across its whole resolved scope. */
  case object Residual extends MitigationSelection

  /** The named mitigations. Never empty, and within both bounds: `create` and
    * `single` are the only ways to build one. */
  final case class Selected private[MitigationSelection] (entries: Map[MitigationId, ScopeRestriction]) extends Enumerated

  given Equal[MitigationSelection] = Equal.default

  /** One mitigation across its whole resolved scope. Total, because one entry
    * breaks no bound. */
  def single(id: MitigationId): Enumerated =
    new Selected(Map(id -> ScopeRestriction.FullScope))

  /** The selection two lists state. `fullScope` holds the mitigations that are
    * on across their whole resolved scope, and `nodesOnly` the per-node ticks.
    * Both lists empty is `Inherent`. A mitigation named in both lists applies
    * across its whole resolved scope, because both lists say it is on and the
    * wider statement subsumes the narrower.
    *
    * A selection names at most as many mitigations as a tree can hold, and
    * ticks one mitigation at no more nodes than a tree can hold. Both bounds
    * are `RiskTree`'s own, and every breach is reported together. Whether the
    * named mitigations exist is not checked here: that is a lookup against a
    * loaded tree.
    *
    * {{{
    * create(Nil, Nil)                                        // Inherent
    * create(List(m1), List((n1, m2), (n2, m2), (n1, m1)))
    * // Selected(Map(m1 -> FullScope, m2 -> NodesOnly(Set(n1, n2))))
    * create(1001 distinct ids, Nil)
    * // fails: [request.Mitigations] too many mitigations: 1001 exceeds the limit of 1000
    * }}}
    */
  def create(
    fullScope: List[MitigationId],
    nodesOnly: List[NodeMitigationEntry]
  ): Validation[ValidationError, Enumerated] =
    val full  = fullScope.toSet
    val named = full ++ nodesOnly.map(_.mitigation)

    val countV: Validation[ValidationError, Unit] =
      RiskTree.requireMitigationCount(NonNegativeInt.count(named.size), ErrorLocation.requestField(FieldName.Mitigations))

    val perMitigationV: Validation[ValidationError, Unit] =
      Validation.validateAll(
        nodesOnly.groupBy(_.mitigation).toList.sortBy(_._1.value).map { (id, ticks) =>
          RiskTree.requireNodeCount(
            NonNegativeInt.count(ticks.size),
            ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Nodes)
          )
        }
      ).as(())

    Validation.validateWith(countV, perMitigationV) { (_, _) =>
      val restricted: Map[MitigationId, ScopeRestriction] =
        nodesOnly
          .filterNot(entry => full.contains(entry.mitigation))
          .groupMap(_.mitigation)(_.node)
          .view.mapValues(nodes => ScopeRestriction.NodesOnly(nodes.toSet))
          .toMap
      val entries = restricted ++ full.map(_ -> ScopeRestriction.FullScope)
      if entries.isEmpty then Inherent else new Selected(entries)
    }

  /** The wire fields. Either list may be omitted and means the empty list. */
  private case class Raw(
    fullScope: Option[List[MitigationId]],
    nodesOnly: Option[List[NodeMitigationEntry]]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  private def fromRaw(raw: Raw): Validation[ValidationError, Enumerated] =
    create(raw.fullScope.getOrElse(Nil), raw.nodesOnly.getOrElse(Nil))

  /** The two lists a selection is stated by, each in identifier order. */
  private def toRaw(selection: Enumerated): Raw = selection match
    case Inherent => Raw(Some(Nil), Some(Nil))
    case Selected(entries) =>
      val ordered = entries.toList.sortBy(_._1.value)
      Raw(
        Some(ordered.collect { case (id, ScopeRestriction.FullScope) => id }),
        Some(ordered.flatMap {
          case (id, ScopeRestriction.NodesOnly(ids)) => ids.toList.sortBy(_.value).map(NodeMitigationEntry(_, id))
          case (_, ScopeRestriction.FullScope)       => Nil
        })
      )

  /** Decode runs `create`, so a decoded selection is as trustworthy as a
    * constructed one, on the server and in the browser. Declared for
    * `Enumerated`, so a body cannot state `Residual` and the encoder is total. */
  given codec: JsonCodec[Enumerated] = JsonCodec(
    JsonEncoder[Raw].contramap(toRaw),
    JsonDecoder[Raw].mapOrFail(raw =>
      fromRaw(raw).toEither.left.map(_.toChunk.map(ValidationError.describe).mkString("; ")))
  )

  /** Derived from `Raw`, the shape the codec reads and writes, and mapped
    * through the constructor the decoder uses (ADR-038 §1). */
  given schema: Schema[Enumerated] =
    Schema.derived[Raw].map(raw => fromRaw(raw).toEither.toOption)(toRaw)
