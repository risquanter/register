package com.risquanter.register.domain.data

import scala.collection.immutable.ListMap

import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder, jsonDiscriminator}
import zio.prelude.{Equal, Validation}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, NonNegativeInt}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** How far one selected mitigation reaches: its whole resolved scope, or the
  * named nodes only. A `NodesOnly` set intersects with the mitigation's
  * resolved scope at application time, so an id outside that scope does
  * nothing.
  */
sealed trait ScopeRestriction

object ScopeRestriction:
  /** The key naming which case a restriction states. */
  final val ScopeField = "scope"

  case object FullScope extends ScopeRestriction

  /** Built only by `nodesOnly`, which bounds the set and never builds an empty one. */
  final case class NodesOnly private[ScopeRestriction] (ids: Set[NodeId]) extends ScopeRestriction

  /** The restriction to `ids`. An empty set is refused with
    * `FieldProblem.NodeIdsEmpty`, and a set above `RiskTree.MaxNodes` with
    * `FieldProblem.TooMany`, both at `location`.
    *
    * {{{
    * nodesOnly(Set(n1, n2), location) // NodesOnly(Set(n1, n2))
    * nodesOnly(Set.empty, location)   // fails: NodeIdsEmpty at location
    * }}}
    */
  def nodesOnly(ids: Set[NodeId], location: ErrorLocation): Validation[ValidationError, ScopeRestriction] =
    if ids.isEmpty then Validation.fail(ValidationError(location, FieldProblem.NodeIdsEmpty))
    else RiskTree.requireNodeCount(NonNegativeInt.count(ids.size), location).as(new NodesOnly(ids))

  given Equal[ScopeRestriction] = Equal.default

/** Which mitigations a valuation applies, each across its whole resolved scope
  * or at named nodes only. `Inherent` applies none. `Residual` applies every
  * mitigation the tree holds. `Selected` applies the ones it names.
  */
sealed trait MitigationSelection

object MitigationSelection:
  /** The key naming which case a selection states. */
  final val TagField = "kind"

  /** A selection stated by naming its mitigations, which is what the interface
    * edits. */
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

  /** The selection `entries` states. An empty map is `Inherent`. More entries
    * than `RiskTree.MaxMitigations` are refused at
    * `ErrorLocation.requestField(FieldName.Mitigations)`. Whether the named
    * mitigations exist is not checked here: that is a lookup against a loaded
    * tree.
    *
    * {{{
    * create(Map.empty)                                   // Inherent
    * create(Map(m1 -> FullScope, m2 -> NodesOnly(Set(n1))))
    * // Selected(Map(m1 -> FullScope, m2 -> NodesOnly(Set(n1))))
    * create(1001 entries)
    * // fails: [request.Mitigations] too many mitigations: 1001 exceeds the limit of 1000
    * }}}
    */
  def create(entries: Map[MitigationId, ScopeRestriction]): Validation[ValidationError, Enumerated] =
    RiskTree
      .requireMitigationCount(NonNegativeInt.count(entries.size), ErrorLocation.requestField(FieldName.Mitigations))
      .as(if entries.isEmpty then Inherent else new Selected(entries))

  /** The wire form of a restriction, tagged by `ScopeRestriction.ScopeField`. */
  @jsonDiscriminator(ScopeRestriction.ScopeField)
  private enum RawScope:
    case FullScope
    case NodesOnly(nodes: List[NodeId])

  private object RawScope:
    given codec: JsonCodec[RawScope] = DeriveJsonCodec.gen[RawScope]

    given schema: Schema[RawScope] =
      given Configuration = Configuration.default.withDiscriminator(ScopeRestriction.ScopeField)
      Schema.derived[RawScope]

  /** The wire form of a selection, tagged by `TagField`. */
  @jsonDiscriminator(TagField)
  private enum Raw:
    case Inherent
    case Residual
    case Selected(mitigations: Map[MitigationId, RawScope])

  private object Raw:
    given codec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw]

    given schema: Schema[Raw] =
      given Configuration = Configuration.default.withDiscriminator(TagField)
      Schema.derived[Raw]

  /** The selection a wire value states. Each node list is built through
    * `ScopeRestriction.nodesOnly`, located at its mitigation, and the count of
    * mitigations is bounded as `create` bounds it. Every breach is reported
    * together. */
  private def fromRaw(raw: Raw): Validation[ValidationError, MitigationSelection] = raw match
    case Raw.Inherent => Validation.succeed(Inherent)
    case Raw.Residual => Validation.succeed(Residual)
    case Raw.Selected(mitigations) =>
      val countV: Validation[ValidationError, Unit] =
        RiskTree.requireMitigationCount(
          NonNegativeInt.count(mitigations.size),
          ErrorLocation.requestField(FieldName.Mitigations)
        )
      val entriesV: Validation[ValidationError, List[(MitigationId, ScopeRestriction)]] =
        Validation.validateAll(mitigations.toList.sortBy(_._1).map {
          case (id, RawScope.FullScope) => Validation.succeed(id -> ScopeRestriction.FullScope)
          case (id, RawScope.NodesOnly(nodes)) =>
            ScopeRestriction
              .nodesOnly(nodes.toSet, ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Nodes))
              .map(id -> _)
        })
      Validation.validateWith(countV, entriesV)((_, entries) => entries.toMap).flatMap(create)

  /** The wire value of a selection, with mitigations and nodes in identifier
    * order. */
  private def toRaw(selection: MitigationSelection): Raw = selection match
    case Inherent => Raw.Inherent
    case Residual => Raw.Residual
    case Selected(entries) =>
      Raw.Selected(ListMap.from(entries.toList.sortBy(_._1).map {
        case (id, ScopeRestriction.FullScope)      => id -> RawScope.FullScope
        case (id, ScopeRestriction.NodesOnly(ids)) => id -> RawScope.NodesOnly(ids.toList.sorted)
      }))

  /** Decode runs `create` and `ScopeRestriction.nodesOnly`, so a decoded
    * selection is as trustworthy as a constructed one, on the server and in
    * the browser. */
  given codec: JsonCodec[MitigationSelection] = JsonCodec(
    JsonEncoder[Raw].contramap(toRaw),
    JsonDecoder[Raw].mapOrFail(raw =>
      fromRaw(raw).toEither.left.map(_.toChunk.map(ValidationError.describe).mkString("; ")))
  )

  /** Derived from `Raw`, the shape the codec reads and writes, and mapped
    * through the constructors the decoder uses (ADR-038 §1). */
  given schema: Schema[MitigationSelection] =
    Schema.derived[Raw].map(raw => fromRaw(raw).toEither.toOption)(toRaw)
