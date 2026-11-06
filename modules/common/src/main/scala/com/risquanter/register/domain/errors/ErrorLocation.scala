package com.risquanter.register.domain.errors

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration

import com.risquanter.register.domain.data.iron.{
  CommitHash, MitigationId, NodeId, NonNegativeInt, NonNegativeLong, PositiveDouble, PositiveLong,
  SafeName, ScenarioName, SeedEntityId, SeedVarId, TreeId, given
}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

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
  * A case names what a refusal or a merge conflict is about, which is not
  * always a single field. A
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
  case Mitigations, Precedence, Cap, Factor, TargetingSource
  case Deductible, Threshold

  // References to a revision or a branch
  case Branch, Scenario, At, Source, ToCommit, CommitHash, ContentHash

  // Credentials, identity and configuration
  case WorkspaceKey, WorkspaceKeyHash, UserId, Email, Url, TraceId

  // Computed values, which no request carries
  case AggregatedLoss, MitigatedResult

  /** The error-accumulation branch that cannot be reached: a failed refinement
    * always produces at least one error, so an empty list has no field to
    * report. */
  case Unknown

object FieldName:
  given codec: JsonCodec[FieldName] = DeriveJsonCodec.gen[FieldName]

  given schema: Schema[FieldName] =
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
  * The browser matches on these cases to route an error to the controls that
  * show it. Each form assigns every case by name, so a case added here, or a
  * `FieldName` case, fails the build until each form is given an arm for it.
  */
@jsonDiscriminator(TagField)
enum ErrorLocation:
  /** A field of some subject. */
  case Field(subject: ErrorSubject, field: FieldName)

  /** An HTTP request header. A closed set rather than a name, so no spelling a
    * client never supplied can reach the wire as free text. */
  case Header(header: RequestHeaderName)

  /** A query parameter of the request, on the same terms as `Header`: the set is
    * closed by this application's endpoint definitions. */
  case QueryParameter(param: RequestQueryParam)

  /** The failure is about the whole of whatever was being validated, with no
    * narrower location. A request body with no field to blame, or a tree built
    * from a store read or a merge, where no field of a request exists at all. */
  case Whole

object ErrorLocation:
  given codec: JsonCodec[ErrorLocation] = DeriveJsonCodec.gen[ErrorLocation]

  given schema: Schema[ErrorLocation] =
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
    case QueryParameter(param) => param.wireName
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

/** The query parameters this application's endpoints declare.
  *
  * Each spelling is declared rather than derived from the case name, on the
  * same terms as `RequestHeaderName`: the string is a contract with the URL, so
  * a Scala rename must not reach it (ADR-037). The set is closed by the endpoint
  * definitions, and Tapir reports a decode failure against a declared input, so
  * the name comes from the definition and never from the request.
  */
@jsonDiscriminator(TagField)
enum RequestQueryParam(val wireName: String):
  /** How many entries the tree history endpoint returns. */
  case Limit extends RequestQueryParam("n")

  /** The commit a point-in-time read is pinned to. */
  case At extends RequestQueryParam("at")

  /** The two revisions a changed-nodes comparison reads: each side is a branch
    * choice, and each may be pinned to a commit. */
  case SideA    extends RequestQueryParam("a")
  case SideAPin extends RequestQueryParam("aAt")
  case SideB    extends RequestQueryParam("b")
  case SideBPin extends RequestQueryParam("bAt")

  /** The loss figure an exceedance read answers for. */
  case Threshold extends RequestQueryParam("threshold")

  /** The entity id a bootstrapped workspace draws with. */
  case SeedEntityId extends RequestQueryParam("seedEntityId")

  /** Whether an analysis read carries each leaf's provenance record. */
  case IncludeProvenance extends RequestQueryParam("includeProvenance")

  /** Whether a multi-node read leaves out a node absent at the revision read. */
  case OmitAbsent extends RequestQueryParam("omitAbsent")

object RequestQueryParam:
  given codec: JsonCodec[RequestQueryParam] = DeriveJsonCodec.gen[RequestQueryParam]

  given schema: Schema[RequestQueryParam] =
    given Configuration = discriminated
    Schema.derived[RequestQueryParam]

  /** The declared parameter carrying this wire name, or `None` for a name this
    * application does not declare. An exact match, because a query parameter
    * name is case-sensitive.
    *
    * {{{
    * fromWireName("aAt") // Some(SideAPin)
    * fromWireName("aat") // None
    * }}}
    */
  def fromWireName(name: String): Option[RequestQueryParam] =
    values.find(_.wireName == name)

/** What a value's text was expected to read as, for a refusal of text that could
  * not be read at all — as against a value that was read and then refused. */
@jsonDiscriminator(TagField)
enum ExpectedInput:
  /** A decimal number. */
  case Number

  /** An integer. */
  case WholeNumber

  /** A comma-separated list of numbers. */
  case NumberList

  /** One value, where the request carried several for the same input. */
  case SingleValue

  /** Whatever type the endpoint declares for the input. */
  case Declared

object ExpectedInput:
  given codec: JsonCodec[ExpectedInput] = DeriveJsonCodec.gen[ExpectedInput]

  given schema: Schema[ExpectedInput] =
    given Configuration = discriminated
    Schema.derived[ExpectedInput]

/** The character set a name admits, for a refusal naming a character outside it. */
@jsonDiscriminator(TagField)
enum Alphabet:
  /** Letters, digits, spaces, hyphens and forward slashes. */
  case NodeName

  /** Letters, digits, spaces, hyphens and underscores. */
  case ScenarioName

object Alphabet:
  given codec: JsonCodec[Alphabet] = DeriveJsonCodec.gen[Alphabet]

  given schema: Schema[Alphabet] =
    given Configuration = discriminated
    Schema.derived[Alphabet]

/** The format a value had to follow, for a refusal of a value that did not. The
  * refused value itself never travels (ADR-035). */
@jsonDiscriminator(TagField)
enum ValueFormat:
  case Ulid, WorkspaceKey, WorkspaceKeyHash, UserId, Email, Url, SecureUrl, ExternalToken, TraceId
  case CommitHash, ContentHash, BranchName, StagingBranchName, DistributionTypeName

  /** A scenario name's first character, which must be a letter or a digit. */
  case ScenarioNameStart

object ValueFormat:
  given codec: JsonCodec[ValueFormat] = DeriveJsonCodec.gen[ValueFormat]

  given schema: Schema[ValueFormat] =
    given Configuration = discriminated
    Schema.derived[ValueFormat]

/** The range a value had to fall in, for a refusal of a value outside it. Each
  * case is one of the refinements in `ValidationUtil`, so the sentence states
  * the bound the refinement enforces. */
@jsonDiscriminator(TagField)
enum ValueRange:
  case NonNegative, Positive, SeedEntityId, SeedVarId
  case OpenUnitInterval, ClosedUnitInterval, Percent, Percentile
  case PositiveLoss, LossCap, MetalogTerms, RetentionFactor, ResidualProbability

object ValueRange:
  given codec: JsonCodec[ValueRange] = DeriveJsonCodec.gen[ValueRange]

  given schema: Schema[ValueRange] =
    given Configuration = discriminated
    Schema.derived[ValueRange]

/** Why one value was refused, with the values its sentence names.
  *
  * The sibling of `QueryProblem`: the query half of a content rejection carries
  * its structure in one discriminated enum, and this is the field half. A case
  * carries what a sentence about it has to name and nothing else, so the
  * sentence is written where it is shown — `ValidationMessages.render` — and
  * no text a client submitted can travel inside a refusal (ADR-035). The
  * location on the `ValidationError` names the field, so a case does not
  * repeat it: `Required` at the name field is the name being required, and a
  * case about a stored node names nothing the location does not already carry.
  *
  * The coarse classification a client filters on is derived by `code`, so the
  * two cannot disagree.
  */
@jsonDiscriminator(TagField)
enum FieldProblem:
  // Presence and readability
  case Required
  case Unreadable(expected: ExpectedInput)

  /** A validator the endpoint declares on the input refused the value, with no
    * refinement of this application's own behind it. */
  case DeclaredConstraintNotMet

  // The shape of one value
  case TooLong(limit: NonNegativeInt)
  case LengthOutOfRange(min: NonNegativeInt, max: NonNegativeInt)
  case InvalidCharacters(allowed: Alphabet)
  case WrongFormat(expected: ValueFormat)
  case OutOfRange(range: ValueRange)
  case ScenarioNameReserved

  // Names and seed identity within a tree
  case ReservedNodeNames(names: List[SafeName.SafeName])
  case DuplicateNodeName(name: SafeName.SafeName, occurrences: NonNegativeInt)
  case DuplicateMitigationName(name: SafeName.SafeName, occurrences: NonNegativeInt)
  case DuplicateMitigationId(id: MitigationId, occurrences: NonNegativeInt)
  case SeedVarIdInUse(seedVarId: SeedVarId.SeedVarId, holders: List[SafeName.SafeName])
  case SeedVarIdImmutable
  case SeedVarIdExhausted(next: NonNegativeLong)
  case SeedVarHighWaterBelowMax(highWater: SeedVarId.SeedVarId, maxAssigned: SeedVarId.SeedVarId)
  case SeedVarHighWaterNotDerivable

  // Topology of a submitted tree, referenced by name
  case RootRequired
  case MultipleRoots
  case RootIdNotFound(rootId: NodeId)
  case ParentPortfolioRequired
  case ParentNotFound(parent: SafeName.SafeName)
  case ParentIsLeaf(parent: SafeName.SafeName)
  case CycleDetected(node: SafeName.SafeName)
  case PortfolioHasNoChildren(portfolios: List[SafeName.SafeName])
  case TooManyChildren(count: NonNegativeInt, limit: NonNegativeInt)
  case TooManyNodes(count: NonNegativeInt, limit: NonNegativeInt)
  case TooManyMitigations(count: NonNegativeInt, limit: NonNegativeInt)

  // A stored node named by a tree update; the location carries its id
  case StoredNodeListedTwice
  case StoredNodeIsLeaf
  case StoredNodeIsPortfolio
  case StoredNodeNotInTree

  // The index of a built tree, referenced by id
  case ChildParentMismatch(child: NodeId, declaredParent: Option[NodeId])
  case ChildNotFound(child: NodeId)
  case ParentDoesNotListChild(parent: NodeId)
  case ParentIdIsLeaf(parent: NodeId)
  case ParentIdNotFound(parent: NodeId)
  case NodeNotInIndex
  case LeafUnreachable

  // Loss distribution
  case AnchorCountOutOfRange(count: NonNegativeInt, min: NonNegativeInt, max: NonNegativeInt)
  case PercentileQuantileLengthMismatch(percentiles: NonNegativeInt, quantiles: NonNegativeInt)
  case PercentilesNotIncreasing
  case QuantilesNotIncreasing
  case TermsAboveAnchorCount
  case LossRangeNotOrdered(min: PositiveLong, max: PositiveLong)
  case MetalogFitFailed
  case LognormalFitFailed
  case TrialIdOutOfRange

  // Mitigation
  case PipelineEmpty(min: NonNegativeInt)
  case PipelineTooLong(count: NonNegativeInt, limit: NonNegativeInt)
  case CapMustExceedDeductible
  case AggregatedLossOverflow
  case MitigationEliminatesRisk
  case MitigationLowersMinLossBelowFloor(min: PositiveLong, minFactor: PositiveDouble)
  case MitigationLowersAnchorLossBelowFloor(min: PositiveDouble, minFactor: PositiveDouble)
  case TargetingPredicateUnparseable
  case TargetingQuantifierNotAllowed
  case TargetingFunctionNotAllowed
  case TargetingQuantifierDepthExceeded(limit: NonNegativeInt, found: NonNegativeInt)
  case TargetingNoFreeVariable
  case TargetingSeveralFreeVariables
  case TargetingMitigationStateReferenced

  // Workspace limits and lookups the services report through ValidationFailed
  case SeedEntityIdInUse(seedEntityId: SeedEntityId.SeedEntityId)
  case MaxTreesReached(limit: NonNegativeInt)
  case NodeNotFound
  case NodeIdsEmpty
  case TreeIdAlreadyExists(treeId: TreeId)
  case TreeNameAlreadyExists(name: SafeName.SafeName)
  case ScenarioNotFound(scenario: ScenarioName.ScenarioName)
  case CommitNotFound(commit: CommitHash)
  case WorkspaceHasNoContent
  case RequiresIrminBackend

  /** The error-accumulation branch that cannot be reached: a failed refinement
    * always produces at least one error, so an empty list has no problem to
    * report. */
  case Unspecified

object FieldProblem:
  given codec: JsonCodec[FieldProblem] = DeriveJsonCodec.gen[FieldProblem]

  given schema: Schema[FieldProblem] =
    given Configuration = discriminated
    Schema.derived[FieldProblem]

  /** The coarse, filterable classification, derived from the case rather than
    * stored beside it — the same relation `WireError` has to its status. */
  def code(problem: FieldProblem): ValidationErrorCode = problem match
    case Required                                   => ValidationErrorCode.REQUIRED_FIELD
    case Unreadable(_)                              => ValidationErrorCode.TYPE_MISMATCH
    case DeclaredConstraintNotMet                   => ValidationErrorCode.INVALID_FORMAT
    case TooLong(_)                                 => ValidationErrorCode.INVALID_LENGTH
    case LengthOutOfRange(_, _)                     => ValidationErrorCode.INVALID_LENGTH
    case InvalidCharacters(_)                       => ValidationErrorCode.INVALID_PATTERN
    case WrongFormat(_)                             => ValidationErrorCode.INVALID_FORMAT
    case OutOfRange(_)                              => ValidationErrorCode.INVALID_RANGE
    case ScenarioNameReserved                       => ValidationErrorCode.INVALID_FORMAT
    case ReservedNodeNames(_)                       => ValidationErrorCode.RESERVED_NAME
    case DuplicateNodeName(_, _)                    => ValidationErrorCode.AMBIGUOUS_REFERENCE
    case DuplicateMitigationName(_, _)              => ValidationErrorCode.AMBIGUOUS_REFERENCE
    case DuplicateMitigationId(_, _)                => ValidationErrorCode.DUPLICATE_VALUE
    case SeedVarIdInUse(_, _)                       => ValidationErrorCode.DUPLICATE_VALUE
    case SeedVarIdImmutable                         => ValidationErrorCode.CONSTRAINT_VIOLATION
    case SeedVarIdExhausted(_)                      => ValidationErrorCode.CONSTRAINT_VIOLATION
    case SeedVarHighWaterBelowMax(_, _)             => ValidationErrorCode.CONSTRAINT_VIOLATION
    case SeedVarHighWaterNotDerivable               => ValidationErrorCode.CONSTRAINT_VIOLATION
    case RootRequired                               => ValidationErrorCode.REQUIRED_FIELD
    case MultipleRoots                              => ValidationErrorCode.AMBIGUOUS_REFERENCE
    case RootIdNotFound(_)                          => ValidationErrorCode.CONSTRAINT_VIOLATION
    case ParentPortfolioRequired                    => ValidationErrorCode.REQUIRED_FIELD
    case ParentNotFound(_)                          => ValidationErrorCode.MISSING_REFERENCE
    case ParentIsLeaf(_)                            => ValidationErrorCode.INVALID_NODE_TYPE
    case CycleDetected(_)                           => ValidationErrorCode.CONSTRAINT_VIOLATION
    case PortfolioHasNoChildren(_)                  => ValidationErrorCode.EMPTY_COLLECTION
    case TooManyChildren(_, _)                      => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TooManyNodes(_, _)                         => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TooManyMitigations(_, _)                   => ValidationErrorCode.CONSTRAINT_VIOLATION
    case StoredNodeListedTwice                      => ValidationErrorCode.DUPLICATE_VALUE
    case StoredNodeIsLeaf                           => ValidationErrorCode.INVALID_NODE_TYPE
    case StoredNodeIsPortfolio                      => ValidationErrorCode.INVALID_NODE_TYPE
    case StoredNodeNotInTree                        => ValidationErrorCode.MISSING_REFERENCE
    case ChildParentMismatch(_, _)                  => ValidationErrorCode.CONSTRAINT_VIOLATION
    case ChildNotFound(_)                           => ValidationErrorCode.CONSTRAINT_VIOLATION
    case ParentDoesNotListChild(_)                  => ValidationErrorCode.CONSTRAINT_VIOLATION
    case ParentIdIsLeaf(_)                          => ValidationErrorCode.CONSTRAINT_VIOLATION
    case ParentIdNotFound(_)                        => ValidationErrorCode.CONSTRAINT_VIOLATION
    case NodeNotInIndex                             => ValidationErrorCode.CONSTRAINT_VIOLATION
    case LeafUnreachable                            => ValidationErrorCode.CONSTRAINT_VIOLATION
    case AnchorCountOutOfRange(_, _, _)             => ValidationErrorCode.CONSTRAINT_VIOLATION
    case PercentileQuantileLengthMismatch(_, _)     => ValidationErrorCode.INVALID_COMBINATION
    case PercentilesNotIncreasing                   => ValidationErrorCode.INVALID_COMBINATION
    case QuantilesNotIncreasing                     => ValidationErrorCode.INVALID_COMBINATION
    case TermsAboveAnchorCount                      => ValidationErrorCode.INVALID_COMBINATION
    case LossRangeNotOrdered(_, _)                  => ValidationErrorCode.INVALID_RANGE
    case MetalogFitFailed                           => ValidationErrorCode.INVALID_METALOG_PARAMS
    case LognormalFitFailed                         => ValidationErrorCode.INVALID_LOGNORMAL_PARAMS
    case TrialIdOutOfRange                          => ValidationErrorCode.CONSTRAINT_VIOLATION
    case PipelineEmpty(_)                          => ValidationErrorCode.CONSTRAINT_VIOLATION
    case PipelineTooLong(_, _)                      => ValidationErrorCode.CONSTRAINT_VIOLATION
    case CapMustExceedDeductible                    => ValidationErrorCode.INVALID_COMBINATION
    case AggregatedLossOverflow                     => ValidationErrorCode.CONSTRAINT_VIOLATION
    case MitigationEliminatesRisk                   => ValidationErrorCode.CONSTRAINT_VIOLATION
    case MitigationLowersMinLossBelowFloor(_, _)    => ValidationErrorCode.INVALID_LOGNORMAL_PARAMS
    case MitigationLowersAnchorLossBelowFloor(_, _) => ValidationErrorCode.INVALID_METALOG_PARAMS
    case TargetingPredicateUnparseable              => ValidationErrorCode.INVALID_FORMAT
    case TargetingQuantifierNotAllowed              => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TargetingFunctionNotAllowed                => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TargetingQuantifierDepthExceeded(_, _)     => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TargetingNoFreeVariable                    => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TargetingSeveralFreeVariables              => ValidationErrorCode.CONSTRAINT_VIOLATION
    case TargetingMitigationStateReferenced         => ValidationErrorCode.CONSTRAINT_VIOLATION
    case SeedEntityIdInUse(_)                       => ValidationErrorCode.DUPLICATE_VALUE
    case MaxTreesReached(_)                         => ValidationErrorCode.CONSTRAINT_VIOLATION
    case NodeNotFound                               => ValidationErrorCode.NOT_FOUND
    case NodeIdsEmpty                               => ValidationErrorCode.EMPTY_COLLECTION
    case TreeIdAlreadyExists(_)                     => ValidationErrorCode.DUPLICATE_VALUE
    case TreeNameAlreadyExists(_)                   => ValidationErrorCode.DUPLICATE_VALUE
    case ScenarioNotFound(_)                        => ValidationErrorCode.NOT_FOUND
    case CommitNotFound(_)                          => ValidationErrorCode.NOT_FOUND
    case WorkspaceHasNoContent                      => ValidationErrorCode.NOT_FOUND
    case RequiresIrminBackend                       => ValidationErrorCode.NOT_SUPPORTED
    case Unspecified                                => ValidationErrorCode.CONSTRAINT_VIOLATION
