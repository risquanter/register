package com.risquanter.register.infra.irmin.model

import _root_.zio.json.*
import _root_.zio.NonEmptyChunk
import _root_.zio.prelude.Validation

import java.time.{DateTimeException, Instant}

import com.risquanter.register.domain.data.iron.{CommitHash, StoreBranch, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, ExpectedInput, FieldProblem, ValidationError}
import com.risquanter.register.infra.irmin.CommitMessage

/**
  * Irmin commit information - metadata for each write operation.
  *
  * Corresponds to the GraphQL `Info` type in Irmin's schema, of which the two
  * fields this service reads are selected:
  * ```graphql
  * type Info {
  *   date: String!
  *   message: String!
  * }
  * ```
  *
  * Both are refined where Irmin's answer arrives (ADR-039): the date is the
  * instant Irmin's epoch-seconds text names, and the message is one this
  * service wrote, because the store holds no other.
  *
  * @param date When the commit was made
  * @param message The message this service wrote for the commit
  */
final case class IrminInfo(
    date: Instant,
    message: CommitMessage
)

object IrminInfo:
  /** Commit information as Irmin's GraphQL answer carries it, before it is
    * refined. */
  final case class Raw(date: String, message: String)

  object Raw:
    given JsonCodec[Raw] = DeriveJsonCodec.gen[Raw]

  /** Refines both fields, accumulating their refusals. Irmin writes the date
    * as a whole number of seconds since the epoch; text that is not a whole
    * number is refused as one, and a count outside the range an `Instant`
    * holds is refused as the instant the reader declares. A message is refused
    * by `CommitMessage.parse`.
    * {{{
    * refine(Raw("0", "workspace:<ws>:risk-tree:<t>:update"))
    * // Right(IrminInfo(1970-01-01T00:00:00Z, TreeWritten(ws, t, Update)))
    * refine(Raw("yesterday", "hello"))
    * // Left(NonEmptyChunk(ValidationError(Whole, Unreadable(WholeNumber)),
    * //                   ValidationError(Whole, Unreadable(Declared))))
    * }}}
    */
  def refine(raw: Raw): Either[NonEmptyChunk[ValidationError], IrminInfo] =
    Validation.validateWith(refineDate(raw.date), ValidationUtil.toValidation(CommitMessage.parse(raw.message).left.map(_.toList)))(IrminInfo.apply)
      .toEither

  private def refineDate(text: String): Validation[ValidationError, Instant] =
    text.trim.toLongOption match
      case None => Validation.fail(unreadable(ExpectedInput.WholeNumber))
      case Some(epochSeconds) =>
        try Validation.succeed(Instant.ofEpochSecond(epochSeconds))
        catch case _: DateTimeException => Validation.fail(unreadable(ExpectedInput.Declared))

  private def unreadable(expected: ExpectedInput): ValidationError =
    ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(expected))

/**
  * Irmin commit - represents a point-in-time snapshot.
  *
  * Corresponds to the GraphQL `Commit` type:
  * ```graphql
  * type Commit {
  *   tree: Tree!
  *   parents: [CommitKey!]!
  *   info: Info!
  *   hash: Hash!
  *   key: CommitKey!
  * }
  * ```
  *
  * Every value it carries is refined where Irmin's answer arrives (ADR-039).
  *
  * @param hash Content-addressed hash of the commit
  * @param key Commit key (may differ from hash in packed format)
  * @param parents Parent commit keys (empty for initial commit)
  * @param info Commit metadata
  */
final case class IrminCommit(
    hash: CommitHash,
    key: CommitHash,
    parents: List[CommitHash],
    info: IrminInfo
)

object IrminCommit:
  /** A commit as Irmin's GraphQL answer carries it, before it is refined.
    * Every query that selects a commit selects all four fields, and Irmin's
    * schema declares `parents` non-null. */
  final case class Raw(hash: String, key: String, parents: List[String], info: IrminInfo.Raw)

  object Raw:
    given JsonCodec[Raw] = DeriveJsonCodec.gen[Raw]

  /** Refines every value the commit carries, accumulating a refusal for each
    * hash that is not a commit hash and each of the info's refusals.
    * {{{
    * refine(Raw("abc", "abc", Nil, IrminInfo.Raw("0", message)))
    * // Left(NonEmptyChunk(ValidationError(Whole, WrongFormat(CommitHash)),
    * //                   ValidationError(Whole, WrongFormat(CommitHash))))
    * }}}
    */
  def refine(raw: Raw): Either[NonEmptyChunk[ValidationError], IrminCommit] =
    Validation.validateWith(
      refineHash(raw.hash),
      refineHash(raw.key),
      Validation.validateAll(raw.parents.map(refineHash)),
      ValidationUtil.toValidation(IrminInfo.refine(raw.info).left.map(_.toList))
    )(IrminCommit.apply)
      .toEither

  private def refineHash(text: String): Validation[ValidationError, CommitHash] =
    ValidationUtil.toValidation(CommitHash.fromString(text, ErrorLocation.Whole))

/**
  * Irmin branch - named reference to a commit.
  *
  * Corresponds to the GraphQL `Branch` type:
  * ```graphql
  * type Branch {
  *   name: BranchName!
  *   head: Commit
  *   tree: Tree!
  * }
  * ```
  *
  * The name is refined where Irmin's answer arrives (ADR-039): every branch in
  * the store was named by this service, as main, a scenario branch or a merge's
  * staging branch, so a name that is none of those is refused.
  *
  * @param name The branch, as this service names branches
  * @param head Current head commit (None if branch is empty)
  */
final case class IrminBranch(
    name: StoreBranch,
    head: Option[IrminCommit]
)
