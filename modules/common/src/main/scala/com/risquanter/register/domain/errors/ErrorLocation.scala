package com.risquanter.register.domain.errors

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, NonNegativeInt, given}

/** The key naming which case a value is, read by every decoder in this file and
  * published in every schema. Stated once so the two cannot disagree. */
private final val TagField = "kind"

/** Names the discriminator in a derived schema. Each case's published value is
  * its own name, which is what the derived codecs write. */
private def discriminated: Configuration =
  Configuration.default.withDiscriminator(TagField)

/** Which submitted collection a node came from.
  *
  * A tree update submits four collections, each indexed from zero
  * independently, so a position alone names four different rows.
  */
@jsonDiscriminator(TagField)
enum SubmittedCollection:
  case Portfolios, Leaves, NewPortfolios, NewLeaves

object SubmittedCollection:
  given codec: JsonCodec[SubmittedCollection] = DeriveJsonCodec.gen[SubmittedCollection]

  given schema: Schema[SubmittedCollection] =
    import sttp.tapir.generic.auto.*
    given Configuration = discriminated
    Schema.derived[SubmittedCollection]

/** What a failure is about.
  *
  * A subject and a field vary independently — the same field can fail under any
  * subject — so they are two components rather than one spelled-out path. None
  * of these cases can hold a value that must not cross the client boundary
  * (ADR-036): there is no case for a `BranchRef` and none for an absolute
  * storage path.
  */
@jsonDiscriminator(TagField)
enum ErrorSubject:
  /** The request body itself, where no narrower subject applies. */
  case Request

  /** A node the form is still assembling, which has no identifier yet. */
  case DraftNode

  /** A node pointed at by its position in a submitted list, because it has no
    * identifier yet or because its identifier is what failed to refine. The
    * collection is part of the subject because the submitted lists are indexed
    * independently. */
  case NodeAtIndex(collection: SubmittedCollection, index: NonNegativeInt)

  /** A node denoted by its identifier — one the tree holds, or one a lookup
    * asked for and did not find. It carries no `TreeId`: the tree is named once
    * on `WireError.ValidationRejected`, and on the creation path no tree
    * identifier exists yet.
    */
  case ExistingNode(nodeId: NodeId)

  /** A mitigation, or the leaf transform belonging to one. */
  case Mitigation(mitigationId: MitigationId)

  /** The workspace the request is scoped to, for a limit the workspace holds
    * rather than the request. */
  case Workspace

object ErrorSubject:
  given codec: JsonCodec[ErrorSubject] = DeriveJsonCodec.gen[ErrorSubject]

  given schema: Schema[ErrorSubject] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = discriminated
    Schema.derived[ErrorSubject]

  /** The node at a zero-based position in one of the submitted collections.
    *
    * A position is produced by iterating the collection, so it is never
    * negative. A negative one would mean the caller passed something that is
    * not a position, and naming the request as a whole is the honest answer
    * then.
    *
    * {{{
    * nodeAtIndex(SubmittedCollection.NewLeaves, 2)  // NodeAtIndex(NewLeaves, 2)
    * nodeAtIndex(SubmittedCollection.NewLeaves, -1) // Request
    * }}}
    */
  def nodeAtIndex(collection: SubmittedCollection, index: Int): ErrorSubject =
    index.refineEither[GreaterEqual[0]].fold(_ => Request, NodeAtIndex(collection, _))

/** Which field or computed aspect failed.
  *
  * One vocabulary for every subject, because a field that can fail on a
  * submitted request can equally fail on a stored node. `PercentileAt` and
  * `QuantileAt` carry the element index as a number rather than as `[3]` inside
  * text; their index-less siblings are the whole-collection failures, since a
  * monotonicity violation names the collection and not an element.
  *
  * A case names what a refusal is about, which is not always a single field. A
  * rule over two fields is about the pair — `LossRange` and
  * `PercentileQuantilePairing` are those cases — because neither field is at
  * fault alone and either one can be edited to satisfy the rule. Which controls
  * display such a refusal is decided where the display is, not here.
  */
@jsonDiscriminator(TagField)
enum FieldName:
  // Identity and structure
  case Id, Name, ParentId, ParentName, ChildIds, RootId, Nodes, Trees
  case SeedVarHighWater

  /** How many entries a listing may return — the `n` parameter of the tree
    * history endpoint. */
  case Limit

  // Values a node declares
  case Probability, SeedVarId, SeedEntityId

  // Distribution shape
  case DistributionType, Terms, MinLoss, MaxLoss, Spec
  case Percentiles, Quantiles
  case PercentileAt(index: NonNegativeInt)
  case QuantileAt(index: NonNegativeInt)
  case ChildAt(index: NonNegativeInt)

  /** The minimum and maximum loss as a pair, for a rule about the relationship
    * between them rather than about either value. Neither field is at fault on
    * its own when the minimum is not below the maximum, and either one can be
    * edited to satisfy the rule, so the pair is what the refusal is about. */
  case LossRange

  /** The percentiles and quantiles as paired sequences, for a rule about the
    * pairing rather than about either sequence. A length mismatch leaves some
    * percentile without a quantile, and either sequence can be edited to
    * satisfy the rule. */
  case PercentileQuantilePairing

  // Mitigations and the transform parameters a mitigation spec carries
  case Mitigations, Cap, Factor, OverrideParams, TargetingSource
  case Deductible, Threshold

  // References to a revision or a branch
  case Branch, Scenario, TreeId, At, Source, ToCommit, CommitHash, ContentHash

  // Credentials, identity and configuration
  case WorkspaceKey, WorkspaceKeyHash, UserId, Email, Url, Token, TraceId

  // Computed values, which no request carries
  case AggregatedLoss, MitigatedResult

  /** The error-accumulation branch that cannot be reached: a failed refinement
    * always produces at least one error, so an empty list has no field to
    * report. */
  case Unknown

object FieldName:
  given codec: JsonCodec[FieldName] = DeriveJsonCodec.gen[FieldName]

  given schema: Schema[FieldName] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = discriminated
    Schema.derived[FieldName]

  /** The element case for a zero-based index into the submitted percentiles.
    *
    * An index is produced by iterating the collection, so it is never
    * negative. A negative one would mean the caller passed something that is
    * not an index, and naming the collection is the honest answer then.
    */
  def percentileAt(index: Int): FieldName =
    index.refineEither[GreaterEqual[0]].fold(_ => Percentiles, PercentileAt(_))

  /** The element case for a zero-based index into the submitted quantiles, on
    * the same terms as `percentileAt`. */
  def quantileAt(index: Int): FieldName =
    index.refineEither[GreaterEqual[0]].fold(_ => Quantiles, QuantileAt(_))

  /** The element case for a zero-based index into a portfolio's child list, on
    * the same terms as `percentileAt`. */
  def childAt(index: Int): FieldName =
    index.refineEither[GreaterEqual[0]].fold(_ => ChildIds, ChildAt(_))

/** Where an error is.
  *
  * The browser matches on these cases to route an error to the control that
  * failed. Each form maps only the locations it renders and sends everything
  * else to its submit banner, so a case added here reaches the banner until a
  * form is given an arm for it.
  */
@jsonDiscriminator(TagField)
enum ErrorLocation:
  /** A field of some subject. */
  case Field(subject: ErrorSubject, field: FieldName)

  /** An HTTP request header. A closed set rather than a name, so no spelling a
    * client never supplied can reach the wire as free text. */
  case Header(header: RequestHeaderName)

  /** The failure is about the whole of whatever was being validated, with no
    * narrower location. A request body with no field to blame, or a tree built
    * from a store read or a merge, where no field of a request exists at all. */
  case Whole

object ErrorLocation:
  given codec: JsonCodec[ErrorLocation] = DeriveJsonCodec.gen[ErrorLocation]

  given schema: Schema[ErrorLocation] =
    import sttp.tapir.generic.auto.*
    given Configuration = discriminated
    Schema.derived[ErrorLocation]

  /** A field of the request body, which is the common case. */
  def requestField(field: FieldName): ErrorLocation = Field(ErrorSubject.Request, field)

  /** A field of a node of the tree. */
  def nodeField(nodeId: NodeId, field: FieldName): ErrorLocation =
    Field(ErrorSubject.ExistingNode(nodeId), field)

  /** The location as diagnostic text, for a log line or a flattened message.
    *
    * Not user-facing text: a banner renders from the case itself, so that the
    * sentence and the values it names cannot drift (ADR-037).
    */
  def describe(location: ErrorLocation): String = location match
    case Field(subject, field) => s"${describeSubject(subject)}.${field.toString}"
    case Header(header)        => header.wireName
    case Whole                 => "request"

  private def describeSubject(subject: ErrorSubject): String = subject match
    case ErrorSubject.Request                => "request"
    case ErrorSubject.DraftNode              => "draft"
    case ErrorSubject.NodeAtIndex(coll, i)   => s"submitted $coll[$i]"
    case ErrorSubject.ExistingNode(nodeId)   => s"node ${nodeId.value}"
    case ErrorSubject.Mitigation(id)         => s"mitigation ${id.value}"
    case ErrorSubject.Workspace              => "workspace"

/** The request headers this application declares.
  *
  * Each spelling is declared rather than derived from the case name, because
  * the string is a contract with HTTP rather than with the encoder, so a Scala
  * rename must not reach it (ADR-037).
  *
  * The set is closed by this application's own endpoint definitions: Tapir
  * reports a decode failure against a declared input, so the name comes from
  * the endpoint definition and never from the request.
  */
@jsonDiscriminator(TagField)
enum RequestHeaderName(val wireName: String):
  /** `authedBaseEndpoint` and the workspace bootstrap endpoint. */
  case UserId extends RequestHeaderName("x-user-id")

  /** `BaseEndpoint.activeBranchHeader`. */
  case Branch extends RequestHeaderName("X-Branch")

  /** The scenario delete endpoint's compare-and-set precondition. */
  case IfMatch extends RequestHeaderName("If-Match")

object RequestHeaderName:
  given codec: JsonCodec[RequestHeaderName] = DeriveJsonCodec.gen[RequestHeaderName]

  given schema: Schema[RequestHeaderName] =
    import sttp.tapir.generic.auto.*
    given Configuration = discriminated
    Schema.derived[RequestHeaderName]

  /** The declared header carrying this wire name, or `None` for a name this
    * application does not declare. Matched without regard to case, because HTTP
    * header names are case-insensitive while these spellings are fixed text.
    *
    * {{{
    * fromWireName("x-branch") // Some(Branch)
    * fromWireName("Accept")   // None
    * }}}
    */
  def fromWireName(name: String): Option[RequestHeaderName] =
    values.find(_.wireName.equalsIgnoreCase(name))
