package app.views

import zio.test.*
import zio.prelude.Validation

import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import app.state.{DistributionMode, ParentSelection, PortfolioField, PortfolioFormState, RiskLeafField, RiskLeafFormState}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, RequestHeaderName, SubmittedCollection, ValidationError, ValidationErrorCode
}
import com.risquanter.register.http.requests.{DistributionShapeRequest, RiskLeafDefinitionRequest, RiskTreeDefinitionRequest}
import com.risquanter.register.http.requests.RiskTreeRequests
import com.risquanter.register.testutil.TestHelpers.safeId

/** Which targets each form shows a refusal under.
  *
  * One mapping per form, and between them they decide where every submit-time
  * error lands: under one or more of the form's inputs, on the form's own line,
  * or nowhere in the form when the location names nothing it renders.
  */
object FormErrorRoutingSpec extends ZIOSpecDefault:

  private def draft(field: FieldName): ErrorLocation =
    ErrorLocation.Field(ErrorSubject.DraftNode, field)

  /** A submit refusal at `location`. The code is not what is under test. */
  private def refusal(location: ErrorLocation, message: String): ValidationError =
    ValidationError(location, ValidationErrorCode.INVALID_COMBINATION, message)

  /** Observe a signal's current value under a throwaway owner. */
  private def current[A](signal: Signal[A]): A =
    given owner: ManualOwner = new ManualOwner
    var seen: Option[A] = None
    signal.foreach(a => seen = Some(a))
    owner.killSubscriptions()
    seen.get

  /** Every `FieldName` case, with one representative index for the three
    * indexed cases. */
  private val everyField: List[FieldName] = List(
    FieldName.Id, FieldName.Name, FieldName.ParentId, FieldName.ParentName, FieldName.ChildIds,
    FieldName.RootId, FieldName.Nodes, FieldName.Trees, FieldName.SeedVarHighWater, FieldName.Limit,
    FieldName.Probability, FieldName.SeedVarId, FieldName.SeedEntityId,
    FieldName.DistributionType, FieldName.Terms, FieldName.MinLoss, FieldName.MaxLoss, FieldName.Spec,
    FieldName.Percentiles, FieldName.Quantiles,
    FieldName.percentileAt(0), FieldName.quantileAt(0), FieldName.childAt(0),
    FieldName.LossRange, FieldName.PercentileQuantilePairing,
    FieldName.Mitigations, FieldName.Cap, FieldName.Factor, FieldName.OverrideParams, FieldName.TargetingSource,
    FieldName.Deductible, FieldName.Threshold,
    FieldName.Branch, FieldName.Scenario, FieldName.TreeId, FieldName.At, FieldName.Source,
    FieldName.ToCommit, FieldName.CommitHash, FieldName.ContentHash,
    FieldName.WorkspaceKey, FieldName.WorkspaceKeyHash, FieldName.UserId, FieldName.Email, FieldName.Url,
    FieldName.Token, FieldName.TraceId,
    FieldName.AggregatedLoss, FieldName.MitigatedResult, FieldName.Unknown
  )

  /** The leaf form's assignment for each field it renders a refusal for.
    * Every other field has no target. */
  private val leafTargets: Map[FieldName, Set[RiskLeafField]] = Map(
    FieldName.Name                      -> Set(RiskLeafField.Name),
    FieldName.ParentName                -> Set(RiskLeafField.Parent),
    FieldName.Probability               -> Set(RiskLeafField.Probability),
    FieldName.Percentiles               -> Set(RiskLeafField.Percentiles),
    FieldName.percentileAt(0)           -> Set(RiskLeafField.Percentiles),
    FieldName.Quantiles                 -> Set(RiskLeafField.Quantiles),
    FieldName.quantileAt(0)             -> Set(RiskLeafField.Quantiles),
    FieldName.MinLoss                   -> Set(RiskLeafField.MinLoss),
    FieldName.MaxLoss                   -> Set(RiskLeafField.MaxLoss),
    FieldName.Terms                     -> Set(RiskLeafField.Terms),
    FieldName.LossRange                 -> Set(RiskLeafField.MinLoss, RiskLeafField.MaxLoss),
    FieldName.PercentileQuantilePairing -> Set(RiskLeafField.Percentiles, RiskLeafField.Quantiles),
    FieldName.DistributionType          -> Set(RiskLeafField.FormLevel)
  )

  /** The portfolio form's assignment, on the same terms. */
  private val portfolioTargets: Map[FieldName, Set[PortfolioField]] = Map(
    FieldName.Name       -> Set(PortfolioField.Name),
    FieldName.ParentName -> Set(PortfolioField.Parent)
  )

  def spec = suite("form error routing")(

    suite("PortfolioFormState.inputsFor")(
      test("a name refusal goes under the name input") {
        assertTrue(PortfolioFormState.inputsFor(draft(FieldName.Name)) == Set(PortfolioField.Name))
      },
      test("a parent refusal goes under the parent input") {
        assertTrue(PortfolioFormState.inputsFor(draft(FieldName.ParentName)) == Set(PortfolioField.Parent))
      },
      test("the builder's topology check of this submit goes on the form's own line") {
        assertTrue(PortfolioFormState.inputsFor(ErrorLocation.Whole) == Set(PortfolioField.FormLevel))
      },
      test("a field this form does not render has no target") {
        assertTrue(PortfolioFormState.inputsFor(draft(FieldName.Percentiles)).isEmpty)
      },
      test("the subject does not change the routing") {
        // A topology rule reports at the draft node; a server rejection of the
        // same field reports at the request. Both belong under the same input.
        assertTrue(
          PortfolioFormState.inputsFor(ErrorLocation.requestField(FieldName.Name)) == Set(PortfolioField.Name)
        )
      },
      test("every field is assigned as stated") {
        assertTrue(everyField.forall(f =>
          PortfolioFormState.inputsFor(draft(f)) == portfolioTargets.getOrElse(f, Set.empty)
        ))
      }
    ),

    suite("RiskLeafFormState.inputsFor")(
      test("the name, parent and probability inputs") {
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.Name)) == Set(RiskLeafField.Name)) &&
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.ParentName)) == Set(RiskLeafField.Parent)) &&
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.Probability)) == Set(RiskLeafField.Probability))
      },
      test("the five distribution inputs") {
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.Percentiles)) == Set(RiskLeafField.Percentiles)) &&
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.Quantiles)) == Set(RiskLeafField.Quantiles)) &&
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.MinLoss)) == Set(RiskLeafField.MinLoss)) &&
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.MaxLoss)) == Set(RiskLeafField.MaxLoss)) &&
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.Terms)) == Set(RiskLeafField.Terms))
      },
      test("an element failure shares its collection's input") {
        // The form edits all percentiles as one comma-separated value, so the
        // third percentile being out of range lights the same input as the
        // collection not being strictly increasing.
        assertTrue(
          RiskLeafFormState.inputsFor(draft(FieldName.percentileAt(2))) == Set(RiskLeafField.Percentiles)
        ) &&
        assertTrue(
          RiskLeafFormState.inputsFor(draft(FieldName.quantileAt(2))) == Set(RiskLeafField.Quantiles)
        )
      },
      test("a rule over a pair of inputs goes under both") {
        assertTrue(
          RiskLeafFormState.inputsFor(draft(FieldName.LossRange)) == Set(RiskLeafField.MinLoss, RiskLeafField.MaxLoss),
          RiskLeafFormState.inputsFor(draft(FieldName.PercentileQuantilePairing)) ==
            Set(RiskLeafField.Percentiles, RiskLeafField.Quantiles)
        )
      },
      test("a fit the mode's parameters do not admit goes on the form's own line") {
        assertTrue(RiskLeafFormState.inputsFor(draft(FieldName.DistributionType)) == Set(RiskLeafField.FormLevel))
      },
      test("the builder's topology check of this submit goes on the form's own line") {
        assertTrue(RiskLeafFormState.inputsFor(ErrorLocation.Whole) == Set(RiskLeafField.FormLevel))
      },
      test("a header refusal has no target") {
        assertTrue(RiskLeafFormState.inputsFor(ErrorLocation.Header(RequestHeaderName.Branch)).isEmpty)
      },
      test("an existing node's field routes by field, not by subject") {
        val nodeId = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
        assertTrue(
          RiskLeafFormState.inputsFor(ErrorLocation.nodeField(nodeId, FieldName.MinLoss)) == Set(RiskLeafField.MinLoss)
        )
      },
      test("every field is assigned as stated") {
        assertTrue(everyField.forall(f =>
          RiskLeafFormState.inputsFor(draft(f)) == leafTargets.getOrElse(f, Set.empty)
        ))
      }
    ),

    /** Two refusals from two different checks, stored together.
      *
      * The parent is the one input with no reactive error of its own, so it is
      * the field that would surface on the form's line if the leaf form's
      * mapping did not know it.
      */
    suite("a leaf submitted with two bad fields")(
      test("shows both under their own inputs and leaves the form's line empty") {
        val state = new RiskLeafFormState
        state.nameVar.set("Server outage")
        state.probabilityVar.set("10")
        state.parentVar.set(ParentSelection.Unset)
        state.distributionModeVar.set(DistributionMode.Metalog)
        // Descending, so the strictly-increasing rule refuses the collection.
        state.percentilesVar.set("90, 50, 10")
        state.quantilesVar.set("1000, 5000, 25000")
        state.triggerValidation()

        val errors = state.toDraft match
          case Validation.Failure(_, es) => es.toList
          case Validation.Success(_, _)  => Nil
        state.setSubmitErrors(errors)

        assertTrue(
          errors.map(_.location).toSet == Set(
            ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.ParentName),
            ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.Percentiles)
          ),
          current(state.parentError).nonEmpty,
          current(state.percentilesError).nonEmpty,
          current(state.formLevelError).isEmpty
        )
      }
    ),

    suite("stored submit refusals")(
      test("a refusal over a pair of inputs is shown under both") {
        val state = new RiskLeafFormState
        state.distributionModeVar.set(DistributionMode.Lognormal)
        // Valid bounds, so neither input's own check takes priority over the
        // submitted refusal.
        state.minLossVar.set("1000")
        state.maxLossVar.set("50000")
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(draft(FieldName.LossRange), "range refused")))
        assertTrue(
          current(state.minLossError) == List("range refused"),
          current(state.maxLossError) == List("range refused"),
          current(state.formLevelError).isEmpty
        )
      },
      test("a topology refusal is shown on the form's own line") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(ErrorLocation.Whole, "two roots")))
        assertTrue(current(state.formLevelError) == List("two roots"))
      },
      test("a refusal with no target is shown on the form's own line") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(draft(FieldName.SeedVarId), "seed in use")))
        assertTrue(
          current(state.unclaimed).map(_.message) == List("seed in use"),
          current(state.formLevelError) == List("seed in use")
        )
      },
      test("every refusal is kept: two naming one input, three on the form's line") {
        val state = new RiskLeafFormState
        state.nameVar.set("Fraud")
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(draft(FieldName.Name), "name taken"),
          refusal(draft(FieldName.Name), "name reserved"),
          refusal(ErrorLocation.Whole, "two roots"),
          refusal(draft(FieldName.DistributionType), "fit refused"),
          refusal(draft(FieldName.SeedVarId), "seed in use")
        ))
        assertTrue(
          current(state.nameError) == List("name taken", "name reserved"),
          current(state.formLevelError) == List("two roots", "fit refused", "seed in use")
        )
      },
      test("the form's own check is shown in place of a stored refusal") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(draft(FieldName.Name), "name taken")))
        val shown = current(state.nameError)
        // The name is blank, so the form's own check refuses it.
        assertTrue(shown.size == 1, !shown.contains("name taken"))
      },
      test("a stored refusal is not shown until its input is touched or the form submitted") {
        val state = new RiskLeafFormState
        state.nameVar.set("Fraud")
        state.setSubmitErrors(List(refusal(draft(FieldName.Name), "name taken")))
        val before = current(state.nameError)
        state.markTouched(RiskLeafField.Name)
        assertTrue(before.isEmpty, current(state.nameError) == List("name taken"))
      },
      test("an edit drops the refusals naming that input and keeps the rest") {
        val state = new RiskLeafFormState
        state.nameVar.set("Fraud")
        state.distributionModeVar.set(DistributionMode.Lognormal)
        state.minLossVar.set("1000")
        state.maxLossVar.set("50000")
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(draft(FieldName.Name), "name taken"),
          refusal(draft(FieldName.LossRange), "range refused")
        ))
        state.clearFor(RiskLeafField.Name)
        val afterName = (current(state.nameError), current(state.minLossError), current(state.maxLossError))
        // Editing either bound can satisfy the pair rule, so it drops from both.
        state.clearFor(RiskLeafField.MinLoss)
        assertTrue(
          afterName == (Nil, List("range refused"), List("range refused")),
          current(state.minLossError).isEmpty,
          current(state.maxLossError).isEmpty
        )
      },
      test("an edit of any input drops the form's own line") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(ErrorLocation.Whole, "two roots"),
          refusal(draft(FieldName.SeedVarId), "seed in use")
        ))
        state.clearFor(RiskLeafField.Probability)
        assertTrue(current(state.formLevelError).isEmpty)
      },
      test("the portfolio form shows its topology refusal on its own line and drops it on an edit") {
        val form = new PortfolioFormState
        form.triggerValidation()
        form.setSubmitErrors(List(refusal(ErrorLocation.Whole, "two roots")))
        val before = current(form.formLevelError)
        form.clearFor(PortfolioField.Parent)
        assertTrue(before == List("two roots"), current(form.formLevelError).isEmpty)
      }
    ),

    suite("the browser's check and the server's check agree")(
      test("an out-of-range probability names the same field on both sides") {
        // 150 on the form's 0–100 scale is 1.5 on the wire's 0–1 scale.
        val state = new RiskLeafFormState
        state.nameVar.set("Server outage")
        state.parentVar.set(ParentSelection.Root)
        state.probabilityVar.set("150")
        state.distributionModeVar.set(DistributionMode.Lognormal)
        state.minLossVar.set("1000")
        state.maxLossVar.set("50000")
        val browser = state.toDraft match
          case Validation.Failure(_, es) => es.toList.map(_.location)
          case Validation.Success(_, _)  => Nil

        val request = RiskTreeDefinitionRequest(
          name       = "Tree",
          portfolios = Seq.empty,
          leaves     = Seq(RiskLeafDefinitionRequest(
            name              = "Server outage",
            parentName        = None,
            probability       = 1.5,
            distributionShape = DistributionShapeRequest.Lognormal(minLoss = 1000L, maxLoss = 50000L),
            seedVarId         = None
          ))
        )
        val server = RiskTreeRequests.resolveCreate(request, () => safeId("generated")) match
          case Validation.Failure(_, es) => es.toList.map(_.location)
          case Validation.Success(_, _)  => Nil

        assertTrue(
          browser == List(ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.Probability)),
          server == List(ErrorLocation.Field(
            ErrorSubject.nodeAtIndex(SubmittedCollection.Leaves, 0), FieldName.Probability
          ))
        )
      }
    )
  )
