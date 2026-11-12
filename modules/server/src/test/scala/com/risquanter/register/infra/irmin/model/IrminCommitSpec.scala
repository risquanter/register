package com.risquanter.register.infra.irmin.model

import java.time.Instant
import zio.NonEmptyChunk
import zio.test.*

import com.risquanter.register.domain.data.iron.WorkspaceId
import com.risquanter.register.domain.errors.{ErrorLocation, ExpectedInput, FieldProblem, ValidationError, ValueFormat}
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.infra.irmin.CommitMessage
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/** `IrminCommit.refine` and `IrminInfo.refine`: a commit Irmin returned is a
  * commit of refined values, or one refusal for every value that does not
  * refine. */
object IrminCommitSpec extends ZIOSpecDefault:

  private val message: CommitMessage =
    CommitMessage.TreeWritten(WorkspaceId(safeId("commit-ws")), treeId("commit-tree"), TreeWriteKind.Update)

  private val rawInfo = IrminInfo.Raw("0", CommitMessage.render(message))
  private val info    = IrminInfo(Instant.EPOCH, message)

  private val validHash  = "a" * 40
  private val parentHash = "b" * 40

  /** One hex digit short of a commit hash. */
  private val shortHash = "c" * 39

  private val hashRefusal: ValidationError =
    ValidationError(ErrorLocation.Whole, FieldProblem.WrongFormat(ValueFormat.CommitHash))

  private def unreadable(expected: ExpectedInput): ValidationError =
    ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(expected))

  def spec = suite("IrminCommit")(
    suite("refine")(
      test("a commit of commit hashes and refined info is refined to those values") {
        val refined = IrminCommit.refine(IrminCommit.Raw(validHash, validHash, List(parentHash), rawInfo))
        assertTrue(
          refined.map(_.hash.value) == Right(validHash),
          refined.map(_.key.value) == Right(validHash),
          refined.map(_.parents.map(_.value)) == Right(List(parentHash)),
          refined.map(_.info) == Right(info)
        )
      },
      test("a hash one digit short is refused as a commit hash") {
        assertTrue(IrminCommit.refine(IrminCommit.Raw(shortHash, validHash, Nil, rawInfo)) == Left(NonEmptyChunk(hashRefusal)))
      },
      test("every value that does not refine is refused, and the refusals accumulate") {
        val refused = IrminCommit.refine(IrminCommit.Raw(shortHash, shortHash, List(validHash, shortHash), IrminInfo.Raw("0", "hello")))
        assertTrue(refused == Left(NonEmptyChunk(hashRefusal, hashRefusal, hashRefusal, unreadable(ExpectedInput.Declared))))
      }
    ),
    suite("IrminInfo.refine")(
      test("an epoch-seconds date and a message this server wrote are refined") {
        assertTrue(IrminInfo.refine(IrminInfo.Raw("1700000000", CommitMessage.render(message))) ==
          Right(IrminInfo(Instant.ofEpochSecond(1700000000L), message)))
      },
      test("a date that is not a whole number is refused as one") {
        assertTrue(IrminInfo.refine(IrminInfo.Raw("2026-01-01T00:00:00Z", CommitMessage.render(message))) ==
          Left(NonEmptyChunk(unreadable(ExpectedInput.WholeNumber))))
      },
      test("a whole number outside an instant's range is refused as the instant the reader declares") {
        assertTrue(IrminInfo.refine(IrminInfo.Raw(Long.MaxValue.toString, CommitMessage.render(message))) ==
          Left(NonEmptyChunk(unreadable(ExpectedInput.Declared))))
      },
      test("the date's and the message's refusals accumulate") {
        assertTrue(IrminInfo.refine(IrminInfo.Raw("yesterday", "hello")) ==
          Left(NonEmptyChunk(unreadable(ExpectedInput.WholeNumber), unreadable(ExpectedInput.Declared))))
      }
    )
  )
