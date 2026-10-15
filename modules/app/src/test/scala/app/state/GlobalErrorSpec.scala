package app.state

import java.time.Duration
import zio.test.*
import sttp.model.StatusCode

import app.core.RequestFailure
import app.views.ErrorMessages
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{TelemetryPlaceholders, TraceId}
import com.risquanter.register.domain.errors as E
import com.risquanter.register.http.errors.{Capability, ConflictKind, QueryProblem, WireError}

/** Which banner treatment each failure gets.
  *
  * There is a case per wire case and one per kind of request failure, because
  * `fromWireError` matches the wire cases exhaustively and a new one is a
  * compile error there until it has a treatment. These tests pin which
  * treatment it got, which the compiler cannot check.
  */
object GlobalErrorSpec extends ZIOSpecDefault:

  private val trace: TraceId = TelemetryPlaceholders.UnlinkedTrace

  private def wire(error: WireError): GlobalError =
    GlobalError.from(RequestFailure.Wire(error))

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("GlobalError.from")(
      test("a rejected body keeps its validation entries") {
        val errors = List(
          E.ValidationError("name", E.ValidationErrorCode.INTERNAL_ERROR, "must not be empty")
        )
        wire(WireError.ValidationRejected(errors, trace)) match
          case GlobalError.ValidationFailed(got) => assertTrue(got == errors)
          case other                             => throw MatchError(other)
      },
      test("a refused query is a rejected request, not a server fault") {
        val problem = QueryProblem.Unparseable("unexpected token", Some(7))
        wire(WireError.QueryRejected(problem, trace)) match
          case GlobalError.RequestRejected(message) =>
            assertTrue(message.contains("unexpected token")) && assertTrue(message.contains("7"))
          case other => throw MatchError(other)
      },
      test("an absent resource is its own case and carries no message of its own") {
        assertTrue(wire(WireError.NotFound(trace)) == GlobalError.NotFound)
      },
      test("an expired workspace states the idle limit when inactivity ended it") {
        val expired = WireError.WorkspaceExpired(
          ExpiryCause.Inactivity,
          Duration.ofHours(72),
          Duration.ofHours(24),
          trace
        )
        wire(expired) match
          case GlobalError.WorkspaceExpired(message) =>
            assertTrue(message.contains("24 hours")) && assertTrue(!message.contains("72"))
          case other => throw MatchError(other)
      },
      test("an expired workspace states the absolute lifetime when the ceiling ended it") {
        val expired = WireError.WorkspaceExpired(
          ExpiryCause.Lifetime,
          Duration.ofHours(72),
          Duration.ofHours(24),
          trace
        )
        wire(expired) match
          case GlobalError.WorkspaceExpired(message) =>
            assertTrue(message.contains("72 hours")) && assertTrue(!message.contains("24"))
          case other => throw MatchError(other)
      },
      // A denial carries its trace: the message says what happened, and the
      // trace is what a user can quote when they believe the refusal is wrong.
      test("a denial keeps the trace the response carried") {
        wire(WireError.AccessDenied(trace)) match
          case GlobalError.ServerError(message, got) =>
            assertTrue(message == ErrorMessages.Forbidden) && assertTrue(got.contains(trace))
          case other => throw MatchError(other)
      },
      test("a conflict names the scenario it is about") {
        val conflict = ConflictKind.MergeAlreadyRunning(
          com.risquanter.register.domain.data.iron.ScenarioName.fromString("draft-a").toOption.get
        )
        wire(WireError.Conflict(conflict, trace)) match
          case GlobalError.Conflict(message) => assertTrue(message.contains("draft-a"))
          case other                         => throw MatchError(other)
      },
      test("an absent capability shows no trace") {
        wire(WireError.NotSupported(Capability.Scenarios, trace)) match
          case GlobalError.ServerError(message, got) =>
            assertTrue(message.contains("Scenarios")) && assertTrue(got.isEmpty)
          case other => throw MatchError(other)
      },
      test("a dependency outage carries the trace, because nothing else is actionable") {
        wire(WireError.DependencyUnavailable(trace)) match
          case GlobalError.DependencyError(message, got) =>
            assertTrue(message == ErrorMessages.DependencyDown) && assertTrue(got.contains(trace))
          case other => throw MatchError(other)
      },
      test("an internal fault carries the trace") {
        wire(WireError.InternalFault(trace)) match
          case GlobalError.ServerError(message, got) =>
            assertTrue(message == ErrorMessages.InternalFault) && assertTrue(got.contains(trace))
          case other => throw MatchError(other)
      },
      test("a transport failure does not show the browser's own wording") {
        val failure = RequestFailure.Transport(new RuntimeException("Failed to fetch"))
        GlobalError.from(failure) match
          case GlobalError.NetworkError(message) =>
            assertTrue(message == ErrorMessages.TransportFailed) &&
            assertTrue(!message.contains("Failed to fetch"))
          case other => throw MatchError(other)
      },
      test("an unreadable answer names the status it arrived with") {
        GlobalError.from(RequestFailure.Undecodable(StatusCode.BadGateway)) match
          case GlobalError.ServerError(message, got) =>
            assertTrue(
              message == ErrorMessages.undecodableAnswer(StatusCode.BadGateway),
              message.contains("502"),
              got.isEmpty
            )
          case other => throw MatchError(other)
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
      }
    )

end GlobalErrorSpec
