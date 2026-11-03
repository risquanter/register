package app.views

import zio.test.*
import zio.prelude.Validation

import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import app.state.{DistributionMode, ParentSelection, PortfolioField, PortfolioFormState, RiskLeafField, RiskLeafFormState, SubmitRefusals, SummaryEntry}
import com.risquanter.register.domain.data.iron.{NodeId, ValidationMessages}
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, FieldProblem, RequestHeaderName, RequestQueryParam, SubmittedCollection, ValidationError
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

  /** A submit refusal at `location`. The problem is not what is under test:
    * each stands for one refusal and is chosen only so the sentences shown can
    * be told apart. */
  private def refusal(location: ErrorLocation, problem: FieldProblem): ValidationError =
    ValidationError(location, problem)

  private val twoRoots: FieldProblem        = FieldProblem.MultipleRoots
  private val seedInUse: FieldProblem       = FieldProblem.SeedVarIdImmutable
  private val rangeRefused: FieldProblem    = FieldProblem.QuantilesNotIncreasing
  private val nameTaken: FieldProblem       = FieldProblem.StoredNodeListedTwice
  private val nameReserved: FieldProblem    = FieldProblem.ScenarioNameReserved
  private val fitRefused: FieldProblem      = FieldProblem.MetalogFitFailed
  private val termsOutOfRange: FieldProblem = FieldProblem.TermsAboveAnchorCount

  /** The sentence a form shows for a problem. */
  private def sentence(problem: FieldProblem): String = ValidationMessages.render(problem)

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
      test("a query parameter refusal has no target in either form") {
        val at = ErrorLocation.QueryParameter(RequestQueryParam.At)
        assertTrue(
          RiskLeafFormState.inputsFor(at).isEmpty,
          PortfolioFormState.inputsFor(at).isEmpty
        )
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
        state.setSubmitErrors(List(refusal(draft(FieldName.LossRange), rangeRefused)))
        assertTrue(
          current(state.minLossError) == List(sentence(rangeRefused)),
          current(state.maxLossError) == List(sentence(rangeRefused)),
          current(state.formLevelError).isEmpty
        )
      },
      test("a topology refusal is shown on the form's own line") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(ErrorLocation.Whole, twoRoots)))
        assertTrue(current(state.formLevelError) == List(sentence(twoRoots)))
      },
      test("a refusal with no target is shown on the form's own line") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(draft(FieldName.SeedVarId), seedInUse)))
        assertTrue(
          current(state.unclaimed).map(e => sentence(e.problem)) == List(sentence(seedInUse)),
          current(state.formLevelError) == List(sentence(seedInUse))
        )
      },
      test("every refusal is kept: two naming one input, three on the form's line") {
        val state = new RiskLeafFormState
        state.nameVar.set("Fraud")
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(draft(FieldName.Name), nameTaken),
          refusal(draft(FieldName.Name), nameReserved),
          refusal(ErrorLocation.Whole, twoRoots),
          refusal(draft(FieldName.DistributionType), fitRefused),
          refusal(draft(FieldName.SeedVarId), seedInUse)
        ))
        assertTrue(
          current(state.nameError) == List(sentence(nameTaken), sentence(nameReserved)),
          current(state.formLevelError) == List(sentence(twoRoots), sentence(fitRefused), sentence(seedInUse))
        )
      },
      test("the form's own check is shown in place of a stored refusal") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(draft(FieldName.Name), nameTaken)))
        val shown = current(state.nameError)
        // The name is blank, so the form's own check refuses it.
        assertTrue(shown.size == 1, !shown.contains(sentence(nameTaken)))
      },
      test("a stored refusal is not shown until its input is touched or the form submitted") {
        val state = new RiskLeafFormState
        state.nameVar.set("Fraud")
        state.setSubmitErrors(List(refusal(draft(FieldName.Name), nameTaken)))
        val before = current(state.nameError)
        state.markTouched(RiskLeafField.Name)
        assertTrue(before.isEmpty, current(state.nameError) == List(sentence(nameTaken)))
      },
      test("an edit drops the refusals naming that input and keeps the rest") {
        val state = new RiskLeafFormState
        state.nameVar.set("Fraud")
        state.distributionModeVar.set(DistributionMode.Lognormal)
        state.minLossVar.set("1000")
        state.maxLossVar.set("50000")
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(draft(FieldName.Name), nameTaken),
          refusal(draft(FieldName.LossRange), rangeRefused)
        ))
        state.clearFor(RiskLeafField.Name)
        val afterName = (current(state.nameError), current(state.minLossError), current(state.maxLossError))
        // Editing either bound can satisfy the pair rule, so it drops from both.
        state.clearFor(RiskLeafField.MinLoss)
        assertTrue(
          afterName == (Nil, List(sentence(rangeRefused)), List(sentence(rangeRefused))),
          current(state.minLossError).isEmpty,
          current(state.maxLossError).isEmpty
        )
      },
      test("an edit of the terms input drops the refusal naming it") {
        val state = new RiskLeafFormState
        state.distributionModeVar.set(DistributionMode.Metalog)
        state.percentilesVar.set("10, 50, 90")
        state.quantilesVar.set("1000, 5000, 25000")
        state.termsVar.set("3")
        state.triggerValidation()
        state.setSubmitErrors(List(refusal(draft(FieldName.Terms), termsOutOfRange)))
        val before = current(state.termsError)
        state.clearFor(RiskLeafField.Terms)
        assertTrue(before == List(sentence(termsOutOfRange)), current(state.termsError).isEmpty)
      },
      test("an edit of any input drops the form's own line") {
        val state = new RiskLeafFormState
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(ErrorLocation.Whole, twoRoots),
          refusal(draft(FieldName.SeedVarId), seedInUse)
        ))
        state.clearFor(RiskLeafField.Probability)
        assertTrue(current(state.formLevelError).isEmpty)
      },
      test("the portfolio form shows its topology refusal on its own line and drops it on an edit") {
        val form = new PortfolioFormState
        form.triggerValidation()
        form.setSubmitErrors(List(refusal(ErrorLocation.Whole, twoRoots)))
        val before = current(form.formLevelError)
        form.clearFor(PortfolioField.Parent)
        assertTrue(before == List(sentence(twoRoots)), current(form.formLevelError).isEmpty)
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
    ),

    suite("the summary list")(
      test("lists a pair refusal once, under the first of its inputs") {
        val pair    = refusal(draft(FieldName.LossRange), rangeRefused)
        val summary = SubmitRefusals.summaryOf(
          List(RiskLeafField.MinLoss -> List(pair), RiskLeafField.MaxLoss -> List(pair)),
          Nil
        )
        assertTrue(summary == List(SummaryEntry(Some(RiskLeafField.MinLoss), sentence(rangeRefused))))
      },
      test("lists two inputs' refusals both, even when their sentences read the same") {
        val summary = SubmitRefusals.summaryOf(
          List(
            RiskLeafField.Name        -> List(refusal(draft(FieldName.Name), FieldProblem.Required)),
            RiskLeafField.Probability -> List(refusal(draft(FieldName.Probability), FieldProblem.Required))
          ),
          Nil
        )
        assertTrue(summary == List(
          SummaryEntry(Some(RiskLeafField.Name), sentence(FieldProblem.Required)),
          SummaryEntry(Some(RiskLeafField.Probability), sentence(FieldProblem.Required))
        ))
      },
      test("keeps the inputs' order and puts the form's own line last") {
        val summary = SubmitRefusals.summaryOf(
          List(
            RiskLeafField.Name        -> List(refusal(draft(FieldName.Name), nameTaken)),
            RiskLeafField.Probability -> List(
              refusal(draft(FieldName.Probability), fitRefused),
              refusal(draft(FieldName.Probability), termsOutOfRange)
            )
          ),
          List(refusal(ErrorLocation.Whole, twoRoots))
        )
        assertTrue(summary == List(
          SummaryEntry(Some(RiskLeafField.Name), sentence(nameTaken)),
          SummaryEntry(Some(RiskLeafField.Probability), sentence(fitRefused)),
          SummaryEntry(Some(RiskLeafField.Probability), sentence(termsOutOfRange)),
          SummaryEntry(None, sentence(twoRoots))
        ))
      },
      test("a leaf form's summary is empty before a submit") {
        val state = new RiskLeafFormState
        assertTrue(current(state.summary).isEmpty)
      },
      test("a leaf form's summary matches what its inputs show after a submit") {
        val state = new RiskLeafFormState
        state.distributionModeVar.set(DistributionMode.Lognormal)
        state.minLossVar.set("1000")
        state.maxLossVar.set("50000")
        state.triggerValidation()
        state.setSubmitErrors(List(
          refusal(draft(FieldName.LossRange), rangeRefused),
          refusal(ErrorLocation.Whole, twoRoots)
        ))
        val expected =
          current(state.nameError).map(SummaryEntry(Some(RiskLeafField.Name), _)) ++
          current(state.probabilityError).map(SummaryEntry(Some(RiskLeafField.Probability), _)) ++
          List(
            SummaryEntry(Some(RiskLeafField.MinLoss), sentence(rangeRefused)),
            SummaryEntry(None, sentence(twoRoots))
          )
        assertTrue(current(state.summary) == expected, current(state.nameError).nonEmpty)
      },
      test("a blocked submit lists its reasons in the order the inputs appear") {
        val state = new RiskLeafFormState
        assertTrue(
          current(state.blockingReasons).take(3) == List.fill(3)(sentence(FieldProblem.Required))
        )
      }
    ),

    suite("an unselected parent")(
      test("blocks the submit with the parent's refusal and shows nothing under the dropdown") {
        val form = new PortfolioFormState
        form.nameVar.set("Operations")
        form.parentVar.set(ParentSelection.Unset)
        assertTrue(
          current(form.blockingReasons) == List(sentence(FieldProblem.Required)),
          current(form.parentError).isEmpty
        )
      },
      test("stops blocking once a parent is chosen") {
        val form = new PortfolioFormState
        form.nameVar.set("Operations")
        form.parentVar.set(ParentSelection.Root)
        assertTrue(current(form.blockingReasons).isEmpty)
      }
    )
  )
