package com.risquanter.register.domain.data

import zio.NonEmptyChunk
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.http.errors.BindProblem

/** One mitigation's resolution against one tree version. `Failed` carries why
  * the predicate does not bind against that version, such as a quoted node
  * that was renamed or deleted. It still contributes an empty applied scope: a
  * predicate that does not bind makes that one mitigation a no-op and never
  * fails a request.
  */
@jsonDiscriminator(ScopeOutcome.TagField)
enum ScopeOutcome:
  case Resolved(scope: Set[NodeId])
  case Failed(errors: NonEmptyChunk[BindProblem])

  def toEither: Either[NonEmptyChunk[BindProblem], Set[NodeId]] = this match
    case Resolved(scope) => Right(scope)
    case Failed(errs)    => Left(errs)

  def scopeOrEmpty: Set[NodeId] = this match
    case Resolved(scope) => scope
    case Failed(_)       => Set.empty

  def failures: Option[NonEmptyChunk[BindProblem]] = this match
    case Resolved(_)  => None
    case Failed(errs) => Some(errs)

object ScopeOutcome:
  /** The key naming which case a payload states, read by the decoder and
    * published in the schema. */
  final val TagField = "kind"

  /** A failure's problems cross as a non-empty array, so a failure that names
    * no problem cannot be decoded. */
  private given nonEmptyChunkEncoder[A: JsonEncoder]: JsonEncoder[NonEmptyChunk[A]] =
    IronTapirCodecs.nonEmptyEncoder[NonEmptyChunk[A], A](_.toList)
  private given nonEmptyChunkDecoder[A: JsonDecoder]: JsonDecoder[NonEmptyChunk[A]] =
    IronTapirCodecs.nonEmptyDecoder[NonEmptyChunk[A], A](NonEmptyChunk.fromIterableOption)
  private given nonEmptyChunkSchema[A: Schema]: Schema[NonEmptyChunk[A]] =
    IronTapirCodecs.nonEmptySchema[NonEmptyChunk[A], A](_.toList, NonEmptyChunk.fromIterableOption)

  given codec: JsonCodec[ScopeOutcome] = DeriveJsonCodec.gen[ScopeOutcome]

  given schema: Schema[ScopeOutcome] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[ScopeOutcome]
