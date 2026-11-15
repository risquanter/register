package com.risquanter.register.http.requests

import io.github.iltotore.iron.autoRefine
import zio.test.*
import zio.json.{DecoderOps, EncoderOps}
import zio.prelude.Validation

import com.risquanter.register.domain.data.{
  AbsoluteReplacement, MitigationPrecedence, MitigationSpec, RelativeLikelihood, RelativeSeverity, ResultTransformSpec,
  TransformPipeline
}
import com.risquanter.register.domain.data.iron.ContentHash
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, SubmittedMitigations, ValidationError}
import com.risquanter.register.http.requests.RiskTreeRequests.ResolvedSpec
import com.risquanter.register.testutil.TestHelpers.{
  idStr, lognormalShape, nodeId, residualProbability, retentionFactor, safeId, unsafeGet
}

/** The submitted mitigation effects: each gate refines its fields at the
  * subject it is given, accumulating every refusal, and `fromSpec` is its
  * inverse for every stored case. */
object MitigationRequestsSpec extends ZIOSpecDefault {

  /** The third entry of `newMitigations`, so a location naming it cannot be
    * mistaken for the request-level default. */
  private val subject: ErrorSubject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.NewMitigations, 2)

  private val stamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  private def locationsOf(result: Validation[ValidationError, ?]): List[ErrorLocation] =
    result match
      case Validation.Failure(_, errors) => errors.toList.map(_.location)
      case Validation.Success(_, _)      => Nil

  private def at(field: FieldName): ErrorLocation = ErrorLocation.Field(subject, field)

  private val resultStage: MitigationSpec =
    unsafeGet(
      MitigationSpec.ResultStage.create(
        TransformPipeline(List(
          ResultTransformSpec.CapLosses(1000000L),
          ResultTransformSpec.InsurancePolicy(5000L, 50000L)
        )),
        ErrorLocation.requestField(FieldName.Spec)
      ),
      "resultStage"
    )

  private val relative: MitigationSpec =
    MitigationSpec.LeafRelative(RelativeLikelihood.Scale(retentionFactor(0.3)), RelativeSeverity.ScaleSeverity(retentionFactor(0.6)))

  private val absolute: MitigationSpec.LeafAbsolute =
    MitigationSpec.LeafAbsolute(
      AbsoluteReplacement.Both(residualProbability(0.02), lognormalShape(1000L, 5000L)), nodeId("cyber"), stamp)

  private val relativeRequest: MitigationSpecRequest =
    MitigationSpecRequest.LeafRelative(RelativeLikelihoodRequest.Scale(0.3), RelativeSeverityRequest.ScaleSeverity(0.6))

  private val mintId: RiskTreeRequests.IdGenerator = () => safeId("minted")

  def spec = suite("MitigationRequests")(

    test("a relative statement within range resolves to the complete spec") {
      val submitted = MitigationSpecRequest.LeafRelative(
        RelativeLikelihoodRequest.Scale(0.3), RelativeSeverityRequest.ScaleSeverity(0.6))
      assertTrue(submitted.toSpec(subject) == Validation.succeed(ResolvedSpec.Complete(relative)))
    },

    test("a relative statement reports both out-of-range factors together, each at the subject's Factor") {
      val submitted = MitigationSpecRequest.LeafRelative(
        RelativeLikelihoodRequest.Scale(1.5), RelativeSeverityRequest.ScaleSeverity(1.5))
      assertTrue(locationsOf(submitted.toSpec(subject)) == List(at(FieldName.Factor), at(FieldName.Factor)))
    },

    test("an absolute statement resolves unstamped, naming its anchor") {
      val submitted = MitigationSpecRequest.LeafAbsolute(
        AbsoluteReplacementRequest.Likelihood(0.02), idStr("cyber"), BaseStampChoice.KeepIfUnchanged)
      assertTrue(
        submitted.toSpec(subject) ==
          Validation.succeed(ResolvedSpec.Unstamped(AbsoluteReplacement.Likelihood(residualProbability(0.02)), nodeId("cyber")))
      )
    },

    test("an absolute statement reports its probability and its anchor together, at the subject") {
      val submitted = MitigationSpecRequest.LeafAbsolute(
        AbsoluteReplacementRequest.Likelihood(1.5), "not-an-id", BaseStampChoice.Restamp)
      assertTrue(locationsOf(submitted.toSpec(subject)).toSet == Set(at(FieldName.Probability), at(FieldName.Anchor)))
    },

    test("a result-stage step is refused at the subject's field, and an empty pipeline at the subject's Spec") {
      val badStep = MitigationSpecRequest.ResultStage(List(ResultTransformSpecRequest.CapLosses(0L)))
      val empty   = MitigationSpecRequest.ResultStage(Nil)
      assertTrue(
        locationsOf(badStep.toSpec(subject)) == List(at(FieldName.Cap)),
        locationsOf(empty.toSpec(subject)) == List(at(FieldName.Spec))
      )
    },

    test("an insurance policy reports its deductible and its limit together, at the subject") {
      val submitted = MitigationSpecRequest.ResultStage(List(ResultTransformSpecRequest.InsurancePolicy(-1L, 0L)))
      assertTrue(locationsOf(submitted.toSpec(subject)).toSet == Set(at(FieldName.Deductible), at(FieldName.Limit)))
    },

    test("fromSpec then toSpec returns a relative or result-stage spec unchanged") {
      assertTrue(
        MitigationSpecRequest.fromSpec(relative).toSpec(subject) == Validation.succeed(ResolvedSpec.Complete(relative)),
        MitigationSpecRequest.fromSpec(resultStage).toSpec(subject) == Validation.succeed(ResolvedSpec.Complete(resultStage))
      )
    },

    test("fromSpec on an absolute statement keeps its replacement and anchor, and asks to keep the stamp") {
      val resubmitted = MitigationSpecRequest.fromSpec(absolute)
      assertTrue(
        resubmitted match
          case MitigationSpecRequest.LeafAbsolute(_, _, BaseStampChoice.KeepIfUnchanged) => true
          case _                                                                         => false,
        resubmitted.toSpec(subject) == Validation.succeed(ResolvedSpec.Unstamped(absolute.replacement, absolute.anchor))
      )
    },

    test("a new or kept mitigation named like a reserved query symbol is refused at that entry's Name") {
      val newEntry  = MitigationDefinitionRequest("residual", "leaf(x)", relativeRequest, MitigationPrecedence.overrideFinal)
      val keptEntry = MitigationUpdateRequest(idStr("kept"), "p95", "leaf(x)", relativeRequest, MitigationPrecedence.overrideFinal)
      val newSubject  = ErrorSubject.mitigationAtIndex(SubmittedMitigations.NewMitigations, 0)
      val keptSubject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.Mitigations, 0)
      assertTrue(
        locationsOf(RiskTreeRequests.refineMitigationDefs(Seq(newEntry), mintId)) ==
          List(ErrorLocation.Field(newSubject, FieldName.Name)),
        locationsOf(RiskTreeRequests.refineExistingMitigations(Seq(keptEntry), Nil)) ==
          List(ErrorLocation.Field(keptSubject, FieldName.Name))
      )
    },

    test("a mitigation with an ordinary name refines") {
      val entry = MitigationDefinitionRequest("Backup policy", "leaf(x)", relativeRequest, MitigationPrecedence.overrideFinal)
      assertTrue(
        RiskTreeRequests.refineMitigationDefs(Seq(entry), mintId).toEither.isRight
      )
    },

    test("a stored relative or result-stage spec's JSON decodes as the submission fromSpec gives") {
      assertTrue(
        relative.toJson.fromJson[MitigationSpecRequest] == Right(MitigationSpecRequest.fromSpec(relative)),
        resultStage.toJson.fromJson[MitigationSpecRequest] == Right(MitigationSpecRequest.fromSpec(resultStage))
      )
    }
  )
}
