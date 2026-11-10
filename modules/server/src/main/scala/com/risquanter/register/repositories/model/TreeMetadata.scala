package com.risquanter.register.repositories.model

import java.time.Instant
import zio.json.*
import zio.prelude.{NonEmptyList, Validation}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, SafeName, SeedVarId, ValidationUtil}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, ExpectedInput, FieldName, FieldProblem, ValidationError}
import com.risquanter.register.http.responses.TreeMetadataFields

/** Metadata persisted for each risk tree under risk-trees/{treeId}/meta. */
final case class TreeMetadata(
  id: TreeId,
  name: SafeName.SafeName,
  rootId: NodeId,
  seedVarHighWater: SeedVarId.SeedVarId,
  schemaVersion: Int,
  createdAt: Instant,
  updatedAt: Instant
):
  /** The fields a merge compares. `updatedAt` is not among them, because every
    * save rewrites it. */
  def toMergeFields: TreeMetadataFields = TreeMetadataFields(name, rootId, seedVarHighWater)

object TreeMetadata:
  /** The `meta` format this server writes. Both the repository and the merge
    * write the `meta` value, so the version lives with the type.
    *
    * Version 2: nodes carry `seedVarId` and `meta` carries `seedVarHighWater`.
    * There is no decode path for version 1: such a store is wiped rather than
    * migrated. */
  val CurrentSchemaVersion: Int = 2

  /** Instants are stored as epoch milliseconds. */
  private given JsonEncoder[Instant] = JsonEncoder[Long].contramap(_.toEpochMilli)

  /** The one way a `meta` value is written. It is read back only through
    * `decodeValidated`. */
  given JsonEncoder[TreeMetadata] = DeriveJsonEncoder.gen[TreeMetadata]

  /** The stored form. The four fields a refusal can name are optional, so an
    * absent one is refused at that field. The version and the two instants have
    * no field to name, so an absent one leaves the text unreadable. */
  private final case class Raw(
    id: Option[String],
    name: Option[String],
    rootId: Option[String],
    seedVarHighWater: Option[Long],
    schemaVersion: Int,
    createdAt: Long,
    updatedAt: Long
  )
  private object Raw:
    given JsonDecoder[Raw] = DeriveJsonDecoder.gen[Raw]

  /** A stored `meta` value read back, refined at `subject` with every refusal
    * kept as a value (ADR-039). Text that is not the raw form's JSON is one
    * refusal of the whole.
    *
    * {{{
    * decodeValidated("""{"id":"…","name":"Ops","schemaVersion":2,"createdAt":0,"updatedAt":0}""", ErrorSubject.Tree(treeId))
    * // Left: Required at tree.RootId and at tree.SeedVarHighWater
    * }}}
    */
  def decodeValidated(json: String, subject: ErrorSubject): Either[NonEmptyList[ValidationError], TreeMetadata] =
    json.fromJson[Raw] match
      case Left(_) =>
        Left(NonEmptyList(ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(ExpectedInput.Json))))
      case Right(raw) =>
        def at(field: FieldName): ErrorLocation = ErrorLocation.Field(subject, field)
        def present[A](value: Option[A], field: FieldName): Validation[ValidationError, A] =
          Validation.fromOptionWith(ValidationError(at(field), FieldProblem.Required))(value)
        Validation
          .validateWith(
            present(raw.id, FieldName.Id).flatMap(id => toValidation(ValidationUtil.refineId(id, at(FieldName.Id)).map(TreeId(_)))),
            present(raw.name, FieldName.Name).flatMap(name => toValidation(ValidationUtil.refineName(name, at(FieldName.Name)))),
            present(raw.rootId, FieldName.RootId).flatMap(root => toValidation(ValidationUtil.refineId(root, at(FieldName.RootId)).map(NodeId(_)))),
            present(raw.seedVarHighWater, FieldName.SeedVarHighWater)
              .flatMap(hw => toValidation(ValidationUtil.refineSeedVarId(hw, at(FieldName.SeedVarHighWater))))
          )((id, name, rootId, highWater) =>
            TreeMetadata(id, name, rootId, highWater, raw.schemaVersion,
              Instant.ofEpochMilli(raw.createdAt), Instant.ofEpochMilli(raw.updatedAt)))
          .toEither
          .left.map(errors => NonEmptyList(errors.head, errors.tail*))
