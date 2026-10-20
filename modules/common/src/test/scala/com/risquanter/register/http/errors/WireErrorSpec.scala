package com.risquanter.register.http.errors

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

import zio.json.*
import zio.test.*
import sttp.model.StatusCode
import sttp.tapir.EndpointOutput

import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{
  BranchRef,
  CommitHash,
  ScenarioName,
  TelemetryPlaceholders,
  TreeId,
  WorkspaceId
}
import com.risquanter.register.domain.errors.*
import com.risquanter.register.domain.errors.FolQueryFailure.*
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError.given

/** The properties the one-way projection onto the wire depends on.
  *
  * Exhaustive matching already makes a missing arm in `fromAppError` a compile
  * error. These assertions pin what the compiler cannot: which wire case each
  * internal error reaches, which status carries it, and that nothing
  * confidential travels with it.
  */
object WireErrorSpec extends ZIOSpecDefault:

  private object Endpoints extends BaseEndpoint

  private val wsId: WorkspaceId     = WorkspaceId.fromString("01HQ8K3F7XJ2M4N5P6R7S8T9V0").toOption.get
  private val treeId: TreeId        = TreeId.fromString("01HQ8K3F7XJ2M4N5P6R7S8T9V1").toOption.get
  private val commit: CommitHash    = CommitHash.fromString("0123456789abcdef0123456789abcdef01234567").toOption.get
  private val otherCommit: CommitHash = CommitHash.fromString("89abcdef0123456789abcdef0123456789abcdef").toOption.get
  private val scenario              = ScenarioName.fromString("feature-1").toOption.get
  private val branch                = BranchRef.fromString("main").toOption.get
  private val trace                 = TelemetryPlaceholders.UnlinkedTrace

  /** A ULID is 26 characters of Crockford base32, which is the shape every
    * confidential internal identifier in this codebase takes. The negative
    * lookahead excludes an all-digit run, so the all-zeros placeholder trace
    * every case carries is not read as a leaked identifier; a real ULID carries
    * letters from its timestamp prefix and still matches.
    */
  private val UlidShape = "(?![0-9]{26})[0-9A-HJKMNP-TV-Z]{26}".r

  // ── Every case, built once, so each property below iterates the same set ──

  // Each is annotated with its own case type, because that is the type its
  // codec is declared for — there is deliberately no codec for the enum.
  private val validationRejected: WireError.ValidationRejected = WireError.ValidationRejected(
    List(ValidationError("name", ValidationErrorCode.REQUIRED_FIELD, "Name is required")),
    trace
  )
  private val queryRejected: WireError.QueryRejected =
    WireError.QueryRejected(QueryProblem.Unparseable("bad syntax", Some(4)), trace)
  private val notFound: WireError.NotFound = WireError.NotFound(trace)
  private val workspaceExpired: WireError.WorkspaceExpired = WireError.WorkspaceExpired(
    ExpiryCause.Inactivity,
    JDuration.ofHours(72),
    JDuration.ofHours(24),
    trace
  )
  private val accessDenied: WireError.AccessDenied = WireError.AccessDenied(trace)
  private val conflict: WireError.Conflict =
    WireError.Conflict(ConflictKind.MergeAlreadyRunning(scenario), trace)
  private val notSupported: WireError.NotSupported = WireError.NotSupported(Capability.Scenarios, trace)
  private val dependencyDown: WireError.DependencyUnavailable = WireError.DependencyUnavailable(trace)
  private val internalFault: WireError.InternalFault = WireError.InternalFault(trace)

  /** Each case beside its own serialised form, because there is deliberately no
    * codec for the enum as a whole — the status names the case, so a body
    * carries the case's own fields and no discriminator.
    */
  private val encodedCases: List[(String, String)] = List(
    "ValidationRejected"    -> validationRejected.toJson,
    "QueryRejected"         -> queryRejected.toJson,
    "NotFound"              -> notFound.toJson,
    "WorkspaceExpired"      -> workspaceExpired.toJson,
    "AccessDenied"          -> accessDenied.toJson,
    "Conflict"              -> conflict.toJson,
    "NotSupported"          -> notSupported.toJson,
    "DependencyUnavailable" -> dependencyDown.toJson,
    "InternalFault"         -> internalFault.toJson
  )

  /** Collect every fixed status the error output declares, descending through
    * the `Pair` that `errorOut` builds and into each `oneOf` variant, so the
    * assertion reads the real declaration rather than a copy of it kept here.
    */
  private def statusesOf(output: EndpointOutput[?]): List[StatusCode] = output match
    case EndpointOutput.FixedStatusCode(code, _, _) => List(code)
    case EndpointOutput.Pair(left, right, _, _)     => statusesOf(left) ++ statusesOf(right)
    case oneOf: EndpointOutput.OneOf[?, ?]          => oneOf.variants.flatMap(variant => statusesOf(variant.output))
    case _                                          => Nil

  private val declaredStatuses: List[StatusCode] = statusesOf(Endpoints.baseEndpoint.errorOutput)

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("WireError")(
      suite("the status-to-case relation")(
        test("declares one variant per case") {
          assertTrue(declaredStatuses.size == encodedCases.size)
        },
        test("gives no two cases the same status") {
          assertTrue(declaredStatuses.distinct.size == declaredStatuses.size)
        },
        test("declares exactly the nine statuses the projection uses") {
          val expected = Set(
            StatusCode.BadRequest,
            StatusCode.UnprocessableEntity,
            StatusCode.Forbidden,
            StatusCode.NotFound,
            StatusCode.Gone,
            StatusCode.Conflict,
            StatusCode.NotImplemented,
            StatusCode.ServiceUnavailable,
            StatusCode.InternalServerError
          )
          assertTrue(declaredStatuses.toSet == expected)
        }
      ),
      suite("no case carries a confidential identifier")(
        test("no serialised case contains a ULID-shaped value") {
          val offenders = encodedCases.filter((_, json) => UlidShape.findFirstIn(json).isDefined)
          assertTrue(offenders.isEmpty)
        },
        test("an error built from a workspace identifier does not carry it onto the wire") {
          val projected = WireError.fromAppError(TreeNotInWorkspace(wsId, treeId), trace)
          val json      = projected match
            case nf: WireError.NotFound => nf.toJson
            case other                  => throw MatchError(other)
          assertTrue(!json.contains(wsId.value)) && assertTrue(!json.contains(treeId.value))
        }
      ),
      suite("fromAppError maps every internal leaf type")(
        test("each of the 31 leaves reaches the case this plan assigns it") {
          val mapping: List[(AppError, String)] = List(
            // Rejected request
            ValidationFailed(Nil)                                  -> "ValidationRejected",
            // Refused query
            FolParseFailure("bad", None)                           -> "QueryRejected",
            FolUnknownReference(List("x"))                         -> "QueryRejected",
            FolBindFailure(List("x"))                              -> "QueryRejected",
            FolDomainNotQuantifiable("x")                          -> "QueryRejected",
            // Absent, or not the caller's
            WorkspaceNotFound()                                    -> "NotFound",
            TreeNotInWorkspace(wsId, treeId)                       -> "NotFound",
            // Expired
            WorkspaceExpired(wsId, JDuration.ofHours(72), JDuration.ofHours(24), ExpiryCause.Lifetime, Instant.EPOCH)
                                                                   -> "WorkspaceExpired",
            // Refused
            AccessDenied("no")                                     -> "AccessDenied",
            AuthForbidden("u", "read", "tree", "t1")               -> "AccessDenied",
            // Conflicts
            DataConflict("dup")                                    -> "Conflict",
            VersionConflict(treeId, commit, otherCommit)           -> "Conflict",
            MergeConflict(scenario, MergeRefusal.BranchesConflict()) -> "Conflict",
            MergeAlreadyRunning(scenario)                          -> "Conflict",
            MergeTargetMoved(scenario)                             -> "Conflict",
            ScenarioHeadStale(scenario, commit, None)              -> "Conflict",
            SimulationNotCached(treeId)                            -> "Conflict",
            // Capability absent
            ScenariosNotSupported("in-memory")                     -> "NotSupported",
            // Dependency did not answer
            IrminUnavailable("down")                               -> "DependencyUnavailable",
            NetworkTimeout("read", 5.seconds)                      -> "DependencyUnavailable",
            IrminHttpError(StatusCode.BadGateway, "body")          -> "DependencyUnavailable",
            IrminGraphQLError(List("boom"), None)                  -> "DependencyUnavailable",
            AuthServiceUnavailable("down", None)                   -> "DependencyUnavailable",
            // Faults
            BranchAlreadyExists(branch)                            -> "InternalFault",
            BranchHeadStale(branch, commit)                        -> "InternalFault",
            IrminMergeConflict("conflict")                         -> "InternalFault",
            RepositoryFailure("disk")                              -> "InternalFault",
            TreeLoadFailure(treeId, "corrupt")                     -> "InternalFault",
            SimulationFailure("sim-1", new RuntimeException("x"))   -> "InternalFault",
            FolModelValidationFailure(List("x"))                   -> "InternalFault",
            FolEvaluationFailure("x", "bind")                      -> "InternalFault"
          )

          val wrong = mapping.filter { (error, expected) =>
            WireError.fromAppError(error, trace).productPrefix != expected
          }
          assertTrue(wrong.isEmpty) && assertTrue(mapping.size == 31)
        }
      ),
      suite("every case round-trips through its own codec")(
        test("a rejected body keeps its entries") {
          assertTrue(validationRejected.toJson.fromJson[WireError.ValidationRejected] == Right(validationRejected))
        },
        test("a refused query keeps its problem") {
          assertTrue(queryRejected.toJson.fromJson[WireError.QueryRejected] == Right(queryRejected))
        },
        test("an absent resource keeps its trace") {
          assertTrue(notFound.toJson.fromJson[WireError.NotFound] == Right(notFound))
        },
        test("an expired workspace keeps both durations and its cause") {
          assertTrue(workspaceExpired.toJson.fromJson[WireError.WorkspaceExpired] == Right(workspaceExpired))
        },
        test("a denial round-trips") {
          assertTrue(accessDenied.toJson.fromJson[WireError.AccessDenied] == Right(accessDenied))
        },
        test("a conflict keeps its inner case") {
          assertTrue(conflict.toJson.fromJson[WireError.Conflict] == Right(conflict))
        },
        test("an absent capability keeps which capability") {
          assertTrue(notSupported.toJson.fromJson[WireError.NotSupported] == Right(notSupported))
        },
        test("a dependency outage round-trips") {
          assertTrue(dependencyDown.toJson.fromJson[WireError.DependencyUnavailable] == Right(dependencyDown))
        },
        test("an internal fault round-trips") {
          assertTrue(internalFault.toJson.fromJson[WireError.InternalFault] == Right(internalFault))
        }
      ),
      suite("indistinguishable outcomes really are identical")(
        test("both refusal paths serialise to the same bytes") {
          val explicit  = WireError.fromAppError(AccessDenied("policy says no"), trace)
          val forbidden = WireError.fromAppError(AuthForbidden("u", "read", "tree", "t1"), trace)

          val encoded = List(explicit, forbidden).map {
            case denied: WireError.AccessDenied => denied.toJson
            case other                          => throw MatchError(other)
          }
          assertTrue(encoded.distinct.size == 1)
        },
        // An unreachable authorization service answers as a dependency outage,
        // so what a caller sees must not say which dependency failed. If it
        // were distinguishable from an Irmin outage, the answer would disclose
        // that the authorization stack is the part that is down.
        test("an authorization outage is indistinguishable from any other dependency outage") {
          val authDown  = WireError.fromAppError(AuthServiceUnavailable("SpiceDB unreachable", None), trace)
          val storeDown = WireError.fromAppError(IrminUnavailable("connection refused"), trace)

          val encoded = List(authDown, storeDown).map {
            case down: WireError.DependencyUnavailable => down.toJson
            case other                                 => throw MatchError(other)
          }
          assertTrue(encoded.distinct.size == 1)
        },
        test("an absent workspace and a tree outside it serialise to the same bytes") {
          val absentWorkspace = WireError.fromAppError(WorkspaceNotFound(), trace)
          val treeElsewhere   = WireError.fromAppError(TreeNotInWorkspace(wsId, treeId), trace)

          val encoded = List(absentWorkspace, treeElsewhere).map {
            case nf: WireError.NotFound => nf.toJson
            case other                  => throw MatchError(other)
          }
          assertTrue(encoded.distinct.size == 1)
        }
      )
    )

end WireErrorSpec
