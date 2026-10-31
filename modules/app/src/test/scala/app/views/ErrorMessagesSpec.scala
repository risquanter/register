package app.views

import zio.test.*
import sttp.model.StatusCode

import app.core.{LocalProblem, RequestFailure}
import com.risquanter.register.domain.data.iron.{TelemetryPlaceholders, TraceId, TreeId}
import com.risquanter.register.domain.errors as E
import com.risquanter.register.http.errors.{RejectionDetail, WireError}

/** Which sentence an inline view error display gets for each kind of failure.
  *
  * `viewFailed` matches every case of `ViewFailure`, so a new case is a compile
  * error there until it has a sentence. What the compiler cannot check is which
  * sentence each case got, and that is what these pin: one case per arm, so a
  * rearranged match that still compiles cannot silently swap two sentences.
  *
  * The companion to this is `GlobalErrorSpec`, which pins the same failures as
  * the banner shows them. Both read these functions, which is why the two tiers
  * cannot drift apart (ADR-019 Pattern 7).
  */
object ErrorMessagesSpec extends ZIOSpecDefault:

  private val trace: TraceId = TelemetryPlaceholders.UnlinkedTrace

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("ErrorMessages")(viewFailedSuite, viewFailedLinesSuite)

  private val blankName: E.ValidationError =
    E.ValidationError(E.ErrorLocation.requestField(E.FieldName.Name), E.ValidationErrorCode.REQUIRED_FIELD, "must not be empty")

  private val badProbability: E.ValidationError =
    E.ValidationError(E.ErrorLocation.requestField(E.FieldName.Probability), E.ValidationErrorCode.INVALID_RANGE, "must be between 0 and 1")

  private val treeId: TreeId = TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get

  private val viewFailedLinesSuite =
    suite("ErrorMessages.viewFailedLines")(
      test("a refusal the form made here gives one line per refusal") {
        assertTrue(
          ErrorMessages.viewFailedLines(LocalProblem.FormInvalid(List(blankName, badProbability))) ==
            List("must not be empty", "must be between 0 and 1")
        )
      },
      test("a request the server refused gives one line per refusal") {
        val failure = RequestFailure.Wire(WireError.RequestRejected(List(blankName, badProbability), trace))
        assertTrue(ErrorMessages.viewFailedLines(failure) == List("must not be empty", "must be between 0 and 1"))
      },
      test("a tree's fields the server refused give one line per refusal") {
        val failure = RequestFailure.Wire(
          WireError.ValidationRejected(treeId, RejectionDetail.Fields(List(blankName, badProbability)), trace)
        )
        assertTrue(ErrorMessages.viewFailedLines(failure) == List("must not be empty", "must be between 0 and 1"))
      },
      test("any other failure gives the one sentence viewFailed gives") {
        val failure = RequestFailure.Wire(WireError.InternalFault(trace))
        assertTrue(ErrorMessages.viewFailedLines(failure) == List(ErrorMessages.InternalFault))
      }
    )

  private val viewFailedSuite =
    suite("ErrorMessages.viewFailed")(
      test("a failure the server answered with reads the same sentence the banner reads") {
        val error = WireError.InternalFault(trace)
        assertTrue(
          ErrorMessages.viewFailed(RequestFailure.Wire(error)) == ErrorMessages.wire(error),
          ErrorMessages.viewFailed(RequestFailure.Wire(error)) == ErrorMessages.InternalFault
        )
      },
      test("a request that never arrived says so, and does not repeat the browser's own wording") {
        val failure = RequestFailure.Transport(new RuntimeException("Failed to fetch"))
        assertTrue(
          ErrorMessages.viewFailed(failure) == ErrorMessages.TransportFailed,
          !ErrorMessages.viewFailed(failure).contains("Failed to fetch")
        )
      },
      // The arm this case exists for: an answer that arrived and could not be
      // read is not a request that never arrived, and only one of the two may
      // promise that nothing was changed.
      test("an unreadable answer names its status and makes no promise about what changed") {
        val failure = RequestFailure.Undecodable(StatusCode.BadGateway)
        assertTrue(
          ErrorMessages.viewFailed(failure) == ErrorMessages.undecodableAnswer(StatusCode.BadGateway),
          ErrorMessages.viewFailed(failure).contains("502"),
          !ErrorMessages.viewFailed(failure).contains("Nothing was changed")
        )
      },
      test("input the form rejected here shows every entry, not only the first") {
        val errors = List(
          E.ValidationError(
            E.ErrorLocation.requestField(E.FieldName.Name),
            E.ValidationErrorCode.REQUIRED_FIELD,
            "must not be empty"
          ),
          E.ValidationError(
            E.ErrorLocation.requestField(E.FieldName.Probability),
            E.ValidationErrorCode.INVALID_FORMAT,
            "must be between 0 and 1"
          )
        )
        val sentence = ErrorMessages.viewFailed(LocalProblem.FormInvalid(errors))
        assertTrue(
          sentence.contains("must not be empty"),
          sentence.contains("must be between 0 and 1")
        )
      },
      test("an action needing a workspace the browser does not hold says what starts one") {
        assertTrue(
          ErrorMessages.viewFailed(LocalProblem.NoActiveWorkspace) == ErrorMessages.NoActiveWorkspace
        )
      }
    )

end ErrorMessagesSpec
