package com.risquanter.register.domain.errors

import java.time.{Duration, Instant}

import scala.compiletime.testing.typeChecks

import sttp.model.StatusCode
import zio.NonEmptyChunk
import zio.test.*

import com.risquanter.register.auth.{Permission, ResourceRef, ResourceType}
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{
  BranchChoice, CommitHash, NodeId, Revision, ScenarioName, TelemetryPlaceholders, TreeId, UserId,
  ValidationMessages, WorkspaceId
}
import com.risquanter.register.http.errors.{
  Capability, ConflictKind, NotFoundKind, QueryProblem, RejectionDetail, TreeScope, WireError
}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.testutil.TestHelpers.safeId

/** The values derived from a failure's fields: its zone, the log level its zone
  * decides, the log line, the log line's structured fields, and the wire case.
  *
  * Each is derived once from the shape rather than declared per case, so these
  * tests pin the derivation over every zone and every variant. The compiler
  * makes a missing arm of the projection an error; the `toWire` suite pins
  * which wire case each `(variant, zone)` pair reaches. The last suite
  * pins the two variants whose zone type admits one zone only, which the
  * compiler enforces and a test can only show by asking it.
  */
object FailureSpec extends ZIOSpecDefault:

  private val treeId: TreeId           = TreeId(safeId("failure-spec-tree"))
  private val nodeId: NodeId           = NodeId(safeId("failure-spec-node"))
  private val workspaceId: WorkspaceId = WorkspaceId(safeId("failure-spec-ws"))
  private val head: CommitHash         = CommitHash.fromString("a" * 40).toOption.get
  private val user: UserId.Authenticated =
    UserId.fromString("8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf").toOption.get

  private val nameRequired: ValidationError =
    ValidationError(ErrorLocation.requestField(FieldName.Name), FieldProblem.Required)

  private def rejectedAt(zone: TrustZone): Failure =
    Failure.rejected(zone, NonEmptyChunk(nameRequired), Nil)

  private def one[P](problem: P): NonEmptyChunk[(ErrorLocation, P)] =
    NonEmptyChunk((ErrorLocation.Whole, problem))

  private val everyZone: List[TrustZone] = List(
    TrustZone.Client,
    TrustZone.Environment,
    TrustZone.MeshHeader,
    TrustZone.Peer(PeerZone.Store),
    TrustZone.Peer(PeerZone.Database),
    TrustZone.Peer(PeerZone.Authorization),
    TrustZone.Service
  )

  /** One failure of each variant, each at a zone it admits. */
  private val everyVariant: List[Failure] = List(
    rejectedAt(TrustZone.Client),
    Failure.Unanswered(Core(PeerZone.Store, one(OutageMode.ConnectionRefused), Nil)),
    Failure.NotFound(Core(TrustZone.Peer(PeerZone.Store), one(NotFoundKind.MetadataAbsent()), Nil)),
    Failure.Conflict(Core(TrustZone.Client, one(ConflictKind.StaleVersion()), Nil)),
    Failure.AccessDenied(Core(TrustZone.MeshHeader, one(DenialKind.IdentityAbsent), Nil)),
    Failure.Expired(Core(
      TrustZone.Client,
      one(ExpiryDetail(ExpiryCause.Inactivity, Duration.ofHours(72), Duration.ofMinutes(30))),
      Nil)),
    Failure.NotSupported(Core(TrustZone.Environment, one(Capability.PinnedReads), Nil))
  )

  /** One diagnostic of each case, with the log keys each must render. */
  private val everyDiagnostic: List[(Diagnostic, Set[String])] = List(
    Diagnostic.WorkspaceRef(workspaceId)            -> Set("workspace_id"),
    Diagnostic.TreeRef(treeId)                      -> Set("tree_id"),
    Diagnostic.RevisionRead(Revision.At(head))      -> Set("revision"),
    Diagnostic.ExpiredAt(Instant.EPOCH)             -> Set("expired_at"),
    Diagnostic.ExpectedHead(head)                   -> Set("expected_head"),
    Diagnostic.ActualHead(None)                     -> Set("actual_head"),
    Diagnostic.Principal(user, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId)) ->
      Set("user", "permission", "resource_type", "resource_id"),
    Diagnostic.StoragePath(IrminPath.unsafeFrom("risk-trees/a")) -> Set("storage_path"),
    Diagnostic.RawValue("bad segment!")             -> Set("raw_value"),
    Diagnostic.PeerMessage("driver said no")        -> Set("peer_message"),
    Diagnostic.PeerBody("<html>")                   -> Set("peer_body"),
    Diagnostic.GraphQlPath(List("main", "tree"))    -> Set("graphql_path"),
    Diagnostic.StoreRefusal(one(FieldProblem.Required)) -> Set("store_refusal"),
    Diagnostic.KeyUnresolved                        -> Set("event_type", "reason")
  )

  def spec = suite("FailureSpec")(
    suite("origin")(
      test("a failure at Client is the caller's") {
        assertTrue(rejectedAt(TrustZone.Client).origin == FailureOrigin.Caller)
      },
      test("a failure at every other zone is the service's") {
        val others = everyZone.filterNot(_ == TrustZone.Client)
        assertTrue(others.forall(z => rejectedAt(z).origin == FailureOrigin.Service))
      },
      test("an outage is at the peer that did not answer, and is the service's") {
        val outage = Failure.Unanswered(Core(PeerZone.Database, one(OutageMode.Timeout(zio.Duration.fromSeconds(5))), Nil))
        assertTrue(
          outage.zone == TrustZone.Peer(PeerZone.Database),
          outage.origin == FailureOrigin.Service
        )
      }
    ),
    suite("logAnnotations")(
      test("carries trust_zone with a distinct value for every zone") {
        val values = everyZone.map(z => rejectedAt(z).logAnnotations.get("trust_zone"))
        assertTrue(values.forall(_.isDefined), values.distinct.size == everyZone.size)
      },
      test("carries every diagnostic's keys") {
        val failure = Failure.rejected(TrustZone.Client, NonEmptyChunk(nameRequired), everyDiagnostic.map(_._1))
        val keys    = failure.logAnnotations.keySet
        assertTrue(everyDiagnostic.forall((_, expected) => expected.subsetOf(keys)))
      },
      test("each diagnostic renders exactly its own keys") {
        assertTrue(everyDiagnostic.forall((d, expected) => Diagnostic.render(d).map(_._1).toSet == expected))
      },
      test("diagnostics under one key keep every value, in order") {
        val other   = TreeId(safeId("failure-spec-tree-2"))
        val failure = Failure.rejected(TrustZone.Peer(PeerZone.Store), NonEmptyChunk(nameRequired),
                        List(Diagnostic.TreeRef(treeId), Diagnostic.TreeRef(other)))
        assertTrue(failure.logAnnotations.get("tree_id") == Some(s"${treeId.value},${other.value}"))
      },
      test("a store refusal renders each entry's location and problem, in order") {
        val second = ValidationError(ErrorLocation.nodeField(nodeId, FieldName.Probability), FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval))
        val entries = NonEmptyChunk(nameRequired, second).map(e => (e.location, e.problem))
        assertTrue(Diagnostic.render(Diagnostic.StoreRefusal(entries)) ==
          List("store_refusal" -> s"${ValidationError.describe(nameRequired)}; ${ValidationError.describe(second)}"))
      },
      test("an unresolved key renders the resolve-failure marker the workspace lifecycle events are selected with") {
        assertTrue(Diagnostic.render(Diagnostic.KeyUnresolved) ==
          List("event_type" -> "workspace.resolve_failed", "reason" -> "not_found"))
      }
    ),
    suite("logMessage")(
      test("renders every element of a two-element list, in order, after the variant's name") {
        val second  = ValidationError(ErrorLocation.nodeField(nodeId, FieldName.Probability), FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval))
        val failure = Failure.rejected(TrustZone.Client, NonEmptyChunk(nameRequired, second), Nil)
        assertTrue(failure.logMessage == s"Rejected: ${ValidationError.describe(nameRequired)}; ${ValidationError.describe(second)}")
      },
      test("names each variant and renders its problem through that payload's own text") {
        val expected = List(
          "Rejected: [request.Name] A value is required",
          s"Unanswered: [request] ${OutageMode.describe(OutageMode.ConnectionRefused)}",
          s"NotFound: [request] ${NotFoundKind.describe(NotFoundKind.MetadataAbsent())}",
          s"Conflict: [request] ${ConflictKind.describe(ConflictKind.StaleVersion())}",
          s"AccessDenied: [request] ${DenialKind.describe(DenialKind.IdentityAbsent)}",
          "Expired: [request] the idle limit of PT30M has passed",
          s"NotSupported: [request] ${Capability.describe(Capability.PinnedReads)}"
        )
        assertTrue(everyVariant.map(_.logMessage) == expected)
      }
    ),
    suite("payload text")(
      test("every case of every payload has its own sentence") {
        val scenario = ScenarioName.fromString("draft-a").toOption.get
        val sentences: List[List[String]] = List(
          List(OutageMode.ConnectionRefused, OutageMode.Timeout(zio.Duration.fromSeconds(5)),
               OutageMode.Status(StatusCode.BadGateway), OutageMode.Protocol).map(OutageMode.describe),
          DenialKind.values.toList.map(DenialKind.describe),
          List(NotFoundKind.Unknown(), NotFoundKind.TreeNotOnBranch(BranchChoice.Main), NotFoundKind.TreeNotAtCommit(),
               NotFoundKind.StoredValueAbsent(), NotFoundKind.MetadataAbsent(), NotFoundKind.NodesAbsent(),
               NotFoundKind.BranchHeadAbsent()).map(NotFoundKind.describe),
          List(ConflictKind.Rejected(), ConflictKind.StaleVersion(),
               ConflictKind.MergeRefused(scenario, MergeRefusal.BranchesConflict()),
               ConflictKind.MergeAlreadyRunning(scenario), ConflictKind.MergeTargetMoved(scenario),
               ConflictKind.ScenarioHeadStale(scenario)).map(ConflictKind.describe),
          Capability.values.toList.map(Capability.describe),
          List(QueryProblem.Unparseable("m", Some(3)), QueryProblem.UnknownReferences(List("m")),
               QueryProblem.TypeCheckFailed(List("m")), QueryProblem.NotQuantifiable("n")).map(QueryProblem.describe),
          List(ExpiryDetail(ExpiryCause.Lifetime, Duration.ofHours(72), Duration.ofMinutes(30)),
               ExpiryDetail(ExpiryCause.Inactivity, Duration.ofHours(72), Duration.ofMinutes(30))).map(ExpiryDetail.describe),
          List(FieldProblem.Unreadable(ExpectedInput.Json), FieldProblem.Unreadable(ExpectedInput.Interval),
               FieldProblem.WrongFormat(ValueFormat.StoreSegment), FieldProblem.StoredNodeKindDiffers,
               FieldProblem.Query(QueryProblem.NotQuantifiable("n"))).map(ValidationMessages.render)
        )
        assertTrue(sentences.forall(s => s.forall(_.nonEmpty) && s.distinct.size == s.size))
      },
      test("a refused query carries the engine's own sentence") {
        val problem = QueryProblem.Unparseable("unexpected token", Some(7))
        assertTrue(ValidationMessages.render(FieldProblem.Query(problem)) == "unexpected token (at character 7)")
      },
      test("a parser message reaches the log line cut to 300 characters, and a tree's wire answer whole") {
        val message = "x" * 400
        val failure = Failure.rejected(
          TrustZone.Client,
          NonEmptyChunk(ValidationError(ErrorLocation.Whole, FieldProblem.Query(QueryProblem.Unparseable(message, Some(2))))),
          Nil
        )
        val trace = TelemetryPlaceholders.UnlinkedTrace
        assertTrue(
          failure.logMessage ==
            s"Rejected: [${ErrorLocation.describe(ErrorLocation.Whole)}] ${"x" * 300}… (message truncated) (at character 2)",
          failure.toWire(trace, TreeScope.InTree(treeId)) ==
            WireError.ValidationRejected(treeId, RejectionDetail.Query(QueryProblem.Unparseable(message, Some(2))), trace),
          // A query runs only on a route naming a tree, so a refusal with none
          // in scope is this service's defect.
          failure.toWire(trace, TreeScope.NoTree) == WireError.InternalFault(trace)
        )
      },
      test("a refused query is classified by the engine's refusal") {
        assertTrue(
          FieldProblem.code(FieldProblem.Query(QueryProblem.Unparseable("m", None))) == ValidationErrorCode.PARSE_ERROR,
          FieldProblem.code(FieldProblem.Query(QueryProblem.UnknownReferences(Nil))) == ValidationErrorCode.UNKNOWN_REFERENCE,
          FieldProblem.code(FieldProblem.Query(QueryProblem.TypeCheckFailed(Nil))) == ValidationErrorCode.BIND_FAILED,
          FieldProblem.code(FieldProblem.Query(QueryProblem.NotQuantifiable("n"))) == ValidationErrorCode.DOMAIN_NOT_QUANTIFIABLE
        )
      },
      test("a tree subject names the tree") {
        assertTrue(ErrorLocation.describe(ErrorLocation.Field(ErrorSubject.Tree(treeId), FieldName.RootId)) == s"tree ${treeId.value}.RootId")
      }
    ),
    suite("toWire")(
      test("every (variant, zone) pair reaches the wire case assigned to it") {
        val t = TelemetryPlaceholders.UnlinkedTrace
        val opaque = WireError.InternalFault(t)
        def at[P](zone: TrustZone, problem: P): Core[TrustZone, P] = Core(zone, one(problem), Nil)
        val expiry = ExpiryDetail(ExpiryCause.Lifetime, Duration.ofHours(72), Duration.ofMinutes(30))

        val rejectedRows = everyZone.map(z =>
          Failure.Rejected(at(z, FieldProblem.Required)) -> (z match
            case TrustZone.Client => WireError.RequestRejected(List(ValidationError(ErrorLocation.Whole, FieldProblem.Required)), t)
            case _                => opaque))
        val notFoundRows = everyZone.map(z =>
          Failure.NotFound(at(z, NotFoundKind.Unknown())) -> (z match
            case TrustZone.Client => WireError.NotFound(NotFoundKind.Unknown(), t)
            case _                => opaque))
        val conflictRows = everyZone.map(z =>
          Failure.Conflict(at(z, ConflictKind.Rejected())) -> (z match
            case TrustZone.Client => WireError.Conflict(ConflictKind.Rejected(), t)
            case _                => opaque))
        val deniedRows = everyZone.map(z =>
          Failure.AccessDenied(at(z, DenialKind.PermissionNotHeld)) -> (z match
            case TrustZone.Client | TrustZone.MeshHeader | TrustZone.Peer(PeerZone.Authorization) => WireError.AccessDenied(t)
            case _                                                                               => opaque))
        val outageRows = PeerZone.values.toList.map(p =>
          Failure.Unanswered(Core(p, one(OutageMode.Protocol), Nil)) -> WireError.DependencyUnavailable(t))
        val fixedZoneRows = List(
          Failure.Expired(Core(TrustZone.Client, one(expiry), Nil)) ->
            WireError.WorkspaceExpired(ExpiryCause.Lifetime, Duration.ofHours(72), Duration.ofMinutes(30), t),
          Failure.NotSupported(Core(TrustZone.Environment, one(Capability.PinnedReads), Nil)) ->
            WireError.NotSupported(Capability.PinnedReads, t)
        )

        val rows: List[(Failure, WireError)] =
          rejectedRows ++ notFoundRows ++ conflictRows ++ deniedRows ++ outageRows ++ fixedZoneRows
        assertTrue(rows.forall((failure, wire) => failure.toWire(t, TreeScope.NoTree) == wire))
      },
      test("a refusal of the caller's content names the tree in scope, and passes every entry through") {
        val t       = TelemetryPlaceholders.UnlinkedTrace
        val second  = ValidationError(ErrorLocation.nodeField(nodeId, FieldName.Probability), FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval))
        val failure = Failure.rejected(TrustZone.Client, NonEmptyChunk(nameRequired, second), List(Diagnostic.TreeRef(treeId)))
        assertTrue(
          failure.toWire(t, TreeScope.InTree(treeId)) ==
            WireError.ValidationRejected(treeId, RejectionDetail.Fields(List(nameRequired, second)), t),
          failure.toWire(t, TreeScope.NoTree) == WireError.RequestRejected(List(nameRequired, second), t)
        )
      },
      test("one refused query keeps the engine's structure on its tree") {
        val t       = TelemetryPlaceholders.UnlinkedTrace
        val problem = QueryProblem.Unparseable("unexpected token", Some(7))
        val failure = Failure.Rejected(Core(TrustZone.Client, one(FieldProblem.Query(problem)), Nil))
        assertTrue(failure.toWire(t, TreeScope.InTree(treeId)) == WireError.ValidationRejected(treeId, RejectionDetail.Query(problem), t))
      },
      test("a refused query with no tree in scope is this service's defect") {
        val t       = TelemetryPlaceholders.UnlinkedTrace
        val failure = Failure.Rejected(Core(TrustZone.Client, one(FieldProblem.Query(QueryProblem.NotQuantifiable("n"))), Nil))
        assertTrue(failure.toWire(t, TreeScope.NoTree) == WireError.InternalFault(t))
      },
      // An unreachable authorization service answers as a dependency outage, so
      // what a caller sees must not say which dependency failed. If it were
      // distinguishable from a store outage, the answer would disclose that the
      // authorization stack is the part that is down.
      test("an authorization outage is indistinguishable on the wire from a store outage") {
        val t         = TelemetryPlaceholders.UnlinkedTrace
        val authDown  = Failure.Unanswered(Core(PeerZone.Authorization, one(OutageMode.ConnectionRefused), Nil))
        val storeDown = Failure.Unanswered(Core(PeerZone.Store, one(OutageMode.Status(StatusCode.ServiceUnavailable)), Nil))
        assertTrue(authDown.toWire(t, TreeScope.NoTree) == storeDown.toWire(t, TreeScope.NoTree))
      },
      // A denial at the mesh header and one the authorization service decided
      // must not be told apart by the caller, so neither discloses whether the
      // resource exists or which layer refused.
      test("an absent identity header and a permission not held are indistinguishable on the wire") {
        val t          = TelemetryPlaceholders.UnlinkedTrace
        val noIdentity = Failure.AccessDenied(Core(TrustZone.MeshHeader,
          NonEmptyChunk(ErrorLocation.Header(RequestHeaderName.UserId) -> DenialKind.IdentityAbsent), Nil))
        val notHeld    = Failure.AccessDenied(Core(TrustZone.Peer(PeerZone.Authorization),
          one(DenialKind.PermissionNotHeld),
          List(Diagnostic.Principal(user, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId)))))
        assertTrue(
          noIdentity.toWire(t, TreeScope.NoTree) == WireError.AccessDenied(t),
          notHeld.toWire(t, TreeScope.NoTree) == WireError.AccessDenied(t)
        )
      },
      test("a wire case naming one problem carries the first entry's") {
        val t       = TelemetryPlaceholders.UnlinkedTrace
        val failure = Failure.Conflict(Core(
          TrustZone.Client,
          NonEmptyChunk((ErrorLocation.Whole, ConflictKind.StaleVersion()), (ErrorLocation.Whole, ConflictKind.Rejected())),
          Nil))
        assertTrue(failure.toWire(t, TreeScope.NoTree) == WireError.Conflict(ConflictKind.StaleVersion(), t))
      }
    ),
    suite("rejected")(
      test("keeps each refusal's location and problem, in order") {
        val failure = Failure.rejected(TrustZone.Peer(PeerZone.Store), NonEmptyChunk(nameRequired), List(Diagnostic.TreeRef(treeId)))
        assertTrue(failure == Failure.Rejected(Core(
          TrustZone.Peer(PeerZone.Store),
          NonEmptyChunk((nameRequired.location, nameRequired.problem)),
          List(Diagnostic.TreeRef(treeId)))))
      }
    ),
    suite("unanswered")(
      test("is one outage at the peer, located at the whole request, with the diagnostics given") {
        val failure = Failure.unanswered(PeerZone.Authorization, OutageMode.ConnectionRefused, List(Diagnostic.TreeRef(treeId)))
        assertTrue(failure == Failure.Unanswered(Core(
          PeerZone.Authorization,
          one(OutageMode.ConnectionRefused),
          List(Diagnostic.TreeRef(treeId)))))
      }
    ),
    suite("peerMessage")(
      test("carries an exception's own text") {
        assertTrue(Diagnostic.peerMessage(new java.io.IOException("connection reset")) ==
          Diagnostic.PeerMessage("connection reset"))
      },
      test("names the exception when it carries no text") {
        assertTrue(Diagnostic.peerMessage(new java.net.ConnectException()) ==
          Diagnostic.PeerMessage("java.net.ConnectException"))
      }
    ),
    suite("zone types")(
      test("an expiry admits the Client zone and no other") {
        assertTrue(
          typeChecks("Failure.Expired(Core(TrustZone.Client, one(ExpiryDetail(ExpiryCause.Lifetime, Duration.ZERO, Duration.ZERO)), Nil))"),
          !typeChecks("Failure.Expired(Core(TrustZone.Service, one(ExpiryDetail(ExpiryCause.Lifetime, Duration.ZERO, Duration.ZERO)), Nil))")
        )
      },
      test("a missing capability admits the Environment zone and no other") {
        assertTrue(
          typeChecks("Failure.NotSupported(Core(TrustZone.Environment, one(Capability.Scenarios), Nil))"),
          !typeChecks("Failure.NotSupported(Core(TrustZone.Client, one(Capability.Scenarios), Nil))")
        )
      },
      test("an outage admits a peer and no other zone") {
        assertTrue(
          typeChecks("Failure.Unanswered(Core(PeerZone.Store, one(OutageMode.Protocol), Nil))"),
          !typeChecks("Failure.Unanswered(Core(TrustZone.Client, one(OutageMode.Protocol), Nil))")
        )
      }
    )
  )
