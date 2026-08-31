package app.state

import zio.test.*

import com.risquanter.register.domain.errors as E

/** Tests for [[GlobalError.fromThrowable]] — the classification of the shared
  * `AppError` hierarchy (reconstructed by `ErrorResponse.decode`) into the SPA's
  * `GlobalError` banner variants.
  *
  * One case per direct `AppError` sub-trait (`SimError`, `IrminError`,
  * `AuthError`, `FolQueryFailure`) plus a non-`AppError` throwable. The match on
  * the sealed `AppError` in `fromAppError` is compiler-enforced exhaustive, so a
  * new sub-trait without a branch is a compile error rather than a silent
  * `NetworkError`.
  */
object GlobalErrorSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("GlobalError.fromThrowable")(
      test("ValidationFailed (SimError) → GlobalError.ValidationFailed") {
        val errs = List(E.ValidationError("name", E.ValidationErrorCode.INTERNAL_ERROR, "must not be empty"))
        GlobalError.fromThrowable(E.ValidationFailed(errs)) match
          case GlobalError.ValidationFailed(got) => assertTrue(got == errs)
          case other                             => throw MatchError(other)
      },
      test("DataConflict (SimError) → GlobalError.Conflict") {
        GlobalError.fromThrowable(E.DataConflict("duplicate")) match
          case GlobalError.Conflict(m) => assertTrue(m.contains("duplicate"))
          case other                   => throw MatchError(other)
      },
      test("sentinel RepositoryFailure → GlobalError.WorkspaceExpired") {
        val sentinel = E.RepositoryFailure(s"${E.RepositoryFailure.WorkspaceSentinelPrefix}not-found")
        GlobalError.fromThrowable(sentinel) match
          case GlobalError.WorkspaceExpired(m) => assertTrue(m.contains("expired"))
          case other                           => throw MatchError(other)
      },
      test("non-sentinel RepositoryFailure (SimError) → GlobalError.ServerError") {
        GlobalError.fromThrowable(E.RepositoryFailure("disk error")) match
          case GlobalError.ServerError(m) => assertTrue(m.contains("disk error"))
          case other                      => throw MatchError(other)
      },
      test("IrminError → GlobalError.DependencyError") {
        GlobalError.fromThrowable(E.IrminUnavailable("down")) match
          case GlobalError.DependencyError(m) => assertTrue(m.contains("down"))
          case other                          => throw MatchError(other)
      },
      test("AuthError → GlobalError.ServerError") {
        val auth = E.AuthForbidden("user-1", "read", "risk-tree", "tree-1")
        GlobalError.fromThrowable(auth) match
          case GlobalError.ServerError(m) => assertTrue(m.contains("Access denied"))
          case other                      => throw MatchError(other)
      },
      test("FolQueryFailure → GlobalError.ServerError") {
        GlobalError.fromThrowable(E.FolQueryFailure.FolParseFailure("bad syntax", None)) match
          case GlobalError.ServerError(m) => assertTrue(m.contains("bad syntax"))
          case other                      => throw MatchError(other)
      },
      test("non-AppError throwable → GlobalError.NetworkError") {
        GlobalError.fromThrowable(new RuntimeException("connection refused")) match
          case GlobalError.NetworkError(m) => assertTrue(m.contains("connection refused"))
          case other                       => throw MatchError(other)
      }
    )

end GlobalErrorSpec
