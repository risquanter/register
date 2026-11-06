package app.state

import zio.test.*

import app.core.{LocalProblem, RequestFailure, ViewFailure}
import com.risquanter.register.domain.data.iron.{BranchChoice, TelemetryPlaceholders, TraceId}
import com.risquanter.register.http.errors.{NotFoundKind, WireError}

/** Which failures read as a tree absent at the revision the view named, which
  * the views show as nothing to show rather than as an error.
  */
object LoadStateSpec extends ZIOSpecDefault:

  private val trace: TraceId = TelemetryPlaceholders.UnlinkedTrace

  private def notFound(kind: NotFoundKind): ViewFailure =
    RequestFailure.Wire(WireError.NotFound(kind, trace))

  private def absence(state: LoadState[Int]): Option[ViewFailure] = state match
    case LoadState.TreeAbsent(failure) => Some(failure)
    case _                             => None

  def spec = suite("LoadState.TreeAbsent")(
    test("matches a tree not on the branch") {
      val failure = notFound(NotFoundKind.TreeNotOnBranch(BranchChoice.Main))
      assertTrue(absence(LoadState.Failed(failure)) == Some(failure))
    },
    test("matches a tree not at the commit") {
      val failure = notFound(NotFoundKind.TreeNotAtCommit())
      assertTrue(absence(LoadState.Failed(failure)) == Some(failure))
    },
    test("does not match a resource that does not exist at all") {
      assertTrue(absence(LoadState.Failed(notFound(NotFoundKind.Unknown()))).isEmpty)
    },
    test("does not match a failure the browser raised itself") {
      assertTrue(absence(LoadState.Failed(LocalProblem.NoActiveWorkspace)).isEmpty)
    },
    test("does not match a state that has not failed") {
      assertTrue(
        absence(LoadState.Idle).isEmpty,
        absence(LoadState.Loading).isEmpty,
        absence(LoadState.Loaded(1)).isEmpty
      )
    }
  )
