package app.views

import zio.test.*
import sttp.model.StatusCode

import app.components.{FormInputs, MergeMessages}
import app.core.{LocalProblem, RequestFailure}
import com.risquanter.register.domain.data.{RiskLeaf, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, ScenarioName, SeedVarId, TelemetryPlaceholders, TraceId, TreeId}
import com.risquanter.register.domain.errors as E
import com.risquanter.register.http.errors.{RejectionDetail, WireError}
import com.risquanter.register.http.responses.{MergeConflictEntry, TreeLabel, TreeMetadataFields, Versions}
import com.risquanter.register.testutil.TestHelpers.{idStr, lognormalShape, nodeId, treeId as treeIdOf, unsafeGet}

/** Which sentence an inline view error display gets for each kind of failure.
  *
  * `viewFailed` matches every case of `ViewFailure`, so a new case is a compile
  * error there until it has a sentence. What the compiler cannot check is which
  * sentence each case got, and that is what these pin: one case per arm, so a
  * rearranged match that still compiles cannot silently swap two sentences.
  *
  * The companion to this is `GlobalErrorSpec`, which pins which failures the
  * banner also shows as a condition of the session (ADR-019 Pattern 7).
  */
object ErrorMessagesSpec extends ZIOSpecDefault:

  private val trace: TraceId = TelemetryPlaceholders.UnlinkedTrace

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("ErrorMessages")(viewFailedSuite, viewFailedLinesSuite, traceOfSuite, summaryHeadingSuite, mergeConflictSuite)

  // ── merge conflict sentences ──────────────────────────────────────────────

  private val draftA   = ScenarioName.fromString("draft-a").toOption.get
  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private def leaf(leafName: String, probability: Double): RiskNode =
    unsafeGet(RiskLeaf.create(
      id = idStr("fraud"),
      name = leafName,
      probability = probability,
      shape = lognormalShape(1000L, 50000L),
      parentId = Some(nodeId("root")),
      seedVarId = 1L
    ), "leaf")

  private val mergeTree = TreeLabel(treeIdOf("merge-test"), Versions(Some(name("merge-test")), Some(name("merge-test")), Some(name("merge-test"))))

  private def nodeConflict(versions: Versions[RiskNode], fields: List[E.FieldName]): MergeConflictEntry =
    MergeConflictEntry.NodeConflict(mergeTree, nodeId("fraud"), versions, fields)

  private val mergeConflictSuite =
    suite("MergeMessages")(
      test("entryLabel names a node once when both branches agree on its name") {
        val entry = nodeConflict(Versions(Some(leaf("Fraud", 0.1)), Some(leaf("Fraud", 0.6)), Some(leaf("Fraud", 0.3))), List(E.FieldName.Probability))
        assertTrue(MergeMessages.entryLabel(draftA, entry) == "Risk 'Fraud' in 'merge-test'")
      },
      test("entryLabel names a node per branch when main renamed it") {
        val entry = nodeConflict(Versions(Some(leaf("Fraud", 0.1)), Some(leaf("Card fraud", 0.1)), Some(leaf("Fraud", 0.3))), List(E.FieldName.Name, E.FieldName.Probability))
        assertTrue(MergeMessages.entryLabel(draftA, entry) == "Risk 'Fraud' on draft-a, 'Card fraud' on main, in 'merge-test'")
      },
      test("fieldLines shows both branches' probability for a node conflict") {
        val entry = nodeConflict(Versions(Some(leaf("Fraud", 0.1)), Some(leaf("Fraud", 0.6)), Some(leaf("Fraud", 0.3))), List(E.FieldName.Probability))
        assertTrue(MergeMessages.fieldLines(draftA, entry) == List("Probability: 30% on draft-a, 60% on main"))
      },
      test("fieldLines shows both branches' names for a meta conflict, and names other fields only") {
        val seed  = SeedVarId.fromLong(3L).toOption.get
        val entry = MergeConflictEntry.TreeMetadataConflict(
          treeIdOf("merge-test"),
          Versions(
            Some(TreeMetadataFields(name("Ops"), nodeId("root"), seed)),
            Some(TreeMetadataFields(name("Operations"), nodeId("root"), SeedVarId.fromLong(4L).toOption.get)),
            Some(TreeMetadataFields(name("Ops risk"), nodeId("root"), SeedVarId.fromLong(5L).toOption.get))
          ),
          List(E.FieldName.Name, E.FieldName.SeedVarHighWater)
        )
        assertTrue(MergeMessages.fieldLines(draftA, entry) == List(
          "Name: 'Ops risk' on draft-a, 'Operations' on main",
          "Last random-number stream issued"
        ))
      },
      test("presenceLine names the branch that deleted a node and the fields the other changed") {
        val entry = nodeConflict(Versions(Some(leaf("Fraud", 0.1)), None, Some(leaf("Card fraud", 0.3))), List(E.FieldName.Name, E.FieldName.Probability))
        assertTrue(
          MergeMessages.presenceLine(draftA, entry) == Some("Deleted on main. Changed on draft-a: Name, Probability"),
          MergeMessages.fieldLines(draftA, entry).isEmpty
        )
      }
    )

  private val summaryHeadingSuite =
    suite("FormInputs.summaryHeading")(
      test("one problem is counted in the singular") {
        assertTrue(FormInputs.summaryHeading(1) == "1 problem to fix")
      },
      test("several problems are counted in the plural") {
        assertTrue(FormInputs.summaryHeading(3) == "3 problems to fix")
      }
    )

  private val traceOfSuite =
    suite("ErrorMessages.traceOf")(
      test("a fault, a denial and an outage offer their trace, because the user cannot resolve them") {
        assertTrue(
          ErrorMessages.traceOf(RequestFailure.Wire(WireError.InternalFault(trace))) == Some(trace),
          ErrorMessages.traceOf(RequestFailure.Wire(WireError.AccessDenied(trace))) == Some(trace),
          ErrorMessages.traceOf(RequestFailure.Wire(WireError.DependencyUnavailable(trace))) == Some(trace)
        )
      },
      test("a refusal the user can correct offers none") {
        assertTrue(
          ErrorMessages.traceOf(RequestFailure.Wire(WireError.RequestRejected(List(blankName), trace))).isEmpty,
          ErrorMessages.traceOf(RequestFailure.Wire(WireError.NotFound(trace))).isEmpty
        )
      },
      test("a failure with no server answer offers none") {
        assertTrue(
          ErrorMessages.traceOf(RequestFailure.Transport(new RuntimeException("Failed to fetch"))).isEmpty,
          ErrorMessages.traceOf(RequestFailure.Undecodable(StatusCode.BadGateway)).isEmpty,
          ErrorMessages.traceOf(LocalProblem.NoActiveWorkspace).isEmpty
        )
      }
    )

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
      test("a failure the server answered with reads the sentence for its wire case") {
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
