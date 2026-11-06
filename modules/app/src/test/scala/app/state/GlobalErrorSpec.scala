package app.state

import java.time.Duration
import zio.test.*
import sttp.model.StatusCode

import app.core.RequestFailure
import app.views.ErrorMessages
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{ScenarioName, TelemetryPlaceholders, TraceId, TreeId}
import com.risquanter.register.domain.errors as E
import com.risquanter.register.http.errors.{
  Capability, ConflictKind, NotFoundKind, QueryProblem, RejectionDetail, WireError
}

/** Which failures the banner shows, and the sentence each failure gets where
  * its action was taken.
  *
  * `fromWireError` matches the wire cases exhaustively, so a new one is a
  * compile error there until it is placed. These tests pin where it was placed,
  * which the compiler cannot check.
  */
object GlobalErrorSpec extends ZIOSpecDefault:

  private val trace: TraceId = TelemetryPlaceholders.UnlinkedTrace

  private val treeId: TreeId =
    TreeId.fromString("01HX9ABCDE0000000000000001").toOption.get

  private val fieldErrors: List[E.ValidationError] = List(
    E.ValidationError(E.ErrorLocation.requestField(E.FieldName.Name), E.FieldProblem.Required)
  )

  private def wire(error: WireError): Option[GlobalError] =
    GlobalError.from(RequestFailure.Wire(error))

  private def expired(cause: ExpiryCause): WireError =
    WireError.WorkspaceExpired(cause, Duration.ofHours(72), Duration.ofHours(24), trace)

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("GlobalErrorSpec")(
      suite("the conditions the banner shows")(
        test("a transport failure means the server cannot be reached") {
          val failure = RequestFailure.Transport(new RuntimeException("Failed to fetch"))
          assertTrue(GlobalError.from(failure) == Some(GlobalError.ServerUnreachable))
        },
        test("a dependency outage carries the trace, because nothing else is actionable") {
          assertTrue(wire(WireError.DependencyUnavailable(trace)) == Some(GlobalError.DependencyDown(Some(trace))))
        },
        test("an expired workspace states the idle limit when inactivity ended it") {
          wire(expired(ExpiryCause.Inactivity)) match
            case Some(GlobalError.WorkspaceExpired(message)) =>
              assertTrue(message.contains("24 hours")) && assertTrue(!message.contains("72"))
            case other => throw MatchError(other)
        },
        test("an expired workspace states the absolute lifetime when the ceiling ended it") {
          wire(expired(ExpiryCause.Lifetime)) match
            case Some(GlobalError.WorkspaceExpired(message)) =>
              assertTrue(message.contains("72 hours")) && assertTrue(!message.contains("24"))
            case other => throw MatchError(other)
        }
      ),
      suite("the results shown only where the action was taken")(
        test("every other wire case reveals no condition") {
          val conflict = ConflictKind.MergeAlreadyRunning(ScenarioName.fromString("draft-a").toOption.get)
          val perView = List(
            WireError.RequestRejected(fieldErrors, trace),
            WireError.ValidationRejected(treeId, RejectionDetail.Fields(fieldErrors), trace),
            WireError.ValidationRejected(treeId, RejectionDetail.Query(QueryProblem.Unparseable("unexpected token", Some(7))), trace),
            WireError.NotFound(NotFoundKind.Unknown(), trace),
            WireError.NotFound(NotFoundKind.TreeNotAtCommit(), trace),
            WireError.AccessDenied(trace),
            WireError.Conflict(conflict, trace),
            WireError.NotSupported(Capability.Scenarios, trace),
            WireError.InternalFault(trace)
          )
          assertTrue(perView.forall(wire(_).isEmpty))
        },
        test("an unreadable answer reveals no condition, because the server was reached") {
          assertTrue(GlobalError.from(RequestFailure.Undecodable(StatusCode.BadGateway)).isEmpty)
        }
      ),
      suite("the sentences shown where the action was taken")(
        test("a refused query names the problem and its position") {
          val problem = QueryProblem.Unparseable("unexpected token", Some(7))
          val message = ErrorMessages.wire(WireError.ValidationRejected(treeId, RejectionDetail.Query(problem), trace))
          assertTrue(message.contains("unexpected token")) && assertTrue(message.contains("7"))
        },
        test("a conflict names the scenario it is about") {
          val conflict = ConflictKind.MergeAlreadyRunning(ScenarioName.fromString("draft-a").toOption.get)
          assertTrue(ErrorMessages.wire(WireError.Conflict(conflict, trace)).contains("draft-a"))
        },
        test("an absent capability names the capability") {
          assertTrue(ErrorMessages.wire(WireError.NotSupported(Capability.Scenarios, trace)).contains("Scenarios"))
        },
        test("a transport failure does not show the browser's own wording") {
          val message = ErrorMessages.viewFailed(RequestFailure.Transport(new RuntimeException("Failed to fetch")))
          assertTrue(message == ErrorMessages.TransportFailed) && assertTrue(!message.contains("Failed to fetch"))
        },
        test("an unreadable answer names the status it arrived with") {
          val message = ErrorMessages.viewFailed(RequestFailure.Undecodable(StatusCode.BadGateway))
          assertTrue(message == ErrorMessages.undecodableAnswer(StatusCode.BadGateway), message.contains("502"))
        },
        // The two are told apart because only one of them can promise that
        // nothing was changed. An unreadable answer reached the server, so a
        // write may have taken effect, and claiming otherwise would be false
        // exactly when it matters most.
        test("an unreadable answer does not claim that nothing was changed") {
          val undecodable = ErrorMessages.undecodableAnswer(StatusCode.InternalServerError)
          assertTrue(
            ErrorMessages.TransportFailed.contains("Nothing was changed"),
            !undecodable.contains("Nothing was changed"),
            undecodable != ErrorMessages.TransportFailed
          )
        },
        test("the banner's sentence for a lost connection is not the action's sentence") {
          assertTrue(ErrorMessages.ConnectionLost != ErrorMessages.TransportFailed)
        }
      )
    )

end GlobalErrorSpec
