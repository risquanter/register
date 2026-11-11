package com.risquanter.register.http.errors

import java.time.Duration as JDuration

import zio.json.*
import zio.test.*
import sttp.model.StatusCode
import sttp.tapir.EndpointOutput

import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{BranchChoice, ScenarioName, TelemetryPlaceholders, TraceId, TreeId}
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

  private val treeId: TreeId        = TreeId.fromString("01HQ8K3F7XJ2M4N5P6R7S8T9V1").toOption.get
  private val scenario: ScenarioName.ScenarioName = ScenarioName.fromString("feature-1").toOption.get
  private val trace: TraceId                      = TelemetryPlaceholders.UnlinkedTrace

  /** A ULID is 26 characters of Crockford base32, which is the shape every
    * confidential internal identifier in this codebase takes. The negative
    * lookahead excludes an all-digit run, so the all-zeros placeholder trace
    * every case carries is not read as a leaked identifier; a real ULID carries
    * letters from its timestamp prefix and still matches.
    *
    * A client-facing identifier takes the same shape, so a match is a value to
    * identify rather than a leak on its own. The test below names which case
    * may carry one and which identifier it has to be.
    */
  private val UlidShape = "(?![0-9]{26})[0-9A-HJKMNP-TV-Z]{26}".r

  // ── Every case, built once, so each property below iterates the same set ──

  // Each is annotated with its own case type, because that is the type its
  // codec is declared for — there is deliberately no codec for the enum.
  private val fieldFailure = ValidationError(
    ErrorLocation.requestField(FieldName.Name),
    FieldProblem.Required
  )

  private val requestRejected: WireError.RequestRejected =
    WireError.RequestRejected(List(fieldFailure), trace)
  private val validationRejected: WireError.ValidationRejected =
    WireError.ValidationRejected(treeId, RejectionDetail.Fields(List(fieldFailure)), trace)
  private val queryRejected: WireError.ValidationRejected =
    WireError.ValidationRejected(
      treeId,
      RejectionDetail.Query(QueryProblem.Unparseable("bad syntax", Some(4))),
      trace
    )
  private val notFound: WireError.NotFound = WireError.NotFound(NotFoundKind.Unknown(), trace)
  private val treeNotOnBranch: WireError.NotFound =
    WireError.NotFound(NotFoundKind.TreeNotOnBranch(BranchChoice.Scenario(scenario)), trace)
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
    "RequestRejected"       -> requestRejected.toJson,
    "ValidationRejected"    -> validationRejected.toJson,
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
        test("only the case that names a tree carries a ULID-shaped value, and it is that tree") {
          // A `TreeId` is not confidential: a client already supplies one in
          // the path of every tree-addressed route, so a rejection naming the
          // tree it is about discloses nothing the caller did not send
          // (ADR-036 §4). `WorkspaceId` is the identifier that may never cross;
          // the specs of the sites that build a failure holding one pin that.
          val carryingUlid = encodedCases.collect {
            case (name, json) if UlidShape.findFirstIn(json).isDefined => name
          }
          assertTrue(
            carryingUlid == List("ValidationRejected"),
            UlidShape.findFirstIn(validationRejected.toJson).contains(treeId.value.toString)
          )
        },
      ),
      suite("fromAppError maps every internal leaf type")(
        test("each leaf reaches the case assigned to it") {
          val mapping: List[(AppError, String)] = List(
            // Rejected request. Read with no tree in scope, so the content
            // failures answer at the request level; the suite below reads the
            // same five with a tree in scope.
            ValidationFailed(Nil)                                  -> "RequestRejected",
            // Refused query. A query route always names a tree, so reaching
            // here without one is this service's defect rather than the
            // caller's and answers the opaque fault.
            FolParseFailure("bad", None)                           -> "InternalFault",
            FolUnknownReference(List("x"))                         -> "InternalFault",
            FolBindFailure(List("x"))                              -> "InternalFault",
            FolDomainNotQuantifiable("x")                          -> "InternalFault",
            // Faults
            SimulationFailure("sim-1", new RuntimeException("x"))   -> "InternalFault",
            FolModelValidationFailure(List("x"))                   -> "InternalFault",
            FolEvaluationFailure("x", "bind")                      -> "InternalFault"
          )

          val wrong = mapping.filter { (error, expected) =>
            WireError.fromAppError(error, trace, TreeScope.NoTree).productPrefix != expected
          }
          assertTrue(wrong.isEmpty) && assertTrue(mapping.size == 8)
        },
        test("with a tree in scope, the five content failures name that tree instead") {
          // The same five leaves the table above reads with no tree. The scope
          // is the only thing that differs, so this is what it decides.
          val inTree: List[AppError] = List(
            ValidationFailed(Nil),
            FolParseFailure("bad", None),
            FolUnknownReference(List("x")),
            FolBindFailure(List("x")),
            FolDomainNotQuantifiable("x")
          )
          val projected = inTree.map(e => WireError.fromAppError(e, trace, TreeScope.InTree(treeId)))
          assertTrue(
            projected.forall {
              case WireError.ValidationRejected(t, _, _) => t == treeId
              case _                                     => false
            }
          )
        },
        test("the detail says which kind of content was refused") {
          val fields = WireError.fromAppError(
            ValidationFailed(List(fieldFailure)), trace, TreeScope.InTree(treeId)
          )
          val query = WireError.fromAppError(
            FolParseFailure("bad", Some(4)), trace, TreeScope.InTree(treeId)
          )
          assertTrue(
            fields == WireError.ValidationRejected(
              treeId, RejectionDetail.Fields(List(fieldFailure)), trace
            ),
            query == WireError.ValidationRejected(
              treeId, RejectionDetail.Query(QueryProblem.Unparseable("bad", Some(4))), trace
            )
          )
        }
      ),
      suite("every case round-trips through its own codec")(
        test("a rejected request keeps its entries") {
          assertTrue(requestRejected.toJson.fromJson[WireError.RequestRejected] == Right(requestRejected))
        },
        test("a rejected body keeps its entries") {
          assertTrue(validationRejected.toJson.fromJson[WireError.ValidationRejected] == Right(validationRejected))
        },
        test("a refused query keeps its problem") {
          assertTrue(queryRejected.toJson.fromJson[WireError.ValidationRejected] == Right(queryRejected))
        },
        test("an absent resource keeps its trace") {
          assertTrue(notFound.toJson.fromJson[WireError.NotFound] == Right(notFound))
        },
        test("a tree absent on a branch keeps the branch") {
          assertTrue(treeNotOnBranch.toJson.fromJson[WireError.NotFound] == Right(treeNotOnBranch))
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
        },
        test("every location, field, input, format and problem case the failure shape added round-trips") {
          val added: WireError.RequestRejected = WireError.RequestRejected(
            List(
              ValidationError(ErrorLocation.Field(ErrorSubject.Tree(treeId), FieldName.Ttl), FieldProblem.Unreadable(ExpectedInput.Json)),
              ValidationError(ErrorLocation.requestField(FieldName.IdleTimeout), FieldProblem.Unreadable(ExpectedInput.Interval)),
              ValidationError(ErrorLocation.Whole, FieldProblem.WrongFormat(ValueFormat.StoreSegment)),
              ValidationError(ErrorLocation.Whole, FieldProblem.Query(QueryProblem.Unparseable("bad syntax", Some(4)))),
              ValidationError(ErrorLocation.Whole, FieldProblem.StoredNodeKindDiffers)
            ),
            trace
          )
          assertTrue(added.toJson.fromJson[WireError.RequestRejected] == Right(added))
        },
        test("every absence and capability case the failure shape added round-trips") {
          val absences = List(
            NotFoundKind.StoredValueAbsent(), NotFoundKind.MetadataAbsent(),
            NotFoundKind.NodesAbsent(), NotFoundKind.BranchHeadAbsent()
          ).map(kind => WireError.NotFound(kind, trace): WireError.NotFound)
          val pinned: WireError.NotSupported = WireError.NotSupported(Capability.PinnedReads, trace)
          assertTrue(
            absences.forall(a => a.toJson.fromJson[WireError.NotFound] == Right(a)),
            pinned.toJson.fromJson[WireError.NotSupported] == Right(pinned)
          )
        }
      )
    )

end WireErrorSpec
