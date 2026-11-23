package com.risquanter.register.http.requests

import io.github.iltotore.iron.autoRefine
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}
import zio.prelude.Validation
import sttp.tapir.Schema

import com.risquanter.register.domain.data.{MitigationSelection, RiskTree}
import com.risquanter.register.domain.data.iron.{NodeId, NonNegativeInt, ValidationUtil}
import com.risquanter.register.domain.errors.{BoundedCollection, ErrorLocation, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One valuation of a curve request: a selection, and the nodes whose curves
  * are wanted under it.
  */
final case class CurveValuationRequest private (selection: MitigationSelection, nodeIds: List[NodeId])

object CurveValuationRequest:

  /** A valuation names at most as many nodes as a tree can hold. */
  def create(selection: MitigationSelection, nodeIds: List[NodeId]): Validation[ValidationError, CurveValuationRequest] =
    RiskTree
      .requireNodeCount(NonNegativeInt.count(nodeIds.size), ErrorLocation.requestField(FieldName.Nodes))
      .as(new CurveValuationRequest(selection, nodeIds))

  private case class Raw(selection: MitigationSelection, nodeIds: List[NodeId])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  private def fromRaw(raw: Raw): Validation[ValidationError, CurveValuationRequest] =
    create(raw.selection, raw.nodeIds)

  private def toRaw(r: CurveValuationRequest): Raw = Raw(r.selection, r.nodeIds)

  /** Decode runs `create`, so no value of this type exists that breaks the
    * bound. */
  given codec: JsonCodec[CurveValuationRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(toRaw),
    JsonDecoder[Raw].mapOrFail(raw =>
      fromRaw(raw).toEither.left.map(_.toChunk.map(ValidationError.describe).mkString("; ")))
  )

  /** Derived from `Raw`, the shape the codec reads and writes (ADR-038 §1): the
    * constructor is private. */
  given schema: Schema[CurveValuationRequest] =
    Schema.derived[Raw].map(raw => fromRaw(raw).toEither.toOption)(toRaw)

/** Body of the multi-node curve read: the valuations to answer. */
final case class LECCurvesMultiRequest private (valuations: List[CurveValuationRequest])

object LECCurvesMultiRequest:

  /** The most valuations one request states. Chosen by this project: each
    * valuation whose selection is not inherent costs one mitigated fold. */
  val MaxValuations: NonNegativeInt = 16

  /** At least one valuation, at most `MaxValuations`, and no two with equal
    * selections. Every breach is reported together.
    *
    * {{{
    * create(Nil)                                 // fails: Required at request.Valuations
    * create(List(inherentOfA, inherentOfB))      // fails: DuplicateSelection(2) at request.Valuations
    * create(List(inherentOfA, residualOfB))      // LECCurvesMultiRequest(List(inherentOfA, residualOfB))
    * }}}
    */
  def create(valuations: List[CurveValuationRequest]): Validation[ValidationError, LECCurvesMultiRequest] =
    val location = ErrorLocation.requestField(FieldName.Valuations)

    val presentV: Validation[ValidationError, Unit] =
      if valuations.isEmpty then Validation.fail(ValidationError(location, FieldProblem.Required))
      else Validation.unit

    val countV: Validation[ValidationError, Unit] =
      ValidationUtil.requireAtMost(
        BoundedCollection.Valuations, NonNegativeInt.count(valuations.size), MaxValuations, location)

    val selections = valuations.map(_.selection)
    val distinctV: Validation[ValidationError, Unit] =
      Validation.validateAll(
        selections.distinct
          .map(selection => selections.count(_ == selection))
          .filter(_ > 1)
          .map(occurrences =>
            Validation.fail(
              ValidationError(location, FieldProblem.DuplicateSelection(NonNegativeInt.count(occurrences)))))
      ).unit

    Validation.validateWith(presentV, countV, distinctV)((_, _, _) => new LECCurvesMultiRequest(valuations))

  private case class Raw(valuations: List[CurveValuationRequest])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  private def fromRaw(raw: Raw): Validation[ValidationError, LECCurvesMultiRequest] = create(raw.valuations)

  private def toRaw(r: LECCurvesMultiRequest): Raw = Raw(r.valuations)

  /** Decode runs `create`, so no value of this type exists that breaks a
    * bound or states one selection twice. */
  given codec: JsonCodec[LECCurvesMultiRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(toRaw),
    JsonDecoder[Raw].mapOrFail(raw =>
      fromRaw(raw).toEither.left.map(_.toChunk.map(ValidationError.describe).mkString("; ")))
  )

  /** Derived from `Raw`, the shape the codec reads and writes (ADR-038 §1): the
    * constructor is private. */
  given schema: Schema[LECCurvesMultiRequest] =
    Schema.derived[Raw].map(raw => fromRaw(raw).toEither.toOption)(toRaw)

/** Body of the exceedance-probability read: the selection the figure is
  * computed under. The node and the threshold are in the path and the query
  * string.
  */
final case class ProbOfExceedanceRequest(selection: MitigationSelection)

object ProbOfExceedanceRequest:
  given codec: JsonCodec[ProbOfExceedanceRequest] = DeriveJsonCodec.gen[ProbOfExceedanceRequest]
  given schema: Schema[ProbOfExceedanceRequest]   = Schema.derived[ProbOfExceedanceRequest]
