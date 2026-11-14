package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}
import zio.prelude.Validation
import sttp.tapir.Schema

import com.risquanter.register.domain.data.{MitigationSelection, RiskTree}
import com.risquanter.register.domain.data.iron.{NodeId, NonNegativeInt}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Body of the multi-node curve read: the nodes to draw, and the one selection
  * the answer is computed under.
  */
final case class LECCurvesMultiRequest private (
  nodeIds: List[NodeId],
  selection: MitigationSelection.Enumerated
)

object LECCurvesMultiRequest:

  /** A request asks for at most as many nodes as a tree can hold. */
  def create(
    nodeIds: List[NodeId],
    selection: MitigationSelection.Enumerated
  ): Validation[ValidationError, LECCurvesMultiRequest] =
    if nodeIds.sizeIs <= RiskTree.MaxNodes then
      Validation.succeed(new LECCurvesMultiRequest(nodeIds, selection))
    else
      Validation.fail(ValidationError(
        ErrorLocation.requestField(FieldName.Nodes),
        FieldProblem.TooManyNodes(NonNegativeInt.count(nodeIds.size), RiskTree.MaxNodes)
      ))

  private case class Raw(nodeIds: List[NodeId], selection: Option[MitigationSelection.Enumerated])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  private def fromRaw(raw: Raw): Validation[ValidationError, LECCurvesMultiRequest] =
    create(raw.nodeIds, raw.selection.getOrElse(MitigationSelection.Inherent))

  private def toRaw(r: LECCurvesMultiRequest): Raw = Raw(r.nodeIds, Some(r.selection))

  /** Decode runs `create`, so no value of this type exists that breaks the
    * bound. An absent `selection` means the inherent reading. */
  given codec: JsonCodec[LECCurvesMultiRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(toRaw),
    JsonDecoder[Raw].mapOrFail(raw =>
      fromRaw(raw).toEither.left.map(_.toChunk.map(ValidationError.describe).mkString("; ")))
  )

  /** Derived from `Raw`, the shape the codec reads and writes (ADR-038 §1): the
    * constructor is private, and the optionality of `selection` is a property
    * of `Raw`. */
  given schema: Schema[LECCurvesMultiRequest] =
    Schema.derived[Raw].map(raw => fromRaw(raw).toEither.toOption)(toRaw)

/** Body of the exceedance-probability read. The node and the threshold stay
  * where they are — in the path and the query string — so the body carries the
  * selection alone.
  */
final case class ProbOfExceedanceRequest(selection: MitigationSelection.Enumerated)

object ProbOfExceedanceRequest:
  private case class Raw(selection: Option[MitigationSelection.Enumerated])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  private def fromRaw(raw: Raw): ProbOfExceedanceRequest =
    ProbOfExceedanceRequest(raw.selection.getOrElse(MitigationSelection.Inherent))

  private def toRaw(r: ProbOfExceedanceRequest): Raw = Raw(Some(r.selection))

  given codec: JsonCodec[ProbOfExceedanceRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(toRaw),
    JsonDecoder[Raw].map(fromRaw)
  )

  /** Derived from `Raw` so the published description shows `selection` as
    * optional, which is what the codec reads. The decode is total, so the
    * mapping never fails. */
  given schema: Schema[ProbOfExceedanceRequest] =
    Schema.derived[Raw].map(raw => Some(fromRaw(raw)))(toRaw)
